"""Pruebas unitarias del ingest: validación, integridad, filtro de Kalman y altitud fusionada."""

from __future__ import annotations

import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "backend"))
os.environ["TELEMETRY_STORE"] = str(Path(tempfile.mkdtemp(prefix="telemetria-test-")) / "store.jsonl")
os.environ.pop("TELEMETRY_API_KEY", None)
os.environ["TELEMETRY_COMPACT_EVERY"] = "100000"

import app as telemetry  # noqa: E402

BASE_LAT = 40.4168
BASE_LNG = -3.7038
BASE_TS = 1_758_000_000_000
JITTER_DEG = 0.0003        # ±33 m


def sample(lat: float = BASE_LAT, lng: float = BASE_LNG, ts: int = BASE_TS, **extra) -> dict:
    payload = {"device_id": "dev-test", "lat": lat, "lng": lng, "timestamp": ts}
    payload.update(extra)
    return telemetry._sanitize(payload)


def spread(values) -> float:
    return max(values) - min(values)


class SanitizeTest(unittest.TestCase):
    def test_normaliza_segundos_a_milisegundos(self):
        self.assertEqual(sample(ts=1_758_000_000)["timestamp"], 1_758_000_000_000)

    def test_rechaza_datos_invalidos(self):
        for payload in (
            {"device_id": "d", "lat": 95.0, "lng": 0.0},
            {"device_id": "d", "lat": 0.0, "lng": 200.0},
            {"lat": 10.0, "lng": 10.0},
            {"device_id": "d", "lat": 0.0, "lng": 0.0},
            {"device_id": "d", "lat": 10.0, "lng": 10.0, "timestamp": 10},
        ):
            with self.assertRaises(ValueError):
                telemetry._sanitize(payload)

    def test_campos_opcionales_absurdos_se_descartan(self):
        point = sample(accuracy=10 ** 9, speed_mps="rápido", battery=-5, gnss={"used": 999})
        self.assertIsNone(point["accuracy"])
        self.assertIsNone(point["speed_mps"])
        self.assertIsNone(point["battery"])
        self.assertIsNone(point["gnss"])

    def test_acepta_alias_de_campos(self):
        point = telemetry._sanitize({"id": "alias", "latitude": 10.0, "longitude": 20.0, "ts": BASE_TS})
        self.assertEqual(point["device_id"], "alias")
        self.assertEqual((point["lat"], point["lng"]), (10.0, 20.0))


class DeltaTimeTest(unittest.TestCase):
    def test_usa_elapsed_nanos_cuando_es_monotono(self):
        previous = {"timestamp": BASE_TS, "elapsed_nanos": 1_000_000_000}
        current = {"timestamp": BASE_TS + 50_000, "elapsed_nanos": 3_000_000_000}
        self.assertAlmostEqual(telemetry._delta_seconds(previous, current), 2.0, places=6)

    def test_cae_al_timestamp_si_falta_el_reloj(self):
        previous = {"timestamp": BASE_TS, "elapsed_nanos": None}
        current = {"timestamp": BASE_TS + 4000, "elapsed_nanos": None}
        self.assertAlmostEqual(telemetry._delta_seconds(previous, current), 4.0, places=6)

    def test_ignora_el_reloj_si_no_avanza(self):
        previous = {"timestamp": BASE_TS, "elapsed_nanos": 9_000}
        current = {"timestamp": BASE_TS + 2000, "elapsed_nanos": 5_000}
        self.assertAlmostEqual(telemetry._delta_seconds(previous, current), 2.0, places=6)


class IngestTest(unittest.TestCase):
    def setUp(self):
        telemetry._devices.clear()
        telemetry._meta.clear()

    def test_primer_punto_no_suma_distancia(self):
        point = telemetry._ingest(sample())
        self.assertEqual(point["distance_m"], 0.0)
        self.assertEqual(point["raw_distance_m"], 0.0)
        self.assertAlmostEqual(point["smooth_lat"], BASE_LAT, places=6)
        self.assertEqual(telemetry._meta["dev-test"]["points"], 1)

    def test_duplicado_exacto_se_ignora(self):
        telemetry._ingest(sample())
        self.assertEqual(telemetry._ingest(sample()), {})

    def test_punto_antiguo_se_descarta(self):
        telemetry._ingest(sample(ts=BASE_TS + 10_000))
        self.assertEqual(telemetry._ingest(sample(ts=BASE_TS)), {})
        self.assertEqual(telemetry._meta["dev-test"]["dropped"], 1)

    def test_salto_imposible_marcado_como_outlier(self):
        telemetry._ingest(sample(accuracy=5.0))
        far = telemetry._ingest(sample(lat=BASE_LAT + 0.05, ts=BASE_TS + 1000, accuracy=5.0))
        self.assertTrue(far["outlier"])
        self.assertTrue(far["low_quality"])
        self.assertEqual(far["distance_m"], 0.0)
        self.assertGreater(far["raw_distance_m"], 5000.0)
        self.assertEqual(telemetry._meta["dev-test"]["outliers"], 1)

    def test_hueco_largo_reinicia_el_filtro_sin_sumar_tramo(self):
        telemetry._ingest(sample(accuracy=5.0))
        later = telemetry._ingest(sample(lat=BASE_LAT + 0.05, ts=BASE_TS + 600_000, accuracy=5.0))
        self.assertTrue(later["gap"])
        self.assertFalse(later.get("outlier", False))
        self.assertEqual(later["distance_m"], 0.0)
        self.assertEqual(telemetry._meta["dev-test"]["gaps"], 1)

    def test_fix_de_baja_calidad_no_suma_distancia(self):
        telemetry._ingest(sample(accuracy=5.0))
        poor = telemetry._ingest(sample(lat=BASE_LAT + 0.0005, ts=BASE_TS + 5000, accuracy=500.0))
        self.assertTrue(poor["low_quality"])
        self.assertEqual(poor["distance_m"], 0.0)

    def test_movimiento_real_suma_distancia_velocidad_y_rumbo(self):
        telemetry._ingest(sample(accuracy=5.0))
        moved = telemetry._ingest(sample(lat=BASE_LAT + 0.001, ts=BASE_TS + 5000, accuracy=5.0))
        self.assertFalse(moved.get("outlier", False))
        self.assertGreater(moved["distance_m"], 80.0)
        self.assertLess(moved["distance_m"], 130.0)
        self.assertIsNotNone(moved["bearing"])
        self.assertGreater(moved["speed_mps"], 5.0)

    def test_el_filtro_reduce_el_ruido_de_alta_frecuencia(self):
        for step in range(40):
            jitter = JITTER_DEG if step % 2 else -JITTER_DEG
            telemetry._ingest(sample(lat=BASE_LAT + jitter, ts=BASE_TS + step * 2000, accuracy=6.0))
        series = telemetry._devices["dev-test"]
        meta = telemetry._meta["dev-test"]
        self.assertLess(spread([p["smooth_lat"] for p in series]), spread([p["lat"] for p in series]) * 0.85)
        self.assertLess(meta["distance_m"], meta["raw_distance_m"] * 0.9)
        self.assertGreater(meta["noise_removed_m"], 0.0)

    def test_no_inventa_movimiento_en_parado(self):
        for step in range(20):
            telemetry._ingest(sample(ts=BASE_TS + step * 3000, accuracy=4.0))
        meta = telemetry._meta["dev-test"]
        self.assertEqual(meta["distance_m"], 0.0)
        self.assertLess(meta["max_speed_mps"], 1.0)

    def test_acumula_estadisticas_de_calidad(self):
        telemetry._ingest(sample(accuracy=8.0, activity="walking", gnss={"visible": 12, "used": 7, "snr_best": 34.0}))
        telemetry._ingest(sample(lat=BASE_LAT + 0.0005, ts=BASE_TS + 5000, accuracy=12.0))
        meta = telemetry._meta["dev-test"]
        self.assertEqual(meta["last_activity"], "walking")
        self.assertEqual(meta["satellites"], 7)
        self.assertAlmostEqual(meta["accuracy_sum"] / meta["accuracy_count"], 10.0, places=3)


class AltitudeFusionTest(unittest.TestCase):
    def setUp(self):
        telemetry._devices.clear()
        telemetry._meta.clear()

    def test_calibra_el_barometro_con_el_primer_fix_gps(self):
        first = telemetry._ingest(sample(accuracy=5.0, altitude=650.0, altitude_baro=1000.0))
        self.assertAlmostEqual(first["altitude_fused"], 650.0, places=1)
        second = telemetry._ingest(sample(lat=BASE_LAT + 0.0005, ts=BASE_TS + 5000, accuracy=5.0, altitude_baro=1005.0))
        self.assertAlmostEqual(second["altitude_fused"], 655.0, places=1)

    def test_no_calibra_con_un_fix_de_baja_calidad(self):
        point = telemetry._ingest(sample(accuracy=180.0, altitude=700.0, altitude_baro=1000.0))
        self.assertIsNone(telemetry._meta["dev-test"]["baro_offset"])
        self.assertAlmostEqual(point["altitude_fused"], 700.0, places=1)

    def test_sin_barometro_usa_la_altitud_del_gps(self):
        point = telemetry._ingest(sample(accuracy=5.0, altitude=712.5))
        self.assertAlmostEqual(point["altitude_fused"], 712.5, places=1)


if __name__ == "__main__":
    unittest.main()
