package com.example.telemetry;

import android.content.Context;
import android.location.GnssStatus;
import android.location.LocationManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Estado de la constelación GNSS: satélites visibles/en uso, SNR medio y mejor, y constelaciones
 * presentes (GPS, GLONASS, Galileo, BeiDou, QZSS, IRNSS, SBAS). Sirve para saber si la posición
 * es realmente buena o solo un mal fix de red.
 */
public class GnssMonitor extends GnssStatus.Callback {

    private static final String TAG = "GnssMonitor";
    /** Tope de satélites que viajan en cada punto: acota el payload sin perder lo relevante. */
    private static final int MAX_SATELLITES = 16;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile int visible = 0;
    private volatile int usedInFix = 0;
    private volatile double snrBest = 0.0;
    private volatile double snrAvg = 0.0;
    private volatile String constellations = "";
    private volatile JSONArray satellites = new JSONArray();
    private volatile int maxSatellites = MAX_SATELLITES;
    private volatile long lastUpdate = 0L;
    private volatile boolean registered = false;

    public GnssMonitor(Context context) {
        this.context = context.getApplicationContext();
    }

    /** En Android Go se envía menos detalle por satélite: mismo dato útil, menos payload. */
    public void setLowRam(boolean lowRam) {
        this.maxSatellites = lowRam ? 6 : MAX_SATELLITES;
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
        // Detalle por satélite: permite saber si el fix es multi-constelación y con qué calidad
        // real, y auditar por qué una posición salió con error. Se envían los que aportan algo.
        JSONArray sats = new JSONArray();

        for (int i = 0; i < count; i++) {
            double snr = status.getSnr(i);
            boolean usedInFix = status.usedInFix(i);
            String name = constellationName(status.getConstellationType(i));
            if (usedInFix) {
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
            if (types.indexOf(name) < 0) {
                if (types.length() > 0) {
                    types.append(',');
                }
                types.append(name);
            }
            if (sats.length() < maxSatellites && (snr > 0.0 || usedInFix)) {
                try {
                    JSONObject sat = new JSONObject();
                    sat.put("sys", name);
                    sat.put("svid", status.getSvid(i));
                    sat.put("cn0", round(snr));
                    sat.put("used", usedInFix);
                    if (status.hasElevation(i)) {
                        sat.put("elev", Math.round(status.getElevation(i)));
                    }
                    if (status.hasAzimuth(i)) {
                        sat.put("azim", Math.round(status.getAzimuth(i)));
                    }
                    sats.put(sat);
                } catch (JSONException e) {
                    // el detalle de satélites es un extra: nunca debe romper la captura
                }
            }
        }

        satellites = sats;
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
        JSONArray sats = satellites;
        if (sats.length() > 0) {
            gnss.put("sats", sats);
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
