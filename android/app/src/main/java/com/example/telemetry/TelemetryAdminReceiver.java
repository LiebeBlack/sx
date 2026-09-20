package com.example.telemetry;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Administrador de dispositivo: protege la app frente a desinstalaciones accidentales,
 * permite bloquear el terminal de forma remota y evita que el servicio se detenga sin
 * pasar por los ajustes del sistema. No aplica políticas de contraseña ni borrado de datos.
 */
public class TelemetryAdminReceiver extends DeviceAdminReceiver {

    private static final String TAG = "TelemetryAdmin";

    public static boolean isActive(Context context) {
        DevicePolicyManager manager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return manager != null && manager.isAdminActive(new ComponentName(context, TelemetryAdminReceiver.class));
    }

    /** Bloquea la pantalla al instante (requiere política force-lock). */
    public static boolean lockNow(Context context) {
        DevicePolicyManager manager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (manager == null || !isActive(context)) {
            return false;
        }
        try {
            manager.lockNow();
            return true;
        } catch (SecurityException e) {
            Log.w(TAG, "bloqueo no permitido: " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.i(TAG, "administrador activado");
        rearm(context, true);
    }

    @Override
    public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.w(TAG, "administrador desactivado");
        // Desactivar el administrador es el primer paso de quien intenta desinstalar la app, y sin ser
        // propietario del dispositivo no se puede impedir: queda registrado para que se vea en el panel
        // y en `error.json` del teléfono, en lugar de pasar desapercibido.
        try {
            ErrorLogger.record("TelemetryAdminReceiver",
                    "el administrador de dispositivo se ha desactivado desde los ajustes", null);
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo registrar la desactivación: " + t.getMessage());
        }
        rearm(context, false);
    }

    /**
     * Rearme con red de seguridad: un receptor que lanza una excepción es un problema para el
     * sistema, no para el rastreo, así que aquí nunca se propaga nada.
     */
    private static void rearm(Context context, boolean ensureService) {
        try {
            Watchdog.arm(context);
            if (ensureService) {
                Watchdog.ensureService(context);
            }
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo rearmar el rastreo: " + t.getMessage());
            ErrorLogger.record("TelemetryAdminReceiver", "no se pudo rearmar el rastreo", t);
        }
    }

    @Override
    public CharSequence onDisableRequested(Context context, Intent intent) {
        return "Desactivar el administrador reduce la persistencia del rastreo de telemetría"
                + (UninstallGuard.isEnabled(context)
                    ? " y queda registrado: la protección contra desinstalación está activada."
                    : ".");
    }
}
