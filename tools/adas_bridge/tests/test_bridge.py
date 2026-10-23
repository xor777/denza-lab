import copy
import json
from pathlib import Path
import subprocess
import sys
import unittest

from tools.adas_bridge.bridge import (ContractError, MAIN_ACTION, ROOT, map_progress,
                                      normalize, recursive_diff, route_convert,
                                      signed64, stable_id, validate_payload)
from tools.adas_bridge.receiver import FakeReceiver
from tools.adas_bridge.bench import make_bench
from tools.adas_bridge.capture_replay import replay_capture
from tools.adas_bridge.watch import EmulatorReader, LiveBridge, validate_serial
from unittest.mock import patch
from tools.adas_bridge.dynamic import (make_dynamic_packets, encode_struct,
                                       decode_struct, POSITION, TRAFFIC)
from tools.adas_bridge.wire import (chunk_payload, decode_binder_route,
                                    decode_someip_route, encode_binder_route,
                                    encode_someip_route, reassemble_chunks)


def pos(edge, fraction=0):
    return {"segmentIndex": edge, "segmentPosition": fraction}


def fixture():
    return {"schema": "denza-yandex-driving-route-v1", "coordinateFrame": "wgs84",
            "pointOrder": "lon,lat", "routeId": "route-a", "generation": 7,
            "points": [[37.60, 55.75], [37.601, 55.75], [37.602, 55.75], [37.603, 55.75]],
            "sections": [{"geometry": {"begin": pos(0), "end": pos(1, .25)},
                          "metadata": {"annotation": {"action": "STRAIGHT", "toponym": "A"}}},
                         {"geometry": {"begin": pos(1, .25), "end": pos(2, 1)},
                          "metadata": {"annotation": {"action": "RIGHT", "toponym": "B"}}}],
            "speedLimits": [10, 10, None], "highways": [],
            "tunnels": [{"position": {"begin": pos(0, .5), "end": pos(1, .5)}}],
            "tollRoads": [], "hdSections": None, "standingSegments": [],
            "trafficLights": [{"position": pos(1, .25), "id": "light-1"}],
            "laneSigns": [{"position": pos(1), "lanes": [{}, {}, {}]}],
            "events": [], "jamSegments": [], "directionSigns": [], "zlevels": [],
            "speedBumps": [], "railwayCrossings": [], "pedestrianCrossings": []}


def sample(**patch):
    value = {"routeId": "route-a", "generation": 7, "capturedAtMs": 10000,
             "expiresAtMs": 12000, "status": "guidance", "matched": True,
             "position": pos(1, .25)}
    value.update(patch)
    return value


def native_location(wall=10000, uptime=3000):
    return {"longitude": 37.60125, "latitude": 55.75, "accuracy": 20.0,
            "speed": 2.5, "absoluteTimestamp": wall, "relativeTimestamp": uptime}


class GeometryTests(unittest.TestCase):
    def test_fractional_splits_and_endpoints_are_contiguous(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        links = converted["intermediate"]["links"]
        self.assertEqual([(r["sourceBegin"], r["sourceEnd"]) for r in links],
                         [(0, .5), (.5, 1.25), (1.25, 1.5), (1.5, 2), (2, 3)])
        candidate = converted["candidateNaviToEHPData"]
        self.assertEqual(validate_payload({"data": [candidate]}), [])
        self.assertAlmostEqual(sum(r["lengthM"] for r in links), converted["intermediate"]["lengthM"])
        for left, right in zip(links, links[1:]):
            self.assertEqual(left["sourcePointsWgs84"][-1], right["sourcePointsWgs84"][0])

    def test_boundary_signal_belongs_to_incoming_link_and_section(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        links = converted["intermediate"]["links"]
        self.assertEqual(links[1]["trafficLightCount"], 1)
        self.assertEqual(links[2]["trafficLightCount"], 0)
        segments = converted["candidateNaviToEHPData"]["guideGroups"][0]["segments"]
        self.assertEqual(segments[0]["trafficLightNum"], 1)
        self.assertEqual(segments[1]["trafficLightNum"], 0)

    def test_road_attributes_never_inferred_from_lanes_or_highway(self):
        source = fixture()
        source["highways"] = [{"position": {"begin": pos(0), "end": pos(2, 1)}}]
        candidate = route_convert(source, target_frame="wgs84")
        for link in candidate["candidateNaviToEHPData"]["links"]:
            for field in ("laneNum", "roadclass", "direction", "ownership", "formway", "mainAction"):
                self.assertIsNone(link[field])
        self.assertFalse(candidate["publicationReady"])

    def test_unknown_and_known_empty_are_distinct(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        availability = converted["intermediate"]["availability"]
        self.assertEqual(availability["highways"], "known")
        self.assertEqual(availability["hdSections"], "unknown")
        self.assertFalse(converted["intermediate"]["links"][0]["rangeMembership"]["highways"])
        self.assertIsNone(converted["intermediate"]["links"][0]["rangeMembership"]["hdSections"])
        source = fixture()
        source["availability"] = {"highways": "unknown"}
        self.assertIsNone(route_convert(source, target_frame="wgs84")["intermediate"]["links"][0]["rangeMembership"]["highways"])

    def test_actual_route_action_enum_separate_from_hud(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        segments = converted["candidateNaviToEHPData"]["guideGroups"][0]["segments"]
        self.assertEqual(segments[0]["navigationMainAction"], 2)
        self.assertIsNone(segments[1]["navigationMainAction"])
        self.assertEqual(MAIN_ACTION["ENTER_ROUNDABOUT"], 11)
        self.assertNotIn("EXIT_RIGHT", MAIN_ACTION)
        self.assertEqual(converted["intermediate"]["sections"][0]["endInstructionSourceSectionIndex"], 1)
        source = fixture()
        source["sections"][1]["metadata"]["annotation"]["action"] = "FINISH"
        self.assertIn("unmapped_route_actions:FINISH", route_convert(source, target_frame="wgs84")["exportBlockers"])

    def test_minimal_legacy_real_fixture_is_supported_honestly(self):
        source = {"routeId": "minimal", "points": [[37.6, 55.75], [37.61, 55.75]]}
        result = route_convert(source, target_frame="wgs84")
        self.assertIn("missing_manoeuvre_sections", result["exportBlockers"])
        self.assertFalse(result["intermediate"]["sectionsKnown"])

    def test_terminal_arrival_requires_known_endpoint_waypoints(self):
        source = fixture()
        source["wayPoints"] = [pos(0), pos(2, 1)]
        converted = route_convert(source, target_frame="wgs84")
        segment = converted["candidateNaviToEHPData"]["guideGroups"][0]["segments"][-1]
        self.assertEqual(segment["navigationMainAction"], 0)
        self.assertEqual(segment["navigationAssitAction"], 36)
        self.assertTrue(segment["isArriveWayPoint"])
        source["wayPoints"][-1] = pos(2, .9)
        segment = route_convert(source, target_frame="wgs84")["candidateNaviToEHPData"]["guideGroups"][0]["segments"][-1]
        self.assertIsNone(segment["navigationAssitAction"])

    def test_invalid_input_is_rejected(self):
        for patch in ({"points": [[0, 0]]}, {"points": [[0, 0], [0, 0]]},
                      {"points": [[37.6, float("nan")], [37.61, 55.75]]},
                      {"coordinateFrame": "native-gcj-unrestricted-candidate"},
                      {"speedLimits": [10]}, {"speedLimits": [10, -1, 0]}):
            source = fixture()
            source.update(patch)
            with self.subTest(patch=patch), self.assertRaises(ContractError):
                route_convert(source, target_frame="wgs84")

    def test_section_overlap_gap_and_fraction_are_rejected(self):
        for bad_begin in (pos(1, .2), pos(1, .3), pos(1, 1.2)):
            source = fixture()
            source["sections"][1]["geometry"]["begin"] = bad_begin
            with self.subTest(position=bad_begin), self.assertRaises(ContractError):
                normalize(source)

    def test_coordinate_transform_once_and_moscow_only(self):
        source = fixture()
        transformed = route_convert(source, target_frame="native-gcj-unrestricted-candidate")
        coordinate = transformed["candidateNaviToEHPData"]["pnts"][0]
        self.assertGreater(abs(coordinate["y"]-55.75), .001)
        self.assertEqual(source["points"][0], [37.6, 55.75])
        self.assertEqual(transformed["coordinateContract"]["transformApplications"], 1)
        source["points"] = [[-73, 40], [-72.9, 40], [-72.8, 40], [-72.7, 40]]
        with self.assertRaisesRegex(ContractError, "Moscow"):
            route_convert(source, target_frame="native-gcj-unrestricted-candidate")

    def test_fractional_progress_uses_new_section_at_boundary_and_remaining_offsets(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        result = map_progress(converted, sample(), now_ms=10500)
        self.assertTrue(result["accepted"])
        self.assertEqual(result["curStepId"], 1)
        self.assertEqual(result["curLinkId"], 0)
        self.assertEqual(result["globalLinkIndex"], 2)
        self.assertAlmostEqual(result["matchedWgs84"][0], 37.60125)
        self.assertAlmostEqual(result["linkOffset"], converted["intermediate"]["links"][2]["lengthM"])
        end = map_progress(converted, sample(position=pos(2, 1)), now_ms=10500)
        self.assertAlmostEqual(end["linkOffset"], 0)
        self.assertAlmostEqual(end["distanceToNextStep"], 0)
        self.assertAlmostEqual(end["matchedWgs84"][0], 37.603)

    def test_stale_offroute_and_reroute_progress_is_rejected(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        for change in ({"generation": 8}, {"routeId": "old"}, {"generation": None},
                       {"capturedAtMs": 11000}, {"expiresAtMs": 10500},
                       {"capturedAtMs": 8000}, {"matched": False}, {"status": "cancelled"}):
            with self.subTest(change=change):
                result = map_progress(converted, sample(**change), now_ms=10500)
                self.assertFalse(result["accepted"])

    def test_actual_guidance_schema_uses_freshness_and_explicit_clock(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        source = {"schema": "denza-yandex-guidance-v1", "coordinateFrame": "wgs84",
                  "routeId": "route-a", "generation": 7, "sequence": 21,
                  "capturedAtMs": 10000, "capturedUptimeMs": 3000,
                  "mode": "guidance", "freshLocation": True, "fixAgeMs": 500,
                  "sourcePolylinePosition": pos(1, .25), "location": native_location(),
                  "nextManoeuvre": {"action": "RIGHT"}}
        result = map_progress(converted, source, now_ms=10500)
        self.assertTrue(result["accepted"])
        self.assertEqual(result["sourceDynamic"]["candidateNextRouteMainAction"], 2)
        self.assertTrue(map_progress(converted, source, now_ms=3500, clock="uptime")["accepted"])
        for patch in ({"mode": "overview"}, {"freshLocation": False}, {"routeId": None},
                      {"fixAgeMs": 2100}, {"coordinateFrame": "unknown"}):
            self.assertFalse(map_progress(converted, dict(source, **patch), now_ms=10500)["accepted"])
        self.assertFalse(map_progress(converted, source, now_ms=12001)["accepted"])
        bad_pose = dict(source, location=dict(source["location"], longitude=0, latitude=0))
        self.assertEqual(map_progress(converted, bad_pose, now_ms=10500)["reason"], "location_source_geometry_mismatch")
        old_pose = dict(source, location=dict(source["location"], absoluteTimestamp=1000))
        self.assertFalse(map_progress(converted, old_pose, now_ms=10500)["accepted"])

    def test_new_session_requires_matching_route_position_owner(self):
        route = dict(fixture(), sourceSessionId="process-a")
        converted = route_convert(route, target_frame="wgs84")
        source = {"schema": "denza-yandex-guidance-v1", "coordinateFrame": "wgs84",
                  "sourceSessionId": "process-a", "routeId": "route-a", "generation": 7,
                  "capturedAtMs": 10000, "capturedUptimeMs": 3000,
                  "mode": "guidance", "freshLocation": True, "fixAgeMs": 0,
                  "sourcePolylinePosition": pos(1, .25), "location": native_location()}
        self.assertEqual(map_progress(converted, source, now_ms=10500)["reason"], "position_route_owner_unknown_or_mismatch")
        source.update(positionRouteId="route-b", positionMatchesRoute=True)
        self.assertEqual(map_progress(converted, source, now_ms=10500)["reason"], "position_route_owner_unknown_or_mismatch")
        source["positionRouteId"] = "route-a"
        self.assertTrue(map_progress(converted, source, now_ms=10500)["accepted"])
        source["sourceSessionId"] = "old-process"
        self.assertEqual(map_progress(converted, source, now_ms=10500)["reason"], "source_session_mismatch")

    def test_synthetic_ids_are_deterministic_and_signed64_exact(self):
        expected = stable_id("route", "абв")
        code = "from tools.adas_bridge.bridge import stable_id; print(stable_id('route','абв'))"
        self.assertEqual(int(subprocess.check_output([sys.executable, "-c", code], cwd=ROOT)), expected)
        for value in (-(1 << 63), (1 << 63)-1, 4950688668349104293):
            self.assertEqual(json.loads(json.dumps(value)), signed64(value))
        for value in (float(4950688668349104293), 1 << 63, -(1 << 63)-1, True):
            with self.assertRaises(ContractError):
                signed64(value)


class SequenceTests(unittest.TestCase):
    def setUp(self):
        self.receiver = FakeReceiver()
        self.converted = route_convert(fixture(), target_frame="wgs84")

    def select(self):
        self.receiver.offer_route(self.converted)
        path = self.converted["candidateNaviToEHPData"]["pathID"]
        self.receiver.select(path)
        self.receiver.confirm(path)

    def test_route_selection_confirmation_order(self):
        with self.assertRaises(ContractError):
            self.receiver.select(1)
        self.receiver.offer_route(self.converted)
        with self.assertRaises(ContractError):
            self.receiver.confirm(1)
        with self.assertRaises(ContractError):
            self.receiver.progress(sample(), now_ms=10500)

    def test_reroute_invalidates_confirmation_and_old_progress(self):
        self.select()
        self.assertTrue(self.receiver.progress(sample(), now_ms=10500)["accepted"])
        new = fixture()
        new.update(routeId="route-b", generation=8)
        converted = route_convert(new, target_frame="wgs84")
        self.receiver.offer_route(converted)
        self.assertIsNone(self.receiver.confirmed)
        with self.assertRaises(ContractError):
            self.receiver.progress(sample(), now_ms=10500)
        path = converted["candidateNaviToEHPData"]["pathID"]
        self.receiver.select(path)
        self.receiver.confirm(path)
        self.assertFalse(self.receiver.progress(sample(), now_ms=10500)["accepted"])
        self.assertEqual(self.receiver.status(now_ms=10500), 110)
        self.assertEqual(self.receiver.status(now_ms=13500), 100)

    def test_expiry_ends_feed_and_replay_rejected(self):
        self.select()
        self.receiver.progress(sample(), now_ms=10500)
        with self.assertRaisesRegex(ContractError, "replayed"):
            self.receiver.progress(sample(), now_ms=10600)
        stale = sample(capturedAtMs=11000, expiresAtMs=12000)
        self.assertFalse(self.receiver.progress(stale, now_ms=13000)["accepted"])
        self.assertEqual(self.receiver.status(now_ms=13000), 110)
        self.assertEqual(self.receiver.status(now_ms=16000), 100)

    def test_stale_to_fresh_same_source_generation_requires_new_epoch_ack(self):
        self.select()
        old_epoch = self.receiver.publication_epoch
        self.receiver.progress(sample(), now_ms=10500)
        stale = sample(capturedAtMs=11000, expiresAtMs=11500)
        self.assertFalse(self.receiver.progress(stale, now_ms=12000)["accepted"])
        self.receiver.republish_cached_route(now_ms=13000)
        self.assertEqual(self.receiver.generation, 7)
        path = self.converted["candidateNaviToEHPData"]["pathID"]
        self.receiver.select(path)
        with self.assertRaisesRegex(ContractError, "old publication epoch"):
            self.receiver.confirm(path, publication_epoch=old_epoch)
        self.receiver.confirm(path, publication_epoch=self.receiver.publication_epoch)
        old = sample(capturedAtMs=12500, expiresAtMs=14500, publicationEpoch=old_epoch)
        self.assertFalse(self.receiver.progress(old, now_ms=13000)["accepted"])
        fresh = sample(capturedAtMs=13000, expiresAtMs=15000)
        self.assertTrue(self.receiver.progress(fresh, now_ms=13000)["accepted"])


class WireTests(unittest.TestCase):
    GOLDEN = ROOT / "captures/stock-map-car-lanes-20261023/reports/route-wire/first-full/route-01.someip.bin"

    def test_binder_proto_is_distinct_and_roundtrips_utf8(self):
        text = '{"payload":"Москва","pathID":9223372036854775807}'
        binder = encode_binder_route(text, route_count=1, checksum=23, counter=9)
        decoded = decode_binder_route(binder)
        self.assertEqual(decoded["wireJson"], text)
        self.assertEqual(decoded["checksCRC32"], 23)
        self.assertEqual(decoded["counter"], 9)
        self.assertEqual(binder[0], 10)
        with self.assertRaises(ContractError):
            decode_someip_route(binder)

    def test_bad_wire_lengths_are_rejected(self):
        for data in (b"", b"\x0a\x80", b"\x0a\xff" * 6, b"\x0a\x03\x22\x08x"):
            with self.subTest(data=data), self.assertRaises(ContractError):
                decode_binder_route(data)

    def test_utf8_chunk_boundaries_do_not_split_characters(self):
        text = "Москва😎" * 5
        chunks = chunk_payload(text, time_ms=123, max_chunk_bytes=8)
        self.assertEqual("".join(row["payload"] for row in chunks), text)
        self.assertTrue(all(len(row["payload"].encode()) <= 8 for row in chunks))
        self.assertEqual([row["currentIndex"] for row in chunks], list(range(len(chunks))))
        self.assertEqual(reassemble_chunks(chunks), text)
        for broken in (chunks[:-1], list(reversed(chunks)), [dict(chunks[0], time=999)] + chunks[1:]):
            with self.assertRaises(ContractError):
                reassemble_chunks(broken)

    @unittest.skipUnless(GOLDEN.exists(), "untracked local stock golden unavailable")
    def test_actual_car_golden_raw_bytes_and_schema(self):
        original = self.GOLDEN.read_bytes()
        decoded = decode_someip_route(original)
        self.assertEqual(encode_someip_route(decoded), original)
        golden_json = json.loads(self.GOLDEN.with_name("route-01.payload.json").read_text())
        self.assertEqual(decoded["payload"], golden_json)
        self.assertEqual(validate_payload(golden_json), [])
        self.assertEqual(golden_json["data"][0]["links"][0]["linkID"], 4950688668349104293)
        binder = encode_binder_route(decoded["wireJson"], route_count=decoded["navigationSDLink2Num"])
        self.assertEqual(decode_binder_route(binder)["wireJson"], decoded["wireJson"])
        broken = bytearray(original)
        broken[26] ^= 1
        with self.assertRaises(ContractError):
            decode_someip_route(broken)

    @unittest.skipUnless(GOLDEN.exists(), "untracked local stock golden unavailable")
    def test_bench_consumes_real_bytes_with_separate_incomplete_profile(self):
        golden = decode_someip_route(self.GOLDEN.read_bytes())
        converted = route_convert(fixture(), target_frame="wgs84")
        bench = make_bench(converted, golden, time_ms=10000)
        route = bench["payload"]["data"][0]
        self.assertEqual(route["links"][0]["roadclass"], -1)
        self.assertIsNone(route["links"][0]["laneNum"])
        self.assertIsNone(converted["candidateNaviToEHPData"]["links"][0]["roadclass"])
        self.assertTrue(bench["manifest"]["remainingNullFields"])
        self.assertFalse(bench["manifest"]["validAdasRouteEstablished"])
        self.assertEqual(bench["receiver"].status(now_ms=10000), 101)
        self.assertEqual(decode_someip_route(bench["someip"])["payload"], bench["payload"])

    @unittest.skipUnless(GOLDEN.exists(), "untracked local stock golden unavailable")
    def test_native_capture_ring_recovers_without_faking_source_generation(self):
        golden = decode_someip_route(self.GOLDEN.read_bytes())
        def native(sequence, fresh=True):
            return {"schema": "denza-yandex-guidance-v1", "coordinateFrame": "wgs84",
                    "routeId": "route-a", "generation": 7, "sequence": sequence,
                    "capturedAtMs": 10000+sequence*1000, "capturedUptimeMs": 3000+sequence*1000,
                    "mode": "guidance", "freshLocation": fresh, "fixAgeMs": 100,
                    "location": native_location(10000+sequence*1000, 3000+sequence*1000),
                    "sourcePolylinePosition": pos(1, .25)}
        samples = [native(0), native(1, False), native(2)]
        replay, benches = replay_capture(fixture(), samples, golden, target_frame="wgs84")
        self.assertEqual(replay["sourceGenerations"], [7])
        self.assertEqual(replay["acceptedOfflineGeometrySamples"], 2)
        self.assertEqual(len(benches), 1)
        self.assertTrue(any(row["event"] == "republish_cached_route" for row in replay["trace"]))

    def test_diff_keeps_types_and_missing_distinct_from_null(self):
        self.assertEqual(recursive_diff({"x": 1}, {"x": 1.0})[0]["reason"], "type")
        self.assertEqual(recursive_diff({}, {"x": None})[0]["reason"], "missing_left")

    def test_watcher_refuses_car_serial_before_any_command(self):
        with patch("tools.adas_bridge.watch.subprocess.run") as run:
            for serial in ("127.0.0.1:5555", "192.168.1.2:5555", "car123", "emulator-5554;id"):
                with self.assertRaises(ContractError):
                    EmulatorReader("adb", serial, "ru.yandex.yandexnavi")
            run.assert_not_called()
        validate_serial("emulator-5592")

    def test_watcher_qemu_guard_blocks_reads(self):
        reader = EmulatorReader("adb", "emulator-5592", "ru.yandex.yandexnavi")
        with patch.object(reader, "command", return_value=b"0\n") as command:
            with self.assertRaises(ContractError):
                reader.verify()
            with self.assertRaises(ContractError):
                reader.json_file("denza-adas-progress.json")
            self.assertEqual(command.call_count, 1)

    @unittest.skipUnless(GOLDEN.exists(), "untracked local stock golden unavailable")
    def test_live_engine_bytes_expiry_cancel_and_recovery(self):
        engine = LiveBridge(decode_someip_route(self.GOLDEN.read_bytes()), "wgs84")
        def native(sequence):
            return {"schema": "denza-yandex-guidance-v1", "coordinateFrame": "wgs84",
                    "routeId": "route-a", "generation": 7, "sequence": sequence,
                    "capturedAtMs": 10000+sequence*1000, "capturedUptimeMs": 3000+sequence*1000,
                    "mode": "guidance", "freshLocation": True, "fixAgeMs": 100,
                    "location": native_location(10000+sequence*1000, 3000+sequence*1000),
                    "sourcePolylinePosition": pos(1, .25)}
        first = native(0)
        self.assertTrue(engine.tick(first, now_ms=3100, snapshot=fixture())["accepted"])
        self.assertEqual(engine.byte_receipts, 1)
        self.assertFalse(engine.tick(first, now_ms=3200)["accepted"])
        self.assertFalse(engine.tick(first, now_ms=5100)["accepted"])
        self.assertIsNotNone(engine.receiver.ended_at)
        self.assertTrue(engine.tick(native(3), now_ms=6100)["accepted"])
        self.assertEqual(engine.receiver.generation, 7)
        self.assertEqual(engine.byte_receipts, 2)
        cancelled = dict(native(4), routeId=None, mode="off", freshLocation=False)
        self.assertFalse(engine.tick(cancelled, now_ms=7100)["accepted"])
        mismatch = dict(native(5), routeId="different", generation=8)
        self.assertEqual(engine.tick(mismatch, now_ms=8100, snapshot=fixture())["reason"], "snapshot_progress_route_mismatch")

    @unittest.skipUnless(GOLDEN.exists(), "untracked local stock golden unavailable")
    def test_live_engine_process_session_restart_and_delayed_old_session(self):
        engine = LiveBridge(decode_someip_route(self.GOLDEN.read_bytes()), "wgs84")
        def native(session, generation, sequence, now):
            return {"schema": "denza-yandex-guidance-v1", "coordinateFrame": "wgs84",
                    "sourceSessionId": session, "routeId": "route-a", "generation": generation,
                    "positionRouteId": "route-a", "positionMatchesRoute": True,
                    "sequence": sequence, "capturedAtMs": now+7000, "capturedUptimeMs": now,
                    "mode": "guidance", "freshLocation": True, "fixAgeMs": 0,
                    "sourcePolylinePosition": pos(1, .25), "location": native_location(now+7000, now)}
        old = native("old-process", 7, 50, 3000)
        self.assertTrue(engine.tick(old, now_ms=3000, snapshot=dict(fixture(), sourceSessionId="old-process"))["accepted"])
        epoch = engine.receiver.publication_epoch
        new = native("new-process", 1, 0, 4000)
        self.assertEqual(engine.tick(new, now_ms=4000, snapshot=fixture())["reason"], "snapshot_progress_route_mismatch")
        self.assertTrue(engine.tick(new, now_ms=4000, snapshot=dict(fixture(), sourceSessionId="new-process"))["accepted"])
        self.assertGreater(engine.receiver.publication_epoch, epoch)
        self.assertEqual(engine.receiver.generation, 1)
        self.assertEqual(engine.tick(old, now_ms=4100, snapshot=fixture())["reason"], "retired_or_missing_source_session")

    @unittest.skipUnless(GOLDEN.exists(), "untracked local stock golden unavailable")
    def test_cancel_generation_rejects_queued_previous_route_sample(self):
        engine = LiveBridge(decode_someip_route(self.GOLDEN.read_bytes()), "wgs84")
        source = {"schema": "denza-yandex-guidance-v1", "coordinateFrame": "wgs84",
                  "routeId": "route-a", "generation": 7, "sequence": 10,
                  "capturedAtMs": 10000, "capturedUptimeMs": 3000,
                  "mode": "guidance", "freshLocation": True, "fixAgeMs": 0,
                  "sourcePolylinePosition": pos(1, .25), "location": native_location()}
        self.assertTrue(engine.tick(source, now_ms=3000, snapshot=fixture())["accepted"])
        cancelled = dict(source, routeId=None, generation=8, sequence=12, mode="off", freshLocation=False)
        self.assertFalse(engine.tick(cancelled, now_ms=3100)["accepted"])
        delayed = dict(source, sequence=11)
        self.assertEqual(engine.tick(delayed, now_ms=3200)["reason"], "old_source_generation_or_sequence")
        self.assertIsNotNone(engine.receiver.ended_at)

    def test_dynamic_proto_double_pathid_exact_and_unknown_fields_separate(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        path = converted["candidateNaviToEHPData"]["pathID"]
        self.assertLessEqual(path, (1 << 53)-1)
        source = sample()
        source["location"] = native_location()
        mapped = map_progress(converted, source, now_ms=10500)
        bundle = make_dynamic_packets(converted, source, mapped)
        position = bundle["packets"][0]
        decoded = decode_struct(bytes.fromhex(position["binderProtobufHex"]), POSITION)
        self.assertEqual(decoded["curSDRouteID"], path)
        self.assertEqual(decoded["latitude"], 55.75)
        self.assertEqual(decoded["longitude"], 37.60125)
        self.assertIn("currentRoadOwnership", position["missingAttributes"])
        self.assertIn("GPSspeed", position["missingAttributes"])
        traffic = bundle["packets"][1]
        self.assertEqual(decode_struct(bytes.fromhex(traffic["binderProtobufHex"]), TRAFFIC)["roadClass"], -1)
        # Generated protobuf int32 negatives use a sign-extended ten-byte varint.
        self.assertIn("ffffffffffffffffff01", traffic["binderProtobufHex"])
        bad = dict(mapped, curSDRouteID=(1 << 53)+1)
        with self.assertRaises(ContractError):
            make_dynamic_packets(converted, source, bad)


if __name__ == "__main__":
    unittest.main()
