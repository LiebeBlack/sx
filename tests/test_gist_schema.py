"""Pruebas del contrato del canal Gist: validación, orden y reglas de fusión.

Son las reglas que en el móvil ejecuta `GistPublisher.java` y que aquí se pueden ejecutar en CI:
fusión por `timestamp_ms`, orden cronológico y doble recorte (número de puntos y caracteres).
"""

from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "tools"))

import gist_schema  # noqa: E402

BASE_TS = 1_758_100_000_000


def point(ts: int, *, latitude: float = 10.9578123, longitude: float = -63.8696456,
          provider: str = "fused/gms", battery: int = 85, **extra) -> dict:
    """Punto que cumple el contrato entero: es el patrón de referencia de las pruebas."""
    record = {
        "location": {
            "latitude": latitude, "longitude": longitude, "altitude": 15.2,
            "altitude_source": "msl", "accuracy_meters": 3.5, "vertical_accuracy_meters": 6.0,
            "speed_mps": 1.25, "speed_kmh": 4.5, "bearing_degrees": 180.0,
            "heading_magnetic_degrees": 181.4, "declination_degrees": -1.4,
            "provider": provider, "mock": False, "smooth_latitude": latitude,
            "smooth_longitude": longitude, "cells": 3, "wifi_aps": 6,
        },
        "telemetry": {
            "timestamp_ms": ts, "elapsed_nanos": 480_123_456_789, "battery_level": battery,
            "is_charging": False, "network_type": "wifi", "data_network": "5G",
            "network_label": "WIFI_5G", "operator": "MOVISTAR", "activity": "walking",
            "steps": 8421, "device_id": "android-8f1c", "label": "Pixel 7", "model": "Pixel 7",
            "sdk": 35, "low_ram": False, "source": "android",
        },
        "status": {
            "is_tracking": True, "fallback_active": False, "last_error": None,
            "local_points": 128, "remote_points": 96, "gist_configured": True,
        },
    }
    record["location"].update(extra)
    return record


class ContractTests(unittest.TestCase):
    def test_valid_point_has_no_issues(self) -> None:
        self.assertEqual([], gist_schema.validate_point(point(BASE_TS)))

    def test_missing_block_is_reported(self) -> None:
        broken = point(BASE_TS)
        del broken["status"]
        issues = gist_schema.validate_point(broken)
        self.assertTrue(any("status" in issue for issue in issues))

    def test_missing_key_inside_block_is_reported(self) -> None:
        broken = point(BASE_TS)
        del broken["telemetry"]["timestamp_ms"]
        issues = gist_schema.validate_point(broken)
        self.assertTrue(any("timestamp_ms" in issue for issue in issues))

    def test_null_is_rejected_where_contract_forbids_it(self) -> None:
        broken = point(BASE_TS)
        broken["location"]["accuracy_meters"] = None
        issues = gist_schema.validate_point(broken)
        self.assertTrue(any("accuracy_meters" in issue for issue in issues))

    def test_null_is_allowed_where_contract_permits_it(self) -> None:
        allowed = point(BASE_TS)
        allowed["location"]["altitude"] = None
        allowed["location"]["speed_kmh"] = None
        allowed["location"]["smooth_latitude"] = None
        allowed["status"]["last_error"] = None
        self.assertEqual([], gist_schema.validate_point(allowed))

    def test_out_of_range_coordinates(self) -> None:
        issues = gist_schema.validate_point(point(BASE_TS, latitude=91.0, longitude=-200.0))
        self.assertTrue(any("latitude" in issue for issue in issues))
        self.assertTrue(any("longitude" in issue for issue in issues))

    def test_non_boolean_flag_is_rejected(self) -> None:
        broken = point(BASE_TS)
        broken["telemetry"]["is_charging"] = 0
        issues = gist_schema.validate_point(broken)
        self.assertTrue(any("is_charging" in issue for issue in issues))

    def test_sentinel_battery_is_accepted(self) -> None:
        self.assertEqual([], gist_schema.validate_point(point(BASE_TS, battery=-1)))

    def test_timestamp_must_be_positive(self) -> None:
        self.assertTrue(any("timestamp_ms" in issue
                            for issue in gist_schema.validate_point(point(0))))

    def test_document_reports_duplicates_and_order(self) -> None:
        document = [point(BASE_TS), point(BASE_TS + 1_000), point(BASE_TS + 1_000)]
        report = gist_schema.validate_document(document)
        self.assertEqual(1, len(report["duplicates"]))
        self.assertFalse(report["ok"])

        unordered = [point(BASE_TS + 5_000), point(BASE_TS)]
        report = gist_schema.validate_document(unordered)
        self.assertFalse(report["order_ok"])
        self.assertIn("del más antiguo al más nuevo", gist_schema.describe(report))

    def test_document_without_timestamps_is_flagged(self) -> None:
        broken = point(BASE_TS)
        del broken["telemetry"]["timestamp_ms"]
        report = gist_schema.validate_document([broken])
        self.assertEqual([0], report["invalid_timestamps"])

    def test_readme_example_shape_is_valid(self) -> None:
        document = [point(BASE_TS), point(BASE_TS + 5_000, latitude=10.95781)]
        report = gist_schema.validate_document(document)
        self.assertTrue(report["ok"], gist_schema.describe(report))
        self.assertTrue(report["within_caps"])

    def test_size_cap_is_reported(self) -> None:
        document = [point(BASE_TS + index) for index in range(gist_schema.MAX_POINTS + 1)]
        report = gist_schema.validate_document(document)
        self.assertFalse(report["within_caps"])


class ParsingTests(unittest.TestCase):
    def test_object_is_wrapped(self) -> None:
        self.assertEqual(1, len(gist_schema.parse_document(json.dumps(point(BASE_TS)))))

    def test_garbage_raises_value_error(self) -> None:
        with self.assertRaises(ValueError):
            gist_schema.parse_document("{no es json}")
        with self.assertRaises(ValueError):
            gist_schema.parse_document("   ")
        with self.assertRaises(ValueError):
            gist_schema.parse_document("42")

    def test_non_object_items_are_dropped(self) -> None:
        text = json.dumps([point(BASE_TS), "basura", 7, None])
        self.assertEqual(1, len(gist_schema.parse_document(text)))


class MergeTests(unittest.TestCase):
    def test_merges_without_duplicating_timestamps(self) -> None:
        remote = [point(BASE_TS), point(BASE_TS + 1_000)]
        local = [point(BASE_TS + 1_000), point(BASE_TS + 2_000)]
        merged = gist_schema.merge_history(local, remote)
        stamps = [gist_schema.timestamp_of(item) for item in merged]
        self.assertEqual([BASE_TS, BASE_TS + 1_000, BASE_TS + 2_000], stamps)

    def test_result_is_oldest_first(self) -> None:
        local = [point(BASE_TS + 3_000), point(BASE_TS)]
        merged = gist_schema.merge_history(local, [])
        self.assertEqual([BASE_TS, BASE_TS + 3_000],
                         [gist_schema.timestamp_of(item) for item in merged])

    def test_local_wins_on_tie(self) -> None:
        remote = [point(BASE_TS, provider="network")]
        local = [point(BASE_TS, provider="fused/gms")]
        merged = gist_schema.merge_history(local, remote)
        self.assertEqual(1, len(merged))
        self.assertEqual("fused/gms", merged[0]["location"]["provider"])

    def test_remote_history_survives_a_local_gap(self) -> None:
        remote = [point(BASE_TS), point(BASE_TS + 1_000), point(BASE_TS + 2_000)]
        merged = gist_schema.merge_history([point(BASE_TS + 3_000)], remote)
        self.assertEqual(4, len(merged))

    def test_points_without_timestamp_are_ignored(self) -> None:
        broken = point(BASE_TS)
        del broken["telemetry"]["timestamp_ms"]
        merged = gist_schema.merge_history([broken], [])
        self.assertEqual([], merged)

    def test_max_points_trims_the_oldest(self) -> None:
        local = [point(BASE_TS + index) for index in range(10)]
        merged = gist_schema.merge_history(local, [], max_points=4)
        stamps = [gist_schema.timestamp_of(item) for item in merged]
        self.assertEqual(4, len(merged))
        self.assertEqual(BASE_TS + 6, stamps[0])          # se descarta lo más antiguo
        self.assertEqual(BASE_TS + 9, stamps[-1])

    def test_char_budget_trims_the_oldest(self) -> None:
        local = [point(BASE_TS + index) for index in range(10)]
        one = len(gist_schema.compact_json(local[0])) + 1
        merged = gist_schema.merge_history(local, [], max_chars=one * 3)
        self.assertEqual(3, len(merged))
        self.assertEqual(BASE_TS + 7, gist_schema.timestamp_of(merged[0]))

    def test_deleted_on_merge_counts_the_loss(self) -> None:
        local = [point(BASE_TS + index) for index in range(10)]
        self.assertEqual(6, gist_schema.deleted_on_merge(local, [], max_points=4))
        self.assertEqual(0, gist_schema.deleted_on_merge(local, [], max_points=50))

    def test_merged_document_is_still_valid(self) -> None:
        remote = [point(BASE_TS + index) for index in range(5)]
        local = [point(BASE_TS + 5_000 + index) for index in range(5)]
        merged = gist_schema.merge_history(local, remote)
        report = gist_schema.validate_document(merged)
        self.assertTrue(report["ok"], gist_schema.describe(report))


class CommandLineTests(unittest.TestCase):
    def test_main_validates_a_file(self) -> None:
        with tempfile.TemporaryDirectory(prefix="gist-schema-") as tmp:
            target = Path(tmp) / "data.json"
            target.write_text(json.dumps([point(BASE_TS)]), encoding="utf-8")
            self.assertEqual(0, gist_schema.main([str(target)]))

            broken = Path(tmp) / "roto.json"
            broken.write_text("[{\"location\": {}}]", encoding="utf-8")
            self.assertEqual(1, gist_schema.main([str(broken)]))

            missing = Path(tmp) / "no-existe.json"
            self.assertEqual(1, gist_schema.main([str(missing)]))

    def test_main_without_arguments_explains_usage(self) -> None:
        self.assertEqual(2, gist_schema.main([]))

    def test_main_merge_mode(self) -> None:
        with tempfile.TemporaryDirectory(prefix="gist-merge-") as tmp:
            local = Path(tmp) / "local.json"
            remote = Path(tmp) / "remote.json"
            local.write_text(json.dumps([point(BASE_TS + 1_000)]), encoding="utf-8")
            remote.write_text(json.dumps([point(BASE_TS), point(BASE_TS + 1_000)]), encoding="utf-8")
            self.assertEqual(0, gist_schema.main(["--merge", str(local), str(remote)]))


if __name__ == "__main__":
    unittest.main()
