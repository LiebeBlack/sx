package com.example.telemetry;

import android.content.Context;
import android.location.Location;
import android.os.Build;
import android.os.Looper;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.gms.tasks.OnSuccessListener;

/**
 * Puente al proveedor fusionado de Google Play Services (Fused Location Provider).
 *
 * <p>¿Por qué además del motor del sistema? Porque <b>Android 11 y anteriores no tienen</b>
 * {@code LocationRequest} con {@code setWaitForAccurateLocation} en el framework (es API 31+): en esos
 * dispositivos el FLP de Google es la única forma de pedir un fix de alta precisión que espere a
 * tener una medida buena en lugar de devolver la primera mediocre. Es exactamente el rango
 * Android 11 que hay que soportar.</p>
 *
 * <p>Es opcional y se comprueba antes de usarlo: en Android Go Edition sin servicios de Google, en
 * ROMs sin GMS o si el usuario los tiene desactivados, {@link #isAvailable} devuelve {@code false} y
 * el servicio sigue con los proveedores del sistema, que ya cubren GPS, red, pasivo y fusionado.</p>
 */
final class FusedLocationBridge {

    /** Entrega cada posición recibida para que el servicio la envíe como una fuente más. */
    interface Listener {
        void onLocation(Location location, String source);
    }

    private static final String TAG = "FusedLocationBridge";

    /** La respuesta de GMS no cambia en caliente: se consulta una vez por proceso. */
    private static volatile Boolean available;

    private final Context context;
    private final Listener listener;

    private FusedLocationProviderClient client;
    private LocationCallback callback;
    private boolean running = false;

    FusedLocationBridge(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** ¿Hay servicios de Google utilizables en este dispositivo? */
    static boolean isAvailable(Context context) {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        boolean result = false;
        try {
            result = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
                    == ConnectionResult.SUCCESS;
        } catch (Throwable t) {
            // GMS ausente por completo: la clase ni siquiera está disponible en algunos ROMs
            Log.w(TAG, "servicios de Google no consultables: " + t.getMessage());
        }
        available = result;
        return result;
    }

    /**
     * Pide posiciones de alta precisión al motor de Google, con espera de fix preciso y réplica
     * rápida entre medidas. También pide de inmediato la última posición conocida, que suele ser
     * mejor que la del sistema porque las apps con permiso comparten caché.
     *
     * @return {@code true} si quedó registrado; {@code false} si no hay servicios de Google.
     */
    boolean start(long intervalMs, float minDistanceM) {
        if (running) {
            return true;
        }
        if (!isAvailable(context)) {
            return false;
        }
        try {
            client = LocationServices.getFusedLocationProviderClient(context);
            LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                    .setMinUpdateIntervalMillis(Math.max(1_000L, intervalMs / 2L))
                    .setMinUpdateDistanceMeters(Math.max(0f, minDistanceM / 2f))
                    .setWaitForAccurateLocation(true)
                    .build();
            callback = new LocationCallback() {
                @Override
                public void onLocationResult(LocationResult result) {
                    if (result == null) {
                        return;
                    }
                    for (Location location : result.getLocations()) {
                        if (location != null) {
                            listener.onLocation(location, "fused/gms");
                        }
                    }
                }
            };
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                client.requestLocationUpdates(request, callback, context.getMainExecutor());
            } else {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper());
            }
            // Caché compartida: un primer punto al instante sin esperar al motor.
            client.getLastLocation().addOnSuccessListener(new OnSuccessListener<Location>() {
                @Override
                public void onSuccess(Location location) {
                    if (location != null) {
                        listener.onLocation(location, "fused/gms/last");
                    }
                }
            });
            running = true;
            Log.i(TAG, "proveedor fusionado de Google activo");
            return true;
        } catch (Throwable t) {
            // permiso revocado, API no disponible o GMS roto: nunca debe tumbar el servicio
            Log.w(TAG, "FLP no disponible: " + t.getMessage());
            running = false;
            return false;
        }
    }

    void stop() {
        running = false;
        FusedLocationProviderClient target = client;
        LocationCallback current = callback;
        if (target != null && current != null) {
            try {
                target.removeLocationUpdates(current);
            } catch (Throwable t) {
                Log.w(TAG, "no se pudo desregistrar el FLP: " + t.getMessage());
            }
        }
        callback = null;
    }

    boolean isRunning() {
        return running;
    }
}
