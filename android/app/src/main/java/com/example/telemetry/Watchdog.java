package com.example.telemetry;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
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

    /** Relanzados seguidos sin latido: si el sistema los rechaza, se espacia la alarma. */
    private static final String KEY_ATTEMPTS = "launch_attempts";

    private static final String TAG = "Watchdog";
    private static final long INTERVAL_MS = 15 * 60 * 1000L;
    private static final long MAX_INTERVAL_MS = 60 * 60 * 1000L;
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
        prefs(context).edit()
                .putBoolean(KEY_TRACKING, enabled)
                .putInt(KEY_ATTEMPTS, 0)
                .apply();
    }

    /** Intentos de relanzado consecutivos sin que el servicio haya latido. */
    static int launchAttempts(Context context) {
        return prefs(context).getInt(KEY_ATTEMPTS, 0);
    }

    /** Latido: se persiste en disco para sobrevivir a la muerte del proceso (máx. 1 escritura/30 s). */
    static void beat(Context context) {
        long now = System.currentTimeMillis();
        if ((now - prefs(context).getLong(KEY_BEAT, 0L)) < BEAT_MIN_INTERVAL_MS) {
            return;
        }
        // Si el servicio late, el relanzado ha funcionado: el contador vuelve a cero y la alarma
        // recupera su cadencia normal de 15 minutos.
        prefs(context).edit().putLong(KEY_BEAT, now).putInt(KEY_ATTEMPTS, 0).apply();
    }

    static boolean isAlive(Context context) {
        long beat = prefs(context).getLong(KEY_BEAT, 0L);
        return LocationTrackerService.running && (System.currentTimeMillis() - beat) < DEAD_AFTER_MS;
    }

    /** Programa la siguiente comprobación (los alarms se pierden al reiniciar: hay que rearmar). */
    static void arm(Context context) {
        if (!isTrackingEnabled(context)) {
            // Sin rastreo autorizado, una alarma cada 15 minutos solo despertaría la CPU para que el
            // receptor no hiciera nada: se retira. Al activar el rastreo se vuelve a armar.
            cancel(context);
            return;
        }
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            PendingIntent pending = alarmIntent(context);
            manager.cancel(pending);
            manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + intervalFor(context), pending);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo programar la alarma: " + e.getMessage());
        }
    }

    /**
     * Intervalo de la próxima comprobación.
     *
     * <p>Si el sistema ha rechazado varios relanzados seguidos, insistir cada 15 minutos solo gasta
     * batería sin conseguir nada: se espacia hasta una hora. El primer latido del servicio lo
     * devuelve a su cadencia normal.</p>
     */
    private static long intervalFor(Context context) {
        int attempts = prefs(context).getInt(KEY_ATTEMPTS, 0);
        if (attempts <= 1) {
            return INTERVAL_MS;
        }
        return Math.min(MAX_INTERVAL_MS, INTERVAL_MS << Math.min(attempts - 1, 2));
    }

    /** ¿Está el dispositivo en Doze? */
    private static boolean isDeviceIdle(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false;
        }
        try {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return power != null && power.isDeviceIdleMode();
        } catch (Exception e) {
            return false;
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
        if (isDeviceIdle(context)) {
            // En Doze el sistema rechaza cualquier arranque en primer plano desde el fondo: insistir
            // solo despierta la CPU para nada. La alarma queda armada y el sistema reanudará el
            // servicio START_STICKY en cuanto el equipo salga de Doze.
            Log.i(TAG, "Doze activo: se pospone el relanzado");
            return;
        }
        int attempts = prefs(context).getInt(KEY_ATTEMPTS, 0) + 1;
        try {
            Intent intent = new Intent(context, LocationTrackerService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            prefs(context).edit()
                    .putInt(KEY_RESTARTS, prefs(context).getInt(KEY_RESTARTS, 0) + 1)
                    .putInt(KEY_ATTEMPTS, attempts)
                    .apply();
            Log.i(TAG, "servicio relanzado por el watchdog (intento " + attempts + ")");
        } catch (Exception e) {
            // Android 12+ puede bloquear el arranque desde segundo plano: el sistema
            // reanudará el servicio START_STICKY y la siguiente alarma lo reintentará, cada vez más
            // espaciada gracias al contador de intentos.
            prefs(context).edit().putInt(KEY_ATTEMPTS, attempts).apply();
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
