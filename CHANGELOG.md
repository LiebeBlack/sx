# Historial de cambios

Formato basado en [Keep a Changelog](https://keepachangelog.com/es-ES/1.1.0/) y
[versionado semántico](https://semver.org/lang/es/).

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
