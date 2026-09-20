package com.example.telemetry;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Pantalla única: configuración, permisos y estado de persistencia del rastreo. */
public class MainActivity extends Activity {

    private static final String PREFS = "telemetry_prefs";
    private static final int REQUEST_FOREGROUND = 0x7E1;
    private static final int REQUEST_BACKGROUND = 0x7E3;
    private static final int REQUEST_PHONE_STATE = 0x7E4;
    private static final int REQUEST_STORAGE = 0x7E5;
    private static final long REFRESH_MS = 1_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private SharedPreferences prefs;
    private EditText endpointInput;
    private EditText apiKeyInput;
    private EditText deviceInput;
    private EditText labelInput;
    private EditText intervalInput;
    private EditText distanceInput;
    private EditText ghTokenInput;
    private EditText ghRepoInput;
    private EditText ghBranchInput;
    private EditText ghPathInput;
    private EditText gistIdInput;
    private EditText gistFileInput;
    private TextView statusView;
    private TextView persistenceView;
    private TextView capabilitiesView;
    private TextView errorView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        try {
            setContentView(buildUi());
        } catch (Throwable t) {
            // Una pantalla rota —una preferencia corrupta, un recurso que falta— no puede dejar la app
            // inservible: se registra el fallo y se muestra una versión mínima que sigue permitiendo
            // iniciar y detener el rastreo con la configuración ya guardada.
            ErrorLogger.recordFatal("MainActivity/buildUi", "no se pudo construir la interfaz", t);
            setContentView(fallbackUi(t));
        }
        requestForegroundPermissions();
        // Abrir la app es el único camino que el sistema NO puede bloquear: en primer plano no
        // aplica la restricción de arranque de servicios desde segundo plano (Android 12+), así que
        // aquí es donde se reanuda el rastreo que el sistema había rechazado relanzar.
        resumeTrackingIfEnabled();
        handler.post(refresh);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /* ------------------------------------------------------------------ UI */

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Telemetría P2P · Cliente Android");
        title.setTextSize(18f);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Configura la URL y activa los tres mecanismos de persistencia (administrador, "
                + "accesibilidad y batería) para que el rastreo no se detenga.");
        subtitle.setTextSize(12f);
        subtitle.setPadding(0, dp(4), 0, dp(8));
        root.addView(subtitle);

        endpointInput = addField(root, "URL del servidor (…/api/location)",
                prefs.getString("endpoint", "https://mi-servidor.ejemplo.com"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        apiKeyInput = addField(root, "API Key (opcional)", prefs.getString("api_key", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        deviceInput = addField(root, "device_id",
                prefs.getString("device_id", "android-" + Long.toHexString(System.currentTimeMillis())), InputType.TYPE_CLASS_TEXT);
        labelInput = addField(root, "Etiqueta visible", prefs.getString("label", Build.MODEL), InputType.TYPE_CLASS_TEXT);
        intervalInput = addField(root, "Intervalo mínimo entre envíos (segundos)",
                String.valueOf(Math.max(1L, prefs.getLong("min_time_ms", 5_000L) / 1000L)), InputType.TYPE_CLASS_NUMBER);
        distanceInput = addField(root, "Distancia mínima entre envíos (metros)",
                String.valueOf(Math.max(1, Math.round(prefs.getFloat("min_distance_m", 5f)))), InputType.TYPE_CLASS_NUMBER);

        TextView ghTitle = new TextView(this);
        ghTitle.setText("GitHub directo (opcional, sin servidor)");
        ghTitle.setTextSize(14f);
        ghTitle.setPadding(0, dp(16), 0, dp(2));
        root.addView(ghTitle);
        TextView ghHint = new TextView(this);
        ghHint.setText("Publica data/latest.json en tu repo vía Contents API. Crea un token con permiso "
                + "'Contents: read and write' (solo ese). El visualizador de GitHub Pages lo lee directo.");
        ghHint.setTextSize(11f);
        ghHint.setPadding(0, 0, 0, dp(4));
        root.addView(ghHint);
        ghTokenInput = addField(root, "Token de GitHub (ghp_…)", prefs.getString("gh_token", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ghRepoInput = addField(root, "usuario/repositorio", prefs.getString("gh_repo", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ghBranchInput = addField(root, "Rama (main)", prefs.getString("gh_branch", "main"), InputType.TYPE_CLASS_TEXT);
        ghPathInput = addField(root, "Ruta del fichero (data/latest.json)", prefs.getString("gh_path", "data/latest.json"),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        TextView gistTitle = new TextView(this);
        gistTitle.setText("GitHub Gist (base de datos, sin repositorio)");
        gistTitle.setTextSize(14f);
        gistTitle.setPadding(0, dp(16), 0, dp(2));
        root.addView(gistTitle);
        TextView gistHint = new TextView(this);
        gistHint.setText("Publica el histórico en un Gist que la web lee del raw_url. Usa el mismo token "
                + "de arriba: basta con permiso 'Gists: read and write' (o 'Contents' si lo compartes con el "
                + "canal de repositorio). Deja el ID vacío para desactivar este canal. Antes de cada escritura "
                + "se lee el Gist y se fusiona por timestamp_ms: nunca se borra el historial de otros equipos.");
        gistHint.setTextSize(11f);
        gistHint.setPadding(0, 0, 0, dp(4));
        root.addView(gistHint);
        gistIdInput = addField(root, "ID del gist (o su URL completa)", prefs.getString("gist_id", ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        gistFileInput = addField(root, "Fichero dentro del gist (data.json)", prefs.getString("gist_file", "data.json"),
                InputType.TYPE_CLASS_TEXT);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(12), 0, dp(4));
        actions.addView(button("Guardar", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                saveSettings();
                toast("Configuración guardada");
            }
        }));
        actions.addView(button("Iniciar", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startTracking();
            }
        }));
        actions.addView(button("Detener", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopTracking();
            }
        }));
        root.addView(actions);

        LinearLayout hardening = new LinearLayout(this);
        hardening.setOrientation(LinearLayout.HORIZONTAL);
        hardening.setPadding(0, dp(4), 0, dp(8));
        hardening.addView(button("Administrador", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestDeviceAdmin();
            }
        }));
        hardening.addView(button("Accesibilidad", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openAccessibilitySettings();
            }
        }));
        hardening.addView(button("Batería", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestBatteryExemption();
            }
        }));
        hardening.addView(button("Telefonía", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestPhoneStatePermission();
            }
        }));
        hardening.addView(button("Diagnóstico", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exportDiagnostic();
            }
        }));
        hardening.addView(button("Limpiar log", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clearErrorLog();
            }
        }));
        hardening.addView(button("Ajustes", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openAppSettings();
            }
        }));
        root.addView(hardening);

        persistenceView = new TextView(this);
        persistenceView.setTextSize(13f);
        root.addView(persistenceView);

        capabilitiesView = new TextView(this);
        capabilitiesView.setTextSize(13f);
        capabilitiesView.setTextIsSelectable(true);   // poder copiar el ID o la URL del gist
        root.addView(capabilitiesView);

        errorView = new TextView(this);
        errorView.setTextSize(12f);
        errorView.setTextIsSelectable(true);
        root.addView(errorView);

        statusView = new TextView(this);
        statusView.setTextSize(13f);
        statusView.setPadding(0, dp(10), 0, 0);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        applyWindowInsets(scroll, root);
        return scroll;
    }

    /**
     * Android 15 (API 35) dibuja a pantalla completa por defecto para las apps que apuntan a esa
     * versión: sin esto, el título y los botones quedarían bajo la barra de estado y la de
     * navegación. El relleno se ajusta a las barras reales del dispositivo (incluido el recorte de
     * pantalla), así que sigue funcionando en Android 11 y en cualquier relación de aspecto.
     */
    private void applyWindowInsets(final ScrollView scroll, final LinearLayout root) {
        final int base = dp(16);
        scroll.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int top;
                int bottom;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    top = bars.top;
                    bottom = bars.bottom;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                root.setPadding(base, base + top, base, base + bottom);
                return insets;
            }
        });
        scroll.requestApplyInsets();
    }

    /**
     * Interfaz mínima de emergencia. Sin ella, un fallo al construir la pantalla completa dejaría la
     * app abierta y muda: aquí al menos se ve el detalle del fallo y se puede arrancar o parar el
     * rastreo con lo que ya estaba configurado.
     */
    private View fallbackUi(Throwable error) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Telemetría · modo de emergencia");
        title.setTextSize(16f);
        root.addView(title);

        TextView detail = new TextView(this);
        detail.setTextSize(12f);
        detail.setTextIsSelectable(true);
        detail.setText("La interfaz completa no se ha podido construir, pero el motor de rastreo sigue "
                + "disponible.\n\nDetalle: " + (error == null ? "desconocido" : String.valueOf(error.getMessage()))
                + "\n\nEl incidente quedó registrado en Descargas/" + ErrorLogger.folder(this)
                + "/error.json (y en la copia privada de la app).\n\nReparación posible: borrar los datos "
                + "de la app o reinstalar el APK; la configuración guardada (endpoint, token, device_id) "
                + "se conserva en el almacén de preferencias.");
        detail.setPadding(0, dp(8), 0, dp(8));
        root.addView(detail);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.addView(button("Iniciar", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startTrackingFallback();
            }
        }));
        actions.addView(button("Detener", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopTracking();
            }
        }));
        actions.addView(button("Diagnóstico", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exportDiagnostic();
            }
        }));
        root.addView(actions);
        return root;
    }

    /**
     * Inicio en modo de emergencia: usa la configuración ya persistida y no toca ningún campo de la
     * interfaz (que en este modo no existe).
     */
    private void startTrackingFallback() {
        String endpoint = prefs.getString("endpoint", "");
        if (endpoint.isEmpty()) {
            toast("No hay destino configurado: instala una versión sana para configurarlo");
            return;
        }
        Watchdog.setTrackingEnabled(this, true);
        Watchdog.arm(this);
        TelemetryWorker.schedulePeriodic(this);
        LocationTrackerService.start(this);
        toast("Rastreo iniciado (modo de emergencia)");
    }

    private EditText addField(LinearLayout parent, String hint, String value, int inputType) {
        TextView label = new TextView(this);
        label.setText(hint);
        label.setTextSize(12f);
        label.setPadding(0, dp(8), 0, dp(2));
        parent.addView(label);

        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(true);
        // El tipo llega completo desde la llamada: mezclar la clase con TYPE_CLASS_TEXT convertía
        // los campos numéricos (intervalo y distancia) en un teclado de teléfono.
        input.setInputType(inputType);
        parent.addView(input);
        return input;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12f);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, dp(6), 0);
        button.setLayoutParams(params);
        return button;
    }

    /* ------------------------------------------------------------------ acciones */

    private void saveSettings() {
        String endpoint = endpointInput.getText().toString().trim().replaceAll("/+$", "");
        String apiKey = apiKeyInput.getText().toString().trim();
        String deviceId = deviceInput.getText().toString().trim();
        // ritmo de envío: compromiso entre precisión de traza y consumo de batería
        long intervalMs = Math.max(1L, parseNumber(intervalInput, 5L)) * 1000L;
        float distanceM = Math.max(1f, (float) parseNumber(distanceInput, 5L));
        prefs.edit()
                .putString("endpoint", endpoint)
                .putString("api_key", apiKey)
                .putString("device_id", deviceId)
                .putString("label", labelInput.getText().toString().trim())
                .putLong("min_time_ms", intervalMs)
                .putFloat("min_distance_m", distanceM)
                .apply();
        TelemetryClient.get(this).configure(endpoint, apiKey, deviceId);
        // publicación directa a GitHub: si el token o el repo quedan vacíos se desactiva
        String token = ghTokenInput.getText().toString().trim();
        GithubPublisher.get(this).configure(
                token,
                ghRepoInput.getText().toString().trim(),
                ghBranchInput.getText().toString().trim(),
                ghPathInput.getText().toString().trim(),
                !token.isEmpty() && !ghRepoInput.getText().toString().trim().isEmpty());
        // Canal Gist: se activa solo si hay token y ID. El ID se guarda ya normalizado (acepta la URL).
        String gistId = gistIdInput.getText().toString().trim();
        GistPublisher.get(this).configure(token, gistId,
                gistFileInput.getText().toString().trim(),
                !token.isEmpty() && !gistId.isEmpty());
        prefs.edit()
                .putString("gist_id", GistPublisher.get(this).getGistId())
                .putString("gist_file", GistPublisher.get(this).getFileName())
                .apply();
        gistIdInput.setText(GistPublisher.get(this).getGistId());
        updateStatus();
    }

    private void startTracking() {
        saveSettings();
        String endpoint = TelemetryClient.get(this).getEndpoint();
        if (endpoint.isEmpty()) {
            toast("Configura primero la URL del servidor");
            return;
        }
        if (!hasForegroundLocation()) {
            requestForegroundPermissions();
            toast("Concede el permiso de ubicación");
            return;
        }
        if (needsBackgroundLocation()) {
            requestBackgroundLocation();
            return;
        }
        Watchdog.setTrackingEnabled(this, true);
        Watchdog.arm(this);
        LocationTrackerService.start(this);
        toast("Rastreo iniciado");
        updateStatus();
    }

    private void stopTracking() {
        Watchdog.setTrackingEnabled(this, false);
        Watchdog.cancel(this);
        LocationTrackerService.stop(this);
        // Al detener, se apaga todo lo periódico y queda un único intento de entrega: los puntos ya
        // capturados no se pierden, pero no queda nada corriendo indefinidamente.
        TelemetryWorker.cancelAll(this);
        TelemetryWorker.scheduleFinalFlush(this);
        toast("Rastreo detenido");
        updateStatus();
    }

    /**
     * Reanuda el rastreo que el usuario había dejado activo, si el servicio no está vivo y hay
     * permiso de ubicación. Se llama al abrir la actividad y cuando se conceden los permisos.
     */
    private void resumeTrackingIfEnabled() {
        if (LocationTrackerService.running || !Watchdog.isTrackingEnabled(this)) {
            return;
        }
        if (!hasForegroundLocation()) {
            return;   // sin permiso no se puede arrancar en primer plano: lo pide el flujo de permisos
        }
        Watchdog.arm(this);
        TelemetryWorker.schedulePeriodic(this);
        LocationTrackerService.start(this);
        toast("Rastreo reanudado");
    }

    private void requestDeviceAdmin() {
        if (TelemetryAdminReceiver.isActive(this)) {
            TelemetryAdminReceiver.lockNow(this);
            toast("Administrador activo: pantalla bloqueada");
            return;
        }
        try {
            Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, TelemetryAdminReceiver.class));
            intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Permite que el rastreo siga activo y evita la desinstalación accidental.");
            startActivity(intent);
        } catch (Exception e) {
            toast("Este dispositivo no permite el administrador: " + e.getMessage());
        }
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            toast("No se pudo abrir Accesibilidad");
        }
    }

    private void requestBatteryExemption() {
        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            toast("La batería ya no restringe la app");
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                toast("Ajusta la batería manualmente en Ajustes");
            }
        }
    }

    /**
     * Permiso de telefonía bajo petición explícita.
     *
     * <p>{@code READ_PHONE_STATE} es peligroso y el código actual no lo necesita para nada: el
     * enriquecimiento de celdas se apoya en el permiso de ubicación precisa. Se deja declarado y se
     * ofrece aquí porque hay despliegues gestionados (MDM) que lo preotorgan y amplía el detalle de
     * telefonía en algunas compilaciones de fabricante, pero nunca se pide sin que el usuario lo
     * pulse: pedir permisos peligrosos que no se usan es exactamente lo que Play penaliza.</p>
     */
    private void requestPhoneStatePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            toast("Concedido en la instalación");
            return;
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            toast("Telefonía ya concedida");
            return;
        }
        requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, REQUEST_PHONE_STATE);
    }

    /**
     * Escribe un diagnóstico completo del sistema y lo deja junto al registro de errores.
     *
     * <p>En Android 9 y anteriores la carpeta pública de Descargas necesita permiso de almacenamiento.
     * Ese permiso ya se pide al arrancar (para que el registro llegue a Descargas desde el primer
     * fallo), pero si en su momento se denegó, este botón lo vuelve a solicitar: pedirlo justo cuando
     * el usuario demuestra que quiere el fichero es cuando tiene sentido, y no hay ningún otro momento
     * mejor para insistir.</p>
     */
    private void exportDiagnostic() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE);
            toast("Concede el permiso y se escribirá el diagnóstico");
            return;
        }
        String path = ErrorLogger.writeDiagnostic(this);
        toast(path == null || path.isEmpty()
                ? "No se pudo escribir el diagnóstico"
                : "Diagnóstico en " + path);
        updateStatus();
    }

    private void clearErrorLog() {
        boolean ok = ErrorLogger.clear(this);
        toast(ok ? "Registro de errores vaciado" : "No se pudo vaciar el registro");
        updateStatus();
    }

    private void openAppSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.fromParts("package", getPackageName(), null));
            startActivity(intent);
        } catch (Exception e) {
            toast("No se pudo abrir los ajustes de la app");
        }
    }

    /* ------------------------------------------------------------------ estado */

    private void updateStatus() {
        if (statusView == null || persistenceView == null || capabilitiesView == null || errorView == null) {
            return;   // modo de emergencia: la pantalla completa no llegó a construirse
        }
        TelemetryClient client = TelemetryClient.get(this);
        String endpoint = client.getEndpoint();
        statusView.setText(
                "ESTADO DEL RASTREO"
                        + "\nservicio: " + (LocationTrackerService.running ? "ACTIVO" : "detenido")
                        + "\ndispositivo: " + client.getDeviceId()
                        + "\nendpoint: " + (endpoint.isEmpty() ? "(sin configurar)" : endpoint)
                        + "\nenviados: " + LocationTrackerService.sentCount
                        + "  fallos: " + LocationTrackerService.failedCount
                        + "\ncola: " + client.queued()
                        + "  descartados: " + client.droppedCount()
                        + "\núltimo envío: " + LocationTrackerService.lastSummary
                        + "\nGitHub: " + (GithubPublisher.get(this).isConfigured()
                            ? GithubPublisher.lastStatus + (GithubPublisher.lastOkAt > 0
                                ? " (" + fmtAgo(GithubPublisher.lastOkAt) + ", " + GithubPublisher.publishedCount + " pubs.)"
                                : "")
                            : "desactivado (sin token/repo)")
                        + "\nGist: " + (GistPublisher.get(this).isConfigured()
                            ? GistPublisher.lastStatus + (GistPublisher.lastOkAt > 0
                                ? " (" + fmtAgo(GistPublisher.lastOkAt) + ")" : "")
                                + " · pendientes " + GistPublisher.get(this).pendingLocal()
                                + " · local " + GistPublisher.get(this).localStored()
                                + " · último envío " + GistPublisher.lastPublished
                            : "desactivado (sin token o sin ID de gist)")
                        + "\nWorkManager (gist): " + TelemetryWorker.lastGist);

        persistenceView.setText(
                "\nPERSISTENCIA"
                        + "\nadministrador: " + yesNo(TelemetryAdminReceiver.isActive(this))
                        + "\naccesibilidad: " + yesNo(TelemetryAccessibilityService.isConnected()
                            || TelemetryAccessibilityService.isEnabledInSystem(this))
                        + "\nbatería sin restricciones: " + yesNo(isIgnoringBatteryOptimizations())
                        + "\nubicación en segundo plano: " + yesNo(hasBackgroundLocation())
                        + "\nWi-Fi (NEARBY_WIFI_DEVICES): " + yesNo(hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES, Build.VERSION_CODES.TIRAMISU))
                        + "\nactividad/podómetro: " + yesNo(hasPermission(Manifest.permission.ACTIVITY_RECOGNITION, Build.VERSION_CODES.Q))
                        + "\nrelanzados por el watchdog: " + Watchdog.restarts(this)
                        + "\nrelanzados rechazados: " + Watchdog.launchAttempts(this)
                        + "\nWorkManager: " + TelemetryWorker.lastRun);

        capabilitiesView.setText(
                "\nCAPACIDADES DEL DISPOSITIVO"
                        + "\nAndroid " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"
                        + "\nrecursos: " + (isLowRamDevice() ? "bajos (Go Edition / 1 GB): modo ahorro" : "normales: modo completo")
                        + "\nubicación del sistema: " + yesNo(locationEnabled())
                        + "\nGPS: " + yesNo(providerEnabled(LocationManager.GPS_PROVIDER))
                        + "\nred (Wi-Fi/celda): " + yesNo(providerEnabled(LocationManager.NETWORK_PROVIDER))
                        + "\nPlay Services (motor fusionado): " + (FusedLocationBridge.isAvailable(this)
                            ? "disponible" : "no disponible (se usa el motor del sistema)")
                        + "\nWi-Fi RTT 802.11mc: " + (WifiRttRanger.isSupported(this) ? "soportado" : "no soportado")
                        + "\nceldas/telefonía: " + (hasPermission(Manifest.permission.READ_PHONE_STATE, Build.VERSION_CODES.M)
                            ? "concedido" : "opcional (botón Telefonía)")
                        + "\nGist raw (pegar en el visor): " + (GistPublisher.get(this).getRawUrl().isEmpty()
                            ? "(sin ID configurado)" : GistPublisher.get(this).getRawUrl())
                        + "\nGist ID: " + (GistPublisher.get(this).getGistId().isEmpty()
                            ? "(vacío: canal desactivado)" : GistPublisher.get(this).getGistId()));

        errorView.setText("\nREGISTRO DE ERRORES (" + ErrorLogger.folder(this) + "/error.json)\n  "
                + ErrorLogger.summary());
    }

    private boolean isLowRamDevice() {
        try {
            ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            return manager != null && manager.isLowRamDevice();
        } catch (Exception e) {
            return false;
        }
    }

    private boolean locationEnabled() {
        try {
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (manager == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return manager.isLocationEnabled();
            }
            return manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean providerEnabled(String provider) {
        try {
            LocationManager manager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            return manager != null && manager.isProviderEnabled(provider);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isIgnoringBatteryOptimizations() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private String yesNo(boolean value) {
        return value ? "activo" : "inactivo";
    }

    /* ------------------------------------------------------------------ permisos */

    private void requestForegroundPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        List<String> missing = new ArrayList<>();
        // Solo los permisos que el código usa de verdad. READ_PHONE_STATE es peligroso y ninguna
        // llamada actual lo necesita (el enriquecimiento de celdas vive del permiso de ubicación),
        // así que no se pide en silencio: está declarado y se solicita con el botón "Telefonía".
        for (String permission : new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION}) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        // Android 9 y anteriores: la carpeta pública de Descargas (donde se deja error.json) necesita
        // este permiso. En Android 10+ se escribe por MediaStore y no se pide nada.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        // API 29+: el podómetro y la clasificación de actividad lo exigen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            // API 33+: sin NEARBY_WIFI_DEVICES no hay ScanResult ni WifiInfo
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), REQUEST_FOREGROUND);
        }
    }

    /** Android 11+ exige pedir "Permitir siempre" en una solicitud independiente. */
    private void requestBackgroundLocation() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, REQUEST_BACKGROUND);
    }

    private boolean needsBackgroundLocation() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBackgroundLocation();
    }

    private boolean hasBackgroundLocation() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Permiso concedido (o no aplicable en esta versión de Android). */
    private boolean hasPermission(String permission, int minSdk) {
        return Build.VERSION.SDK_INT < minSdk
                || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasForegroundLocation() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_FOREGROUND) {
            updateStatus();
            if (hasForegroundLocation() && needsBackgroundLocation()) {
                requestBackgroundLocation();
            } else {
                resumeTrackingIfEnabled();
            }
        } else if (requestCode == REQUEST_STORAGE) {
            // Permiso de almacenamiento para la carpeta pública en Android 9 y anteriores: si se
            // concedió, se escribe ya el diagnóstico que el usuario había pedido.
            updateStatus();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                exportDiagnostic();
            }
        } else if (requestCode == REQUEST_PHONE_STATE) {
            boolean granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                    || checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                // Fuerza a releer Wi-Fi, celdas y telefonía en el siguiente punto sin esperar al TTL.
                LocationTrackerService.requestEnrichmentRefresh(this);
                toast("Telefonía concedida: se aplica en el próximo punto");
            }
            updateStatus();
        } else if (requestCode == REQUEST_BACKGROUND) {
            updateStatus();
            if (hasBackgroundLocation()) {
                startTracking();
            } else {
                // El servicio en primer plano ya permite rastrear en segundo plano;
                // el permiso "siempre" solo añade tolerancia ante reinicios agresivos.
                Watchdog.setTrackingEnabled(this, true);
                Watchdog.arm(this);
                LocationTrackerService.start(this);
                toast("Rastreo iniciado (recomendado: ubicación Siempre)");
            }
        }
    }

    private String fmtAgo(long when) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - when) / 1000L);
        if (seconds < 90L) {
            return seconds + " s";
        }
        return (seconds / 60L) + " min";
    }

    private long parseNumber(EditText input, long fallback) {
        try {
            return Long.parseLong(input.getText().toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
