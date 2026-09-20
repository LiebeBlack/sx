package com.example.telemetry;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.GeomagneticField;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.TransportInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.telephony.CellIdentityGsm;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityWcdma;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthGsm;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthWcdma;
import android.telephony.TelephonyManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/**
 * Servicio en primer plano de telemetría para Android 11-14 (API 30-34), compatible hasta API 24.
 *
 * Fuentes de localización, todas las disponibles a la vez:
 *   · GPS, red (Wi-Fi/celda), pasivo, proveedor fusionado y último punto conocido de cada uno.
 *   · Fix inmediato bajo demanda con {@code getCurrentLocation} (API 30+) al arrancar y cuando el
 *     dato envejece, en lugar de esperar al siguiente ciclo del proveedor.
 *   · Pasada en segundo plano con GNSS (satélites en uso, SNR) y sensores (barómetro, podómetro,
 *     brújula, actividad) para poder juzgar la calidad real de cada punto.
 *   · Wi-Fi (AP conectado + vecinos), celdas LTE/GSM/WCDMA/NR, batería, tipo de red y operador.
 */
public class LocationTrackerService extends Service implements LocationListener {

    private static final String TAG = "LocationTracker";
    private static final String PREFS = "telemetry_prefs";
    private static final String CHANNEL_ID = "telemetry_tracking";
    private static final int NOTIFICATION_ID = 0x7E1;
    private static final String FUSED_PROVIDER = "fused";
    private static final long HEARTBEAT_MS = 60_000L;
    private static final long WAKELOCK_MS = 10 * 60 * 1000L;
    private static final long MAX_FIX_AGE_MS = 15 * 60 * 1000L;
    private static final long ENRICHMENT_TTL_MS = 30_000L;
    private static final long FRESH_FIX_AFTER_MS = 120_000L;
    private static final float ACCURACY_IMPROVEMENT_M = 8f;
    private static final long DEDUPE_WINDOW_MS = 1_500L;      // dos fixes del mismo instante
    private static final long RTT_TTL_MS = 60_000L;           // mínimo entre mediciones RTT
    private static final long RTT_STALE_MS = 90_000L;         // caducidad de una medida RTT
    private static final float RTT_TRIGGER_ACCURACY_M = 25f;  // solo cuando el GPS no va fino
    private static final int MAX_WIFI = 16;                   // puntos de acceso por punto enviado
    private static final int MAX_WIFI_LOW_RAM = 6;

    /** Estado observable desde la UI sin binder ni broadcasts. */
    public static volatile boolean running = false;
    public static volatile int sentCount = 0;
    public static volatile int failedCount = 0;
    public static volatile int droppedCount = 0;
    public static volatile String lastSummary = "sin datos";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            acquireWakeLock();
            Watchdog.beat(LocationTrackerService.this);
            Watchdog.arm(LocationTrackerService.this);
            // La cola no debe esperar a un fix nuevo para vaciarse: si hay puntos pendientes (por
            // ejemplo, se recuperó la cobertura estando quieto), se intenta ya.
            if (client != null && client.queued() > 0) {
                client.flushAsync();
            }
            sendHeartbeat();
            // reengancha la publicación a GitHub si un ciclo se perdió en Doze
            GithubPublisher.get(LocationTrackerService.this).publishAsync();
            // y el canal Gist: reintenta con todo el histórico local pendiente, no solo con el último punto
            GistPublisher.get(LocationTrackerService.this).publishAsync();
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private LocationManager locationManager;
    private TelemetryClient client;
    private GithubPublisher publisher;
    private SensorFusion sensorFusion;
    private GnssMonitor gnssMonitor;
    private final TrackFilter trackFilter = new TrackFilter();
    private FusedLocationBridge fusedBridge;
    private GistPublisher gistPublisher;
    private ConnectivityManager.NetworkCallback networkCallback;
    private PowerManager.WakeLock wakeLock;
    private CancellationSignal cancellationSignal;
    private ExecutorService freshFixExecutor;

    private String label = "android";
    private long minTimeMs = 5_000L;
    private float minDistanceM = 5f;
    private long lastSentAt = 0L;
    private long lastNotifyAt = 0L;
    private long lastSentMonotonicMs = 0L;
    private float lastSentAccuracy = -1f;
    private long lastRttRequestAt = 0L;
    private Boolean rttSupported;   // se consulta una sola vez: la capacidad no cambia en caliente
    private long rttAt = 0L;
    private volatile JSONArray cachedRtt = new JSONArray();
    private Location lastSentLocation;
    private boolean tracking = false;
    private boolean foregroundOk = false;

    private static volatile boolean enrichmentDirty = false;
    private long enrichmentAt = 0L;
    private long lastFreshFixAt = 0L;
    private long enrichmentTtlMs = ENRICHMENT_TTL_MS;
    /** Android Go / dispositivos de 1 GB: menos trabajo por punto, mismo dato útil. */
    private boolean lowRam = false;
    private int wifiLimit = MAX_WIFI;
    private JSONArray cachedWifi = new JSONArray();
    private JSONArray cachedCells = new JSONArray();
    private int cachedBattery = -1;
    private String cachedNetwork;
    private String cachedOperator;
    private String cachedDataNetwork;

    public static void start(Context context) {
        Intent intent = new Intent(context, LocationTrackerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, LocationTrackerService.class));
    }

    /* ------------------------------------------------------------------ ciclo de vida */

    @Override
    public void onCreate() {
        super.onCreate();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        label = prefs.getString("label", Build.MODEL);
        minTimeMs = Math.max(1_000L, prefs.getLong("min_time_ms", 5_000L));
        minDistanceM = Math.max(1f, prefs.getFloat("min_distance_m", 5f));

        client = TelemetryClient.get(this)
                .configure(prefs.getString("endpoint", ""), prefs.getString("api_key", ""), prefs.getString("device_id", null));
        client.setListener(new TelemetryClient.Listener() {
            @Override
            public void onFlush(int httpCode, int sent, int queued, int lost) {
                sentCount += sent;
                if (sent == 0 && queued > 0) {
                    failedCount++;
                }
                droppedCount = lost;
                String estado = httpCode < 0 ? (httpCode == -3 ? "sin red" : "sin conexión") : ("http=" + httpCode);
                lastSummary = estado + " enviados=" + sentCount + " cola=" + queued;
                updateNotification(lastSummary);
            }
        });

        freshFixExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "telemetry-fix");
                thread.setDaemon(true);
                return thread;
            }
        });
        lowRam = isLowRamDevice();
        if (lowRam) {
            // En Android Go el enriquecimiento (Wi-Fi, celdas, sensores) se espacia y se recorta: es
            // lo que evita que el proceso muera por presión de memoria en un dispositivo de 1 GB.
            enrichmentTtlMs = ENRICHMENT_TTL_MS * 2L;
            wifiLimit = MAX_WIFI_LOW_RAM;
        }
        sensorFusion = new SensorFusion(this);
        gnssMonitor = new GnssMonitor(this);
        gnssMonitor.setLowRam(lowRam);
        publisher = GithubPublisher.get(this);
        gistPublisher = GistPublisher.get(this);
        fusedBridge = new FusedLocationBridge(this, new FusedLocationBridge.Listener() {
            @Override
            public void onLocation(Location location, String source) {
                send(location, source, false);
            }
        });
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

        createChannel();
        startAsForeground();
        acquireWakeLock();
        watchNetworks();
        Watchdog.arm(this);
        // WorkManager como segunda vía de persistencia (idempotente: conserva el trabajo si existe).
        TelemetryWorker.schedulePeriodic(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (client.getEndpoint().isEmpty()) {
            // Fallo determinista de configuración: autorizar al watchdog aquí significaría relanzar
            // el servicio cada 15 minutos durante toda la vida del teléfono sin poder enviar nada.
            lastSummary = "endpoint vacío: configura la URL en la app";
            updateNotification(lastSummary);
            Watchdog.setTrackingEnabled(this, false);
            Watchdog.cancel(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        // Nota: si falla startForeground (permiso o restricción del sistema) NO se desactiva el
        // rastreo. Ese fallo es transitorio y ahí es donde el watchdog + START_STICKY recuperan el
        // servicio; desactivarlo enterraría el mecanismo de persistencia.
        if (!foregroundOk) {
            // API 34+ exige el permiso de ubicación en uso para arrancar con tipo 'location'.
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                // Fallo determinista: sin permiso no hay nada que rastrear y autorizar al watchdog
                // significaría relanzar un servicio incapaz de arrancar durante toda la vida del
                // teléfono. Al conceder el permiso, la actividad vuelve a activar el rastreo.
                lastSummary = "sin permiso de ubicación: concédelo en la app";
                updateNotification(lastSummary);
                Watchdog.setTrackingEnabled(this, false);
                Watchdog.cancel(this);
                stopSelf();
                return START_NOT_STICKY;
            }
            // Cualquier otro fallo (restricción del fabricante, permiso revocado a media ejecución)
            // es transitorio: el watchdog y START_STICKY son los que recuperan el servicio.
            lastSummary = "primer plano bloqueado: reintentando";
            stopSelf();
            return START_STICKY;
        }
        startTracking();
        running = true;
        Watchdog.setTrackingEnabled(this, true);
        Watchdog.beat(this);
        Watchdog.arm(this);
        handler.removeCallbacks(heartbeat);
        handler.postDelayed(heartbeat, HEARTBEAT_MS);
        return START_STICKY;
    }

    /** Cierre desde "recientes": se rearma el watchdog y se pide continuidad al sistema. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Watchdog.arm(this);
        Watchdog.ensureService(this);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        tracking = false;
        handler.removeCallbacksAndMessages(null);
        if (locationManager != null) {
            try {
                locationManager.removeUpdates(this);
            } catch (Exception ignored) {
                // permiso revocado en caliente
            }
        }
        if (cancellationSignal != null) {
            try {
                cancellationSignal.cancel();
            } catch (Exception ignored) {
                // sin acción
            }
            cancellationSignal = null;
        }
        if (sensorFusion != null) {
            sensorFusion.stop();
        }
        if (gnssMonitor != null) {
            gnssMonitor.stop();
        }
        if (fusedBridge != null) {
            fusedBridge.stop();
        }
        if (networkCallback != null) {
            try {
                ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
                if (manager != null) {
                    manager.unregisterNetworkCallback(networkCallback);
                }
            } catch (Exception ignored) {
                // ya desregistrado, o el proceso se está cerrando: no hay nada que hacer
            }
            networkCallback = null;
        }
        if (freshFixExecutor != null) {
            freshFixExecutor.shutdownNow();
        }
        if (client != null) {
            client.shutdown();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (Watchdog.isTrackingEnabled(this)) {
            Watchdog.arm(this);   // si el usuario sigue queriendo rastrear, el watchdog lo relanzará
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /* ------------------------------------------------------------------ captura */

    private void startTracking() {
        if (tracking || locationManager == null) {
            return;
        }
        tracking = true;
        sensorFusion.start();
        gnssMonitor.start();

        String[] providers = {
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
                FUSED_PROVIDER
        };
        for (String provider : providers) {
            registerProvider(provider);
        }
        Location best = bestLastKnown();
        if (best != null) {
            send(best, best.getProvider() + "/last", true);
        }
        // Motor fusionado de Google si el dispositivo lo tiene: en Android 11 y anteriores es la
        // única forma de pedir alta precisión con espera a un fix bueno (el framework lo añadió en 12).
        if (fusedBridge != null && fusedBridge.start(minTimeMs, minDistanceM)) {
            Log.i(TAG, "puente al motor fusionado de Google activo");
        }
        requestFreshFixes();
    }

    private void registerProvider(String provider) {
        try {
            if (!locationManager.getAllProviders().contains(provider)) {
                return;
            }
            if (isFused(provider)) {
                registerAccurateFused();
            } else {
                locationManager.requestLocationUpdates(provider, minTimeMs, minDistanceM, this, Looper.getMainLooper());
            }
            Location last = locationManager.getLastKnownLocation(provider);
            if (last != null) {
                send(last, provider + "/last", true);
            }
            Log.i(TAG, "proveedor activo: " + provider);
        } catch (Exception e) {
            // proveedor ausente, deshabilitado o sin permisos: no debe tumbar el servicio
            Log.w(TAG, "proveedor no disponible (" + provider + "): " + e.getMessage());
        }
    }

    private static boolean isFused(String provider) {
        return FUSED_PROVIDER.equals(provider);
    }

    /**
     * Motor de ubicación de alta precisión (Android 12+).
     *
     * <p>Es la diferencia entre «hay una posición» y «la posición es buena»: se pide calidad
     * {@code QUALITY_HIGH_ACCURACY}, con réplica rápida entre fixes y
     * {@code setWaitForAccurateLocation(true)}, que hace esperar al motor fusionado hasta tener una
     * medida realmente precisa en vez de entregar el primer fix mediocre que encuentre. Es lo que
     * evita los puntos de ±300 m que arruinan una traza urbana.</p>
     */
    private void registerAccurateFused() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                LocationRequest request = new LocationRequest.Builder(LocationRequest.QUALITY_HIGH_ACCURACY, minTimeMs)
                        .setMinUpdateIntervalMillis(Math.max(1_000L, minTimeMs / 2L))
                        .setMinUpdateDistanceMeters(Math.max(0f, minDistanceM / 2f))
                        .setWaitForAccurateLocation(true)
                        .build();
                locationManager.requestLocationUpdates(request, getMainExecutor(), this);
                Log.i(TAG, "fused de alta precisión activo");
                return;
            } catch (Exception e) {
                Log.w(TAG, "alta precisión no disponible: " + e.getMessage());
            }
        }
        try {
            locationManager.requestLocationUpdates(FUSED_PROVIDER, minTimeMs, minDistanceM, this, Looper.getMainLooper());
        } catch (Exception e) {
            Log.w(TAG, "proveedor fusionado no disponible: " + e.getMessage());
        }
    }

    /**
     * Android 11+ permite pedir un fix inmediato sin esperar al ciclo del proveedor: mejora el
     * primer punto y reengancha tras horas de inactividad o de pérdida de señal.
     */
    private void requestFreshFixes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || locationManager == null) {
            return;
        }
        lastFreshFixAt = System.currentTimeMillis();
        if (cancellationSignal == null) {
            cancellationSignal = new CancellationSignal();
        }
        // Android 12+ sabe esperar a un fix realmente preciso: mejor una sola petición al motor
        // fusionado que tres concurrentes devolviendo el mismo punto por duplicado.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && requestAccurateCurrentFix()) {
            return;
        }
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, FUSED_PROVIDER}) {
            try {
                if (!locationManager.getAllProviders().contains(provider)) {
                    continue;
                }
                final String name = provider;
                locationManager.getCurrentLocation(provider, cancellationSignal, freshFixExecutor, new Consumer<Location>() {
                    @Override
                    public void accept(Location location) {
                        if (location != null) {
                            send(location, name + "/current", false);
                        }
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "fix inmediato no disponible (" + provider + "): " + e.getMessage());
            }
        }
    }

    /**
     * Pide un único fix de alta precisión y espera a que sea bueno (hasta 20 s de margen, con caché
     * de hasta 30 s). Devuelve {@code false} si el dispositivo no lo soporta, para poder caer al
     * método clásico por proveedor.
     */
    private boolean requestAccurateCurrentFix() {
        try {
            LocationRequest request = new LocationRequest.Builder(LocationRequest.QUALITY_HIGH_ACCURACY, 5_000L)
                    .setDurationMillis(20_000L)
                    .setMaxUpdateAgeMillis(30_000L)
                    .setWaitForAccurateLocation(true)
                    .build();
            locationManager.getCurrentLocation(request, cancellationSignal, freshFixExecutor, new Consumer<Location>() {
                @Override
                public void accept(Location location) {
                    if (location != null) {
                        send(location, location.getProvider() + "/current", false);
                    }
                }
            });
            return true;
        } catch (Exception e) {
            Log.w(TAG, "fix inmediato de alta precisión no disponible: " + e.getMessage());
            return false;
        }
    }

    private Location bestLastKnown() {
        Location best = null;
        if (locationManager == null) {
            return null;
        }
        try {
            for (String provider : locationManager.getProviders(true)) {
                Location candidate = locationManager.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) {
                    best = candidate;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "sin permiso de ubicación: " + e.getMessage());
        }
        return best;
    }

    private void sendHeartbeat() {
        Location best = bestLastKnown();
        if (best == null) {
            // Sin ningún fix conocido (arranque reciente, GPS dormido) se pide uno inmediato, como
            // mucho cada FRESH_FIX_AFTER_MS, en lugar de esperar pasivamente al proveedor.
            if (System.currentTimeMillis() - lastFreshFixAt > FRESH_FIX_AFTER_MS) {
                requestFreshFixes();
            }
            // Sin posición es justo el caso en el que una distancia de interiores aporta algo.
            maybeRange(-1f);
            lastSummary = "sin fixes disponibles · cola=" + client.queued();
            updateNotification(lastSummary);
            return;
        }
        long age = Math.abs(System.currentTimeMillis() - best.getTime());
        if (age > MAX_FIX_AGE_MS) {
            lastSummary = "fix antiguo (" + (age / 60_000L) + " min) · cola=" + client.queued();
            updateNotification(lastSummary);
            if (age > FRESH_FIX_AFTER_MS) {
                requestFreshFixes();
            }
            return;
        }
        send(best, best.getProvider() + "/last", false);
    }

    /**
     * Filtro anti-ruido: envío si ha pasado el tiempo mínimo, si hay desplazamiento relevante
     * o si la precisión ha mejorado de forma clara en el mismo sitio.
     */
    /**
     * Envía un punto si supera el filtro anti-ruido.
     *
     * <p>{@code synchronized} porque conviven dos fuentes de llamada —los callbacks de los
     * proveedores (hilo principal) y los fixes inmediatos (hilo de I/O)— y ambas comparten el
     * estado del filtro: sin esto, dos hilos podían pasar a la vez el control de tiempo/distancia
     * y enviar el mismo movimiento por duplicado.</p>
     */
    private synchronized void send(Location location, String provider, boolean force) {
        if (location == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && lastSentAt > 0L) {
            long elapsed = now - lastSentAt;
            float moved = lastSentLocation == null ? Float.MAX_VALUE : lastSentLocation.distanceTo(location);
            boolean betterAccuracy = lastSentLocation != null
                    && location.getAccuracy() > 0f
                    && lastSentLocation.getAccuracy() > 0f
                    && location.getAccuracy() + ACCURACY_IMPROVEMENT_M < lastSentLocation.getAccuracy();
            if (elapsed < minTimeMs && moved < minDistanceM && !betterAccuracy) {
                return;
            }
        }
        // Dos proveedores entregan el mismo instante con precisiones distintas (p. ej. red y
        // fusionado): quedarse con el peor sería ensuciar la traza con un dato que ya se tenía mejor.
        long monotonicMs = location.getElapsedRealtimeNanos() / 1_000_000L;
        if (!force && lastSentMonotonicMs > 0L && monotonicMs > 0L
                && Math.abs(monotonicMs - lastSentMonotonicMs) < DEDUPE_WINDOW_MS
                && lastSentAccuracy > 0f && location.hasAccuracy()
                && location.getAccuracy() >= lastSentAccuracy) {
            return;
        }
        JSONObject point = buildPoint(location, provider, force);
        if (point == null) {
            return;
        }
        lastSentAt = now;
        lastSentLocation = location;
        lastSentMonotonicMs = monotonicMs;
        lastSentAccuracy = location.hasAccuracy() ? location.getAccuracy() : -1f;
        maybeRange(lastSentAccuracy);
        Watchdog.beat(this);
        notifyProgress(point);
        client.send(point);
        // difusión directa a GitHub (opcional): funciona incluso sin servidor propio
        if (publisher != null) {
            publisher.enqueue(point);
        }
        // Canal Gist: el punto se guarda en el histórico local (SQLite) antes de intentar publicarlo,
        // así que un fallo de red, un token caducado o un proceso muerto no pierden nada: el envío
        // masivo del siguiente ciclo manda todo lo pendiente. Todo el trabajo ocurre en el hilo del
        // publicador, porque este método corre en el hilo principal y aquí no se toca el disco.
        if (gistPublisher != null) {
            JSONObject gistPoint = GistPublisher.toGistPoint(this, point);
            if (gistPoint != null) {
                gistPublisher.record(gistPoint);
            }
        }
    }

    /** Notificación con la calidad real del dato en curso (como mucho cada 10 s). */
    private void notifyProgress(JSONObject point) {
        long now = System.currentTimeMillis();
        if (now - lastNotifyAt < 10_000L) {
            return;
        }
        lastNotifyAt = now;
        // accuracy viaja como double: optInt devolvería el valor por defecto
        int accuracy = (int) Math.round(point.optDouble("accuracy", -1.0));
        int used = (gnssMonitor != null && gnssMonitor.isFresh()) ? gnssMonitor.usedInFix() : -1;
        String activity = point.optString("activity", "");
        lastSummary = String.format(Locale.US, "±%d m · sat %d · %s · cola %d",
                accuracy, used, activity.isEmpty() ? "—" : activity, client.queued());
        updateNotification(lastSummary);
    }

    /* ------------------------------------------------------------------ enriquecimiento */

    /**
     * Fuerza que el siguiente punto relea Wi-Fi, celdas y telefonía, sin esperar al TTL.
     *
     * <p>Se usa cuando el usuario concede un permiso opcional desde la app (telefonía, por ejemplo):
     * sin esto, el enriquecimiento ya cacheado seguiría sin aprovecharlo hasta medio minuto después.
     * Es estático a propósito: lo llama la actividad, que no tiene referencia al servicio.</p>
     */
    public static void requestEnrichmentRefresh(Context context) {
        enrichmentDirty = true;
    }

    /** Refresca Wi-Fi, celdas, batería y red cada {@link #ENRICHMENT_TTL_MS} milisegundos. */
    private void refreshEnrichment() {
        long now = System.currentTimeMillis();
        if (enrichmentDirty) {
            enrichmentDirty = false;
            enrichmentAt = 0L;
        }
        if (enrichmentAt != 0L && (now - enrichmentAt) < enrichmentTtlMs) {
            return;
        }
        enrichmentAt = now;
        cachedWifi = collectWifi();
        cachedCells = collectCells();
        cachedBattery = batteryPercent();
        cachedNetwork = networkType();
        cachedOperator = networkOperator();
        cachedDataNetwork = dataNetwork();
    }

    private JSONObject buildPoint(Location location, String provider, boolean cached) {
        try {
            refreshEnrichment();
            JSONObject point = new JSONObject();
            point.put("device_id", client.getDeviceId());
            point.put("label", label);
            point.put("lat", location.getLatitude());
            point.put("lng", location.getLongitude());
            point.put("timestamp", location.getTime() > 0 ? location.getTime() : System.currentTimeMillis());
            point.put("elapsed_nanos", location.getElapsedRealtimeNanos());
            point.put("provider", provider != null ? provider : location.getProvider());
            point.put("source", cached ? "android/cached" : "android");
            point.put("model", Build.MANUFACTURER + " " + Build.MODEL);
            point.put("sdk", Build.VERSION.SDK_INT);
            if (lowRam) {
                // Quien lea la traza debe saber que este dispositivo va recortado a propósito.
                point.put("low_ram", true);
            }

            if (location.hasAccuracy()) {
                point.put("accuracy", location.getAccuracy());
            }
            if (location.hasAltitude()) {
                point.put("altitude", location.getAltitude());
            }
            if (location.hasSpeed()) {
                point.put("speed_mps", location.getSpeed());
            }
            if (location.hasBearing()) {
                point.put("bearing", location.getBearing());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (location.hasVerticalAccuracy()) {
                    point.put("vertical_accuracy", location.getVerticalAccuracyMeters());
                }
                if (location.hasSpeedAccuracy()) {
                    point.put("speed_accuracy", location.getSpeedAccuracyMetersPerSecond());
                }
                if (location.hasBearingAccuracy()) {
                    point.put("bearing_accuracy", location.getBearingAccuracyDegrees());
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (location.isMock()) {
                    point.put("mock", true);
                }
            } else if (location.isFromMockProvider()) {
                point.put("mock", true);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // isComplete() (API 30) dice si el fix trae todos los campos: señal de calidad.
                point.put("complete", location.isComplete());
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34: altitud sobre el nivel del mar (geoidal) en vez de la elipsoidal, con su
                // propia precisión. Es otra referencia y mejor para trazas terrestres.
                if (location.hasMslAltitude()) {
                    point.put("altitude_msl", location.getMslAltitudeMeters());
                }
                if (location.hasMslAltitudeAccuracy()) {
                    point.put("altitude_msl_accuracy", location.getMslAltitudeAccuracyMeters());
                }
            }

            if (cachedBattery > 0 && cachedBattery <= 100) {
                point.put("battery", cachedBattery);
            }
            if (cachedNetwork != null) {
                point.put("network", cachedNetwork);
            }
            if (cachedOperator != null && !cachedOperator.isEmpty()) {
                point.put("operator", cachedOperator);
            }
            if (cachedDataNetwork != null) {
                point.put("data_network", cachedDataNetwork);
            }
            if (cachedWifi.length() > 0) {
                point.put("wifi", cachedWifi);
            }
            if (cachedCells.length() > 0) {
                point.put("cell", cachedCells);
            }
            JSONArray rtt = cachedRtt;
            if (rtt.length() > 0 && (System.currentTimeMillis() - rttAt) < RTT_STALE_MS) {
                point.put("rtt", rtt);
            }
            if (gnssMonitor != null) {
                gnssMonitor.enrich(point);
            }
            if (sensorFusion != null) {
                sensorFusion.enrich(point);
            }
            applyPositionFilter(point, location);
            enrichTrueHeading(point, location);
            return point;
        } catch (JSONException e) {
            Log.e(TAG, "no se pudo serializar el punto", e);
            return null;
        }
    }

    /**
     * Añade la posición filtrada —y la velocidad estimada si el proveedor no la da— al punto.
     *
     * <p>El paso de tiempo usa el reloj monotónico del fix: con la hora de pared, un cambio de hora
     * del teléfono metería un salto enorme en el filtro y lo desestabilizaría.</p>
     */
    private void applyPositionFilter(JSONObject point, Location location) {
        long monotonicMs = location.getElapsedRealtimeNanos() / 1_000_000L;
        if (monotonicMs <= 0L) {
            monotonicMs = SystemClock.elapsedRealtime();
        }
        double accuracy = location.hasAccuracy() ? location.getAccuracy() : -1.0;
        TrackFilter.Result filtered = trackFilter.update(
                location.getLatitude(), location.getLongitude(), monotonicMs, accuracy);
        try {
            point.put("smooth_lat", Math.round(filtered.lat * 10_000_000.0) / 10_000_000.0);
            point.put("smooth_lng", Math.round(filtered.lng * 10_000_000.0) / 10_000_000.0);
            if (!location.hasSpeed() && filtered.speedMps > 0.1) {
                point.put("speed_mps", Math.round(filtered.speedMps * 1_000.0) / 1_000.0);
            }
        } catch (JSONException e) {
            Log.w(TAG, "no se pudo añadir la posición filtrada: " + e.getMessage());
        }
    }

    /**
     * Rumbo al norte verdadero.
     *
     * <p>La brújula da el norte magnético, que está desviado del geográfico (unos 2° en la península
     * ibérica, más de 20° en otras latitudes). Para que el rumbo sirva para navegar se corrige con
     * la declinación del modelo geomagnético en la posición actual; se publican las dos versiones
     * para no perder información.</p>
     */
    private void enrichTrueHeading(JSONObject point, Location location) {
        double magnetic = point.optDouble("heading_magnetic", Double.NaN);
        if (Double.isNaN(magnetic)) {
            return;
        }
        try {
            double altitude = location.hasAltitude() ? location.getAltitude() : 0.0;
            GeomagneticField field = new GeomagneticField(
                    (float) location.getLatitude(), (float) location.getLongitude(), (float) altitude,
                    System.currentTimeMillis());
            double declination = field.getDeclination();
            point.put("declination_deg", Math.round(declination * 100.0) / 100.0);
            point.put("heading_true", Math.round(((magnetic + declination + 360.0) % 360.0) * 10.0) / 10.0);
        } catch (Exception e) {
            Log.w(TAG, "declinación magnética no disponible: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ Wi-Fi */

    private JSONArray collectWifi() {
        JSONArray result = new JSONArray();
        try {
            WifiInfo info = currentWifiInfo();
            if (info != null && info.getNetworkId() != -1 && info.getSSID() != null) {
                JSONObject connected = new JSONObject();
                String ssid = info.getSSID().replace("\"", "");
                if (!ssid.isEmpty()) {
                    connected.put("ssid", ssid);
                }
                if (info.getBSSID() != null) {
                    connected.put("bssid", info.getBSSID());
                }
                connected.put("rssi", info.getRssi());
                if (info.getFrequency() > 0) {
                    connected.put("frequency", info.getFrequency());
                }
                connected.put("connected", true);
                result.put(connected);
            }
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                return result;
            }
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                return result;
            }
            List<ScanResult> raw = wifiManager.getScanResults();
            if (raw == null || raw.isEmpty()) {
                return result;
            }
            List<ScanResult> scanResults = new ArrayList<>(raw);
            Collections.sort(scanResults, new Comparator<ScanResult>() {
                @Override
                public int compare(ScanResult left, ScanResult right) {
                    return right.level - left.level;
                }
            });
            int limit = Math.min(scanResults.size(), wifiLimit);
            for (int i = 0; i < limit; i++) {
                ScanResult scan = scanResults.get(i);
                JSONObject item = new JSONObject();
                if (scan.SSID != null && !scan.SSID.isEmpty()) {
                    item.put("ssid", scan.SSID);
                }
                item.put("bssid", scan.BSSID);
                item.put("rssi", scan.level);
                if (scan.frequency > 0) {
                    item.put("frequency", scan.frequency);
                    item.put("channel", channelOf(scan.frequency));
                }
                item.put("connected", scan.BSSID != null && info != null && scan.BSSID.equals(info.getBSSID()));
                result.put(item);
            }
        } catch (JSONException e) {
            Log.w(TAG, "Wi-Fi no serializable: " + e.getMessage());
        } catch (Exception e) {
            // Wi-Fi apagado, permiso revocado, sin NEARBY_WIFI_DEVICES o ROM restrictiva
            Log.w(TAG, "Wi-Fi no disponible: " + e.getMessage());
        }
        return result;
    }

    /** En Android 10+ el AP conectado se lee mejor desde ConnectivityManager que desde WifiManager. */
    private WifiInfo currentWifiInfo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiInfo modern = wifiInfoFromConnectivity();
            if (modern != null) {
                return modern;
            }
        }
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            return wifiManager == null ? null : wifiManager.getConnectionInfo();
        } catch (Exception e) {
            return null;
        }
    }

    private WifiInfo wifiInfoFromConnectivity() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return null;
            }
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return null;
            }
            TransportInfo transport = capabilities.getTransportInfo();
            return transport instanceof WifiInfo ? (WifiInfo) transport : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static int channelOf(int frequencyMhz) {
        if (frequencyMhz == 2484) {
            return 14;
        }
        if (frequencyMhz >= 2412 && frequencyMhz <= 2472) {
            return (frequencyMhz - 2407) / 5;
        }
        if (frequencyMhz >= 5000 && frequencyMhz <= 5900) {
            return (frequencyMhz - 5000) / 5;
        }
        return 0;
    }

    /* ------------------------------------------------------------------ telefonía */

    /** Celdas de telefonía: LTE, GSM, WCDMA y cualquier variante extra (NR incluida). */
    private JSONArray collectCells() {
        JSONArray result = new JSONArray();
        try {
            TelephonyManager telephony = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (telephony == null) {
                return result;
            }
            List<CellInfo> cells = telephony.getAllCellInfo();
            if (cells == null) {
                return result;
            }
            for (int i = 0; i < cells.size() && result.size() < 12; i++) {
                CellInfo cell = cells.get(i);
                JSONObject item = new JSONObject();
                boolean registered = cell.isRegistered();
                item.put("registered", registered);
                if (cell instanceof CellInfoLte) {
                    CellIdentityLte identity = ((CellInfoLte) cell).getCellIdentity();
                    CellSignalStrengthLte signal = ((CellInfoLte) cell).getCellSignalStrength();
                    item.put("type", "LTE");
                    item.put("mcc", identity.getMcc());
                    item.put("mnc", identity.getMnc());
                    item.put("cid", identity.getCi());
                    item.put("tac", identity.getTac());
                    item.put("pci", identity.getPci());
                    item.put("rsrp", signal.getRsrp());
                    item.put("rsrq", signal.getRsrq());
                    item.put("dbm", signal.getDbm());
                    item.put("level", signal.getLevel());
                } else if (cell instanceof CellInfoGsm) {
                    CellIdentityGsm identity = ((CellInfoGsm) cell).getCellIdentity();
                    CellSignalStrengthGsm signal = ((CellInfoGsm) cell).getCellSignalStrength();
                    item.put("type", "GSM");
                    item.put("mcc", identity.getMcc());
                    item.put("mnc", identity.getMnc());
                    item.put("cid", identity.getCid());
                    item.put("lac", identity.getLac());
                    item.put("dbm", signal.getDbm());
                    item.put("level", signal.getLevel());
                } else if (cell instanceof CellInfoWcdma) {
                    CellIdentityWcdma identity = ((CellInfoWcdma) cell).getCellIdentity();
                    CellSignalStrengthWcdma signal = ((CellInfoWcdma) cell).getCellSignalStrength();
                    item.put("type", "WCDMA");
                    item.put("mcc", identity.getMcc());
                    item.put("mnc", identity.getMnc());
                    item.put("cid", identity.getCid());
                    item.put("lac", identity.getLac());
                    item.put("dbm", signal.getDbm());
                    item.put("level", signal.getLevel());
                } else {
                    item.put("type", cell.getClass().getSimpleName().replace("CellInfo", "").toUpperCase(Locale.US));
                }
                result.put(item);
            }
        } catch (JSONException e) {
            Log.w(TAG, "celda no serializable: " + e.getMessage());
        } catch (Exception e) {
            Log.w(TAG, "celdas no disponibles: " + e.getMessage());
        }
        return result;
    }

    private String dataNetwork() {
        try {
            TelephonyManager telephony = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (telephony == null) {
                return null;
            }
            int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? telephony.getDataNetworkType()
                    : telephony.getNetworkType();
            switch (type) {
                case TelephonyManager.NETWORK_TYPE_GPRS:
                case TelephonyManager.NETWORK_TYPE_EDGE:
                case TelephonyManager.NETWORK_TYPE_CDMA:
                case TelephonyManager.NETWORK_TYPE_1xRTT:
                case TelephonyManager.NETWORK_TYPE_IDEN:
                    return "2G";
                case TelephonyManager.NETWORK_TYPE_UMTS:
                case TelephonyManager.NETWORK_TYPE_EVDO_0:
                case TelephonyManager.NETWORK_TYPE_EVDO_A:
                case TelephonyManager.NETWORK_TYPE_HSDPA:
                case TelephonyManager.NETWORK_TYPE_HSUPA:
                case TelephonyManager.NETWORK_TYPE_HSPA:
                case TelephonyManager.NETWORK_TYPE_EVDO_B:
                case TelephonyManager.NETWORK_TYPE_EHRPD:
                case TelephonyManager.NETWORK_TYPE_HSPAP:
                case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                    return "3G";
                case TelephonyManager.NETWORK_TYPE_LTE:
                case TelephonyManager.NETWORK_TYPE_IWLAN:
                    return "4G";
                case TelephonyManager.NETWORK_TYPE_NR:
                    return "5G";
                default:
                    return "unknown";
            }
        } catch (Exception e) {
            return null;
        }
    }

    private int batteryPercent() {
        try {
            BatteryManager manager = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            return manager == null ? -1 : manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) {
            return -1;
        }
    }

    private String networkType() {
        try {
            ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return null;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
                if (capabilities == null) {
                    return "offline";
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return "wifi";
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    return "cellular";
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return "ethernet";
                }
                if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return "vpn";
                }
                return "other";
            }
            NetworkInfo info = manager.getActiveNetworkInfo();
            return info != null && info.isConnected() ? info.getTypeName() : "offline";
        } catch (Exception e) {
            return null;
        }
    }

    private String networkOperator() {
        try {
            TelephonyManager telephony = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            return telephony == null ? null : telephony.getNetworkOperatorName();
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isLowRamDevice() {
        try {
            ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            return activityManager != null && activityManager.isLowRamDevice();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean granted(String permission) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    /* ------------------------------------------------------------------ notificación */

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Rastreo de telemetría", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Ubicación, Wi-Fi, celdas y sensores enviados al servidor");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void startAsForeground() {
        Notification notification = buildNotification("Recopilando ubicación…");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foregroundOk = true;
        } catch (Exception e) {
            // permiso de ubicación revocado o restricción del fabricante: nunca debe crashear
            Log.e(TAG, "no se pudo iniciar en primer plano: " + e.getMessage());
            ErrorLogger.record("LocationTrackerService/startForeground",
                    "no se pudo iniciar en primer plano: " + e.getMessage(), e);
            foregroundOk = false;
            stopSelf();
        }
    }

    private void updateNotification(final String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        } catch (Exception e) {
            Log.w(TAG, "notificación no actualizada: " + e.getMessage());
        }
    }

    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle("Telemetría activa")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                return;
            }
            if (wakeLock == null) {
                wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "telemetry:tracker");
                wakeLock.setReferenceCounted(false);
            }
            if (!wakeLock.isHeld()) {
                wakeLock.acquire(WAKELOCK_MS);
            }
        } catch (Exception e) {
            Log.w(TAG, "wake lock no disponible: " + e.getMessage());
        }
    }

    /**
     * Vigila la red por defecto: en cuanto aparece cualquier conexión —Wi-Fi, datos móviles,
     * ethernet— se vacía la cola y se publica en GitHub al instante, en lugar de esperar al
     * siguiente latido de 60 s. Es lo que hace que el sistema aproveche «cualquier medio
     * disponible» en cuanto lo haya, que es justo el momento en que los puntos atrasados importan.
     */
    private void watchNetworks() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || networkCallback != null) {
            return;
        }
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return;
        }
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                Log.i(TAG, "red disponible: se vacía la cola y se reintenta el fix");
                if (client != null) {
                    client.flushAsync();
                }
                if (publisher != null) {
                    publisher.publishAsync();
                }
                if (gistPublisher != null) {
                    // En cuanto aparece red se manda todo el histórico pendiente al Gist, no solo el
                    // último punto: es la ventana en la que GitHub acepta la escritura.
                    gistPublisher.publishAsync();
                }
                requestFreshFixes();
            }

            @Override
            public void onLost(Network network) {
                Log.w(TAG, "red perdida: la cola espera y sigue acumulando puntos");
            }
        };
        try {
            manager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo vigilar la red: " + e.getMessage());
            networkCallback = null;
        }
    }

    /**
     * Pide distancias por Wi-Fi RTT cuando la posición no es buena.
     *
     * <p>El 802.11mc mide la distancia al punto de acceso con precisión de decímetros, algo que
     * ningún otro método da en interiores: donde el GPS no llega, esta es la única medida geométrica
     * real disponible. Se lanza como mucho una vez por minuto y solo si el fix no es ya preciso, así
     * que no compite con el GPS cuando el GPS va bien.</p>
     */
    private void maybeRange(float accuracyM) {
        if (lowRam) {
            return;   // en dispositivos de 1 GB no se paga el coste de una medición RTT
        }
        if (rttSupported == null) {
            rttSupported = WifiRttRanger.isSupported(this);
        }
        if (!rttSupported) {
            return;
        }
        long now = System.currentTimeMillis();
        if ((now - lastRttRequestAt) < RTT_TTL_MS) {
            return;
        }
        if (accuracyM > 0f && accuracyM <= RTT_TRIGGER_ACCURACY_M) {
            return;   // el GPS ya va fino: no se gasta batería midiendo distancias
        }
        lastRttRequestAt = now;
        WifiRttRanger.range(this, getMainExecutor(), new WifiRttRanger.Callback() {
            @Override
            public void onRanged(JSONArray results) {
                if (results != null && results.length() > 0) {
                    cachedRtt = results;
                    rttAt = System.currentTimeMillis();
                    Log.i(TAG, "distancias Wi-Fi RTT disponibles: " + results.length());
                }
            }
        });
    }

    /* ------------------------------------------------------------------ LocationListener */

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            send(location, location.getProvider(), false);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        Log.d(TAG, "estado " + provider + "=" + status);
    }

    @Override
    public void onProviderEnabled(String provider) {
        Log.i(TAG, "proveedor habilitado: " + provider);
        registerProvider(provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        Log.w(TAG, "proveedor deshabilitado: " + provider);
    }
}
