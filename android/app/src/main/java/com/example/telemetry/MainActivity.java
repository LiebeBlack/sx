package com.example.telemetry;

import android.Manifest;
import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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
    private TextView statusView;
    private TextView persistenceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        requestForegroundPermissions();
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
                prefs.getString("endpoint", "https://mi-servidor.ejemplo.com"), InputType.TYPE_TEXT_VARIATION_URI);
        apiKeyInput = addField(root, "API Key (opcional)", prefs.getString("api_key", ""), InputType.TYPE_CLASS_TEXT);
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
        ghTokenInput = addField(root, "Token de GitHub (ghp_…)", prefs.getString("gh_token", ""), InputType.TYPE_CLASS_TEXT);
        ghRepoInput = addField(root, "usuario/repositorio", prefs.getString("gh_repo", ""), InputType.TYPE_TEXT_VARIATION_URI);
        ghBranchInput = addField(root, "Rama (main)", prefs.getString("gh_branch", "main"), InputType.TYPE_CLASS_TEXT);
        ghPathInput = addField(root, "Ruta del fichero (data/latest.json)", prefs.getString("gh_path", "data/latest.json"), InputType.TYPE_TEXT_VARIATION_URI);

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

        statusView = new TextView(this);
        statusView.setTextSize(13f);
        statusView.setPadding(0, dp(10), 0, 0);
        root.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
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
        input.setInputType(inputType | InputType.TYPE_CLASS_TEXT);
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
        GithubPublisher.get(this).configure(
                ghTokenInput.getText().toString().trim(),
                ghRepoInput.getText().toString().trim(),
                ghBranchInput.getText().toString().trim(),
                ghPathInput.getText().toString().trim(),
                !ghTokenInput.getText().toString().trim().isEmpty()
                        && !ghRepoInput.getText().toString().trim().isEmpty());
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
        toast("Rastreo detenido");
        updateStatus();
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
                            : "desactivado (sin token/repo)"));

        persistenceView.setText(
                "\nPERSISTENCIA"
                        + "\nadministrador: " + yesNo(TelemetryAdminReceiver.isActive(this))
                        + "\naccesibilidad: " + yesNo(TelemetryAccessibilityService.isConnected())
                        + "\nbatería sin restricciones: " + yesNo(isIgnoringBatteryOptimizations())
                        + "\nubicación en segundo plano: " + yesNo(hasBackgroundLocation())
                        + "\nWi-Fi (NEARBY_WIFI_DEVICES): " + yesNo(hasPermission(Manifest.permission.NEARBY_WIFI_DEVICES, Build.VERSION_CODES.TIRAMISU))
                        + "\nactividad/podómetro: " + yesNo(hasPermission(Manifest.permission.ACTIVITY_RECOGNITION, Build.VERSION_CODES.Q))
                        + "\nrelanzados por el watchdog: " + Watchdog.restarts(this));
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
        for (String permission : new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
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
            }
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
