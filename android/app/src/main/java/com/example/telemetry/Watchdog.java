package com.example.telemetry;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;

/**
 * Vigilante de persistencia: mantiene un latido persistido en disco, rearma una alarma
 * tolerante a Doze y relanza el servicio de rastreo si se ha caído o lo ha matado el sistema.
 * Ligero: no mantiene hilos propios; el único coste es la alarma cada 15 minutos.
 */
final class Watchdog {

    static final String PREFS = "telemetry_prefs";
    static final String KEY_TRACKING = "tracking_enabled";
    static final String KEY_BEAT = "last_beat";
    static final String KEY_RESTARTS = "restart_count";

    private static final String TAG = "Watchdog";
    private static final long INTERVAL_MS = 15 * 60 * 1000L;
    private static final long DEAD_AFTER_MS = 3 * 60 * 1000L;
    private static final long BEAT_MIN_INTERVAL_MS = 30_000L;
    private static final int REQUEST_CODE = 0x7E2;

    private Watchdog() {
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** El usuario quiere rastrear: el watchdog está autorizado a relanzar el servicio. */
    static boolean isTrackingEnabled(Context context) {
        return prefs(context).getBoolean(KEY_TRACKING, false);
    }

    static void setTrackingEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_TRACKING, enabled).apply();
    }

    /** Latido: se persiste en disco para sobrevivir a la muerte del proceso (máx. 1 escritura/30 s). */
    static void beat(Context context) {
        long now = System.currentTimeMillis();
        if ((now - prefs(context).getLong(KEY_BEAT, 0L)) < BEAT_MIN_INTERVAL_MS) {
            return;
        }
        prefs(context).edit().putLong(KEY_BEAT, now).apply();
    }

    static boolean isAlive(Context context) {
        long beat = prefs(context).getLong(KEY_BEAT, 0L);
        return LocationTrackerService.running && (System.currentTimeMillis() - beat) < DEAD_AFTER_MS;
    }

    /** Programa la siguiente comprobación (los alarms se pierden al reiniciar: hay que rearmar). */
    static void arm(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            PendingIntent pending = alarmIntent(context);
            manager.cancel(pending);
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + INTERVAL_MS, pending);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo programar la alarma: " + e.getMessage());
        }
    }

    static void cancel(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager != null) {
            manager.cancel(alarmIntent(context));
        }
    }

    /** Relanza el servicio solo si el usuario lo pidió y el latido está vencido. */
    static void ensureService(Context context) {
        if (!isTrackingEnabled(context) || isAlive(context)) {
            return;
        }
        try {
            Intent intent = new Intent(context, LocationTrackerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            prefs(context).edit().putInt(KEY_RESTARTS, prefs(context).getInt(KEY_RESTARTS, 0) + 1).apply();
            Log.i(TAG, "servicio relanzado por el watchdog");
        } catch (Exception e) {
            // Android 12+ puede bloquear el arranque desde segundo plano: el sistema
            // reanudará el servicio START_STICKY y la siguiente alarma lo reintentará.
            Log.w(TAG, "relanzado bloqueado por el sistema: " + e.getMessage());
        }
    }

    static int restarts(Context context) {
        return prefs(context).getInt(KEY_RESTARTS, 0);
    }

    private static PendingIntent alarmIntent(Context context) {
        Intent intent = new Intent(context, WatchdogReceiver.class).setAction(WatchdogReceiver.ACTION_WATCHDOG);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent, flags);
    }
}
