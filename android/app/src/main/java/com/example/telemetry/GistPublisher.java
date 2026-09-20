package com.example.telemetry;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Canal GitHub Gist: publica la telemetría en un Gist que actúa como base de datos y que la web lee
 * del {@code raw_url} sin servidor propio.
 *
 * <p><b>La regla que evita perder datos: leer antes de escribir.</b> Un {@code PATCH} al Gist
 * reemplaza el contenido del fichero, así que nunca se envía a ciegas. En cada ciclo se hace
 * {@code GET} del Gist, se fusiona lo remoto con lo local por {@code timestamp_ms} y solo entonces se
 * escribe el resultado. Si el {@code GET} falla (sin red, 5xx, rate limit), ese ciclo <b>no escribe
 * nada</b>: la historia sigue a salvo en el Gist y en el histórico local, y el ciclo siguiente
 * reintenta con todo lo pendiente. Escribir sin leer sería justo el fallo que borra el historial de
 * los demás dispositivos.</p>
 *
 * <p><b>Y si el Gist ya estaba corrupto</b> (JSON inválido o vacío), el envío masivo lo reescribe y lo
 * sana: es el único caso en el que se publica sin haber podido interpretar lo remoto, y es seguro
 * porque el histórico local ({@link TelemetryStore}) es la fuente de verdad.</p>
 *
 * <p>El formato es un array de objetos con tres bloques —{@code location}, {@code telemetry} y
 * {@code status}— ordenado del más antiguo al más nuevo, acotado por número de puntos y por tamaño en
 * caracteres para no acercarse al límite de 1 MB por fichero de la API de Gists.</p>
 */
public final class GistPublisher {

    /** Estado del último intento, observable desde la UI. */
    public static volatile String lastStatus = "sin publicar";
    public static volatile long lastOkAt = 0L;
    public static volatile int lastPublished = 0;
    public static volatile int totalPublished = 0;
    public static volatile int failedCount = 0;
    /** Puntos que había en el Gist la última vez que se pudo leer. */
    public static volatile int remoteCount = 0;
    public static volatile String lastError = null;
    /** Instante del punto más nuevo del último envío aceptado (para saber qué queda pendiente). */
    private static volatile long lastPublishedNewestMs = 0L;
    /**
     * Puntos del histórico local, en memoria: evita tocar disco para pintar el estado en la UI y,
     * sobre todo, para normalizar un punto —que ocurre en el hilo principal—. Es atómico porque lo
     * incrementa el hilo del publicador y lo recalcula el de WorkManager.
     */
    private static final AtomicInteger localCount = new AtomicInteger();
    /** Pendientes de publicar, recalculado en cada ciclo (la UI solo lee el número ya calculado). */
    private static final AtomicInteger pendingCache = new AtomicInteger();
    /** Última {@code raw_url} que informó la API del Gist (lectura pública del histórico). */
    private static volatile String lastRawUrl = "";

    private static final String TAG = "GistPublisher";
    private static final String PREFS = "telemetry_prefs";
    private static final String KEY_ENABLED = "gist_enabled";
    private static final String KEY_TOKEN = "gist_token";
    private static final String KEY_ID = "gist_id";
    private static final String KEY_FILE = "gist_file";

    private static final String API_BASE = "https://api.github.com/gists/";
    private static final String API_VERSION = "2022-11-28";
    private static final int TIMEOUT_MS = 20_000;
    private static final int MAX_ATTEMPTS = 2;
    /** GitHub recomienda no abusar de los PATCH: un ciclo cada 20 s son 180 escrituras/hora. */
    private static final long MIN_INTERVAL_MS = 20_000L;
    private static final int MAX_POINTS = 500;
    private static final int MAX_CHARS = 400_000;
    /** Se lee del histórico más de lo que se publica, para poder recortar por tamaño sin quedarse corto. */
    private static final int STORE_WINDOW = 700;

    private static volatile GistPublisher instance;

    private final Context context;
    private final SharedPreferences prefs;
    private volatile ExecutorService io;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile long lastPublishAt = 0L;

    private GistPublisher(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.io = newIo();
    }

    private static ExecutorService newIo() {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "telemetry-gist");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * Ejecutor auto-reparable, por la misma razón que en {@link TelemetryClient}: este publicador es
     * un singleton de proceso y el sistema puede matar y recrear el servicio en el mismo proceso. Un
     * ejecutor muerto dejaría los envíos en {@code RejectedExecutionException} silenciosa.
     */
    private synchronized ExecutorService io() {
        ExecutorService current = io;
        if (current == null || current.isShutdown() || current.isTerminated()) {
            current = newIo();
            io = current;
        }
        return current;
    }

    public static GistPublisher get(Context context) {
        GistPublisher local = instance;
        if (local == null) {
            synchronized (GistPublisher.class) {
                local = instance;
                if (local == null) {
                    local = new GistPublisher(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public void configure(String token, String gistId, String fileName, boolean enabled) {
        prefs.edit()
                .putString(KEY_TOKEN, token == null ? "" : token.trim())
                .putString(KEY_ID, gistId == null ? "" : normalizeId(gistId))
                .putString(KEY_FILE, fileName == null || fileName.trim().isEmpty() ? "data.json" : fileName.trim())
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    /** Acepta tanto el id pelado como una URL de gist completa. */
    private static String normalizeId(String raw) {
        String value = raw.trim();
        int gist = value.lastIndexOf("/gists/");
        if (gist >= 0) {
            value = value.substring(gist + "/gists/".length());
        } else {
            int slash = value.lastIndexOf('/');
            if (slash >= 0) {
                value = value.substring(slash + 1);
            }
        }
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        return value.trim();
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public boolean isConfigured() {
        return !prefs.getString(KEY_TOKEN, "").isEmpty() && !prefs.getString(KEY_ID, "").isEmpty();
    }

    public String getGistId() {
        return prefs.getString(KEY_ID, "");
    }

    public String getFileName() {
        return prefs.getString(KEY_FILE, "data.json");
    }

    /**
     * URL pública de lectura del histórico: es la que se pega en el visor web.
     *
     * <p>Se prefiere la que informa la propia API ({@code raw_url} del {@code GET}), que incluye el
     * usuario y es la autoridad; la construida a mano es solo el respaldo de antes de la primera
     * lectura, porque sin el usuario no se puede componer exactamente.</p>
     */
    public String getRawUrl() {
        String known = lastRawUrl;
        if (!known.isEmpty()) {
            return known;
        }
        String id = getGistId();
        String file = getFileName();
        return id.isEmpty() ? "" : "https://gist.github.com/" + id + "/raw/" + file;
    }

    /**
     * Registra un punto: lo guarda en el histórico local (SQLite) y decide si toca publicar.
     *
     * <p>Todo —incluida la escritura en disco— ocurre en el hilo del publicador, porque las llamadas
     * llegan desde los callbacks de ubicación, que corren en el hilo principal: ahí no se hace I/O
     * nunca. El histórico local solo se mantiene cuando el canal está configurado, de modo que
     * activarlo y desactivarlo no deja rastros innecesarios en el dispositivo.</p>
     */
    public void record(JSONObject gistPoint) {
        if (gistPoint == null || !isEnabled() || !isConfigured()) {
            return;
        }
        final JSONObject point = gistPoint;
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    if (TelemetryStore.get(context).insert(point)) {
                        localCount.incrementAndGet();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "no se pudo guardar en el histórico local: " + t.getMessage());
                }
                long now = SystemClock.elapsedRealtime();
                if (lastPublishAt != 0L && (now - lastPublishAt) < MIN_INTERVAL_MS) {
                    return;   // el histórico ya tiene el punto: se publicará en el siguiente ciclo
                }
                lastPublishAt = now;
                if (!busy.compareAndSet(false, true)) {
                    return;   // ya hay una publicación en curso: esa misma mandará lo pendiente
                }
                try {
                    publishOnce();
                } catch (IOException e) {
                    failFrom(e);
                } catch (JSONException e) {
                    failFrom(e);
                } catch (Throwable t) {
                    failFrom(new IOException(String.valueOf(t.getMessage())));
                } finally {
                    busy.set(false);   // sin esto, el canal se quedaría mudo tras la primera escritura
                }
            }
        };
        try {
            io().execute(task);
        } catch (RejectedExecutionException e) {
            try {
                io().execute(task);
            } catch (RejectedExecutionException retry) {
                Log.w(TAG, "sin ejecutor para registrar el punto: se publicará en el siguiente ciclo");
            }
        }
    }

    /** Publica en el hilo de este publicador (lo llama el servicio: nunca bloquea al llamante). */
    public void publishAsync() {
        if (!isEnabled() || !isConfigured() || !busy.compareAndSet(false, true)) {
            return;
        }
        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    publishOnce();
                } catch (IOException e) {
                    failFrom(e);
                } catch (JSONException e) {
                    failFrom(e);
                } catch (Throwable t) {
                    failFrom(new IOException(String.valueOf(t.getMessage())));
                } finally {
                    busy.set(false);   // el canal debe quedar libre para el siguiente ciclo
                }
            }
        };
        try {
            io().execute(task);
        } catch (RejectedExecutionException e) {
            // Carrera con un shutdown: se reintenta una sola vez con un ejecutor nuevo.
            try {
                io().execute(task);
            } catch (RejectedExecutionException retry) {
                busy.set(false);
            }
        }
    }

    /**
     * Publica de forma síncrona en el hilo que llama. Es la vía para {@code TelemetryWorker}, que ya
     * corre en su propio hilo de trabajo y necesita que la publicación termine antes de devolver su
     * resultado (si devolviera antes, WorkManager podría dar el trabajo por hecho sin que el Gist se
     * haya escrito).
     *
     * @return {@code true} si el Gist quedó escrito.
     */
    public boolean publishNow() {
        if (!isEnabled() || !isConfigured()) {
            return false;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            publishAsync();
            return false;
        }
        if (!busy.compareAndSet(false, true)) {
            return false;   // ya hay una publicación en curso: esa misma escribirá lo pendiente
        }
        try {
            return publishOnce();
        } catch (IOException e) {
            failFrom(e);
            return false;
        } catch (JSONException e) {
            failFrom(e);
            return false;
        } catch (Throwable t) {
            failFrom(new IOException(String.valueOf(t.getMessage())));
            return false;
        } finally {
            busy.set(false);
        }
    }

    /**
     * Puntos guardados en el dispositivo que todavía no viajaron en una publicación correcta.
     *
     * <p>Se compara por instante, no por hora de reloj: {@link #lastPublishedNewestMs} guarda el
     * punto más nuevo del último envío que GitHub aceptó, así que la resta es exacta aunque el
     * reloj del teléfono cambie de zona horaria o el sistema lo ajuste por NTP.</p>
     */
    public int pendingLocal() {
        return pendingCache.get();
    }

    /** Puntos del histórico local según el último recuento (sin tocar disco desde la UI). */
    public int localStored() {
        return localCount.get();
    }

    /** Recalcula los contadores en el hilo del publicador, nunca desde la interfaz. */
    private void refreshCounters() {
        try {
            TelemetryStore store = TelemetryStore.get(context);
            int stored = store.count();
            localCount.set(stored);
            pendingCache.set(lastPublishedNewestMs > 0L ? store.countNewerThan(lastPublishedNewestMs) : stored);
        } catch (Throwable t) {
            Log.w(TAG, "no se pudieron recalcular los contadores: " + t.getMessage());
        }
    }

    /* ------------------------------------------------------------------ flujo de publicación */

    /** @return {@code true} si el Gist quedó escrito con contenido nuevo. */
    private boolean publishOnce() throws IOException, JSONException {
        final String token = prefs.getString(KEY_TOKEN, "");
        final String gistId = prefs.getString(KEY_ID, "");
        final String fileName = getFileName();
        if (token.isEmpty() || gistId.isEmpty()) {
            lastStatus = "gist sin configurar";
            return false;
        }

        // 1) Leer SIEMPRE antes de escribir. Si esto falla, el ciclo termina sin tocar el Gist.
        String remote = fetch(token, gistId, fileName);
        JSONArray remoteArray = parse(remote);
        remoteCount = remoteArray.length();
        refreshCounters();

        // 2) Fusionar: remoto primero (historia acumulada de todos los dispositivos), local después
        //    (en empate de instante gana el punto propio, que trae más detalle).
        JSONArray local = TelemetryStore.get(context).recent(STORE_WINDOW);
        JSONArray payload = buildPayload(local, remoteArray);
        if (payload.length() == 0) {
            lastStatus = "sin puntos que publicar";
            refreshCounters();
            return false;
        }
        String content = payload.toString();

        // 3) Escribir, con un reintento ante errores transitorios o de carrera.
        int code = 0;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            code = patch(token, gistId, fileName, content);
            if (code == HttpURLConnection.HTTP_OK) {
                break;
            }
            if (!transientCode(code)) {
                break;
            }
            sleep(700L * (attempt + 1));
        }

        if (code == HttpURLConnection.HTTP_OK) {
            lastOkAt = System.currentTimeMillis();
            lastPublished = payload.length();
            totalPublished += payload.length();
            lastPublishedNewestMs = TelemetryStore.timestampOf(payload.optJSONObject(payload.length() - 1));
            refreshCounters();
            lastError = null;
            lastStatus = "ok · " + payload.length() + "/" + MAX_POINTS + " puntos · remoto " + remoteCount;
            Log.i(TAG, "gist actualizado con " + payload.length() + " puntos (" + content.length() + " caracteres)");
            return true;
        }

        failedCount++;
        lastStatus = "http=" + code;
        lastError = describe(code);
        Log.w(TAG, "no se pudo escribir el gist: " + lastStatus + " " + lastError);
        // Un 404 o un 401 no se arreglan reintentando: se dice tal cual para poder corregirlo en la
        // app, y se deja constancia en error.json porque es algo que el usuario debe arreglar. Los
        // códigos transitorios (502/503/429) son meteorología de red y no ensucian el registro.
        if (!transientCode(code)) {
            ErrorLogger.record("GistPublisher", "escritura rechazada: " + lastStatus + " · " + lastError);
        }
        return false;
    }

    private static boolean transientCode(int code) {
        return code == 502 || code == 503 || code == 504 || code == 409 || code == 429;
    }

    private static String describe(int code) {
        if (code == 401 || code == 403) {
            return "token rechazado o rate limit alcanzado: revisa el PAT y su caducidad";
        }
        if (code == 404) {
            return "gist no encontrado: revisa el ID (un error de un carácter basta)";
        }
        if (code == 422) {
            return "el contenido fue rechazado: ¿el fichero supera 1 MB?";
        }
        if (code <= 0) {
            return "sin conexión";
        }
        return "respuesta inesperada del API de Gists";
    }

    private void failFrom(Exception e) {
        failedCount++;
        String message = e == null ? "error desconocido" : String.valueOf(e.getMessage());
        lastStatus = "error: " + message;
        lastError = message;
        Log.w(TAG, "publicación en gist fallida: " + message);
        ErrorLogger.record("GistPublisher", message, e);
    }

    /**
     * Fusiona por {@code timestamp_ms} en un {@link TreeMap}: la clave única es el instante, así que
     * un mismo momento nunca aparece dos veces y el orden cronológico sale gratis. El recorte va del
     * extremo antiguo hacia atrás y respeta dos límites a la vez: número de puntos y caracteres
     * serializados (el límite de la API es 1 MB por fichero).
     */
    static JSONArray buildPayload(JSONArray local, JSONArray remote) {
        TreeMap<Long, JSONObject> byTimestamp = new TreeMap<>();
        addAll(byTimestamp, remote);
        addAll(byTimestamp, local);

        JSONArray newestFirst = new JSONArray();
        long budget = MAX_CHARS;
        Iterator<JSONObject> it = byTimestamp.descendingMap().values().iterator();
        while (it.hasNext() && newestFirst.length() < MAX_POINTS) {
            JSONObject point = it.next();
            String serialized = point.toString();
            if (serialized.length() + 1 > budget) {
                break;   // no cabe: se descarta este y todos los más antiguos
            }
            budget -= serialized.length() + 1;
            newestFirst.put(point);
        }

        // Se devuelve del más antiguo al más nuevo, que es como se lee una traza.
        JSONArray out = new JSONArray();
        for (int i = newestFirst.length() - 1; i >= 0; i--) {
            JSONObject point = newestFirst.optJSONObject(i);
            if (point != null) {
                out.put(point);
            }
        }
        return out;
    }

    private static void addAll(TreeMap<Long, JSONObject> target, JSONArray source) {
        if (source == null) {
            return;
        }
        for (int i = 0; i < source.length(); i++) {
            JSONObject point = source.optJSONObject(i);
            if (point == null) {
                continue;
            }
            long ts = TelemetryStore.timestampOf(point);
            if (ts > 0L) {
                target.put(ts, point);
            }
        }
    }

    /** Tolerante a propósito: un fichero corrupto o vacío se trata como «sin historia» y se sanea. */
    private static JSONArray parse(String raw) {
        if (raw == null) {
            return new JSONArray();
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return new JSONArray();
        }
        try {
            Object parsed = new org.json.JSONTokener(text).nextValue();
            if (parsed instanceof JSONArray) {
                return (JSONArray) parsed;
            }
            if (parsed instanceof JSONObject) {
                // Un objeto suelto (versión antigua del visor) también se acepta y se convierte.
                JSONArray array = new JSONArray();
                array.put((JSONObject) parsed);
                return array;
            }
        } catch (Exception e) {
            Log.w(TAG, "el fichero remoto no es JSON válido: se reescribirá desde el histórico local");
        }
        return new JSONArray();
    }

    private String fetch(String token, String gistId, String fileName) throws IOException {
        HttpURLConnection connection = open("GET", API_BASE + gistId, token, false);
        try {
            int code = connection.getResponseCode();
            String body = read(connection, code, 2_000_000);
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new IOException("gist no encontrado (404): revisa el ID");
            }
            if (code == 401 || code == 403) {
                throw new IOException("token rechazado o rate limit (http=" + code + ")");
            }
            if (code < 200 || code >= 300) {
                throw new IOException("GET del gist: http=" + code);
            }
            try {
                JSONObject gist = new JSONObject(body);
                JSONObject files = gist.optJSONObject("files");
                if (files == null || files.length() == 0) {
                    return "";
                }
                JSONObject file = files.optJSONObject(fileName);
                if (file == null) {
                    // El nombre configurado no existe: se adopta el único fichero que haya.
                    Iterator<String> names = files.keys();
                    if (names.hasNext()) {
                        file = files.optJSONObject(names.next());
                    }
                }
                if (file == null) {
                    return "";
                }
                String reportedRaw = file.optString("raw_url", "");
                if (!reportedRaw.isEmpty()) {
                    lastRawUrl = reportedRaw;   // URL pública real, con usuario incluido
                }
                if (file.optBoolean("truncated", false)) {
                    // La API no devuelve el contenido de ficheros grandes: se usa la URL cruda.
                    String rawUrl = file.optString("raw_url", "");
                    if (rawUrl.isEmpty()) {
                        return "";
                    }
                    return fetchRaw(rawUrl, token);
                }
                return file.optString("content", "");
            } catch (JSONException e) {
                throw new IOException("respuesta del gist ilegible: " + e.getMessage());
            }
        } finally {
            connection.disconnect();
        }
    }

    private String fetchRaw(String rawUrl, String token) throws IOException {
        HttpURLConnection connection = open("GET", rawUrl, token, false);
        try {
            int code = connection.getResponseCode();
            String body = read(connection, code, 2_000_000);
            if (code < 200 || code >= 300) {
                throw new IOException("GET del contenido crudo: http=" + code);
            }
            return body;
        } finally {
            connection.disconnect();
        }
    }

    private int patch(String token, String gistId, String fileName, String content) throws IOException {
        JSONObject files = new JSONObject();
        JSONObject file = new JSONObject();
        JSONObject payload = new JSONObject();
        try {
            file.put("content", content);
            files.put(fileName, file);
            payload.put("files", files);
            payload.put("description", "Telemetría P2P · histórico de ubicación (última escritura "
                    + System.currentTimeMillis() / 1000L + ")");
        } catch (JSONException e) {
            throw new IOException("no se pudo serializar el cuerpo: " + e.getMessage());
        }

        byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open("PATCH", API_BASE + gistId, token, true);
        try {
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(bodyBytes.length);
            OutputStream out = new BufferedOutputStream(connection.getOutputStream(), 16_384);
            try {
                out.write(bodyBytes);
                out.flush();
            } finally {
                closeQuietly(out);
            }
            int code = connection.getResponseCode();
            String response = read(connection, code, 8_192);
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "PATCH " + code + " " + response);
            }
            return code;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Conexión lista para usar. {@code PATCH} es un método estándar para {@code HttpURLConnection}
     * desde Android 7 (el cliente HTTP del sistema es OkHttp), así que no hace falta ningún truco.
     */
    private HttpURLConnection open(String method, String url, String token, boolean output) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setDoOutput(output);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", API_VERSION);
        connection.setRequestProperty("User-Agent", "TelemetryGistClient/1.0 (Android " + Build.VERSION.SDK_INT + ")");
        return connection;
    }

    private static String read(HttpURLConnection connection, int code, int maxBytes) {
        InputStream in = null;
        try {
            in = (code >= 200 && code < 300) ? connection.getInputStream() : connection.getErrorStream();
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(4_096);
            byte[] chunk = new byte[8_192];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > maxBytes) {
                    break;
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toString("UTF-8");
        } catch (IOException e) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // cierre best-effort
        }
    }

    /* ------------------------------------------------------------------ normalización */

    /**
     * Traduce el punto interno (plano, con decenas de campos) al esquema del Gist:
     * {@code location} + {@code telemetry} + {@code status}.
     *
     * <p>Los tres bloques y sus claves obligatorias están siempre presentes —el visor nunca tiene que
     * defenderse de un campo ausente— y los campos extra del punto original (GNSS, sensores, calidad)
     * se añaden dentro de su bloque para que la traza siga siendo auditable. Las magnitudes que el
     * sensor puede no haber entregado van con el centinela {@code -1} (el mismo que usan las filas
     * compactas del proyecto) y el visor lo interpreta como «desconocido».</p>
     */
    public static JSONObject toGistPoint(Context context, JSONObject source) {
        if (source == null) {
            return null;
        }
        try {
            JSONObject location = new JSONObject();
            double lat = source.optDouble("lat", Double.NaN);
            double lng = source.optDouble("lng", Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lng)) {
                return null;
            }
            location.put("latitude", lat);
            location.put("longitude", lng);

            String altitudeSource = "gps";
            double altitude = source.optDouble("altitude", Double.NaN);
            if (source.has("altitude_msl")) {
                altitude = source.optDouble("altitude_msl", altitude);
                altitudeSource = "msl";
            } else if (source.has("altitude_baro")) {
                altitude = source.optDouble("altitude_baro", altitude);
                altitudeSource = "barometric";
            }
            location.put("altitude", Double.isNaN(altitude) ? JSONObject.NULL : round1(altitude));
            location.put("altitude_source", altitudeSource);

            location.put("accuracy_meters", round1(source.optDouble("accuracy", -1)));
            location.put("vertical_accuracy_meters", round1(source.optDouble("vertical_accuracy", -1)));
            double speedMps = source.optDouble("speed_mps", -1);
            location.put("speed_mps", round1(speedMps));
            location.put("speed_kmh", speedMps < 0 ? JSONObject.NULL : round1(speedMps * 3.6));
            double bearing = source.has("heading_true")
                    ? source.optDouble("heading_true", -1)
                    : source.optDouble("bearing", -1);
            location.put("bearing_degrees", round1(bearing));
            location.put("heading_magnetic_degrees", round1(source.optDouble("heading_magnetic", -1)));
            location.put("declination_degrees", round1(source.optDouble("declination_deg", 0)));
            String provider = source.optString("provider", "unknown");
            location.put("provider", provider);
            location.put("mock", source.optBoolean("mock", false));
            location.put("smooth_latitude", source.has("smooth_lat")
                    ? source.optDouble("smooth_lat") : JSONObject.NULL);
            location.put("smooth_longitude", source.has("smooth_lng")
                    ? source.optDouble("smooth_lng") : JSONObject.NULL);

            JSONObject gnss = source.optJSONObject("gnss");
            if (gnss != null) {
                location.put("satellites_used", gnss.optInt("used", 0));
                location.put("satellites_visible", gnss.optInt("visible", 0));
                location.put("snr_best", gnss.optDouble("snr_best", 0));
                location.put("constellations", gnss.optString("constellations", ""));
            }
            JSONArray cells = source.optJSONArray("cell");
            JSONArray wifi = source.optJSONArray("wifi");
            location.put("cells", cells == null ? 0 : cells.length());
            location.put("wifi_aps", wifi == null ? 0 : wifi.length());

            JSONObject telemetry = new JSONObject();
            long timestamp = source.optLong("timestamp", System.currentTimeMillis());
            telemetry.put("timestamp_ms", timestamp);
            telemetry.put("elapsed_nanos", source.optLong("elapsed_nanos", 0L));
            telemetry.put("battery_level", source.optInt("battery", -1));
            telemetry.put("is_charging", source.has("charging")
                    ? source.optBoolean("charging", false) : batteryIsCharging(context));
            String network = source.optString("network", "unknown");
            String dataNetwork = source.optString("data_network", "");
            telemetry.put("network_type", network);
            telemetry.put("data_network", dataNetwork);
            telemetry.put("network_label", networkLabel(network, dataNetwork));
            telemetry.put("operator", source.optString("operator", ""));
            telemetry.put("activity", source.optString("activity", "unknown"));
            telemetry.put("steps", source.optInt("steps", -1));
            telemetry.put("device_id", source.optString("device_id", ""));
            telemetry.put("label", source.optString("label", ""));
            telemetry.put("model", source.optString("model", Build.MODEL));
            telemetry.put("sdk", source.optInt("sdk", Build.VERSION.SDK_INT));
            telemetry.put("low_ram", source.optBoolean("low_ram", false));
            telemetry.put("source", source.optString("source", "android"));

            JSONObject status = new JSONObject();
            status.put("is_tracking", Watchdog.isTrackingEnabled(context));
            status.put("fallback_active", isFallback(provider, source.optBoolean("mock", false)));
            status.put("last_error", lastError == null ? JSONObject.NULL : lastError);
            // Contadores en memoria a propósito: esta función corre en el hilo principal (los
            // callbacks de ubicación), así que aquí no se consulta SQLite ni se toca el disco.
            status.put("local_points", localCount.get());
            status.put("remote_points", remoteCount);
            status.put("gist_configured", configuredNow(context));

            JSONObject point = new JSONObject();
            point.put("location", location);
            point.put("telemetry", telemetry);
            point.put("status", status);
            return point;
        } catch (JSONException e) {
            Log.w(TAG, "punto no normalizable: " + e.getMessage());
            return null;
        }
    }

    /** Configuración efectiva sin depender de que exista la instancia (se usa al normalizar). */
    private static boolean configuredNow(Context context) {
        GistPublisher publisher = instance;
        if (publisher != null) {
            return publisher.isConfigured();
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return !prefs.getString(KEY_TOKEN, "").isEmpty() && !prefs.getString(KEY_ID, "").isEmpty();
    }

    /**
     * Un punto es de respaldo cuando no viene del motor principal (GPS o fusionado): red, pasivo,
     * último conocido o una medida marcada como simulada.
     */
    private static boolean isFallback(String provider, boolean mock) {
        if (mock) {
            return true;
        }
        String value = provider == null ? "" : provider.toLowerCase();
        return !(value.contains("gps") || value.contains("fused"));
    }

    private static String networkLabel(String network, String dataNetwork) {
        if (network == null || network.isEmpty()) {
            return "UNKNOWN";
        }
        String base = network.toUpperCase();
        if (dataNetwork == null || dataNetwork.isEmpty()) {
            return base;
        }
        return base + "_" + dataNetwork.toUpperCase();
    }

    private static boolean batteryIsCharging(Context context) {
        try {
            BatteryManager manager = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
            return manager != null && manager.isCharging();
        } catch (Exception e) {
            return false;
        }
    }

    private static double round1(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return -1;
        }
        return Math.round(value * 10.0) / 10.0;
    }
}
