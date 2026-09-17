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
| `GET` | `/` | no | visualizador estático (si existe `frontend/`) |

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
        "heading_magnetic": 141.0, "steps": 8421, "activity": "walking",
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
    "points": [[1758100012000, 40.4168, -3.7038, 4.2, 137.0, 6.5]],
    "smooth": [[1758100012000, 40.41682, -3.70379, 4.2]]
  }]
}
```

`points` en modo `compact` es `[timestamp, lat, lng, speed_mps, bearing, accuracy]`;
`smooth` es `[timestamp, lat, lng, speed_mps]` con la traza filtrada por Kalman.

## Borrar y diagnosticar

```bash
curl -sS -X DELETE -H 'X-Api-Key: mi-clave' http://127.0.0.1:8000/api/locations/android-8f1c
curl -sS http://127.0.0.1:8000/api/health
```

```json
{"ok": true, "status": "up", "server_time": 1758100012000, "devices": 2, "points": 512,
 "storage": "/data/telemetry_store.jsonl", "auth": true, "max_points_per_device": 5000}
```

## Códigos de estado

| Código | Cuándo |
|---|---|
| `200` | lote procesado sin puntos aceptados (duplicados o inválidos): mira `errors` |
| `201` | al menos un punto almacenado |
| `400` | cuerpo ausente, vacío o JSON ilegible |
| `401` | falta `X-Api-Key` o no coincide |
| `404` | dispositivo desconocido |
| `413` | lote de más de 500 puntos o cuerpo mayor de 128 KB |
| `500` | error interno (se registra en el log del servidor) |
