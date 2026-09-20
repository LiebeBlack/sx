package com.example.telemetry;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;

/**
 * Centinela de accesibilidad: NO lee el contenido de la pantalla ni de otras aplicaciones
 * ({@code canRetrieveWindowContent="false"}). Solo observa cambios de ventana para detectar
 * cuándo el sistema o el usuario están interrumpiendo el rastreo y rearmarlo.
 * Coste prácticamente nulo: como máximo una comprobación cada 30 segundos.
 */
public class TelemetryAccessibilityService extends AccessibilityService {

    private static final String TAG = "TelemetrySentinel";
    private static final long CHECK_INTERVAL_MS = 30_000L;

    private static volatile boolean connected = false;
    private static volatile long lastCheck = 0L;

    public static boolean isConnected() {
        return connected;
    }

    /**
     * ¿Está el centinela habilitado en los ajustes del sistema?
     *
     * <p>El flag {@link #isConnected()} solo refleja si el framework nos ha enlazado en este
     * proceso: tras un reinicio del teléfono el servicio puede estar habilitado y aún no enlazado.
     * Para el panel de estado se consulta al sistema, que es la fuente de verdad.</p>
     */
    public static boolean isEnabledInSystem(Context context) {
        try {
            AccessibilityManager manager =
                    (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager == null) {
                return connected;
            }
            for (AccessibilityServiceInfo info
                    : manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
                if (info == null || info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
                    continue;
                }
                if (context.getPackageName().equals(info.getResolveInfo().serviceInfo.packageName)) {
                    return true;
                }
            }
        } catch (Exception e) {
            return connected;
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        lastCheck = System.currentTimeMillis();
        Log.i(TAG, "centinela conectado");
        rearm();
    }

    /**
     * Rearme con red de seguridad: un centinela que se cae deja de vigilar, así que un rechazo del
     * sistema aquí nunca puede propagarse como excepción; se registra y se sigue.
     */
    private void rearm() {
        try {
            Watchdog.arm(this);
            Watchdog.ensureService(this);
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo rearmar el rastreo: " + t.getMessage());
            ErrorLogger.record("TelemetryAccessibilityService", "no se pudo rearmar el rastreo", t);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCheck < CHECK_INTERVAL_MS) {
            return;
        }
        lastCheck = now;
        rearm();
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "interrupción del centinela");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        Log.w(TAG, "centinela desconectado; se rearma el watchdog");
        rearm();
        return true;   // permitir reconexión posterior
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        connected = true;
        Log.i(TAG, "centinela reconectado");
        rearm();
    }
}
