"""Pruebas de los endpoints HTTP: ingesta, lectura compacta, autorización y borrado."""

from __future__ import annotations

import importlib
import os
import sys
import tempfile
import time
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend"))

import app as telemetry  # noqa: E402

API_KEY = "clave-de-prueba"


def load(api_key: str):
    """Reimporta el módulo con un almacén temporal y una clave de API concreta."""
    os.environ["TELEMETRY_STORE"] = str(Path(tempfile.mkdtemp(prefix="telemetria-api-")) / "store.jsonl")
    os.environ["TELEMETRY_COMPACT_EVERY"] = "100000"
    if api_key:
        os.environ["TELEMETRY_API_KEY"] = api_key
    else:
        os.environ.pop("TELEMETRY_API_KEY", None)
    module = importlib.reload(telemetry)
    module.app.config["TESTING"] = True
    return module


def point(**overrides) -> dict:
    payload = {
        "device_id": "api-dev",
        "label": "API",
        "lat": 40.4168,
        "lng": -3.7038,
        "timestamp": int(time.time() * 1000),
        "accuracy": 7.5,
        "provider": "fused",
        "gnss": {"visible": 14, "used": 8, "snr_best": 33.5},
    }
    payload.update(overrides)
    return payload


class ApiSinClaveTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load("")
        cls.client = cls.module.app.test_client()

    def setUp(self):
        self.module._devices.clear()
        self.module._meta.clear()

    def test_health(self):
        body = self.client.get("/api/health").get_json()
        self.assertTrue(body["ok"])
        self.assertFalse(body["auth"])
        # Los umbrales del filtro son observables: sirven para depurar trazas en producción.
        self.assertEqual(body["filter"]["max_jump_m"], 150.0)
        self.assertEqual(body["filter"]["plausible_speed_mps"], 60.0)
        self.assertEqual(body["filter"]["max_speed_mps"], 90.0)

    def test_ingesta_y_lectura_compacta(self):
        created = self.client.post("/api/location", json=point())
        self.assertEqual(created.status_code, 201)
        self.assertEqual(created.get_json()["stored"], 1)

        listed = self.client.get("/api/locations?format=compact&limit=10").get_json()
        self.assertTrue(listed["ok"])
        self.assertEqual(listed["count"], 1)
        device = listed["devices"][0]
        self.assertEqual(device["device_id"], "api-dev")
        # [ts, lat, lng, speed, bearing, accuracy, battery, activity, smooth_lat, smooth_lng]
        self.assertEqual(len(device["points"][0]), 10)
        self.assertAlmostEqual(device["points"][0][1], 40.4168, places=6)
        self.assertAlmostEqual(device["points"][0][8], 40.4168, places=6)   # smooth_lat del primer punto
        self.assertAlmostEqual(device["points"][0][9], -3.7038, places=6)   # smooth_lng del primer punto
        self.assertAlmostEqual(device["smooth"][0][1], 40.4168, places=6)
        self.assertEqual(device["summary"]["satellites"], 8)
        self.assertEqual(device["summary"]["points"], 1)

    def test_ingesta_por_lotes(self):
        batch = {"points": [point(lat=40.4168 + step * 0.0005, timestamp=int(time.time() * 1000) + step * 1000)
                            for step in range(3)]}
        body = self.client.post("/api/location", json=batch).get_json()
        self.assertEqual(body["stored"], 3)
        self.assertEqual(body["devices"], ["api-dev"])

    def test_lote_demasiado_grande(self):
        batch = {"points": [point() for _ in range(501)]}
        self.assertEqual(self.client.post("/api/location", json=batch).status_code, 413)

    def test_cuerpo_invalido(self):
        self.assertEqual(self.client.post("/api/location", data="{", content_type="application/json").status_code, 400)

    def test_punto_invalido_no_rompe_la_api(self):
        response = self.client.post("/api/location", json={"lat": 200.0, "lng": 0.0})
        self.assertEqual(response.status_code, 200)
        body = response.get_json()
        self.assertFalse(body["ok"])
        self.assertEqual(body["stored"], 0)
        self.assertTrue(body["errors"])

    def test_dispositivo_concreto_y_404(self):
        self.client.post("/api/location", json=point())
        found = self.client.get("/api/locations/api-dev?format=compact").get_json()
        self.assertTrue(found["ok"])
        self.assertEqual(found["device_id"], "api-dev")
        self.assertEqual(self.client.get("/api/locations/no-existe").status_code, 404)

    def test_borrado_de_dispositivo(self):
        self.client.post("/api/location", json=point())
        deleted = self.client.delete("/api/locations/api-dev").get_json()
        self.assertTrue(deleted["ok"])
        self.assertEqual(deleted["removed"], 1)
        self.assertEqual(self.client.get("/api/locations/api-dev").status_code, 404)

    def test_cors_y_cache(self):
        response = self.client.get("/api/locations")
        self.assertEqual(response.headers["Access-Control-Allow-Origin"], "*")
        self.assertIn("no-store", response.headers["Cache-Control"])

    def test_sirve_el_visualizador(self):
        response = self.client.get("/")
        try:
            self.assertEqual(response.status_code, 200)
            self.assertIn("TELEMETRÍA", response.get_data(as_text=True))
        finally:
            # El visor se sirve con send_file: sin cerrar la respuesta, el descriptor del fichero
            # queda abierto hasta que el recolector lo note (ResourceWarning en CI).
            response.close()


class ApiConClaveTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.module = load(API_KEY)
        cls.client = cls.module.app.test_client()

    def setUp(self):
        self.module._devices.clear()
        self.module._meta.clear()

    def test_sin_clave_devuelve_401(self):
        self.assertEqual(self.client.post("/api/location", json=point()).status_code, 401)
        self.assertEqual(self.client.delete("/api/locations/api-dev").status_code, 401)

    def test_cabecera_x_api_key(self):
        response = self.client.post("/api/location", json=point(), headers={"X-Api-Key": API_KEY})
        self.assertEqual(response.status_code, 201)

    def test_clave_en_query(self):
        response = self.client.delete(f"/api/locations/api-dev?key={API_KEY}")
        self.assertIn(response.status_code, (200, 404))

    def test_lectura_sigue_abierta(self):
        self.assertEqual(self.client.get("/api/locations").status_code, 200)

    def test_health_anuncia_autenticacion(self):
        self.assertTrue(self.client.get("/api/health").get_json()["auth"])


if __name__ == "__main__":
    unittest.main()
