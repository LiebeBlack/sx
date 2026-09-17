## Qué cambia

<!-- Una o dos frases: el problema y el enfoque. -->

## Componentes afectados

- [ ] Backend (`backend/app.py`)
- [ ] Visualizador (`frontend/index.html`)
- [ ] Cliente Android (`android/`)
- [ ] Simulador, tests, docs o CI

## Verificación

- [ ] `python -m unittest discover -s tests -v`
- [ ] `python tools/check_frontend.py`
- [ ] `cd android && ./gradlew assembleDebug`
- [ ] Probado contra el simulador (`--noisy` incluido si toca filtros)

## Impacto en precisión y batería

<!-- Si tocas el filtrado, el envío o los sensores, describe el efecto medido o razonado. -->

## Privacidad

- [ ] No se añaden datos personales nuevos ni se relaja la minimización
- [ ] `docs/API.md` y `tools/simulate.py` actualizados si cambia el esquema del payload
