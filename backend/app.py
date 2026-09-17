"""Servidor de telemetría y geolocalización punto a punto.

Estado en memoria (deque por dispositivo) + persistencia plana append-only JSONL.
Endpoints JSON puros, sin ORM, sin base de datos, thread-safe.
"""

from __future__ import annotations

import atexit
import hmac
import json
import math
import os
import threading
import time
from collections import deque
from pathlib import Path
from typing import Any, Deque, Dict, List

from flask import Flask, jsonify, request, send_from_directory
from werkzeug.exceptions import HTTPException

import github_mirror

ROOT = Path(__file__).resolve().parent.parent

API_KEY = os.environ.get("TELEMETRY_API_KEY", "").strip()
STORE_PATH = Path(os.environ.get("TELEMETRY_STORE", str(ROOT / "telemetry_store.jsonl")))
FRONTEND_DIR = ROOT / "frontend"
MAX_POINTS = int(os.environ.get("TELEMETRY_MAX_POINTS", "5000"))
COMPACT_EVERY = int(os.environ.get("TELEMETRY_COMPACT_EVERY", "2000"))
MAX_BODY_BYTES = int(os.environ.get("TELEMETRY_MAX_BODY", "131072"))
MAX_BATCH = 500

EARTH_RADIUS_M = 6371008.8
DAY_MS = 86_400_000
MIN_TS_MS = 978_307_200_000  # 2001-01-01
MAX_IMPLIED_SPEED_MPS = 90.0    # 324 km/h: por encima se trata como salto de posición
MAX_TRUSTED_ACCURACY_M = 150.0  # los fixes peores que esto no suman distancia
A_PROCESS_MPS2 = 1.5            # aceleración supuesta en el ruido de proceso del filtro
DEFAULT_ACCURACY_M = 25.0       # precisión asumida cuando el cliente no la informa
ACCURACY_INFLATION = 1.8        # la precisión del GPS es un radio del 68 %: el error real es mayor
MAX_GAP_SECONDS = 120.0         # hueco mayor: se reinicia el filtro y no se cuenta el tramo

app = Flask(__name__)
app.json.sort_keys = False
app.config["MAX_CONTENT_LENGTH"] = MAX_BODY_BYTES

_lock = threading.RLock()
_devices: Dict[str, Deque[Dict[str, Any]]] = {}
_meta: Dict[str, Dict[str, Any]] = {}
_written = 0


def _now_ms() -> int:
    return int(time.time() * 1000)


def _num(value: Any, name: str, minimum: float | None = None, maximum: float | None = None) -> float:
    try:
        out = float(value)
    except (TypeError, ValueError):
        raise ValueError(f"campo '{name}' no numérico") from None
    if not math.isfinite(out):
        raise ValueError(f"campo '{name}' no finito")
    if minimum is not None and out < minimum:
        raise ValueError(f"campo '{name}' fuera de rango")
    if maximum is not None and out > maximum:
        raise ValueError(f"campo '{name}' fuera de rango")
    return out


def _optional_num(value: Any, key: str, minimum: float, maximum: float) -> float | None:
    if value is None or value == "":
        return None
    try:
        return round(_num(value, key, minimum, maximum), 4)
    except ValueError:
        return None


def _optional_int(value: Any, key: str, minimum: int, maximum: int) -> int | None:
    if value is None or value == "":
        return None
    try:
        out = int(float(value))
    except (TypeError, ValueError):
        return None
    return None if out < minimum or out > maximum else out


def _norm_ts(value: Any) -> int:
    ts = int(_num(value, "timestamp", 0, 4e12))
    if ts < 1e11:  # segundos -> milisegundos
        ts *= 1000
    if ts < MIN_TS_MS or ts > _now_ms() + DAY_MS:
        raise ValueError("campo 'timestamp' incoherente")
    return ts


def _haversine(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dphi = p2 - p1
    dlambda = math.radians(lng2 - lng1)
    h = math.sin(dphi * 0.5) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dlambda * 0.5) ** 2
    return 2.0 * EARTH_RADIUS_M * math.asin(min(1.0, math.sqrt(h)))


def _bearing(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dlambda = math.radians(lng2 - lng1)
    y = math.sin(dlambda) * math.cos(p2)
    x = math.cos(p1) * math.sin(p2) - math.sin(p1) * math.cos(p2) * math.cos(dlambda)
    return (math.degrees(math.atan2(y, x)) + 360.0) % 360.0


def _sanitize_list(raw: Any, allowed: frozenset[str], limit: int = 32) -> List[Dict[str, Any]]:
    if not isinstance(raw, list):
        return []
    out: List[Dict[str, Any]] = []
    for item in raw[:limit]:
        if not isinstance(item, dict):
            continue
        clean: Dict[str, Any] = {}
        for key, value in item.items():
            if key not in allowed or value is None:
                continue
            clean[key] = value if isinstance(value, (int, float, bool)) else str(value)[:64]
        if clean:
            out.append(clean)
    return out


WIFI_FIELDS = frozenset({"ssid", "bssid", "rssi", "level", "frequency", "channel", "connected", "capabilities"})
CELL_FIELDS = frozenset({"type", "mcc", "mnc", "cid", "lac", "tac", "pci", "dbm", "rsrp", "rsrq", "level", "registered"})


def _sanitize_gnss(raw: Any) -> Dict[str, Any] | None:
    """Calidad GNSS: satélites visibles/en uso, SNR y constelaciones presentes."""
    if not isinstance(raw, dict):
        return None
    out: Dict[str, Any] = {}
    for key in ("visible", "used"):
        value = _optional_int(raw.get(key), key, 0, 200)
        if value is not None:
            out[key] = value
    for key in ("snr_best", "snr_avg"):
        value = _optional_num(raw.get(key), key, 0.0, 60.0)
        if value is not None:
            out[key] = value
    constellations = raw.get("constellations")
    if isinstance(constellations, str) and constellations:
        out["constellations"] = constellations[:64]
    return out or None


def _text(raw: Any, maximum: int) -> str | None:
    text = str(raw).strip() if raw not in (None, "") else ""
    return text[:maximum] or None


def _sanitize(raw: Any) -> Dict[str, Any]:
    if not isinstance(raw, dict):
        raise ValueError("cada punto debe ser un objeto JSON")

    device_id = str(raw.get("device_id") or raw.get("id") or "").strip()[:64]
    if not device_id:
        raise ValueError("campo 'device_id' requerido")

    lat = _num(raw.get("lat", raw.get("latitude")), "lat", -90.0, 90.0)
    lng = _num(raw.get("lng", raw.get("lon", raw.get("longitude"))), "lng", -180.0, 180.0)
    if lat == 0.0 and lng == 0.0:
        raise ValueError("coordenada (0,0) descartada")

    point: Dict[str, Any] = {
        "device_id": device_id,
        "label": str(raw.get("label") or device_id)[:48],
        "lat": round(lat, 7),
        "lng": round(lng, 7),
        "timestamp": _norm_ts(raw.get("timestamp", raw.get("ts", _now_ms()))),
        "accuracy": _optional_num(raw.get("accuracy"), "accuracy", 0.0, 100_000.0),
        "altitude": _optional_num(raw.get("altitude"), "altitude", -500.0, 20_000.0),
        "speed_mps": _optional_num(raw.get("speed_mps", raw.get("speed")), "speed_mps", -1.0, 400.0),
        "bearing": _optional_num(raw.get("bearing", raw.get("heading")), "bearing", 0.0, 360.0),
        "battery": _optional_num(raw.get("battery"), "battery", 0.0, 100.0),
        "vertical_accuracy": _optional_num(raw.get("vertical_accuracy"), "vertical_accuracy", 0.0, 100_000.0),
        "speed_accuracy": _optional_num(raw.get("speed_accuracy"), "speed_accuracy", 0.0, 400.0),
        "bearing_accuracy": _optional_num(raw.get("bearing_accuracy"), "bearing_accuracy", 0.0, 360.0),
        "altitude_baro": _optional_num(raw.get("altitude_baro"), "altitude_baro", -500.0, 20_000.0),
        "pressure_hpa": _optional_num(raw.get("pressure_hpa"), "pressure_hpa", 300.0, 1200.0),
        "heading_magnetic": _optional_num(raw.get("heading_magnetic"), "heading_magnetic", 0.0, 360.0),
        "movement": _optional_num(raw.get("movement"), "movement", 0.0, 100.0),
        "steps": _optional_int(raw.get("steps"), "steps", 0, 10**9),
        "elapsed_nanos": _optional_int(raw.get("elapsed_nanos"), "elapsed_nanos", 0, 10**15),
        "sdk": _optional_int(raw.get("sdk"), "sdk", 1, 100),
        "provider": _text(raw.get("provider") or "unknown", 24),
        "source": _text(raw.get("source") or "android", 24),
        "model": _text(raw.get("model"), 48),
        "activity": _text(raw.get("activity"), 16),
        "network": _text(raw.get("network"), 16),
        "data_network": _text(raw.get("data_network"), 8),
        "operator": _text(raw.get("operator"), 48),
        "mock": bool(raw.get("mock", False)),
        "wifi": _sanitize_list(raw.get("wifi"), WIFI_FIELDS),
        "cell": _sanitize_list(raw.get("cell"), CELL_FIELDS),
        "gnss": _sanitize_gnss(raw.get("gnss")),
    }
    return point


def _delta_seconds(previous: Dict[str, Any], current: Dict[str, Any]) -> float:
    """dt en segundos: reloj monotónico del cliente (elapsed_nanos) si es válido, si no el timestamp."""
    first = previous.get("elapsed_nanos")
    second = current.get("elapsed_nanos")
    if isinstance(first, int) and isinstance(second, int) and second > first:
        return (second - first) / 1e9
    return (current["timestamp"] - previous["timestamp"]) / 1000.0


def _new_meta(device_id: str, point: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "device_id": device_id,
        "label": point.get("label") or device_id,
        "points": 0,
        "dropped": 0,
        "outliers": 0,
        "low_quality": 0,
        "distance_m": 0.0,
        "max_speed_mps": 0.0,
        "accuracy_sum": 0.0,
        "accuracy_count": 0,
        "raw_distance_m": 0.0,
        "gaps": 0,
        "baro_offset": None,
        "smooth_lat": None,
        "smooth_lng": None,
        "kf": None,
        "last_activity": None,
        "satellites": None,
        "first_seen": point["timestamp"],
        "last_seen": point["timestamp"],
        "last_provider": point["provider"],
    }


def _to_local(lat: float, lng: float, origin_lat: float, origin_lng: float) -> tuple[float, float]:
    return (
        math.radians(lng - origin_lng) * EARTH_RADIUS_M * math.cos(math.radians(origin_lat)),
        math.radians(lat - origin_lat) * EARTH_RADIUS_M,
    )


def _to_wgs84(x: float, y: float, origin_lat: float, origin_lng: float) -> tuple[float, float]:
    cos_lat = max(1e-6, math.cos(math.radians(origin_lat)))
    return (
        origin_lat + math.degrees(y / EARTH_RADIUS_M),
        origin_lng + math.degrees(x / (EARTH_RADIUS_M * cos_lat)),
    )


class PositionFilter:
    """Filtro de Kalman ligero con estado (posición, velocidad) por eje en plano métrico local.

    Suaviza el ruido del GPS sin retrasar el movimiento real y publica la velocidad estimada.
    El ruido de medida es la precisión del fix al cuadrado; el de proceso, la aceleración típica.
    """

    __slots__ = ("origin_lat", "origin_lng", "last_time", "pos", "vel", "cov_p", "cov_pv", "cov_v")

    def __init__(self, lat: float, lng: float, timestamp_ms: int) -> None:
        self.origin_lat = lat
        self.origin_lng = lng
        self.last_time = timestamp_ms
        self.pos = [0.0, 0.0]
        self.vel = [0.0, 0.0]
        self.cov_p = [DEFAULT_ACCURACY_M ** 2, DEFAULT_ACCURACY_M ** 2]
        self.cov_pv = [0.0, 0.0]
        self.cov_v = [4.0, 4.0]

    def update(self, lat: float, lng: float, timestamp_ms: int, accuracy: float | None) -> tuple[float, float]:
        dt = min(120.0, max(0.001, (timestamp_ms - self.last_time) / 1000.0))
        self.last_time = timestamp_ms
        measured = _to_local(lat, lng, self.origin_lat, self.origin_lng)
        reported = accuracy if accuracy else DEFAULT_ACCURACY_M
        variance = max(1.0, reported * ACCURACY_INFLATION) ** 2
        process = A_PROCESS_MPS2 ** 2
        for axis in (0, 1):
            predicted_pos = self.pos[axis] + self.vel[axis] * dt
            cov_p = self.cov_p[axis] + 2.0 * dt * self.cov_pv[axis] + dt * dt * self.cov_v[axis] + process * dt ** 4 / 4.0
            cov_pv = self.cov_pv[axis] + dt * self.cov_v[axis] + process * dt ** 3 / 2.0
            cov_v = self.cov_v[axis] + process * dt * dt
            denominator = cov_p + variance
            gain_p = cov_p / denominator
            gain_v = cov_pv / denominator
            innovation = measured[axis] - predicted_pos
            self.pos[axis] = predicted_pos + gain_p * innovation
            self.vel[axis] = self.vel[axis] + gain_v * innovation
            self.cov_p[axis] = (1.0 - gain_p) * cov_p
            self.cov_pv[axis] = (1.0 - gain_p) * cov_pv
            self.cov_v[axis] = cov_v - gain_v * cov_pv
        return _to_wgs84(self.pos[0], self.pos[1], self.origin_lat, self.origin_lng)

    def speed(self) -> float:
        return math.hypot(self.vel[0], self.vel[1])


def _ingest(point: Dict[str, Any]) -> Dict[str, Any]:
    """Inserta un punto aplicando fusión: dt monotónico, Kalman, altitud barométrica calibrada
    y filtros de saltos imposibles y de fixes de baja calidad."""
    device_id = point["device_id"]
    with _lock:
        series = _devices.get(device_id)
        if series is None:
            series = _devices[device_id] = deque(maxlen=MAX_POINTS)
            _meta[device_id] = _new_meta(device_id, point)
        meta = _meta[device_id]
        meta["last_provider"] = point["provider"]

        prev = series[-1] if series else None
        if prev is None:
            point["distance_m"] = 0.0
            point["raw_distance_m"] = 0.0
            meta["kf"] = PositionFilter(point["lat"], point["lng"], point["timestamp"])
            smooth_lat, smooth_lng = point["lat"], point["lng"]
        else:
            if point["timestamp"] < prev["timestamp"]:
                meta["dropped"] += 1
                return {}
            if point["timestamp"] == prev["timestamp"] and point["lat"] == prev["lat"] and point["lng"] == prev["lng"]:
                return {}

            dt = _delta_seconds(prev, point)
            raw = _haversine(prev["lat"], prev["lng"], point["lat"], point["lng"])
            implied = (raw / dt) if dt > 0.0 else 0.0
            accuracy = point.get("accuracy")

            gap = False
            if implied > MAX_IMPLIED_SPEED_MPS:
                # salto imposible: no contamina el filtro ni las estadísticas
                point["outlier"] = True
                point["low_quality"] = True
                meta["outliers"] += 1
                meta["low_quality"] += 1
                smooth_lat, smooth_lng = meta["smooth_lat"], meta["smooth_lng"]
            else:
                if accuracy is not None and accuracy > MAX_TRUSTED_ACCURACY_M:
                    point["low_quality"] = True
                    meta["low_quality"] += 1
                kalman = meta.get("kf")
                gap = kalman is None or dt > MAX_GAP_SECONDS
                if gap:
                    # hueco largo (sin cobertura, teléfono apagado): se reinicia el filtro
                    kalman = meta["kf"] = PositionFilter(point["lat"], point["lng"], point["timestamp"])
                    point["gap"] = True
                    meta["gaps"] += 1
                smooth_lat, smooth_lng = kalman.update(point["lat"], point["lng"], point["timestamp"], accuracy)

            prev_lat = prev.get("smooth_lat", prev["lat"])
            prev_lng = prev.get("smooth_lng", prev["lng"])
            filtered = _haversine(prev_lat, prev_lng, smooth_lat, smooth_lng)
            point["raw_distance_m"] = round(raw, 2)
            # ni los saltos ni los tramos tras un hueco suman recorrido: no son trayecto real
            point["distance_m"] = 0.0 if (point.get("outlier") or gap) else round(filtered, 2)

            if point.get("speed_mps") is None:
                kalman = meta.get("kf")
                if not point.get("outlier") and not gap and kalman is not None:
                    point["speed_mps"] = round(kalman.speed(), 3)
                elif dt > 0.0 and implied <= MAX_IMPLIED_SPEED_MPS:
                    point["speed_mps"] = round(implied, 3)
            if point.get("bearing") is None and not gap and filtered > 1.0:
                point["bearing"] = round(_bearing(prev_lat, prev_lng, smooth_lat, smooth_lng), 1)

        point["smooth_lat"] = round(smooth_lat, 7)
        point["smooth_lng"] = round(smooth_lng, 7)

        # Altitud fusionada: el barómetro es preciso pero relativo; el GPS lo calibra.
        barometric = point.get("altitude_baro")
        gps_altitude = point.get("altitude")
        accuracy = point.get("accuracy")
        if barometric is not None and gps_altitude is not None and (accuracy is None or accuracy <= 50.0):
            meta["baro_offset"] = gps_altitude - barometric
        offset = meta.get("baro_offset")
        if barometric is not None and offset is not None:
            point["altitude_fused"] = round(barometric + offset, 1)
        elif gps_altitude is not None:
            point["altitude_fused"] = round(gps_altitude, 1)

        series.append(point)
        meta["points"] += 1
        meta["smooth_lat"] = smooth_lat
        meta["smooth_lng"] = smooth_lng
        meta["raw_distance_m"] = round(meta["raw_distance_m"] + point.get("raw_distance_m", 0.0), 2)
        meta["distance_m"] = round(meta["distance_m"] + point.get("distance_m", 0.0), 2)
        speed = point.get("speed_mps") or 0.0
        if speed > meta["max_speed_mps"] and not point.get("outlier"):
            meta["max_speed_mps"] = round(speed, 3)
        accuracy = point.get("accuracy")
        if accuracy is not None and accuracy > 0.0:
            meta["accuracy_sum"] += accuracy
            meta["accuracy_count"] += 1
        if point.get("activity"):
            meta["last_activity"] = point["activity"]
        gnss = point.get("gnss")
        if isinstance(gnss, dict) and gnss.get("used") is not None:
            meta["satellites"] = gnss["used"]
        meta["last_seen"] = max(meta["last_seen"], point["timestamp"])
        meta["first_seen"] = min(meta["first_seen"], point["timestamp"])
        return point


def _persist(points: List[Dict[str, Any]]) -> None:
    if not points:
        return
    global _written
    with _lock:
        try:
            with STORE_PATH.open("a", encoding="utf-8") as fh:
                for point in points:
                    fh.write(json.dumps(point, separators=(",", ":"), ensure_ascii=False) + "\n")
                fh.flush()
                os.fsync(fh.fileno())
        except OSError as exc:  # almacenamiento no bloqueante: nunca rompe la API
            app.logger.warning("no se pudo escribir %s: %s", STORE_PATH, exc)
            return
        _written += len(points)
        if _written >= COMPACT_EVERY:
            _compact()


def _compact() -> None:
    """Reescribe el archivo plano con el estado actual (últimos MAX_POINTS por dispositivo)."""
    global _written
    tmp = STORE_PATH.with_name(STORE_PATH.name + ".tmp")
    try:
        with tmp.open("w", encoding="utf-8") as fh:
            for series in _devices.values():
                for point in series:
                    fh.write(json.dumps(point, separators=(",", ":"), ensure_ascii=False) + "\n")
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, STORE_PATH)
        _written = 0
    except OSError as exc:
        app.logger.warning("compactación fallida: %s", exc)
        try:
            tmp.unlink()
        except OSError:
            pass


def _flush() -> None:
    with _lock:
        if _devices:
            _compact()


def _load() -> None:
    if not STORE_PATH.exists():
        return
    try:
        with STORE_PATH.open("r", encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    point = json.loads(line)
                except json.JSONDecodeError:
                    continue
                if isinstance(point, dict) and "lat" in point and "lng" in point and "device_id" in point:
                    _ingest(point)
    except OSError as exc:
        app.logger.warning("no se pudo leer %s: %s", STORE_PATH, exc)


def _authorized() -> bool:
    if not API_KEY:
        return True
    provided = request.headers.get("X-Api-Key") or request.args.get("key", "")
    if not provided:
        header = request.headers.get("Authorization", "")
        if header.lower().startswith("bearer "):
            provided = header[7:].strip()
    return bool(provided) and hmac.compare_digest(provided, API_KEY)


def _require_key(view):
    def wrapper(*args, **kwargs):
        if not _authorized():
            return jsonify({"ok": False, "error": "no autorizado"}), 401
        return view(*args, **kwargs)

    wrapper.__name__ = view.__name__
    return wrapper


def _error(message: str, status: int):
    return jsonify({"ok": False, "error": message}), status


def _int_arg(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = request.args.get(name)
    if raw is None or raw == "":
        return default
    try:
        value = int(float(raw))
    except (TypeError, ValueError):
        return default
    return max(minimum, min(maximum, value))


def _compact_point(point: Dict[str, Any]) -> List[Any]:
    """Fila compacta [ts, lat, lng, speed, bearing, acc, battery, activity].

    Mismo formato que publica la app Android vía Contents API: los lectores toman las
    6 primeras columnas, así que añadir colas es retrocompatible.
    """
    return [
        point["timestamp"],
        point["lat"],
        point["lng"],
        point.get("speed_mps"),
        point.get("bearing"),
        point.get("accuracy"),
        point.get("battery"),
        point.get("activity"),
    ]


def _summary(meta: Dict[str, Any]) -> Dict[str, Any]:
    duration_ms = max(0, meta["last_seen"] - meta["first_seen"])
    avg = (meta["distance_m"] / (duration_ms / 1000.0)) if duration_ms > 0 else 0.0
    return {
        "device_id": meta["device_id"],
        "label": meta["label"],
        "points": meta["points"],
        "dropped": meta["dropped"],
        "outliers": meta["outliers"],
        "low_quality": meta["low_quality"],
        "avg_accuracy_m": (round(meta["accuracy_sum"] / meta["accuracy_count"], 2) if meta["accuracy_count"] else None),
        "activity": meta["last_activity"],
        "satellites": meta["satellites"],
        "distance_m": meta["distance_m"],
        "raw_distance_m": meta["raw_distance_m"],
        "noise_removed_m": round(meta["raw_distance_m"] - meta["distance_m"], 2),
        "gaps": meta["gaps"],
        "avg_speed_mps": round(avg, 3),
        "max_speed_mps": meta["max_speed_mps"],
        "first_seen": meta["first_seen"],
        "last_seen": meta["last_seen"],
        "age_ms": max(0, _now_ms() - meta["last_seen"]),
        "online": (_now_ms() - meta["last_seen"]) <= 30_000,
        "provider": meta["last_provider"],
    }


def _serialize(device_id: str, fmt: str, limit: int, since: int, with_points: bool) -> Dict[str, Any]:
    series = _devices[device_id]
    meta = _meta[device_id]
    points: List[Dict[str, Any]] = list(series)
    if since:
        points = [p for p in points if p["timestamp"] >= since]
    if limit:
        points = points[-limit:]
    latest = series[-1] if series else None
    smooth = [
        [p["timestamp"], p["smooth_lat"], p["smooth_lng"], p.get("speed_mps")]
        for p in points
        if p.get("smooth_lat") is not None
    ]
    return {
        "device_id": device_id,
        "summary": _summary(meta),
        "latest": latest,
        "points": ([_compact_point(p) for p in points] if fmt == "compact" else points) if with_points else [],
        "smooth": smooth if with_points else [],
    }


@app.after_request
def _cors(response):
    response.headers["Access-Control-Allow-Origin"] = "*"
    response.headers["Access-Control-Allow-Methods"] = "GET, POST, DELETE, OPTIONS"
    response.headers["Access-Control-Allow-Headers"] = "Content-Type, X-Api-Key, Authorization"
    response.headers["Access-Control-Max-Age"] = "86400"
    response.headers.setdefault("Cache-Control", "no-store, max-age=0")
    return response


@app.errorhandler(HTTPException)
def _http_error(exc: HTTPException):
    return jsonify({"ok": False, "error": exc.description, "status": exc.code}), exc.code


@app.errorhandler(Exception)
def _unhandled(exc: Exception):
    app.logger.exception("error no controlado: %s", exc)
    return jsonify({"ok": False, "error": "error interno del servidor"}), 500


@app.get("/api/health")
def health():
    with _lock:
        devices = len(_devices)
        points = sum(meta["points"] for meta in _meta.values())
    return jsonify(
        {
            "ok": True,
            "status": "up",
            "server_time": _now_ms(),
            "devices": devices,
            "points": points,
            "storage": str(STORE_PATH),
            "auth": bool(API_KEY),
            "max_points_per_device": MAX_POINTS,
            "github_mirror": github_mirror.status(),
        }
    )


@app.post("/api/location")
@_require_key
def post_location():
    if request.content_length and request.content_length > MAX_BODY_BYTES:
        return _error("cuerpo demasiado grande", 413)

    raw = request.get_json(silent=True)
    if raw is None:
        return _error("cuerpo JSON inválido o ausente", 400)
    if isinstance(raw, dict) and isinstance(raw.get("points"), list):
        items = raw["points"]
    elif isinstance(raw, list):
        items = raw
    else:
        items = [raw]
    if not items:
        return _error("lote vacío", 400)
    if len(items) > MAX_BATCH:
        return _error(f"lote demasiado grande (máx {MAX_BATCH})", 413)

    accepted: List[Dict[str, Any]] = []
    errors: List[str] = []
    for item in items:
        try:
            point = _sanitize(item)
        except ValueError as exc:
            if len(errors) < 5:
                errors.append(str(exc))
            continue
        stored = _ingest(point)
        if stored:
            accepted.append(stored)

    _persist(accepted)
    publish_snapshot()
    return (
        jsonify(
            {
                "ok": bool(accepted),
                "stored": len(accepted),
                "rejected": len(items) - len(accepted),
                "errors": errors,
                "devices": sorted({p["device_id"] for p in accepted}),
                "server_time": _now_ms(),
            }
        ),
        201 if accepted else 200,
    )


def publish_snapshot() -> None:
    """Espeja el estado consolidado a GitHub (Contents API) si GITHUB_REPO está definido.

    Formato compacto: los puntos viajan como arrays [ts, lat, lng, vel, rumbo, ±], igual que
    publica la app Android, para que el visualizador los lea sin importar quién los escribió.
    """
    try:
        with _lock:
            snapshot = [_serialize(did, "compact", 0, 0, True) for did in _devices]
        github_mirror.publish_if_due(snapshot)
    except Exception as exc:   # el espejo nunca debe tumbar la API
        app.logger.warning("espejo GitHub: %s", exc)


@app.get("/api/locations")
def get_locations():
    fmt = "compact" if request.args.get("format") == "compact" else "full"
    limit = _int_arg("limit", 0, 0, MAX_POINTS)
    since = _int_arg("since", 0, 0, 2**53 - 1)
    with_points = request.args.get("latest", "0") not in ("1", "true", "yes")
    only = request.args.get("device_id", "").strip()

    with _lock:
        if only:
            if only not in _devices:
                return _error("dispositivo desconocido", 404)
            ids = [only]
        else:
            ids = sorted(_devices, key=lambda did: _meta[did]["last_seen"], reverse=True)
        devices = [_serialize(did, fmt, limit, since, with_points) for did in ids]

    return jsonify({"ok": True, "server_time": _now_ms(), "count": len(devices), "devices": devices})


@app.get("/api/locations/<device_id>")
def get_device(device_id: str):
    fmt = "compact" if request.args.get("format") == "compact" else "full"
    limit = _int_arg("limit", 0, 0, MAX_POINTS)
    since = _int_arg("since", 0, 0, 2**53 - 1)
    with _lock:
        if device_id not in _devices:
            return _error("dispositivo desconocido", 404)
        payload = _serialize(device_id, fmt, limit, since, True)
    payload["ok"] = True
    payload["server_time"] = _now_ms()
    return jsonify(payload)


@app.delete("/api/locations/<device_id>")
@_require_key
def delete_device(device_id: str):
    with _lock:
        if device_id not in _devices:
            return _error("dispositivo desconocido", 404)
        removed = len(_devices.pop(device_id))
        _meta.pop(device_id, None)
        _compact()
    return jsonify({"ok": True, "device_id": device_id, "removed": removed})


if FRONTEND_DIR.is_dir():

    @app.get("/")
    @app.get("/index.html")
    def index():
        return send_from_directory(FRONTEND_DIR, "index.html")


_load()
atexit.register(_flush)

if __name__ == "__main__":
    app.run(
        host=os.environ.get("HOST", "0.0.0.0"),
        port=int(os.environ.get("PORT", "8000")),
        threaded=True,
        debug=False,
    )
