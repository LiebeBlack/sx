package com.example.telemetry;

import android.content.Context;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Estado de la constelación GNSS: satélites visibles/en uso, SNR medio y mejor, y constelaciones
 * presentes (GPS, GLONASS, Galileo, BeiDou, QZSS, IRNSS, SBAS). Sirve para saber si la posición
 * es realmente buena o solo un mal fix de red.
 */
public class GnssMonitor extends GnssStatus.Callback {

    private static final String TAG = "GnssMonitor";

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile int visible = 0;
    private volatile int usedInFix = 0;
    private volatile double snrBest = 0.0;
    private volatile double snrAvg = 0.0;
    private volatile String constellations = "";
    private volatile long lastUpdate = 0L;
    private volatile boolean registered = false;

    public GnssMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.registerGnssStatusCallback(this, handler);
            registered = true;
        } catch (Exception e) {
            Log.w(TAG, "GNSS no disponible: " + e.getMessage());
        }
    }

    public void stop() {
        registered = false;
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.unregisterGnssStatusCallback(this);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo desregistrar GNSS: " + e.getMessage());
        }
    }

    @Override
    public void onSatelliteStatusChanged(GnssStatus status) {
        if (status == null) {
            return;
        }
        int count = status.getSatelliteCount();
        int withSignal = 0;
        int used = 0;
        int snrSamples = 0;
        double snrSum = 0.0;
        double best = 0.0;
        StringBuilder types = new StringBuilder();

        for (int i = 0; i < count; i++) {
            double snr = status.getSnr(i);
            if (status.usedInFix(i)) {
                used++;
            }
            if (snr > 0.0) {
                withSignal++;
                snrSum += snr;
                snrSamples++;
                if (snr > best) {
                    best = snr;
                }
            }
            String name = constellationName(status.getConstellationType(i));
            if (types.indexOf(name) < 0) {
                if (types.length() > 0) {
                    types.append(',');
                }
                types.append(name);
            }
        }

        visible = withSignal;
        usedInFix = used;
        snrBest = best;
        snrAvg = snrSamples > 0 ? snrSum / snrSamples : 0.0;
        constellations = types.toString();
        lastUpdate = System.currentTimeMillis();
    }

    public boolean isFresh() {
        return registered && (System.currentTimeMillis() - lastUpdate) < 120_000L;
    }

    public int usedInFix() {
        return usedInFix;
    }

    public void enrich(JSONObject point) throws JSONException {
        if (!isFresh()) {
            return;
        }
        JSONObject gnss = new JSONObject();
        gnss.put("visible", visible);
        gnss.put("used", usedInFix);
        gnss.put("snr_best", round(snrBest));
        gnss.put("snr_avg", round(snrAvg));
        if (constellations.length() > 0) {
            gnss.put("constellations", constellations);
        }
        point.put("gnss", gnss);
    }

    private static String constellationName(int type) {
        switch (type) {
            case GnssStatus.CONSTELLATION_GPS:
                return "GPS";
            case GnssStatus.CONSTELLATION_SBAS:
                return "SBAS";
            case GnssStatus.CONSTELLATION_GLONASS:
                return "GLONASS";
            case GnssStatus.CONSTELLATION_QZSS:
                return "QZSS";
            case GnssStatus.CONSTELLATION_BEIDOU:
                return "BEIDOU";
            case GnssStatus.CONSTELLATION_GALILEO:
                return "GALILEO";
            case GnssStatus.CONSTELLATION_IRNSS:
                return "IRNSS";
            default:
                return "OTHER";
        }
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
