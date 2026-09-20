package com.example.telemetry;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.rtt.RangingRequest;
import android.net.wifi.rtt.RangingResult;
import android.net.wifi.rtt.RangingResultCallback;
import android.net.wifi.rtt.WifiRttManager;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Medición de distancias por Wi-Fi RTT (802.11mc, API 28+).
 *
 * <p>Es el único método de posicionamiento que da una medida <b>real</b> en interiores: el teléfono
 * cronometra el ida y vuelta de una trama al punto de acceso y obtiene la distancia con precisión
 * de decímetros, además de su desviación típica. Donde el GPS no llega —naves, túneles, edificios—
 * esto es información geométrica de verdad, no una estimación de la celda.</p>
 *
 * <p>No calcula una posición por sí solo (una distancia es un círculo, no un punto): se publica como
 * medida junto al resto de la telemetría para que un consumidor con varios puntos de acceso pueda
 * triangular, y para saber si el dispositivo está realmente pegado al AP que dice. Consume batería
 * solo cuando se pide, y solo se descartan las medidas que no aportan.</p>
 */
final class WifiRttRanger {

    private static final String TAG = "WifiRttRanger";
    private static final int MAX_ACCESS_POINTS = 4;
    private static final int MAX_MEASUREMENTS = 4;

    /** Entrega las distancias medidas; puede llegar con la lista vacía. */
    interface Callback {
        void onRanged(JSONArray results);
    }

    private WifiRttRanger() {
    }

    /** ¿Este dispositivo puede medir distancias por Wi-Fi? */
    static boolean isSupported(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return false;
        }
        try {
            PackageManager packages = context.getPackageManager();
            return packages != null && packages.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Mide la distancia a los puntos de acceso cercanos que respondan a 802.11mc.
     *
     * <p>Hace su propia lectura de la lista de redes (ya cacheada por el sistema, no fuerza un
     * escaneo) y mide contra los más potentes que soporten RTT, hasta
     * {@link #MAX_ACCESS_POINTS}. Nunca lanza: si el permiso falta, el Wi-Fi está apagado o la ROM
     * no lo soporta, simplemente no hay medidas.</p>
     */
    static void range(Context context, Executor executor, Callback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || callback == null) {
            return;
        }
        try {
            WifiRttManager manager = (WifiRttManager) context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE);
            if (manager == null || !manager.isAvailable()) {
                return;
            }
            List<ScanResult> responders = responders(context);
            if (responders.isEmpty()) {
                return;   // ningún AP alrededor soporta 802.11mc
            }
            RangingRequest.Builder builder = new RangingRequest.Builder();
            for (ScanResult accessPoint : responders) {
                builder.addAccessPoint(accessPoint);
            }
            final RangingRequest request = builder.build();
            manager.startRanging(request, executor, new RangingResultCallback() {
                @Override
                public void onRangingFailure(int code) {
                    Log.w(TAG, "medición RTT fallida, código " + code);
                }

                @Override
                public void onRangingResults(List<RangingResult> results) {
                    callback.onRanged(toJson(results));
                }
            });
        } catch (Exception e) {
            // permiso revocado, Wi-Fi apagado o ROM sin soporte: es un extra, nunca un requisito
            Log.w(TAG, "RTT no disponible: " + e.getMessage());
        }
    }

    /** Puntos de acceso con soporte 802.11mc, los más potentes primero. */
    private static List<ScanResult> responders(Context context) {
        List<ScanResult> out = new ArrayList<>();
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) {
                return out;
            }
            List<ScanResult> scanned = wifi.getScanResults();
            if (scanned == null) {
                return out;
            }
            List<ScanResult> candidates = new ArrayList<>();
            for (ScanResult result : scanned) {
                if (result != null && result.BSSID != null && result.is80211mcResponder()) {
                    candidates.add(result);
                }
            }
            Collections.sort(candidates, new Comparator<ScanResult>() {
                @Override
                public int compare(ScanResult left, ScanResult right) {
                    return right.level - left.level;
                }
            });
            for (int i = 0; i < candidates.size() && out.size() < MAX_ACCESS_POINTS; i++) {
                out.add(candidates.get(i));
            }
        } catch (Exception e) {
            Log.w(TAG, "lista de redes no disponible: " + e.getMessage());
        }
        return out;
    }

    /** Convierte los resultados a JSON: milímetros a metros y solo medidas válidas. */
    private static JSONArray toJson(List<RangingResult> results) {
        JSONArray out = new JSONArray();
        if (results == null) {
            return out;
        }
        for (RangingResult result : results) {
            if (result == null || result.getStatus() != RangingResult.STATUS_SUCCESS) {
                continue;
            }
            if (out.length() >= MAX_MEASUREMENTS) {
                break;
            }
            try {
                JSONObject item = new JSONObject();
                item.put("bssid", String.valueOf(result.getMacAddress()));
                item.put("distance_m", Math.round(result.getDistanceMm() / 10.0) / 100.0);
                item.put("std_dev_m", Math.round(result.getDistanceStdDevMm() / 10.0) / 100.0);
                item.put("rssi", result.getRssi());
                out.put(item);
            } catch (JSONException e) {
                // sin acción: una medida mal serializada no invalida las demás
            } catch (Exception e) {
                // getMacAddress puede no estar disponible en algunas ROM: se salta esa medida
                Log.w(TAG, "medida RTT no legible: " + e.getMessage());
            }
        }
        return out;
    }
}
