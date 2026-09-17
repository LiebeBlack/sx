# Telemetría y geolocalización punto a punto

[![CI](https://github.com/OWNER/REPO/actions/workflows/ci.yml/badge.svg)](https://github.com/OWNER/REPO/actions/workflows/ci.yml)
![Python](https://img.shields.io/badge/Python-3.10%2B-3776AB?logo=python&logoColor=white)
![Android](https://img.shields.io/badge/Android-API%2030--34-3DDC84?logo=android&logoColor=white)
![Sin dependencias JS](https://img.shields.io/badge/frontend-Vanilla%20JS-f7df1e)
![Licencia](https://img.shields.io/badge/licencia-MIT-blue)

Cliente Android (API 24-34) → API Flask con fusión de sensores → Visualizador web estático
publicable en GitHub Pages. Sin base de datos, sin frameworks de frontend y sin dependencias
fuera del SDK de Android.

## Dos modos de producción

> 📋 **Guía completa de puesta en marcha de verdad**: [`docs/SETUP.md`](docs/SETUP.md) — token
> fine-grained paso a paso, app, Pages, espejo con docker-compose, checklist y solución de problemas.

| | Modo API en vivo | Modo GitHub directo |
|---|---|---|
| Latencia | segundos | ~1 min (rate limit de la Contents API) |
| Requiere servidor | sí (VPS/LAN/Docker) | **no**: el móvil publica a GitHub por Wi-Fi o datos |
| Consumo GitHub | 0 | ~1 request/min por dispositivo |
| Ideal para | seguimiento en tiempo real | demo táctica, sin infraestructura |

En ambos casos el visualizador es el mismo: elige la fuente en el desplegable
**API en vivo / GitHub Pages (fichero)**.

### Modo GitHub directo (sin servidor)

1. Crea un **fine-grained token** con permiso `Contents: read and write` solo en el repo de destino
   (Settings → Developer settings → Fine-grained tokens).
2. En la app, rellena la sección **GitHub directo**: token, `usuario/repositorio`, rama y ruta
   (`data/latest.json` por defecto). Guarda e inicia el rastreo.
3. La app escribe el fichero vía Contents API: fusión por `device_id`, reintento automático ante
   409 (sha obsoleto) y publicación como mucho cada 60 s. Activa **Settings → Pages → Deploy from
   branch** en el repo.
4. En el visualizador, elige la fuente **GitHub Pages (fichero)**. Desde la propia web de Pages la
   ruta por defecto ya es correcta; para otro repo escribe `https://usuario.github.io/repo` en el
   campo API.

Con el backend en marcha, `GITHUB_REPO=usuario/repo` + `GITHUB_TOKEN` activan además el **espejo**
(`backend/github_mirror.py`): el servidor consolida el estado completo en el mismo fichero con rate
limit y diff mínimo (estado visible en `/api/health → github_mirror`).

> El token vive en el móvil y solo debe tener alcance de escritura en ese repo (fine-grained, no
> classic). Publicar ubicación en un repo **público** lo hace visible para cualquiera.

```
backend/app.py                          API JSON: Kalman, altitud fusionada, integridad de datos
backend/github_mirror.py                Espejo del estado a GitHub (Contents API)
android/.../GithubPublisher.java        Publicación directa del móvil a GitHub (Wi-Fi o datos)
frontend/index.html                     Visualizador Vanilla JS + Leaflet (auto-fetch 3 s, 2 fuentes)
tools/simulate.py                       Generador de trafico sintetico (--noisy)
tools/check_frontend.py                 Valida el JS embebido con node --check
tests/test_api.py · tests/test_fusion.py  Tests de endpoints, filtros y fusion
 docs/API.md                            Referencia de endpoints con ejemplos curl
 docs/SETUP.md                          Puesta en producción completa (token, Pages, espejo)
scripts/dev.sh                          install | run | test | simulate | apk | lint
Dockerfile · docker-compose.yml         Despliegue en contenedor con volumen /data
.github/workflows/ci.yml                CI: tests del backend + build del APK

android/settings.gradle · build.gradle · gradle.properties    Proyecto Gradle (AGP 8.5.2 / Gradle 8.7)
android/app/build.gradle · proguard-rules.pro                Módulo: compileSdk 34, minSdk 24, firma opcional
android/app/src/main/AndroidManifest.xml
android/app/src/main/res/drawable/ic_launcher.xml             Icono vectorial
android/app/src/main/res/values/strings.xml
android/app/src/main/res/xml/device_admin.xml
android/app/src/main/res/xml/accessibility_service_config.xml
android/app/src/main/java/com/example/telemetry/
    MainActivity.java                  Configuración, permisos y estado de persistencia
    LocationTrackerService.java        Servicio en primer plano: GPS/red/pasivo/fused + Wi-Fi + celdas
    GnssMonitor.java                   Satélites visibles/en uso, SNR y constelaciones
    SensorFusion.java                  Barómetro, podómetro, brújula y clasificación de actividad
    TelemetryClient.java               POST JSON nativo (HttpURLConnection, cola, backoff)
    Watchdog.java                      Vigilante: latido persistido, alarma y relanzado
    WatchdogReceiver.java              Arranque, actualización del paquete, desbloqueo y alarma
    TelemetryAdminReceiver.java        Administrador de dispositivo
    TelemetryAccessibilityService.java Centinela de accesibilidad (sin leer contenido)
```

## 1. Backend

```bash
cd backend
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r requirements.txt
python app.py                                      # http://0.0.0.0:8000
```

Producción: `gunicorn -w 1 --threads 8 -b 0.0.0.0:8000 app:app` (Windows: `waitress-serve --threads=8 --port=8000 app:app`).
Usa **1 worker**: el estado vive en memoria; con varios procesos cada worker tendría su propio subconjunto.

| Variable | Default | Uso |
|---|---|---|
| `TELEMETRY_API_KEY` | *vacío* | si se define, exige `X-Api-Key` (o `?key=`) en POST/DELETE |
| `TELEMETRY_STORE` | `./telemetry_store.jsonl` | archivo plano append-only |
| `TELEMETRY_MAX_POINTS` | `5000` | puntos retenidos por dispositivo |
| `TELEMETRY_COMPACT_EVERY` | `2000` | líneas escritas antes de compactar el archivo |
| `HOST` / `PORT` | `0.0.0.0` / `8000` | bind del servidor de desarrollo |

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/api/location` | un punto `{lat,lng,timestamp,…}` o lote `{"points":[ … ]}` (máx 500) |
| `GET` | `/api/locations` | todos los dispositivos; `?format=compact&limit=300&since=<ms>&latest=1` |
| `GET` | `/api/locations/<device_id>` | historial de un dispositivo |
| `DELETE` | `/api/locations/<device_id>` | borra el dispositivo (requiere key) |
| `GET` | `/api/health` | estado, nº de dispositivos, puntos y ruta de almacenamiento |

El servidor valida rangos, normaliza el timestamp (s → ms), descarta duplicados y puntos fuera de
orden, y calcula distancia recorrida, velocidad media/máxima y rumbo. También sirve
`frontend/index.html` en `/` si existe la carpeta.

## 2. Frontend en GitHub Pages

Publica la carpeta `frontend/` y escribe la URL pública del backend en el campo **API**
(se guarda en `localStorage`). Alternativa antes de cargar el script:

```html
<script>window.TELEMETRY_API = 'https://tu-servidor'; window.TELEMETRY_TOKEN = 'mi-clave';</script>
```

CORS ya está abierto en el backend. Sondea `?format=compact&limit=300` cada 3 s (1/3/5/10 s),
con timeout de 8 s, backoff exponencial, pausa con la pestaña oculta y modo radar canvas si
Leaflet no está disponible.

## 3. Cliente Android

`minSdkVersion 24`, `targetSdkVersion 34`, sin dependencias externas:

```gradle
android {
    namespace "com.example.telemetry"
    compileSdk 34
    defaultConfig { minSdk 24; targetSdk 34 }
    compileOptions { sourceCompatibility JavaVersion.VERSION_1_8; targetCompatibility JavaVersion.VERSION_1_8 }
}
```

Build del APK (requiere JDK 17 y Android SDK 34, con `ANDROID_HOME` o `local.properties`):

```bash
cd android
gradle wrapper --gradle-version 8.7      # una sola vez: crea gradlew + gradle-wrapper.jar
./gradlew assembleDebug                  # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                # -> app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

El release va ofuscado (`minifyEnabled` + `shrinkResources`) con las reglas de `proguard-rules.pro`,
que conservan los componentes instanciados por el sistema. Firma: si existe `android/keystore.properties`
se usa para el release; si no, el release se firma con la clave de depuración y **la compilación nunca
falla por falta de keystore**:

```properties
storeFile=telemetria.jks
storePassword=tu_password
keyAlias=telemetria
keyPassword=tu_password
```

En la app: URL (`https://tu-servidor`, el cliente añade `/api/location`), API key opcional,
`device_id`, etiqueta, **intervalo mínimo (s)** y **distancia mínima (m)** → **Guardar** → **Iniciar**.
Esos dos valores son el compromiso entre fidelidad de traza y batería (por defecto 5 s / 5 m); la
notificación muestra en vivo `±precisión · satélites · actividad · cola` como máximo cada 10 s.

Fuentes de posicionamiento, todas activas a la vez:

| Fuente | Detalle | API |
|---|---|---|
| `GPS_PROVIDER`, `NETWORK_PROVIDER`, `PASSIVE_PROVIDER`, `fused` | `requestLocationUpdates` continuo en cada proveedor disponible | 24+ |
| Último punto conocido de cada proveedor | primer dato al arrancar sin esperar señal | 24+ |
| `getCurrentLocation` (fix inmediato) | al arrancar y cuando el último fix supera 2 min | 30+ |
| GNSS (`GnssStatus`) | satélites visibles/en uso, SNR medio y mejor, constelaciones (GPS/GLONASS/Galileo/BeiDou/QZSS/IRNSS/SBAS) | 24+ |
| Barómetro | `altitude_baro` + `pressure_hpa`, más estable que la altitud GPS | sensor |
| Podómetro | `steps` para validar movimiento real | sensor (+`ACTIVITY_RECOGNITION` 29+) |
| Brújula | `heading_magnetic`, rumbo de reserva cuando el GPS no lo da | sensor |
| Acelerómetro | `activity` (still/walking/running/vehicle) y `movement` | sensor |
| Wi-Fi | AP conectado vía `ConnectivityManager` (10+) con caída a `WifiManager` + hasta 16 AP vecinos | 24+ / `NEARBY_WIFI_DEVICES` 33+ |
| Celdas | LTE/GSM/WCDMA y variantes tipo NR: MCC/MNC/CID/TAC/PCI/RSRP/RSRQ/dBm | 24+ |
| Red y estado | `network`, `data_network` (2G/3G/4G/5G), operador, `sdk`, `model`, batería, `mock` | 24+ |

Cada punto lleva además `elapsed_nanos` (reloj monotónico, para calcular `dt` exacto),
`vertical_accuracy`, `speed_accuracy`, `bearing_accuracy` (API 26+) y `accuracy`. Wi-Fi, celdas,
batería y red se cachean 30 s para no castigar la batería; el envío se dispara por tiempo,
desplazamiento **o mejora clara de precisión** en el mismo sitio.

### Fusión y filtrado en el servidor

1. **`dt` monotónico** — se calcula con `elapsed_nanos` (reloj de arranque) cuando está disponible,
   inmune a cambios de hora; si no, con el timestamp.
2. **Filtro de Kalman ligero** (`PositionFilter`) — estado (posición, velocidad) por eje en un plano
   métrico local, con ruido de medida = precisión² y ruido de proceso = 1,5 m/s². Suaviza el ruido
   del GPS (sobre todo en parado) sin retrasar el movimiento real y publica la velocidad estimada.
3. **Altitud fusionada** — el barómetro es preciso pero relativo: el primer fix GPS bueno lo calibra
   (<= 50 m de error) y cada punto publica `altitude_fused`, estable en interiores.
4. **Filtros de integridad** — saltos > 90 m/s se marcan `outlier` y no contaminan ni el filtro ni
   las estadísticas; los fixes peores de 150 m se marcan `low_quality` y no suman distancia.
5. **Resumen honesto** — `distance_m` (filtrada), `raw_distance_m` (bruta), `noise_removed_m`
   (ruido eliminado), `avg_accuracy_m`, `outliers`, `low_quality`, `activity` y `satellites`.

Cada punto incluye `smooth_lat`/`smooth_lng` (posición filtrada) y el endpoint devuelve además un
array `smooth` (`[ts, lat, lng, speed]`) para dibujar la traza limpia. En el visualizador, la casilla
**suavizado** alterna entre traza GPS cruda y filtrada, y se muestran ambos recorridos.

Red: `HttpURLConnection`, timeouts de 10 s, lotes de 40, cola persistida (máx 1000), 5 reintentos
con backoff 0,8 s→30 s, descarte de 4xx irrecuperables y reintento cada 15 s. Todo el I/O corre en
un `ExecutorService` de un hilo: el hilo principal nunca se bloquea.

### Compatibilidad API 30–34

| Versión | Qué cambia y cómo se cubre |
|---|---|
| Android 11 (30) | `ACCESS_BACKGROUND_LOCATION` se pide en una solicitud **independiente**; `getCurrentLocation` como fix inmediato; `ConnectivityManager.getTransportInfo()` para el Wi-Fi real. |
| Android 12 (31) | `PendingIntent` siempre con `FLAG_IMMUTABLE`; `location.isMock()`; `setForegroundServiceBehavior(IMMEDIATE)`; arranques de FGS desde segundo plano capturados (el watchdog reintenta y el sistema reanuda por `START_STICKY`). |
| Android 13 (33) | `POST_NOTIFICATIONS` en runtime; **`NEARBY_WIFI_DEVICES`** obligatorio para `ScanResult`/`WifiInfo` (declarado sin `neverForLocation`, porque sí usamos la señal para ubicar). |
| Android 14 (34) | `FOREGROUND_SERVICE_LOCATION` declarado y comprobado antes de `startForeground`; servicio con `stopWithTask=false`; `high sampling` no necesario (sensores en `SENSOR_DELAY_NORMAL`). |

Sin permisos que no se puedan conceder: todo se degrada con gracia (menos datos, nunca una excepción).

## 4. Persistencia: que el APK no muera

Cuatro capas independientes; la app muestra el estado de cada una y se activan en un toque:

1. **Servicio en primer plano resiliente** — `START_STICKY`, `android:stopWithTask="false"`
   (sobrevive al cierre desde "recientes"), `onTaskRemoved` que rearma el watchdog, notificación
   `ongoing` de importancia baja con `FOREGROUND_SERVICE_IMMEDIATE` (API 31+), wake lock parcial
   renovado cada 10 minutos y heartbeat de 60 s que envía el último fix conocido.
2. **Watchdog (alarma + arranque)** — `WatchdogReceiver` escucha `BOOT_COMPLETED`,
   `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED` y `USER_PRESENT`, y `Watchdog` programa una alarma
   `setAndAllowWhileIdle` (tolerante a Doze, sin permisos especiales) cada 15 minutos. El latido se
   guarda en disco, así que tras un kill del proceso se detecta el vencimiento y se relanza el
   servicio. Botón **Detener** = `tracking_enabled=false`: el watchdog deja de relanzar.
3. **Administrador de dispositivo** (`TelemetryAdminReceiver`, políticas `force-lock` y
   `watch-login`) — evita la desinstalación accidental, permite bloquear la pantalla desde la app y
   hace que detener el rastreo pase por los ajustes del sistema. Se activa con el botón
   **Administrador**.
4. **Centinela de accesibilidad** (`TelemetryAccessibilityService`) — observa únicamente cambios de
   ventana (`typeWindowStateChanged`, `canRetrieveWindowContent="false"`: **no** lee pantalla,
   texto ni datos de otras apps) y como máximo una vez cada 30 s verifica y rearma el watchdog.
   Se activa con el botón **Accesibilidad**.

Además, el botón **Batería** pide la exención de optimizaciones
(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) y los permisos se piden en el orden correcto en Android
11+: ubicación en uso → notificaciones → "Permitir siempre" en una solicitud independiente.

Límites reales (Android no permite garantías absolutas):

- Android 12+ **prohíbe** arrancar servicios en primer plano desde segundo plano. El watchdog lo
  intenta, captura el rechazo y vuelve a intentarlo en la siguiente alarma; mientras tanto el
  sistema reanuda el servicio por `START_STICKY` y el temporizador de ubicación sigue activo.
- Los ROM agresivos (MIUI, EMUI, ColorOS, OneUI) matan procesos extra: hay que habilitar
  manualmente "Inicio automático"/"Autostart" y "Sin restricciones de batería" para la app.
- Usar accesibilidad + ubicación en segundo plano y publicar en Google Play exige justificación
  muy estricta (política de permisos sensibles) y una declaración destacada en Play Console. Para
  uso táctico/interno distribuye el APK firmado o vía MDM.
- Una app *device owner* (no solo *admin*), provisionada por MDM, sí puede fijar el modo kiosco
  (`setLockTaskPackages`); como *admin* normal ese API no está disponible.

## 5. Pruebas end-to-end sin Android

```bash
python backend/app.py &
python tools/simulate.py --url http://127.0.0.1:8000/api/location --devices 3 --interval 2
python tools/simulate.py --devices 1 --interval 2 --noisy   # saltos y fixes malos a propósito
# abre http://127.0.0.1:8000/  (sirve el frontend)
```

El simulador emite el mismo esquema que el cliente Android (GNSS, sensores, Wi-Fi, celdas,
precisiones y `elapsed_nanos`), así que `--noisy` sirve para comprobar que los filtros del
servidor de verdad descartan teleports y fixes de baja calidad.

## 6. Tests, CI y despliegue en contenedor

```bash
python -m unittest discover -s tests -v     # endpoints, validación, Kalman, altitud, huecos
python tools/check_frontend.py              # sintaxis del JS embebido (requiere node)
scripts/dev.sh test                         # las dos cosas + compileall
```

La CI (`.github/workflows/ci.yml`) ejecuta esos tests en Python 3.10 y 3.12 y compila el APK de
depuración, subiéndolo como artefacto. Los tests usan un almacén temporal: **nunca** escriben en
`telemetry_store.jsonl`.

```bash
docker compose up --build -d                # http://localhost:8000/ (API + visualizador)
TELEMETRY_API_KEY=$(openssl rand -hex 24) docker compose up -d
```

La imagen corre como usuario sin privilegios, guarda los datos en el volumen `telemetry-data`
y expone un `HEALTHCHECK` contra `/api/health`. Usa **un solo worker**: el estado vive en memoria.

## 7. Publicar en GitHub

```bash
git init -b main && git add . && git commit -m "feat: telemetría y geolocalización punto a punto"
gh repo create telemetria --public --source=. --remote=origin --push
```

Sin `gh`: crea el repositorio vacío en la web, añade el remoto y empuja.

```bash
git remote add origin https://github.com/TU_USUARIO/telemetria.git
git push -u origin main
```

Después: sustituye `OWNER/REPO` en los badges y en `.github/ISSUE_TEMPLATE/config.yml`, y si vas a
usar GitHub Pages publica la carpeta `frontend/` (Settings → Pages → rama y carpeta `/frontend`),
que solo contiene HTML, un `<script>` y Leaflet por CDN.

## 8. Seguridad, privacidad y límites

- Sin `TELEMETRY_API_KEY` la API es abierta: úsala solo en LAN o tras un proxy.
- Despliega detrás de HTTPS (nginx/Caddy) o el tráfico de ubicación viajará en claro.
- El almacenamiento es un JSONL local: pensado para demo/uso táctico, no para alta concurrencia.
  Para producción real, sustituye `_persist`/`_load` por SQLite/PostGIS manteniendo la misma API.
- Rastrear personas exige consentimiento explícito e informado; el administrador de dispositivo y
  la accesibilidad son permisos sensibles: úsalos solo en dispositivos propios o con autorización.
