package com.example.telemetry;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Publica la telemetría directamente en un repositorio de GitHub (Contents API), sin servidor
 * propio: el móvil puede estar en Wi-Fi o en datos móviles. El resultado es un fichero compacto
 * {@code data/latest.json} que GitHub Pages sirve como estático y que el visualizador lee sin
 * backend. Cada fila de puntos lleva, además de la medida bruta, la posición ya filtrada.
 *
 * <p>Concurrency: GitHub rechaza PUT con un {@code sha} obsoleto (409). Ante 409 se vuelve a
 * leer el fichero, se fusiona con lo publicado por otros dispositivos y se reintenta: nunca se
 * pierde un dispositivo por una carrera.</p>
 */
public final class GithubPublisher {

    /** Estado del último intento, observable desde la UI. */
    public static volatile String lastStatus = "sin publicar";
    public static volatile long lastOkAt = 0L;
    public static volatile int publishedCount = 0;
    public static volatile int failedCount = 0;

    private static final String TAG = "GithubPublisher";
    private static final String PREFS = "telemetry_prefs";
    private static final String KEY_ENABLED = "gh_enabled";
    private static final String KEY_TOKEN = "gh_token";
    private static final String KEY_REPO = "gh_repo";
    private static final String KEY_BRANCH = "gh_branch";
    private static final String KEY_PATH = "gh_path";
    private static final String KEY_LABEL = "label";

    private static final String API_BASE = "https://api.github.com/repos/";
    private static final int TIMEOUT_MS = 15_000;
    private static final int MAX_ATTEMPTS = 3;
    private static final long MIN_PUBLISH_INTERVAL_MS = 60_000L;
    private static final int MAX_POINTS_IN_FILE = 2000;

    private static volatile GithubPublisher instance;

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile long lastPublishAt = 0L;
    private volatile JSONObject pendingPoint;

    private GithubPublisher(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.io = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "telemetry-github");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public static GithubPublisher get(Context context) {
        GithubPublisher local = instance;
        if (local == null) {
            synchronized (GithubPublisher.class) {
                local = instance;
                if (local == null) {
                    local = new GithubPublisher(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    public void configure(String token, String repo, String branch, String path, boolean enabled) {
        prefs.edit()
                .putString(KEY_TOKEN, token == null ? "" : token.trim())
                .putString(KEY_REPO, repo == null ? "" : repo.trim().replaceAll("/+$", ""))
                .putString(KEY_BRANCH, branch == null || branch.trim().isEmpty() ? "main" : branch.trim())
                .putString(KEY_PATH, path == null || path.trim().isEmpty() ? "data/latest.json" : path.trim())
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public boolean isConfigured() {
        return !prefs.getString(KEY_TOKEN, "").isEmpty() && !prefs.getString(KEY_REPO, "").isEmpty();
    }

    public String getRepo() {
        return prefs.getString(KEY_REPO, "");
    }

    /** Encola el último punto: el siguiente ciclo publica como mucho cada {@link #MIN_PUBLISH_INTERVAL_MS}. */
    public void enqueue(JSONObject point) {
        if (!isEnabled() || !isConfigured() || point == null) {
            return;
        }
        pendingPoint = point;
        long now = SystemClock.elapsedRealtime();
        if (now - lastPublishAt < MIN_PUBLISH_INTERVAL_MS) {
            return;
        }
        lastPublishAt = now;
        publishAsync();
    }

    /** Publica ahora si hay algo pendiente; lo llama el watchdog para no perder el ciclo en Doze. */
    public void publishAsync() {
        if (!isEnabled() || !isConfigured() || !busy.compareAndSet(false, true)) {
            return;
        }
        try {
            io.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        publishOnce();
                    } catch (Exception e) {
                        failedCount++;
                        lastStatus = "error: " + e.getMessage();
                        Log.w(TAG, "publicación fallida: " + e.getMessage());
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            busy.set(false);
        }
    }

    /* ------------------------------------------------------------------ interno */

    private void publishOnce() {
        try {
            JSONObject point = pendingPoint;
            if (point == null) {
                return;
            }
            String token = prefs.getString(KEY_TOKEN, "");
            String repo = prefs.getString(KEY_REPO, "");
            String branch = prefs.getString(KEY_BRANCH, "main");
            String path = prefs.getString(KEY_PATH, "data/latest.json");

            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                JSONObject remote = downloadCurrent(token, repo, branch, path);
                JSONArray devices = remote == null ? new JSONArray() : remote.optJSONArray("devices");
                if (devices == null) {
                    devices = new JSONArray();
                }
                String remoteSha = remote == null ? null : remote.optString("_sha", null);
                JSONArray merged = mergeDevice(devices, point);
                int code = upload(token, repo, branch, path, remoteSha, merged);
                if (code >= 200 && code < 300) {
                    pendingPoint = null;
                    publishedCount++;
                    lastOkAt = System.currentTimeMillis();
                    lastStatus = "publicado (" + merged.length() + " disp.)";
                    return;
                }
                if (code == 409 || code == 422) {
                    // sha obsoleto: otro dispositivo publicó antes; se relee y se reintenta
                    continue;
                }
                failedCount++;
                lastStatus = "HTTP " + code + " al publicar";
                return;
            }
            failedCount++;
            lastStatus = "conflicto persistente (" + MAX_ATTEMPTS + " intentos)";
        } finally {
            busy.set(false);
        }
    }

    /** Fusión por device_id: sustituye la serie del propio dispositivo y conserva las demás. */
    private JSONArray mergeDevice(JSONArray devices, JSONObject point) {
        String deviceId = point.optString("device_id", "android-unknown");
        JSONArray series = null;
        for (int i = 0; i < devices.length(); i++) {
            JSONObject entry = devices.optJSONObject(i);
            if (entry != null && deviceId.equals(entry.optString("device_id"))) {
                series = entry.optJSONArray("points");
                break;
            }
        }
        if (series == null) {
            series = new JSONArray();
        }
        // Estas dos filas se construyen fuera del try a propósito: `JSONArray.put(Object)` no
        // declara JSONException. Los dobles que sí podrían lanzarla los sanea `pointRow`.
        JSONArray trimmed = new JSONArray();
        int from = Math.max(0, series.length() - (MAX_POINTS_IN_FILE - 1));
        for (int i = from; i < series.length(); i++) {
            trimmed.put(series.opt(i));
        }
        trimmed.put(pointRow(point));

        try {
            JSONObject mergedEntry = new JSONObject();
            mergedEntry.put("device_id", deviceId);
            mergedEntry.put("label", prefs.getString(KEY_LABEL, deviceId));
            mergedEntry.put("updated", point.optLong("timestamp", System.currentTimeMillis()));
            mergedEntry.put("points", trimmed);
            mergedEntry.put("latest", latestOf(point));
            JSONArray wifi = point.optJSONArray("wifi");
            if (wifi != null && wifi.length() > 0) {
                mergedEntry.put("wifi", wifi);
            }
            JSONArray cells = point.optJSONArray("cell");
            if (cells != null && cells.length() > 0) {
                mergedEntry.put("cell", cells);
            }
            JSONObject gnss = point.optJSONObject("gnss");
            if (gnss != null) {
                mergedEntry.put("gnss", gnss);
            }
            JSONArray out = new JSONArray();
            boolean replaced = false;
            for (int i = 0; i < devices.length(); i++) {
                JSONObject entry = devices.optJSONObject(i);
                if (entry != null && deviceId.equals(entry.optString("device_id"))) {
                    out.put(mergedEntry);
                    replaced = true;
                } else if (entry != null) {
                    out.put(entry);
                }
            }
            if (!replaced) {
                out.put(mergedEntry);
            }
            return out;
        } catch (Exception e) {
            Log.w(TAG, "fusión no serializable: " + e.getMessage());
            return devices;
        }
    }

    /**
     * Fila compacta [ts, lat, lng, speed, bearing, acc, battery, activity, smooth_lat, smooth_lng].
     *
     * <p>Las dos últimas columnas son la posición filtrada por {@link TrackFilter} en el propio
     * dispositivo, las mismas que añade el backend a sus puntos: así el visualizador dibuja la traza
     * filtrada igual en modo «GitHub directo» que en modo «API en vivo», sin necesitar servidor.
     * Los lectores antiguos siguen leyendo solo las 8 primeras columnas.</p>
     *
     * <p>{@code JSONArray.put(double)} declara {@code throws JSONException} porque JSON no admite
     * NaN ni infinito: por eso los dobles se sanean antes de entrar y el {@code catch} queda como
     * red de seguridad. Un dato raro no puede tumbar la publicación entera.</p>
     */
    private JSONArray pointRow(JSONObject point) {
        JSONArray row = new JSONArray();
        try {
            row.put(point.optLong("timestamp"));
            row.put(finite(point, "lat", 0.0));
            row.put(finite(point, "lng", 0.0));
            row.put(finite(point, "speed_mps", -1.0));
            row.put(finite(point, "bearing", -1.0));
            row.put(finite(point, "accuracy", -1.0));
            row.put(finite(point, "battery", -1.0));
            row.put(point.optString("activity", ""));
            row.put(finite(point, "smooth_lat", -1.0));
            row.put(finite(point, "smooth_lng", -1.0));
        } catch (JSONException e) {
            // Inalcanzable con los valores ya saneados: si algún día deja de serlo, la fila sale
            // incompleta pero la publicación continúa.
        }
        return row;
    }

    /** NaN e infinito rompen {@code JSONArray.put(double)}; se sustituyen por el centinela dado. */
    private static double finite(JSONObject source, String key, double fallback) {
        double value = source.optDouble(key, fallback);
        return Double.isFinite(value) ? value : fallback;
    }

    private JSONObject latestOf(JSONObject point) {
        JSONObject latest = new JSONObject();
        putIfFinite(latest, "lat", point.optDouble("lat", Double.NaN));
        putIfFinite(latest, "lng", point.optDouble("lng", Double.NaN));
        putIfFinite(latest, "speed", point.optDouble("speed_mps", Double.NaN));
        putIfFinite(latest, "bearing", point.optDouble("bearing", Double.NaN));
        putIfFinite(latest, "acc", point.optDouble("accuracy", Double.NaN));
        putIfFinite(latest, "battery", point.optDouble("battery", Double.NaN));
        try {
            latest.put("ts", point.optLong("timestamp"));
            latest.put("activity", point.optString("activity", ""));
            latest.put("provider", point.optString("provider", ""));
            // Medidas de interiores: sin ellas, el modo «GitHub directo» perdería información que
            // sí muestra el modo API (la distancia real al punto de acceso por 802.11mc).
            JSONArray rtt = point.optJSONArray("rtt");
            if (rtt != null && rtt.length() > 0) {
                latest.put("rtt", rtt);
            }
        } catch (Exception ignored) {
            // nunca debe romper la publicación
        }
        return latest;
    }

    private static void putIfFinite(JSONObject target, String key, double value) {
        if (!Double.isNaN(value) && !Double.isInfinite(value)) {
            try {
                target.put(key, value);
            } catch (Exception ignored) {
                // sin acción
            }
        }
    }

    /* ------------------------------------------------------------------ HTTP */

    private JSONObject downloadCurrent(String token, String repo, String branch, String path) {
        HttpURLConnection connection = null;
        try {
            connection = open("GET", repo, "/contents/" + path + "?ref=" + branch, token);
            int code = connection.getResponseCode();
            if (code != 200) {
                return null;    // 404 = aún no existe: primera publicación
            }
            JSONObject body = new JSONObject(readBody(connection, code));
            String content = new String(Base64.decode(body.optString("content", ""), Base64.DEFAULT), StandardCharsets.UTF_8);
            JSONObject parsed = new JSONObject(content);
            parsed.put("_sha", body.optString("sha", ""));
            return parsed;
        } catch (Exception e) {
            return null;    // sin fichero previo se publica desde cero
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private int upload(String token, String repo, String branch, String path, String sha, JSONArray devices) {
        HttpURLConnection connection = null;
        try {
            JSONObject payload = new JSONObject();
            payload.put("message", "telemetría: actualización automática");
            payload.put("branch", branch);
            if (sha != null && !sha.isEmpty()) {
                payload.put("sha", sha);
            }
            JSONObject file = new JSONObject();
            file.put("generated", System.currentTimeMillis());
            file.put("devices", devices);
            String content = Base64.encodeToString(file.toString().getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
            payload.put("content", content);

            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection = open("PUT", repo, "/contents/" + path, token);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            OutputStream out = new BufferedOutputStream(connection.getOutputStream(), 8_192);
            try {
                out.write(body);
                out.flush();
            } finally {
                close(out);
            }
            int code = connection.getResponseCode();
            readBody(connection, code);   // drena la respuesta para reutilizar la conexión
            return code;
        } catch (Exception e) {
            Log.w(TAG, "subida fallida: " + e.getMessage());
            return -1;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection open(String method, String repo, String apiPath, String token) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_BASE + repo + apiPath).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "TelemetryClient/1.2 (Android)");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        return connection;
    }

    private static String readBody(HttpURLConnection connection, int code) {
        InputStream in = null;
        try {
            in = (code >= 200 && code < 300) ? connection.getInputStream() : connection.getErrorStream();
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(1_024);
            byte[] chunk = new byte[2_048];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > 512 * 1_024) {
                    break;    // el GeoJSON publicado no debería crecer indefinidamente
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toString("UTF-8");
        } catch (IOException e) {
            return "";
        } finally {
            close(in);
        }
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // cierre best-effort
        }
    }
}
