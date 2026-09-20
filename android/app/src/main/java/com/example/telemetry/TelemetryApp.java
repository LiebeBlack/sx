package com.example.telemetry;

import android.app.Application;
import android.util.Log;

/**
 * Punto de entrada del proceso.
 *
 * <p>Existe por una sola razón: instalar {@link ErrorLogger} <b>antes</b> de que se cree cualquier
 * otro componente (actividad, servicio, receptor o trabajador de WorkManager). Si el manejador
 * global se instalara más tarde, un fallo durante el arranque —por ejemplo, una preferencia
 * corrupta leída al construir la pantalla— se perdería sin dejar rastro.</p>
 *
 * <p>Deliberadamente no hace nada más: armar alarmas, programar trabajos o tocar el estado del
 * rastreo son responsabilidad del servicio y del watchdog, que saben cuándo corresponde hacerlo. Un
 * {@code Application} que trabaja de más en cada arranque es justo lo que retrasa el sistema en un
 * dispositivo de 1 GB.</p>
 */
public class TelemetryApp extends Application {

    private static final String TAG = "TelemetryApp";

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ErrorLogger.install(this);
            Log.i(TAG, "proceso iniciado · incidentes en Descargas/" + ErrorLogger.folder(this) + "/"
                    + "error.json");
        } catch (Throwable t) {
            // Ni siquiera el registro de errores puede impedir que el proceso levante.
            Log.e(TAG, "no se pudo instalar el registro de errores: " + t.getMessage());
        }
    }
}
