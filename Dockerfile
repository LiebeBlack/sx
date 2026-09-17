# Backend + visualizador estático en una sola imagen (sin base de datos externa).
FROM python:3.12-slim

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    PORT=8000 \
    TELEMETRY_STORE=/data/telemetry_store.jsonl

WORKDIR /app

COPY backend/requirements.txt /app/backend/requirements.txt
RUN pip install --no-cache-dir -r /app/backend/requirements.txt

COPY backend /app/backend
COPY frontend /app/frontend

RUN mkdir -p /data \
    && useradd --create-home --uid 10001 telemetry \
    && chown -R telemetry:telemetry /data /app
USER telemetry

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8000/api/health', timeout=2)"

# Un solo worker: el estado vive en memoria (varios procesos tendrían estados distintos).
CMD ["gunicorn", "--chdir", "/app/backend", "-w", "1", "--threads", "8", "-b", "0.0.0.0:8000", "--access-logfile", "-", "app:app"]
