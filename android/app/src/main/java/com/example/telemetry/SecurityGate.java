package com.example.telemetry;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

/**
 * Código de seguridad que protege la pantalla de configuración.
 *
 * <p>No guarda el código: guarda su SHA-256 junto a una sal aleatoria por dispositivo, así que ni
 * leyendo las preferencias se puede recuperar. La comparación es en tiempo constante y, tras
 * {@value #MAX_FAILS} intentos fallidos seguidos, la pantalla queda bloqueada
 * {@value #LOCKOUT_MS} milisegundos.</p>
 *
 * <p><b>El candado protege la interfaz, no el rastreo.</b> El servicio en primer plano, el watchdog
 * y el receptor de arranque siguen funcionando con la pantalla bloqueada: un código olvidado no
 * puede detener el motor ni perder puntos. Es la decisión deliberada de que el candado no se
 * convierta en una forma de romper el sistema desde dentro.</p>
 */
public final class SecurityGate {

    private static final String TAG = "SecurityGate";
    private static final String KEY_HASH = "sec_pin_hash";
    private static final String KEY_SALT = "sec_pin_salt";
    private static final String KEY_FAILS = "sec_pin_fails";
    private static final String KEY_FAIL_AT = "sec_pin_fail_at";
    /** Mismo instante del fallo, pero en el reloj monótono del arranque (no se puede mover a mano). */
    private static final String KEY_FAIL_ELAPSED = "sec_pin_fail_elapsed";

    /** Longitud aceptada para el código (solo dígitos). */
    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 12;
    /** Intentos fallidos seguidos antes del bloqueo temporal. */
    public static final int MAX_FAILS = 5;
    /** Duración del bloqueo temporal una vez agotados los intentos. */
    public static final long LOCKOUT_MS = 30_000L;

    private SecurityGate() {
    }

    /** ¿Hay un código configurado? */
    public static boolean isEnabled(Context context) {
        SharedPreferences prefs = AppConfig.prefs(context);
        return !prefs.getString(KEY_HASH, "").isEmpty() && !prefs.getString(KEY_SALT, "").isEmpty();
    }

    /** El código debe ser numérico y tener entre {@value #MIN_LENGTH} y {@value #MAX_LENGTH} dígitos. */
    public static boolean isValid(String pin) {
        if (pin == null) {
            return false;
        }
        String value = pin.trim();
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Define (o cambia) el código. Devuelve {@code false} si el código no cumple el formato. */
    public static boolean set(Context context, String pin) {
        if (!isValid(pin)) {
            return false;
        }
        String salt = Hashing.randomHex(16);
        String hash = Hashing.sha256Hex(salt + ":" + pin.trim());
        if (salt == null || hash == null) {
            Log.w(TAG, "no se pudo calcular la huella del código: se deja sin candado");
            return false;
        }
        AppConfig.prefs(context).edit()
                .putString(KEY_SALT, salt)
                .putString(KEY_HASH, hash)
                .putInt(KEY_FAILS, 0)
                .putLong(KEY_FAIL_AT, 0L)
                .putLong(KEY_FAIL_ELAPSED, 0L)
                .apply();
        return true;
    }

    /**
     * Quita el candado. La configuración y el rastreo no se tocan.
     *
     * <p><b>Con la protección contra desinstalación activada no se quita</b>, y esa negativa se
     * aplica aquí y no en la pantalla porque este es el único sitio por el que se puede perder el
     * candado —lo pulsan el diálogo de seguridad y la importación de un fichero—: sin código, la
     * tarjeta que apaga la protección quedaría al alcance de quien coja el teléfono.</p>
     *
     * @return {@code true} si el candado quedó quitado; {@code false} si la protección lo impide
     */
    public static boolean clear(Context context) {
        if (UninstallGuard.isEnabled(context)) {
            Log.i(TAG, "el código no se quita con la protección contra desinstalación activada");
            return false;
        }
        AppConfig.prefs(context).edit()
                .remove(KEY_HASH)
                .remove(KEY_SALT)
                .putInt(KEY_FAILS, 0)
                .putLong(KEY_FAIL_AT, 0L)
                .putLong(KEY_FAIL_ELAPSED, 0L)
                .apply();
        return true;
    }

    /** Milisegundos que faltan para poder volver a intentarlo ({@code 0} si no hay bloqueo). */
    public static long lockRemainingMs(Context context) {
        SharedPreferences prefs = AppConfig.prefs(context);
        if (prefs.getInt(KEY_FAILS, 0) < MAX_FAILS) {
            return 0L;
        }
        // Dos relojes y se toma el mayor: el de pared se puede mover —un cambio de hora, un técnico,
        // la batería quitada— y no debe servir ni para saltarse el bloqueo adelantándolo ni para
        // eternizarlo atrasándolo. El recorte final al máximo es la red de seguridad: pase lo que pase
        // con la hora, el dueño no se queda fuera de su propia app más de los segundos del bloqueo.
        long byWallClock = prefs.getLong(KEY_FAIL_AT, 0L) + LOCKOUT_MS - System.currentTimeMillis();
        return Math.max(0L, Math.min(LOCKOUT_MS, Math.max(byWallClock, remainingByBootClock(prefs))));
    }

    /**
     * Lo que queda según el reloj monótono del arranque, que no se puede cambiar desde los ajustes.
     *
     * <p>Tras un reinicio ese reloj empieza de cero y el valor guardado pertenece al arranque
     * anterior: entonces no significa nada y la cuenta vuelve a ser la del reloj de pared.</p>
     */
    private static long remainingByBootClock(SharedPreferences prefs) {
        long failedAt = prefs.getLong(KEY_FAIL_ELAPSED, 0L);
        if (failedAt <= 0L) {
            return 0L;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < failedAt) {
            return 0L;
        }
        return failedAt + LOCKOUT_MS - now;
    }

    /** Intentos fallidos acumulados desde el último acierto. */
    public static int failures(Context context) {
        return AppConfig.prefs(context).getInt(KEY_FAILS, 0);
    }

    /**
     * Comprueba el código.
     *
     * @return {@code true} si coincide —o si no hay ningún código configurado—; {@code false} si no
     *     coincide o si la pantalla estaba bloqueada por intentos previos
     */
    public static boolean verify(Context context, String pin) {
        if (lockRemainingMs(context) > 0L) {
            return false;
        }
        SharedPreferences prefs = AppConfig.prefs(context);
        String salt = prefs.getString(KEY_SALT, "");
        String expected = prefs.getString(KEY_HASH, "");
        if (salt.isEmpty() || expected.isEmpty()) {
            return true;   // sin código configurado no hay nada que comprobar
        }
        String actual = Hashing.sha256Hex(salt + ":" + (pin == null ? "" : pin.trim()));
        if (Hashing.equalsConstantTime(expected, actual)) {
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_FAIL_AT, 0L)
                    .putLong(KEY_FAIL_ELAPSED, 0L).apply();
            return true;
        }
        int fails = prefs.getInt(KEY_FAILS, 0) + 1;
        prefs.edit().putInt(KEY_FAILS, fails)
                .putLong(KEY_FAIL_AT, System.currentTimeMillis())
                .putLong(KEY_FAIL_ELAPSED, SystemClock.elapsedRealtime()).apply();
        if (fails >= MAX_FAILS) {
            Log.w(TAG, "código fallado " + fails + " veces seguidas: bloqueo temporal de "
                    + (LOCKOUT_MS / 1000L) + " s");
        }
        return false;
    }
}
