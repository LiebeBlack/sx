package com.example.telemetry;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Pantalla única: configuración, permisos y estado de persistencia del rastreo. */
public class MainActivity extends Activity {

    private static final String PREFS = "telemetry_prefs";
    /* Paleta del proyecto: fondo negro puro OLED y acentos cian/púrpura, los mismos del visor web. */
    private static final int COLOR_CARD = 0xFF06090A;
    private static final int COLOR_ACCENT = 0xFF00FFFF;
    private static final int COLOR_TEXT = 0xFFE6FEFF;
    private static final int COLOR_TEXT_SOFT = 0xFF8FD9E0;
    private static final int COLOR_OUTLINE = 0xFF800080;
    private static final int REQUEST_FOREGROUND = 0x7E1;
    private static final int REQUEST_BACKGROUND = 0x7E3;
    private static final int REQUEST_PHONE_STATE = 0x7E4;
    private static final int REQUEST_STORAGE = 0x7E5;
    private static final int REQUEST_IMPORT = 0x7E6;
    private static final int REQUEST_EXPORT = 0x7E7;
    private static final long REFRESH_MS = 1_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            updateLockCountdown();
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
    private EditText pinInput;
    private TextView lockMessageView;
    private TextView statusView;
    private TextView persistenceView;
    private TextView capabilitiesView;
    private TextView protectionView;
    private TextView errorView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        // El candado protege la pantalla, no el rastreo: los permisos y la reanudación del servicio
        // se piden igual aunque el código esté sin introducir.
        if (SecurityGate.isEnabled(this)) {
            showLockScreen();
        } else {
            showConfigScreen();
        }
        requestForegroundPermissions();
        // Abrir la app es el único camino que el sistema NO puede bloquear: en primer plano no
        // aplica la restricción de arranque de servicios desde segundo plano (Android 12+), así que
        // aquí es donde se reanuda el rastreo que el sistema había rechazado relanzar.
        resumeTrackingIfEnabled();
        autoLoadConfig();
        handler.post(refresh);
    }

    /** Construye la pantalla de configuración (o la de emergencia) y la deja lista para usar. */
    private void showConfigScreen() {
        try {
            setContentView(buildUi());
        } catch (Throwable t) {
            // Una pantalla rota —una preferencia corrupta, un recurso que falta— no puede dejar la app
            // inservible: se registra el fallo y se muestra una versión mínima que sigue permitiendo
            // iniciar y detener el rastreo con la configuración ya guardada.
            ErrorLogger.recordFatal("MainActivity/buildUi", "no se pudo construir la interfaz", t);
            // Se sueltan las vistas a medias antes de mostrar la de emergencia: si `buildUi` falló a
            // mitad —como pasó con la tarjeta 6— la mitad construida deja campos apuntando a una
            // pantalla que ya no está en uso, y el primer latido de estado lanzaría un NPE **encima**
            // del fallo original. Con esto, todas las guardas de null que ya existen para el modo de
            // emergencia valen sin importar en qué línea se rompió.
            releaseConfigViews();
            setContentView(fallbackUi(t));
        }
        fillFieldsFromPrefs();
        updateStatus();
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
        LinearLayout root = column(dp(16));

        TextView heading = title("Telemetría · punto a punto");
        root.addView(heading);
        TextView subtitle = hint("Cliente Android nativo: captura con GPS, red, celdas y sensores, y "
                + "publica por API, por repositorio o por Gist. Rellena lo mínimo, pulsa Guardar, inicia "
                + "el rastreo y concede los tres permisos de persistencia para que no se detenga.");
        root.addView(subtitle);

        LinearLayout serverCard = card(root, "1 · Servidor y dispositivo",
                "A dónde se envían los puntos y cómo se identifica este teléfono.");
        endpointInput = addField(serverCard, "URL del servidor (…/api/location)",
                prefs.getString(AppConfig.KEY_ENDPOINT, AppConfig.DEFAULT_ENDPOINT),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        apiKeyInput = addField(serverCard, "API Key (opcional)",
                prefs.getString(AppConfig.KEY_API_KEY, ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        deviceInput = addField(serverCard, "device_id (identifica este teléfono)",
                prefs.getString(AppConfig.KEY_DEVICE_ID, AppConfig.defaultDeviceId()),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        labelInput = addField(serverCard, "Etiqueta visible",
                prefs.getString(AppConfig.KEY_LABEL, Build.MODEL), InputType.TYPE_CLASS_TEXT);

        LinearLayout rateCard = card(root, "2 · Ritmo de captura",
                "El compromiso entre precisión y batería: más segundos y más metros, menos consumo.");
        intervalInput = addField(rateCard, "Intervalo mínimo entre envíos (segundos)",
                String.valueOf(Math.max(1L, prefs.getLong(AppConfig.KEY_MIN_TIME_MS,
                        AppConfig.DEFAULT_MIN_TIME_MS) / 1000L)), InputType.TYPE_CLASS_NUMBER);
        distanceInput = addField(rateCard, "Distancia mínima entre envíos (metros)",
                String.valueOf(Math.max(0, Math.round(prefs.getFloat(AppConfig.KEY_MIN_DISTANCE_M,
                        AppConfig.DEFAULT_MIN_DISTANCE_M)))), InputType.TYPE_CLASS_NUMBER);

        LinearLayout githubCard = card(root, "3 · Publicación directa (GitHub)",
                "Opcional y sin servidor: escribe data/latest.json en tu repositorio. Token con permiso "
                        + "'Contents: read and write' (solo ese). El visualizador de Pages lo lee directo.");
        ghTokenInput = addField(githubCard, "Token de GitHub (ghp_…)",
                prefs.getString(AppConfig.KEY_GH_TOKEN, ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ghRepoInput = addField(githubCard, "usuario/repositorio",
                prefs.getString(AppConfig.KEY_GH_REPO, ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        ghBranchInput = addField(githubCard, "Rama (main)",
                prefs.getString(AppConfig.KEY_GH_BRANCH, AppConfig.DEFAULT_GH_BRANCH), InputType.TYPE_CLASS_TEXT);
        ghPathInput = addField(githubCard, "Ruta del fichero (data/latest.json)",
                prefs.getString(AppConfig.KEY_GH_PATH, AppConfig.DEFAULT_GH_PATH),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        LinearLayout gistCard = card(root, "4 · Histórico en un Gist",
                "Opcional: base de datos sin repositorio que la web lee del raw_url. Antes de cada "
                        + "escritura se lee el Gist y se fusiona por timestamp_ms, así que nunca se borra el "
                        + "historial de otros equipos. Deja el ID vacío para desactivar este canal.");
        gistIdInput = addField(gistCard, "ID del gist (o su URL completa)",
                prefs.getString(AppConfig.KEY_GIST_ID, ""),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        gistFileInput = addField(gistCard, "Fichero dentro del gist (data.json)",
                prefs.getString(AppConfig.KEY_GIST_FILE, AppConfig.DEFAULT_GIST_FILE), InputType.TYPE_CLASS_TEXT);

        LinearLayout fileCard = card(root, "5 · Configuración en un fichero y seguridad",
                "Escribe el JSON una vez —o expórtalo desde aquí— y déjalo en "
                        + AppConfig.fileLocation(this) + ": la app lo aplica sola al abrirse. Si el "
                        + "fichero lo copió un ordenador y no se aplica solo, usa Importar JSON: "
                        + "Android 10+ solo deja ver a cada app sus propios ficheros, y el selector "
                        + "del sistema no depende de eso.");
        GridLayout fileGrid = buttonGrid();
        gridAdd(fileGrid, "Importar JSON", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                importConfig();
            }
        });
        gridAdd(fileGrid, "Exportar JSON", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exportConfig();
            }
        });
        gridAdd(fileGrid, "Seguridad (PIN)", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                securityDialog();
            }
        });
        gridAdd(fileGrid, "Diagnóstico", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                exportDiagnostic();
            }
        });
        fileCard.addView(fileGrid);

        LinearLayout protectionCard = card(root, "6 · Protección contra desinstalación",
                "Impide desinstalar la app y apagar el rastreo. La garantía la da ser propietario del "
                        + "dispositivo —el sistema quita el botón de desinstalar—; el centinela de "
                        + "accesibilidad es la segunda capa. Apagarla exige el código de seguridad, y "
                        + "siempre queda una salida de emergencia por ADB. El bloqueo vive en el "
                        + "sistema y sobrevive a borrar los datos de la app: si quedara de antes, "
                        + "«Bloqueo del sistema» lo levanta desde aquí.");
        protectionView = monospaced();
        protectionView.setTextIsSelectable(true);
        protectionCard.addView(protectionView);
        GridLayout protectionGrid = buttonGrid();
        gridAdd(protectionGrid, "Activar / apagar", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleProtection();
            }
        });
        gridAdd(protectionGrid, "Bloqueo del sistema", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                blockUninstallNow();
            }
        });
        gridAdd(protectionGrid, "Cómo activarlo", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                ownerInstructions();
            }
        });
        protectionCard.addView(protectionGrid);
        // Sin `root.addView(protectionCard)`: `card(root, …)` ya la añadió al crearla. Volver a añadirla
        // lanzaba IllegalStateException («el hijo ya tiene padre») y dejaba la pantalla entera en el
        // modo de emergencia, porque `buildUi` se construye de una sola vez.

        LinearLayout actionCard = card(root, "7 · Control del rastreo", null);
        GridLayout actions = buttonGrid();
        gridAddPrimary(actions, "Guardar", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                saveSettings();
                toast("Configuración guardada");
            }
        });
        gridAddPrimary(actions, "Iniciar", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startTracking();
            }
        });
        gridAdd(actions, "Detener", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopTracking();
            }
        });
        gridAdd(actions, "Bloquear ahora", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                lockNow();
            }
        });
        actionCard.addView(actions);

        LinearLayout hardeningCard = card(root, "8 · Persistencia en el sistema",
                "Los seguros que impiden que Android detenga el rastreo. Se activan una vez.");
        GridLayout hardening = buttonGrid();
        gridAdd(hardening, "Administrador", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestDeviceAdmin();
            }
        });
        gridAdd(hardening, "Accesibilidad", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openAccessibilitySettings();
            }
        });
        gridAdd(hardening, "Batería", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestBatteryExemption();
            }
        });
        gridAdd(hardening, "Telefonía", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                requestPhoneStatePermission();
            }
        });
        gridAdd(hardening, "Limpiar log", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                clearErrorLog();
            }
        });
        gridAdd(hardening, "Ajustes", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openAppSettings();
            }
        });
        hardeningCard.addView(hardening);      // la tarjeta ya está en `root`: la añade `card(root, …)`

        LinearLayout statusCard = card(root, "9 · Estado", null);
        statusView = monospaced();
        statusCard.addView(statusView);
        persistenceView = monospaced();
        statusCard.addView(persistenceView);
        capabilitiesView = monospaced();
        capabilitiesView.setTextIsSelectable(true);   // poder copiar el ID o la URL del gist
        statusCard.addView(capabilitiesView);
        errorView = monospaced();
        errorView.setTextIsSelectable(true);
        statusCard.addView(errorView);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        applyWindowInsets(scroll, root);
        return scroll;
    }

    /** Columna vertical con relleno, la raíz de cualquier pantalla de esta actividad. */
    private LinearLayout column(int pad) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        return root;
    }

    /**
     * Tarjeta con título y explicación: agrupa los campos para que la pantalla se lea de un vistazo.
     *
     * <p><b>La añade ya al padre</b> (última línea), así que quien la reciba <b>no debe volver a
     * añadirla</b>: `root.addView(loQueDevuelve)` lanza `IllegalStateException` —«el hijo ya tiene
     * padre»— y, como `buildUi` construye todo de una vez, ese fallo tumba la pantalla completa y deja
     * la app en el modo de emergencia.</p>
     */
    private LinearLayout card(LinearLayout parent, String heading, String description) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(COLOR_CARD, COLOR_OUTLINE, 16));
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, 0);
        card.setLayoutParams(params);

        TextView title = new TextView(this);
        title.setText(heading);
        title.setTextSize(13f);
        title.setLetterSpacing(0.08f);
        title.setTextColor(COLOR_ACCENT);
        card.addView(title);

        if (description != null && !description.isEmpty()) {
            TextView detail = new TextView(this);
            detail.setText(description);
            detail.setTextSize(11f);
            detail.setTextColor(COLOR_TEXT_SOFT);
            detail.setPadding(0, dp(3), 0, 0);
            card.addView(detail);
        }
        parent.addView(card);
        return card;
    }

    /** Renglón de cabecera. */
    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(17f);
        view.setLetterSpacing(0.06f);
        view.setTextColor(COLOR_ACCENT);
        return view;
    }

    /** Texto secundario: explicaciones y estado en modo aviso. */
    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(11f);
        view.setTextColor(COLOR_TEXT_SOFT);
        view.setPadding(0, dp(4), 0, dp(2));
        return view;
    }

    /** Texto de estado: monoespaciado, para que las líneas no bailen al refrescarse cada segundo. */
    private TextView monospaced() {
        TextView view = new TextView(this);
        view.setTextSize(12f);
        view.setTypeface(Typeface.MONOSPACE);
        view.setTextColor(COLOR_TEXT);
        return view;
    }

    /** Rejilla de botones a dos columnas: en pantallas pequeñas nada se sale ni se corta. */
    private GridLayout buttonGrid() {
        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(2);
        return grid;
    }

    private void gridAdd(GridLayout grid, String text, View.OnClickListener listener) {
        grid.addView(gridButton(text, listener, false));
    }

    private void gridAddPrimary(GridLayout grid, String text, View.OnClickListener listener) {
        grid.addView(gridButton(text, listener, true));
    }

    private Button gridButton(String text, View.OnClickListener listener, boolean primary) {
        Button button = styledButton(text, listener);
        if (primary) {
            button.setTextColor(0xFF000000);
            button.setBackground(rounded(COLOR_ACCENT, 0, 12));
        } else {
            button.setTextColor(COLOR_ACCENT);
            button.setBackground(rounded(0x00000000, COLOR_OUTLINE, 12));
        }
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = 0;
        params.height = GridLayout.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f);
        params.setMargins(dp(3), dp(3), dp(3), dp(3));
        button.setLayoutParams(params);
        return button;
    }

    /** Rectángulo con esquinas redondeadas y borde opcional, sin recursos XML. */
    private GradientDrawable rounded(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != 0) {
            drawable.setStroke(dp(1), stroke);
        }
        return drawable;
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
        label.setTextSize(11f);
        label.setTextColor(COLOR_TEXT_SOFT);
        label.setPadding(0, dp(10), 0, dp(2));
        parent.addView(label);

        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(true);
        input.setTextSize(14f);
        // El tipo llega completo desde la llamada: mezclar la clase con TYPE_CLASS_TEXT convertía
        // los campos numéricos (intervalo y distancia) en un teclado de teléfono.
        input.setInputType(inputType);
        parent.addView(input);
        return input;
    }

    private Button styledButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12f);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    /** Botón suelto (pantalla de bloqueo y modo de emergencia), sin rejilla. */
    private Button button(String text, View.OnClickListener listener) {
        Button button = styledButton(text, listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), dp(6), dp(6));
        button.setLayoutParams(params);
        return button;
    }

    /** Copia a los campos lo que hay guardado (al arrancar y después de importar un fichero). */
    private void fillFieldsFromPrefs() {
        if (endpointInput == null) {
            return;   // modo de emergencia: no hay campos que rellenar
        }
        endpointInput.setText(prefs.getString(AppConfig.KEY_ENDPOINT, ""));
        apiKeyInput.setText(prefs.getString(AppConfig.KEY_API_KEY, ""));
        deviceInput.setText(prefs.getString(AppConfig.KEY_DEVICE_ID, AppConfig.defaultDeviceId()));
        labelInput.setText(prefs.getString(AppConfig.KEY_LABEL, Build.MODEL));
        intervalInput.setText(String.valueOf(Math.max(1L,
                prefs.getLong(AppConfig.KEY_MIN_TIME_MS, AppConfig.DEFAULT_MIN_TIME_MS) / 1000L)));
        distanceInput.setText(String.valueOf(Math.max(0,
                Math.round(prefs.getFloat(AppConfig.KEY_MIN_DISTANCE_M, AppConfig.DEFAULT_MIN_DISTANCE_M)))));
        ghTokenInput.setText(prefs.getString(AppConfig.KEY_GH_TOKEN, ""));
        ghRepoInput.setText(prefs.getString(AppConfig.KEY_GH_REPO, ""));
        ghBranchInput.setText(prefs.getString(AppConfig.KEY_GH_BRANCH, AppConfig.DEFAULT_GH_BRANCH));
        ghPathInput.setText(prefs.getString(AppConfig.KEY_GH_PATH, AppConfig.DEFAULT_GH_PATH));
        gistIdInput.setText(prefs.getString(AppConfig.KEY_GIST_ID, ""));
        gistFileInput.setText(prefs.getString(AppConfig.KEY_GIST_FILE, AppConfig.DEFAULT_GIST_FILE));
    }

    /* ------------------------------------------------------------------ seguridad y ficheros */

    /**
     * Pone el candado: suelta las vistas de la pantalla de configuración y muestra la del código.
     *
     * <p>Soltarlas no es cosmético: {@link #updateStatus()} y {@link #fillFieldsFromPrefs()} se
     * ejecutan en cada latido, y sus guardas de {@code null} —escritas para el modo de emergencia—
     * son las que ahora impiden tocar vistas que ya no están en pantalla.</p>
     */
    private void showLockScreen() {
        releaseConfigViews();
        setContentView(buildLockUi());
    }

    /** Suelta las referencias a la pantalla de configuración cuando deja de estar en pantalla. */
    private void releaseConfigViews() {
        endpointInput = null;
        apiKeyInput = null;
        deviceInput = null;
        labelInput = null;
        intervalInput = null;
        distanceInput = null;
        ghTokenInput = null;
        ghRepoInput = null;
        ghBranchInput = null;
        ghPathInput = null;
        gistIdInput = null;
        gistFileInput = null;
        statusView = null;
        persistenceView = null;
        capabilitiesView = null;
        protectionView = null;
        errorView = null;
    }

    /**
     * Pantalla de bloqueo: solo el código. Debajo del candado no se ve nada de la configuración
     * —ni el endpoint ni los tokens— y arriba se recuerda que el rastreo sigue vivo.
     */
    private View buildLockUi() {
        LinearLayout root = column(dp(20));
        root.setGravity(Gravity.CENTER);

        root.addView(title("Telemetría · acceso protegido"));
        root.addView(hint("Escribe el código de seguridad para ver y cambiar la configuración. El "
                + "rastreo sigue funcionando con esta pantalla bloqueada: el candado no puede detener "
                + "el motor ni perder puntos."));

        pinInput = new EditText(this);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setHint("Código");
        pinInput.setSingleLine(true);
        pinInput.setGravity(Gravity.CENTER);
        pinInput.setTextSize(20f);
        pinInput.setPadding(0, dp(20), 0, dp(10));
        pinInput.setImeOptions(EditorInfo.IME_ACTION_DONE);   // el teclado trae «Listo»
        pinInput.requestFocus();                             // se escribe sin tocar el campo
        pinInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
                attemptUnlock();
                return true;
            }
        });
        root.addView(pinInput);

        lockMessageView = hint("");
        root.addView(lockMessageView);

        GridLayout grid = buttonGrid();
        gridAddPrimary(grid, "Desbloquear", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                attemptUnlock();
            }
        });
        root.addView(grid);

        root.addView(button("¿Código olvidado?", new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                forgotPinDialog();
            }
        }));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        applyWindowInsets(scroll, root);
        return scroll;
    }

    private void attemptUnlock() {
        if (pinInput == null || lockMessageView == null) {
            return;
        }
        long remaining = SecurityGate.lockRemainingMs(this);
        if (remaining > 0L) {
            lockMessageView.setText("Bloqueado por intentos fallidos: espera "
                    + ((remaining + 999L) / 1000L) + " s");
            return;
        }
        if (SecurityGate.verify(this, pinInput.getText().toString())) {
            pinInput.setText("");
            pinInput = null;
            lockMessageView = null;
            showConfigScreen();
            toast("Acceso concedido");
            return;
        }
        pinInput.selectAll();   // lo siguiente que se escriba sustituye al intento fallido
        int fails = SecurityGate.failures(this);
        if (fails >= SecurityGate.MAX_FAILS) {
            lockMessageView.setText("Código incorrecto " + fails + " veces: espera "
                    + (SecurityGate.LOCKOUT_MS / 1000L) + " s");
        } else {
            lockMessageView.setText("Código incorrecto (" + fails + " de " + SecurityGate.MAX_FAILS
                    + " antes de que se bloquee)");
        }
    }

    /** Cuenta atrás del bloqueo temporal, refrescada por el mismo latido de un segundo. */
    private void updateLockCountdown() {
        if (lockMessageView == null) {
            return;
        }
        long remaining = SecurityGate.lockRemainingMs(this);
        lockMessageView.setText(remaining > 0L
                ? "Bloqueado por intentos fallidos: espera " + ((remaining + 999L) / 1000L) + " s"
                : "");
    }

    /**
     * Vuelve a poner el candado sin salir de la app: la pantalla de configuración —con los tokens y
     * con el botón que apaga la protección— queda otra vez detrás del código. El rastreo no se toca.
     */
    private void lockNow() {
        if (!SecurityGate.isEnabled(this)) {
            toast("No hay código de seguridad: defínelo en la tarjeta 5");
            return;
        }
        showLockScreen();
        toast("Pantalla bloqueada: el rastreo sigue activo");
    }

    /**
     * La salida del candado cuando el código se olvidó, dicha entera: qué se pierde, qué <b>no</b> se
     * pierde, y los comandos de este teléfono. Un candado sin salida deja al dueño fuera de su propio
     * teléfono, así que esto es tan parte de la función como el propio código.
     */
    private void forgotPinDialog() {
        String message = "El código protege esta pantalla, no el rastreo: el servicio, el vigilante y "
                + "el arranque del teléfono siguen funcionando con el candado puesto, así que olvidarlo "
                + "no detiene la captura ni pierde puntos. Lo que se pierde es el acceso a esta pantalla."
                + "\n\n1) El camino más corto: crea el fichero " + AppConfig.fileLocation(this)
                + " con un código nuevo —{\"security\": {\"pin\": \"4821\"}}—; la app lo aplica sola al "
                + "abrirse y entras con ese código. No es una puerta trasera: para "
                + "escribir ahí hace falta acceso físico al teléfono, exactamente el mismo que hace "
                + "falta para borrar los datos.\n\n2) O borra los datos de la app: Ajustes → "
                + "Aplicaciones → " + getString(R.string.app_name) + " → Almacenamiento → Borrar "
                + "datos. El candado desaparece y vuelves a entrar. Se pierde la configuración guardada "
                + "en el teléfono —endpoint, tokens y código—, nunca lo ya publicado al servidor, al "
                + "repositorio o al Gist.\n\n3) Una configuración exportada —o el config.json que le "
                + "diste a la app— vuelve a dejar el teléfono como estaba en un paso: Importar JSON.\n\n"
                + "4) Si esta app llegó a ser propietaria del dispositivo, ese papel y el bloqueo de "
                + "desinstalación sobreviven al borrado de datos, porque viven en el sistema: al "
                + "abrirla los verás en la tarjeta 6 y «Bloqueo del sistema» te deja levantarlo. Con "
                + "ADB también se quita:\n\n" + UninstallGuard.removeOwnerCommand(this) + "\n\n"
                + "El bloqueo por intentos fallidos es de " + (SecurityGate.LOCKOUT_MS / 1000L)
                + " s, así que esperar y volver a probar es lo primero que conviene intentar si el "
                + "código se cree recordar.";
        new AlertDialog.Builder(this)
                .setTitle("Código olvidado")
                .setMessage(message)
                .setPositiveButton("Abrir Ajustes de la app", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openAppSettings();
                    }
                })
                .setNeutralButton("Copiar comando ADB", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyToClipboard(UninstallGuard.removeOwnerCommand(MainActivity.this));
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    /**
     * Carga automática al abrir la app: si hay un {@code config.json} en Descargas, se aplica.
     *
     * <p>La lectura toca disco, así que va en su propio hilo y nunca bloquea la interfaz; solo se
     * avisa cuando había un fichero nuevo que aplicar.</p>
     */
    private void autoLoadConfig() {
        Thread loader = new Thread(new Runnable() {
            @Override
            public void run() {
                final AppConfig.Result result = AppConfig.loadFromDownloadsIfChanged(MainActivity.this);
                if (!result.found) {
                    return;   // sin fichero, o ya estaba aplicado: silencio
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        fillFieldsFromPrefs();
                        updateStatus();
                        toast(result.ok()
                                ? "Configuración aplicada del fichero: " + result.summary()
                                : "El fichero de configuración no se pudo aplicar: " + result.error);
                    }
                });
            }
        }, "config-autoload");
        loader.setDaemon(true);
        loader.start();
    }

    /** Elegir un JSON con el selector del sistema: no necesita ningún permiso. */
    private void importConfig() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_IMPORT);
        } catch (Exception e) {
            toast("No hay selector de ficheros disponible");
        }
    }

    /** Guardar la configuración actual donde elija el usuario. */
    private void exportConfig() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "telemetria-config.json");
            startActivityForResult(intent, REQUEST_EXPORT);
        } catch (Exception e) {
            toast("No hay dónde guardar el fichero");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQUEST_IMPORT) {
            applyImported(uri);
        } else if (requestCode == REQUEST_EXPORT) {
            writeExported(uri);
        }
    }

    private void applyImported(final Uri uri) {
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                String text = "";
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    try {
                        String read = AppConfig.readAll(in);
                        if (read != null) {
                            text = read;
                        }
                    } finally {
                        closeQuietly(in);
                    }
                } catch (Throwable t) {
                    ErrorLogger.record("MainActivity/importar", "no se pudo leer el fichero elegido", t);
                }
                final AppConfig.Result result = AppConfig.importText(MainActivity.this, text);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        fillFieldsFromPrefs();
                        updateStatus();
                        toast(result.ok()
                                ? "Importado: " + result.summary()
                                : "No se pudo importar: " + result.error);
                    }
                });
            }
        }, "config-import");
        worker.setDaemon(true);
        worker.start();
    }

    private void writeExported(final Uri uri) {
        final String text = AppConfig.exportText(this);
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                String failure = null;
                try {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    try {
                        out.write(text.getBytes("UTF-8"));
                        out.flush();
                    } finally {
                        closeQuietly(out);
                    }
                } catch (Throwable t) {
                    failure = String.valueOf(t.getMessage());
                    ErrorLogger.record("MainActivity/exportar", "no se pudo escribir la configuración", t);
                }
                final String error = failure;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(error == null
                                ? "Configuración exportada: sirve para provisionar otro teléfono"
                                : "No se pudo exportar: " + error);
                    }
                });
            }
        }, "config-export");
        worker.setDaemon(true);
        worker.start();
    }

    /** Define, cambia o quita el código de seguridad de la pantalla. */
    private void securityDialog() {
        final boolean enabled = SecurityGate.isEnabled(this);
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("De " + SecurityGate.MIN_LENGTH + " a " + SecurityGate.MAX_LENGTH + " dígitos");
        input.setSingleLine(true);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(enabled ? "Cambiar o quitar el código" : "Definir código de seguridad");
        builder.setMessage("El código protege esta pantalla, no el rastreo: con la interfaz bloqueada "
                + "el servicio sigue capturando y publicando.\n\nNo se guarda en claro —SHA-256 con sal "
                + "por dispositivo— y tras " + SecurityGate.MAX_FAILS + " intentos fallidos la pantalla "
                + "se bloquea " + (SecurityGate.LOCKOUT_MS / 1000L) + " s.\n\nSi lo olvidas: el botón "
                + "«¿Código olvidado?» de la pantalla de bloqueo lo explica entero —borrar los datos de "
                + "la app devuelve el acceso, y el rastreo no se detiene en ningún momento.\n\n"
                + (UninstallGuard.isEnabled(this)
                        ? "Con la protección activada el código no se puede quitar: es lo único que "
                        + "separa esta pantalla de quien coja el teléfono, y desde ella se apaga la "
                        + "protección. Solo se cambia por otro código."
                        : "También puedes cerrar la pantalla a mano con «Bloquear ahora» (tarjeta 7)."));
        builder.setView(input);
        builder.setPositiveButton(enabled ? "Cambiar" : "Activar", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (SecurityGate.set(MainActivity.this, input.getText().toString())) {
                    toast("Código guardado: se pedirá al abrir la app");
                } else {
                    toast("El código debe tener de " + SecurityGate.MIN_LENGTH + " a "
                            + SecurityGate.MAX_LENGTH + " dígitos");
                }
            }
        });
        if (enabled) {
            builder.setNeutralButton("Quitar", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    if (!SecurityGate.clear(MainActivity.this)) {
                        toast("No se puede quitar el código con la protección activada: apágala en la "
                                + "tarjeta 6 y vuelve a intentarlo");
                        return;
                    }
                    toast("Candado quitado: la pantalla vuelve a abrirse sin código");
                }
            });
        }
        builder.setNegativeButton("Cancelar", null);
        builder.show();
    }

    private void closeQuietly(Closeable stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
            // cerrar nunca debe tapar el resultado de la operación
        }
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

    /* ------------------------------------------------------------------ desinstalación */

    /**
     * Activa o apaga la protección. Apagarla exige el código de seguridad: es lo que impide que quien
     * tiene el teléfono en la mano quite de un toque lo que configuró el dueño. Si no hay código, se
     * ofrece definirlo en ese momento en vez de dejar el candado sin llave.
     */
    private void toggleProtection() {
        if (UninstallGuard.isEnabled(this)) {
            UninstallGuard.setEnabled(this, false);
            toast("Protección desactivada: la app se desinstala con normalidad");
            updateStatus();
            return;
        }
        if (!SecurityGate.isEnabled(this)) {
            toast("Define primero el código de seguridad: sin él cualquiera podría apagarla");
            securityDialog();
            return;
        }
        UninstallGuard.setEnabled(this, true);
        if (UninstallGuard.isUninstallBlocked(this)) {
            toast("Protección activada: el sistema ya no permite desinstalarla");
        } else if (UninstallGuard.canBlockUninstall(this)) {
            toast("Protección activada: el bloqueo del sistema queda aplicado");
        } else {
            toast("Protección activada. Sin ser propietario del dispositivo el sistema no puede "
                    + "bloquearla: usa «Cómo activarlo» para la garantía real");
        }
        updateStatus();
    }

    /**
     * Aplica el bloqueo del sistema, lo levanta si está puesto, o explica lo que falta para poder
     * aplicarlo.
     *
     * <p>Levantarlo importa de verdad en un caso concreto: el bloqueo vive en el sistema y sobrevive a
     * borrar los datos de la app —el camino para salir de un código olvidado—, así que sin este botón
     * el bloqueo se quedaría puesto con la protección apagada y la app solo sería desinstalable por
     * ADB. Con la protección activada no se levanta desde aquí: se volvería a aplicar sola en el
     * siguiente latido, y para eso ya está «Activar / apagar», que sí exige el código.</p>
     */
    private void blockUninstallNow() {
        if (UninstallGuard.isUninstallBlocked(this)) {
            if (UninstallGuard.isEnabled(this)) {
                toast("El bloqueo lo mantiene la protección: apágala en «Activar / apagar» y se "
                        + "levanta solo");
                return;
            }
            new AlertDialog.Builder(this)
                    .setTitle("Levantar el bloqueo del sistema")
                    .setMessage("El sistema sigue impidiendo desinstalar esta app, pero la protección "
                            + "está apagada: es un bloqueo que quedó de antes, por ejemplo de cuando "
                            + "se borraron los datos de la app. Levantarlo la deja desinstalable con "
                            + "normalidad.")
                    .setPositiveButton("Levantar el bloqueo", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            toast(UninstallGuard.setUninstallBlocked(MainActivity.this, false)
                                    ? "Bloqueo levantado: esta app se puede desinstalar"
                                    : "El sistema rechazó levantarlo");
                            updateStatus();
                        }
                    })
                    .setNegativeButton("Dejarlo puesto", null)
                    .show();
            return;
        }
        if (!UninstallGuard.canBlockUninstall(this)) {
            ownerInstructions();
            return;
        }
        toast(UninstallGuard.setUninstallBlocked(this, true)
                ? "Bloqueo aplicado: esta app no se puede desinstalar"
                : "El sistema rechazó el bloqueo");
        updateStatus();
    }

    /** Los comandos exactos, con la salida de emergencia incluida: esto no debe encerrar a nadie. */
    private void ownerInstructions() {
        String message = "La garantía real es que la app sea propietaria del dispositivo: entonces el "
                + "sistema quita el botón de desinstalar y la app no se puede desactivar.\n\n"
                + "1) Restablece el teléfono de fábrica y no añadas cuentas (es un requisito del sistema).\n"
                + "2) Activa la depuración USB y conéctalo al ordenador.\n"
                + "3) Ejecuta:\n\n" + UninstallGuard.ownerCommand(this) + "\n\n"
                + "Salida de emergencia, si algún día quieres quitarla:\n\n"
                + UninstallGuard.removeOwnerCommand(this) + "\n\n"
                + "Y siempre quedan el modo seguro y el restablecimiento de fábrica: ninguna de estas "
                + "capas es irreversible.";
        new AlertDialog.Builder(this)
                .setTitle("Propietario del dispositivo")
                .setMessage(message)
                .setPositiveButton("Copiar comando", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        copyToClipboard(UninstallGuard.ownerCommand(MainActivity.this));
                    }
                })
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void copyToClipboard(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                toast("No se pudo copiar");
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("telemetria", text));
            toast("Comando copiado");
        } catch (Exception e) {
            toast("No se pudo copiar: " + e.getMessage());
        }
    }

    /* ------------------------------------------------------------------ estado */

    private void updateStatus() {
        if (statusView == null || persistenceView == null || capabilitiesView == null || errorView == null
                || protectionView == null) {
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
                        + "\nWorkManager (gist): " + TelemetryWorker.lastGist
                        + "\n\nRECORRIDO POR DÍA\n" + DailyTally.summary(this));

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

        protectionView.setText(
                "\nPROTECCIÓN CONTRA DESINSTALACIÓN"
                        + "\n" + UninstallGuard.stateSummary(this));

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
