# Configuración del cliente Android

Todo lo que hay que rellenar para dejar un teléfono rastreando, campo por campo, y cómo cargarlo de
una vez con **un solo fichero JSON** en lugar de escribir catorce campos a mano.

- [1 · Las dos formas de configurar](#1--las-dos-formas-de-configurar)
- [2 · El fichero en 20 segundos](#2--el-fichero-en-20-segundos)
- [3 · Campo por campo](#3--campo-por-campo)
- [4 · El token de GitHub](#4--el-token-de-github)
- [5 · El Gist](#5--el-gist)
- [6 · Código de seguridad (PIN)](#6--código-de-seguridad-pin)
- [7 · Llevar la configuración a otro teléfono](#7--llevar-la-configuración-a-otro-teléfono)
- [8 · Checklist de un teléfono nuevo](#8--checklist-de-un-teléfono-nuevo)
- [9 · Problemas y soluciones](#9--problemas-y-soluciones)
- [10 · Impedir la desinstalación](#10--impedir-la-desinstalación)

---

## 1 · Las dos formas de configurar

| Forma | Cuándo usarla |
| --- | --- |
| **La pantalla de la app** | Un teléfono suelto, cambios rápidos. Los campos están agrupados en tarjetas: servidor, ritmo, GitHub, Gist, fichero y seguridad. |
| **Un fichero `config.json`** | Uno o varios teléfonos, o volver a montar el sistema después de reinstalar. Se escribe una vez y la app lo aplica sola. |

Las dos escriben exactamente lo mismo: el fichero no es un modo aparte, es la misma configuración
en un formato que se puede copiar.

## 2 · El fichero en 20 segundos

1. Copia la plantilla de abajo (o el fichero
   [`config.ejemplo.json`](https://github.com/LiebeBlack/sx/blob/main/config.ejemplo.json), en la
   raíz del repositorio) a tu ordenador y rellénala: el significado de cada campo está en el
   [apartado 3](#3--campo-por-campo).
2. Pásalo al teléfono, a la carpeta de Descargas, dentro de la carpeta de la app:
   **`Descargas/Telemetria/config.json`** (la app indica la ruta exacta en su tarjeta
   «Configuración en un fichero y seguridad»).
3. Abre la app: **lo aplica sola**. Verás un aviso con los campos aplicados y los que se ignoraron,
   cada uno con su motivo.

> **El único caso en el que la carga automática puede no verlo.** Desde Android 10 cada aplicación
> solo puede leer de Descargas los ficheros **que ella misma escribió**, así que un `config.json`
> copiado desde un ordenador (USB, correo, nube) puede no aparecer en la búsqueda. La app intenta
> además la ruta clásica, pero si no lo encuentra el camino fiable es **Importar JSON**: el selector
> del sistema no depende de ese permiso y funciona igual en cualquier versión. La carga automática
> está pensada sobre todo para el fichero que exporta la propia app.

También se puede elegir desde cualquier ubicación con **Importar JSON** (selector del sistema, sin
permisos). Y **Exportar JSON** genera el mismo formato con lo que el teléfono tiene puesto: es la
forma de llevarse la configuración a otro equipo.

**Detalles que conviene saber:**

- Se aplica **una vez por contenido**: la app guarda la huella SHA-256 del fichero, así que abrir la
  app cien veces no reescribe nada. Si editas el fichero, la huella cambia y se vuelve a aplicar.
- Un fichero **mal escrito no rompe nada**: se avisa una vez, se conserva la configuración anterior
  y la app sigue funcionando.
- El importador es **tolerante**: acepta el objeto directamente o dentro de una clave `config`,
  acepta los bloques de abajo o las claves planas de la app (`endpoint`, `gh_token`, `gist_id`…),
  números escritos como texto y `true`/`false` entre comillas. Lo que no entiende lo ignora con un
  motivo, nunca lo inventa.

**Plantilla completa** (cópiala y rellena solo lo que uses; lo que dejes vacío o fuera se ignora, y
lo que no pongas conserva el valor que ya tuviera el teléfono):

```json
{
  "version": 1,
  "server": {
    "endpoint": "https://mi-servidor.ejemplo.com/api/location",
    "api_key": ""
  },
  "device": {
    "device_id": "",
    "label": "Teléfono de campo"
  },
  "tracking": {
    "interval_seconds": 5,
    "distance_meters": 5,
    "enabled": false
  },
  "github": {
    "token": "",
    "user": "",
    "repo": "usuario/repositorio",
    "branch": "main",
    "path": "data/latest.json"
  },
  "gist": {
    "token": "",
    "id": "",
    "file": "data.json"
  },
  "security": {
    "pin": ""
  }
}
```

> **`device_id` vacío es lo correcto aquí.** En blanco significa «no lo toques»: cada teléfono
> conserva el identificador que ya generó, así que copiar esta plantilla en varios equipos **no
> los iguala**. Y el bloque `security` trae `pin` vacío (no cambia el código) pero **no trae
> `lock`**: con `false` borraría el código de un teléfono que ya lo tenga, y esto es el fichero que
> se copia tal cual. Para quitar un código, escríbelo tú —está explicado en el apartado 6—.
>
> Esta copia y el fichero [`config.ejemplo.json`](https://github.com/LiebeBlack/sx/blob/main/config.ejemplo.json)
> tienen que ser idénticos, y la CI lo comprueba (`tools/check_config.py`).

Dos avisos sobre la plantilla, porque son los dos errores fáciles:

- **`device_id` no viene**: si lo omites, la app genera uno (`android-<hex>`) la primera vez, que es
  lo que quieres. Solo escríbelo si necesitas un identificador fijo, y **distinto en cada teléfono**.
- **Los tokens van vacíos**: con `github.token` vacío el canal de repositorio queda desactivado y con
  `gist.id` vacío el del Gist también. Rellénalos solo si vas a usar esos modos (§4 y §5).

**Ejemplo mínimo** (solo enviar a tu propio servidor):

```json
{
  "server": { "endpoint": "https://mi-servidor.ejemplo.com/api/location", "api_key": "mi-clave" },
  "device": { "label": "Teléfono de campo" },
  "tracking": { "interval_seconds": 5, "distance_meters": 5 }
}
```

## 3 · Campo por campo

### Bloque `server` — a dónde van los puntos

| Clave | Tipo | Obligatorio | Ejemplo | Qué es |
| --- | --- | --- | --- | --- |
| `endpoint` | texto | **sí** (para usar la API) | `https://midominio.com/api/location` | URL del backend. Debe empezar por `http://` o `https://`; si no, se ignora y se avisa. Con `docker compose` local es `http://192.168.1.50:8000/api/location` (la IP del PC, no `localhost`: el teléfono es otro equipo). |
| `api_key` | texto | no | `clave-larga-aleatoria` | Solo si el backend define `TELEMETRY_API_KEY`. Se envía en la cabecera `X-Api-Key` en cada lote. |

### Bloque `device` — quién es este teléfono

| Clave | Tipo | Obligatorio | Ejemplo | Qué es |
| --- | --- | --- | --- | --- |
| `device_id` | texto | no | `android-8f1c` | Identificador del equipo. **Si lo omites, la app genera uno** (`android-<hex>`) y lo conserva. Úsalo para fijarlo a mano (por ejemplo `moto-reparto-3`). Admite letras, números, `-`, `_` y `.`; el resto se normaliza o se convierte en `-`. **No repitas un `device_id` entre teléfonos**: sus trazas se fusionarían en el visor. |
| `label` | texto | no | `Moto de reparto 3` | Nombre que se ve en el visor web. Por defecto, el modelo del teléfono. |

### Bloque `tracking` — ritmo y arranque

| Clave | Tipo | Obligatorio | Ejemplo | Qué es |
| --- | --- | --- | --- | --- |
| `interval_seconds` | número | no | `5` | Intervalo mínimo entre envíos. Rango admitido 1–3600; fuera de rango se ajusta y se avisa. Más segundos = menos batería. También acepta `min_time_ms` (en milisegundos, como la app). |
| `distance_meters` | número | no | `5` | Distancia mínima para considerar un punto nuevo. `0` guarda todo. Máximo 10000 (a partir de ahí ya no es una traza). |
| `enabled` | booleano | no | `true` | Deja el rastreo **marcado como activo**. No arranca el servicio por sí solo: al abrir la app se reanuda (y el watchdog lo relanza tras un reinicio). Para un teléfono nuevo deja `false` y pulsa **Iniciar** una vez, o pon `true` y abre la app. |

### Bloque `github` — publicación directa sin servidor (opcional)

| Clave | Tipo | Obligatorio | Ejemplo | Qué es |
| --- | --- | --- | --- | --- |
| `token` | texto | no | `github_pat_…` | Token *fine-grained* con permiso `Contents: read and write` **sobre ese repositorio**. Sin token, el canal queda desactivado. |
| `repo` | texto | no | `usuario/mi-repo` | Repositorio destino, en formato `usuario/repositorio`. Si lo reparte en dos claves (`user` + `repo`), también lo entiende. |
| `user` | texto | no | `usuario` | Solo tiene sentido junto a un `repo` escrito **sin** la barra: se combinan en `usuario/repositorio`. Si el `repo` ya trae la barra, esta clave no se usa. |
| `branch` | texto | no | `main` | Rama donde se escribe el fichero. |
| `path` | texto | no | `data/latest.json` | Ruta del fichero dentro del repositorio. Es el que lee el visor en modo «GitHub Pages (fichero)». |

### Bloque `gist` — histórico sin repositorio (opcional)

| Clave | Tipo | Obligatorio | Ejemplo | Qué es |
| --- | --- | --- | --- | --- |
| `token` | texto | no | (vacío) | Token con permiso `Gists: read and write`. **Si se deja vacío se usa el mismo del bloque `github`**, que es lo normal. |
| `id` | texto | no | `e3541f64e…` | ID del Gist —o su URL completa, que se normaliza—. **Vacío = canal desactivado.** |
| `file` | texto | no | `data.json` | Nombre del fichero dentro del Gist. Por defecto `data.json`. |

> El canal Gist **lee antes de escribir** y fusiona por `timestamp_ms`, así que varios teléfonos
> pueden publicar en el mismo Gist sin pisarse el historial.

### Bloque `security` — código de la pantalla (opcional)

| Clave | Tipo | Obligatorio | Ejemplo | Qué es |
| --- | --- | --- | --- | --- |
| `pin` | texto | no | `"4821"` | Define o cambia el código de seguridad. De 4 a 12 **dígitos**. |
| `lock` | booleano | no | `false` | Con `false`, **quita** el código existente. `true` sin `pin` no hace nada (y se avisa). |

## 4 · El token de GitHub

1. Entra en **GitHub → Settings → Developer settings → Fine-grained tokens → Generate new token**.
2. **Repository access**: solo el repositorio donde se publicará el fichero.
3. **Permissions → Repository permissions → Contents: Read and write** (es el único permiso que hace
   falta para este canal; si además usarás el Gist, añade **Gists: Read and write**).
4. Copia el token (`github_pat_…`) y pégalo en el fichero o en la pantalla. **No se comparte**: el
   fichero de configuración lo lleva en claro, así que trátalo como una contraseña.
5. Caduca (90 días por defecto). Cuando venza, la app dirá `HTTP 401`: genera otro y sustitúyelo.

## 5 · El Gist

1. Entra en **gist.github.com** y crea un Gist **secreto** con un fichero llamado `data.json` y
   contenido `[]` (un array vacío: es la forma del contrato).
2. Copia el ID de la barra de direcciones (`gist.github.com/USUARIO/<ID>`) y ponlo en `gist.id`.
3. El mismo token del punto 4 sirve si le añadiste `Gists: Read and write`; si no, crea uno con ese
   permiso y ponlo en `gist.token`.
4. La URL que se pega en el visor web la muestra la propia app, en la tarjeta de estado y bajo el
   rótulo **«Gist raw (pegar en el visor)»**. También vale el propio ID: el visor acepta las dos cosas.

## 6 · Código de seguridad (PIN)

**Qué protege.** Solo la pantalla de configuración: nadie sin el código ve el endpoint, los tokens ni
el estado. **Qué no protege, a propósito:** el rastreo. El servicio en primer plano, el watchdog y el
receptor de arranque siguen funcionando con la interfaz bloqueada, así que un código olvidado no
puede detener el motor ni perder puntos.

**Cómo se guarda.** Nunca en claro: `SHA-256` del código junto a una sal aleatoria de 128 bits por
dispositivo. La comparación es en tiempo constante y tras **5 intentos fallidos** la pantalla queda
bloqueada **30 segundos** (con cuenta atrás).

**Cómo se pone.** En la app: **Seguridad (PIN)** → «Activar». O en el fichero:

> El código no es solo un candado: es la **llave de la protección contra desinstalación** (§10). Sin
> código, cualquiera con el teléfono en la mano apagaría la protección de un toque, así que activarla
> exige tenerlo definido.

```json
{ "security": { "pin": "4821" } }
```

**Cómo se quita.** Botón **Seguridad (PIN)** → «Quitar». O con `"security": { "lock": false }`.

> Con la **protección contra desinstalación activada** (§10) el código **no se puede quitar**, ni desde
> la app ni desde el fichero: es lo único que separa esa pantalla de quien coja el teléfono, y desde
> ella se apaga la protección. Solo se cambia por otro código. Para quedarte sin candado, apaga antes
> la protección; luego ya puedes quitar el código cuando quieras.

**Cerrar sin salir.** Tarjeta 7 → **Bloquear ahora**: vuelve a poner el candado sin cerrar la app, y la
pantalla de configuración —tokens incluidos— queda otra vez detrás del código. El rastreo no se toca.

**Si lo olvidas.** No hay puerta trasera —una backdoor anularía el candado—, así que la salida es
borrar los datos de la app, y la propia pantalla de bloqueo la explica entera con el botón
**«¿Código olvidado?»**. Qué pasa exactamente:

1. **El camino más corto es otro `config.json`**: deja en `Descargas/Telemetria/` un fichero con un
   código nuevo (`{ "security": { "pin": "4821" } }`). La app lo aplica sola al abrirse y entras con
   ese código, sin perder nada. **No es una puerta trasera**: escribir ese fichero exige acceso físico
   al teléfono —el mismo que hace falta para borrar los datos—, y el código protege la interfaz, no el
   aparato. Si el teléfono se pierde, lo que hay que proteger es la pantalla, y para eso está el
   bloqueo del propio Android.
2. **Nada deja de funcionar mientras tanto.** El candado protege la pantalla, no el motor: el servicio,
   el watchdog y el arranque del teléfono siguen capturando y publicando. Olvidar el código **no**
   cuesta puntos.
3. **El otro camino es borrar los datos**, y se pierde la configuración del teléfono, no lo publicado:
   borrar los datos (Ajustes → Aplicaciones → Telemetría → Almacenamiento → Borrar datos) se lleva el
   endpoint, los tokens y el código. Lo que ya está en el servidor, en el repositorio o en el Gist
   sigue ahí.
4. **Si la app llegó a ser propietaria del dispositivo, ese papel y el bloqueo de desinstalación
   sobreviven al borrado**, porque viven en el sistema y no en los datos de la app. Al abrirla los
   verás en la tarjeta 6: **«Bloqueo del sistema»** lo levanta desde el propio teléfono, y por ADB
   sigue estando `dpm remove-active-admin` (§10).
5. **Volver a como estaba son diez segundos** si exportaste el JSON antes: **Importar JSON** (§7).

Antes de nada, ten en cuenta que el bloqueo por intentos fallidos dura **30 segundos**: si el código
se cree recordar, esperar y volver a probar es el primer paso, no el último.

> **La hora del teléfono no sirve para saltárselo.** El bloqueo se cuenta con dos relojes —el de pared
> y el monótono del arranque, que no se puede tocar desde los ajustes— y toma el que más resta, con un
> recorte a 30 s pase lo que pase. Ni adelantar la hora lo abre al instante, ni atrasarla deja la
> pantalla bloqueada durante horas: lo primero sería un atajo para probar códigos, y lo segundo, un
> técnico o un cambio de hora dejándote fuera de tu propia app.

## 7 · Llevar la configuración a otro teléfono

1. En el teléfono de origen: **Exportar JSON** → guarda `telemetria-config.json` (por ejemplo en
   Descargas).
2. Pásalo al teléfono nuevo y, o bien renómbralo a `config.json` dentro de `Descargas/Telemetria/`
   (se aplica solo al abrir la app), o elige **Importar JSON**.
3. **Cambia `device_id`** (o quítalo para que la app genere otro): si compartes identificador, las dos
   trazas se mezclan en el visor.
4. El `security.pin` **no se exporta** —solo se indica si hay candado—, así que en el teléfono nuevo
   tendrás que volver a definirlo si lo quieres.

## 8 · Checklist de un teléfono nuevo

1. Instala el APK (`docs/SETUP.md`, paso 3).
2. Copia `config.json` a `Descargas/Telemetria/` y abre la app: verás el aviso «Configuración aplicada
   del fichero: N campos aplicados» (o el motivo por el que no se pudo). Si no aparece, importa el
   fichero con **Importar JSON** — ver la nota del apartado 2.
3. Concede los permisos de ubicación (**Precisa**; después, **Permitir siempre**).
4. Pulsa **Iniciar** (o deja `tracking.enabled` en `true` y reabre la app).
5. Activa los tres seguros: **Administrador**, **Accesibilidad**, **Batería** (y **Telefonía** si lo
   quieres en despliegues gestionados).
6. Comprueba en **Estado** que aparecen `servicio: ACTIVO`, el endpoint correcto y los envíos
   subiendo (`enviados`, `cola` a cero).
7. Si usas Gist o GitHub, verifica que `GitHub:` y `Gist:` digan «publicado» y no un error.
8. Define el **código de seguridad** y anota dónde lo guardaste.
9. Si el teléfono tiene que resistir un intento de desinstalación, activa la **protección** (§10): con
   el centinela bastan dos toques; la garantía real pide ser propietario del dispositivo.

## 9 · Problemas y soluciones

| Síntoma | Causa probable | Qué hacer |
| --- | --- | --- |
| «El fichero de configuración no se pudo aplicar» | JSON mal formado (una coma de más, comillas tipográficas) | Corrige el fichero; el aviso dice el motivo exacto. Vuelve a copiarlo: al cambiar el contenido se aplica otra vez. |
| El fichero está en su sitio pero no pasa nada | Ya se había aplicado ese mismo contenido | Es lo correcto: cambia algo del fichero (o borra la copia) si quieres forzar. |
| El fichero está, la app lo ignora y ni siquiera avisa | Android 10+ solo deja a cada app leer de Descargas sus propios ficheros: si lo copió un ordenador, la búsqueda automática puede no verlo | Usa **Importar JSON**; el selector del sistema no depende de ese permiso. |
| «ignorados: server.endpoint debe empezar por http:// o https://» | Falta el esquema en la URL | Añade `http://` o `https://`. |
| `HTTP 401` en GitHub o Gist | Token caducado, mal pegado o sin permiso | Genera otro con el permiso correcto (pasos 4 y 5) y vuelve a aplicarlo. |
| No aparecen puntos en la web | El rastreo no está iniciado, o el endpoint apunta a `localhost` | Pulsa **Iniciar** y comprueba que el endpoint usa la IP del servidor, no `localhost`. |
| La pantalla pide un código y no lo sé | Candado activo | Borra los datos de la app (Ajustes → Apps → Telemetría → Almacenamiento). El rastreo sigue. |
| Cambié el `device_id` y ahora hay dos dispositivos | Es el comportamiento correcto | Usa un identificador estable por teléfono; el visor agrupa por `device_id`. |

## 10 · Impedir la desinstalación

Hay **dos capas**, y conviene saber qué garantiza cada una:

| Capa | Qué impide | Qué necesita |
| --- | --- | --- |
| **Propietario del dispositivo** — la garantía real | El sistema **quita el botón de desinstalar** y la app no se puede desactivar. Es una regla de Android, no del programa. | Convertir la app en propietaria **una única vez**, en un teléfono recién restablecido y sin cuentas. |
| **Centinela de accesibilidad** — disuasión | Reconoce la pantalla de desinstalación o de desactivación del administrador y la cierra con «Atrás», lo avisa y lo registra. | El centinela activado en Ajustes → Accesibilidad **y** la protección encendida en la app. |

### Cómo se activa

1. Define primero el **código de seguridad** (§6): apagarla exige ese código.
2. Tarjeta **6 · Protección contra desinstalación** → **Activar / apagar**.
3. Para la garantía real, pulsa **Cómo activarlo**: la app muestra y copia el comando exacto de *este* teléfono, que es

   ```bash
   adb shell dpm set-device-owner com.example.telemetry/com.example.telemetry.TelemetryAdminReceiver
   ```

**Requisitos que pone Android, no la app**: teléfono recién restablecido de fábrica, **sin ninguna
cuenta añadida** y sin perfiles de trabajo. Con cuentas ya configuradas el comando falla: hay que
restablecer primero. Activarlo no borra nada ni impide usarlo con normalidad.

### Qué queda registrado

Cada intento interceptado y **cada desactivación del administrador** se anotan en
`Descargas/Telemetria/error.json`, y el contador se ve en la propia tarjeta de protección y en la de
estado. Bajar de categoría la protección es exactamente lo que interesa saber.

### Mantenimiento del equipo: qué pasa con cada acción

Ninguna de estas acciones deja el software en un estado roto, y lo que hace en cada una está decidido:

| Acción del sistema | Qué hace el software |
| --- | --- |
| **Reiniciar el teléfono** | El receptor de arranque rearma la alarma y relanza el rastreo si estaba activado. Queda anotado. |
| **Actualizar la app** | Igual: se rearma la alarma y se anota la actualización. |
| **Apagar el teléfono** | Se anota el apagado **con el recorrido del día**, que es el último dato útil de la jornada. |
| **Modo avión** | Se anota con su estado. Los puntos quedan en cola y se publican al recuperar la red. |
| **Parar forzosamente** | **El único hueco real**, y no se puede tapar: el sistema mata el proceso y no queda código que se ejecute, así que no hay nada que anotar. El rastreo se recupera al abrir la app o al reiniciar. Ninguna app puede impedir una parada forzosa. |
| **Borrar la caché** | Sin efecto alguno. |
| **Borrar los datos** | Se pierde la configuración guardada. El bloqueo del sistema y el papel de propietario **sobreviven** (viven en el sistema): la tarjeta 6 los detecta y los levanta. |
| **Apagar la ubicación o revocar el permiso** | Se anota **una vez** por episodio, con el momento en que empezó. El rastreo sigue en pie y encolando. |
| **Cambiar la hora o la zona horaria** | No afecta al bloqueo (dos relojes) ni al día a día: cada punto lleva su propio instante y el día es el natural local. |
| **Modo seguro** | El sistema arranca sin apps de terceros: el rastreo está parado mientras dure. Es una de las vías documentadas para recuperar el control. |
| **Depuración USB / ADB** | `adb uninstall` no puede desinstalar una app **propietaria del dispositivo**; `adb shell dpm remove-active-admin` sí puede quitarla, y por eso está documentado. |
| **Restablecimiento de fábrica** | Borra todo. Es la salida inevitable y está dicha desde el principio. |

### Cadena de custodia: lo que queda registrado

`Descargas/Telemetria/error.json` guarda, además de los fallos, los hitos que deja detrás un robo o
una manipulación: **el apagado del teléfono** (con el recorrido de ese día), **el modo avión** con su
estado, **el reinicio** y **la actualización de la app**, la **desactivación del administrador**, el
**centinela apagado**, **cada intento de desinstalación interceptado** y **la ubicación apagada o el
permiso revocado**. La protección se reaplica en cada señal del watchdog, así que bajar de categoría
cualquiera de esas capas no pasa inadvertido.

**Lo que esto no es**, y conviene decirlo sin adornos: es un **registro**, no una alarma. No envía
nada a ningún sitio por sí solo —viaja dentro de la app, y el diagnóstico se exporta a mano—, la
parada forzosa no se puede anotar, y nadie con un ordenador y el teléfono en la mano está detenido por
estas capas: lo que hay es una traza de lo ocurrido y una desinstalación difícil, no una criptografía.
Si el teléfono se pierde, lo que protege de verdad los datos es el bloqueo de pantalla del propio
Android, y el rastreo sirve para saber por dónde ha estado.

### La salida de emergencia (léela antes de activarlo)

Nada de esto es irreversible, y es a propósito: un candado sin salida documentada deja al dueño fuera
de su propio teléfono.

- **Desde la app**: tarjeta 6 → *Activar / apagar*, con el código de seguridad.
- **Bloqueo del sistema heredado**: el bloqueo del sistema **sobrevive a borrar los datos de la app**
  —es el camino de salida cuando se olvida el código (§6)—, así que puede quedar puesto con la
  protección ya apagada. La tarjeta 6 lo detecta y **«Bloqueo del sistema»** lo levanta desde el
  propio teléfono; pide confirmación antes de hacerlo y no se puede usar con la protección activada
  (se volvería a aplicar sola en el siguiente latido: para eso está *Activar / apagar*).
- **Quitar el propietario por ADB**:

  ```bash
  adb shell dpm remove-active-admin com.example.telemetry/com.example.telemetry.TelemetryAdminReceiver
  ```

- **Modo seguro** (arranca sin servicios de terceros) y **restablecimiento de fábrica**: siempre ganan.
  Ninguna aplicación puede impedir un restablecimiento, y estas capas no lo intentan.

### Lo que esta protección **no** hace

No es un MDM completo: no impide el restablecimiento ni el modo seguro, y no resiste a alguien con
conocimientos y un ordenador. Lo que sí consigue es que la desinstalación accidental o de un toque no
ocurra, y que cualquier intento quede registrado.

---

**Nada de esto hace falta si solo quieres una prueba rápida**: abre la app, escribe la URL del
servidor, pulsa Guardar e Iniciar. La configuración por fichero existe para hacerlo una vez y
repetirlo en tantos teléfonos como haga falta sin equivocarse en un campo.
