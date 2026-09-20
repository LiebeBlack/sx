# Telemetría y geolocalización punto a punto

[![CI](https://github.com/LiebeBlack/sx/actions/workflows/ci.yml/badge.svg)](https://github.com/LiebeBlack/sx/actions/workflows/ci.yml)
![Python](https://img.shields.io/badge/Python-3.10%2B-3776AB?logo=python&logoColor=white)
![Android](https://img.shields.io/badge/Android-API%2030--35-3DDC84?logo=android&logoColor=white)
![Sin dependencias JS](https://img.shields.io/badge/frontend-Vanilla%20JS-f7df1e)
![Licencia](https://img.shields.io/badge/licencia-MIT-blue)

Cliente Android (API 24-35, objetivo Android 11 → 15/16) → API Flask con fusión de sensores →
Visualizador web estático publicable en GitHub Pages. Sin base de datos y sin frameworks de
frontend. En Android el 95 % es SDK puro (`org.json`, `HttpURLConnection`, sensores, GNSS, telefonía)
más dos dependencias opcionales que se comprueban antes de usarlas y de las que el sistema sigue
funcionando si no están (el motor fusionado de Google y WorkManager).

## Tres modos de producción

> 📋 **Guía completa de puesta en marcha de verdad**: [`docs/SETUP.md`](docs/SETUP.md) — token
> fine-grained paso a paso, app, Pages, espejo con docker-compose, checklist y solución de problemas.
>
> ⚙️ **Configurar el móvil sin escribir campo a campo**: [`docs/CONFIGURACION.md`](docs/CONFIGURACION.md)
> — qué significa cada clave, la plantilla lista (`config.ejemplo.json`) que la app carga sola desde
> `Descargas/Telemetria/config.json`, y el código de seguridad de la pantalla. Incluye **§10: impedir la
> desinstalación** —propietario del dispositivo más centinela de accesibilidad, con su salida de
> emergencia, la tabla de **qué pasa con cada acción de mantenimiento** (reiniciar, apagar, modo avión,
> parada forzosa, borrar datos, cambiar la hora), la **cadena de custodia** que queda en `error.json` y
> lo que esa protección *no* hace—.

| | Modo API en vivo | Modo GitHub directo | Modo Gist (BaaS) |
|---|---|---|---|
| Latencia | segundos | ~1 min (rate limit de la Contents API) | ~20 s |
| Traza filtrada (Kalman) | sí | sí: filtra el propio móvil | sí: filtra el propio móvil |
| Requiere servidor | sí (VPS/LAN/Docker) | **no** | **no** |
| Requiere repositorio | no | sí (público o Pages) | **no**: basta un gist |
| Formato publicado | filas compactas + historial | `latest.json` con filas compactas | array `location`/`telemetry`/`status` |
| Consumo GitHub | 0 | ~1 request/min por dispositivo | 2 requests por ciclo (GET + PATCH) |
| Ideal para | seguimiento en tiempo real | demo táctica con repo propio | histórico acumulativo sin infraestructura |

En los tres casos el visualizador es el mismo: elige la fuente en el desplegable
**API en vivo / GitHub Pages (fichero) / Gist (histórico)**. Sin salir de la página la traza visible se
descarga en **CSV** o en **GPX** (para mapas, relojes y análisis), el panel **distancia por día**
resume la última semana —lo de hoy primero, con `hoy` también en la barra del pie y en la tarjeta de
cada dispositivo—, el botón **Días** se lleva esa tabla completa en CSV y una vista concreta se
comparte por URL —`…/frontend/?feed=gist&gist=<id>`, `?view=radar`, `?interval=5000`, `?follow=<id>`—. Los tokens
**no** se aceptan por URL a propósito: una credencial en un enlace compartido o en el historial del
navegador es una credencial filtrada.

### Modo Gist (GitHub como base de datos)

El Gist es la base de datos: Android escribe el histórico y la web lo lee del `raw_url`, sin
servidor, sin repositorio y sin desplegar nada. El detalle está en
[§6](#6-modo-gist-github-como-base-de-datos).

**Aviso de seguridad obligatorio:** un PAT es una credencial. Se configura **en la app** (se guarda
en `SharedPreferences`, nunca en el código) y si alguna vez se pega en un chat, un issue o un commit,
hay que **revocarlo y crear otro**: cualquiera que lo lea puede escribir en tus gists mientras siga
vivo. El token que necesitas para este modo solo requiere el permiso `Gists: read and write` (o
`Contents` si compartes token con el modo repositorio).

### Modo GitHub directo (sin servidor)

1. Crea un **fine-grained token** con permiso `Contents: read and write` solo en el repo de destino
   (Settings → Developer settings → Fine-grained tokens).
2. En la app, rellena la sección **GitHub directo**: token, `usuario/repositorio`, rama y ruta
   (`data/latest.json` por defecto). Guarda e inicia el rastreo.
3. La app escribe el fichero vía Contents API: fusión por `device_id`, reintento automático ante
   409 (sha obsoleto) y publicación como mucho cada 60 s. Activa **Settings → Pages → Deploy from
   branch** en el repo: rama `main` y carpeta **`/ (root)`** — es la única que publica el
   visualizador y `data/latest.json`; con `/docs` se publica solo la documentación (su portada ya
   existe en `docs/index.html` y los documentos se leen en `docs/doc.html`, que pinta el Markdown
   servido por Pages) y el mapa se ve desde el backend (ver `docs/SETUP.md`, Paso 4).
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
frontend/index.html                     Visualizador Vanilla JS + Leaflet (OLED, 3 fuentes, 2 trazas, radar sin CDN, CSV/GPX, distancia por día, URL compartible)
frontend/gist.html                      Visor del canal Gist (autónomo, sin librerías, histórico, distancia y día, diagnóstico del enlace; enlazado desde el visor principal)
index.html                              Portada del sitio de Pages: redirige al visualizador en vivo
tools/simulate.py                       Generador de trafico sintetico (--noisy)
tools/check_frontend.py                 Valida el JS embebido de los dos visores con node --check
tools/check_config.py                   Contrasta el importador, la plantilla y la guía de configuración
tools/gist_schema.py                    Contrato del canal Gist: validador y fusión de referencia
tests/test_api.py · tests/test_fusion.py  Tests de endpoints, filtros y fusion
tests/test_gist_schema.py               Tests del contrato y de las reglas de fusión del Gist
tests/test_workflow.py                  Sintaxis de los workflows (un YAML inválido no ejecuta nada)
tests/test_frontend_adapter.py          Contrato del Gist dentro del visor, ejecutando sus funciones con node
 docs/API.md                            Referencia de endpoints con ejemplos curl
 docs/SETUP.md                          Puesta en producción completa (token, Pages, espejo)
 docs/CONFIGURACION.md                  Configuración del móvil campo por campo + plantilla JSON
 docs/doc.html                          Lector de la documentación: pinta esos .md publicados (un fichero, sin librerías)
config.ejemplo.json                    Plantilla lista para rellenar y copiar al teléfono (todas las claves que lee el importador)
 android/.../AppConfig.java             Dueño único de la configuración: exportar, importar y aplicar
 android/.../SecurityGate.java          Código de seguridad de la pantalla (SHA-256 + sal, 5 intentos)
 android/.../UninstallGuard.java         Protección contra desinstalación (propietario + centinela)
 docs/index.html                        Portada del sitio: las funciones y la configuración completa (sirve para /docs y /)
scripts/dev.sh                          install | run | test | simulate | apk | lint
Dockerfile · docker-compose.yml         Despliegue en contenedor con volumen /data
.github/workflows/ci.yml                CI: tests del backend + build del APK

android/settings.gradle · build.gradle · gradle.properties    Proyecto Gradle (AGP 8.7.3 / Gradle 8.9)
android/app/build.gradle · proguard-rules.pro                Módulo: compileSdk/targetSdk 35, minSdk 24, firma opcional
android/app/src/main/AndroidManifest.xml
android/app/src/main/res/drawable/ic_launcher.xml             Icono vectorial
android/app/src/main/res/values/strings.xml
android/app/src/main/res/xml/device_admin.xml
android/app/src/main/res/xml/accessibility_service_config.xml
android/app/src/main/java/com/example/telemetry/
    TelemetryApp.java                  Application: instala el manejador global de errores al arrancar
    ErrorLogger.java                   error.json en Descargas/Telemetria + diagnóstico del sistema
    MainActivity.java                  Configuración, permisos y estado de persistencia
    LocationTrackerService.java        Servicio en primer plano: GPS/red/pasivo/fused + Wi-Fi + celdas
    GnssMonitor.java                   Satélites visibles/en uso, SNR y constelaciones
    FusedLocationBridge.java           Puente al motor fusionado de Google (alta precisión en Android 11)
    WifiRttRanger.java                 Wi-Fi RTT 802.11mc: distancia real al AP en interiores
    SensorFusion.java                  Barómetro, podómetro, brújula compensada por gravedad y actividad
    TrackFilter.java                   Kalman en el propio móvil: smooth_lat/smooth_lng sin servidor
    TelemetryClient.java               POST JSON nativo (HttpURLConnection, cola, backoff)
    TelemetryStore.java                Fuente de verdad local en SQLite (clave: timestamp_ms)
    GistPublisher.java                 Canal Gist: lee, fusiona y escribe el histórico (BaaS)
    TelemetryWorker.java               WorkManager: revive el rastreo y entrega la cola sin servicio
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
| `TELEMETRY_MAX_JUMP_M` | `150` | un salto mayor que esto a velocidad irreal es `outlier` (`jump`) |
| `TELEMETRY_PLAUSIBLE_SPEED` | `60` | m/s a partir de los cuales ese salto es imposible |
| `TELEMETRY_MAX_SPEED_MPS` | `90` | velocidad implícita imposible: `outlier` directo (`speed`) |
| `TELEMETRY_MAX_ACCURACY_M` | `150` | fixes peores que esto (`low_quality`) no suman distancia |
| `TELEMETRY_MAX_GAP_S` | `120` | hueco mayor: reinicia el filtro y no cuenta el tramo |
| `HOST` / `PORT` | `0.0.0.0` / `8000` | bind del servidor de desarrollo |

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/api/location` | un punto `{lat,lng,timestamp,…}` o lote `{"points":[ … ]}` (máx 500) |
| `GET` | `/api/locations` | todos los dispositivos; `?format=compact&limit=300&since=<ms>&latest=1` |
| `GET` | `/api/locations/<device_id>` | historial de un dispositivo |
| `DELETE` | `/api/locations/<device_id>` | borra el dispositivo (requiere key) |
| `GET` | `/api/health` | estado, dispositivos, puntos, ruta de almacenamiento y umbrales activos en `filter` |

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

Diseño **OLED puro**: fondo `#000000` real (los píxeles se apagan, no hay gris de fondo) con
acentos neón cian `#00e5ff` y púrpura `#b14cff`, y teselas oscuras de CARTO para que el mapa no
rompa el contraste ni la batería. Cada dispositivo recibe un color estable de esa paleta neón.

Sobre el mapa se dibujan **siempre las dos trazas del mismo recorrido**, con leyenda en la cabecera:

- **bruta** (gris discontinuo): los puntos GPS reales tal cual llegan, ruido incluido.
- **filtrada** (neón sólido): la posición Kalman calculada por el servidor.

La casilla **bruta** oculta o muestra la primera; la filtrada no se puede ocultar porque es la
lectura útil. En el radar canvas se dibujan igual. Si el origen de datos no publica traza filtrada
(app publicando en directo a GitHub, por ejemplo), la tarjeta del dispositivo lo avisa con
`SIN FILTRO` en lugar de inventarse una traza que no existe.

## 3. Cliente Android

`minSdkVersion 24`, `compileSdk 35` y `targetSdk 35` (Android 15), con la API 24 como suelo real y
Android 11 → 16 como rango objetivo:

```gradle
android {
    namespace "com.example.telemetry"
    compileSdk 35
    defaultConfig { minSdk 24; targetSdk 35 }
    compileOptions { sourceCompatibility JavaVersion.VERSION_1_8; targetCompatibility JavaVersion.VERSION_1_8 }
}
dependencies {
    implementation 'com.google.android.gms:play-services-location:21.3.0'   // motor fusionado (opcional en runtime)
    implementation 'androidx.work:work-runtime:2.9.1'                       // segunda vía de persistencia
}
```

Build del APK (requiere JDK 17 y Android SDK 35, con `ANDROID_HOME` o `local.properties`):

```bash
cd android
gradle wrapper --gradle-version 8.9      # una sola vez: crea gradlew + gradle-wrapper.jar
./gradlew assembleDebug                  # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease                # -> app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

El release va ofuscado en modo extremo (`minifyEnabled` + `shrinkResources`): repaquetado en un único
paquete, sobrecarga de nombres y firmas únicas, con `proguard-rules.pro` conservando solo lo que el
sistema instancia por nombre —activity, servicios, receivers, sus callbacks de ciclo de vida y
`TelemetryWorker`, que WorkManager instancia **por nombre de clase leído de su base de datos**— y
borrando de paso todas las llamadas a `Log`. La CI compila el release y comprueba con `grep` sobre
el dex que esos nombres sobrevivieron a R8. Firma: si existe `android/keystore.properties`
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
| Alta precisión (framework) | `LocationRequest` con `QUALITY_HIGH_ACCURACY` + `setWaitForAccurateLocation(true)`, y fix inmediato a la carta (20 s de margen, caché de 30 s) | 31+ |
| **Motor fusionado de Google** (`FusedLocationBridge`) | el mismo `LocationRequest` de alta precisión sobre `FusedLocationProviderClient`, más `getLastLocation` (caché compartida entre apps): es lo que da la precisión de Android 12 a **Android 11 y anteriores**, y la fuente `fused/gms`. Se comprueba `GoogleApiAvailability` antes de usarlo; sin servicios de Google se sigue con el motor del sistema | 24+ (con GMS) |
| Modo recursos bajos | `ActivityManager.isLowRamDevice()` (Android Go / 1 GB): enriquecimiento cada 60 s, 6 AP y 6 satélites en vez de 16, sin Wi-Fi RTT. Mismo dato útil con una fracción del trabajo | 24+ |
| Último punto conocido de cada proveedor | primer dato al arrancar sin esperar señal | 24+ |
| `getCurrentLocation` (fix inmediato) | al arrancar y cuando el último fix supera 2 min | 30+ |
| GNSS (`GnssStatus`) | satélites visibles/en uso, SNR medio y mejor, constelaciones (GPS/GLONASS/Galileo/BeiDou/QZSS/IRNSS/SBAS) y **detalle por satélite** (`sys`, `svid`, `cn0`, `used`, `elev`, `azim`, hasta 16) para auditar por qué un fix salió con error | 24+ |
| Wi-Fi RTT (802.11mc) | distancia **real** al punto de acceso en decímetros, con su desviación típica (`rtt`), cuando el fix no es preciso y el AP responde a RTT | 28+ |
| Barómetro | `altitude_baro` + `pressure_hpa`, más estable que la altitud GPS | sensor |
| Altitud MSL | `altitude_msl` y su precisión: altitud sobre el nivel del mar (geoidal, API 34) en vez de la elipsoidal, que es la misma referencia que usa el barómetro | 34+ |
| Podómetro | `steps` para validar movimiento real | sensor (+`ACTIVITY_RECOGNITION` 29+) |
| Brújula | `heading_magnetic` con la inclinación compensada por la gravedad (rumbo correcto con el móvil en la mano, no solo plano) y `heading_true` corregido con la declinación magnética del modelo geomagnético (`declination_deg`): norte verdadero, no magnético | sensor (+`TYPE_GRAVITY`) |
| Acelerómetro | `activity` (still/walking/running/vehicle) y `movement` | sensor |
| Wi-Fi | AP conectado vía `ConnectivityManager` (10+) con caída a `WifiManager` + hasta 16 AP vecinos | 24+ / `NEARBY_WIFI_DEVICES` 33+ |
| Celdas | LTE/GSM/WCDMA y variantes tipo NR: MCC/MNC/CID/TAC/PCI/RSRP/RSRQ/dBm | 24+ |
| Red y estado | `network`, `data_network` (2G/3G/4G/5G), operador, `sdk`, `model`, batería, `mock` | 24+ |
| Vigilante de red | `registerDefaultNetworkCallback`: al aparecer cualquier conexión (Wi-Fi, datos, ethernet) se vacía la cola y se publica al instante en lugar de esperar al latido | 24+ |

Cada punto lleva además `elapsed_nanos` (reloj monotónico, para calcular `dt` exacto),
`vertical_accuracy`, `speed_accuracy`, `bearing_accuracy` (API 26+), `accuracy` y `complete`
(API 30: si el fix trae todos los campos). Dos proveedores que entregan el mismo instante con
precisiones distintas se resuelven en el dispositivo quedándose con el mejor: el peor se descarta
antes de salir, así que nunca hay dos puntos contradictorios para el mismo momento. Wi-Fi, celdas,
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
4. **Filtros de integridad** — dos reglas marcan `outlier` sin contaminar el filtro ni las
   estadísticas, y cada punto dice por qué (`outlier_reason`):
   - `speed`: velocidad implícita > 90 m/s (324 km/h), imposible para un vehículo terrestre.
   - `jump`: más de **150 m** recorridos a más de 60 m/s (216 km/h). Es el teleport urbano clásico
     (p. ej. 200 m en 3 s = 240 km/h), que pasa la regla de velocidad porque su media es menor.

   Las dos exigen velocidad irreal a propósito: un trayecto legítimo de 300 m en 12 s (90 km/h)
   **no** se descarta solo por superar los 150 m. Los fixes peores de 150 m de precisión se marcan
   `low_quality` y no suman distancia.
5. **Resumen honesto** — `distance_m` (filtrada), `raw_distance_m` (bruta), `noise_removed_m`
   (ruido eliminado), `avg_accuracy_m`, `outliers`, `low_quality`, `activity` y `satellites`.

Cada punto incluye `smooth_lat`/`smooth_lng` (posición filtrada) y el endpoint devuelve además un
array `smooth` (`[ts, lat, lng, speed]`) para dibujar la traza limpia. Las filas compactas llevan
esa posición como columnas 9 y 10 —después de las 8 históricas, así que ningún lector antiguo se
rompe— y el visualizador la reconstruye desde ahí cuando el origen es un fichero de GitHub.

El móvil filtra también: `TrackFilter.java` corre el **mismo modelo de Kalman** en el dispositivo, de
modo que la traza publicada directamente a GitHub ya viene suavizada y con la velocidad estimada
cuando el proveedor no la informa. Los dos modos de producción publican exactamente el mismo formato
de fila, así que el visualizador dibuja las dos trazas igual en ambos.

Red: `HttpURLConnection`, timeouts de 10 s, lotes de 40, cola persistida (máx 1000), 5 reintentos
con backoff 0,8 s→30 s, descarte de 4xx irrecuperables y reintento cada 15 s. Todo el I/O corre en
un `ExecutorService` de un hilo: el hilo principal nunca se bloquea. Cliente y publicador son
singletons de proceso y su ejecutor se recrea bajo demanda, para que la cola siga viva cuando el
sistema mata el servicio y el watchdog lo relanza en el mismo proceso.

### Compatibilidad Android 11 → 15 (API 30–35)

| Versión | Qué cambia y cómo se cubre |
|---|---|
| Android 11 (30) | `ACCESS_BACKGROUND_LOCATION` se pide en una solicitud **independiente**; `getCurrentLocation` como fix inmediato; `ConnectivityManager.getTransportInfo()` para el Wi-Fi real; el motor fusionado de Google cubre la alta precisión que el framework no da hasta Android 12. |
| Android 12 (31) | `PendingIntent` siempre con `FLAG_IMMUTABLE`; `location.isMock()`; `setForegroundServiceBehavior(IMMEDIATE)`; arranques de FGS desde segundo plano capturados (el watchdog reintenta, WorkManager reintenta por su cuenta y el sistema reanuda por `START_STICKY`). |
| Android 13 (33) | `POST_NOTIFICATIONS` en runtime; **`NEARBY_WIFI_DEVICES`** obligatorio para `ScanResult`/`WifiInfo` (declarado sin `neverForLocation`, porque sí usamos la señal para ubicar). |
| Android 14 (34) | `FOREGROUND_SERVICE_LOCATION` declarado y comprobado antes de `startForeground`; servicio con `stopWithTask=false`; si falta el permiso de ubicación el fallo se trata como **determinista** (no se autoriza al watchdog a relanzar), mientras que un bloqueo del fabricante sí se reintenta. |
| Android 15 (35) | `targetSdk 35` obliga a dibujar a pantalla completa: resuelto con insets de las barras reales (`systemBars()` + recorte de pantalla). Los tipos de servicio en primer plano prohibidos desde `BOOT_COMPLETED` son `dataSync`, `camera`, `mediaPlayback`, `phoneCall`, `mediaProjection` y `microphone` — **`location` no lo está**, así que el arranque tras reiniciar sigue permitido; y la entrega de cola se hace con WorkManager, sin servicio, precisamente para no depender de esa lista. |
| Android 16 | Nada de lo usado cambia de contrato: lo nuevo se aplica siempre con guarda de versión y lo que no existe cae a la alternativa del SDK. Para compilar contra API 36 basta subir AGP a 8.9.1+ y Gradle a 8.11.1+. |
| Android Go Edition | `isLowRamDevice()` activa el modo recursos bajos de la tabla anterior: mismos permisos, mismos servicios y menos trabajo por punto, que es lo que evita que el proceso muera en un equipo de 1 GB. |

Sin permisos que no se puedan conceder: todo se degrada con gracia (menos datos, nunca una excepción).
`READ_PHONE_STATE` está declarado pero **no se pide al arrancar**: el código no necesita ese permiso
peligroso para el enriquecimiento actual (las celdas viven del permiso de ubicación precisa), así que
se solicita solo con el botón **Telefonía**, pensado para despliegues gestionados por MDM que ya lo
preotorgan. Al concederlo, el servicio relee Wi-Fi, celdas y telefonía en el punto siguiente.

## 4. Persistencia: que el APK no muera

Cinco capas independientes; la app muestra el estado de cada una y se activan en un toque. La app
reanuda el rastreo al abrirse (en primer plano el sistema no puede bloquear el arranque), que es el
camino de rescate cuando todo lo automático ha sido rechazado:

1. **Servicio en primer plano resiliente** — `START_STICKY`, `android:stopWithTask="false"`
   (sobrevive al cierre desde "recientes"), `onTaskRemoved` que rearma el watchdog, notificación
   `ongoing` de importancia baja con `FOREGROUND_SERVICE_IMMEDIATE` (API 31+), wake lock parcial
   renovado cada 10 minutos y heartbeat de 60 s que envía el último fix conocido.
2. **Watchdog (alarma + arranque)** — `WatchdogReceiver` escucha `BOOT_COMPLETED`,
   `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED` y `USER_PRESENT`, y `Watchdog` programa una alarma
   `setAndAllowWhileIdle` (tolerante a Doze, sin permisos especiales) cada 15 minutos. El latido se
   guarda en disco, así que tras un kill del proceso se detecta el vencimiento y se relanza el
   servicio. Si varios relanzados seguidos son rechazados, la alarma se espacia hasta una hora en
   lugar de insistir en vano, y el primer latido la devuelve a 15 minutos. Botón **Detener** =
   `tracking_enabled=false`: el watchdog deja de relanzar **y retira la alarma**, para no despertar
   la CPU cada 15 minutos sin rastrear nada.
3. **WorkManager (segunda vía, con reglas distintas)** — `TelemetryWorker` corre cada 15 minutos y
   hace dos cosas: comprobar el latido y relanzar el servicio si hace falta, y **vaciar la cola
   persistida por HTTP sin necesidad de servicio en primer plano**. Eso último es decisivo en
   Android 12+, donde el sistema puede negarse a arrancar el servicio: WorkManager sí ejecuta, así
   que los puntos acumulados llegan igualmente. Tras reiniciar hay además un reintento a un minuto,
   y al detener el rastreo queda un único intento de entrega con la condición de que haya red.
4. **Administrador de dispositivo** (`TelemetryAdminReceiver`, políticas `force-lock` y
   `watch-login`) — evita la desinstalación accidental, permite bloquear la pantalla desde la app y
   hace que detener el rastreo pase por los ajustes del sistema. Se activa con el botón
   **Administrador**.
5. **Centinela de accesibilidad** (`TelemetryAccessibilityService`) — observa únicamente cambios de
   ventana (`typeWindowStateChanged`, `canRetrieveWindowContent="false"`: **no** lee pantalla,
   texto ni datos de otras apps) y como máximo una vez cada 30 s verifica y rearma el watchdog.
   Se activa con el botón **Accesibilidad**.

Además, el botón **Batería** pide la exención de optimizaciones
(`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) y los permisos se piden en el orden correcto en Android
11+: ubicación en uso → notificaciones → "Permitir siempre" en una solicitud independiente. El botón
**Telefonía** pide `READ_PHONE_STATE`, un permiso peligroso que el código actual **no** necesita: no
se solicita al arrancar, solo si el usuario lo pulsa, y la entrega de la cola por WorkManager se hace
sin servicio en primer plano, así que tampoco depende de ningún permiso de ubicación en segundo plano.

Límites reales (Android no permite garantías absolutas):

- Android 12+ **prohíbe** arrancar servicios en primer plano desde segundo plano. El watchdog lo
  intenta, captura el rechazo y vuelve a intentarlo en la siguiente alarma; mientras tanto el
  sistema reanuda el servicio por `START_STICKY`, WorkManager entrega la cola por su cuenta y el
  temporizador de ubicación sigue activo. El rescate definitivo es abrir la app: en primer plano no
  hay restricción, y `MainActivity` reanuda el rastreo sola si estaba activado.
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
python tools/simulate.py --devices 1 --interval 2 --noisy      # saltos y fixes malos a propósito
python tools/simulate.py --devices 1 --interval 3 --teleport   # 150-250 m por vuelta: prueba la regla de salto
# abre http://127.0.0.1:8000/  (sirve el frontend)
```

El simulador emite el mismo esquema que el cliente Android (GNSS, sensores, Wi-Fi, celdas,
precisiones y `elapsed_nanos`), así que `--noisy` sirve para comprobar que los filtros del
servidor de verdad descartan teleports y fixes de baja calidad.

## 6. Modo Gist (GitHub como base de datos)

Un Gist es un fichero con control de versiones y una URL cruda que no necesita autenticación para
leerse: eso lo convierte en una base de datos pública de verdad, sin servidor. El canal se activa en
la app con **token + ID del gist** (el ID acepta también la URL completa) y un nombre de fichero
(`data.json` por defecto).

**Leer antes de escribir, o no se escribe.** Un `PATCH` al Gist reemplaza el contenido del fichero,
así que publicar a ciegas sería el fallo que borra el historial de todos los demás dispositivos. Cada
ciclo hace `GET` del Gist, fusiona lo remoto con lo local por `timestamp_ms` y solo entonces escribe.
Si el `GET` falla (sin red, 5xx, rate limit), ese ciclo **no toca el Gist**: la historia sigue a salvo
y el ciclo siguiente reintenta con todo lo pendiente. Si el fichero ya estaba corrupto o vacío, el
envío masivo lo reescribe y lo sana, y eso es seguro porque la fuente de verdad es local.

**Fuente de verdad local (`TelemetryStore.java`).** Cada punto se guarda en SQLite del propio SDK
—sin Room, sin anotaciones y sin procesador de anotaciones en el build— con `timestamp_ms` como
clave primaria: dos proveedores que entreguen el mismo instante no pueden duplicar una fila, y esa
es exactamente la misma clave que usa el visor para deduplicar. El histórico local guarda los últimos
5 000 puntos (unos cientos de KB) y de ahí sale el envío masivo de los últimos 500, acotado también
por caracteres para no acercarse al límite de 1 MB por fichero.

**Formato publicado**: un array de objetos, del más antiguo al más nuevo, con los tres bloques del
esquema:

```json
[
  {
    "location": { "latitude": 10.9578123, "longitude": -63.8696456, "altitude": 15.2,
                  "altitude_source": "msl", "accuracy_meters": 3.5, "speed_kmh": 4.5,
                  "bearing_degrees": 180.0, "provider": "fused/gms", "mock": false,
                  "smooth_latitude": 10.9578120, "smooth_longitude": -63.8696450,
                  "satellites_used": 9, "satellites_visible": 18,
                  "snr_best": 38.4, "constellations": "GPS,GLONASS,GALILEO,BEIDOU",
                  "cells": 3, "wifi_aps": 6 },
    "telemetry": { "timestamp_ms": 1726799620000, "battery_level": 85, "is_charging": false,
                   "network_type": "wifi", "data_network": "5G", "network_label": "WIFI_5G",
                   "operator": "MOVISTAR", "activity": "walking", "steps": 8421,
                   "device_id": "android-8f1c", "label": "Pixel 7", "model": "Pixel 7",
                   "sdk": 35, "low_ram": false, "source": "android" },
    "status": { "is_tracking": true, "fallback_active": false, "last_error": null,
                "local_points": 128, "remote_points": 96, "gist_configured": true }
  }
]
```

Los tres bloques y sus claves están siempre presentes, así que el visor nunca tiene que defenderse de
un campo ausente; las magnitudes que el sensor puede no haber entregado van con el centinela `-1` (el
mismo que usan las filas compactas). `status.fallback_active` se activa cuando el punto no viene del
motor principal (GPS o fusionado), y `status.last_error` lleva el diagnóstico del propio canal: el
payload se explica a sí mismo.

**El visor `frontend/gist.html`** es autónomo: un solo fichero, sin librerías externas, fondo negro
puro y solo dos colores (cian `#00FFFF` y púrpura `#800080`). Sondea cada 3 s con cache-busting
(`?t=`), y si GitHub entrega el fichero a medio escribir el `JSON.parse` falla dentro de un
`try/catch`, **se conserva en pantalla el último estado válido** y se reintenta en el ciclo siguiente
sin borrar el historial ni romper la interfaz. La deduplicación usa un `Map` con `timestamp_ms` como
clave: los puntos ya vistos se actualizan en su sitio y los nuevos se insertan sin repintar lo
existente (cero parpadeos). Con la pestaña oculta deja de sondear, así que no se gastan lecturas
mientras nadie mira.

**Ciclo de publicación:** como mucho cada 20 s desde el servicio (180 escrituras/hora, dos peticiones
equivalentes cada una), más un intento inmediato en cuanto aparece cualquier red y otro desde
WorkManager cuando el sistema bloquea el arranque del servicio en primer plano. Todos los caminos
leen y escriben lo mismo, así que reiniciar la app tras un día sin cobertura publica el histórico
completo de golpe.

**El contrato está escrito y se puede comprobar sin el móvil.** `tools/gist_schema.py` es la
implementación de referencia del formato —validación de bloques, tipos y centinelas, fusión por
`timestamp_ms` y doble recorte— y `tests/test_gist_schema.py` la cubre en CI, así que las reglas que
en el dispositivo ejecuta Java aquí se prueban de verdad en cada push:

```bash
python tools/gist_schema.py data.json                 # valida un fichero
python tools/gist_schema.py https://gist.githubusercontent.com/USUARIO/ID/raw/data.json
python tools/gist_schema.py --merge local.json remoto.json   # simula la fusión y el recorte
```

Devuelve `VÁLIDO` o `NO CUMPLE EL CONTRATO` con el detalle por punto, y sale con código distinto de
cero si algo falla, así que sirve tanto para diagnosticar un gist real como para una verificación
en una tubería.

---

## 7. Registro de errores (`error.json`)

Todo fallo queda escrito en un fichero JSON dentro de la carpeta pública de descargas, en una carpeta
con el nombre de la aplicación: **`Descargas/Telemetria/error.json`**. No hay que activar nada ni
recordar una ruta: si algo se rompe, el fichero está ahí, y el panel de la app muestra la ruta exacta
y el recuento en todo momento.

**Qué se registra.** Las excepciones no capturadas de **cualquier** hilo (el manejador global se
instala en `TelemetryApp`, la clase `Application` del proceso, antes de que exista ningún otro
componente) y también los fallos que el código ya capturaba y hasta ahora solo iban a `logcat`: no
poder iniciar el servicio en primer plano, escrituras rechazadas por el canal Gist (401/403/404/422,
es decir, lo que el usuario debe arreglar), fallos del trabajo de WorkManager, fallos del receptor de
arranque, lotes que no se pueden serializar y rechazos del ejecutor de red —que no deberían ocurrir
nunca y por eso importan—.

**Dónde va, exactamente.** En Android 10+ se escribe con `MediaStore` en Descargas (contribuir ahí no
necesita permiso alguno). En Android 9 y anteriores la carpeta pública sí exige
`WRITE_EXTERNAL_STORAGE`, que se declara acotado con `maxSdkVersion="28"` y se pide **solo en esas
versiones**: primero junto a los permisos de ubicación, para que el registro caiga en Descargas desde
el primer fallo, y también con el botón Diagnóstico si en su momento se denegó. Si la carpeta pública
no está disponible (permiso denegado, almacenamiento no montado, política del fabricante), el fichero
se guarda en `Android/data/com.example.telemetry/files/Telemetria/` y el panel lo dice: el registro
**nunca se pierde, solo cambia de sitio**.

**Formato.** Un array de incidentes, el más nuevo primero, con el mismo criterio que el resto del
proyecto: el documento se lee entero antes de reescribirlo, así que nada se destruye y un fichero
corrupto se regenera solo. Y si reinstalas la app, la copia privada desaparece pero **la pública
sobrevive**: el registro continúa a partir del historial que ya había en Descargas en lugar de
empezar de cero.

```json
[
  {
    "when_ms": 1758100012000,
    "when_iso": "2026-09-19T18:26:52.000Z",
    "severity": "fatal",
    "source": "uncaught/main",
    "thread": "main",
    "exception": "java.lang.NullPointerException",
    "message": "Attempt to invoke virtual method … on a null object reference",
    "stack": ["com.example.telemetry.X.y(X.java:42)", "… 3 marcos más"],
    "causes": [{ "exception": "java.io.IOException", "message": "disco lleno" }],
    "repeat_count": 1,
    "app": { "package": "com.example.telemetry", "version": "1.7.0", "version_code": 7 },
    "device": { "model": "Pixel 7", "android": "15", "sdk": 35, "low_ram": false, "uptime_ms": 912345 },
    "state": { "tracking_enabled": true, "service_running": true, "queue": 3, "last_http": 200,
               "gist_status": "ok · 412/500 puntos · remoto 380", "worker_last_gist": "…gist ok" },
    "paths": { "public_folder": "Download/Telemetria", "private_file": "/data/user/0/…/error.json" }
  }
]
```

Cada incidente lleva la huella del dispositivo, la versión de la app y una **instantánea del estado
del sistema en ese instante** (rastreo activo, cola pendiente, último código HTTP, estado del canal
Gist, reintentos del watchdog). Es lo que convierte el fichero en algo accionable en lugar de un
volcado de texto. **Los tokens nunca se escriben**: el diagnóstico explica el sistema sin exponer
credenciales.

**Agrupación y límites.** Un fallo que se repite cada segundo no llena el fichero: si el mismo origen,
excepción y mensaje vuelven en menos de 60 s, se incrementa `repeat_count` del último incidente en
lugar de añadir otro. Se conservan los 200 incidentes más recientes (y como tope 500 000 caracteres),
descartando siempre por el extremo más antiguo.

**El sistema sigue siendo el sistema.** El manejador guarda el anterior y **delega en él** después de
escribir: el proceso muere y Android muestra su diálogo exactamente igual que antes; lo único nuevo es
que el motivo queda documentado en disco.

**Dos botones en la app.** **Diagnóstico** escribe `diagnostico.json` junto al registro (estado
completo del sistema, útil cuando todavía no hay ningún incidente) y **Limpiar log** vacía el registro
—acción explícita del usuario—. Si el permiso de almacenamiento se denegó en Android 9 o anterior, el
botón Diagnóstico lo vuelve a pedir y escribe el fichero en cuanto se concede. Además, si la interfaz completa no se pudiera construir por un fallo
al arrancar, la app abre en **modo de emergencia**: muestra el detalle del error, deja iniciar y
detener el rastreo con la configuración ya guardada y escribe el diagnóstico, en vez de quedarse muda.

**Cómo leerlo** desde un ordenador:

```bash
adb pull /sdcard/Download/Telemetria/error.json
```

---

## 8. Tests, CI y despliegue en contenedor

```bash
python -m unittest discover -s tests -v     # endpoints, Kalman, huecos y contrato del Gist
python tools/check_frontend.py              # sintaxis del JS de los dos visores (requiere node)
python tools/gist_schema.py data.json       # valida un documento del canal Gist
scripts/dev.sh test                         # las dos cosas + compileall
```

La CI (`.github/workflows/ci.yml`) ejecuta esos tests en Python 3.10 y 3.12 y compila el APK de
depuración **y de release** (para validar las reglas de ofuscación y comprobar con `grep` sobre el dex
que los componentes que el sistema instancia por nombre sobrevivieron a R8), subiendo ambos como
artefacto. Los tests usan un almacén temporal: **nunca** escriben en `telemetry_store.jsonl`.

Antes de los tests hay un paso que comprueba que **`node` existe**: la prueba del visor se salta sin
él, y ese salto silencioso convertiría una regresión del adaptador en un verde. El runner está fijado
en `ubuntu-24.04` —GitHub avisa de que `ubuntu-latest` migra a Ubuntu 26 en octubre de 2026—, cada job
tiene tiempo máximo, un push nuevo **cancela** el run anterior y el pip de Flask se cachea. Las cinco
acciones van en su versión mayor más reciente, verificada contra el `action.yml` de cada una antes de
subirla: una acción que corre sobre un Node retirado **avisa en vez de fallar**, y un aviso que se
repite acaba ignorándose.

Las reglas del canal Gist se prueban aquí porque en el móvil no se pueden ejecutar desde CI: fusión
por `timestamp_ms`, empate a favor del punto local, orden cronológico y doble recorte (número de
puntos y caracteres). Un cambio de contrato que rompa el formato falla en `tests/test_gist_schema.py`
antes de llegar a un dispositivo.

```bash
docker compose up --build -d                # http://localhost:8000/ (API + visualizador)
TELEMETRY_API_KEY=$(openssl rand -hex 24) docker compose up -d
```

La imagen es **Alpine multi-stage** (~60 MB frente a ~450 MB de una base Debian): la primera etapa
compila las ruedas por si alguna no publica wheel musllinux y la segunda se queda solo con ellas,
con `init: true` (el tini del propio Docker), `no-new-privileges`, `/tmp` en RAM y un usuario sin
privilegios (uid 10001).
Guarda los datos en el volumen `telemetry-data` y expone un `HEALTHCHECK` contra `/api/health`.
Usa **un solo worker**: el estado vive en memoria. Al cerrar, el `stop_grace_period` da margen a
que el backend compacte el JSONL antes de morir.

## 9. Publicar en GitHub

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

## 10. Seguridad, privacidad y límites

- Sin `TELEMETRY_API_KEY` la API es abierta: úsala solo en LAN o tras un proxy.
- Despliega detrás de HTTPS (nginx/Caddy) o el tráfico de ubicación viajará en claro.
- El almacenamiento es un JSONL local: pensado para demo/uso táctico, no para alta concurrencia.
  Para producción real, sustituye `_persist`/`_load` por SQLite/PostGIS manteniendo la misma API.
- Rastrear personas exige consentimiento explícito e informado; el administrador de dispositivo y
  la accesibilidad son permisos sensibles: úsalos solo en dispositivos propios o con autorización.
