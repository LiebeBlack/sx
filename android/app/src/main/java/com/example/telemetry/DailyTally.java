package com.example.telemetry;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Locale;

/**
 * Cuánto se ha recorrido <b>hoy</b>, que es la pregunta que un total de sesión no responde.
 *
 * <p>El servidor y el visor ya calculan la distancia de un trayecto, pero hasta ahora nadie guardaba
 * el día: al reiniciar el teléfono o al abrir la app por la mañana, el recorrido de la jornada era
 * otra vez una incógnita. Aquí se acumula <b>una sola vez por punto aceptado</b>, en el mismo sitio
 * donde el punto sale hacia los tres canales, así que el total diario cuenta exactamente los mismos
 * puntos que se publican.</p>
 *
 * <p>Tres decisiones que conviene conocer:</p>
 *
 * <ul>
 *   <li><b>El día es el día local del teléfono</b>, y cambia a medianoche: el recorrido de un día no
 *       se arrastra al siguiente, porque «cuánto llevo hoy» empieza donde empieza el día.</li>
 *   <li><b>Solo suma tramos creíbles</b>, con las mismas reglas que el filtro de traza
 *       ({@link TrackFilter#MAX_GAP_SECONDS}, {@link TrackFilter#MAX_IMPLIED_SPEED_MPS}): un salto de
 *       GPS no es un desplazamiento, y un hueco largo —la app estuvo parada o el teléfono sin
 *       cobertura— no se atribuye a nadie, porque no se sabe qué camino se hizo. En ese caso la
 *       referencia avanza igual, así que el siguiente tramo se mide desde donde se está de verdad.</li>
 *   <li><b>Se mide sobre la traza suavizada</b> cuando el punto la trae, que es la misma que dibuja la
 *       web: así el número que se ve en el teléfono y el que se deduce en el navegador coinciden.</li>
 * </ul>
 *
 * <p><b>Coste.</b> Unas pocas operaciones y un {@code apply()} por punto aceptado —el mismo orden de
 * coste que el latido del watchdog— y una limpieza al día. Se conservan {@value #KEEP_DAYS} días: la
 * medida es de hoy, no un histórico paralelo al que ya publican el Gist y el repositorio.</p>
 */
public final class DailyTally {

    private static final String TAG = "DailyTally";

    /** Días que se conservan en las preferencias (el actual incluido). */
    private static final int KEEP_DAYS = 14;
    /** Días anteriores que muestra la tarjeta de estado, además de hoy. */
    private static final int SHOWN_DAYS = 7;

    /** Un sufijo por día natural: {@code daily.m.2026-09-20} son los metros recorridos ese día. */
    private static final String METERS_PREFIX = "daily.m.";
    private static final String POINTS_PREFIX = "daily.p.";

    /** Última posición medida y su instante, para saber qué tramo sumar al siguiente punto. */
    private static final String KEY_REF_DAY = "daily.ref.day";
    private static final String KEY_REF_TS = "daily.ref.ts";
    private static final String KEY_REF_LAT = "daily.ref.lat";
    private static final String KEY_REF_LNG = "daily.ref.lng";

    private DailyTally() {
    }

    /**
     * Suma al día el tramo que acaba de recorrerse. Se llama por cada punto ya aceptado.
     *
     * <p>Nunca lanza: una medida es información, no algo que pueda tumbar el rastreo.</p>
     */
    public static void record(Context context, JSONObject point) {
        try {
            if (context == null || point == null) {
                return;
            }
            // La traza suavizada cuando existe: es la misma sobre la que mide el visor web.
            double lat = point.has("smooth_lat") ? point.optDouble("smooth_lat", Double.NaN)
                    : point.optDouble("lat", Double.NaN);
            double lng = point.has("smooth_lng") ? point.optDouble("smooth_lng", Double.NaN)
                    : point.optDouble("lng", Double.NaN);
            if (!plausible(lat, lng)) {
                return;
            }
            long timestamp = point.optLong("timestamp_ms", 0L);
            if (timestamp <= 0L) {
                timestamp = System.currentTimeMillis();
            }

            SharedPreferences prefs = AppConfig.prefs(context);
            String day = dayKey(timestamp);
            boolean newDay = !day.equals(prefs.getString(KEY_REF_DAY, ""));
            double added = 0.0;
            if (!newDay) {
                long previousTs = prefs.getLong(KEY_REF_TS, 0L);
                double previousLat = Double.longBitsToDouble(prefs.getLong(KEY_REF_LAT, 0L));
                double previousLng = Double.longBitsToDouble(prefs.getLong(KEY_REF_LNG, 0L));
                double seconds = Math.abs(timestamp - previousTs) / 1000.0;
                double meters = TrackFilter.distanceMeters(previousLat, previousLng, lat, lng);
                if (seconds > 0.0 && seconds <= TrackFilter.MAX_GAP_SECONDS
                        && (meters / seconds) <= TrackFilter.MAX_IMPLIED_SPEED_MPS) {
                    added = meters;
                }
            }

            String metersKey = METERS_PREFIX + day;
            String pointsKey = POINTS_PREFIX + day;
            SharedPreferences.Editor editor = prefs.edit();
            editor.putFloat(metersKey, prefs.getFloat(metersKey, 0f) + (float) added);
            editor.putInt(pointsKey, prefs.getInt(pointsKey, 0) + 1);
            editor.putString(KEY_REF_DAY, day)
                    .putLong(KEY_REF_TS, timestamp)
                    .putLong(KEY_REF_LAT, Double.doubleToRawLongBits(lat))
                    .putLong(KEY_REF_LNG, Double.doubleToRawLongBits(lng));
            if (newDay) {
                // Una vez al día, y no en cada punto: recorrer todas las claves tiene su coste.
                prune(prefs, editor, day);
            }
            editor.apply();
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo anotar el tramo del día: " + t.getMessage());
        }
    }

    /** Metros recorridos hoy. */
    private static double todayMeters(Context context) {
        return meters(context, dayKey(System.currentTimeMillis()));
    }

    /** Puntos aceptados hoy: los mismos que se han publicado. */
    private static int todayPoints(Context context) {
        return points(context, dayKey(System.currentTimeMillis()));
    }

    /** El día de hoy en una palabra, para la notificación: {@code 12.40 km} o {@code 0 m}. */
    public static String todayShort(Context context) {
        return format(todayMeters(context));
    }

    /**
     * La medida del día y los días anteriores con datos, en las líneas que muestra la tarjeta de
     * estado: hoy primero, y después el día a día de la última semana.
     */
    public static String summary(Context context) {
        long now = System.currentTimeMillis();
        StringBuilder text = new StringBuilder();
        text.append("hoy (").append(shortDay(dayKey(now))).append("): ")
                .append(format(todayMeters(context))).append(" · ")
                .append(todayPoints(context)).append(" puntos");
        int shown = 0;
        for (int back = 1; back <= SHOWN_DAYS; back++) {
            String day = shiftDay(now, -back);
            double meters = meters(context, day);
            int points = points(context, day);
            if (points == 0 && meters <= 0.0) {
                continue;   // un día sin datos no se inventa: simplemente no aparece
            }
            text.append("\n  · ").append(shortDay(day)).append(": ").append(format(meters))
                    .append(" · ").append(points).append(" puntos");
            shown++;
        }
        if (shown == 0) {
            text.append("\n  (el día a día empieza a partir de ahora)");
        }
        return text.toString();
    }

    /* ------------------------------------------------------------------ lectura */

    private static double meters(Context context, String day) {
        try {
            return AppConfig.prefs(context).getFloat(METERS_PREFIX + day, 0f);
        } catch (Throwable t) {
            return 0.0;
        }
    }

    private static int points(Context context, String day) {
        try {
            return AppConfig.prefs(context).getInt(POINTS_PREFIX + day, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Borra los días que ya no se muestran, para que la medida no crezca sin fin. */
    private static void prune(SharedPreferences prefs, SharedPreferences.Editor editor, String today) {
        String oldest = shiftDayOf(today, -(KEEP_DAYS - 1));
        for (String key : prefs.getAll().keySet()) {
            String day = null;
            if (key.startsWith(METERS_PREFIX)) {
                day = key.substring(METERS_PREFIX.length());
            } else if (key.startsWith(POINTS_PREFIX)) {
                day = key.substring(POINTS_PREFIX.length());
            }
            // Las claves son fechas ISO, así que el orden alfabético es el orden temporal.
            if (day != null && day.compareTo(oldest) < 0) {
                editor.remove(key);
            }
        }
    }

    /* ------------------------------------------------------------------ fechas y formato */

    /** Día natural local de un instante, en ISO ({@code 2026-09-20}): ordena y compara como texto. */
    private static String dayKey(long timestamp) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(timestamp);
        return String.format(Locale.US, "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH));
    }

    /** Día desplazado en días naturales, sin equivocarse con los cambios de hora. */
    private static String shiftDay(long timestamp, int days) {
        return shiftDayOf(dayKey(timestamp), days);
    }

    private static String shiftDayOf(String day, int days) {
        try {
            Calendar calendar = Calendar.getInstance();
            calendar.set(Integer.parseInt(day.substring(0, 4)),
                    Integer.parseInt(day.substring(5, 7)) - 1,
                    Integer.parseInt(day.substring(8, 10)));
            calendar.add(Calendar.DAY_OF_MONTH, days);
            return dayKey(calendar.getTimeInMillis());
        } catch (Throwable t) {
            return day;
        }
    }

    /** {@code 2026-09-20} → {@code 20/09}, que es lo que se lee de un vistazo. */
    private static String shortDay(String day) {
        return day.length() == 10 ? day.substring(8) + "/" + day.substring(5, 7) : day;
    }

    private static String format(double meters) {
        if (meters >= 1000.0) {
            return String.format(Locale.US, "%.2f km", meters / 1000.0);
        }
        return Math.round(meters) + " m";
    }

    private static boolean plausible(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng)
                && lat >= -90.0 && lat <= 90.0 && lng >= -180.0 && lng <= 180.0
                && !(lat == 0.0 && lng == 0.0);   // el centinela de «sin posición» no es un sitio
    }
}
