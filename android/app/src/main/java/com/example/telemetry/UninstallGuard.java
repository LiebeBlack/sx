package com.example.telemetry;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityService;
import android.widget.Toast;

import java.util.Locale;

/**
 * Protección contra la desinstalación y contra la desactivación del rastreo.
 *
 * <p>Tiene <b>dos capas</b>, y conviene saber exactamente qué garantiza cada una:</p>
 *
 * <ol>
 *   <li><b>Propietario del dispositivo: la garantía real.</b> Desde la API 21, solo una aplicación
 *       propietaria del dispositivo —o del perfil de trabajo— puede pedirle al sistema que impida
 *       desinstalarla ({@code setUninstallBlocked}). Además, una app propietaria <b>no se puede
 *       desactivar</b> desde los ajustes: el botón desaparece. El propietario se establece una única
 *       vez, en un teléfono recién restablecido y sin cuentas, con
 *       {@code adb shell dpm set-device-owner <paquete>/<receptor>}; el comando exacto lo muestra la
 *       propia pantalla con los datos de este teléfono.</li>
 *   <li><b>Centinela de accesibilidad: capa disuasoria, sin permisos de propietario.</b> Con la
 *       protección activada, el centinela reconoce la pantalla de desinstalación o de desactivación
 *       del administrador por los <b>metadatos del propio evento</b> —paquete, texto y descripción— y
 *       la cierra con «Atrás», lo cuenta y lo deja en {@code error.json}. <b>No lee el contenido de la
 *       pantalla</b>: el servicio sigue declarado con {@code canRetrieveWindowContent="false"}. No es
 *       una garantía: otra versión de Ajustes puede no publicar ese texto, y el modo seguro o un
 *       restablecimiento de fábrica siempre ganan.</li>
 * </ol>
 *
 * <p><b>Salida documentada.</b> Esto nunca debe poder encerrar al dueño del teléfono: se apaga desde
 * la propia app, que exige el código de seguridad —por eso activar la protección obliga a tener uno—,
 * y si el código se olvidara quedan {@code adb shell dpm remove-active-admin}, el modo seguro o el
 * restablecimiento de fábrica. Está escrito en {@code docs/CONFIGURACION.md}.</p>
 */
public final class UninstallGuard {

    private static final String TAG = "UninstallGuard";

    /** Ajuste de la protección. Se activa a mano: nunca por defecto. */
    private static final String KEY_ENABLED = "uninstall_guard";
    private static final String KEY_INTERCEPTS = "uninstall_intercepts";
    private static final String KEY_LAST_INTERCEPT = "uninstall_last_intercept";
    /** Aviso ya dado de que falta el administrador de dispositivo (para no repetirlo en cada latido). */
    private static final String KEY_ADMIN_MISSING = "uninstall_admin_missing";

    /** Freno entre interceptaciones: «Atrás» dos veces seguidas puede salir de Ajustes por completo. */
    private static final long INTERCEPT_MIN_GAP_MS = 1_200L;

    /** Aplicaciones del sistema que muestran la desinstalación o la desactivación del administrador. */
    private static final String[] WATCHED_PACKAGES = {
            "com.android.settings",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.miui.securitycenter",
            "com.coloros.safecenter",
    };

    /** Palabras que convierten la pantalla en peligrosa (en minúsculas y sin tildes, ver {@link #fold}). */
    private static final String[] DANGER_WORDS = {
            "desinstal", "uninstall", "desactivar", "deactivate", "deactivation",
            "desactivacion", "quitar la aplicacion", "remove app",
    };

    private UninstallGuard() {
    }

    private static SharedPreferences prefs(Context context) {
        return AppConfig.prefs(context);
    }

    private static DevicePolicyManager dpm(Context context) {
        try {
            return (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ComponentName admin(Context context) {
        return new ComponentName(context, TelemetryAdminReceiver.class);
    }

    /* ------------------------------------------------------------------ ajuste */

    /** ¿Está activada la protección? */
    public static boolean isEnabled(Context context) {
        try {
            return prefs(context).getBoolean(KEY_ENABLED, false);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Activa o desactiva la protección.
     *
     * <p>Al activarla se aplica de inmediato lo que permita este teléfono y se comprueba lo que falta;
     * al desactivarla se <b>levanta el bloqueo</b> del sistema, porque dejarlo puesto con la protección
     * apagada dejaría la app desinstalable solo con ADB.</p>
     */
    public static void setEnabled(Context context, boolean enabled) {
        try {
            prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
            if (enabled) {
                enforce(context);
            } else {
                if (isUninstallBlocked(context)) {
                    setUninstallBlocked(context, false);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo cambiar la protección: " + t.getMessage());
            ErrorLogger.record("UninstallGuard", "no se pudo cambiar la protección", t);
        }
    }

    /* ------------------------------------------------------------------ propietario del dispositivo */

    /** ¿Esta app es propietaria del dispositivo? Es lo único que impide desinstalarla de verdad. */
    public static boolean isDeviceOwner(Context context) {
        try {
            DevicePolicyManager manager = dpm(context);
            return manager != null && manager.isDeviceOwnerApp(context.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Propietario de un perfil de trabajo: desde la API 26 puede bloquear la desinstalación igual. */
    public static boolean isProfileOwner(Context context) {
        try {
            DevicePolicyManager manager = dpm(context);
            return manager != null && manager.isProfileOwnerApp(context.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** ¿Este teléfono permite el bloqueo real de la desinstalación? */
    public static boolean canBlockUninstall(Context context) {
        return isDeviceOwner(context) || isProfileOwner(context);
    }

    /** ¿Está el sistema impidiendo la desinstalación ahora mismo? */
    public static boolean isUninstallBlocked(Context context) {
        if (!canBlockUninstall(context)) {
            // Fuera de propietario el sistema ni siquiera permite preguntarlo, y esta consulta se hace
            // en cada refresco de la pantalla: mejor no provocar una excepción por segundo.
            return false;
        }
        try {
            DevicePolicyManager manager = dpm(context);
            return manager != null && manager.isUninstallBlocked(admin(context), context.getPackageName());
        } catch (Throwable t) {
            // Fuera de propietario la consulta también está prohibida: no poder preguntarlo no es un error.
            return false;
        }
    }

    /**
     * Pide al sistema que impida (o permita) desinstalar esta aplicación.
     *
     * @return {@code true} si el sistema lo aceptó; {@code false} si este teléfono no es propietario
     */
    public static boolean setUninstallBlocked(Context context, boolean blocked) {
        DevicePolicyManager manager = dpm(context);
        if (manager == null) {
            return false;
        }
        try {
            manager.setUninstallBlocked(admin(context), context.getPackageName(), blocked);
            Log.i(TAG, "bloqueo de desinstalación " + (blocked ? "activado" : "levantado"));
            return true;
        } catch (SecurityException e) {
            // El sistema solo lo permite al propietario del dispositivo o del perfil: es la respuesta
            // esperada en un teléfono normal, no un fallo que haya que registrar como incidente.
            Log.i(TAG, "este teléfono no es propietario del dispositivo: el bloqueo real no está disponible");
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo cambiar el bloqueo: " + t.getMessage());
            ErrorLogger.record("UninstallGuard", "no se pudo cambiar el bloqueo de desinstalación", t);
            return false;
        }
    }

    /** Comando exacto para convertir este teléfono en propietario del dispositivo (una sola vez). */
    public static String ownerCommand(Context context) {
        return "adb shell dpm set-device-owner "
                + context.getPackageName() + "/" + TelemetryAdminReceiver.class.getName();
    }

    /** Comando exacto para quitar el propietario si se pierde el acceso (salida de emergencia). */
    public static String removeOwnerCommand(Context context) {
        return "adb shell dpm remove-active-admin "
                + context.getPackageName() + "/" + TelemetryAdminReceiver.class.getName();
    }

    /* ------------------------------------------------------------------ aplicación de la protección */

    /**
     * Reaplica lo que el usuario pidió: si es propietario y el bloqueo no está puesto, lo pone, y si el
     * administrador de dispositivo ha desaparecido lo deja registrado como incidente —bajar de
     * categoría la protección es exactamente lo que interesa saber—.
     *
     * <p>Se llama al arrancar el teléfono, al actualizar el paquete y con cada latido del watchdog, así
     * que tiene que ser barata y no lanzar nunca.</p>
     */
    public static void enforce(Context context) {
        if (context == null || !isEnabled(context)) {
            return;
        }
        try {
            if (canBlockUninstall(context) && !isUninstallBlocked(context)) {
                setUninstallBlocked(context, true);
            }
            // El aviso de administrador ausente se registra UNA vez por caída: esto se llama en cada
            // latido del watchdog, y repetir el mismo incidente cada pocos minutos llenaría el registro
            // del usuario sin añadir nada. Cuando el administrador vuelve, se rearma el aviso.
            SharedPreferences prefs = prefs(context);
            if (TelemetryAdminReceiver.isActive(context)) {
                if (prefs.getBoolean(KEY_ADMIN_MISSING, false)) {
                    prefs.edit().putBoolean(KEY_ADMIN_MISSING, false).apply();
                }
            } else if (!prefs.getBoolean(KEY_ADMIN_MISSING, false)) {
                prefs.edit().putBoolean(KEY_ADMIN_MISSING, true).apply();
                ErrorLogger.record("UninstallGuard",
                        "el administrador de dispositivo no está activo: la protección queda reducida",
                        null);
            }
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo reaplicar la protección: " + t.getMessage());
        }
    }

    /* ------------------------------------------------------------------ capa de accesibilidad */

    /**
     * ¿Este evento es la pantalla de desinstalación o de desactivación de <b>esta</b> aplicación?
     *
     * <p>Se decide solo con lo que trae el evento —paquete, texto y descripción—, nunca leyendo el
     * árbol de la ventana. Si el texto no menciona la app, no se toca nada: es preferible dejar pasar
     * una desinstalación ajena que cerrarle a alguien la pantalla que estaba usando.</p>
     */
    static boolean shouldIntercept(Context context, AccessibilityEvent event) {
        try {
            if (event == null) {
                return false;
            }
            SharedPreferences prefs = prefs(context);
            long now = System.currentTimeMillis();
            if (now - prefs.getLong(KEY_LAST_INTERCEPT, 0L) < INTERCEPT_MIN_GAP_MS) {
                return false;
            }
            if (!isWatched(String.valueOf(event.getPackageName()))) {
                return false;
            }
            StringBuilder text = new StringBuilder();
            if (event.getText() != null) {
                for (CharSequence part : event.getText()) {
                    if (part != null) {
                        text.append(part).append(' ');
                    }
                }
            }
            if (event.getContentDescription() != null) {
                text.append(event.getContentDescription());
            }
            String haystack = fold(text.toString());
            if (haystack.isEmpty()) {
                return false;
            }
            boolean mentionsThisApp = haystack.contains(fold(context.getString(R.string.app_name)))
                    || haystack.contains(fold(context.getPackageName()));
            return mentionsThisApp && containsAny(haystack, DANGER_WORDS);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Cierra la pantalla con «Atrás», lo avisa y lo cuenta. Nunca lanza. */
    static void intercept(AccessibilityService service) {
        if (service == null) {
            return;
        }
        try {
            SharedPreferences prefs = prefs(service);
            prefs.edit()
                    .putLong(KEY_LAST_INTERCEPT, System.currentTimeMillis())
                    .putInt(KEY_INTERCEPTS, intercepts(service) + 1)
                    .apply();
            boolean closed = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            Toast.makeText(service, closed
                    ? "Telemetría está protegida: para desinstalarla hay que apagar la protección en la app"
                    : "Telemetría está protegida: no se puede desinstalar sin apagar la protección",
                    Toast.LENGTH_LONG).show();
            ErrorLogger.record("UninstallGuard",
                    "intento de desinstalación o desactivación interceptado (Atrás " + (closed ? "pulsado" : "rechazado") + ")",
                    null);
        } catch (Throwable t) {
            Log.w(TAG, "no se pudo interceptar: " + t.getMessage());
        }
    }

    /** Intentos interceptados desde que se instaló la app. */
    public static int intercepts(Context context) {
        try {
            return prefs(context).getInt(KEY_INTERCEPTS, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    /**
     * Estado de la protección en varias líneas, para la tarjeta de la pantalla.
     *
     * <p>El estado del <b>sistema</b> —bloqueo y papel de propietario— se informa siempre, incluso con
     * la protección apagada: el bloqueo vive en el sistema y <b>sobrevive a borrar los datos de la
     * app</b>, así que decir «se desinstala con normalidad» en ese caso sería sencillamente falso.</p>
     */
    public static String stateSummary(Context context) {
        boolean enabled = isEnabled(context);
        boolean blocked = isUninstallBlocked(context);
        StringBuilder text = new StringBuilder();
        text.append("protección: ").append(enabled
                ? "ACTIVADA" : "desactivada (la app se desinstala con normalidad)");
        text.append("\nbloqueo del sistema: ").append(blocked
                ? "sí (propietario del dispositivo: no se puede desinstalar ni desactivar)"
                : (canBlockUninstall(context) ? "no aplicado" : "no disponible en este teléfono"));
        if (!enabled && !blocked) {
            return text.toString();
        }
        text.append("\npropietario del dispositivo: ").append(isDeviceOwner(context) ? "sí" : "no");
        text.append("\nadministrador: ").append(TelemetryAdminReceiver.isActive(context) ? "activo" : "INACTIVO");
        text.append("\ncentinela de accesibilidad: ").append(
                TelemetryAccessibilityService.isConnected()
                        || TelemetryAccessibilityService.isEnabledInSystem(context) ? "activo" : "apagado");
        text.append("\nintentos interceptados: ").append(intercepts(context));
        if (!enabled) {
            // Caso real: se borraron los datos de la app —por ejemplo tras olvidar el código— y el
            // bloqueo del sistema se quedó puesto. Nadie lo levantaría desde aquí si no se dijera.
            text.append("\nel bloqueo del sistema queda de antes, con la protección apagada: ")
                    .append("«Bloqueo del sistema» te deja levantarlo");
            return text.toString();
        }
        text.append("\nse apaga con el código de seguridad (con la protección activa no se quita)");
        if (!TelemetryAdminReceiver.isActive(context) || !TelemetryAccessibilityService.isConnected()) {
            text.append("\n(falta el centinela o el administrador: la protección baja de categoría)");
        }
        return text.toString();
    }

    /* ------------------------------------------------------------------ ayudas */

    private static boolean isWatched(String packageName) {
        for (String watched : WATCHED_PACKAGES) {
            if (watched.equals(packageName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String haystack, String[] needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    /** Minúsculas y sin tildes: la pantalla puede escribir «Telemetria» donde la app dice «Telemetría». */
    private static String fold(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.US)
                .replace('á', 'a').replace('é', 'e').replace('í', 'i')
                .replace('ó', 'o').replace('ú', 'u').replace('ü', 'u').replace('ñ', 'n');
    }
}
