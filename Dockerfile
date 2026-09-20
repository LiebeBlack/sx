# Backend + visualizador estático en una sola imagen Alpine (sin base de datos externa).
#
# Dos etapas: la primera compila las dependencias (por si alguna no trae wheel musl), la segunda
# se queda solo con las ruedas ya construidas. El resultado son ~60 MB frente a los ~450 MB de
# una imagen de Python basada en Debian, y arranca más rápido.

# --- Etapa 1: ruedas --------------------------------------------------------------------------
FROM python:3.12-alpine AS builder

ENV PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_ROOT_USER_ACTION=ignore

# build-base aporta gcc/musl-dev/make: necesario solo si algún paquete no publica wheel musllinux.
RUN apk add --no-cache --virtual .build-deps build-base libffi-dev

WORKDIR /wheels
COPY backend/requirements.txt /wheels/requirements.txt
RUN pip install --upgrade pip setuptools wheel \
    && pip wheel --wheel-dir /wheels -r /wheels/requirements.txt

# --- Etapa 2: imagen final --------------------------------------------------------------------
FROM python:3.12-alpine

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_ROOT_USER_ACTION=ignore \
    PORT=8000 \
    TELEMETRY_STORE=/data/telemetry_store.jsonl

WORKDIR /app

# Instalación offline desde las ruedas: sin compiladores ni toolchain en la imagen final.
COPY --from=builder /wheels /wheels
RUN pip install --no-index --find-links=/wheels -r /wheels/requirements.txt \
    && rm -rf /wheels

COPY backend /app/backend
COPY frontend /app/frontend

# Usuario sin privilegios (uid fijo) y almacén escribible en el volumen /data.
RUN adduser -D -u 10001 -h /home/telemetry -s /sbin/nologin telemetry \
    && mkdir -p /data \
    && chown -R telemetry:telemetry /data /app
USER telemetry

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8000/api/health', timeout=2)"

# Un solo worker: el estado vive en memoria (varios procesos tendrían estados distintos).
# --graceful-timeout da margen al cierre para que el atexit compacte el JSONL sin perder puntos.
CMD ["gunicorn", "--chdir", "/app/backend", "-w", "1", "--threads", "8", "-b", "0.0.0.0:8000", \
     "--access-logfile", "-", "--graceful-timeout", "20", "--timeout", "60", "app:app"]
