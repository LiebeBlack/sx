package com.example.telemetry;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cliente HTTP de telemetría: POST JSON con {@link HttpURLConnection}, cola en memoria
 * (+ espejo persistente), lotes, reintentos con backoff exponencial y descarte controlado.
 * Nunca bloquea el hilo principal: todo el I/O corre en un executor de un solo hilo.
 */
public final class TelemetryClient {

    /** Resultado de cada ciclo de envío, entregado en el hilo principal. */
    public interface Listener {
        void onFlush(int httpCode, int sent, int queued, int dropped);
    }

    private static final String TAG = "TelemetryClient";
    private static final String PREFS = "telemetry_prefs";
    private static final String KEY_QUEUE = "offline_queue";
    private static final String KEY_ENDPOINT = "endpoint";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_DEVICE_ID = "device_id";

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 10_000;
    private static final int BATCH_SIZE = 40;
    private static final int MAX_QUEUE = 1_000;
    private static final int MAX_ATTEMPTS = 5;
    private static final long BASE_BACKOFF_MS = 800L;
    private static final long MAX_BACKOFF_MS = 30_000L;
    private static final long RETRY_CYCLE_MS = 15_000L;
    private static final long PERSIST_MIN_INTERVAL_MS = 10_000L;
    private static final int MAX_RESPONSE_BYTES = 8_192;
    /** Código interno: no hay red. Se distingue de -1 (fallo de conexión) para la notificación. */
    private static final int NO_NETWORK = -3;

    private static volatile TelemetryClient instance;

    private final Context context;
    private final SharedPreferences prefs;
    private volatile ExecutorService io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object queueLock = new Object();
    private final ArrayDeque<JSONObject> queue = new ArrayDeque<>();
    private final AtomicBoolean flushing = new AtomicBoolean(false);
    private final AtomicInteger dropped = new AtomicInteger();
    private final AtomicLong lastHttpCode = new AtomicLong();
    private volatile long lastPersistAt = 0L;

    private volatile String endpoint = "";
    private volatile String apiKey = "";
    private volatile String deviceId = "android-unknown";
    private volatile Listener listener;

    private TelemetryClient(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.io = newIo();
        this.endpoint = prefs.getString(KEY_ENDPOINT, "");
        this.apiKey = prefs.getString(KEY_API_KEY, "");
        this.deviceId = prefs.getString(KEY_DEVICE_ID, "android-" + Long.toHexString(System.currentTimeMillis()));
        loadQueue();
    }

    private static ExecutorService newIo() {
        return Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "telemetry-io");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * Ejecutor de I/O auto-reparable.
     *
     * <p>Este cliente es un singleton de proceso y el sistema mata el servicio cuando le conviene:
     * {@code onDestroy} cierra el worker, pero el watchdog relanza el servicio en el MISMO proceso
     * y vuelve a pedir esta misma instancia. Si el ejecutor se quedase muerto, cada envío lanzaría
     * {@code RejectedExecutionException} y la telemetría dejaría de subir puntos en silencio para
     * siempre; por eso se recrea bajo demanda.</p>
     */
    private synchronized ExecutorService io() {
        ExecutorService current = io;
        if (current == null || current.isShutdown() || current.isTerminated()) {
            current = newIo();
            io = current;
        }
        return current;
    }

    public static TelemetryClient get(Context context) {
        TelemetryClient local = instance;
        if (local == null) {
            synchronized (TelemetryClient.class) {
                local = instance;
                if (local == null) {
                    local = new TelemetryClient(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    /** Configura destino y credenciales (se persisten entre arranques). */
    public TelemetryClient configure(String endpointUrl, String key, String id) {
        if (endpointUrl != null) {
            this.endpoint = endpointUrl.trim().replaceAll("/+$", "");
            prefs.edit().putString(KEY_ENDPOINT, this.endpoint).apply();
        }
        if (key != null) {
            this.apiKey = key.trim();
            prefs.edit().putString(KEY_API_KEY, this.apiKey).apply();
        }
        if (id != null && !id.trim().isEmpty()) {
            this.deviceId = id.trim();
            prefs.edit().putString(KEY_DEVICE_ID, this.deviceId).apply();
        }
        return this;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getDeviceId() {
        return deviceId;
    }

    public int queued() {
        synchronized (queueLock) {
            return queue.size();
        }
    }

    public int droppedCount() {
        return dropped.get();
    }

    public int lastHttpCode() {
        return (int) lastHttpCode.get();
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Encola un punto y dispara el envío en segundo plano. */
    public void send(JSONObject point) {
        if (point == null) {
            return;
        }
        synchronized (queueLock) {
            queue.addLast(point);
            while (queue.size() > MAX_QUEUE) {
                queue.pollFirst();
                dropped.incrementAndGet();
            }
            persistLocked(false);
        }
        flushAsync();
    }

    /** Fuerza un intento de vaciado de cola sin bloqueo del llamante. */
    public void flushAsync() {
        if (endpoint.isEmpty() || !flushing.compareAndSet(false, true)) {
            return;
        }
        Runnable drainTask = new Runnable() {
            @Override
            public void run() {
                drain();
            }
        };
        try {
            io().execute(drainTask);
        } catch (RejectedExecutionException e) {
            // Carrera con shutdown(): se reintenta una sola vez con un ejecutor nuevo.
            try {
                io().execute(drainTask);
            } catch (RejectedExecutionException retry) {
                flushing.set(false);
                // Esto no debería ocurrir nunca (el ejecutor se recrea bajo demanda): si pasa, es un
                // defecto real y tiene que quedar registrado en vez de morir en silencio.
                ErrorLogger.record("TelemetryClient/flush", "el ejecutor rechazó el vaciado de cola", retry);
            }
        }
    }

    /**
     * Vaciado síncrono desde un hilo de trabajo propio (WorkManager).
     *
     * <p>Es la vía de transmisión que <b>no depende</b> de que el servicio en primer plano pueda
     * arrancar: WorkManager tiene su propia ventana de ejecución y no le afectan las restricciones
     * de arranque en segundo plano de Android 12+, así que puede entregar los puntos que quedaron
     * en la cola persistida aunque el rastreo esté bloqueado por el sistema.</p>
     *
     * @return número de puntos entregados en esta llamada (0 si no había nada o no había red).
     */
    public int flushBlocking() {
        if (endpoint.isEmpty()) {
            return 0;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            // Regla de la casa: el hilo principal nunca se bloquea con I/O.
            flushAsync();
            return 0;
        }
        if (!flushing.compareAndSet(false, true)) {
            return 0;   // ya hay un vaciado en curso en otro hilo: ese se encarga
        }
        int before = queued();
        try {
            drain();
        } catch (Throwable t) {
            // Ningún fallo del trabajo debe propagarse como excepción: WorkManager lo reintentaría
            // en vano y la cola sigue persistida para el siguiente intento.
            Log.w(TAG, "vaciado síncrono interrumpido: " + t.getMessage());
            ErrorLogger.record("TelemetryClient/flushBlocking", "vaciado síncrono interrumpido", t);
        }
        return Math.max(0, before - queued());
    }

    /** Cierra el worker tras persistir la cola. La instancia sigue usable: el ejecutor se recrea. */
    public void shutdown() {
        ExecutorService current = io;
        if (current != null) {
            current.shutdownNow();
        }
        synchronized (queueLock) {
            persistLocked(true);
        }
    }

    /* ------------------------------------------------------------------ interno */

    private void drain() {
        int sent = 0;
        int lastCode = 0;
        try {
            if (!isOnline()) {
                // Sin red: se avisa al listener para que la notificación lo diga, y el heartbeat
                // del servicio reintentará en el siguiente ciclo en lugar de esperar en silencio.
                notifyListener(NO_NETWORK, 0);
                return;
            }
            int attempt = 0;
            while (!Thread.currentThread().isInterrupted()) {
                List<JSONObject> batch = snapshot(BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }
                lastCode = post(batch);
                lastHttpCode.set(lastCode);
                if (lastCode >= 200 && lastCode < 300) {
                    removeHead(batch.size());
                    sent += batch.size();
                    attempt = 0;
                    continue;
                }
                if (!isRetryable(lastCode)) {
                    removeHead(batch.size());   // 400/413/JSON inválido: el punto nunca será aceptado
                    dropped.addAndGet(batch.size());
                    attempt = 0;
                    continue;
                }
                attempt++;
                if (attempt >= MAX_ATTEMPTS) {
                    Log.w(TAG, "reintentos agotados (código " + lastCode + "), quedan " + queued() + " en cola");
                    break;
                }
                sleep(BASE_BACKOFF_MS << Math.min(attempt, 5));
            }
        } finally {
            synchronized (queueLock) {
                persistLocked(true);   // refleja el estado real de la cola antes de dormir el hilo
            }
            flushing.set(false);
        }
        notifyListener(lastCode, sent);
        if (queued() > 0) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    flushAsync();
                }
            }, RETRY_CYCLE_MS);
        }
    }

    private void notifyListener(int httpCode, int sent) {
        final Listener target = listener;
        if (target == null) {
            return;
        }
        final int queued = queued();
        final int lost = dropped.get();
        main.post(new Runnable() {
            @Override
            public void run() {
                target.onFlush(httpCode, sent, queued, lost);
            }
        });
    }

    private List<JSONObject> snapshot(int max) {
        synchronized (queueLock) {
            List<JSONObject> out = new ArrayList<>(Math.min(max, queue.size()));
            Iterator<JSONObject> it = queue.iterator();
            while (it.hasNext() && out.size() < max) {
                out.add(it.next());
            }
            return out;
        }
    }

    private void removeHead(int count) {
        synchronized (queueLock) {
            for (int i = 0; i < count && !queue.isEmpty(); i++) {
                queue.pollFirst();
            }
            persistLocked(false);
        }
    }

    private static boolean isRetryable(int code) {
        return code == -1 || code == 408 || code == 429 || code >= 500;
    }

    /** POST por lotes: {@code {"points":[ ... ]}}. Devuelve el código HTTP o -1/-2 en fallo local. */
    private int post(List<JSONObject> batch) {
        HttpURLConnection connection = null;
        try {
            JSONArray array = new JSONArray();
            for (int i = 0; i < batch.size(); i++) {
                array.put(batch.get(i));
            }
            JSONObject body = new JSONObject();
            body.put("points", array);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            String url = endpoint.endsWith("/api/location") ? endpoint : endpoint + "/api/location";

            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "TelemetryClient/1.0 (Android " + Build.VERSION.SDK_INT + ")");
            if (!apiKey.isEmpty()) {
                connection.setRequestProperty("X-Api-Key", apiKey);
            }
            connection.setFixedLengthStreamingMode(payload.length);

            OutputStream out = new BufferedOutputStream(connection.getOutputStream(), 8_192);
            try {
                out.write(payload);
                out.flush();
            } finally {
                closeQuietly(out);
            }

            int code = connection.getResponseCode();
            String response = read(connection, code);
            if (code >= 200 && code < 300) {
                Log.d(TAG, "POST " + code + " lote=" + batch.size() + " " + response);
            } else {
                Log.w(TAG, "POST " + code + " " + response);
            }
            return code;
        } catch (IOException e) {
            Log.w(TAG, "fallo de red: " + e.getMessage());
            return -1;
        } catch (JSONException e) {
            Log.e(TAG, "payload inválido", e);
            ErrorLogger.record("TelemetryClient/post", "el lote no se pudo serializar", e);
            return -2;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String read(HttpURLConnection connection, int code) {
        InputStream in = null;
        try {
            in = (code >= 200 && code < 300) ? connection.getInputStream() : connection.getErrorStream();
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
            byte[] chunk = new byte[2_048];
            int total = 0;
            int read;
            while ((read = in.read(chunk)) != -1) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
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

    private boolean isOnline() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return true;
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info == null || info.isConnected();
        } catch (Exception e) {
            return true;   // si no se puede saber, se intenta igualmente
        }
    }

    private void loadQueue() {
        String raw = prefs.getString(KEY_QUEUE, "[]");
        synchronized (queueLock) {
            try {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item != null) {
                        queue.addLast(item);
                    }
                }
            } catch (JSONException e) {
                Log.w(TAG, "cola persistida ilegible, se descarta");
            }
            while (queue.size() > MAX_QUEUE) {
                queue.pollFirst();
            }
        }
    }

    /**
     * Debe invocarse con {@code queueLock} tomado. El espejo en disco se actualiza como mucho
     * cada {@link #PERSIST_MIN_INTERVAL_MS} para no serializar la cola en cada punto enviado.
     */
    private void persistLocked(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && (now - lastPersistAt) < PERSIST_MIN_INTERVAL_MS) {
            return;
        }
        lastPersistAt = now;
        JSONArray array = new JSONArray();
        for (Iterator<JSONObject> it = queue.iterator(); it.hasNext(); ) {
            array.put(it.next());
        }
        prefs.edit().putString(KEY_QUEUE, array.toString()).apply();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis + (long) (Math.random() * 250L));
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
            // sin acción: cierre best-effort
        }
    }
}
