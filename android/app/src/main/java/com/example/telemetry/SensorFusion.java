package com.example.telemetry;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Fusión de sensores para afinar la telemetría: altitud barométrica (más estable que la de GPS
 * en interiores), podómetro, rumbo magnético y clasificación de actividad por aceleración.
 * Coste mínimo: SENSOR_DELAY_NORMAL, sensores solo si existen y sin hilos propios.
 */
public class SensorFusion implements SensorEventListener {

    private static final String TAG = "SensorFusion";
    private static final float SEA_LEVEL_HPA = 1013.25f;
    private static final float EMA_ALPHA = 0.1f;
    private static final long STALE_MS = 90_000L;

    private final Context context;

    private SensorManager manager;
    private boolean running = false;
    private long lastEventAt = 0L;

    private volatile float pressureHpa = Float.NaN;
    private volatile double altitudeBaro = Double.NaN;
    private volatile double steps = -1.0;
    private volatile double headingMagnetic = Double.NaN;
    private volatile float movement = 0f;

    public SensorFusion(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        if (running) {
            return;
        }
        manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (manager == null) {
            return;
        }
        register(manager.getDefaultSensor(Sensor.TYPE_PRESSURE));
        register(manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER));
        register(manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD));
        register(manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER));
        running = true;
    }

    public void stop() {
        running = false;
        if (manager != null) {
            try {
                manager.unregisterListener(this);
            } catch (Exception e) {
                Log.w(TAG, "no se pudo desregistrar sensores: " + e.getMessage());
            }
        }
    }

    private void register(Sensor sensor) {
        if (sensor == null || manager == null) {
            return;
        }
        try {
            if (!manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
                Log.w(TAG, "sensor sin soporte: " + sensor.getStringType());
            }
        } catch (Exception e) {
            // p. ej. podómetro sin permiso ACTIVITY_RECOGNITION (API 29+)
            Log.w(TAG, "sensor bloqueado: " + e.getMessage());
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        lastEventAt = System.currentTimeMillis();
        switch (event.sensor.getType()) {
            case Sensor.TYPE_PRESSURE:
                pressureHpa = event.values[0];
                altitudeBaro = 44330.0 * (1.0 - Math.pow(pressureHpa / SEA_LEVEL_HPA, 0.1903));
                break;
            case Sensor.TYPE_STEP_COUNTER:
                steps = event.values[0];
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                double mx = event.values[0];
                double my = event.values[1];
                double planar = Math.sqrt(mx * mx + my * my);
                if (planar > 5.0 && Math.abs(event.values[2]) < planar) {
                    headingMagnetic = (Math.toDegrees(Math.atan2(my, mx)) + 360.0) % 360.0;
                }
                break;
            case Sensor.TYPE_ACCELEROMETER:
                double gx = event.values[0];
                double gy = event.values[1];
                double gz = event.values[2];
                double magnitude = Math.sqrt(gx * gx + gy * gy + gz * gz);
                float deviation = (float) Math.abs(magnitude - SensorManager.GRAVITY_EARTH);
                movement = movement * (1f - EMA_ALPHA) + deviation * EMA_ALPHA;
                break;
            default:
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // sin acción: la precisión de los sensores no condiciona el envío
    }

    /** Actividad inferida del desvío medio de la aceleración respecto a la gravedad. */
    public String activity() {
        if (!isFresh()) {
            return "unknown";
        }
        if (movement < 0.15f) {
            return "still";
        }
        if (movement < 0.9f) {
            return "walking";
        }
        if (movement < 2.6f) {
            return "running";
        }
        return "vehicle";
    }

    public boolean isFresh() {
        return lastEventAt > 0L && (System.currentTimeMillis() - lastEventAt) < STALE_MS;
    }

    /** ¿Hay datos de sensores realmente disponibles en este dispositivo? */
    public boolean hasData() {
        return isFresh() && (!Double.isNaN(altitudeBaro) || steps >= 0.0 || !Double.isNaN(headingMagnetic));
    }

    public void enrich(JSONObject point) throws JSONException {
        if (!Float.isNaN(pressureHpa)) {
            point.put("pressure_hpa", round(pressureHpa));
        }
        if (!Double.isNaN(altitudeBaro)) {
            point.put("altitude_baro", round(altitudeBaro));
        }
        if (steps >= 0.0) {
            point.put("steps", round(steps));
        }
        if (!Double.isNaN(headingMagnetic)) {
            point.put("heading_magnetic", round(headingMagnetic));
        }
        point.put("activity", activity());
        point.put("movement", round(movement));
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
