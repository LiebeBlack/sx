# Contribuir

Gracias por el interés. Este proyecto captura datos de ubicación, así que la revisión prioriza
privacidad, consumo de batería y corrección de los filtros.

## Entorno

```bash
python -m venv .venv && . .venv/bin/activate      # Windows: .venv\Scripts\activate
pip install -r backend/requirements.txt
python tools/simulate.py --devices 1 --interval 2 --noisy
```

Para el APK: **JDK 17, Android SDK 35** (compileSdk y targetSdk 35, minSdk 24) y Gradle **8.9**, que
es el mínimo que exige AGP 8.7.3:

```bash
cd android && gradle wrapper --gradle-version 8.9 && ./gradlew assembleDebug
```

## Antes de enviar cambios

```bash
python -m compileall -q backend tools tests
python -m unittest discover -s tests -v          # incluye el contrato del canal Gist
python tools/check_frontend.py                   # sintaxis del JS de los dos visores
python tools/gist_schema.py data.json            # opcional: valida un documento real
cd android && ./gradlew assembleDebug assembleRelease
```

Los cinco tienen que pasar. La CI ejecuta lo mismo (los cuatro primeros en Python 3.10 y 3.12, y el
APK en debug **y** release, comprobando después con `grep` sobre el dex que los componentes que el
sistema instancia por nombre sobrevivieron a R8).

## Estilo

- Python: módulos pequeños, funciones descritas con una línea, `from __future__ import annotations`.
  Sin dependencias nuevas salvo que sean imprescindibles y estén justificadas.
- Java: SDK puro, con **dos dependencias opcionales en tiempo de ejecución** que se comprueban antes
  de usarse (`play-services-location` para el motor fusionado de Google y `work-runtime` para la
  segunda vía de persistencia); si no están, el sistema sigue funcionando con el SDK. Todo acceso a
  sensores, red, disco o servicios va entre `try/catch`, porque los ROM y los permisos revocados en
  caliente no avisan.
- Frontend: Vanilla JS sin frameworks. Nada de `innerHTML` con datos sin escapar (`esc()`). Si un
  visor escribe en un `<canvas>`, se limpia y se repinta en cada ciclo: no se acumula estado.
- Nada de trabajo de disco en el hilo principal: los callbacks de ubicación corren ahí, y una
  consulta a SQLite o una escritura de fichero se nota. Los contadores que pinta la interfaz se leen
  de memoria.
- Comentarios solo donde explican una decisión difícil (filtros, redondeos, límites de API).

## Datos sensibles

No subas nunca `telemetry_store.jsonl`, `keystore.properties`, `*.jks` ni capturas con coordenadas
reales. Están en `.gitignore`; si se te escapa alguno, no abras un PR con él descrito, avísame por un
canal privado.

**Los tokens nunca van en el código.** El PAT de GitHub (repositorio o Gist) se configura en la app y
vive en `SharedPreferences`; `error.json` y `diagnostico.json` tampoco los escriben. Si un token
aparece en un commit, un issue o un chat, considéralo comprometido: revócalo y crea otro.

## Pull requests

Explica el problema, el enfoque, cómo lo has verificado y el efecto en batería o precisión.

Si cambias **el esquema del backend**, actualiza `docs/API.md`, `README.md` y `tools/simulate.py` en
el mismo PR (el simulador es la referencia ejecutable de ese esquema).

Si cambias **el contrato del canal Gist**, actualiza en el mismo PR las cuatro piezas que lo
comparten: `GistPublisher.java` (el que publica), `tools/gist_schema.py` (la referencia ejecutable),
`tests/test_gist_schema.py` (las reglas de fusión y recorte) y `frontend/gist.html` (el visor). Las
reglas que en el móvil no se pueden ejecutar desde CI se prueban ahí precisamente para que un cambio
de contrato falle antes de llegar a un dispositivo.
