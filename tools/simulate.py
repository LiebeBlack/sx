"""Generador de telemetría sintética con GNSS, sensores y ruido realista.

    python tools/simulate.py --url http://127.0.0.1:8000/api/location --devices 2 --interval 2
    python tools/simulate.py --devices 1 --noisy          # inyecta saltos y fixes de baja calidad
"""

from __future__ import annotations

import argparse
import json
import math
import random
import time
import urllib.error
import urllib.request

WIFI_POOL = [
    {"ssid": "Cafe_WiFi", "bssid": "a4:2b:8c:11:22:01", "rssi": -71, "frequency": 2437, "channel": 6},
    {"ssid": "MOVISTAR_5G", "bssid": "b0:4e:26:aa:bb:02", "rssi": -58, "frequency": 5180, "channel": 36},
    {"ssid": "Vecino_2.4", "bssid": "f8:32:e4:cc:dd:03", "rssi": -84, "frequency": 2462, "channel": 11},
    {"ssid": "IoT_AP", "bssid": "9c:5c:8e:ee:ff:04", "rssi": -77, "frequency": 2412, "channel": 1},
]
CONSTELLATIONS = "GPS,GLONASS,GALILEO,BEIDOU"


def post(url: str, key: str, payload: dict) -> tuple[int, str]:
    body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="POST")
    request.add_header("Content-Type", "application/json; charset=utf-8")
    if key:
        request.add_header("X-Api-Key", key)
    try:
        with urllib.request.urlopen(request, timeout=8) as response:
            return response.status, response.read(512).decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        return exc.code, exc.read(256).decode("utf-8", "replace")
    except urllib.error.URLError as exc:
        return 0, str(exc.reason)


def walk(state: dict, step: float) -> None:
    state["bearing"] = (state["bearing"] + random.uniform(-25, 25)) % 360
    state["speed"] = max(0.0, min(40.0, state["speed"] + random.uniform(-1.5, 1.5)))
    state["lat"] += math.cos(math.radians(state["bearing"])) * step
    state["lng"] += math.sin(math.radians(state["bearing"])) * step / max(0.2, math.cos(math.radians(state["lat"])))
    state["steps"] += int(state["speed"] * 1.4)


def activity_of(speed: float) -> str:
    if speed < 0.3:
        return "still"
    if speed < 1.8:
        return "walking"
    if speed < 4.0:
        return "running"
    return "vehicle"


def point_for(device_id: str, state: dict, noisy: bool) -> dict:
    accuracy = random.uniform(4.0, 18.0)
    lat, lng = state["lat"], state["lng"]
    speed = state["speed"]

    if noisy and random.random() < 0.06:
        # salto imposible (~300 m) o fix de baja calidad: el servidor debe filtrarlos
        if random.random() < 0.5:
            lat += random.uniform(-0.003, 0.003)
            lng += random.uniform(-0.003, 0.003)
            accuracy = random.uniform(90.0, 180.0)
        else:
            accuracy = random.uniform(160.0, 400.0)

    satellites_visible = random.randint(6, 22)
    return {
        "device_id": device_id,
        "label": device_id.upper(),
        "lat": round(lat, 7),
        "lng": round(lng, 7),
        "timestamp": int(time.time() * 1000),
        "elapsed_nanos": int(state["elapsed_ns"]),
        "accuracy": round(accuracy, 1),
        "vertical_accuracy": round(accuracy * random.uniform(1.2, 2.2), 1),
        "speed_accuracy": round(random.uniform(0.1, 0.8), 2),
        "bearing_accuracy": round(random.uniform(2.0, 12.0), 1),
        "altitude": round(650.0 + random.uniform(-8.0, 8.0), 1),
        "altitude_baro": round(state["baro"], 1),
        "pressure_hpa": round(1013.25 * (1.0 - state["baro"] / 44330.0) ** (1.0 / 0.1903), 2),
        "speed_mps": round(speed, 2),
        "bearing": round(state["bearing"], 1),
        "heading_magnetic": round((state["bearing"] + random.uniform(-4, 4)) % 360, 1),
        "battery": round(state["battery"], 1),
        "steps": state["steps"],
        "activity": activity_of(speed),
        "movement": round(random.uniform(0.02, 3.5), 2),
        "provider": "simulate",
        "source": "simulate.py",
        "model": "Simulator",
        "sdk": 34,
        "network": "wifi",
        "data_network": "5G",
        "operator": "SIM-ES",
        "mock": False,
        "gnss": {
            "visible": satellites_visible,
            "used": random.randint(4, min(satellites_visible, 14)),
            "snr_best": round(random.uniform(28.0, 45.0), 1),
            "snr_avg": round(random.uniform(18.0, 33.0), 1),
            "constellations": CONSTELLATIONS,
        },
        "wifi": random.sample(WIFI_POOL, 2),
        "cell": [
            {"type": "LTE", "mcc": 214, "mnc": 7, "cid": random.randint(1000, 9999),
             "tac": random.randint(100, 999), "rsrp": random.randint(-110, -70),
             "level": random.randint(1, 4), "registered": True}
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Simulador de clientes de telemetría")
    parser.add_argument("--url", default="http://127.0.0.1:8000/api/location")
    parser.add_argument("--key", default="", help="API key (X-Api-Key)")
    parser.add_argument("--devices", type=int, default=2)
    parser.add_argument("--interval", type=float, default=3.0)
    parser.add_argument("--lat", type=float, default=40.4168)
    parser.add_argument("--lng", type=float, default=-3.7038)
    parser.add_argument("--rounds", type=int, default=0, help="0 = infinito")
    parser.add_argument("--noisy", action="store_true", help="inyecta saltos y fixes malos")
    args = parser.parse_args()

    devices = {}
    for index in range(max(1, args.devices)):
        devices[f"sim-{index + 1:02d}"] = {
            "lat": args.lat + random.uniform(-0.002, 0.002),
            "lng": args.lng + random.uniform(-0.002, 0.002),
            "bearing": random.uniform(0, 360),
            "speed": random.uniform(0.5, 3.0),
            "baro": 650.0,
            "battery": random.uniform(20.0, 100.0),
            "steps": 0,
            "elapsed_ns": int(random.uniform(1e11, 5e11)),
        }

    round_number = 0
    while args.rounds == 0 or round_number < args.rounds:
        round_number += 1
        points = []
        for device_id, state in devices.items():
            walk(state, (state["speed"] * args.interval) / 111_320.0)
            state["elapsed_ns"] += int(args.interval * 1e9)
            state["baro"] += random.uniform(-1.2, 1.2)
            state["battery"] = max(1.0, state["battery"] - args.interval * 0.01)
            points.append(point_for(device_id, state, args.noisy))

        status, text = post(args.url, args.key, {"points": points})
        print(f"[{round_number}] HTTP {status} {len(points)} punto(s) -> {text[:110]}", flush=True)
        time.sleep(min(30.0, args.interval * 2) if status == 0 else args.interval)


if __name__ == "__main__":
    main()
