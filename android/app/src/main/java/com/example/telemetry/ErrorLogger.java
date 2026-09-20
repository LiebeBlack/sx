package com.example.telemetry;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Registro automático de errores en {@code error.json}.
 *
 * <p><b>Dónde queda el fichero.</b> La copia que el usuario ve se escribe en la carpeta pública de
 * descargas del dispositivo, dentro de una carpeta con el nombre de la aplicación:
 * {@code Descargas/Telemetria/error.json}. En Android 10 y superiores se usa {@link MediaStore}
 * (contribuir a Descargas no necesita permisos); en Android 9 y anteriores hace falta
 * {@code WRITE_EXTERNAL_STORAGE}, que se pide en tiempo de ejecución. Si la carpeta pública no está
 * disponible —permiso denegado, almacenamiento externo no montado, política del fabricante— el
 * fichero se guarda en {@code Android/data/<paquete>/files/Telemetria/} y el estado lo indica, de
 * modo que el registro <b>nunca se pierde</b>: solo cambia de sitio.</p>
 *
 * <p><b>Formato.</b> Un array de incidentes, del más nuevo al más antiguo, con el mismo espíritu que
 * el resto del proyecto: el documento se lee entero antes de reescribirlo, de manera que nada se
 * destruye. Cada incidente lleva su instante en milisegundos y en ISO 8601, la gravedad, el origen,
 * el hilo, la excepción, el mensaje, la pila recortada, la huella del dispositivo y una
 * instantánea del estado del sistema en ese momento (rastreo activo, cola, último envío, estado del
 * canal Gist). <b>Nunca</b> se escriben tokens: el diagnóstico explica el sistema sin exponer
 * credenciales.</p>
 *
 * <p><b>Agrupación de repetidos.</b> Un bucle que falla cada segundo llenaría el fichero con miles de
 * copias del mismo error. Si el mismo origen, excepción y mensaje vuelven a aparecer en menos de
 * {@link #REPEAT_WINDOW_MS}, no se añade un incidente nuevo: se incrementa {@code repeat_count} del
 * último y se actualiza su instante. El histórico se acota a {@link #MAX_INCIDENTS} incidentes y a
 * {@link #MAX_BYTES} caracteres, descartando siempre por el extremo más antiguo.</p>
 *
 * <p><b>Uso.</b> {@link #install(Context)} se llama una sola vez desde {@link TelemetryApp} y deja el
 * manejador global de excepciones no capturadas instalado para <i>todos</i> los hilos; después, los
 * bloques {@code catch} que ya existían llaman a {@link #record(String, String, Throwable)} para
 * dejar constancia sin cambiar su comportamiento.</p>
 */
public final class ErrorLogger {

    /* ------------------------------------------------------------------ estado observable */

    /** Ruta del último {@code error.json} escrito (la copia pública, si se pudo). */
    public static volatile String lastPath = "";
    /** Ruta de la copia privada, que es la autoridad del documento. */
    public static volatile String privatePath = "";
    public static volatile long lastWriteAt = 0L;
    public static volatile int recordedCount = 0;
    public static volatile int repeatedCount = 0;
    public static volatile int writeFailures = 0;
    public static volatile String lastWriteStatus = "sin escrituras";
    public static volatile String lastPublicError = "";
    public static volatile String lastIncident = "";

    /* ------------------------------------------------------------------ constantes */

    private static final String TAG = "ErrorLogger";
    private static final String FILE_ERRORS = "error.json";
    private static final String FILE_DIAGNOSTIC = "diagnostico.json";
    private static final String PRIVATE_DIR = "diagnostics";
    private static final String FALLBACK_FOLDER = "Telemetria";
    private static final String MIME_JSON = "application/json";

    /** Incidentes conservados: suficiente para reconstruir una noche mala sin crecer sin control. */
    private static final int MAX_INCIDENTS = 200;
    /** Cota de tamaño del documento serializado. */
    private static final int MAX_BYTES = 500_000;
    /** Líneas de pila y longitud de cada línea que se guardan. */
    private static final int MAX_STACK_LINES = 40;
    private static final int MAX_STACK_LINE_CHARS = 400;
    /** Dentro de esta ventana, un error idéntico se agrupa en lugar de duplicarse. */
    private static final long REPEAT_WINDOW_MS = 60_000L;
    /** Número de capturas encadenadas que se incluyen (una por excepción envuelta). */
    private static final int MAX_CAUSES = 5;

    private static volatile Context appContext;

    private ErrorLogger() {
    }

    /* ------------------------------------------------------------------ instalación */

    /**
     * Instala el manejador global de excepciones no capturadas y guarda el contexto de aplicación.
     *
     * <p>El manejador anterior <b>se conserva y se invoca siempre</b> después de escribir el
     * incidente: el registro no debe cambiar el comportamiento del sistema (que mata el proceso y
     * muestra su diálogo), solo dejar constancia antes de morir.</p>
     */
    public static void install(Context context) {
        if (context == null) {
            return;
        }
        appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                String threadName = thread == null ? "desconocido" : thread.getName();
                try {
                    // Escritura síncrona a propósito: el proceso está muriendo y un hilo en segundo
                    // plano no llegaría a terminar. Es la única forma de que el fichero exista.
                    recordSync("uncaught/" + threadName, describe(error), error, "fatal", true);
                } catch (Throwable ignored) {
                    // Si el propio registro falla, jamás debe impedir el cierre normal del proceso.
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            }
        });
        Log.i(TAG, "manejador de errores instalado · carpeta " + folderName(appContext));
    }

    /* ------------------------------------------------------------------ API pública */

    /** Deja constancia de un fallo ya capturado (no relanza nada, nunca lanza). */
    public static void record(String source, String message) {
        record(source, message, null);
    }

    /** Deja constancia de un fallo capturado, con su excepción si la hay. */
    public static void record(String source, String message, Throwable error) {
        recordSync(source, message, error, "error", false);
    }

    /** Igual que {@link #record}, marcando el incidente como fatal. */
    public static void recordFatal(String source, String message, Throwable error) {
        recordSync(source, message, error, "fatal", false);
    }

    /**
     * Escribe un diagnóstico completo del sistema en {@code diagnostico.json}, con la misma ruta y el
     * mismo mecanismo que los errores. Es lo que se pulsa cuando "algo va raro" y todavía no hay
     * ningún incidente: deja el estado entero del sistema en un fichero que se puede enviar tal cual.
     *
     * @return la ruta donde quedó la copia pública, o la privada si la pública no estaba disponible.
     */
    public static synchronized String writeDiagnostic(Context context) {
        try {
            JSONObject document = new JSONObject();
            document.put("generated_ms", System.currentTimeMillis());
            document.put("generated_iso", isoNow());
            document.put("app", appFingerprint(context));
            document.put("device", deviceFingerprint(context));
            document.put("state", stateSnapshot(context));
            document.put("errors", errorSummary());
            document.put("paths", paths(context));
            boolean ok = writeDocument(context, FILE_DIAGNOSTIC, document);
            String target = ok ? lastPath : privatePath;
            lastWriteStatus = ok ? "diagnóstico escrito" : "diagnóstico solo en la copia privada";
            return target;
        } catch (Throwable t) {
            writeFailures++;
            lastPublicError = String.valueOf(t.getMessage());
            Log.w(TAG, "no se pudo escribir el diagnóstico: " + t.getMessage());
            return privatePath;
        }
    }

    /** Vacía el registro de errores (acción explícita del usuario). */
    public static synchronized boolean clear(Context context) {
        try {
            writeDocument(context, FILE_ERRORS, new JSONArray());
            recordedCount = 0;
            repeatedCount = 0;
            lastIncident = "";
            lastWriteStatus = "registro vaciado";
            return true;
        } catch (Throwable t) {
            writeFailures++;
            lastPublicError = String.valueOf(t.getMessage());
            return false;
        }
    }

    /** Nombre de la carpeta pública (el nombre de la aplicación, saneado para cualquier sistema de archivos). */
    public static String folder(Context context) {
        return folderName(context);
    }

    /** Resumen legible para el panel de la aplicación. */
    public static String summary() {
        StringBuilder text = new StringBuilder();
        text.append(recordedCount).append(" incidentes");
        if (repeatedCount > 0) {
            text.append(" (+").append(repeatedCount).append(" repetidos agrupados)");
        }
        if (writeFailures > 0) {
            text.append(" · ").append(writeFailures).append(" sin copia en Descargas");
        }
        text.append("\n  último: ").append(lastIncident.isEmpty() ? "ninguno" : lastIncident);
        text.append("\n  fichero: ").append(lastPath.isEmpty() ? "(pendiente)" : lastPath);
        if (!lastPublicError.isEmpty()) {
            text.append("\n  aviso: ").append(lastPublicError);
        }
        return text.toString();
    }

    /* ------------------------------------------------------------------ escritura */

    private static synchronized void recordSync(String source, String message, Throwable error,
                                                String severity, boolean isCrash) {
        Context context = appContext;
        if (context == null) {
            // Sin contexto no hay fichero posible: se deja al menos la traza en logcat.
            Log.w(TAG, "error sin contexto registrado: " + source + " · " + message);
            return;
        }
        try {
            JSONObject incident = buildIncident(context, source, message, error, severity);
            JSONArray previous = readDocument(context, FILE_ERRORS);
            JSONArray document = mergeIncident(previous, incident);
            trim(document);
            boolean ok = writeDocument(context, FILE_ERRORS, document);
            if (document.length() > 0 && document.optJSONObject(0) == incident) {
                recordedCount++;   // incidente nuevo; las repeticiones se cuentan aparte
            } else {
                repeatedCount++;
            }
            if (!ok) {
                writeFailures++;
            }
            lastIncident = severity + " · " + source + " · " + shorten(message, 120);
            Log.w(TAG, "incidente registrado (" + severity + "): " + lastIncident);
            if (isCrash) {
                // En la ruta de caída no hay segunda oportunidad: se fuerza el volcado a disco.
                flushPrivate(context, document);
            }
        } catch (Throwable t) {
            writeFailures++;
            lastPublicError = String.valueOf(t.getMessage());
            Log.w(TAG, "no se pudo registrar el incidente: " + t.getMessage());
        }
    }

    /**
     * Coloca el incidente en el documento: si es la repetición inmediata del último se agrupa (se
     * incrementa su contador y se refresca su instante) y, si no, se añade al principio.
     *
     * <p>Devuelve un documento <b>nuevo</b> en lugar de mutar el anterior: el array de JSON de Android
     * no tiene inserción al principio, y reconstruirlo es más claro que mover elementos a mano.</p>
     */
    private static JSONArray mergeIncident(JSONArray previous, JSONObject incident) throws JSONException {
        JSONArray document = new JSONArray();
        long now = incident.optLong("when_ms", System.currentTimeMillis());
        JSONObject newest = previous.optJSONObject(0);
        if (newest != null && sameIncident(newest, incident)
                && (now - newest.optLong("when_ms", 0L)) < REPEAT_WINDOW_MS) {
            newest.put("repeat_count", newest.optInt("repeat_count", 1) + 1);
            newest.put("when_ms", now);
            newest.put("when_iso", isoNow());
            document.put(newest);
        } else {
            document.put(incident);
        }
        for (int i = 0; i < previous.length(); i++) {
            JSONObject item = previous.optJSONObject(i);
            if (item != null && item != newest) {
                document.put(item);
            }
        }
        return document;
    }

    private static boolean sameIncident(JSONObject a, JSONObject b) {
        return a.optString("source", " ").equals(b.optString("source", "!"))
                && a.optString("exception", " ").equals(b.optString("exception", "!"))
                && a.optString("message", " ").equals(b.optString("message", "!"));
    }

    private static void trim(JSONArray document) {
        while (document.length() > MAX_INCIDENTS) {
            document.remove(document.length() - 1);
        }
        int guard = 0;
        while (document.toString().length() > MAX_BYTES && document.length() > 1 && guard < MAX_INCIDENTS) {
            document.remove(document.length() - 1);
            guard++;
        }
    }

    /** Escritura privada forzada, usada en la ruta de caída cuando el proceso no volverá. */
    private static void flushPrivate(Context context, JSONArray document) {
        OutputStream out = null;
        try {
            File dir = new File(context.getFilesDir(), PRIVATE_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }
            File target = new File(dir, FILE_ERRORS);
            byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
            FileOutputStream stream = new FileOutputStream(target, false);
            out = stream;
            out.write(bytes);
            out.flush();
            privatePath = target.getAbsolutePath();
            try {
                stream.getFD().sync();   // durabilidad: el proceso puede morir en el instante siguiente
            } catch (Throwable ignored) {
                // si el sistema no permite el sync, el write ya se hizo igualmente
            }
        } catch (Throwable t) {
            Log.w(TAG, "volcado privado fallido: " + t.getMessage());
        } finally {
            closeQuietly(out);
        }
    }

    /**
     * Escribe el documento: primero la copia privada (autoridad, siempre accesible) y después la
     * copia pública en Descargas. Ambas se reescriben completas, así que un fichero corrupto se
     * regenera solo.
     *
     * @return {@code true} si la copia pública quedó escrita.
     */
    private static boolean writeDocument(Context context, String fileName, Object document) throws IOException {
        byte[] bytes = document.toString().getBytes(StandardCharsets.UTF_8);
        writePrivate(context, fileName, bytes);
        return writePublic(context, fileName, bytes);
    }

    private static void writePrivate(Context context, String fileName, byte[] bytes) throws IOException {
        File dir = new File(context.getFilesDir(), PRIVATE_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("no se pudo crear " + dir.getAbsolutePath());
        }
        File target = new File(dir, fileName);
        OutputStream out = null;
        try {
            out = new FileOutputStream(target, false);
            out.write(bytes);
            out.flush();
            privatePath = target.getAbsolutePath();
        } finally {
            closeQuietly(out);
        }
    }

    /** @return {@code true} si quedó en la carpeta pública de descargas (lo que el usuario pidió). */
    private static boolean writePublic(Context context, String fileName, byte[] bytes) {
        lastPublicError = "";
        String folder = folderName(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (writeViaMediaStore(context, folder, fileName, bytes)) {
                return true;
            }
        } else if (hasLegacyStoragePermission(context)) {
            if (writeLegacy(context, folder, fileName, bytes)) {
                return true;
            }
        } else {
            lastPublicError = "sin permiso de almacenamiento (Android 9 o anterior): se pide con el botón Diagnóstico";
        }
        // Respaldo 1: almacenamiento externo propio de la app (visible por USB, sin permisos).
        File external = context.getExternalFilesDir(null);
        if (external != null && writeTo(new File(new File(external, folder), fileName), bytes)) {
            lastPath = new File(new File(external, folder), fileName).getAbsolutePath();
            lastWriteStatus = "copia en almacenamiento propio de la app";
            return false;
        }
        lastPath = privatePath;
        lastWriteStatus = "solo copia privada";
        return false;
    }

    private static boolean writeViaMediaStore(Context context, String folder, String fileName, byte[] bytes) {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
        Uri target = null;
        boolean created = false;
        try {
            target = findInDownloads(resolver, collection, folder, fileName);
            if (target == null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, MIME_JSON);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + folder);
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                target = resolver.insert(collection, values);
                created = true;
            }
            if (target == null) {
                lastPublicError = "MediaStore rechazó la creación del fichero";
                return false;
            }
            OutputStream out = null;
            try {
                out = resolver.openOutputStream(target, "wt");   // "wt": truncar y reescribir
                if (out == null) {
                    lastPublicError = "MediaStore no abrió el fichero para escritura";
                    return false;
                }
                out.write(bytes);
                out.flush();
            } finally {
                closeQuietly(out);
            }
            if (created) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(target, done, null, null);
            }
            lastPath = Environment.getExternalStorageDirectory().getAbsolutePath()
                    + "/" + Environment.DIRECTORY_DOWNLOADS + "/" + folder + "/" + fileName;
            lastWriteStatus = "escrito en Descargas";
            return true;
        } catch (Throwable t) {
            lastPublicError = String.valueOf(t.getMessage());
            Log.w(TAG, "MediaStore falló: " + t.getMessage());
            if (created && target != null) {
                try {
                    resolver.delete(target, null, null);   // no dejar una entrada a medias
                } catch (Throwable ignored) {
                    // nada que hacer: la entrada pendiente caduca sola
                }
            }
            return false;
        }
    }

    /** Busca el fichero ya existente en Descargas/<carpeta>/ para reescribirlo, no para duplicarlo. */
    private static Uri findInDownloads(ContentResolver resolver, Uri collection, String folder, String fileName) {
        android.database.Cursor cursor = null;
        try {
            cursor = resolver.query(collection,
                    new String[]{MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH},
                    MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                    new String[]{fileName}, null);
            if (cursor == null) {
                return null;
            }
            String wanted = Environment.DIRECTORY_DOWNLOADS + "/" + folder;
            while (cursor.moveToNext()) {
                String relative = cursor.getString(1);
                if (relative == null || relative.isEmpty()) {
                    continue;
                }
                String normalized = relative.endsWith("/") ? relative.substring(0, relative.length() - 1) : relative;
                if (normalized.equalsIgnoreCase(wanted)) {
                    return ContentUris.withAppendedId(collection, cursor.getLong(0));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "consulta a MediaStore fallida: " + t.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private static boolean writeLegacy(Context context, String folder, String fileName, byte[] bytes) {
        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            File dir = new File(downloads, folder);
            File target = new File(dir, fileName);
            if (writeTo(target, bytes)) {
                lastPath = target.getAbsolutePath();
                lastWriteStatus = "escrito en Descargas";
                return true;
            }
        } catch (Throwable t) {
            lastPublicError = String.valueOf(t.getMessage());
            Log.w(TAG, "escritura directa en Descargas fallida: " + t.getMessage());
        }
        return false;
    }

    private static boolean writeTo(File target, byte[] bytes) {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return false;
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(target, false);
            out.write(bytes);
            out.flush();
            return true;
        } catch (Throwable t) {
            lastPublicError = String.valueOf(t.getMessage());
            return false;
        } finally {
            closeQuietly(out);
        }
    }

    private static boolean hasLegacyStoragePermission(Context context) {
        try {
            return context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    /* ------------------------------------------------------------------ lectura */

    /**
     * Lee el documento anterior para no destruirlo al reescribirlo.
     *
     * <p>La copia privada es la autoridad, pero <b>no siempre existe</b>: al reinstalar la app se
     * borran sus datos mientras {@code Descargas/Telemetria/error.json} sobrevive. Si solo se mirara
     * la copia privada, la primera escritura tras una reinstalación machacaría el historial anterior;
     * por eso, cuando la privada falta o está vacía, se adopta la pública como base.</p>
     */
    private static JSONArray readDocument(Context context, String fileName) {
        JSONArray fromPrivate = readFile(new File(new File(context.getFilesDir(), PRIVATE_DIR), fileName));
        if (fromPrivate.length() > 0) {
            return fromPrivate;
        }
        return readPublic(context, fileName);
    }

    /** Lectura de la copia pública: MediaStore en Android 10+, ruta clásica antes, y respaldo propio. */
    private static JSONArray readPublic(Context context, String fileName) {
        String folder = folderName(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            JSONArray fromStore = readFromMediaStore(context, folder, fileName);
            if (fromStore.length() > 0) {
                return fromStore;
            }
        } else {
            try {
                File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                JSONArray fromFile = readFile(new File(new File(downloads, folder), fileName));
                if (fromFile.length() > 0) {
                    return fromFile;
                }
            } catch (Throwable t) {
                Log.w(TAG, "lectura de Descargas fallida: " + t.getMessage());
            }
        }
        File external = context.getExternalFilesDir(null);
        if (external != null) {
            return readFile(new File(new File(external, folder), fileName));
        }
        return new JSONArray();
    }

    private static JSONArray readFromMediaStore(Context context, String folder, String fileName) {
        ContentResolver resolver = context.getContentResolver();
        InputStream in = null;
        try {
            Uri target = findInDownloads(resolver, MediaStore.Downloads.EXTERNAL_CONTENT_URI, folder, fileName);
            if (target == null) {
                return new JSONArray();
            }
            in = resolver.openInputStream(target);
            if (in == null) {
                return new JSONArray();
            }
            return parseDocument(readText(in, MAX_BYTES));
        } catch (Throwable t) {
            Log.w(TAG, "lectura por MediaStore fallida: " + t.getMessage());
            return new JSONArray();
        } finally {
            closeQuietly(in);
        }
    }

    /** Lee un fichero con cota de tamaño. Un fichero ausente o ilegible se trata como vacío. */
    private static JSONArray readFile(File target) {
        InputStream in = null;
        try {
            if (target == null || !target.exists() || target.length() == 0L) {
                return new JSONArray();
            }
            in = new FileInputStream(target);
            return parseDocument(readText(in, MAX_BYTES));
        } catch (Throwable t) {
            Log.w(TAG, "documento anterior ilegible, se empieza de cero: " + t.getMessage());
            return new JSONArray();
        } finally {
            closeQuietly(in);
        }
    }

    private static String readText(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8_192);
        byte[] chunk = new byte[8_192];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                break;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toString("UTF-8");
    }

    /**
     * Acepta un array (formato del proyecto) y también un objeto suelto, que se envuelve: así un
     * fichero escrito por una versión anterior se puede seguir leyendo sin perder nada.
     */
    private static JSONArray parseDocument(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new JSONArray();
        }
        try {
            Object parsed = new JSONTokener(text).nextValue();
            if (parsed instanceof JSONArray) {
                return (JSONArray) parsed;
            }
            if (parsed instanceof JSONObject) {
                JSONArray array = new JSONArray();
                array.put((JSONObject) parsed);
                return array;
            }
        } catch (Throwable t) {
            Log.w(TAG, "documento ilegible, se empieza de cero: " + t.getMessage());
        }
        return new JSONArray();
    }

    /* ------------------------------------------------------------------ composición del incidente */

    private static JSONObject buildIncident(Context context, String source, String message,
                                            Throwable error, String severity) throws Exception {
        JSONObject incident = new JSONObject();
        long now = System.currentTimeMillis();
        incident.put("when_ms", now);
        incident.put("when_iso", isoNow());
        incident.put("severity", severity);
        incident.put("source", source == null ? "desconocido" : source);
        incident.put("thread", Thread.currentThread().getName());
        incident.put("exception", error == null ? "" : error.getClass().getName());
        incident.put("message", shorten(message == null ? describe(error) : message, 1_000));
        incident.put("stack", stackOf(error));
        incident.put("causes", causesOf(error));
        incident.put("repeat_count", 1);
        incident.put("app", appFingerprint(context));
        incident.put("device", deviceFingerprint(context));
        incident.put("state", stateSnapshot(context));
        incident.put("paths", paths(context));
        return incident;
    }

    private static JSONArray stackOf(Throwable error) throws JSONException {
        JSONArray stack = new JSONArray();
        if (error == null) {
            return stack;
        }
        StackTraceElement[] frames = error.getStackTrace();
        if (frames == null) {
            return stack;
        }
        int limit = Math.min(frames.length, MAX_STACK_LINES);
        for (int i = 0; i < limit; i++) {
            stack.put(shorten(String.valueOf(frames[i]), MAX_STACK_LINE_CHARS));
        }
        if (frames.length > limit) {
            stack.put("… " + (frames.length - limit) + " marcos más");
        }
        return stack;
    }

    private static JSONArray causesOf(Throwable error) throws JSONException {
        JSONArray causes = new JSONArray();
        Throwable cause = error == null ? null : error.getCause();
        int depth = 0;
        while (cause != null && depth < MAX_CAUSES && cause != error) {
            JSONObject item = new JSONObject();
            try {
                item.put("exception", cause.getClass().getName());
                item.put("message", shorten(String.valueOf(cause.getMessage()), 400));
            } catch (Exception ignored) {
                // un dato menos en la cadena de causas: no merece abortar el registro
            }
            causes.put(item);
            cause = cause.getCause();
            depth++;
        }
        return causes;
    }

    private static JSONObject appFingerprint(Context context) throws JSONException {
        JSONObject app = new JSONObject();
        try {
            PackageManager manager = context.getPackageManager();
            PackageInfo info = manager.getPackageInfo(context.getPackageName(), 0);
            app.put("package", context.getPackageName());
            app.put("version", info.versionName == null ? "" : info.versionName);
            app.put("version_code", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? info.getLongVersionCode() : info.versionCode);
        } catch (Throwable t) {
            app.put("package", context.getPackageName());
            app.put("version", "");
            app.put("version_code", 0);
        }
        return app;
    }

    private static JSONObject deviceFingerprint(Context context) throws JSONException {
        JSONObject device = new JSONObject();
        try {
            device.put("model", Build.MODEL);
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("brand", Build.BRAND);
            device.put("device", Build.DEVICE);
            device.put("android", Build.VERSION.RELEASE);
            device.put("sdk", Build.VERSION.SDK_INT);
            device.put("abis", Build.SUPPORTED_ABIS == null || Build.SUPPORTED_ABIS.length == 0
                    ? "" : Build.SUPPORTED_ABIS[0]);
            device.put("locale", Locale.getDefault().toString());
            device.put("low_ram", isLowRam(context));
            device.put("uptime_ms", SystemClock.elapsedRealtime());
        } catch (Throwable t) {
            device.put("sdk", Build.VERSION.SDK_INT);
        }
        return device;
    }

    /** Instantánea del sistema en el momento del fallo: sin credenciales, solo estado. */
    private static JSONObject stateSnapshot(Context context) {
        JSONObject state = new JSONObject();
        try {
            state.put("tracking_enabled", Watchdog.isTrackingEnabled(context));
            state.put("watchdog_restarts", Watchdog.restarts(context));
            state.put("watchdog_rejected", Watchdog.launchAttempts(context));
        } catch (Throwable t) {
            // sin watchdog disponible: el resto del estado sigue siendo útil
        }
        try {
            state.put("service_running", LocationTrackerService.running);
            state.put("sent", LocationTrackerService.sentCount);
            state.put("failed", LocationTrackerService.failedCount);
            state.put("dropped", LocationTrackerService.droppedCount);
            state.put("last_summary", LocationTrackerService.lastSummary);
        } catch (Throwable t) {
            // el servicio puede no haberse cargado nunca en este proceso
        }
        try {
            TelemetryClient client = TelemetryClient.get(context);
            state.put("endpoint", client.getEndpoint());
            state.put("device_id", client.getDeviceId());
            state.put("queue", client.queued());
            state.put("dropped_by_client", client.droppedCount());
            state.put("last_http", client.lastHttpCode());
        } catch (Throwable t) {
            // sin cliente HTTP disponible
        }
        try {
            GistPublisher gist = GistPublisher.get(context);
            state.put("gist_configured", gist.isConfigured());
            state.put("gist_status", GistPublisher.lastStatus);
            state.put("gist_published", GistPublisher.lastPublished);
            state.put("gist_local_points", gist.localStored());
            state.put("gist_pending", gist.pendingLocal());
            state.put("gist_error", GistPublisher.lastError == null ? "" : GistPublisher.lastError);
        } catch (Throwable t) {
            // sin canal gist disponible
        }
        try {
            state.put("worker_last_run", TelemetryWorker.lastRun);
            state.put("worker_last_gist", TelemetryWorker.lastGist);
        } catch (Throwable t) {
            // sin WorkManager disponible
        }
        return state;
    }

    private static JSONObject errorSummary() {
        JSONObject summary = new JSONObject();
        try {
            summary.put("recorded", recordedCount);
            summary.put("grouped_repeats", repeatedCount);
            summary.put("write_failures", writeFailures);
            summary.put("last_incident", lastIncident);
            summary.put("last_write_status", lastWriteStatus);
        } catch (Throwable t) {
            // un resumen incompleto sigue siendo un resumen
        }
        return summary;
    }

    private static JSONObject paths(Context context) {
        JSONObject paths = new JSONObject();
        try {
            String folder = folderName(context);
            paths.put("public_folder", Environment.DIRECTORY_DOWNLOADS + "/" + folder);
            paths.put("public_file", lastPath);
            paths.put("private_file", privatePath);
            File external = context.getExternalFilesDir(null);
            paths.put("app_external_dir", external == null ? "" : external.getAbsolutePath());
        } catch (Throwable t) {
            // sin rutas disponibles
        }
        return paths;
    }

    /* ------------------------------------------------------------------ utilidades */

    /** "Telemetría" → "Telemetria": válido en cualquier sistema de archivos, sin sorpresas. */
    private static String folderName(Context context) {
        String label;
        try {
            label = context == null ? FALLBACK_FOLDER : context.getString(R.string.app_name);
        } catch (Throwable t) {
            label = FALLBACK_FOLDER;
        }
        String plain = stripDiacritics(label == null ? FALLBACK_FOLDER : label);
        StringBuilder safe = new StringBuilder(plain.length());
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '-' || c == '_') {
                safe.append(c);
            } else if (c == ' ' || c == '.' || c == '·') {
                safe.append('-');
            }
        }
        String value = safe.toString().replaceAll("-{2,}", "-").replaceAll("^-+|-+$", "");
        return value.isEmpty() ? FALLBACK_FOLDER : value;
    }

    private static String stripDiacritics(String value) {
        try {
            String normalized = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD);
            return normalized.replaceAll("\\p{M}+", "");
        } catch (Throwable t) {
            return value;
        }
    }

    private static boolean isLowRam(Context context) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            return manager != null && manager.isLowRamDevice();
        } catch (Throwable t) {
            return false;
        }
    }

    private static String isoNow() {
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.format(new Date());
        } catch (Throwable t) {
            return "";
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "sin excepción";
        }
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getName() : message;
    }

    private static String shorten(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // cierre best-effort
        }
    }
}
