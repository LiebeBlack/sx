# Contribuir

Gracias por el interés. Este proyecto captura datos de ubicación, así que la revisión prioriza
privacidad, consumo de batería y corrección de los filtros.

## Entorno

```bash
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r backend/requirements.txt
python tools/simulate.py --devices 1 --interval 2 --noisy
```

Para el APK: JDK 17, Android SDK 34 y `cd android && gradle wrapper --gradle-version 8.7`.

## Antes de enviar cambios

```bash
python -m compileall -q backend tools tests
python -m unittest discover -s tests -v
python tools/check_frontend.py
cd android && ./gradlew assembleDebug
```

Los cuatro tienen que pasar. La CI ejecuta exactamente lo mismo.

## Estilo

- Python: módulos pequeños, funciones descritas con una línea, `from __future__ import annotations`.
  Sin dependencias nuevas salvo que sean imprescindibles y estén justificadas.
- Java: sin AndroidX; solo SDK. Todo acceso a sensores, red o servicios va entre `try/catch`
  porque los ROM y los permisos revocados en caliente no avisan.
- Frontend: Vanilla JS sin frameworks. Nada de `innerHTML` con datos sin escapar (`esc()`).
- Comentarios solo donde explican una decisión difícil (filtros, redondeos, límites de API).

## Datos sensibles

No subas nunca `telemetry_store.jsonl`, `keystore.properties`, `*.jks` ni capturas con
coordenadas reales. Están en `.gitignore`; si se te escapa alguno, no abras un PR con él
descrito, avísame por un canal privado.

## Pull requests

Explica el problema, el enfoque, cómo lo has verificado y el efecto en batería o precisión.
Si cambias el formato del payload, actualiza `docs/API.md`, `README.md` y `tools/simulate.py`
en el mismo PR (el simulador es la referencia ejecutable del esquema).
