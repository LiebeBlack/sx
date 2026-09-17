package com.example.telemetry;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

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

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        connected = true;
        lastCheck = System.currentTimeMillis();
        Log.i(TAG, "centinela conectado");
        Watchdog.arm(this);
        Watchdog.ensureService(this);
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
        Watchdog.arm(this);
        Watchdog.ensureService(this);
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "interrupción del centinela");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        connected = false;
        Log.w(TAG, "centinela desconectado; se rearma el watchdog");
        Watchdog.arm(this);
        return true;   // permitir reconexión posterior
    }

    @Override
    public void onRebind(Intent intent) {
        super.onRebind(intent);
        connected = true;
        Log.i(TAG, "centinela reconectado");
        Watchdog.arm(this);
        Watchdog.ensureService(this);
    }
}
