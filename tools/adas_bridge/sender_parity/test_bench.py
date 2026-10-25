"""Focused host-adapter and strict comparator tests; synthetic fixtures are NOT stock evidence."""
import copy
import json
from pathlib import Path
import tempfile
import unittest

from tools.adas_bridge.sender_parity import bench


def header():
    return {"kind": "header", "schema": bench.INPUT_SCHEMA, "origin": "original-stock-dex", "start_ms": 100000,
            "locale": "EN", "gates": {"service": True, "switch": True, "no_sleep": True},
            "source": {key: "a" * 64 for key in bench.HASH_KEYS}, "fixture": "SYNTHETIC UNIT TEST, NOT ORIGINAL EVIDENCE"}


def event(seq, at, name, data=None):
    return {"kind": "event", "seq": seq, "at_ms": at, "event": name, "data": data or {}}


def native_path():
    return {"pathId": "9000000001", "groups": [], "trafficLights": [], "segments": [
        {"length": 100, "travelTime": 8, "mainAction": 0, "assistantAction": 36, "trafficLightNum": 0, "links": [
            {"topoId": "9223372036854775806", "roadClass": 9, "roadName": "Exact native road", "length": 100,
             "linkType": 0, "formway": 15, "hasTrafficLight": False, "laneNum": 2, "hasMultiOut": False,
             "mainAction": 0, "assistantAction": 0, "adcode": "990001", "ownership": 0, "hasParallel": False,
             "direction": 1, "hasMixFork": False, "lon": [419000001, 419000100], "lat": [143000001, 143000100]}]}]}


class ComparatorTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="sender-parity-test-")
        self.root = Path(self.tmp.name)
        self.inputs, self.reference, self.port = [self.root / f"{x}.jsonl" for x in ("inputs", "reference", "port")]
        bench.write_jsonl(self.inputs, [header(), event(0, 100001, "end")])
        self.rh = {"schema": bench.REFERENCE_SCHEMA, "kind": "header", "origin": "original-stock-dex",
                   "input_sha256": bench.digest(self.inputs), "source": header()["source"]}
        self.ph = {"kind": "header", "schema": "denza-stock-sender-port-v1", "input_header": header()}
        self.packet = {"kind": "fire", "at_ms": 100000, "seq": 0, "topic": "1125929972105217", "payloadHex": "0a020864"}

    def tearDown(self):
        self.tmp.cleanup()

    def compare(self, original=None, actual=None, **kwargs):
        bench.write_jsonl(self.reference, [self.rh] + (original if original is not None else [self.packet]))
        bench.write_jsonl(self.port, [self.ph] + (actual if actual is not None else [self.packet]))
        return bench.compare(self.inputs, self.reference, self.port, required_coverage=(), **kwargs)

    def test_identical_stream_equal_only_with_complete_coverage(self):
        self.assertEqual(self.compare()["verdict"], "EQUAL")
        report = bench.compare(self.inputs, self.reference, self.port)
        self.assertEqual(report["verdict"], "INCOMPLETE")
        self.assertIn({"missing_scenario_event": "progress"}, report["coverage_gaps"])

    def test_byte_change_detected_despite_same_decoded_unknowns(self):
        actual = {**self.packet, "payloadHex": self.packet["payloadHex"] + "1800"}
        report = self.compare(actual=[actual])
        self.assertEqual(report["verdict"], "DIFFERENT")
        self.assertEqual(report["mismatches"][0]["type"], "payload_bytes")

    def test_order_extra_and_missing_packets_are_not_resynchronized(self):
        other = {**self.packet, "topic": "1268847189590025"}
        report = self.compare([self.packet, other], [other, self.packet, other])
        self.assertEqual([m["type"] for m in report["mismatches"]], ["topic_order", "topic_order", "extra_port_packet"])
        self.assertEqual(self.compare([self.packet, other], [self.packet])["mismatches"][0]["type"], "missing_port_packet")

    def test_timing_tolerance_is_explicit_and_retained(self):
        actual = {**self.packet, "at_ms": 100007}
        self.assertEqual(self.compare(actual=[actual])["verdict"], "DIFFERENT")
        report = self.compare(actual=[actual], timing_tolerance_ms=7)
        self.assertEqual(report["verdict"], "EQUAL")
        self.assertEqual(report["allowed_nondeterminism"][0]["delta_ms"], 7)

    def test_route_timestamp_exception_cannot_mask_payload_or_envelope_change(self):
        def varint(n):
            b = bytearray()
            while n > 127:
                b.append((n & 127) | 128); n >>= 7
            return bytes(b) + bytes([n])
        def pb(time, payload='{"native":1}', suffix=b""):
            s = json.dumps({"currentIndex": 0, "length": 1, "pathCount": 1, "payload": payload, "time": time}, separators=(",", ":")).encode()
            inner = b"\x18\x01\x22" + varint(len(s)) + s + suffix
            return b"\x0a" + varint(len(inner)) + inner
        self.assertTrue(bench.route_wall_time_only(pb(123), pb(456789))[0])
        self.assertFalse(bench.route_wall_time_only(pb(123), pb(456789, '{"native":2}'))[0])
        self.assertFalse(bench.route_wall_time_only(pb(123), pb(456789, suffix=b"\x10\x00"))[0])
        self.assertFalse(bench.route_wall_time_only(pb(123), b"\x8a\x00" + pb(456789)[1:])[0])
        original = {**self.packet, "topic": str(bench.ROUTE_TOPIC), "payloadHex": pb(123).hex()}
        actual = {**original, "payloadHex": pb(456789).hex()}
        self.assertEqual(self.compare([original], [actual])["verdict"], "DIFFERENT")
        report = self.compare([original], [actual], allow_route_wall_clock=True)
        self.assertEqual(report["verdict"], "EQUAL")
        self.assertEqual(report["allowed_nondeterminism"][0]["original"], 123)

    def test_causal_comparison_keeps_global_fail_and_uses_no_resynchronization(self):
        rows = [header(), event(0, 100000, "location", {}), event(1, 100001, "end")]
        bench.write_jsonl(self.inputs, rows)
        self.rh["input_sha256"] = bench.digest(self.inputs)
        original = {**self.packet, "raw": {"sender_input_seq": 0}}
        actual = {**self.packet, "input_seq": 0}
        extra = {**self.packet, "input_seq": None}
        report = self.compare([original], [extra, actual])
        self.assertEqual(report["verdict"], "DIFFERENT")
        self.assertEqual(report["causal_comparison"]["equal_inputs"], 1)
        self.assertFalse(report["causal_comparison"]["affects_global_verdict"])
        actual["payloadHex"] += "1801"
        report = self.compare([original], [extra, actual])
        causal = report["causal_comparison"]["inputs"][0]
        self.assertEqual(causal["verdict"], "DIFFERENT")
        self.assertEqual(causal["differences"][0]["type"], "causal_payload_bytes")
        original.pop("raw")
        self.assertEqual(self.compare([original], [actual])["causal_comparison"]["inputs"][0]["verdict"], "UNATTRIBUTED")

    def test_transport_lifecycle_cannot_be_silently_dropped(self):
        lifecycle = {"kind": "stop_service", "at_ms": 100000, "topic": "123"}
        report = self.compare([self.packet, lifecycle], [self.packet])
        self.assertEqual(report["verdict"], "INCOMPLETE")
        self.assertEqual(report["transport_lifecycle"]["verdict"], "DIFFERENT")
        self.assertIn("transport_lifecycle_unrepresented_or_different", report["coverage_gaps"][0])
        port_lifecycle = {"kind": "note", "note": "stop_service", "at_ms": 100000, "data": {"topic": "123"}}
        report = self.compare([self.packet, lifecycle], [self.packet, port_lifecycle])
        self.assertEqual(report["verdict"], "EQUAL")

    def test_reference_cannot_be_a_port_or_from_other_inputs(self):
        self.rh["origin"] = "port"
        with self.assertRaisesRegex(ValueError, "original-stock-dex"):
            self.compare()
        self.rh["origin"] = "original-stock-dex"
        self.rh["input_sha256"] = "b" * 64
        with self.assertRaisesRegex(ValueError, "digest mismatch"):
            self.compare()

    def test_coverage_gap_cannot_pass(self):
        gap = {"kind": "note", "note": "coverage_gap", "data": {"curPointIdx": 2}}
        self.assertEqual(self.compare(actual=[self.packet, gap])["verdict"], "INCOMPLETE")

    def test_artifact_bytes_verified(self):
        import hashlib
        import zipfile
        apk, dex, recorder = [self.root / role for role in ("apk", "dex", "recorder")]
        dex.write_bytes(b"original-dex");recorder.write_bytes(b"recording-source")
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("classes15.dex", dex.read_bytes())
        source = {"apk_sha256": bench.digest(apk), "dex_sha256": bench.digest(dex), "recorder_sha256": bench.digest(recorder),
                  "dex_entry": "classes15.dex", "dex_entries": {"classes15.dex": bench.digest(dex)}}
        files = [{"role": role, "path": str(path), "sha256": bench.digest(path)} for role, path in (("apk", apk), ("dex", dex), ("recorder", recorder))]
        files.append({"role": "dex_component", "entry": "classes15.dex", "path": str(dex), "sha256": bench.digest(dex)})
        manifest = self.root / "artifacts.json";manifest.write_text(json.dumps({"artifacts": files}))
        self.assertEqual(len(bench.validate_artifacts(manifest, source)), 4)
        source["dex_entries"]["classes2.dex"] = "a" * 64
        with self.assertRaisesRegex(ValueError, "every original APK DEX entry"):
            bench.validate_artifacts(manifest, source)
        del source["dex_entries"]["classes2.dex"]
        dex.write_text("changed")
        with self.assertRaisesRegex(ValueError, "do not match"):
            bench.validate_artifacts(manifest, source)

    def test_runtime_identity_rehashes_report_and_loaded_agent(self):
        import zipfile
        apk, dex, recorder, agent, report_path = [self.root / role for role in
                                                ("apk", "dex", "recorder", "agent.js", "report.json")]
        dex.write_bytes(b"original-dex");recorder.write_bytes(b"recording-source");agent.write_bytes(b"loaded-script")
        with zipfile.ZipFile(apk, "w") as archive:
            archive.writestr("classes15.dex", dex.read_bytes())
        source = {"apk_sha256": bench.digest(apk), "dex_sha256": bench.digest(dex), "recorder_sha256": bench.digest(recorder),
                  "dex_entry": "classes15.dex", "dex_entries": {"classes15.dex": bench.digest(dex)}}
        report_path.write_text(json.dumps({"apk_sha256": source["apk_sha256"], "agent_sha256": bench.digest(agent)}))
        files = [{"role": role, "path": str(path), "sha256": bench.digest(path)} for role, path in
                 (("apk", apk), ("dex", dex), ("recorder", recorder), ("runtime_agent", agent), ("runtime_report", report_path))]
        identity = {"apk_sha256": source["apk_sha256"], "agent_sha256": bench.digest(agent),
                    "actual_agent_sha256": bench.digest(agent), "agent_path": str(agent),
                    "report_path": str(report_path), "report_sha256": bench.digest(report_path)}
        original = {"schema": "denza-stock-sender-capture-v1", "artifacts": files, "runtime_identity": identity}
        manifest_path = self.root / "manifest.json"
        def validate(value):
            manifest_path.write_text(json.dumps(value))
            return bench.validate_artifacts(manifest_path, source)
        self.assertEqual({a["role"] for a in validate(original)},
                         {"apk", "dex", "recorder", "runtime_agent", "runtime_report"})
        for path in (agent, report_path):
            with self.subTest(tampered=path.name):
                saved = path.read_bytes()
                path.write_bytes(saved + b"tampered")
                with self.assertRaisesRegex(ValueError, "runtime .* bytes differ"):
                    validate(original)
                path.write_bytes(saved)
                path.unlink()
                with self.assertRaisesRegex(ValueError, "artifact missing"):
                    validate(original)
                path.write_bytes(saved)
        for role in ("runtime_agent", "runtime_report"):
            with self.subTest(missing=role):
                value = copy.deepcopy(original)
                value["artifacts"] = [a for a in value["artifacts"] if a["role"] != role]
                with self.assertRaisesRegex(ValueError, "exactly one " + role):
                    validate(value)
        value = copy.deepcopy(original)
        del value["runtime_identity"]
        with self.assertRaisesRegex(ValueError, "runtime_identity"):
            validate(value)
        # Even internally updated report metadata cannot claim a different APK.
        saved = report_path.read_bytes()
        report_path.write_text(json.dumps({"apk_sha256": "f" * 64, "agent_sha256": bench.digest(agent)}))
        value = copy.deepcopy(original)
        value["runtime_identity"]["report_sha256"] = bench.digest(report_path)
        next(a for a in value["artifacts"] if a["role"] == "runtime_report")["sha256"] = bench.digest(report_path)
        with self.assertRaisesRegex(ValueError, "runtime APK"):
            validate(value)
        report_path.write_bytes(saved)

    def test_missing_header_end_input_or_monotonic_time_rejected(self):
        with self.assertRaisesRegex(ValueError, "end"):
            bench.validate_inputs([header()])
        with self.assertRaisesRegex(ValueError, "monotonically"):
            bench.validate_inputs([header(), event(0, 1, "end")])
        with self.assertRaisesRegex(ValueError, "data snapshot"):
            bench.validate_inputs([header(), {"kind": "event", "seq": 0, "at_ms": 100001, "event": "end"}])


class ReplayTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(prefix="sender-parity-java-test-")
        cls.root = Path(cls.tmp.name)
        cls.inputs = cls.root / "inputs.jsonl"
        cls.output = cls.root / "port.jsonl"
        cls.rows = [header(), event(0, 100010, "nav_start"),
                    event(1, 100020, "route", {"path": native_path(), "amapRouteType": 1, "resend": False}),
                    event(2, 100030, "progress", {"pathId": "9000000001", "segIdx": 0, "linkIdx": 0, "curPointIdx": 1,
                                               "linkRemain": 91, "segRemain": 92, "routeRemain": 93, "remainLights": 0,
                                               "timeToFinishS": 7, "curLinkSpeed": 42, "crossManeuverID": 15}),
                    event(3, 100040, "location", {"matchedLon": 116.123456789, "matchedLat": 39.123456789,
                                               "rawLon": 116.123456987, "rawLat": 39.123456987,
                                               "speedKmh": 20.9, "vehicleSpeedKmh": 19.9, "heading": 21,
                                               "simulated": False, "roadClass": 9, "ownership": 0,
                                               "regionState": {"regionId": 710, "countryCode": "86", "provinceCode": 11, "cityCode": 12, "weather": 1}}),
                    event(4, 103000, "advance"), event(5, 103001, "end")]
        bench.write_jsonl(cls.inputs, cls.rows)
        cls.proof = bench.replay(cls.inputs, cls.output, cls.root / "classes")
        cls.result = bench.read_jsonl(cls.output)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_native_path_id_topology_and_distances_survive_without_yandex(self):
        from tools.adas_bridge.feed import TOPICS, decode
        route = next(row for row in self.result if row.get("kind") == "fire" and int(row["topic"]) == bench.ROUTE_TOPIC)
        decoded = decode(TOPICS[bench.ROUTE_TOPIC][1], bytes.fromhex(route["payloadHex"]))
        text = json.dumps(decoded, ensure_ascii=False)
        self.assertIn("9000000001", text)
        self.assertIn("9223372036854775806", text)
        self.assertIn("Exact native road", text)
        position = next(row for row in self.result if row.get("kind") == "fire" and row["topic"] == "1268847189590024")
        payload = decode(TOPICS[int(position["topic"])][1], bytes.fromhex(position["payloadHex"]))
        self.assertEqual(position["input_seq"], 3)
        flat = next(iter(payload.values()))
        self.assertIn(91, flat.values())
        self.assertIn(92, flat.values())
        self.assertEqual(self.proof["input_sha256"], bench.digest(self.inputs))

    def test_unrepresentable_native_point_index_retained_as_coverage_gap(self):
        self.assertIn({"curPointIdx": 1}, [row["data"] for row in self.result if row.get("note") == "coverage_gap"])

    def test_processing_boundary_cause_excludes_queued_stationary_and_timer_fires(self):
        rows = copy.deepcopy(self.rows)
        rows[4]["data"]["speedKmh"] = 0
        rows[4]["data"]["vehicleSpeedKmh"] = 0
        source, output = self.root / "stationary-input.jsonl", self.root / "stationary-port.jsonl"
        bench.write_jsonl(source, rows)
        import subprocess
        subprocess.run([str(bench.jdk() / "java"), "-cp", str(self.root / "classes"), "dev.denza.adaslink.NativeSenderReplay", str(source), str(output)],
                       check=True, capture_output=True, text=True)
        fires = [r for r in bench.read_jsonl(output) if r.get("kind") == "fire"]
        progress = [r for r in fires if r.get("input_seq") == 2]
        self.assertEqual([r["topic"] for r in progress], ["1268847189590023", "1268847189590027", "1268847189590026"])
        immediate_region = [r for r in fires if r.get("input_seq") == 3]
        self.assertEqual([r["topic"] for r in immediate_region], ["1268847189590025"])
        positions = [r for r in fires if r["topic"] == "1268847189590024"]
        self.assertGreaterEqual(len(positions), 3)
        self.assertTrue(all(r["input_seq"] is None for r in positions))

    def test_pre_G_progress_is_gap_and_does_not_borrow_future_native_path(self):
        rows = copy.deepcopy(self.rows)
        early = copy.deepcopy(rows[3]);early["at_ms"] = 100015
        wrong_path = copy.deepcopy(rows[3]);wrong_path["at_ms"] = 100025;wrong_path["data"]["pathId"] = "9000000002"
        rows = rows[:2] + [early, rows[2], wrong_path] + rows[3:]
        for seq, row in enumerate(rows[1:]):
            row["seq"] = seq
        source, output = self.root / "pre-G-input.jsonl", self.root / "pre-G-port.jsonl"
        bench.write_jsonl(source, rows)
        import subprocess
        subprocess.run([str(bench.jdk() / "java"), "-cp", str(self.root / "classes"), "dev.denza.adaslink.NativeSenderReplay", str(source), str(output)],
                       check=True, capture_output=True, text=True)
        result = bench.read_jsonl(output)
        gaps = [r["data"]["unrepresentable_native_progress_missing_earlier_path_snapshot"] for r in result
                if r.get("note") == "coverage_gap" and "unrepresentable_native_progress_missing_earlier_path_snapshot" in r["data"]]
        self.assertEqual(len(gaps), 2)
        self.assertEqual(gaps[0]["native_input"], early["data"])
        self.assertIsNone(gaps[0]["current_captured_pathId"])
        self.assertEqual(gaps[1]["native_input"]["pathId"], "9000000002")
        self.assertEqual(gaps[1]["current_captured_pathId"], "9000000001")
        self.assertFalse(any(r.get("kind") == "fire" and r.get("input_seq") in (early["seq"], wrong_path["seq"]) for r in result))
        routes = [r for r in result if r.get("kind") == "fire" and int(r["topic"]) == bench.ROUTE_TOPIC]
        self.assertEqual(len(routes), 1)
        self.assertGreaterEqual(routes[0]["at_ms"], rows[3]["at_ms"])
        # Malformed native input must still fail even though no earlier path snapshot exists.
        del rows[2]["data"]["curLinkSpeed"]
        bench.write_jsonl(source, rows)
        failed = subprocess.run([str(bench.jdk() / "java"), "-cp", str(self.root / "classes"), "dev.denza.adaslink.NativeSenderReplay", str(source), str(output)], capture_output=True, text=True)
        self.assertNotEqual(failed.returncode, 0)
        self.assertIn("missing native input: curLinkSpeed", failed.stderr)

    def test_native_run_failure_surfaces_real_diagnostic(self):
        from unittest.mock import patch
        import subprocess
        source, output = self.root / "valid-input.jsonl", self.root / "failed-port.jsonl"
        bench.write_jsonl(source, self.rows)
        failure = subprocess.CalledProcessError(1, ["java"], stderr="missing native input: roadClass")
        with patch.object(bench.subprocess, "run", side_effect=[subprocess.CompletedProcess([], 0), failure]):
            with self.assertRaisesRegex(RuntimeError, "Native sender replay failed.*exit 1") as raised:
                bench.replay(source, output, self.root / "mock-classes")
        self.assertIn("missing native input: roadClass", str(raised.exception))

    def test_source_has_no_yandex_converter_or_driver_call(self):
        text = bench.JAVA.read_text()
        self.assertNotIn("RouteConverter.convert(", text)
        self.assertNotIn("new NavigatorDriver", text)
        self.assertNotIn("YandexRoute.fromSnapshot", text)

    def test_missing_native_getter_fails_entire_replay(self):
        rows = copy.deepcopy(self.rows)
        del rows[2]["data"]["path"]["segments"][0]["links"][0]["direction"]
        missing_input = self.root / "missing-native.jsonl"
        bench.write_jsonl(missing_input, rows)
        import subprocess
        with self.assertRaises(subprocess.CalledProcessError) as raised:
            subprocess.run([str(bench.jdk() / "java"), "-cp", str(self.root / "classes"), "dev.denza.adaslink.NativeSenderReplay", str(missing_input), str(self.root / "missing-native-port.jsonl")],
                           check=True, capture_output=True, text=True)
        self.assertIn("missing native input: direction", raised.exception.stderr)


if __name__ == "__main__":
    unittest.main()
