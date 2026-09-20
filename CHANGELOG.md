# Historial de cambios

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y
[versionado semántico](https://semver.org/lang/es/).

## [1.7.0] - 2026-09-19

### Añadido

- **Registro automático de errores en `Descargas/Telemetria/error.json`** (`ErrorLogger.java`): un
  array de incidentes, el más nuevo primero, escrito a la vez en la carpeta pública de descargas y en
  una copia privada que es la autoridad del documento. En Android 10+ se usa `MediaStore` (contribuir
  a Descargas no necesita permisos) y busca el fichero existente para reescribirlo en lugar de
  duplicarlo; en Android 9 y anteriores se usa la ruta clásica con `WRITE_EXTERNAL_STORAGE`,
  declarado con `maxSdkVersion="28"` y pedido **solo en esas versiones** (junto a los permisos de
  ubicación, para que el registro llegue a Descargas desde el primer fallo, y de nuevo con el botón
  Diagnóstico si se denegó). Si la carpeta pública
  no está disponible, el fichero cae a la carpeta propia de la app y el panel lo indica: el registro
  nunca se pierde, solo cambia de sitio. La carpeta toma el nombre de la aplicación saneado para
  cualquier sistema de archivos: «Telemetría» → `Telemetria`.
- **Manejador global de excepciones** instalado en `TelemetryApp` (la clase `Application` del
  proceso) antes de que se cree cualquier componente, con un detalle importante: **conserva el
  manejador anterior y delega en él** después de escribir, así que el sistema sigue matando el proceso
  y mostrando su diálogo exactamente igual; lo único nuevo es que el motivo queda en disco. La
  escritura en la ruta de caída es síncrona a propósito (un hilo en segundo plano no llegaría a
  terminar) y hace `fsync` del descriptor.
- **Cada incidente lleva contexto accionable**: instante en milisegundos y en ISO 8601, gravedad,
  origen, hilo, excepción, mensaje, pila recortada, cadena de causas, huella del dispositivo
  (modelo, versión de Android, API, ABI, idioma, `low_ram`) y una instantánea del sistema en ese
  instante (rastreo activo, cola pendiente, último código HTTP, estado del canal Gist, reintentos del
  watchdog). **Nunca se escriben tokens.**
- **Agrupación de repetidos y cotas**: el mismo origen, excepción y mensaje dentro de 60 s incrementa
  `repeat_count` en lugar de añadir otro incidente, y el histórico se acota a 200 incidentes y
  500 000 caracteres descartando por el extremo antiguo. Un bucle que falla cada segundo no llena el
  fichero del usuario.
- **Modo de emergencia de la interfaz**: si construir la pantalla completa falla (preferencia
  corrupta, recurso ausente), la app ya no se queda muda: registra el incidente, muestra su detalle y
  ofrece **Iniciar** / **Detener** / **Diagnóstico** usando la configuración ya persistida.
- **Botones Diagnóstico y Limpiar log**: el primero escribe `diagnostico.json` con el estado completo
  del sistema (útil cuando aún no hay ningún incidente) y el segundo vacía el registro por decisión
  explícita del usuario.
- **El registro cubre también los fallos que ya se capturaban**: inicio del servicio en primer plano,
  escrituras rechazadas por el canal Gist (solo las que exigen acción: 401/403/404/422), fallos del
  trabajo de WorkManager, fallos del receptor de arranque, lotes que no se pueden serializar y
  rechazos del ejecutor de red. Las condiciones esperadas —red caída, arranque en segundo plano
  bloqueado por Android 12+— se quedan en `logcat` para no convertir el fichero en ruido.
- **`index.html` en la raíz del repositorio**: es la portada del sitio de Pages cuando se publica la
  raíz. Redirige al visualizador en vivo (que deduce la base del repositorio del primer segmento de
  la ruta, así que funciona igual servido desde `/` o desde `/frontend/`) y deja a un clic el visor
  autónomo del canal Gist y la documentación renderizada.

### Corregido

Y en el run #6, con los 14 arreglos anteriores ya dentro, los que quedaban. La lista de anotaciones
llegó **recortada** —mostró 10, y los dos errores que el run #8 reveló después ya existían en ese
mismo código—, así que además se auditó el patrón completo a mano:

- **`setWaitForAccurateLocation` no existe en el builder del framework** (`LocationTrackerService`,
  dos sitios): el flag es del cliente de Play Services, y el builder de `android.location` solo
  acepta `(intervalo)` o `(petición)`, con la calidad en `setQuality`. Las dos peticiones usan ahora
  métodos que el compilador ya había validado (`setQuality`, `setMinUpdateIntervalMillis`,
  `setMinUpdateDistanceMeters`) más `setMaxUpdates(1)` para el fix único.
- **`JSONArray.put(double)` declara `throws JSONException`** (`GithubPublisher`): ocho dobles de una
  fila compacta se metían sin capturarla —JSON no admite NaN ni infinito, de ahí la excepción
  declarada—. Ahora los dobles se sanean a un centinela antes de entrar y el `catch` queda como red
  de seguridad, porque ese método se llama fuera del `try` de su llamador.
- **Una frase que mentía** (`FusedLocationBridge`): decía que Android 11 y anteriores no tienen ese
  flag «en el framework»; la verdad es que el framework no lo tiene en ninguna versión. Las dos
  razones reales del puente a Play Services son: Android 11 no tiene `LocationRequest` (llegó en
  API 31) y el flag de espera a una medida buena es exclusivo de GMS.

- **El sitio de Pages respondía 404 en la raíz** (`index.html`, `docs/SETUP.md`): el repositorio
  tenía Pages publicando la carpeta **`/docs`**, que no tiene `index.html` —de ahí el «For root URLs
  you must provide an index.html»— y que además dejaba fuera el visualizador de `frontend/` y el
  fichero `data/latest.json`. Ahora la raíz tiene su `index.html` y la documentación explica el
  fallo y la única configuración correcta (`main` + `/ (root)`; la carpeta `/frontend` que
  recomendaba antes no existe como opción en Pages).

Y en el run #8, con los arreglos del #6 ya dentro, quedaban exactamente dos errores —esta vez la
lista de anotaciones venía completa, con los dos fallos y solo dos—, y los dos eran el mismo fallo:

- **Proveedor omitido en las sobrecargas de `LocationRequest`** (`LocationTrackerService`, dos
  sitios): el framework **sí** acepta `LocationRequest` desde la API 31, pero con el nombre del
  proveedor como primer argumento: `requestLocationUpdates(String, LocationRequest, Executor,
  LocationListener)` y `getCurrentLocation(String, LocationRequest, CancellationSignal, Executor,
  Consumer)` (firmas `@Register` de la API pública, `ApiSince=31`). El código las llamaba sin él,
  así que javac elegía la sobrecarga `(String, LocationRequest, PendingIntent)` y fallaba con
  «LocationRequest cannot be converted to String»: el error no estaba en el tipo de la petición
  sino en su posición. El registro de alta precisión pasa ahora el proveedor que se está
  registrando, y la consulta inmediata exige además que el proveedor fusionado exista —con la
  comprobación de versión dentro del propio método, no solo en quien lo llama— para caer al camino
  clásico por proveedor cuando no lo haya.

Y en el run #11, con el proveedor ya en su sitio, el compilador entró por fin en la fase de flujo y
apareció una familia de errores que hasta entonces era **invisible por diseño de javac**: cuando la
atribución falla —una API inexistente, un tipo mal puesto— javac **no ejecuta la comprobación de
excepciones**, así que los `unreported exception` de todo el proyecto estaban escondidos detrás de
aquellos 14 errores de nombres. La lista volvía a llegar recortada a 10 anotaciones, así que los 34
sitios del proyecto se localizaron con un analizador propio que replica esa fase, calibrado contra
los 7 que el compilador sí mostró (7 de 7 de acuerdo, y sin colar ninguna llamada de `ContentValues`
como si fuera JSON):

- **`org.json.put` declara `throws JSONException`** y 34 llamadas no lo cubrían, en cuatro ficheros.
  Los constructores privados de `ErrorLogger` (`mergeIncident`, `stackOf`, `causesOf`,
  `appFingerprint`, `deviceFingerprint`) declaran ahora la excepción, exactamente como
  `buildIncident` ya declaraba `throws Exception` y como sus llamadores ya envuelven el cuerpo en
  `try/catch (Throwable)`; `GistPublisher.buildPayload` hace lo mismo, con `publishOnce` que ya
  declaraba `IOException, JSONException` y ya los capturaba en sus tres llamadores; en
  `GithubPublisher.mergeDevice` las filas compactas se construyen dentro del `try` que ya existía
  para la fusión; y `TelemetryClient.persistLocked` la captura, porque ahí no hay a quién
  propagarla sin cambiar el contrato: si un punto no serializa se pierde el espejo en disco, pero la
  cola en memoria —que es la autoridad— sigue intacta. Se verificaron además los 19 métodos que
  declaran excepciones `checked` en el proyecto: todos sus llamadores las capturan o las declaran,
  sin un solo punto suelto.

Y en el run #5, el primero que llegó a compilar de verdad: los **dos jobs de Python quedaron en
verde** (3.10 y 3.12, con la suite completa y la guarda nueva del workflow incluidas) y el de Android
pasó de morir en la configuración del SDK a **fallar en el compilador**, que es exactamente donde
queremos que falle: **14 errores reales**, todos APIs que el código se había inventado.

- **`AccessibilityServiceInfo` en el paquete equivocado** (`TelemetryAccessibilityService`): vive en
  `android.accessibilityservice`, no en `android.view.accessibility`.
- **Constructor de `LocationRequest.Builder` de Play Services usado sobre el del framework**
  (`LocationTrackerService`, dos sitios): el del framework solo admite `(intervalo)` o `(petición)`,
  y la calidad se fija con `setQuality(QUALITY_HIGH_ACCURACY)`. Los dos errores de las llamadas que
  usaban esa petición eran cascada del constructor roto.
- **`JSONArray` medido con `size()`** (`LocationTrackerService`): esa clase no tiene `size()`; se mide
  con `length()`.
- **Cinco nombres de `GnssStatus` que no existen** (`GnssMonitor`): no hay `getSnr` (es `getCn0DbHz`),
  ni `hasElevation`/`getElevation`/`hasAzimuth`/`getAzimuth` (son `getElevationDegrees` y
  `getAzimuthDegrees`, y no existe bandera de disponibilidad: cuando el fabricante no los calcula
  devuelve NaN, que es lo que ahora se comprueba).
- **Argumentos invertidos en el canal de Google Play Services** (`FusedLocationBridge`): la sobrecarga
  con `Executor` es `(petición, executor, callback)` y la de `Looper` es `(petición, callback,
  looper)`. La API las invierte y el código las había igualado.

Seis correcciones salidas de la auditoría del primer CI real y de su primer run en GitHub —66
pruebas con 2 rojas, el job de Android muriendo antes de compilar y un workflow rechazado entero—:

- **Recorrido inventado por un fix de baja calidad** (`backend/app.py`): la puerta que decide si un
  punto suma distancia solo miraba `outlier` y `gap`, aunque la marca `low_quality` ya se calculaba
  unas líneas antes. Un fix de 500 m de precisión sumaba 0,07 m de trayecto ficticio. Ahora la
  condición es `low_quality or gap`, que cubre los tres casos con una sola comprobación porque los
  outliers ya se marcan también como `low_quality`.
- **`noise_removed_m` solo existía en el informe** (`tests/test_fusion.py`): la prueba lo leía de
  `_meta`, donde no vive porque se deriva al publicar. La prueba lo lee ahora de `_summary()`. El
  comportamiento que verificaba —el filtro reduce el ruido más de un 10 %— ya pasaba; lo que fallaba
  era el nombre del campo consultado.
- **Descriptor sin cerrar al servir el visor** (`tests/test_api.py`): `send_file` deja el fichero
  abierto hasta que alguien cierra la respuesta, y la prueba no lo hacía (`ResourceWarning` en CI).
- **El job de Android nunca llegaba a compilar** (`.github/workflows/ci.yml`): la aceptación
  automática de licencias de `android-actions/setup-android@v3` dejaba el prompt de `sdkmanager` sin
  responder y el job moría a los 11 s, **antes de que Gradle se ejecutara una sola vez**: ninguna
  línea del código Android se había compilado jamás. Las licencias se aceptan ahora con entrada
  infinita y los paquetes (`platforms;android-35`, `build-tools;35.0.0`) se instalan explícitamente.
  Además, la comprobación de sintaxis del JavaScript se ejecuta **antes** de los tests: estaba
  después y nunca llegaba a correr en las CI rojas, así que el JS quedaba sin verificar.
- **Un dos puntos invalidaba el workflow entero** (`.github/workflows/ci.yml#L45`): el nombre de paso
  `SDK de Android: licencias y paquetes` metía «: » dentro de un escalar simple de YAML, que es
  justo lo que la sintaxis prohíbe. GitHub rechazó el fichero con «Invalid workflow file» y creó un
  run con **cero jobs** —de ahí el «no runs» engañoso de la interfaz y de que la compilación ni se
  evaluara—. El paso se llama ahora `Licencias y paquetes del SDK de Android`.
- **Guarda contra esa clase de fallo** (`tests/test_workflow.py`): un lint del subconjunto de YAML que
  usa un workflow —tabuladores, indentación y «: » en escalares simples, respetando comillas y los
  bloques `run: |`— recorre `.github/workflows/*.yml` en cada CI. Está calibrado contra el fichero
  roto: lo marca en la línea exacta que señaló GitHub.

Cinco fallos reales encontrados en una revisión del propio código nuevo, antes de darlo por bueno:

- **El incidente nuevo no se insertaba nunca** en el documento: el cálculo de repeticiones devolvía
  el recuento, pero nadie añadía el incidente al array, de modo que `error.json` habría quedado
  permanentemente vacío —justo lo contrario de lo que se pide—. Ahora la inserción y la agrupación se
  resuelven en la misma función, devolviendo un documento nuevo.
- **Fuga de descriptor en la ruta de caída**: el `fsync` del volcado abría un segundo flujo que nadie
  cerraba. Se sincroniza el flujo que ya se está escribiendo.
- **El historial público se perdía al reinstalar la app**: la copia privada desaparece con los datos
  de la app, pero `Descargas/Telemetria/error.json` sobrevive, así que la primera escritura lo habría
  machacado. Ahora la lectura adopta la copia pública como base cuando la privada falta o está vacía,
  y el registro continúa a partir del historial anterior en vez de empezar de cero.
- **Consulta a SQLite en el hilo principal**: la normalización de un punto al esquema del Gist —que
  corre en los callbacks de ubicación— contaba los puntos almacenados con un `COUNT(*)`. Pasa a usar
  el contador en memoria, que además es atómico porque lo tocan dos hilos distintos.
- **Orden invertido en la tabla del visor**: las filas se insertaban al principio pero se recorrían de
  más nueva a más antigua, de modo que dentro de cada lote la más nueva acababa por debajo de las
  viejas. Se recorre al revés y, de paso, un punto que llegue corregido se refresca en su fila sin
  recrear el nodo (sin parpadeo).

### Documentación y herramientas

- **`scripts/dev.sh` fijaba el wrapper de Gradle a 8.7**, cuando AGP 8.7.3 exige 8.9 o superior:
  quien siguiera ese camino tenía un build roto desde el primer comando. Corregido a 8.9, con el
  motivo escrito al lado, y añadido el comando `gist` para validar un documento del canal.
- **`CONTRIBUTING.md` describía un proyecto que ya no existe**: decía «Java: sin AndroidX» cuando hay
  dos dependencias (motor fusionado y WorkManager), y pedía Android SDK 34 y Gradle 8.7. Ahora
  refleja el SDK 35 y el Gradle 8.9 reales, añade la comprobación del contrato del Gist a la lista de
  pasos previos al envío, avisa de que los tokens no van nunca en el código y explica que el contrato
  del Gist se mantiene en cuatro piezas a la vez.
- El README documenta el contrato del Gist con los comandos de validación, y la sección de tests
  refleja lo que la CI ejecuta de verdad (tests en Python 3.10 y 3.12, APK en debug **y** release,
  `grep` sobre el dex y `node --check` de los dos visores).

### Cambiado

- Las reglas de ofuscación conservan también `TelemetryApp`: el sistema instancia la clase
  `Application` **por nombre** desde el manifest, así que renombrarla habría impedido arrancar el
  proceso. La CI comprueba ahora con `grep` sobre el dex que sobrevive a R8, junto a los demás
  componentes.

## [1.6.0] - 2026-09-19

### Añadido

- **Modo Gist como base de datos (BaaS), sin servidor ni repositorio** (`GistPublisher.java`): la
  app publica el histórico en un Gist y la web lo lee del `raw_url`. Un único `PATCH` por ciclo
  reemplaza el fichero, así que la regla de oro del canal es **leer antes de escribir**: cada ciclo
  hace `GET` del Gist, fusiona lo remoto con lo local por `timestamp_ms` y solo entonces escribe. Si
  el `GET` falla (sin red, 5xx, rate limit), ese ciclo **no toca el Gist**, con lo que nunca se borra
  el historial que otros dispositivos publicaron; si el fichero estaba corrupto o vacío, el envío
  masivo lo reescribe y lo sana.
- **`TelemetryStore.java`, fuente de verdad local en SQLite del propio SDK** (sin Room, sin kapt y
  sin procesador de anotaciones): `timestamp_ms` como clave primaria con `CONFLICT_REPLACE`, de modo
  que dos proveedores que entreguen el mismo instante no pueden duplicar una fila. Es el mismo
  criterio de identidad que usa el visor, así que lo guardado, lo publicado y lo dibujado coinciden.
  Cota de disco de 5 000 puntos con recorte por el extremo antiguo y recorte a saltos para no pagar
  un `DELETE` en cada inserción.
- **Formato publicado**: array ordenado del más antiguo al más nuevo con los tres bloques
  `location` / `telemetry` / `status`, las claves siempre presentes, centinela `-1` en las magnitudes
  que el sensor puede no haber entregado y campos extra de calidad (GNSS, celdas, puntos de acceso,
  altitud con su origen, traza suavizada) para que cada punto siga siendo auditable.
- **`frontend/gist.html`**: visor autónomo en un solo fichero, sin librerías externas, fondo negro
  puro y solo cian `#00FFFF` y púrpura `#800080`. Sondeo cada 3 s con cache-busting, `JSON.parse`
  dentro de `try/catch` que conserva el último estado válido ante una lectura sucia, deduplicación por
  `timestamp_ms` con `Map` (los puntos ya vistos se actualizan en su sitio y las filas nuevas se
  insertan sin repintar: cero parpadeos), traza en `canvas` con bruta y filtrada, panel de
  diagnóstico del enlace y exportación a JSON. Con la pestaña oculta deja de sondear.
- **`tools/check_frontend.py` valida los dos visores** (antes solo `index.html`): un error de
  sintaxis en cualquiera deja una página en blanco, y es el único componente sin compilador propio.
- **`tools/gist_schema.py` y `tests/test_gist_schema.py`: el contrato del Gist, ejecutable.** Las
  reglas que en el dispositivo corre Java no se podían ejecutar desde CI, así que ahora tienen una
  implementación de referencia en Python —validación de bloques, tipos y centinelas, fusión por
  `timestamp_ms` con empate a favor del punto local, orden cronológico y doble recorte— y pruebas que
  la cubren en cada push. El mismo fichero sirve como herramienta: valida un `data.json` real (local
  o por URL), simula la fusión con `--merge` y sale con código distinto de cero si algo no cumple el
  contrato, así que un cambio de formato falla antes de llegar a un móvil.

### Cambiado

- El canal Gist se publica como mucho cada 20 s (180 escrituras/hora), además de un intento inmediato
  al aparecer cualquier red y otro desde `TelemetryWorker`, que lo hace de forma **síncrona** para que
  WorkManager no dé el trabajo por terminado antes de que el Gist se haya escrito.

### Seguridad

- El token de GitHub **no se escribe nunca en el código**: se configura en la app y vive en
  `SharedPreferences`, igual que ya hacía el canal de repositorio. Cualquier PAT pegado en un chat,
  un issue o un commit debe revocarse y sustituirse.

## [1.5.0] - 2026-09-19

### Añadido

- **Motor fusionado de Google (`FusedLocationBridge.java`)**: `LocationRequest` de alta precisión con
  `setWaitForAccurateLocation(true)` sobre `FusedLocationProviderClient`, más `getLastLocation` de
  caché compartida. Es lo que da a **Android 11 y anteriores** la precisión que el framework solo
  ofrece desde Android 12; se comprueba `GoogleApiAvailability` antes de usarlo y, si no hay
  servicios de Google, el servicio sigue con los proveedores del sistema. Los puntos entran como
  fuente `fused/gms`.
- **WorkManager como segunda vía de persistencia y de transmisión (`TelemetryWorker.java`)**:
  trabajo periódico de 15 minutos que comprueba el latido, relanza el servicio y —esto es lo
  nuevo— **vacía la cola persistida por HTTP sin necesidad de servicio en primer plano**
  (`TelemetryClient.flushBlocking()`). En Android 12+ el sistema puede negarse a arrancar el
  servicio; WorkManager sí ejecuta, así que los puntos acumulados llegan igualmente. Tras un
  reinicio hay un reintento único a un minuto, y al detener el rastreo queda un único intento de
  entrega con la condición de que haya red. Al instanciarlo WorkManager **por nombre de clase**, se
  añadió la regla `-keep` correspondiente y la CI comprueba que sobrevive a R8.
- **Reanudación al abrir la app**: `MainActivity` relanza el rastreo al arrancar si el usuario lo
  había dejado activo y el servicio no está vivo. En primer plano no aplica la restricción de
  arranque de servicios desde segundo plano, así que es el camino de rescate que el sistema no puede
  bloquear.
- **Modo recursos bajos (Android Go Edition / 1 GB)**: `ActivityManager.isLowRamDevice()` espacia el
  enriquecimiento a 60 s, recorta los puntos de acceso de 16 a 6, el detalle GNSS de 16 a 6 satélites
  y desactiva el Wi-Fi RTT: mismo dato útil, una fracción del trabajo y menos presión de memoria.
- **`useAndroidX` en Gradle** con las dos únicas dependencias del proyecto, ambas **opcionales en
  tiempo de ejecución** y comprobadas antes de usarse: `play-services-location` y `work-runtime`.
- **Android 15 / API 35**: `compileSdk`/`targetSdk` 35 con AGP 8.7.3 y Gradle 8.9. Como API 35 dibuja
  a pantalla completa por defecto, la actividad aplica los insets reales
  (`systemBars()` + recorte de pantalla); `windowSoftInputMode="adjustResize"` y
  `enableOnBackInvokedCallback` añadidos al manifest.
- **Botón «Telefonía» para el permiso opcional `READ_PHONE_STATE`**: el código no usa ninguna API que
  lo exija (el enriquecimiento de celdas vive del permiso de ubicación precisa), así que no se pide
  en silencio junto a los demás —pedir permisos peligrosos que no se usan es lo que Play penaliza—
  sino solo si el usuario lo pulsa, y `LocationTrackerService.requestEnrichmentRefresh()` fuerza a releer
  Wi-Fi, celdas y telefonía en el siguiente punto en lugar de esperar al TTL de 30 s.
- **Panel de capacidades del dispositivo** en la app: versión de Android, modo de recursos, ubicación
  del sistema, GPS, red, disponibilidad del motor fusionado de Google, soporte de Wi-Fi RTT,
  telefonía, relanzados rechazados por el sistema y última ejecución de WorkManager.

### Corregido

- **La alarma del watchdog, el trabajo periódico y el reintento de arranque ya no se reprograman
  cuando el rastreo está detenido**: antes, un receptor o un latido volvían a armarlos, de modo que
  quedaba una alarma despertando la CPU cada 15 minutos para que el receptor no hiciera
  absolutamente nada. Al detener el rastreo solo queda el intento de entrega de la cola pendiente.
- **Distinción entre fallo determinista y transitorio de `startForeground`**: si falta el permiso de
  ubicación, se desactiva el rastreo (el watchdog deja de relanzar un servicio incapaz de arrancar);
  si es una restricción del sistema o del fabricante, se mantiene autorizado el relanzado, que es
  justo donde el watchdog y `START_STICKY` sí pueden recuperar el servicio.
- **`TelemetryWorker` sin `androidx.annotation` en sus firmas** y con `Result.retry()` cuando quedan
  puntos por entregar: el reintento lo gestiona WorkManager con su propio backoff en lugar de
  esperar 15 minutos al siguiente ciclo.

## [1.3.0] - 2026-09-19

### Añadido

- **Regla de teleport**: un desplazamiento de más de `TELEMETRY_MAX_JUMP_M` (150 m por defecto)
  recorrido a más de `TELEMETRY_PLAUSIBLE_SPEED` (60 m/s) se marca `outlier` con
  `outlier_reason: "jump"`. Atrapa el salto urbano que la regla de velocidad (90 m/s) dejaba pasar.
  Cada outlier lleva ahora `outlier_reason` (`speed` o `jump`).
- Las filas compactas publican la posición filtrada como columnas 9 y 10 (`smooth_lat`, `smooth_lng`),
  después de las 8 históricas: la traza suavizada existe también en modo GitHub Pages sin romper a
  ningún lector antiguo.
- El visualizador dibuja **las dos trazas a la vez** (bruta en gris discontinuo, filtrada en neón)
  en mapa y radar, con leyenda y color neón estable por dispositivo; avisa con `SIN FILTRO` cuando el
  origen no publica traza filtrada.
- Tema **OLED puro** (`#000000`) con acentos neón cian `#00e5ff` y púrpura `#b14cff`, y teselas
  oscuras de CARTO para el mapa.
- **`TrackFilter.java`: Kalman en el propio dispositivo.** El móvil suaviza la posición antes de
  publicarla y rellena `smooth_lat`/`smooth_lng` (con la velocidad estimada si el proveedor no la
  da), de modo que la traza filtrada existe también en modo «GitHub directo», donde no hay servidor
  que la calcule. Es el mismo modelo que usa el backend, así que las dos fuentes son comparables.
- **Wi-Fi RTT (802.11mc, `WifiRttRanger.java`):** distancia real al punto de acceso con precisión de
  decímetros y su desviación típica (`rtt`), medida solo cuando el fix no es preciso y el AP responde
  a RTT. Es la única medida geométrica de verdad disponible en interiores, donde el GPS no llega.
- **Detalle por satélite** en cada punto (`gnss.sats`: constelación, SVID, C/N0, elevación, azimut y
  si se usó en el fix): permite auditar la calidad real de una posición y ver si es GPS-solo o
  multi-constelación.
- **Altitud MSL** (`altitude_msl` y su precisión, API 34) y `complete` (API 30): el servidor prefiere
  la altitud geoidal para calibrar el barómetro porque comparte referencia con él.
- **Deduplicación por instante en el dispositivo:** si dos proveedores entregan el mismo momento con
  precisiones distintas, se envía el mejor y se descarta el peor antes de salir.
- **Alta precisión real (Android 12+):** `LocationRequest` con `QUALITY_HIGH_ACCURACY` y
  `setWaitForAccurateLocation(true)` sobre el proveedor fusionado, réplica rápida entre fixes, y fix
  inmediato a la carta con 20 s de margen y caché de 30 s. Es lo que evita los puntos de ±300 m.
- **Rumbo al norte verdadero:** además del magnético se publica `heading_true` corregido con la
  declinación magnética (`declination_deg`) del modelo geomagnético en la posición actual. El
  visualizador usa el verdadero cuando existe.
- **Vigilante de red:** `registerDefaultNetworkCallback` vacía la cola y publica en GitHub en cuanto
  aparece cualquier conexión (Wi-Fi, datos móviles, ethernet), sin esperar al latido de 60 s.
- `GET /api/health → filter` publica los umbrales activos del filtro.
- Umbrales de integridad configurables por entorno: `TELEMETRY_MAX_JUMP_M`,
  `TELEMETRY_PLAUSIBLE_SPEED`, `TELEMETRY_MAX_SPEED_MPS`, `TELEMETRY_MAX_ACCURACY_M`,
  `TELEMETRY_MAX_GAP_S`.
- `tools/simulate.py --teleport`: inyecta saltos de 150-250 m por vuelta para probar la regla nueva.
- `SensorFusion` registra el sensor de gravedad dedicado (con la aceleración filtrada como
  reserva) para el rumbo compensado por inclinación.
- Ofuscación extrema del APK de release: `-repackageclasses`, `-overloadaggressively`,
  `-allowaccessmodification`, `-useuniqueclassmembernames`, `-adaptclassstrings` y eliminación de
  todas las llamadas a `Log`, conservando el ciclo de vida y los constructores de los componentes
  instanciados por el sistema.
- Imagen Docker **Alpine multi-stage** (~60 MB) con usuario sin privilegios, `init`,
  `no-new-privileges`, `/tmp` en RAM y `stop_grace_period` para compactar el JSONL al cerrar.

### Cambiado

- Las filas compactas del móvil pasan de 8 a 10 columnas (se añaden `smooth_lat`/`smooth_lng`), con
  el mismo formato que las del backend: los lectores antiguos no se rompen porque las columnas
  nuevas van al final.
- La casilla **bruta** del visualizador ya no alterna entre trazas: la filtrada se dibuja siempre y
  la casilla solo oculta la cruda.
- Los umbrales de integridad pasan de constantes fijas a configurables por entorno, manteniendo sus
  valores por defecto (90 m/s, 150 m, 120 s). El filtrado es ahora algo más estricto que en 1.2.0:
  los saltos de 150-250 m a velocidad implausible que antes se aceptaban se descartan.

### Corregido

- **La telemetría del móvil moría en silencio tras el primer cierre del servicio.** El cliente HTTP
  es un singleton de proceso y `onDestroy` cerraba su ejecutor de I/O; como el watchdog relanza el
  servicio en el MISMO proceso, cada envío posterior lanzaba `RejectedExecutionException`, que se
  capturaba y se descartaba: la cola crecía hasta desbordar sin ningún error visible. El ejecutor
  ahora se recrea bajo demanda y `shutdown` deja la instancia reutilizable.
- La brújula publicaba un rumbo que solo era correcto con el teléfono plano (y que medía dónde
  queda el norte dentro de la pantalla, no hacia dónde apunta el aparato). Ahora se calcula con la
  matriz de rotación del framework a partir de la gravedad, así que es válido con el móvil en la
  mano, inclinado o en vertical.
- Con el endpoint vacío, el servicio desactivaba el rastreo pero dejaba `tracking_enabled` en `true`:
  el watchdog relanzaba cada 15 minutos, para siempre, un servicio incapaz de enviar nada. El fallo
  transitorio de `startForeground` sí mantiene el rastreo activo, porque ahí es donde el watchdog y
  `START_STICKY` recuperan el servicio.
- El latido de 60 s vacía la cola pendiente aunque no llegue ningún fix nuevo (antes había que
  esperar a un punto para reenviar lo acumulado) y pide un fix inmediato cuando no queda ningún
  punto conocido, como mucho cada 2 minutos.
- El cliente ya no se queda callado sin red: informa con `sin red` en lugar de no notificar nada.
- Los campos numéricos de la app (intervalo y distancia) abrían el teclado de teléfono: mezclar
  `TYPE_CLASS_NUMBER` con `TYPE_CLASS_TEXT` producía `TYPE_CLASS_PHONE`.
- El panel de estado mostraba la accesibilidad como inactiva tras reiniciar el teléfono aunque el
  centinela estuviera habilitado: ahora se consulta al sistema.
- El espejo de GitHub serializaba el estado completo de todos los dispositivos en **cada** POST para
  descartarlo 59 de cada 60 veces por rate limit; ahora se comprueba antes de serializar.
- En modo GitHub Pages la traza «filtrada» era una copia de la bruta (`smooth: rows`): ahora solo se
  dibuja si el origen la publicó de verdad.
- El visualizador se rompía entero (Leaflet: *Invalid LatLng*) ante una fila sin coordenada o un
  `latest` sin latitud/longitud —posible con un fichero de Pages editado a mano—: las filas no
  numéricas se descartan y ya no se crea ningún marcador ni encuadre sin posición válida.
- `tools/check_frontend.py` sigue validando el JavaScript embebido (sin sintaxis ES6+).

## [1.2.0] - 2026-09-17

### Añadido

- Fusión de posición en el servidor: filtro de Kalman ligero (posición + velocidad por eje),
  traza `smooth` en la API y conmutador «suavizado» en el visualizador.
- Calibración de la altitud barométrica con el primer fix GPS bueno (`altitude_fused`).
- Integridad de datos: `outlier` para saltos > 90 m/s, `low_quality` para precisión > 150 m,
  `gap` para huecos > 120 s, y métricas `raw_distance_m` / `noise_removed_m` / `gaps`.
- `dt` con reloj monotónico (`elapsed_nanos`) cuando el cliente lo envía.
- Cliente Android: `GnssMonitor` (satélites, SNR, constelaciones) y `SensorFusion`
  (barómetro, podómetro, brújula, clasificación de actividad).
- Fix inmediato con `getCurrentLocation` (API 30+) y lectura de Wi-Fi por `ConnectivityManager`.
- Persistencia: `Watchdog` con latido en disco y alarma de 15 min, receptor de arranque,
  administrador de dispositivo y centinela de accesibilidad (sin leer contenido).
- Intervalo mínimo y distancia mínima configurables desde la app.
- Proyecto Gradle completo (AGP 8.5.2 / Gradle 8.7), icono vectorial, firma opcional.
- Suite de tests (`tests/`), validador de JavaScript (`tools/check_frontend.py`),
  CI en GitHub Actions, `Dockerfile`, `docker-compose.yml` y `docs/API.md`.

### Cambiado

- `ACCESS_BACKGROUND_LOCATION` se solicita en una petición independiente (Android 11+).
- Wi-Fi, celdas, batería y red se cachean 30 s para reducir consumo.
- La cola del cliente se persiste en disco como mucho cada 10 s en lugar de en cada punto.

### Corregido

- Permisos `NEARBY_WIFI_DEVICES` (Android 13+) y `ACTIVITY_RECOGNITION` (Android 10+) ausentes.
- `startForeground` con tipo `location` sin permiso ya no derriba el servicio (Android 14).
- `optInt` sobre un valor `double` devolvía siempre el valor por defecto en la notificación.

## [1.1.0] - 2026-09-17

### Añadido

- Validación estricta de rangos, normalización de `timestamp` y descarte de duplicados.
- Cálculo de distancia, velocidad y rumbo por punto, con resumen por dispositivo.
- Persistencia plana JSONL con compactación atómica y carga al arrancar.
- `X-Api-Key` opcional, CORS abierto y errores siempre en JSON.

## [1.0.0] - 2026-09-17

### Añadido

- Backend Flask con estado en memoria y endpoints JSON punto a punto.
- Visualizador estático en Vanilla JS con sondeo cada 3 s y modo radar de reserva.
- Cliente Android nativo (`HttpURLConnection`) con cola, lotes y reintentos con backoff.
