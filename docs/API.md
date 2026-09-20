# API de telemetría

Base por defecto: `http://127.0.0.1:8000`. Todas las respuestas son JSON.
Si el backend define `TELEMETRY_API_KEY`, las escrituras requieren `X-Api-Key` (o `?key=`);
las lecturas siempre son abiertas.

| Método | Ruta | Auth | Descripción |
|---|---|---|---|
| `POST` | `/api/location` | sí | un punto o un lote `{"points":[…]}` (máx. 500) |
| `GET` | `/api/locations` | no | todos los dispositivos |
| `GET` | `/api/locations/<device_id>` | no | historial de un dispositivo |
| `DELETE` | `/api/locations/<device_id>` | sí | borra el dispositivo y sus puntos |
| `GET` | `/api/health` | no | estado del servidor |
| `GET` | `/` | no | visualizador estático (si existe `docs/`; sirve `mapa.html`) |

Parámetros de lectura: `format=compact` (puntos como arrays), `limit=N` (últimos N),
`since=<ms>` (desde un instante), `latest=1` (solo última posición y resumen, sin historial),
`device_id=<id>` (filtra en el listado).

## Enviar una posición

```bash
curl -sS -X POST http://127.0.0.1:8000/api/location \
  -H 'Content-Type: application/json' \
  -H 'X-Api-Key: mi-clave' \
  -d '{
        "device_id": "android-8f1c",
        "label": "Pixel 7",
        "lat": 40.4168, "lng": -3.7038,
        "timestamp": 1758100000000,
        "elapsed_nanos": 480123456789,
        "accuracy": 6.5, "vertical_accuracy": 11.2,
        "speed_mps": 4.2, "speed_accuracy": 0.4,
        "bearing": 137.0, "bearing_accuracy": 5.0,
        "altitude": 651.0, "altitude_baro": 1002.4, "pressure_hpa": 936.1,
        "heading_magnetic": 141.0, "heading_true": 139.6, "declination_deg": -1.4,
        "steps": 8421, "activity": "walking",
        "battery": 78.5, "network": "wifi", "data_network": "5G",
        "operator": "MOVISTAR", "model": "Pixel 7", "sdk": 34, "mock": false,
        "provider": "fused", "source": "android",
        "gnss": {"visible": 18, "used": 9, "snr_best": 38.4, "snr_avg": 26.1,
                 "constellations": "GPS,GLONASS,GALILEO,BEIDOU"},
        "wifi": [{"ssid": "Cafe_WiFi", "bssid": "a4:2b:8c:11:22:01", "rssi": -71,
                  "frequency": 2437, "channel": 6, "connected": true}],
        "cell": [{"type": "LTE", "mcc": 214, "mnc": 7, "cid": 48213, "tac": 1101,
                  "rsrp": -95, "rsrq": -12, "dbm": -91, "level": 3, "registered": true}]
      }'
```

Respuesta:

```json
{"ok": true, "stored": 1, "rejected": 0, "errors": [], "devices": ["android-8f1c"], "server_time": 1758100001200}
```

`provider` y `source` son texto libre (máx. 24 caracteres) y sirven para auditar de dónde salió cada
punto: el cliente Android publica `gps`, `network`, `passive`, `fused` (motor del sistema),
`fused/gms` y `fused/gms/last` (motor fusionado de Google y su caché compartida) y los sufijos
`/last` del último punto conocido de cada proveedor. No hay lista cerrada: cualquier origen es válido.

Lote (lo que envía el cliente Android, hasta 40 puntos por petición):

```bash
curl -sS -X POST http://127.0.0.1:8000/api/location \
  -H 'Content-Type: application/json' \
  -d '{"points":[{"device_id":"a1","lat":40.4168,"lng":-3.7038,"timestamp":1758100000000},
                 {"device_id":"a1","lat":40.4170,"lng":-3.7035,"timestamp":1758100005000}]}'
```

Reglas de validación: `device_id` obligatorio (máx. 64), `lat` en [-90, 90], `lng` en [-180, 180],
`(0,0)` descartado, `timestamp` en segundos se normaliza a milisegundos y no puede ir más de 24 h
en el futuro. Los campos numéricos fuera de rango se descartan sin rechazar el punto; los guardas
de Wi-Fi y celda permitidos están en `WIFI_FIELDS` y `CELL_FIELDS` de `backend/app.py`.

Rumbo: se aceptan `heading_magnetic` (brújula), `heading_true` (norte verdadero) y `declination_deg`
(corrección aplicada). `smooth_lat`/`smooth_lng` se **ignoran** en la entrada: el servidor calcula su
propia traza filtrada, y solo se honran si vienen dentro de una fila compacta ya publicada.

Medidas adicionales que viajan en el punto:

| Campo | Contenido |
|---|---|
| `gnss.sats[]` | detalle por satélite: `sys`, `svid`, `cn0`, `used`, `elev`, `azim` (máx. 24) |
| `rtt[]` | distancias Wi-Fi 802.11mc: `bssid`, `distance_m`, `std_dev_m`, `rssi` (máx. 8) |
| `altitude_msl`, `altitude_msl_accuracy` | altitud geoidal (API 34); si existe, el servidor la prefiere a `altitude` para calibrar el barómetro |
| `complete` | el fix trae todos los campos (API 30) |

Los campos permitidos en esas listas están en `GNSS_SAT_FIELDS` y `RTT_FIELDS`; todo lo demás se
descarta sin rechazar el punto.

## Leer posiciones

```bash
curl -sS 'http://127.0.0.1:8000/api/locations?format=compact&limit=300'
curl -sS 'http://127.0.0.1:8000/api/locations/android-8f1c?since=1758100000000'
curl -sS 'http://127.0.0.1:8000/api/locations?latest=1'      # sondeo ligero sin historial
```

Forma de la respuesta (recortada):

```json
{
  "ok": true,
  "server_time": 1758100012000,
  "count": 1,
  "devices": [{
    "device_id": "android-8f1c",
    "summary": {
      "label": "Pixel 7", "points": 128, "dropped": 0, "outliers": 1, "low_quality": 3,
      "gaps": 0, "distance_m": 1840.22, "raw_distance_m": 2130.87, "noise_removed_m": 290.65,
      "avg_speed_mps": 3.41, "max_speed_mps": 12.9, "avg_accuracy_m": 8.42,
      "first_seen": 1758000000000, "last_seen": 1758100012000, "age_ms": 0,
      "online": true, "provider": "fused", "activity": "walking", "satellites": 9
    },
    "latest": { "lat": 40.4168, "lng": -3.7038, "smooth_lat": 40.41682, "smooth_lng": -3.70379,
                "altitude_fused": 651.0, "accuracy": 6.5, "speed_mps": 4.2, "...": "resto de campos" },
    "points": [[1758100012000, 40.4168, -3.7038, 4.2, 137.0, 6.5, 78.5, "walking", 40.41682, -3.70379]],
    "smooth": [[1758100012000, 40.41682, -3.70379, 4.2]]
  }]
}
```

`points` en modo `compact` es una fila de 10 columnas:

| # | Columna | Nota |
|---|---|---|
| 0 | `timestamp` | ms |
| 1-2 | `lat`, `lng` | medida bruta del dispositivo |
| 3-5 | `speed_mps`, `bearing`, `accuracy` | pueden ser `null` |
| 6-7 | `battery`, `activity` | pueden ser `null` |
| 8-9 | `smooth_lat`, `smooth_lng` | posición Kalman; pueden faltar si quien publicó no filtró |

Las 10 columnas son el mismo formato que escribe la app Android al publicar en GitHub (usa `-1.0`
como centinela para lo que no tiene), así que el visualizador lee igual un punto del servidor que uno
del móvil. Las 8 primeras son el formato histórico: cualquier lector que solo mire esas sigue
funcionando. `smooth` es `[timestamp, lat, lng, speed_mps]` con la traza filtrada por Kalman —en el
servidor o en el propio dispositivo— y omite los puntos sin filtro.

## Borrar y diagnosticar

```bash
curl -sS -X DELETE -H 'X-Api-Key: mi-clave' http://127.0.0.1:8000/api/locations/android-8f1c
curl -sS http://127.0.0.1:8000/api/health
```

```json
{"ok": true, "status": "up", "server_time": 1758100012000, "devices": 2, "points": 512,
 "storage": "/data/telemetry_store.jsonl", "auth": true, "max_points_per_device": 5000,
 "filter": {"max_speed_mps": 90.0, "max_jump_m": 150.0, "plausible_speed_mps": 60.0,
            "max_trusted_accuracy_m": 150.0, "max_gap_seconds": 120.0},
 "github_mirror": {"enabled": false, "last_status": "sin publicar", "last_error": null, "interval_s": 60.0}}
```

El bloque `filter` publica los umbrales realmente activos (se cambian por entorno, ver README), útil
para entender por qué un punto quedó marcado `outlier` o `low_quality`.

## Códigos de estado

| Código | Cuándo |
|---|---|
| `200` | lote procesado sin puntos aceptados (duplicados o inválidos): mira `errors` |
| `201` | al menos un punto almacenado |
| `400` | cuerpo ausente, vacío o JSON ilegible |
| `401` | falta `X-Api-Key` o no coincide |
| `404` | dispositivo desconocido en lectura o borrado |
| `413` | lote de más de 500 puntos o cuerpo mayor de 128 KB |
| `500` | error interno (se registra en el log del servidor) |
