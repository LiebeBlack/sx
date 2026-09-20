package com.example.telemetry;

import android.content.Context;
import android.util.Log;

import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Segunda vía de persistencia y de transmisión, además de la alarma del watchdog.
 *
 * <p>No sustituye a la alarma (que es tolerante a Doze y no depende de nada), la complementa: son
 * dos mecanismos con reglas distintas, y cuando el sistema rechaza uno el otro tiene otra
 * oportunidad. WorkManager además mantiene su propia base de datos de trabajos pendientes, así que
 * un trabajo que no llegó a ejecutarse se reintenta cuando el sistema lo permite.</p>
 *
 * <p>Hace dos cosas, en este orden:</p>
 * <ol>
 *   <li><b>Revive el rastreo</b>: comprueba el latido y, si el rastreo está habilitado y el servicio
 *       no corre, lo relanza. Si el sistema sigue rechazando el arranque en segundo plano
 *       (Android 12+), el camino fiable que queda es abrir la app: {@code MainActivity} reanuda el
 *       rastreo en primer plano, donde esa restricción no aplica.</li>
 *   <li><b>Entrega lo pendiente</b>: vacía la cola persistida por HTTP. Esta parte es la importante
 *       cuando el relanzado está bloqueado, porque WorkManager <b>sí</b> puede ejecutar código en
 *       segundo plano sin necesidad de un servicio en primer plano: los puntos acumulados no se
 *       pierden aunque el sistema no deje rastrear.</li>
 * </ol>
 */
public class TelemetryWorker extends Worker {

    private static final String TAG = "TelemetryWorker";
    private static final String PERIODIC_NAME = "telemetry-watchdog";
    private static final String BOOT_NAME = "telemetry-boot";
    private static final String FINAL_NAME = "telemetry-final-flush";

    /** Último resultado observable desde la actividad, sin binder ni broadcasts. */
    static volatile String lastRun = "sin ejecuciones";
    /** Resultado del último ciclo del canal Gist, para el panel de la actividad. */
    static volatile String lastGist = "sin publicar";

    public TelemetryWorker(Context context, WorkerParameters parameters) {
        super(context, parameters);
    }

    @Override
    public Result doWork() {
        Context context;
        try {
            context = getApplicationContext();
        } catch (Throwable t) {
            return Result.failure();
        }
        try {
            Watchdog.arm(context);
            Watchdog.ensureService(context);
        } catch (Throwable t) {
            Log.w(TAG, "watchdog por WorkManager: " + t.getMessage());
            recordUnlessExpected("TelemetryWorker/watchdog", "el watchdog no pudo comprobar el servicio", t);
        }
        // Entrega independiente del servicio: WorkManager ejecuta este hilo de trabajo aunque el
        // arranque en primer plano esté bloqueado, así que la cola persistida se drena aquí.
        try {
            TelemetryClient client = TelemetryClient.get(context);
            if (!client.getEndpoint().isEmpty() && client.queued() > 0) {
                int sent = client.flushBlocking();
                lastRun = System.currentTimeMillis() / 1000L + " · enviados " + sent
                        + " · cola " + client.queued();
                Log.i(TAG, "entrega por WorkManager: " + lastRun);
                if (client.queued() > 0) {
                    // Quedan puntos: se pide un reintento con backoff propio de WorkManager en vez de
                    // esperar 15 minutos al siguiente ciclo periódico.
                    return Result.retry();
                }
            } else {
                lastRun = System.currentTimeMillis() / 1000L + " · sin pendientes";
            }
        } catch (Throwable t) {
            Log.w(TAG, "entrega por WorkManager fallida: " + t.getMessage());
            recordUnlessExpected("TelemetryWorker/entrega", "la entrega de la cola falló", t);
            return Result.retry();
        }
        // Tercera vía de entrega, la del canal Gist: también funciona sin servicio en primer plano y
        // es la única que puede publicar cuando Android 12+ bloquea el arranque del servicio. Se hace
        // de forma síncrona porque este hilo es de trabajo y la escritura debe terminar antes de
        // devolver el resultado; si GitHub no acepta, WorkManager reintenta con su propio backoff.
        try {
            GistPublisher publisher = GistPublisher.get(context);
            if (publisher.isEnabled() && publisher.isConfigured()) {
                boolean ok = publisher.publishNow();
                lastGist = (System.currentTimeMillis() / 1000L) + " · " + (ok ? "gist ok" : "gist pendiente")
                        + " · local " + TelemetryStore.get(context).count();
                Log.i(TAG, "entrega al gist: " + lastGist);
                if (!ok) {
                    return Result.retry();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "entrega al gist fallida: " + t.getMessage());
            ErrorLogger.record("TelemetryWorker/gist", "la entrega al gist falló", t);
            return Result.retry();
        }
        return Result.success();
    }

    /**
     * Reprograma la comprobación periódica. Es idempotente y se llama desde la actividad, desde el
     * servicio y desde el receptor de arranque: WorkManager conserva el trabajo existente en lugar
     * de duplicarlo.
     */
    static void schedulePeriodic(Context context) {
        try {
            if (!Watchdog.isTrackingEnabled(context)) {
                // Sin rastreo autorizado no hay nada que comprobar: se retira el trabajo periódico
                // para no despertar el proceso cada 15 minutos en balde.
                WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME);
                return;
            }
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(TelemetryWorker.class, 15, TimeUnit.MINUTES)
                    .addTag(TAG)
                    .build();
            WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo programar el trabajo periódico: " + t.getMessage());
        }
    }

    /**
     * Intento único con retardo. Tras un reinicio, el sistema suele rechazar el arranque inmediato en
     * primer plano desde el receptor de {@code BOOT_COMPLETED}; este trabajo lo reintenta un minuto
     * después, cuando el equipo ya está asentado.
     */
    static void scheduleBootRetry(Context context) {
        try {
            if (!Watchdog.isTrackingEnabled(context)) {
                // Tras reiniciar con el rastreo detenido no hay nada que reintentar: la cola que
                // hubiera quedado pendiente ya tiene su propio intento de entrega programado al
                // detener el rastreo, y WorkManager lo conserva entre reinicios.
                return;
            }
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(TelemetryWorker.class)
                    .setInitialDelay(1, TimeUnit.MINUTES)
                    .addTag(TAG)
                    .build();
            WorkManager.getInstance(context)
                    .enqueueUniqueWork(BOOT_NAME, ExistingWorkPolicy.REPLACE, request);
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo programar el reintento de arranque: " + t.getMessage());
        }
    }

    /**
     * Registra el fallo salvo que sea el sistema aplicando su propia política.
     *
     * <p>Android 12+ puede negar el arranque de un servicio en primer plano desde segundo plano: eso
     * no es una avería de la app, es el comportamiento esperado, y meterlo en {@code error.json}
     * cada 15 minutos convertiría el registro en ruido. Se comprueba por nombre de clase para no
     * referenciar tipos que solo existen en API 31+.</p>
     */
    private static void recordUnlessExpected(String source, String message, Throwable error) {
        if (error instanceof SecurityException) {
            Log.i(TAG, "rechazo del sistema (esperado): " + error.getMessage());
            return;
        }
        String name = error == null ? "" : error.getClass().getName();
        if (name.contains("ForegroundServiceStartNotAllowed") || name.contains("BackgroundServiceStartNotAllowed")) {
            Log.i(TAG, "arranque en segundo plano bloqueado (esperado): " + error.getMessage());
            return;
        }
        ErrorLogger.record(source, message, error);
    }

    static void cancelAll(Context context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_NAME);
            WorkManager.getInstance(context).cancelUniqueWork(BOOT_NAME);
        } catch (Throwable t) {
            Log.w(TAG, "no se pudieron cancelar los trabajos: " + t.getMessage());
        }
    }

    /**
     * Último intento de entrega al detener el rastreo, con la única condición de que haya red.
     * WorkManager lo ejecutará aunque la app esté cerrada: los puntos ya capturados llegan al
     * servidor y, una vez entregados, no queda ningún trabajo programado.
     */
    static void scheduleFinalFlush(Context context) {
        try {
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(TelemetryWorker.class)
                    .setConstraints(new Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build())
                    .addTag(TAG)
                    .build();
            WorkManager.getInstance(context)
                    .enqueueUniqueWork(FINAL_NAME, ExistingWorkPolicy.REPLACE, request);
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo programar la entrega final: " + t.getMessage());
        }
    }
}
