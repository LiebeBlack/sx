#!/usr/bin/env bash
# Tareas habituales del proyecto. Uso: scripts/dev.sh <comando>
#   install    instala las dependencias del backend
#   run        arranca el servidor (sirve también el visualizador en /)
#   test       ejecuta los tests y valida el JavaScript del frontend
#   simulate   genera tráfico sintético (--noisy para probar los filtros)
#   apk        compila el APK de depuración
#   lint       compila en modo lint del backend y valida el frontend
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON="${PYTHON:-python}"

case "${1:-help}" in
  install)
    "$PYTHON" -m pip install -r "$ROOT/backend/requirements.txt"
    ;;
  run)
    cd "$ROOT/backend" && "$PYTHON" app.py
    ;;
  test)
    cd "$ROOT" && "$PYTHON" -m compileall -q backend tools tests
    "$PYTHON" -m unittest discover -s tests -v
    "$PYTHON" tools/check_frontend.py
    ;;
  simulate)
    shift || true
    "$PYTHON" "$ROOT/tools/simulate.py" --url "${TELEMETRY_URL:-http://127.0.0.1:8000/api/location}" "$@"
    ;;
  apk)
    cd "$ROOT/android"
    if [ ! -x ./gradlew ]; then
      command -v gradle >/dev/null 2>&1 || { echo "instala Gradle 8.7+ o abre el proyecto en Android Studio"; exit 1; }
      gradle wrapper --gradle-version 8.7
    fi
    ./gradlew assembleDebug
    echo "APK: android/app/build/outputs/apk/debug/app-debug.apk"
    ;;
  lint)
    cd "$ROOT" && "$PYTHON" -m compileall -q backend tools tests && "$PYTHON" tools/check_frontend.py
    cd "$ROOT/android" && [ -x ./gradlew ] && ./gradlew lintDebug || echo "sin wrapper de Gradle: se omite lintDebug"
    ;;
  *)
    sed -n '2,10p' "${BASH_SOURCE[0]}"
    ;;
esac
