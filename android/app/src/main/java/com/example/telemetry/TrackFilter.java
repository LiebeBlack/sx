package com.example.telemetry;

/**
 * Filtro de posición ligero en el propio dispositivo.
 *
 * <p>Estado (posición, velocidad) por eje en un plano métrico local, con el mismo modelo que usa el
 * servidor: ruido de medida = precisión², ruido de proceso = 1,5 m/s² y ganancia de Kalman clásica.
 * Sirve para dos cosas:</p>
 *
 * <ul>
 *   <li>La posición que se publica ya viene limpia: el ruido del GPS parado no se cuenta como
 *       recorrido ni ensucia la traza.</li>
 *   <li>En el modo «GitHub directo» (sin servidor) no hay nadie más que pueda suavizar la traza, así
 *       que cada punto viaja con {@code smooth_lat}/{@code smooth_lng}, exactamente igual que los
 *       puntos que produce el backend.</li>
 * </ul>
 *
 * <p>Coste: unas pocas operaciones por fix, sin hilos, sin I/O y con una única reserva de memoria
 * para el resultado. No decide qué enviar: solo suaviza.</p>
 */
final class TrackFilter {

    private static final double EARTH_RADIUS_M = 6371008.8;
    private static final double A_PROCESS_MPS2 = 1.5;
    private static final double DEFAULT_ACCURACY_M = 25.0;
    private static final double ACCURACY_INFLATION = 1.8;
    /** Hueco máximo entre dos fixes para que el tramo cuente ({@link DailyTally} usa el mismo). */
    static final double MAX_GAP_SECONDS = 120.0;
    /** Velocidad implícita máxima creíble: por encima es un salto de GPS, no un desplazamiento. */
    static final double MAX_IMPLIED_SPEED_MPS = 90.0;

    private final double[] pos = new double[2];
    private final double[] vel = new double[2];
    private final double[] covP = new double[2];
    private final double[] covPv = new double[2];
    private final double[] covV = new double[2];

    private boolean initialized = false;
    private double originLat = 0.0;
    private double originLng = 0.0;
    private long lastTimeMs = 0L;
    private double lastRawLat = 0.0;
    private double lastRawLng = 0.0;
    private double outLat = 0.0;
    private double outLng = 0.0;

    /** Posición filtrada y velocidad estimada para un instante concreto. */
    static final class Result {
        final double lat;
        final double lng;
        final double speedMps;

        Result(double lat, double lng, double speedMps) {
            this.lat = lat;
            this.lng = lng;
            this.speedMps = speedMps;
        }
    }

    /**
     * Incorpora una medida bruta y devuelve la posición filtrada.
     *
     * @param monotonicMs reloj monotónico en milisegundos (nunca el de pared: un cambio de hora
     *                    alteraría el paso de tiempo del filtro).
     * @param accuracyM   precisión horizontal informada por el proveedor; {@code <= 0} usa la media.
     */
    synchronized Result update(double lat, double lng, long monotonicMs, double accuracyM) {
        if (!Double.isFinite(lat) || !Double.isFinite(lng)) {
            return snapshot();
        }
        if (!initialized) {
            reset(lat, lng, monotonicMs);
            return snapshot();
        }

        double dt = (monotonicMs - lastTimeMs) / 1000.0;
        if (dt <= 0.0) {
            // Fix repetido o desordenado: no toca el estado ni la salida.
            return snapshot();
        }
        if (dt > MAX_GAP_SECONDS) {
            // Hueco largo (sin cobertura, teléfono apagado): el filtro no puede extrapolar.
            reset(lat, lng, monotonicMs);
            return snapshot();
        }

        double moved = distanceMeters(lastRawLat, lastRawLng, lat, lng);
        if (moved / dt > MAX_IMPLIED_SPEED_MPS) {
            // Teleport del GPS: se ignora la medida entera en lugar de dejar que arrastre la posición.
            return snapshot();
        }

        double reported = accuracyM > 0.0 ? accuracyM : DEFAULT_ACCURACY_M;
        double variance = Math.pow(Math.max(1.0, reported * ACCURACY_INFLATION), 2);
        double process = A_PROCESS_MPS2 * A_PROCESS_MPS2;
        double measuredX = Math.toRadians(lng - originLng) * EARTH_RADIUS_M * Math.cos(Math.toRadians(originLat));
        double measuredY = Math.toRadians(lat - originLat) * EARTH_RADIUS_M;

        for (int axis = 0; axis < 2; axis++) {
            double measured = axis == 0 ? measuredX : measuredY;
            double predictedPos = pos[axis] + vel[axis] * dt;
            double predictedCovP = covP[axis] + 2.0 * dt * covPv[axis] + dt * dt * covV[axis]
                    + process * dt * dt * dt * dt / 4.0;
            double predictedCovPv = covPv[axis] + dt * covV[axis] + process * dt * dt * dt / 2.0;
            double predictedCovV = covV[axis] + process * dt * dt;
            double denominator = predictedCovP + variance;
            double gainP = predictedCovP / denominator;
            double gainV = predictedCovPv / denominator;
            double innovation = measured - predictedPos;
            pos[axis] = predictedPos + gainP * innovation;
            vel[axis] = vel[axis] + gainV * innovation;
            covP[axis] = (1.0 - gainP) * predictedCovP;
            covPv[axis] = (1.0 - gainP) * predictedCovPv;
            covV[axis] = predictedCovV - gainV * predictedCovPv;
        }

        lastTimeMs = monotonicMs;
        lastRawLat = lat;
        lastRawLng = lng;
        double cosLat = Math.max(1e-6, Math.cos(Math.toRadians(originLat)));
        outLng = originLng + Math.toDegrees(pos[0] / (EARTH_RADIUS_M * cosLat));
        outLat = originLat + Math.toDegrees(pos[1] / EARTH_RADIUS_M);
        return snapshot();
    }

    /** Posición filtrada actual, sin incorporar ninguna medida. */
    synchronized Result current() {
        return snapshot();
    }

    synchronized void clear() {
        initialized = false;
    }

    private void reset(double lat, double lng, long monotonicMs) {
        originLat = lat;
        originLng = lng;
        lastRawLat = lat;
        lastRawLng = lng;
        outLat = lat;
        outLng = lng;
        lastTimeMs = monotonicMs;
        for (int axis = 0; axis < 2; axis++) {
            pos[axis] = 0.0;
            vel[axis] = 0.0;
            covP[axis] = DEFAULT_ACCURACY_M * DEFAULT_ACCURACY_M;
            covPv[axis] = 0.0;
            covV[axis] = 4.0;
        }
        initialized = true;
    }

    private Result snapshot() {
        return new Result(outLat, outLng, Math.hypot(vel[0], vel[1]));
    }

    /** Distancia entre dos coordenadas en metros. La comparte {@link DailyTally}: una sola fórmula. */
    static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dPhi = p2 - p1;
        double dLambda = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dPhi * 0.5) * Math.sin(dPhi * 0.5)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dLambda * 0.5) * Math.sin(dLambda * 0.5);
        return 2.0 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}
