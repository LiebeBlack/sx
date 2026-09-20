# Puesta en marcha de verdad (producción)

Guía completa para dejar el sistema funcionando **con la información llegando a la web de
GitHub Pages**, en los dos modos posibles:

| | **Modo GitHub directo** | **Modo con backend** |
|---|---|---|
| ¿Necesita servidor? | **No.** El móvil publica a GitHub por Wi-Fi o datos móviles | Sí (VPS, NAS, LAN o Docker) |
| Latencia de la web | ~60 s (rate limit de la Contents API) | 1–10 s (API en vivo) o ~60 s (espejo) |
| Qué escribe el fichero | Cada app Android (`GithubPublisher.java`) | El espejo del servidor (`github_mirror.py`) |
| Ideal para | Demo táctica, sin infraestructura | Seguimiento real de varios dispositivos |

Ambos modos terminan en el mismo sitio: un JSON en `data/latest.json` del repositorio, servido
estáticamente por GitHub Pages y leído por el visualizador sin backend y sin CORS. Con backend,
además, puedes ver la API en vivo con latencia de segundos.

**Tiempo total del modo directo: ~10 minutos.** Lo único que requiere tu cuenta (no código) es:
crear el repositorio, crear un fine-grained token y activar Pages.

---

## Paso 0 · Requisitos

- Una cuenta de GitHub.
- Un móvil Android 7.0+ (API 24+) con el APK instalado
  (`cd android && ./gradlew assembleDebug`, ver README §3) — o el APK que ya tengas compilado.
- Solo para el modo con backend: Python 3.10+ o Docker, y una máquina siempre encendida.

**Decisión previa — repo público o privado:**

> ⚠️ Lo que publiques en `data/latest.json` es **ubicación en tiempo casi real**. Si el repo es
> público, esa ubicación es visible para cualquiera con el enlace. Para uso real usa un repo
> **privado**: GitHub Pages funciona igual, pero requiere un plan de pago (GitHub Pro o superior)
> para servirse desde repos privados. Para pruebas usa el modo privado de datos: publica con
> coordenadas de prueba o desactiva la app al terminar.

---

## Paso 1 · Crear el repositorio y subir el código

1. En GitHub: **New repository** → nombre (p. ej. `telemetria`) → visibilidad según lo decidido
   arriba → **Create** (sin README, ya lo tienes).
2. Desde tu máquina, dentro de este proyecto:

```bash
git remote add origin https://github.com/TU_USUARIO/telemetria.git
git push -u origin main
```

3. (Opcional) Activa la pestaña **Actions** si la CI pregunta; el flujo `.github/workflows/ci.yml`
   ejecuta los tests del backend y compila el APK en cada push, con artefacto descargable.

---

## Paso 2 · Crear el fine-grained token (solo `Contents: read and write`)

El token es la credencial con la que la app (o el espejo del backend) escribe
`data/latest.json` en tu repo. Debe tener **el mínimo privilegio posible**:

1. GitHub → clic en tu avatar → **Settings** → barra lateral, abajo: **Developer settings**.
2. **Personal access tokens → Fine-grained tokens** → **Generate new token**.
   (Usa *fine-grained*, **no** classic: los classic conceden permisos a *todos* tus repos.)
3. Rellena:
   - **Token name**: `telemetria-publisher`
   - **Expiration**: 90 días (o el máximo; se renueva regenerándolo, ver Paso 8).
   - **Resource owner**: tu usuario.
   - **Repository access**: **Only select repositories** → elige `telemetria`.
4. En **Permissions → Repository permissions**:
   - **Contents** → **Read and write** ← el único permiso que necesita el sistema.
   - Todo lo demás debe quedar en **No access** (Metadata se marca sola en lectura; es obligatorio).
5. **Generate token** y copia el valor (`github_pat_…`) **ahora**: no se vuelve a mostrar.

Ese token solo permite leer y escribir ficheros de ese único repo. Si se filtra, el daño está
acotado a ese repositorio y se revoca en segundos (Paso 8).

---

## Paso 3 · Configurar la app Android («GitHub directo»)

1. Abre la app. En **Ajustes**, concede en este orden: ubicación en uso → notificaciones →
   **Permitir siempre** (ubicación en segundo plano). Opcional pero recomendado para que
   sobreviva a todo: **Batería** (exención de optimizaciones), **Administrador** y
   **Accesibilidad** (capas de persistencia, ver README §3). El botón **Telefonía** es opcional y
   solo hace falta si quieres ampliar el detalle de telefonía en despliegues preotorgados por MDM:
   el enriquecimiento de celdas funciona sin él.
2. En la sección **GitHub directo** de la pantalla principal:
   - **Token**: pega el `github_pat_…` del Paso 2.
   - **Repo**: `TU_USUARIO/telemetria` (usuario y repo separados por `/`, sin `https://`).
   - **Rama**: `main`. **Ruta**: `data/latest.json` (por defecto; déjala salvo que sepas lo que haces).
3. **Guardar** y luego **Iniciar rastreo**. El estado de publicación aparece en el panel
   (`publicado · hace 45 s`) y como mucho se publica cada 60 s por dispositivo — la app fusiona
   por `device_id` con reintentos automáticos ante conflicto (sha obsoleto), así que varios
   móviles pueden publicar al mismo repo sin pisarse.
4. Sin backend, por Wi-Fi o por datos móviles, da igual: la app habla directamente con
   `api.github.com`.

**Verifica** en github.com: el fichero `data/latest.json` debe existir en el repo con tus
coordenadas (se crea en la primera publicación, ~1 min tras iniciar el rastreo).

---

## Paso 4 · Activar GitHub Pages (Deploy from branch)

1. En el repo: **Settings → Pages** (barra lateral).
2. En **Build and deployment → Source**: **Deploy from a branch**.
3. **Branch**: `main` y la carpeta de publicación → **Save**. **Las dos funcionan y las dos
   publican el mapa**, porque el visor vive en `docs/`, la carpeta que se publica en uno de los dos
   casos y una subcarpeta en el otro:

   - **`/docs`** — *recomendada*: es la más simple de razonar, porque lo publicado y lo que hay en
     `docs/` son exactamente lo mismo. La raíz sirve la portada (`docs/index.html`), el mapa queda en
     `…/mapa.html`, el visor del Gist en `…/gist.html`, el lector de documentos en `…/doc.html` y el
     fichero de datos en `…/data/latest.json`.
   - **`/ (root)`** — publica todo el repositorio. La raíz la sirve el `index.html` de la raíz, que
     redirige al visor; el mapa queda en `…/docs/mapa.html`, la documentación en `…/docs/doc.html` y
     el fichero de datos en `…/docs/data/latest.json`. El visor prueba las dos ubicaciones del
     fichero, así que **la misma configuración del móvil vale para las dos carpetas**.

> **Por qué el visor está dentro de `docs/`.** Antes vivía en `frontend/`, y con la carpeta `/docs`
> —la que más gente usa— el sitio publicado no tenía visor: se servía la documentación y el mapa solo
> se veía desde el backend. Moverlo a `docs/` hace que el mapa se publique con las dos
> configuraciones. La contrapartida: **el fichero de datos también tiene que estar dentro de `docs/`**
> (`docs/data/latest.json`, que es el valor por defecto), porque un fichero en la raíz del repositorio
> solo lo sirve Pages si publicas `/ (root)`.
4. Espera ~1 minuto. En **Settings → Pages** aparecerá la URL pública:
   `https://TU_USUARIO.github.io/telemetria/`.

> **Repo privado**: si Settings → Pages no ofrece tu rama o el despliegue falla con
> "private repository", tu plan no incluye Pages privado — necesitas GitHub Pro, o hacer el repo
> público asumiendo que la ubicación publicada es pública.

---

## Paso 5 · Abrir la web y elegir la fuente GitHub

1. Abre `https://TU_USUARIO.github.io/telemetria/`. Con la carpeta `/docs` verás la portada del
   sitio, y el **mapa en `…/mapa.html`** (enlazado desde la portada). Con `/ (root)` la raíz redirige
   directamente al visor. En las dos, `doc.html` sirve la documentación y `gist.html` el visor
   autónomo del Gist.
2. En el desplegable de fuente, cambia **API en vivo** por **GitHub Pages (fichero)**.
   - Desde el propio dominio de Pages la ruta por defecto ya es correcta: deduce la base del
     repositorio de la URL actual y prueba el fichero en las dos ubicaciones posibles
     (`/<repo>/docs/data/latest.json` y `/<repo>/data/latest.json`). Cero configuración, cero CORS.
   - Para leer **otro** repo desde esta web: escribe `https://OTRO_USUARIO.github.io/otro_repo`
     en el campo **API** y mantén la fuente GitHub — el visualizador añadirá la ruta del fichero
     automáticamente.
3. Comprueba la píldora de estado: `Pages · N disp. · generado HH:MM:SS`. Los dispositivos
   aparecen con su traza, panel lateral (velocidad, batería, actividad, satélites, Wi-Fi, celdas)
   y se refrescan cada 3 s leyendo el fichero (con *cache-buster* para saltarse la caché de Pages).
4. La fuente elegida queda guardada en `localStorage`: las siguientes visitas ya abren en GitHub.
5. Si los datos se ven viejos («generado» lleva minutos sin cambiar): la app dejó de publicar
   (revisa Paso 3) o Pages está sirviendo caché (recarga con Ctrl+F5; el propio visualizador ya
   manda `cache: no-store`).

---

## Paso 6 · Modo con backend (producción en tiempo real + espejo)

Con el backend en marcha tienes **además** la API en vivo (1–10 s) y el **espejo**: el servidor
consolida en el mismo `data/latest.json` todos los dispositivos — tanto los que le llegan por la
API como los que publican directo a GitHub — con rate limit, diff mínimo y tolerancia a conflictos.

### Opción A · Docker (recomendado)

1. Crea un fichero **`.env`** junto a `docker-compose.yml` (no se sube al repo, está en `.gitignore`):

```dotenv
TELEMETRY_API_KEY=una-clave-larga-que-inventes
GITHUB_REPO=TU_USUARIO/telemetria
GITHUB_TOKEN=github_pat_XXXXXXXXXXXXXXXXXXXX
GITHUB_BRANCH=main
GITHUB_PATH=data/latest.json
GITHUB_MIN_INTERVAL=60
```

2. Arranca:

```bash
docker compose up -d --build
```

3. Comprueba que el espejo quedó activo:

```bash
curl -s http://127.0.0.1:8000/api/health | python -m json.tool | grep -A4 github_mirror
# "enabled": true, "last_status": "sin publicar" → pasará a "publicado (N dispositivos)"
```

Variables de entorno del espejo (ya declaradas en `docker-compose.yml`, con valores por defecto):

| Variable | Defecto | Qué hace |
|---|---|---|
| `GITHUB_REPO` | *(vacío = espejo desactivado)* | `usuario/repo` destino de la publicación |
| `GITHUB_TOKEN` | *(vacío)* | fine-grained token del Paso 2 (solo `Contents: read and write`) |
| `GITHUB_BRANCH` | `main` | rama donde se escribe el fichero |
| `GITHUB_PATH` | `data/latest.json` | ruta del fichero dentro del repo |
| `GITHUB_MIN_INTERVAL` | `60` | segundos mínimos entre publicaciones (rate limit propio; la API de GitHub permite 5000/h) |

### Opción B · Sin Docker

```bash
cd backend
pip install -r requirements.txt
export GITHUB_REPO="TU_USUARIO/telemetria"
export GITHUB_TOKEN="github_pat_…"
python app.py            # producción: gunicorn -w 1 -b 0.0.0.0:8000 app:app
```

> Un solo worker: el estado es en memoria (+ JSONL en disco). Con gunicorn usa `-w 1`.

### Consumo desde la web

- **API en vivo** (tiempo real): campo **API** = `http://IP-DEL-SERVIDOR:8000` (o tu dominio) y
  fuente **API en vivo**. Si pusiste `TELEMETRY_API_KEY`, pégala en el campo **Clave API**.
- **GitHub Pages (fichero)**: igual que el Paso 5 — ahora el fichero lo escribe el espejo y
  contiene *todos* los dispositivos, incluidos los que publican directo.
- En producción real expón la API tras HTTPS (Caddy/Nginx/Traefik). La API acepta `X-Api-Key`
  y no tiene más control de acceso: no la publiques abierta a Internet sin clave.

---

## Paso 7 · Verificación end-to-end (checklist)

- [ ] `data/latest.json` existe en el repo y cambia cada ~60 s (botón **History** del fichero).
- [ ] La app muestra el estado de publicación actualizado (`publicado · hace N s`).
- [ ] La web en Pages muestra los dispositivos con traza y panel; píldora `Pages · N disp.`.
- [ ] (Con backend) `curl http://SERVIDOR/api/health` → `"github_mirror": {"enabled": true, …}` y
      `last_status: "publicado (N dispositivos)"`.
- [ ] (Con backend) La web en fuente **API en vivo** refresca en segundos.
- [ ] CI en verde en la pestaña **Actions** (tests + APK).

---

## Solución de problemas

| Síntoma | Causa probable | Solución |
|---|---|---|
| La web da **404** al leer el fichero | Pages sin activar, o aún no se ha publicado nunca | Paso 4; publica un primer punto desde la app (Paso 3) y espera 1 min |
| `HTTP 401` o `403` en la app | Token mal pegado, expirado o sin permiso `Contents` | Regenera el token (Paso 2/8) y vuelve a pegarlo en la app |
| `HTTP 409/422` persistente | Conflicto de sha tras varios reintentos (publicaciones simultáneas muy agresivas) | Es transitorio: el siguiente ciclo lo resuelve; si no, sube `GITHUB_MIN_INTERVAL` |
| Datos siempre viejos en la web | Caché de Pages o la app dejó de publicar | Recarga dura (Ctrl+F5); revisa el estado en la app y el History del fichero |
| Pages no disponible en repo privado | Plan gratuito | GitHub Pro, o repo público (⚠️ ubicación pública) |
| El espejo no publica | Faltan `GITHUB_REPO`/`GITHUB_TOKEN`, o el snapshot está vacío | Revisa `/api/health → github_mirror.last_error` y los logs del contenedor |
| La app no publica por datos móviles | Sin red o token sin guardar | Verifica conexión; el estado de la app muestra el último error HTTP |
| Dispositivos que «desaparecen» de la web | Puntos > 2000 por dispositivo se recortan en el fichero | Normal: el fichero mantiene la ventana reciente (2000 puntos/dispositivo) |

---

## Paso 8 · Mantenimiento del token y límites

- **Expiración**: el fine-grained token caduca (90 días por defecto). Antes de que venza:
  genera uno nuevo, pégalo en la app (y en el `.env` del backend si usas espejo) y **revoca el
  antiguo**. GitHub además te avisa por email y el estado de la app mostrará `HTTP 401`.
- **Si sospechas fuga**: Settings → Developer settings → Fine-grained tokens → **Revoke**.
  El sistema solo pierde la capacidad de escribir un fichero en un repo; nada más.
- **Rotación de clave de la API**: cambia `TELEMETRY_API_KEY` en el `.env` y en los clientes;
  `docker compose up -d` para aplicar.
- **Límites reales**: Contents API ≈ 5000 req/h por token → con publicación cada 60 s por
  dispositivo hay margen para decenas de móviles. Pages (repo público) tiene un límite blando de
  ~100 GB/mes de ancho de banda: sobrado para un visualizador que lee un JSON de unos cientos de KB.

---

## Modo Gist en cinco pasos (sin servidor y sin repositorio)

1. Crea un **gist secreto** (gist.github.com → *Create secret gist*) con un fichero `data.json` que
   contenga `[]`. Copia el **ID** de la URL (el tramo largo al final).
2. Crea un token con permiso **`Gists: read and write`** (fine-grained) o usa uno clásico con
   `gist`; si compartes token con el modo repositorio, basta `Contents` + `Gists`.
3. En la app, sección **GitHub Gist**: pega el token en el campo de token de arriba, el **ID** (o la
   URL completa del gist, se normaliza sola) y deja `data.json`. **Guardar** e **Iniciar**.
4. Publica `frontend/gist.html` donde quieras (o ábrelo en local): sondea cada 3 s la URL cruda del
   gist y dibuja el histórico, con la tabla de puntos y el diagnóstico del enlace. La URL se puede
   cambiar en el propio visor y queda guardada. El **visor principal** lee el mismo Gist: elige la
   fuente «Gist (histórico)» y pega el ID, o entra directamente con
   `…/frontend/?feed=gist&gist=<id>` — así el mapa, el radar y las dos trazas funcionan también sin
   backend ni repositorio.
5. Comprueba en el panel de la app que aparece `Gist: ok · N/500 puntos …` y `WorkManager (gist)`.
   Si sale `pendientes`, hay red de por medio: el ciclo siguiente manda todo lo acumulado.

**Nunca pegues el token en el visor ni en el repositorio**: la lectura del gist crudo no necesita
autenticación, así que la web no lleva credenciales. Si un token se expone, revócalo y crea otro.

---

## Si algo falla: `error.json`

La app escribe sola sus fallos en **`Descargas/Telemetria/error.json`**. En Android 10 o superior no
hace falta hacer nada más; en Android 9 y anteriores la carpeta pública necesita el permiso de
almacenamiento, que se pide junto a los demás (y que el botón **Diagnóstico** vuelve a solicitar si se
denegó). El panel de la pantalla principal muestra la ruta exacta, el recuento de incidentes y los
repetidos agrupados. Para extraerlo desde un ordenador:

```bash
adb pull /sdcard/Download/Telemetria/error.json
```

El botón **Diagnóstico** añade `diagnostico.json` en la misma carpeta con el estado completo del
sistema (permisos, cola, canal Gist, reintentos del watchdog) para cuando todavía no hay ningún
error registrado. Si la carpeta pública no está disponible, ambos ficheros quedan en
`Android/data/com.example.telemetry/files/Telemetria/` y la app lo indica en el panel.

---

## Resumen en una imagen

```
móvil(s) Android ──POST /api/location──▶ backend (Flask, Kalman + integridad) ─┐
      │                                     │                                   ├── data/latest.json
      ├── Contents API (Wi-Fi o datos) ─────┴── espejo github_mirror.py ────────┘         │
      │                                                                    GitHub Pages ◀─┘
      └── Gists API (GET + PATCH) ──▶ gist (histórico) ──raw_url──▶ gist.html (3 s)
```

Referencias cruzadas: `docs/API.md` (endpoints del backend), `docs/CONFIGURACION.md` (rellenar la
configuración del móvil y cargarla de una vez con un JSON), `README.md` §«Tres modos de
producción» y §2–3 (frontend y app), `backend/github_mirror.py` (espejo),
`android/.../GithubPublisher.java` (publicación directa).
