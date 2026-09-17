"""Publica el estado consolidado en GitHub (Contents API) para consumo estático en Pages.

Diseñado para producción sin servidor público propio: el backend corre en cualquier máquina
(VPS, LAN, portátil) y el resultado es un JSON plano que GitHub Pages sirve como
``https://usuario.github.io/repo/data/latest.json``.

Seguridad y límites:
    · El token debe tener SOLO "Contents: read and write" del repo de destino (fine-grained).
    · Publicación acotada: como mucho cada GITHUB_MIN_INTERVAL segundos y solo si el contenido
      cambió (hash), lo que mantiene el consumo de la API muy por debajo del límite (5000/h).
    · Fusión conservadora por device_id: conserva otros dispositivos publicados por clientes
      directos y reintenta ante 409 (sha obsoleto). Nada de ``git push`` local.
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import threading
import time
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional

API_BASE = "https://api.github.com/repos/"
MIN_INTERVAL_S = float(os.environ.get("GITHUB_MIN_INTERVAL", "60"))
MAX_ATTEMPTS = 3
TIMEOUT_S = 15
MAX_POINTS_PER_DEVICE = 2000

_lock = threading.Lock()
_last_body_hash: Optional[str] = None
_last_publish_at = 0.0
last_status = "sin publicar"
last_error: Optional[str] = None


def _config() -> Optional[Dict[str, str]]:
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    repo = os.environ.get("GITHUB_REPO", "").strip().strip("/")
    if not token or "/" not in repo:
        return None
    return {
        "token": token,
        "repo": repo,
        "branch": os.environ.get("GITHUB_BRANCH", "main").strip() or "main",
        "path": os.environ.get("GITHUB_PATH", "data/latest.json").strip() or "data/latest.json",
    }


def enabled() -> bool:
    return _config() is not None


def status() -> Dict[str, Any]:
    return {
        "enabled": enabled(),
        "last_status": last_status,
        "last_error": last_error,
        "interval_s": MIN_INTERVAL_S,
    }


def _api_request(method: str, url: str, token: str, body: Optional[Dict[str, Any]] = None) -> tuple[int, Dict[str, Any]]:
    data = None
    headers = {
        "Accept": "application/vnd.github+json",
        "User-Agent": "telemetry-mirror/1.2",
        "X-GitHub-Api-Version": "2022-11-28",
        "Authorization": f"Bearer {token}",
    }
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=TIMEOUT_S) as response:
            raw = response.read(512 * 1024).decode("utf-8", "replace")
            return response.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as exc:
        raw = exc.read(16 * 1024).decode("utf-8", "replace")
        try:
            return exc.code, json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            return exc.code, {"message": raw[:200]}
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        return 0, {"message": str(exc)}


def _download(cfg: Dict[str, str]) -> tuple[Optional[Dict[str, Any]], Optional[str]]:
    status_code, body = _api_request(
        "GET", f"{API_BASE}{cfg['repo']}/contents/{cfg['path']}?ref={cfg['branch']}", cfg["token"]
    )
    if status_code != 200 or not isinstance(body, dict):
        return None, None    # 404: primera publicación
    try:
        content = base64.b64decode(body.get("content", "")).decode("utf-8")
        return json.loads(content), body.get("sha")
    except (ValueError, json.JSONDecodeError):
        return None, None


def _merge(remote: Optional[Dict[str, Any]], snapshot: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Fusiona el estado del servidor con lo ya publicado (los clientes directos escriben ahí)."""
    by_id: Dict[str, Dict[str, Any]] = {}
    for entry in (remote or {}).get("devices", []):
        if isinstance(entry, dict) and entry.get("device_id"):
            by_id[entry["device_id"]] = entry
    for device in snapshot:
        trimmed = dict(device)
        if len(trimmed.get("points", [])) > MAX_POINTS_PER_DEVICE:
            trimmed["points"] = trimmed["points"][-MAX_POINTS_PER_DEVICE:]
            if trimmed.get("smooth"):
                trimmed["smooth"] = trimmed["smooth"][-MAX_POINTS_PER_DEVICE:]
        by_id[device["device_id"]] = trimmed
    return sorted(by_id.values(), key=lambda d: d.get("summary", {}).get("last_seen", 0), reverse=True)


def publish_if_due(snapshot: List[Dict[str, Any]]) -> bool:
    """Publica el estado si venció el intervalo y cambió el contenido. Thread-safe, no bloquea."""
    global _last_body_hash, _last_publish_at, last_status, last_error
    cfg = _config()
    if cfg is None or not snapshot:
        return False
    now = time.monotonic()
    with _lock:
        if now - _last_publish_at < MIN_INTERVAL_S:
            return False
        _last_publish_at = now

    file_payload = {"generated": int(time.time() * 1000), "devices": snapshot}
    encoded = json.dumps(file_payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    body_hash = hashlib.sha256(encoded).hexdigest()
    if body_hash == _last_body_hash:
        last_status = "sin cambios"
        return False

    for attempt in range(1, MAX_ATTEMPTS + 1):
        remote, sha = _download(cfg)
        merged = _merge(remote, snapshot)
        file_payload["devices"] = merged
        encoded = json.dumps(file_payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
        body_hash = hashlib.sha256(json.dumps(file_payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")).hexdigest()
        if body_hash == _last_body_hash:
            last_status = "sin cambios"
            return False
        payload: Dict[str, Any] = {
            "message": f"telemetría: estado consolidado ({len(merged)} dispositivos)",
            "branch": cfg["branch"],
            "content": base64.b64encode(encoded).decode("ascii"),
        }
        if sha:
            payload["sha"] = sha
        status_code, response = _api_request(
            "PUT", f"{API_BASE}{cfg['repo']}/contents/{cfg['path']}", cfg["token"], payload
        )
        if 200 <= status_code < 300:
            _last_body_hash = body_hash
            last_status = f"publicado ({len(merged)} dispositivos)"
            last_error = None
            return True
        if status_code in (409, 422):
            continue    # sha obsoleto: relee y reintenta
        last_status = f"HTTP {status_code}"
        last_error = str(response.get("message", ""))[:200]
        return False
    last_status = "conflicto persistente"
    last_error = "sha obsoleto tras varios intentos"
    return False
