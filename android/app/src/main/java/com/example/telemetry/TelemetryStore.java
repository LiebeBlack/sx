package com.example.telemetry;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Fuente de verdad local: todo punto de telemetría se guarda aquí antes de intentar publicarlo.
 *
 * <p>Es SQLite del propio SDK ({@link SQLiteOpenHelper}), no Room: Room obligaría a Kotlin, kapt o
 * anotaciones y a un procesador de anotaciones en cada compilación, y para «guardar puntos y leer
 * los últimos N ordenados por instante» no aporta nada que una tabla no dé. Mismo resultado, cero
 * dependencias nuevas y cero riesgo de romper el build.</p>
 *
 * <p>La clave primaria es {@code timestamp_ms} del propio punto, con {@code CONFLICT_REPLACE}: dos
 * proveedores que entreguen el mismo instante —o dos ciclos de envío del mismo punto— no pueden
 * duplicar una fila. Es exactamente la misma clave que usa el visualizador para deduplicar, así que
 * lo que se guarda, lo que se publica y lo que se dibuja coinciden.</p>
 *
 * <p>Nunca es destructiva por sí sola: solo recorta los puntos más antiguos por encima de
 * {@link #MAX_STORED}, que es una cota de disco (unos cientos de KB), no una decisión de negocio.
 * El envío masivo al Gist se hace con {@link #recent(int)}.</p>
 */
public final class TelemetryStore extends SQLiteOpenHelper {

    private static final String TAG = "TelemetryStore";
    private static final String DB_NAME = "telemetry_store.db";
    private static final int DB_VERSION = 1;

    static final String TABLE = "points";
    static final String COL_TS = "ts";
    static final String COL_DEVICE = "device_id";
    static final String COL_PAYLOAD = "payload";

    /** Puntos guardados en el dispositivo. 5 000 puntos son ~2 MB de disco como máximo. */
    private static final int MAX_STORED = 5_000;
    /** El recorte se hace a saltos para no pagar un DELETE en cada inserción. */
    private static final int TRIM_SLACK = 250;

    private static volatile TelemetryStore instance;

    private TelemetryStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
        try {
            setWriteAheadLoggingEnabled(true);   // lecturas concurrentes mientras se escribe
        } catch (Exception e) {
            Log.w(TAG, "WAL no disponible: " + e.getMessage());
        }
    }

    public static TelemetryStore get(Context context) {
        TelemetryStore local = instance;
        if (local == null) {
            synchronized (TelemetryStore.class) {
                local = instance;
                if (local == null) {
                    local = new TelemetryStore(context);
                    instance = local;
                }
            }
        }
        return local;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " ("
                + COL_TS + " INTEGER PRIMARY KEY, "
                + COL_DEVICE + " TEXT, "
                + COL_PAYLOAD + " TEXT NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // v1 es la única versión: cuando exista un esquema nuevo, se migra aquí columna a columna.
        // Por ahora, reconstruir la tabla es lo correcto porque el contenido es una caché de envío
        // y la historia remota (el Gist) sigue intacta.
        db.execSQL("DROP TABLE IF EXISTS " + TABLE);
        onCreate(db);
    }

    /**
     * Guarda un punto ya normalizado al esquema del Gist.
     *
     * @return {@code true} si quedó almacenado (una fila insertada o reemplazada).
     */
    public boolean insert(JSONObject gistPoint) {
        if (gistPoint == null) {
            return false;
        }
        long ts = timestampOf(gistPoint);
        if (ts <= 0L) {
            return false;   // sin instante no hay clave: se descarta en lugar de ensuciar la tabla
        }
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(COL_TS, ts);
            values.put(COL_DEVICE, deviceOf(gistPoint));
            values.put(COL_PAYLOAD, gistPoint.toString());
            long row = db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_REPLACE);
            maybeTrim(db);
            return row != -1L;
        } catch (Exception e) {
            // Un fallo de disco no puede tumbar el rastreo: el punto ya viaja por la cola de red.
            Log.w(TAG, "no se pudo guardar el punto: " + e.getMessage());
            return false;
        }
    }

    /** Últimos {@code limit} puntos, del más antiguo al más nuevo, listos para publicar. */
    public JSONArray recent(int limit) {
        JSONArray out = new JSONArray();
        if (limit <= 0) {
            return out;
        }
        Cursor cursor = null;
        try {
            SQLiteDatabase db = getReadableDatabase();
            // Subconsulta para quedarse con los MÁS NUEVOS y devolverlos en orden cronológico.
            cursor = db.rawQuery("SELECT " + COL_PAYLOAD + " FROM ("
                    + "SELECT " + COL_PAYLOAD + ", " + COL_TS + " FROM " + TABLE
                    + " ORDER BY " + COL_TS + " DESC LIMIT ?) ORDER BY " + COL_TS + " ASC",
                    new String[]{String.valueOf(limit)});
            while (cursor.moveToNext()) {
                String payload = cursor.getString(0);
                try {
                    out.put(new JSONObject(payload));
                } catch (Exception e) {
                    Log.w(TAG, "fila ilegible, se omite");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "no se pudo leer el histórico: " + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return out;
    }

    /** Puntos guardados (contador de disco, no de envío). */
    public int count() {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** Puntos guardados con instante posterior a {@code since} (diagnóstico de envío pendiente). */
    public int countNewerThan(long since) {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM " + TABLE + " WHERE " + COL_TS + " > ?",
                    new String[]{String.valueOf(since)});
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } catch (Exception e) {
            return 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** Instante del punto más nuevo guardado, o 0 si todavía no hay ninguno. */
    public long newestTimestamp() {
        Cursor cursor = null;
        try {
            cursor = getReadableDatabase().rawQuery("SELECT MAX(" + COL_TS + ") FROM " + TABLE, null);
            return cursor.moveToFirst() && !cursor.isNull(0) ? cursor.getLong(0) : 0L;
        } catch (Exception e) {
            return 0L;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** Borra todo el histórico local (solo lo usa el usuario desde la app: es una acción explícita). */
    public void clear() {
        try {
            getWritableDatabase().delete(TABLE, null, null);
        } catch (Exception e) {
            Log.w(TAG, "no se pudo vaciar el histórico: " + e.getMessage());
        }
    }

    /**
     * Recorta solo cuando se supera la cota, y siempre por el extremo antiguo: el histórico que
     * alimenta al Gist son los últimos puntos, así que lo viejo es lo único prescindible.
     */
    private void maybeTrim(SQLiteDatabase db) {
        try {
            Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + TABLE, null);
            int total = cursor.moveToFirst() ? cursor.getInt(0) : 0;
            cursor.close();
            if (total <= MAX_STORED + TRIM_SLACK) {
                return;
            }
            db.execSQL("DELETE FROM " + TABLE + " WHERE " + COL_TS + " <= ("
                    + "SELECT " + COL_TS + " FROM " + TABLE + " ORDER BY " + COL_TS + " DESC LIMIT 1 OFFSET ?)",
                    new Object[]{MAX_STORED});
            Log.i(TAG, "histórico local recortado a " + MAX_STORED + " puntos");
        } catch (Exception e) {
            Log.w(TAG, "recorte fallido: " + e.getMessage());
        }
    }

    /** Instante del punto según el esquema del Gist, con tolerancia a esquemas planos. */
    static long timestampOf(JSONObject point) {
        if (point == null) {
            return 0L;
        }
        JSONObject telemetry = point.optJSONObject("telemetry");
        long ts = telemetry != null ? telemetry.optLong("timestamp_ms", 0L) : 0L;
        if (ts <= 0L) {
            ts = point.optLong("timestamp_ms", 0L);
        }
        if (ts <= 0L) {
            ts = point.optLong("timestamp", 0L);
        }
        return ts;
    }

    private static String deviceOf(JSONObject point) {
        JSONObject telemetry = point.optJSONObject("telemetry");
        String id = telemetry != null ? telemetry.optString("device_id", "") : point.optString("device_id", "");
        return id.length() > 64 ? id.substring(0, 64) : id;
    }
}
