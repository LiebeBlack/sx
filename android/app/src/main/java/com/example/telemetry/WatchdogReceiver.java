package com.example.telemetry;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Recibe el arranque del dispositivo, la actualización del paquete, el desbloqueo del usuario
 * y la propia alarma del watchdog. En todos los casos rearma la alarma y, si el rastreo está
 * habilitado y el latido está vencido, relanza el servicio en primer plano.
 *
 * <p>Además es donde se anota la <b>cadena de custodia</b> del equipo: el apagado, el modo avión, el
 * reinicio y la actualización de la app. Son los hitos que deja detrás un robo, y quedan en
 * {@code error.json} con el recorrido del día al lado para que el registro sirva de algo cuando
 * alguien lo lea después.</p>
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
        recordCustodyEvent(context, action, intent);
        // 'tracking_enabled' sobrevive a reinicios: basta con rearmar la alarma y verificar el servicio.
        try {
            // La protección contra desinstalación se reaplica en cada señal —arranque, actualización
            // del paquete y latido—, que es justo cuando se puede haber quedado a medias.
            UninstallGuard.enforce(context);
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

    /**
     * Anota los hitos de mantenimiento del equipo, y solo con el rastreo activado: con la app parada
     * serían ruido en el registro del usuario.
     *
     * <p><b>Lo que esta cadena de custodia no puede anotar</b> es una parada forzosa desde Ajustes: el
     * sistema mata el proceso y no queda código que se ejecute. Ese hueco está documentado, porque
     * prometer vigilancia donde no la hay sería peor que no tenerla.</p>
     */
    private void recordCustodyEvent(Context context, String action, Intent intent) {
        try {
            if (!Watchdog.isTrackingEnabled(context)) {
                return;
            }
            if (action.contains("SHUTDOWN")) {
                // Un apagado con el rastreo vivo es el primer movimiento de un robo: se anota con el
                // recorrido del día, que es el último dato útil de la jornada.
                ErrorLogger.record("WatchdogReceiver", "el dispositivo se apaga con el rastreo activo,"
                        + " recorrido de hoy " + DailyTally.todayShort(context), null);
            } else if (action.contains("AIRPLANE")) {
                boolean on = intent != null && intent.getBooleanExtra("state", false);
                ErrorLogger.record("WatchdogReceiver", "modo avión " + (on ? "activado" : "desactivado")
                        + " con el rastreo activo, recorrido de hoy " + DailyTally.todayShort(context), null);
            } else if (action.equals(Intent.ACTION_BOOT_COMPLETED) || action.contains("QUICKBOOT")) {
                ErrorLogger.record("WatchdogReceiver",
                        "dispositivo reiniciado con el rastreo activado: se relanza el servicio", null);
            } else if (action.equals(Intent.ACTION_MY_PACKAGE_REPLACED)) {
                ErrorLogger.record("WatchdogReceiver",
                        "la aplicación se actualizó con el rastreo activado: se rearma la alarma", null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo anotar el hito de mantenimiento: " + t.getMessage());
        }
    }
}
