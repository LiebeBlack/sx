# Historial de cambios

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y
[versionado semántico](https://semver.org/lang/es/).

## [1.8.0] - 2026-09-20

### Añadido

- **Configuración completa desde un fichero JSON** (`AppConfig.java`, `config.ejemplo.json`,
  `docs/CONFIGURACION.md`): el cliente deja de exigir que los catorce campos se escriban uno a uno en
  la pantalla. Se redacta un `config.json` en el ordenador —o se exporta desde otro teléfono—, se
  copia a `Descargas/Telemetria/config.json` y la app **lo aplica sola al abrirse**, en un hilo aparte
  y sin permisos de almacenamiento (en Android 10+ se lee por `MediaStore`, igual que `error.json`).
- **El fichero se aplica una sola vez por contenido**: se guarda la huella SHA-256 del texto leído, así
  que abrir la app cien veces no reescribe nada y un fichero corregido se vuelve a aplicar en cuanto
  cambia. Un JSON roto se avisa **una vez**, conserva la configuración anterior y no impide arrancar.
- **Importar y exportar con el selector del sistema** (`ACTION_OPEN_DOCUMENT` / `ACTION_CREATE_DOCUMENT`):
  sin permisos y sin rutas cableadas. La exportación incluye los tokens —el fichero existe para
  trasladar la configuración— y **nunca** el código de seguridad, que no se puede reconstruir desde su
  huella y no debe viajar en claro: solo se exporta si hay candado (`security.lock`).
- **Importador tolerante y explícito**: acepta el objeto directamente o envuelto en `{"config": {…}}`,
  bloques anidados (`server`, `device`, `tracking`, `github`, `gist`, `security`) o las claves planas
  de la aplicación (`endpoint`, `gh_token`, `gist_id`…), números escritos como texto y `"true"`/`"false"`
  entre comillas. Cada valor se valida y lo rechazado se devuelve **con su motivo** («`server.endpoint`
  debe empezar por `http://` o `https://`», «`github.repo` debe tener la forma `usuario/repositorio`»,
  «`device.device_id` normalizado a …»), nunca en silencio. Los intervalos fuera de rango se ajustan y
  se explica a qué valor. Si no hay `device_id`, se genera uno.
- **Un solo dueño de la configuración** (`AppConfig`): reúne la lectura, la exportación, la importación y
  la aplicación en caliente a los tres canales (`TelemetryClient`, `GithubPublisher`,
  `GistPublisher`), que antes vivía repartida entre la pantalla y cada publicador. Las claves de
  `SharedPreferences` **no cambian**: son las mismas cadenas que ya leían los demás componentes.
- **Código de seguridad de la pantalla** (`SecurityGate.java`, `Hashing.java`): de 4 a 12 dígitos, se
  guarda como `SHA-256` con una sal aleatoria de 128 bits **por dispositivo** —nunca en claro—, la
  comparación es en tiempo constante y tras **5 intentos fallidos seguidos** la pantalla queda
  bloqueada **30 segundos**, con cuenta atrás y sin opción de saltárselo. Se define, cambia o quita
  desde la app o desde el fichero (`security.pin`, `security.lock: false`).
- **El candado protege la interfaz, no el rastreo**, y es una decisión deliberada: con la pantalla
  bloqueada el servicio en primer plano, el watchdog y el receptor de arranque siguen funcionando, así
  que un código olvidado no puede detener el motor ni perder puntos. No hay puerta trasera ni pista de
  recuperación: si se olvida, se borran los datos de la app y se vuelve a configurar (el rastreo sigue).
- **Guía de configuración campo por campo** (`docs/CONFIGURACION.md`): qué significa cada clave, de
  dónde sale cada dato, la plantilla completa para copiar, el paso a paso del token de GitHub y del
  Gist, la checklist de un teléfono nuevo y una tabla de problemas con su causa.
- **Plantilla lista para rellenar** (`config.ejemplo.json`, en la raíz del repositorio): con los tokens
  vacíos —para que copiarla no active nada por accidente— y sin `device_id`, para que cada teléfono
  genere el suyo.

- **Protección contra la desinstalación** (`UninstallGuard.java`, tarjeta 6 de la pantalla): dos capas
  con garantías distintas, y la diferencia se dice en la propia interfaz. **Propietario del
  dispositivo** es la garantía real: desde la API 21 solo esa condición permite pedir al sistema que
  impida desinstalar la app (`setUninstallBlocked`), y una app propietaria ni se puede desactivar —el
  botón desaparece—; se establece una vez, en un teléfono recién restablecido, y la pantalla muestra y
  copia el comando exacto de ese teléfono. **Centinela de accesibilidad** es la segunda capa y no
  necesita permisos de propietario: reconoce la pantalla de desinstalación o de desactivación por los
  **metadatos del evento** —paquete, texto y descripción, sin leer el árbol de la ventana, así que el
  servicio sigue con `canRetrieveWindowContent="false"`— y la cierra con «Atrás», avisa y lo registra.
- **Apagarla exige el código de seguridad** y activarla obliga a tener uno: sin código, cualquiera con
  el teléfono en la mano apagaría la protección de un toque. La app propone definirlo en ese momento.
- **Todo intento queda registrado**: cada interceptación, y también **cada desactivación del
  administrador de dispositivo** —que sin ser propietario no se puede impedir— acaban en `error.json`,
  con el contador a la vista en la tarjeta de protección y en la de estado. La protección se reaplica
  en cada señal del watchdog: arranque, actualización del paquete y latido.
- **Salida de emergencia documentada** (`docs/CONFIGURACION.md` §10), porque un candado sin salida deja
  al dueño fuera de su propio teléfono: la propia app (con el código), `adb shell dpm remove-active-admin`,
  el modo seguro y el restablecimiento de fábrica. El apartado dice con la misma claridad lo que la
  protección **no** hace (no impide un restablecimiento ni resiste a alguien con un ordenador).
- **Medida del día en el móvil** (`DailyTally.java`, `LocationTrackerService`, `MainActivity`): el
  servidor y el visor ya calculaban la distancia de un trayecto, pero nadie guardaba **el día**: al
  reiniciar el teléfono, el recorrido de la jornada era otra vez una incógnita. Ahora se acumula una
  sola vez por punto aceptado —en el mismo sitio donde el punto sale hacia los tres canales, así que
  el total diario cuenta exactamente los mismos puntos que se publican—, con el día natural **local**
  como frontera, persistido en las preferencias (sobrevive a reinicios y al cierre de la app) y
  conservado `14` días. Se ve en la tarjeta de estado, bajo el rótulo **RECORRIDO POR DÍA**, con hoy y
  el día a día de la última semana, y la notificación del servicio lleva el de hoy al lado de la
  calidad del dato (`±8 m · sat 12 · WALKING · cola 0 · hoy 12.40 km`).
- **Solo suma tramos creíbles**, con las **mismas reglas que el filtro de traza** —que ahora comparte
  su fórmula y sus umbrales (`TrackFilter.distanceMeters`, `MAX_GAP_SECONDS`, `MAX_IMPLIED_SPEED_MPS`),
  en lugar de tener una copia—: un salto de GPS no es un desplazamiento, y un hueco largo —la app
  estuvo parada o sin cobertura— no se atribuye a nadie, porque no se sabe qué camino se hizo. En ese
  caso la referencia avanza igual, así que el tramo siguiente se mide desde donde se está de verdad.
- **Cadena de custodia en el registro** (`WatchdogReceiver`, `LocationTrackerService`, manifiesto):
  se anotan los hitos que deja detrás un robo o un mantenimiento —el **apagado del teléfono con el
  recorrido del día**, el **modo avión** con su estado, el **reinicio** y la **actualización de la
  app**—, y **una sola vez por episodio** la **ubicación del sistema apagada** o el **permiso de
  ubicación revocado**, que es la manera de apagar un rastreador sin desinstalarlo. El receptor es el
  mismo que ya atendía el arranque: dos acciones más en su filtro, sin servicios ni consumo nuevos.
- **Panel «distancia por día» en el visor** (`frontend/index.html`): siete días naturales del
  navegador, con **hoy siempre presente** y cada día en una barra a escala del mejor día de la
  ventana, más una casilla `hoy` en la barra del pie. Se calcula en el navegador a partir del
  histórico cargado, así que funciona **igual en las tres fuentes** —API, fichero de Pages y Gist— sin
  tocar el contrato publicado, y usa la traza filtrada cuando existe, de modo que el número del
  teléfono y el del navegador coinciden.

### Cambiado

- **Pantalla reorganizada en tarjetas numeradas** (`MainActivity.java`, `res/values/themes.xml`):
  servidor y dispositivo, ritmo de captura, GitHub, Gist, fichero y seguridad, y el estado del sistema.
  Los botones pasan a una rejilla de dos columnas, los campos llevan una línea de ayuda, los secretos
  tienen las sugerencias del teclado desactivadas y las rutas largas se muestran en una línea aparte
  para que un campo no aplaste al de al lado.
- **Tema oscuro declarado en el manifest** (`@style/AppTheme`): negro puro OLED y acentos cian/púrpura,
  los mismos que el visor, con los colores de los controles (texto, pistas, acento, resaltado y barras
  del sistema) definidos en un solo sitio en lugar de pintarse a mano por pantalla. Hereda de Material
  (API 21+) y **no añade ninguna dependencia** al APK.
- **La carga del fichero ocurre mientras se construye la pantalla**, en segundo plano: si aparece un
  aviso es porque había algo que aplicar, y cuando no hay fichero —o ya estaba aplicado— no se dice
  nada, para no convertir lo normal en un mensaje.
- **El candado se puede cerrar sin salir de la app** (`MainActivity`): nuevo botón **«Bloquear ahora»**
  en la tarjeta 7, que vuelve a dejar la pantalla de configuración —tokens y controles de la protección
  incluidos— detrás del código. Al hacerlo se sueltan las referencias a las vistas de la pantalla
  anterior, y las guardas de `null` que ya existían para el modo de emergencia son las que evitan que
  el latido de estado toque vistas que ya no están en pantalla.
- **Campos del código más cómodos**: el campo del código pide el foco al abrirse, el teclado numérico
  trae la tecla **«Listo»** y un intento fallido deja el texto seleccionado para sustituirlo
  escribiendo, sin borrar dígito a dígito. El botón de la tarjeta 6 pasa de «Bloquear desinstalación» a
  **«Bloqueo del sistema»**, porque ahora también levanta un bloqueo heredado.

- **CI al día, y con menos formas de quedarse colgada** (`.github/workflows/ci.yml`): las cinco
  acciones suben a su versión mayor más reciente (`checkout` v4→v7, `setup-python` v5→v7,
  `setup-java` v5→v6, `upload-artifact` v4→v7, `setup-gradle` v4→v6), cada una **verificada contra su
  `action.yml`** antes de tocarla para no inventar parámetros que ya no existen; las cinco corrían
  sobre un Node 20 retirado y GitHub solo lo avisaba. El runner se fija en `ubuntu-24.04` en lugar de
  `ubuntu-latest` —que migra a Ubuntu 26 el 19 de octubre de 2026—, cada job lleva tiempo máximo (25 y
  10 min) para que un cuelgue falle en vez de consumir cuota, un push nuevo **cancela** el run anterior
  en lugar de dejar dos pipelines del mismo commit, el pip de Flask se cachea, y un paso comprueba que
  **`node` existe** antes de los tests: la prueba del visor se salta sin él, y ese salto silencioso
  convertiría una regresión del adaptador en un verde. `setup-gradle` se fija en
  `cache-provider: basic` —la caché de código abierto sobre GitHub Actions, la que ya está medida aquí—
  en lugar del servicio comercial que v6 pone por defecto; su `dependency-graph` ya viene `disabled`.

### Web (visualizador)

- **Las tarjetas se actualizan en su sitio, no se rehacen** (`frontend/index.html`): la lista entera se
  reemplazaba en cada sondeo, y eso reparseaba el HTML de todos los dispositivos cada pocos segundos y
  se llevaba por delante el foco del teclado, el hover y cualquier texto seleccionado. Ahora cada
  tarjeta se crea una vez y solo se reescribe su cuerpo cuando el contenido cambia de verdad (se
  compara la cadena ya construida). Con el mismo cambio, el recorrido del día se calcula **una sola
  vez por render**: antes se recalculaba entero por tarjeta y otra vez para el panel, multiplicando el
  trabajo por el número de dispositivos.
- **Las tarjetas se abren con el teclado** y el estado se anuncia (`role="button"` + `tabIndex` +
  Intro/Espacio, `role="status"` con `aria-live` en la píldora, `:focus-visible` en todos los
  controles). Antes solo respondían al ratón.
- **Aviso visible si el CDN del mapa no carga** (barra bajo la cabecera): el visor pasa al radar y lo
  dice, en vez de dejar un hueco negro sin explicación. Y un `<noscript>` que explica que sin
  JavaScript no hay nada que mostrar, con el enlace a la documentación, que sí se lee sin él.
- **«Limpiar» pide confirmación en dos pasos** (el botón cambia a «¿Seguro?» durante 4 s): borra el
  historial local y no se deshace. Se evita el diálogo nativo, que algunos navegadores incrustados
  bloquean.
- **El CSV ya no se rompe con una coma en el nombre**: las celdas se entrecomillan solo cuando hace
  falta y las comillas se doblan, en la exportación de la traza y en la de los días.
- **El globo del mapa se construye al abrirlo**, no en cada render, y ahora dice también lo recorrido
  hoy por ese dispositivo. La posición actual de una fuente sin bloque `latest` se refresca en cada
  ingesta: antes se fijaba una vez y la tarjeta se quedaba congelada en el primer punto que llegó.
- **Detalles de interfaz**: `color-scheme: dark`, `theme-color`, `viewport-fit=cover` con
  `safe-area-inset` para las pantallas con muesca, `100dvh`, y `prefers-reduced-motion` para quien pide
  no ver el latido del marcador.
- **El visor principal ya lee el Gist**: `frontend/index.html` pasa de dos fuentes a tres —**API en
  vivo**, **GitHub Pages (fichero)** y **Gist (histórico)**—, así que el canal sin servidor y sin
  repositorio deja de tener un visor aparte: el mismo mapa, el mismo radar, las mismas dos trazas
  (bruta y filtrada), las mismas tarjetas y las mismas estadísticas sirven para las tres. El ID del
  Gist admite también su URL cruda completa.
- **Exportación de la traza visible en CSV y GPX** (botones `CSV` y `GPX`): se descarga lo que está en
  pantalla —el GPX con una traza por dispositivo, listo para mapas, relojes y análisis— sin servidor y
  sin permisos, con Blob y enlace temporal. Si no hay nada que exportar lo dice en lugar de generar un
  fichero vacío.
- **Vistas compartibles por URL**: `?feed=`, `?gist=`, `?path=`, `?base=`, `?view=`, `?interval=`,
  `?follow=` y `?raw=`. Los **tokens no se aceptan por URL** a propósito: una credencial en el
  historial del navegador o en un enlace compartido es una credencial filtrada, y el visor no la
  necesita para leer.
- **Los campos de cada fuente se muestran solo cuando tocan** (el del Gist y el del fichero de Pages)
  y la ayuda vacía explica qué le falta a la fuente elegida, en vez de un «esperando telemetría»
  genérico.
- **Cambiar de fuente vacía la vista**: mezclar puntos de dos orígenes distintos en el mismo mapa haría
  pasar por un recorrido lo que en realidad son dos historiales.
- **Prueba de comportamiento del adaptador** (`tests/test_frontend_adapter.py`): extrae las funciones
  reales del visor y las ejecuta con `node`, así el contrato del Gist queda probado en CI y no solo se
  comprueba su sintaxis. Se salta sin ruido si `node` no está instalado. Cubre ya **48 comprobaciones**,
  veintiuna del día a día y del recuento de descartes: que el salto de GPS no engorde un día, que los
  puntos de ayer no se cuelen en hoy, que un día sin datos quede a cero, que la traza filtrada mande
  cuando existe, que una fila sin instante no cree un día nuevo, que **dos dispositivos lejanos no
  inventen un tramo entre ellos** y que el Gist diga cuántos puntos descartó.
- **El día también se exporta y se ve por dispositivo**: el botón **Días** descarga en CSV la tabla
  completa del histórico cargado (`fecha,distancia_m,distancia_km,puntos`), no solo la semana que
  enseña el panel, y la tarjeta de cada dispositivo añade **hoy**, para saber cuál se movió y cuánto sin
  cruzarlos a ojo. El núcleo es uno solo (`collectDays`), y suma **cada dispositivo por separado**: si
  mezclara sus filas, el tramo entre el último punto de un teléfono y el primero de otro sería un salto
  inventado de miles de kilómetros.
- **El GPX se describe a sí mismo**: `metadata` con el número de trazas, los puntos, la distancia y el
  rango temporal, y un `desc` por traza con sus puntos y su distancia. Al abrirlo en un mapa, un reloj o
  un analizador, esos datos ya no hay que deducirlos a mano.
- **El Gist ya no descarta en silencio**: el adaptador cuenta los puntos que se caen por el contrato y
  lo dice —«el Gist se leyó bien, pero no trae ningún punto válido (3 descartado(s) por el contrato)»—,
  en lugar de dejar una vista vacía indistinguible de «todavía no hay datos».
- **El visor autónomo queda enlazado y completo** (`frontend/gist.html`): el visor principal lleva un
  enlace directo a él —es el que sigue funcionando si el CDN del mapa no carga—, y ese fichero, que no
  mostraba **ninguna** distancia, ahora trae el total recorrido y los metros de **hoy** con las mismas
  reglas. Las tres funciones están repetidas a propósito respecto a `index.html`: su promesa es ser un
  solo fichero sin librerías, y compartir código la rompería; queda dicho en el propio código.

### Corregido

- **La plantilla que la guía manda copiar no traía la mitad de los ajustes** (`config.ejemplo.json`,
  `docs/CONFIGURACION.md`, `tools/check_config.py`): el fichero de ejemplo declaraba 12 claves y el
  importador entiende 18, así que copiarlo dejaba sin configurar `device.device_id`, `github.user`,
  `gist.token` y —lo más grave— **`security.pin`**: el apartado 6 explica cómo poner el código de
  seguridad por fichero, y hacerlo copiando lo que ese mismo apartado manda copiar **no era posible**.
  El fallo era mudo por definición: quien lo seguía se quedaba sin código y sin ningún aviso, porque
  una clave que no está en el fichero no se ignora — simplemente no existe. La guía tenía además **su
  propia copia** de la plantilla, también desactualizada, y nada comparaba los dos textos. Ahora la
  plantilla cubre los 18 campos, la copia de la guía es idéntica al fichero y una comprobación nueva
  en CI (`tools/check_config.py`) contrasta los tres sitios —importador, plantilla y guía— y **se
  autocalibra**: si deja de detectar los fallos que se le inyectan, falla.
- **Una clave `security.lock` en la plantilla habría borrado el código del teléfono al copiarla**
  (`config.ejemplo.json`): `"lock": false` **quita** el código existente, y la plantilla es un fichero
  que se copia tal cual; en un teléfono que ya tuviera candado lo habría desactivado en silencio. Se
  deja fuera a propósito —y así está declarado, con su motivo, en la comprobación de CI—: para quitar
  un código se escribe `lock` a mano, que es lo que explica el apartado 6.
- **Un `device_id` vacío en el fichero salía como «configuración ignorada»** (`AppConfig.java`): vacío
  ahora significa «no lo toques», que es justo lo que conviene al repartir la plantilla entre varios
  teléfonos —cada uno conserva el suyo—. Antes entraba en la rama, se normalizaba a nada y aparecía en
  el aviso de la pantalla: un error que solo existía por copiar el fichero que manda la guía.
- **La app arrancaba en modo de emergencia y la interfaz completa no llegaba a cargar nunca**
  (`MainActivity.java`): `card(parent, …)` crea la tarjeta **y ya la añade** al padre, pero dos sitios
  volvían a añadirla (`root.addView(protectionCard)` y `root.addView(hardeningCard)`), así que
  `addView` lanzaba `IllegalStateException` —«el hijo ya tiene padre»— y `buildUi` se quedaba a medias.
  Como la pantalla se construye de una sola vez, ese único fallo tumbaba **las nueve tarjetas**: el
  usuario no veía ni un campo de configuración y la app caía al modo de emergencia, con el aviso de la
  pantalla mínima y el `error.json` como única pista. Se quitan las dos líneas sobrantes y el javadoc
  de `card()` avisa de que **no hay que volver a añadirla**. Además, el camino de emergencia ahora
  suelta las vistas a medias (`releaseConfigViews()`) antes de mostrar la pantalla mínima: antes solo
  los cuatro campos de estado estaban guardados contra `null`, así que un fallo un poco más tardío
  —con los campos de entrada ya creados y las vistas de estado todavía sin crear— habría encadenado un
  `NullPointerException` encima del fallo original; ahora las guardas cubren cualquier punto.
- **Toda la documentación publicada daba 404** (`docs/doc.html`, `docs/index.html`, `index.html`): la
  portada del sitio, el `index.html` de la raíz y la propia prosa de `SETUP.md` enlazaban a
  `SETUP.html`, `API.html` y `CONFIGURACION.html`, y **esos ficheros no existen**: la documentación se
  escribe en Markdown y nada la convertía, así que el sitio se servía bien y cada sección daba «File
  not found». Como este proyecto no tiene paso de compilación para el sitio, la solución es la misma
  idea que `frontend/gist.html` —**un solo fichero, sin librerías**—: `docs/doc.html` lee el Markdown
  que Pages publica de su propia carpeta (`text/markdown`) y lo pinta, con lista blanca de los tres
  documentos, todo el texto escapado antes de añadir etiquetas y las anclas generadas con la regla de
  GitHub, para que el índice interno de `CONFIGURACION.md` funcione. Además de los enlaces, se
  comprobó con un rastreador de enlaces relativos que **todo el sitio resuelve** (HTML y Markdown).
- **Las fuentes sin servidor mostraban «rec. 0 m» para siempre** (`frontend/index.html`): ni el Gist ni
  el fichero de Pages publican el resumen que sí calcula la API (distancia, velocidad máxima, precisión
  media, ruido quitado), así que la tarjeta y el pie mostraban ceros y guiones. Ahora el navegador lo
  deduce de las propias filas —haversine entre puntos consecutivos, con el mismo umbral de 90 m/s que
  usa el filtro del móvil para no contar un salto de GPS como desplazamiento— y la distancia que manda
  es la de la traza filtrada cuando existe, con `noise_removed_m` = bruta − filtrada, igual que en la
  API.
- **La carga automática podía no ver el fichero que había que cargar** (`AppConfig.java`): en Android
  10+ la consulta a `MediaStore` solo devuelve a cada aplicación los ficheros **que ella misma
  escribió**, así que un `config.json` copiado desde un ordenador podía quedar invisible y la app no
  decía nada —el peor de los silencios, porque el usuario ya había hecho su parte—. Ahora, si esa
  consulta viene vacía, se intenta también la ruta clásica, y la tarjeta de la pantalla y la guía
  dicen cuál es el camino garantizado: **Importar JSON**, que usa el selector del sistema y no depende
  de ningún permiso de almacenamiento.
- **`gist.html` inventaba la hora actual cuando un punto no traía instante** (`ts = Date.now()`): un
  objeto sin marca de tiempo aparecía como recién capturado, que es la peor forma de inventar un dato
  —parece fresco—. Ahora el instante es obligatorio y **positivo**, como exige el contrato
  (`telemetry.timestamp_ms`), y el punto se descarta si no lo trae. Se acepta en segundos y se
  normaliza a milisegundos, igual que el backend.
- **Ni el visor principal ni `gist.html` entendían el alias `lon`**: el backend lo acepta desde el
  principio (`raw.get("lng", raw.get("lon", raw.get("longitude")))`), pero los dos visores solo
  miraban `longitude` y `lng`, así que un Gist escrito a mano con `lon` se descartaba **en silencio**.
  Es exactamente el caso que había publicado el Gist real de pruebas (`{"lat":0.0,"lon":0.0,
  "timestamp":0}`): la página decía «0 puntos» sin explicar por qué.
- **Un Gist sin ningún punto válido ya no es un silencio**: `gist.html` cuenta y muestra los elementos
  **descartados por el contrato** (sin coordenada o sin instante positivo) y avisa cuando el fichero
  se leyó bien pero no había nada pintable. Antes eso era una vista vacía indistinguible de «todavía
  no hay datos».
- **El visor principal descartaba filas del Gist sin coordenada numérica** sin decirlo y sin contar:
  ahora el descarte sigue siendo defensivo —una fila mala no puede tumbar el render de Leaflet— pero
  forma parte del mismo contrato explícito.
- **El candado se podía quitar con la protección contra desinstalación activada** (`SecurityGate.clear`,
  `MainActivity`, `AppConfig`): quitar el código dejaba la tarjeta 6 abierta, así que cualquiera con el
  teléfono en la mano apagaba la protección de un toque. Ahora `clear` **se niega mientras la
  protección está activa** y lo dice; la regla vive en un solo sitio —el único por el que se puede
  perder el candado— y por eso vale igual para el diálogo de seguridad y para la importación de un
  fichero, que antes también la saltaba en silencio.
- **La tarjeta de protección mentía después de borrar los datos de la app** (`UninstallGuard`): el
  bloqueo de desinstalación vive en el **sistema**, no en los datos, así que sobrevivía al borrado
  —que es justo el camino de salida cuando se olvida el código— mientras la pantalla afirmaba «se
  desinstala con normalidad». Ahora el estado del sistema se informa siempre, y **«Bloqueo del
  sistema»** levanta ese bloqueo heredado desde el propio teléfono, con confirmación, cosa que antes
  solo se podía hacer por ADB. Con la protección activada no se levanta desde ahí: se volvería a
  aplicar en el siguiente latido, y para eso está «Activar / apagar».
- **La salida del código olvidado vivía en un aviso de tres segundos** (`MainActivity`): el botón
  «¿Código olvidado?» era un `Toast` que decía «borra los datos de la app», sin explicar qué se pierde
  —y qué no—, ni qué pasa con el bloqueo del sistema, ni cómo volver a dejar el teléfono como estaba.
  Ahora abre la explicación entera: el rastreo sigue funcionando, lo que se pierde es la configuración
  del teléfono y nunca lo ya publicado, el bloqueo del sistema sobrevive al borrado y se levanta desde
  la tarjeta 6, y el comando de ADB de *este* teléfono se copia con un toque. Incluye un atajo a los
  ajustes de la app y recuerda que el bloqueo por intentos dura 30 s, que es lo primero que conviene
  intentar antes de darse por vencido. Incluye también el camino **más corto**, que hasta ahora no
  estaba escrito en ninguna parte: dejar en `Descargas/Telemetria/` un `config.json` con un
  `security.pin` nuevo, que la app aplica sola al abrirse — explicando por qué eso no es una puerta
  trasera: escribir ese fichero exige acceso físico al teléfono, el mismo que hace falta para borrar
  los datos.
- **El bloqueo por intentos fallidos se medía solo con el reloj de pared** (`SecurityGate`): adelantar la
  hora del teléfono lo abría al instante —un atajo para probar códigos seguidos— y atrasarla podía
  dejar la pantalla bloqueada durante horas, que es justo lo que provocan un cambio de zona horaria o
  un técnico poniendo la hora a mano. Ahora la cuenta usa **también el reloj monótono del arranque**,
  que no se puede tocar desde los ajustes, toma el mayor de los dos y lo recorta a los 30 s: ni atajo
  para forzarlo ni secuestro de la propia app. La documentación lo dice, en la sección del código.

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
  `setMinUpdateDistanceMeters`) más `setMaxUpdates(1)` para el fix único. La otra API retirada,
  `setMaxUpdateAgeMillis`, tampoco está en el builder del framework (lo confirmó el compilador), así
  que se quitó sin sustituto: la consulta inmediata pide un fix genuinamente fresco en lugar de
  aceptar uno de hasta 30 s de antigüedad. Es un cambio de comportamiento pequeño, deliberado y en
  la dirección prudente.
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
- **Portada para la carpeta de documentación** (`docs/index.html`, `docs/SETUP.md`): cuando Pages
  publica `/docs` —que es la configuración que estaba activa— la raíz del dominio seguía en 404
  porque esa carpeta no tenía portada. Ahora la tiene: enlaces a la API y a la puesta en marcha, el
  badge de la CI y la explicación de las dos formas de ver el mapa en vivo, sin JavaScript y con la
  misma paleta OLED del visor. El Paso 4 de la puesta en marcha describe con precisión qué publica
  cada carpeta en vez de dar por hecha una sola: `/ (root)` publica además el visualizador y
  `data/latest.json`; `/docs`, solo la documentación.

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

Y en el run #13, el primero con el compilador conforme: **todo en verde, por primera vez**. El job
de Android bajó de 14 errores a 12, a 2, a 1 y a 0, y esta vez no solo compila: el **release se
construye con las reglas ProGuard extremas**, la comprobación de que el manifest sobrevive a R8
pasa, y el **APK se sube como artefacto** (los dos jobs de Python, verdes desde el #5, siguen en
verde con la guarda del workflow y la comprobación de JavaScript incluidas). Lo único que quedaba
después del #11 era mío, y dejó una regla que conviene no olvidar:

- **Un `catch (JSONException)` alrededor de un `try` que no puede lanzarla** (`TelemetryClient`): el
  lenguaje prohíbe capturar una excepción que el cuerpo no declara, y el compilador lo dijo con
  precisión: «exception JSONException is never thrown in body of corresponding try statement». La
  causa es una asimetría real de `org.json`: **todos los `JSONObject.put(...)` y las sobrecargas
  numéricas de `JSONArray` declaran `JSONException`, pero `JSONArray.put(Object)` no** — y
  `array.put(it.next())` cae justo en ese último caso.
- **Cuatro correcciones de más, retiradas con el mismo criterio**: el barrido previo sobrecorrigió
  porque no distinguía la sobrecarga de objeto, así que sobraba el `throws JSONException` de
  `ErrorLogger.stackOf` y `causesOf` y el de `GistPublisher.buildPayload`, y sobraba mover las filas
  compactas de `GithubPublisher.mergeDevice` dentro del `try`. El compilador fue el árbitro: los
  cuatro volvieron a su forma anterior y se quedaron los tres que sí hacían falta (`mergeIncident`,
  `appFingerprint`, `deviceFingerprint`). Los comentarios que explican la asimetría de `org.json` se
  conservan donde importan.

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
