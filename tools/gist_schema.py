"""Contrato del canal Gist: validación del documento y fusión de históricos.

Es la implementación de referencia de lo que publica `GistPublisher.java`. Existe por tres razones
concretas:

1. **Comprobar un `data.json` real sin tocar el móvil**::

       python tools/gist_schema.py data.json
       python tools/gist_schema.py https://gist.githubusercontent.com/USUARIO/ID/raw/data.json

   (el segundo acepta cualquier URL: se descarga con la librería estándar).

2. **Fijar por escrito el contrato** que comparten el publicador Android y el visor: bloques
   obligatorios, tipos, centinelas (`-1` = magnitud desconocida) y orden cronológico.

3. **Probar en CI la lógica que en el móvil no se puede ejecutar**: las reglas de fusión por
   `timestamp_ms`, el orden y el doble recorte (número de puntos y caracteres) están replicadas aquí
   y cubiertas por `tests/test_gist_schema.py`.

Los tres sitios cambian a la vez: el Java que publica, este validador y sus pruebas.

Nota honesta sobre los tamaños: el recorte por caracteres cuenta caracteres del JSON compacto. Java
(`org.json`) y Python (`json.dumps` con separadores compactos) no escapan exactamente igual los
caracteres no ASCII, así que los dos conteos pueden diferir en unos pocos caracteres por punto. Los
dos son conservadores y quedan muy por debajo del límite de 1 MB por fichero de la API de Gists.
"""

from __future__ import annotations

import json
import sys
from typing import Any, Iterable, Sequence
from urllib.request import urlopen

MAX_POINTS = 500
MAX_CHARS = 400_000
SENTINEL = -1
BLOCKS = ("location", "telemetry", "status")

# Claves que el contrato exige SIEMPRE (aunque el valor sea null cuando el sensor no lo dio).
REQUIRED: dict[str, tuple[str, ...]] = {
    "location": (
        "latitude", "longitude", "altitude", "altitude_source", "accuracy_meters",
        "vertical_accuracy_meters", "speed_mps", "speed_kmh", "bearing_degrees",
        "heading_magnetic_degrees", "declination_degrees", "provider", "mock",
        "smooth_latitude", "smooth_longitude", "cells", "wifi_aps",
    ),
    "telemetry": (
        "timestamp_ms", "elapsed_nanos", "battery_level", "is_charging", "network_type",
        "data_network", "network_label", "operator", "activity", "steps", "device_id",
        "label", "model", "sdk", "low_ram", "source",
    ),
    "status": (
        "is_tracking", "fallback_active", "last_error", "local_points", "remote_points",
        "gist_configured",
    ),
}

# Clave → comprobador. Los que admiten null se marcan aquí; el centinela -1 se acepta donde el
# contrato lo permite (magnitud no medida).
NULLABLE = {"altitude", "speed_kmh", "smooth_latitude", "smooth_longitude", "last_error"}


def _is_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def compact_json(document: Any) -> str:
    """Serialización compacta equivalente a `JSONObject.toString()` / `JSONArray.toString()`."""
    return json.dumps(document, separators=(",", ":"), ensure_ascii=False)


def timestamp_of(point: Any) -> int:
    """Instante del punto: `telemetry.timestamp_ms`, con tolerancia al esquema plano."""
    if not isinstance(point, dict):
        return 0
    telemetry = point.get("telemetry")
    if isinstance(telemetry, dict) and isinstance(telemetry.get("timestamp_ms"), (int, float)):
        return int(telemetry["timestamp_ms"])
    for key in ("timestamp_ms", "timestamp"):
        if isinstance(point.get(key), (int, float)):
            return int(point[key])
    return 0


def parse_document(text: str) -> list[dict]:
    """Lee un documento del canal. Un objeto suelto se envuelve; lo ilegible lanza `ValueError`."""
    if not isinstance(text, str) or not text.strip():
        raise ValueError("documento vacío")
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError as error:
        raise ValueError(f"JSON inválido: {error}") from error
    if isinstance(parsed, list):
        return [item for item in parsed if isinstance(item, dict)]
    if isinstance(parsed, dict):
        return [parsed]
    raise ValueError("el documento debe ser un array de objetos")


def validate_point(point: Any) -> list[str]:
    """Problemas de un punto (lista vacía = cumple el contrato)."""
    issues: list[str] = []
    if not isinstance(point, dict):
        return ["no es un objeto"]

    for block in BLOCKS:
        if not isinstance(point.get(block), dict):
            issues.append(f"falta el bloque '{block}' o no es un objeto")
    if issues:
        return issues

    for block, keys in REQUIRED.items():
        for key in keys:
            if key not in point[block]:
                issues.append(f"{block}.{key} ausente")
                continue
            if point[block][key] is None and key not in NULLABLE:
                issues.append(f"{block}.{key} es null y el contrato no lo permite")

    location = point["location"]
    telemetry = point["telemetry"]
    status = point["status"]

    latitude = location.get("latitude")
    if not _is_number(latitude) or not -90 <= latitude <= 90:
        issues.append("location.latitude fuera de rango")
    longitude = location.get("longitude")
    if not _is_number(longitude) or not -180 <= longitude <= 180:
        issues.append("location.longitude fuera de rango")

    for key in ("accuracy_meters", "vertical_accuracy_meters", "speed_mps", "bearing_degrees",
                "heading_magnetic_degrees", "declination_degrees"):
        value = location.get(key)
        if value is None or not _is_number(value):
            issues.append(f"location.{key} debe ser numérico (usa {SENTINEL} si no se midió)")

    for key in ("altitude", "speed_kmh", "smooth_latitude", "smooth_longitude"):
        value = location.get(key)
        if value is not None and not _is_number(value):
            issues.append(f"location.{key} debe ser numérico o null")

    if not isinstance(location.get("provider"), str) or not location.get("provider"):
        issues.append("location.provider debe ser una cadena no vacía")
    if not isinstance(location.get("altitude_source"), str):
        issues.append("location.altitude_source debe ser una cadena")
    if not isinstance(location.get("mock"), bool):
        issues.append("location.mock debe ser booleano")
    for key in ("cells", "wifi_aps"):
        value = location.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            issues.append(f"location.{key} debe ser un entero >= 0")

    stamp = telemetry.get("timestamp_ms")
    if not isinstance(stamp, int) or isinstance(stamp, bool) or stamp <= 0:
        issues.append("telemetry.timestamp_ms debe ser un entero positivo")
    battery = telemetry.get("battery_level")
    if not isinstance(battery, int) or isinstance(battery, bool) or (battery != SENTINEL and not 0 <= battery <= 100):
        issues.append(f"telemetry.battery_level debe ser 0-100 o {SENTINEL}")
    steps = telemetry.get("steps")
    if not isinstance(steps, int) or isinstance(steps, bool) or steps < SENTINEL:
        issues.append(f"telemetry.steps debe ser entero >= {SENTINEL}")
    for key in ("elapsed_nanos", "sdk"):
        value = telemetry.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            issues.append(f"telemetry.{key} debe ser un entero >= 0")
    for key in ("is_charging", "low_ram"):
        if not isinstance(telemetry.get(key), bool):
            issues.append(f"telemetry.{key} debe ser booleano")
    for key in ("network_type", "data_network", "network_label", "operator", "activity",
                "device_id", "label", "model", "source"):
        if not isinstance(telemetry.get(key), str):
            issues.append(f"telemetry.{key} debe ser una cadena")

    for key in ("is_tracking", "fallback_active", "gist_configured"):
        if not isinstance(status.get(key), bool):
            issues.append(f"status.{key} debe ser booleano")
    if status.get("last_error") is not None and not isinstance(status.get("last_error"), str):
        issues.append("status.last_error debe ser una cadena o null")
    for key in ("local_points", "remote_points"):
        value = status.get(key)
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            issues.append(f"status.{key} debe ser un entero >= 0")

    return issues


def validate_document(document: Any) -> dict:
    """Informe completo del documento: validez, problemas por punto, duplicados y orden."""
    report: dict[str, Any] = {
        "ok": False,
        "points": 0,
        "size_chars": 0,
        "problems": [],
        "duplicates": [],
        "invalid_timestamps": [],
        "order_ok": True,
        "within_caps": True,
    }
    if not isinstance(document, list):
        report["problems"].append({"index": -1, "issues": ["el documento no es un array"]})
        return report

    report["points"] = len(document)
    stamped: list[int] = []

    for index, point in enumerate(document):
        issues = validate_point(point)
        if issues:
            report["problems"].append({"index": index, "issues": issues})
        stamp = timestamp_of(point)
        if stamp <= 0:
            report["invalid_timestamps"].append(index)   # sin instante no hay clave de identidad
        else:
            stamped.append(stamp)

    seen: set[int] = set()
    for stamp in stamped:
        if stamp in seen:
            report["duplicates"].append(stamp)
        seen.add(stamp)
    report["order_ok"] = all(a < b for a, b in zip(stamped, stamped[1:]))

    report["size_chars"] = len(compact_json(document))
    report["within_caps"] = len(document) <= MAX_POINTS and report["size_chars"] <= MAX_CHARS
    report["ok"] = (not report["problems"] and not report["duplicates"]
                     and not report["invalid_timestamps"] and report["order_ok"])
    return report


def merge_history(local: Sequence[dict] | None, remote: Sequence[dict] | None,
                  max_points: int = MAX_POINTS, max_chars: int = MAX_CHARS) -> list[dict]:
    """Fusión de históricos con las mismas reglas que `GistPublisher.buildPayload`.

    - La clave única es `timestamp_ms`: un mismo instante nunca aparece dos veces.
    - En empate gana el punto **local** (trae más detalle).
    - Se conservan los más nuevos: primero el límite de puntos, después el de caracteres, y en
      ambos casos se descarta por el extremo más antiguo.
    - El resultado va del más antiguo al más nuevo, que es como se lee una traza.
    """
    by_timestamp: dict[int, dict] = {}
    for source in (remote, local):
        for point in source or []:
            stamp = timestamp_of(point)
            if stamp > 0:
                by_timestamp[stamp] = point

    newest_first: list[dict] = []
    budget = max_chars
    for stamp in sorted(by_timestamp, reverse=True):
        if len(newest_first) >= max_points:
            break
        size = len(compact_json(by_timestamp[stamp])) + 1
        if size > budget:
            break
        budget -= size
        newest_first.append(by_timestamp[stamp])

    newest_first.reverse()
    return newest_first


def deleted_on_merge(local: Sequence[dict] | None, remote: Sequence[dict] | None,
                     max_points: int = MAX_POINTS, max_chars: int = MAX_CHARS) -> int:
    """Cuántos puntos con instante válido se han quedado fuera por los límites (diagnóstico)."""
    total = {timestamp_of(p) for p in (local or []) if timestamp_of(p) > 0}
    total |= {timestamp_of(p) for p in (remote or []) if timestamp_of(p) > 0}
    return max(0, len(total) - len(merge_history(local, remote, max_points, max_chars)))


def load(source: str) -> str:
    """Texto del documento desde un fichero o desde una URL http(s)."""
    if source.startswith("http://") or source.startswith("https://"):
        with urlopen(source, timeout=20) as response:   # noqa: S310 - URL elegida por el usuario
            return response.read().decode("utf-8", errors="replace")
    with open(source, "r", encoding="utf-8") as handle:
        return handle.read()


def describe(report: dict) -> str:
    lines = [
        f"puntos: {report['points']}",
        f"tamaño: {report['size_chars']} caracteres (límite {MAX_CHARS}, puntos {MAX_POINTS})",
        f"orden cronológico: {'correcto' if report['order_ok'] else 'INCORRECTO (debe ir del más antiguo al más nuevo)'}",
        f"dentro de límites: {'sí' if report['within_caps'] else 'no'}",
    ]
    if report["duplicates"]:
        lines.append(f"instantes repetidos (la clave debe ser única): {len(report['duplicates'])}")
    if report["invalid_timestamps"]:
        lines.append(f"puntos sin instante válido: {len(report['invalid_timestamps'])}")
    if report["problems"]:
        lines.append(f"puntos con problemas: {len(report['problems'])}")
        for problem in report["problems"][:10]:
            lines.append(f"  · índice {problem['index']}: {'; '.join(problem['issues'][:4])}")
        if len(report["problems"]) > 10:
            lines.append(f"  · … y {len(report['problems']) - 10} más")
    lines.append("resultado: " + ("VÁLIDO" if report["ok"] else "NO CUMPLE EL CONTRATO"))
    return "\n".join(lines)


def main(argv: Iterable[str] | None = None) -> int:
    arguments = list(sys.argv[1:] if argv is None else argv)
    if not arguments:
        print(__doc__.strip())
        return 2

    if arguments[0] == "--merge":
        if len(arguments) < 3:
            print("uso: python tools/gist_schema.py --merge local.json remoto.json")
            return 2
        local = parse_document(load(arguments[1]))
        remote = parse_document(load(arguments[2]))
        merged = merge_history(local, remote)
        print(f"local: {len(local)} puntos · remoto: {len(remote)} puntos")
        print(f"fusionado: {len(merged)} puntos · descartados por límites: "
              f"{deleted_on_merge(local, remote)}")
        if merged:
            first, last = timestamp_of(merged[0]), timestamp_of(merged[-1])
            print(f"rango temporal: {first} … {last}")
        return 0

    try:
        document = parse_document(load(arguments[0]))
    except (ValueError, OSError) as error:
        print(f"no se pudo leer el documento: {error}")
        return 1

    report = validate_document(document)
    print(describe(report))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
