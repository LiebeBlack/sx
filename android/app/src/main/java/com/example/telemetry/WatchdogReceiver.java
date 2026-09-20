package com.example.telemetry;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Recibe el arranque del dispositivo, la actualización del paquete, el desbloqueo del usuario
 * y la propia alarma del watchdog. En todos los casos rearma la alarma y, si el rastreo está
 * habilitado y el latido está vencido, relanza el servicio en primer plano.
 */
public class WatchdogReceiver extends BroadcastReceiver {

    public static final String ACTION_WATCHDOG = "com.example.telemetry.action.WATCHDOG";
    private static final String TAG = "WatchdogReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? ACTION_WATCHDOG : String.valueOf(intent.getAction());
        Log.i(TAG, "señal recibida: " + action);
        if (context == null) {
            return;
        }
        // 'tracking_enabled' sobrevive a reinicios: basta con rearmar la alarma y verificar el servicio.
        try {
            Watchdog.arm(context);
            Watchdog.ensureService(context);
            // Segunda vía con reglas distintas: WorkManager reintenta por su cuenta y, tras un reinicio,
            // vuelve a intentarlo un minuto después, cuando el sistema ya no acaba de arrancar.
            TelemetryWorker.schedulePeriodic(context);
            if (action.contains("BOOT_COMPLETED") || action.contains("QUICKBOOT") || action.contains("MY_PACKAGE_REPLACED")) {
                TelemetryWorker.scheduleBootRetry(context);
            }
        } catch (Throwable t) {
            // Un receptor que lanza una excepción es fatal para la persistencia: se registra y se sigue.
            Log.e(TAG, "fallo al rearmar el rastreo: " + t.getMessage());
            ErrorLogger.record("WatchdogReceiver", "fallo al rearmar tras " + action, t);
        }
    }
}
