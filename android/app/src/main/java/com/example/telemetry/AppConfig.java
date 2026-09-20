package com.example.telemetry;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Dueño único de la configuración del cliente.
 *
 * <p>Reúne lo que antes estaba repartido entre la pantalla y cada publicador: leer los valores
 * guardados, exportarlos a un JSON legible, importar un JSON escrito a mano y aplicar el resultado
 * a los tres canales en caliente. El objetivo es no volver a escribir campo por campo lo que ya
 * viene en un fichero.</p>
 *
 * <p><b>Las claves de preferencias no cambian.</b> Son las mismas cadenas que leen
 * {@link TelemetryClient}, {@link GithubPublisher}, {@link GistPublisher} y {@link Watchdog}; este
 * fichero solo las centraliza para poder escribirlas en bloque.</p>
 *
 * <p>El importador es <b>tolerante a propósito</b>: acepta el objeto directamente o dentro de una
 * clave {@code config}, acepta bloques anidados ({@code server}, {@code device}, {@code tracking},
 * {@code github}, {@code gist}, {@code security}) o las claves planas de la aplicación, ignora lo
 * que no entiende y explica por qué. Un fichero a medio escribir nunca deja la app sin arrancar.</p>
 */
public final class AppConfig {

    private static final String TAG = "AppConfig";

    /** Almacén compartido por toda la aplicación. */
    public static final String PREFS = "telemetry_prefs";

    /* Claves reales del almacén (no renombrar: las usan los demás componentes). */
    public static final String KEY_ENDPOINT = "endpoint";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_DEVICE_ID = "device_id";
    public static final String KEY_LABEL = "label";
    public static final String KEY_MIN_TIME_MS = "min_time_ms";
    public static final String KEY_MIN_DISTANCE_M = "min_distance_m";
    public static final String KEY_GH_TOKEN = "gh_token";
    public static final String KEY_GH_REPO = "gh_repo";
    public static final String KEY_GH_BRANCH = "gh_branch";
    public static final String KEY_GH_PATH = "gh_path";
    public static final String KEY_GIST_TOKEN = "gist_token";
    public static final String KEY_GIST_ID = "gist_id";
    public static final String KEY_GIST_FILE = "gist_file";
    /** Huella del último fichero de configuración aplicado (evita reaplicar lo mismo). */
    private static final String KEY_APPLIED_HASH = "config_applied_hash";

    /* Valores por defecto, los mismos que muestra la pantalla. */
    public static final String DEFAULT_ENDPOINT = "https://mi-servidor.ejemplo.com";
    public static final String DEFAULT_GH_BRANCH = "main";
    public static final String DEFAULT_GH_PATH = "data/latest.json";
    public static final String DEFAULT_GIST_FILE = "data.json";
    public static final long DEFAULT_MIN_TIME_MS = 5_000L;
    public static final float DEFAULT_MIN_DISTANCE_M = 5f;

    /** Nombre del fichero que se busca en Descargas para configurar sin tocar la pantalla. */
    public static final String FILE_NAME = "config.json";
    /** Cota de lectura: un fichero de configuración legible a mano nunca se acerca a esto. */
    private static final long MAX_CONFIG_BYTES = 128L * 1024L;
    private static final int MAX_ID_CHARS = 40;
    private static final int MAX_LABEL_CHARS = 60;
    private static final long MIN_INTERVAL_S = 1L;
    private static final long MAX_INTERVAL_S = 3_600L;
    private static final long MAX_DISTANCE_M = 10_000L;

    private AppConfig() {
    }

    /** Almacén de preferencias de la aplicación. */
    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Identificador por defecto de un dispositivo nuevo (el mismo formato que usa la pantalla). */
    public static String defaultDeviceId() {
        return "android-" + Long.toHexString(System.currentTimeMillis());
    }

    /** Ruta del fichero que se lee al arrancar, para poder decírsela al usuario. */
    public static String fileLocation(Context context) {
        return Environment.DIRECTORY_DOWNLOADS + "/" + ErrorLogger.folder(context) + "/" + FILE_NAME;
    }

    /* ------------------------------------------------------------------ exportar */

    /**
     * Configuración completa como JSON.
     *
     * <p>Incluye los tokens tal cual, porque el fichero sirve para trasladar la configuración de un
     * teléfono a otro; nunca incluye el código de seguridad —solo si hay candado—, porque ese no se
     * puede reconstruir desde un hash y no debe viajar en claro.</p>
     */
    public static JSONObject export(Context context) throws JSONException {
        SharedPreferences prefs = prefs(context);
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("generated_ms", System.currentTimeMillis());

        JSONObject server = new JSONObject();
        server.put("endpoint", prefs.getString(KEY_ENDPOINT, ""));
        server.put("api_key", prefs.getString(KEY_API_KEY, ""));
        root.put("server", server);

        JSONObject device = new JSONObject();
        device.put("device_id", prefs.getString(KEY_DEVICE_ID, ""));
        device.put("label", prefs.getString(KEY_LABEL, Build.MODEL));
        root.put("device", device);

        JSONObject tracking = new JSONObject();
        tracking.put("interval_seconds", Math.max(MIN_INTERVAL_S,
                prefs.getLong(KEY_MIN_TIME_MS, DEFAULT_MIN_TIME_MS) / 1000L));
        tracking.put("distance_meters", Math.round(prefs.getFloat(KEY_MIN_DISTANCE_M, DEFAULT_MIN_DISTANCE_M)));
        tracking.put("enabled", Watchdog.isTrackingEnabled(context));
        root.put("tracking", tracking);

        JSONObject github = new JSONObject();
        github.put("token", prefs.getString(KEY_GH_TOKEN, ""));
        github.put("repo", prefs.getString(KEY_GH_REPO, ""));
        github.put("branch", prefs.getString(KEY_GH_BRANCH, DEFAULT_GH_BRANCH));
        github.put("path", prefs.getString(KEY_GH_PATH, DEFAULT_GH_PATH));
        root.put("github", github);

        JSONObject gist = new JSONObject();
        gist.put("token", prefs.getString(KEY_GIST_TOKEN, ""));
        gist.put("id", prefs.getString(KEY_GIST_ID, ""));
        gist.put("file", prefs.getString(KEY_GIST_FILE, DEFAULT_GIST_FILE));
        root.put("gist", gist);

        JSONObject security = new JSONObject();
        security.put("lock", SecurityGate.isEnabled(context));
        root.put("security", security);

        return root;
    }

    /** La misma exportación, como texto listo para escribir en un fichero. */
    public static String exportText(Context context) {
        try {
            return export(context).toString(2);
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo exportar la configuración: " + t.getMessage());
            return "{}";
        }
    }

    /* ------------------------------------------------------------------ importar */

    /**
     * Aplica un JSON de configuración.
     *
     * @return el resumen de lo aplicado y lo ignorado; nunca lanza
     */
    public static Result importText(Context context, String text) {
        if (text == null || text.trim().isEmpty()) {
            return new Result(true, 0, new ArrayList<String>(), "el fichero está vacío");
        }
        try {
            return apply(context, parse(text));
        } catch (JSONException e) {
            return new Result(true, 0, new ArrayList<String>(), "JSON inválido: " + e.getMessage());
        } catch (Throwable t) {
            return new Result(true, 0, new ArrayList<String>(), "no se pudo aplicar: " + t.getMessage());
        }
    }

    /**
     * Carga automática al abrir la app: lee {@code Descargas/<carpeta>/config.json} y lo aplica solo
     * si su contenido cambió desde la última vez (se compara la huella SHA-256).
     *
     * <p>Así se puede editar el fichero desde un ordenador y basta con abrir la app: no hay que
     * tocar ningún campo, y un fichero mal escrito se avisa <b>una vez</b>, no en cada arranque.</p>
     */
    public static Result loadFromDownloadsIfChanged(Context context) {
        String text = readDownloads(context);
        if (text == null || text.trim().isEmpty()) {
            return new Result(false, 0, new ArrayList<String>(), null);
        }
        String hash = Hashing.sha256Hex(text);
        if (hash != null && hash.equals(prefs(context).getString(KEY_APPLIED_HASH, ""))) {
            return new Result(false, 0, new ArrayList<String>(), null);   // ya estaba aplicado
        }
        Result result = importText(context, text);
        if (hash != null) {
            // Se recuerda tanto el éxito como el fallo: un fichero roto se avisa una vez, y en cuanto
            // se corrija su huella cambia y se vuelve a intentar.
            prefs(context).edit().putString(KEY_APPLIED_HASH, hash).apply();
        }
        Log.i(TAG, "configuración de Descargas: " + result.summary());
        return result;
    }

    /** Resumen legible de un resultado, para la pantalla y el registro. */
    public static final class Result {
        /** ¿Se encontró un fichero o un texto que aplicar? */
        public final boolean found;
        /** Campos aplicados. */
        public final int applied;
        /** Campos ignorados, con el motivo. */
        public final List<String> ignored;
        /** Motivo del fallo, o {@code null} si fue bien. */
        public final String error;

        Result(boolean found, int applied, List<String> ignored, String error) {
            this.found = found;
            this.applied = applied;
            this.ignored = ignored == null ? new ArrayList<String>() : ignored;
            this.error = error;
        }

        public boolean ok() {
            return error == null;
        }

        /** Texto corto para un aviso de pantalla. */
        public String summary() {
            if (error != null) {
                return error;
            }
            if (!found) {
                return "sin fichero de configuración";
            }
            String text = applied + (applied == 1 ? " campo aplicado" : " campos aplicados");
            if (!ignored.isEmpty()) {
                text += " · ignorados: " + join(ignored);
            }
            return text;
        }
    }

    /* ------------------------------------------------------------------ aplicación */

    private static JSONObject parse(String text) throws JSONException {
        Object parsed = new JSONTokener(text).nextValue();
        if (!(parsed instanceof JSONObject)) {
            throw new JSONException("se esperaba un objeto { … }");
        }
        JSONObject root = (JSONObject) parsed;
        JSONObject nested = root.optJSONObject("config");
        return nested == null ? root : nested;   // admite {"config": { … }}
    }

    private static Result apply(Context context, JSONObject root) {
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor editor = prefs.edit();
        List<String> ignored = new ArrayList<String>();
        int applied = 0;

        JSONObject server = group(root, "server", "servidor", "api");
        JSONObject device = group(root, "device", "dispositivo");
        JSONObject tracking = group(root, "tracking", "capture", "captura", "gps");
        JSONObject github = group(root, "github", "gh");
        JSONObject gist = group(root, "gist");
        JSONObject security = group(root, "security", "seguridad");

        /* --- servidor --- */
        String endpoint = text(server, root, "endpoint", "url", "server_url");
        if (endpoint != null) {
            if (endpoint.isEmpty()) {
                ignored.add("server.endpoint vacío");
            } else if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
                ignored.add("server.endpoint debe empezar por http:// o https://");
            } else {
                editor.putString(KEY_ENDPOINT, endpoint.replaceAll("/+$", ""));
                applied++;
            }
        }
        String apiKey = text(server, root, "api_key", "apikey");
        if (apiKey != null) {
            editor.putString(KEY_API_KEY, apiKey);
            applied++;
        }

        /* --- dispositivo --- */
        String deviceId = text(device, root, "device_id", "deviceId");
        if (deviceId != null) {
            String clean = sanitizeId(deviceId);
            if (clean.isEmpty()) {
                ignored.add("device.device_id sin caracteres válidos");
            } else {
                editor.putString(KEY_DEVICE_ID, clean);
                applied++;
                if (!clean.equals(deviceId)) {
                    ignored.add("device.device_id normalizado a " + clean);
                }
            }
        }
        String label = text(device, root, "label", "nombre", "name");
        if (label != null && !label.isEmpty()) {
            String clean = label.length() > MAX_LABEL_CHARS ? label.substring(0, MAX_LABEL_CHARS) : label;
            editor.putString(KEY_LABEL, clean);
            applied++;
        }

        /* --- ritmo de captura --- */
        Double seconds = number(tracking, root, "interval_seconds", "intervalo_segundos", "interval");
        if (seconds == null) {
            Double millis = number(tracking, root, "min_time_ms", "interval_ms");
            if (millis != null) {
                seconds = millis / 1000.0;
            }
        }
        if (seconds != null) {
            long clamped = clamp(Math.round(seconds), MIN_INTERVAL_S, MAX_INTERVAL_S);
            if (clamped != Math.round(seconds)) {
                ignored.add("tracking.interval_seconds ajustado a " + clamped + " s (rango "
                        + MIN_INTERVAL_S + "–" + MAX_INTERVAL_S + ")");
            }
            editor.putLong(KEY_MIN_TIME_MS, clamped * 1000L);
            applied++;
        }
        Double distance = number(tracking, root, "distance_meters", "min_distance_m", "distancia_metros");
        if (distance != null) {
            long clamped = clamp(Math.round(distance), 0L, MAX_DISTANCE_M);
            if (clamped != Math.round(distance)) {
                ignored.add("tracking.distance_meters ajustado a " + clamped + " m (máximo "
                        + MAX_DISTANCE_M + ")");
            }
            editor.putFloat(KEY_MIN_DISTANCE_M, (float) clamped);
            applied++;
        }
        Boolean trackingOn = flag(tracking, root, "enabled", "activo");
        if (trackingOn != null) {
            // Se delega en el dueño de ese estado: además de marcarlo, reinicia los intentos del
            // watchdog, que es justo lo que no debe quedar a medias al activar el rastreo.
            Watchdog.setTrackingEnabled(context, trackingOn.booleanValue());
            applied++;
        }

        /* --- GitHub directo --- */
        String ghToken = text(github, root, "token", "gh_token");
        String ghRepo = text(github, root, "repo", "gh_repo", "repositorio");
        String ghUser = text(github, root, "user", "usuario");
        String ghBranch = text(github, root, "branch", "gh_branch", "rama");
        String ghPath = text(github, root, "path", "gh_path", "ruta");
        if (ghToken != null) {
            editor.putString(KEY_GH_TOKEN, ghToken);
            applied++;
        }
        if (ghRepo != null) {
            String clean = ghRepo.replaceAll("/+$", "");
            if (ghUser != null && !ghUser.isEmpty() && !clean.contains("/")) {
                clean = ghUser + "/" + clean;   // comodidad: usuario y repo por separado
            }
            if (clean.isEmpty()) {
                editor.putString(KEY_GH_REPO, "");
                applied++;   // vaciarlo desactiva el canal, igual que dejar el token en blanco
            } else if (!clean.contains("/")) {
                ignored.add("github.repo debe tener la forma usuario/repositorio");
            } else {
                editor.putString(KEY_GH_REPO, clean);
                applied++;
            }
        }
        if (ghBranch != null && !ghBranch.isEmpty()) {
            editor.putString(KEY_GH_BRANCH, ghBranch);
            applied++;
        }
        if (ghPath != null && !ghPath.isEmpty()) {
            editor.putString(KEY_GH_PATH, ghPath);
            applied++;
        }

        /* --- Gist --- */
        String gistToken = text(gist, root, "token", "gist_token");
        String gistId = text(gist, root, "id", "gist_id");
        String gistFile = text(gist, root, "file", "gist_file", "fichero");
        if (gistToken != null) {
            editor.putString(KEY_GIST_TOKEN, gistToken);
            applied++;
        }
        if (gistId != null) {
            // El ID se guarda tal cual y se normaliza al configurar el publicador (acepta la URL).
            editor.putString(KEY_GIST_ID, gistId);
            applied++;
        }
        if (gistFile != null && !gistFile.isEmpty()) {
            editor.putString(KEY_GIST_FILE, gistFile);
            applied++;
        }

        /* --- seguridad --- */
        String pin = text(security, root, "pin", "codigo", "code");
        Boolean lock = flag(security, root, "lock", "activo");
        if (pin != null && !pin.isEmpty()) {
            if (SecurityGate.set(context, pin)) {
                applied++;
            } else {
                ignored.add("security.pin inválido (de " + SecurityGate.MIN_LENGTH + " a "
                        + SecurityGate.MAX_LENGTH + " dígitos)");
            }
        } else if (lock != null && !lock.booleanValue()) {
            if (SecurityGate.clear(context)) {
                applied++;
            } else {
                // El candado se conserva a propósito: sin él la protección quedaría al alcance de
                // cualquiera. Se dice, porque el fichero pedía otra cosa.
                ignored.add("security.lock=false: el código no se quita con la protección activada");
            }
        } else if (lock != null && lock.booleanValue()) {
            ignored.add("security.lock=true no hace nada sin security.pin");
        }

        editor.apply();
        // Sin identificador no se puede publicar: se genera uno la primera vez, como hace la pantalla.
        if (prefs.getString(KEY_DEVICE_ID, "").trim().isEmpty()) {
            prefs.edit().putString(KEY_DEVICE_ID, defaultDeviceId()).apply();
        }
        applyToClients(context);
        return new Result(true, applied, ignored, null);
    }

    /** Reaplica a los tres canales lo que hay guardado (lo mismo que hace el botón Guardar). */
    public static void applyToClients(Context context) {
        SharedPreferences prefs = prefs(context);
        TelemetryClient.get(context).configure(
                prefs.getString(KEY_ENDPOINT, ""),
                prefs.getString(KEY_API_KEY, ""),
                prefs.getString(KEY_DEVICE_ID, ""));

        String ghToken = prefs.getString(KEY_GH_TOKEN, "");
        String ghRepo = prefs.getString(KEY_GH_REPO, "");
        GithubPublisher.get(context).configure(ghToken, ghRepo,
                prefs.getString(KEY_GH_BRANCH, DEFAULT_GH_BRANCH),
                prefs.getString(KEY_GH_PATH, DEFAULT_GH_PATH),
                !ghToken.isEmpty() && !ghRepo.isEmpty());

        // El mismo token sirve para los dos canales; el Gist puede tener el suyo propio.
        String gistToken = prefs.getString(KEY_GIST_TOKEN, "");
        String token = gistToken.isEmpty() ? ghToken : gistToken;
        String gistId = prefs.getString(KEY_GIST_ID, "");
        GistPublisher.get(context).configure(token, gistId,
                prefs.getString(KEY_GIST_FILE, DEFAULT_GIST_FILE),
                !token.isEmpty() && !gistId.isEmpty());
        // El ID y el nombre de fichero se releen ya normalizados por el publicador.
        prefs.edit()
                .putString(KEY_GIST_ID, GistPublisher.get(context).getGistId())
                .putString(KEY_GIST_FILE, GistPublisher.get(context).getFileName())
                .apply();
    }

    /* ------------------------------------------------------------------ lectura del fichero */

    /**
     * Lee {@code Descargas/<carpeta>/config.json}.
     *
     * <p>En Android 10+ se usa MediaStore y no hace falta ningún permiso; en Android 9 y anteriores
     * se lee por la ruta clásica (necesita el permiso de almacenamiento, y si no se concedió esta
     * función simplemente devuelve {@code null}). La búsqueda en MediaStore es la misma que usa
     * {@link ErrorLogger} para encontrar su {@code error.json}.</p>
     */
    private static String readDownloads(Context context) {
        String folder = ErrorLogger.folder(context);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                String fromMediaStore = readFromMediaStore(context, folder);
                if (fromMediaStore != null && !fromMediaStore.trim().isEmpty()) {
                    return fromMediaStore;
                }
                // MediaStore solo devuelve a esta aplicación los ficheros que ella misma escribió, así
                // que un `config.json` copiado desde un ordenador puede no aparecer en esa consulta.
                // Se intenta además la ruta clásica (en Android 9 siempre funciona; en 10, si el
                // sistema lo permite) y, si tampoco está, el camino fiable es «Importar JSON», que usa
                // el selector del sistema y no depende de permisos de almacenamiento.
            }
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File target = new File(new File(downloads, folder), FILE_NAME);
            if (!target.exists() || target.length() == 0L) {
                return null;
            }
            InputStream in = new FileInputStream(target);
            try {
                return readAll(in);
            } finally {
                closeQuietly(in);
            }
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo leer " + FILE_NAME + ": " + t.getMessage());
            return null;
        }
    }

    private static String readFromMediaStore(Context context, String folder) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Cursor cursor = null;
        try {
            cursor = resolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{FILE_NAME}, null);
            if (cursor == null) {
                return null;
            }
            String wanted = Environment.DIRECTORY_DOWNLOADS + "/" + folder;
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String relative = cursor.getString(1);
                if (relative != null && relative.startsWith(wanted)) {
                    Uri uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                    InputStream in = resolver.openInputStream(uri);
                    try {
                        return readAll(in);
                    } finally {
                        closeQuietly(in);
                    }
                }
            }
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Lee un flujo completo con cota de tamaño.
     *
     * <p>Público a propósito: la pantalla importa ficheros con el selector del sistema y así usa
     * exactamente el mismo lector —y la misma cota— que la carga automática, en lugar de tener dos
     * versiones que puedan divergir.</p>
     */
    public static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(4_096);
        byte[] chunk = new byte[4_096];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > MAX_CONFIG_BYTES) {
                break;   // cota de tamaño: un fichero mayor no es una configuración
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // cerrar nunca debe tapar el resultado de la lectura
        }
    }

    /* ------------------------------------------------------------------ ayudas de lectura */

    /** Primer bloque de los nombres dados que exista como objeto. */
    private static JSONObject group(JSONObject root, String... names) {
        if (root == null) {
            return null;
        }
        for (String name : names) {
            JSONObject child = root.optJSONObject(name);
            if (child != null) {
                return child;
            }
        }
        return null;
    }

    /** Valor de la primera clave que exista: primero en el bloque, después en la raíz. */
    private static Object first(JSONObject group, JSONObject root, String... keys) {
        Object value = search(group, keys);
        return value == null ? search(root, keys) : value;
    }

    private static Object search(JSONObject object, String... keys) {
        if (object == null) {
            return null;
        }
        for (String key : keys) {
            if (object.has(key)) {
                Object value = object.opt(key);
                if (value != null && value != JSONObject.NULL) {
                    return value;
                }
            }
        }
        return null;
    }

    /** Texto, o {@code null} si la clave no está (para no pisar lo que ya había). */
    private static String text(JSONObject group, JSONObject root, String... keys) {
        Object value = first(group, root, keys);
        return value instanceof String ? ((String) value).trim() : null;
    }

    /** Número, aceptando también el número escrito como texto. */
    private static Double number(JSONObject group, JSONObject root, String... keys) {
        Object value = first(group, root, keys);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.valueOf(((String) value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /** Booleano, aceptando también "true"/"false" en texto. */
    private static Boolean flag(JSONObject group, JSONObject root, String... keys) {
        Object value = first(group, root, keys);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            if ("true".equalsIgnoreCase(text)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(text)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Deja el identificador en un formato que el canal Gist y el backend aceptan sin sorpresas. */
    private static String sanitizeId(String raw) {
        StringBuilder clean = new StringBuilder(Math.min(raw.length(), MAX_ID_CHARS));
        for (int i = 0; i < raw.length() && clean.length() < MAX_ID_CHARS; i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.') {
                clean.append(c);
            } else if (c == ' ') {
                clean.append('-');
            }
        }
        return clean.toString();
    }

    private static String join(List<String> values) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                text.append("; ");
            }
            text.append(values.get(i));
        }
        return text.toString();
    }
}
