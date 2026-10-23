import copy
import json
from pathlib import Path
import subprocess
import sys
import unittest

from tools.adas_bridge import stock
from tools.adas_bridge.bridge import (ContractError, MAIN_ACTION, ROOT, map_progress,
                                      normalize, recursive_diff, route_convert,
                                      signed64, stable_id, validate_payload)
from tools.adas_bridge.receiver import FakeReceiver
from tools.adas_bridge.bench import make_bench
from tools.adas_bridge.capture_replay import replay_capture
from tools.adas_bridge.replay import replay
from tools.adas_bridge.watch import EmulatorReader, LiveBridge, validate_serial
from unittest.mock import patch
from tools.adas_bridge.dynamic import (make_dynamic_packets, decode_struct, as_int32, POSITION, TRAFFIC)
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


def native(sequence, *, fresh=True, mode="guidance", position=None, step=1000, **extra):
    value = {"schema": "denza-yandex-guidance-v1", "coordinateFrame": "wgs84",
             "routeId": "route-a", "generation": 7, "sequence": sequence,
             "capturedAtMs": 10000+sequence*step, "capturedUptimeMs": 3000+sequence*step,
             "mode": mode, "freshLocation": fresh, "fixAgeMs": 0,
             "location": native_location(10000+sequence*step, 3000+sequence*step),
             "sourcePolylinePosition": position or pos(1, .25)}
    value.update(extra)
    return value


class GeometryTests(unittest.TestCase):
    def test_fractional_splits_and_endpoints_are_contiguous(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        links = converted["intermediate"]["links"]
        self.assertEqual([(r["sourceBegin"], r["sourceEnd"]) for r in links],
                         [(0, .5), (.5, 1.25), (1.25, 1.5), (1.5, 2), (2, 3)])
        candidate = converted["candidateNaviToEHPData"]
        self.assertEqual(validate_payload({"data": [candidate]}), [])
        self.assertAlmostEqual(sum(r["lengthM"] for r in links), converted["intermediate"]["lengthM"])
        pnts, wire = candidate["pnts"], candidate["links"]
        for left, right in zip(wire, wire[1:]):
            self.assertEqual(pnts[left["pntBegIdx"]+left["pntCnt"]-1], pnts[right["pntBegIdx"]])

    def test_boundary_signal_belongs_to_incoming_link_and_section(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        links = converted["intermediate"]["links"]
        self.assertTrue(links[1]["hasTrafficLight"])
        self.assertFalse(links[2]["hasTrafficLight"])
        segments = converted["candidateNaviToEHPData"]["guideGroups"][0]["segments"]
        self.assertEqual(segments[0]["trafficLightNum"], 1)
        self.assertEqual(segments[1]["trafficLightNum"], 0)

    def test_every_light_ends_its_own_link(self):
        """F2: lights are cuts; two lights in one edge get two links; the flagged link ends at its light."""
        source = fixture()
        source["trafficLights"] = [{"position": pos(2, .25)}, {"position": pos(2, .75)}, {"position": pos(0)}]
        converted = route_convert(source, target_frame="wgs84")
        links = converted["intermediate"]["links"]
        light_m = converted["stockGeometry"]["lightM"]
        flagged = [l for l in links if l["hasTrafficLight"]]
        self.assertEqual(len(flagged), 2)
        for link in flagged:
            end = link["distanceFromStartM"] + link["lengthM"]
            self.assertLessEqual(min(abs(end - d) for d in light_m), 1.0)
        group = converted["candidateNaviToEHPData"]["guideGroups"][0]
        self.assertEqual(group["groupTrafficLightsCount"], sum(s["trafficLightNum"] for s in group["segments"]))
        self.assertEqual(len(converted["candidateNaviToEHPData"]["trafficLights"]), 3)

    def test_unknown_attributes_carry_stock_null_values_never_json_null(self):
        """F4: off highways the stock's own NULL sentinels; nothing inferred from city roads or lanes."""
        converted = route_convert(fixture(), target_frame="wgs84")
        self.assertNotIn(":null", converted["messageSendJson"])
        for link in converted["candidateNaviToEHPData"]["links"]:
            self.assertEqual((link["roadclass"], link["formway"], link["ownership"], link["laneNum"],
                              link["direction"], link["speedLimit"]), (-1, -1, -1, 0, 0, 0))
        types = [l["linktype"] for l in converted["candidateNaviToEHPData"]["links"]]
        self.assertEqual(types, [-1, 2, 2, -1, -1])
        self.assertFalse(converted["publicationReady"])

    def test_manoeuvre_action_sits_on_the_link_ending_at_it(self):
        """Factory profile: the last link of a segment carries its MainAction, the others 0."""
        links = route_convert(fixture(), target_frame="wgs84")["candidateNaviToEHPData"]["links"]
        self.assertEqual([l["mainAction"] for l in links], [0, 2, 0, 0, 0])

    def test_highway_links_take_the_factory_controlled_access_attributes(self):
        """Factory profile: a highway link without a light is class 0 at >= 90 km/h else 6, formway 1,
        direction 1, ownership 0; no limit keeps class NULL; a light keeps the link NULL."""
        source = fixture()
        source["highways"] = [{"position": {"begin": pos(0), "end": pos(2, 1)}}]
        links = route_convert(source, target_frame="wgs84")["candidateNaviToEHPData"]["links"]
        # The fixture's lane sign at edge 1 lies on the highway: it cuts there and gives its width.
        self.assertEqual([l["roadclass"] for l in links], [6, 6, -1, 6, 6, -1])
        self.assertEqual([l["formway"] for l in links], [1, 1, -1, 1, 1, 1])
        self.assertEqual([l["direction"] for l in links], [1, 1, 0, 1, 1, 1])
        self.assertEqual([l["ownership"] for l in links], [0, 0, -1, 0, 0, 0])
        self.assertEqual([l["laneNum"] for l in links], [0, 3, 0, 0, 0, 0])
        fast = copy.deepcopy(source)
        fast["speedLimits"] = [25, 25, 25]
        self.assertEqual(route_convert(fast, target_frame="wgs84")["candidateNaviToEHPData"]["links"][0]["roadclass"], 0)

    def test_lane_sign_on_a_highway_ends_its_link_with_the_lane_count(self):
        source = fixture()
        source["highways"] = [{"position": {"begin": pos(1, .5), "end": pos(2, 1)}}]
        source["laneSigns"] = [{"position": pos(2, .5), "lanes": [{}, {}, {}]},
                               {"position": pos(0, .25), "lanes": [{}, {}]}]   # off the highway: no cut
        converted = route_convert(source, target_frame="wgs84")
        ends = [l["sourceEnd"] for l in converted["intermediate"]["links"]]
        self.assertIn(2.5, ends)
        self.assertNotIn(0.25, ends)
        links = converted["candidateNaviToEHPData"]["links"]
        self.assertEqual(links[ends.index(2.5)]["laneNum"], 3)
        self.assertEqual(links[ends.index(2.5) + 1]["laneNum"], 0)

    def test_roundabout_section_is_formway_4_one_way(self):
        source = fixture()
        source["sections"][1]["metadata"]["annotation"]["action"] = "ENTER_ROUNDABOUT"
        links = route_convert(source, target_frame="wgs84")["candidateNaviToEHPData"]["links"]
        self.assertEqual([(l["formway"], l["direction"]) for l in links[2:]], [(4, 1)] * 3)
        self.assertEqual(links[0]["formway"], -1)

    def test_free_flow_speed_is_distance_over_time_without_traffic(self):
        source = fixture()
        source["sections"][0]["metadata"]["weight"] = {"distance": {"value": 100.0}, "time": {"value": 10.0},
                                                        "timeWithTraffic": {"value": 20.0}}
        route = stock.route_from_snapshot(source)
        self.assertAlmostEqual(route["sections"][0]["freeFlowKmh"], 36.0)
        self.assertTrue(stock.math.isnan(route["sections"][1]["freeFlowKmh"]))
        self.assertEqual(route["sections"][0]["timeS"], 20.0)
        source["sections"][0]["metadata"]["weight"]["time"] = {"value": 0}
        self.assertTrue(stock.math.isnan(stock.route_from_snapshot(source)["sections"][0]["freeFlowKmh"]))
        converted = stock.convert(route, "wgs84")
        self.assertAlmostEqual(converted["segmentFreeFlowKmh"][0], 36.0)

    def test_lengths_are_whole_metres_summing_to_the_segment(self):
        """F3: navigationLen/groupLen/groupTime are JSON integers; sum(link len) == navigationLen."""
        converted = route_convert(fixture(), target_frame="wgs84")
        data = converted["candidateNaviToEHPData"]
        group = data["guideGroups"][0]
        for key in ("groupLen", "groupTime", "groupTrafficLightsCount"):
            self.assertIs(type(group[key]), int)
        for segment in group["segments"]:
            self.assertIs(type(segment["navigationLen"]), int)
            lens = [l["len"] for l in data["links"][segment["linkBegIdx"]:segment["linkBegIdx"]+segment["crntSegmLinkCnt"]]]
            self.assertTrue(all(float(v).is_integer() for v in lens))
            self.assertEqual(sum(int(v) for v in lens), segment["navigationLen"])
        self.assertIn('"len":', converted["messageSendJson"])
        self.assertNotRegex(converted["messageSendJson"], r'"navigationLen":\d+\.')

    def test_jams_never_cut_and_path_id_follows_the_table(self):
        """F1, DYN-03, DYN-12: jams are not topology; same table same Amap-style id, new table new id."""
        ids = stock.PathIds()
        first = route_convert(fixture(), target_frame="wgs84", path_ids=ids)
        jammed = fixture()
        jammed["jamSegments"] = [{"jamType": "HARD", "speed": 1.0}, {"jamType": "FREE", "speed": 20.0}, {"jamType": "HARD", "speed": 2.0}]
        second = route_convert(jammed, target_frame="wgs84", path_ids=ids)
        self.assertEqual(first["contentKey"], second["contentKey"])
        self.assertEqual(first["candidateNaviToEHPData"]["pathID"], 100002)
        self.assertEqual(second["candidateNaviToEHPData"]["pathID"], 100002)
        changed = fixture()
        changed["speedLimits"] = [10, 12, None]
        third = route_convert(changed, target_frame="wgs84", path_ids=ids)
        self.assertNotEqual(first["contentKey"], third["contentKey"])
        self.assertEqual(third["candidateNaviToEHPData"]["pathID"], 100003)

    def test_unknown_and_known_empty_are_distinct(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        availability = converted["intermediate"]["availability"]
        self.assertEqual(availability["highways"], "known")
        self.assertEqual(availability["hdSections"], "unknown")
        self.assertIn("unknown_yandex_hdSections", converted["exportBlockers"])
        source = fixture()
        source["availability"] = {"tunnels": "unknown"}
        types = [l["linktype"] for l in route_convert(source, target_frame="wgs84")["candidateNaviToEHPData"]["links"]]
        self.assertEqual(set(types), {-1})

    def test_actual_route_action_enum_separate_from_hud(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        segments = converted["candidateNaviToEHPData"]["guideGroups"][0]["segments"]
        self.assertEqual(segments[0]["navigationMainAction"], 2)
        self.assertEqual((segments[1]["navigationMainAction"], segments[1]["navigationAssitAction"]), (0, 36))
        self.assertTrue(segments[1]["isArriveWayPoint"])
        self.assertEqual(MAIN_ACTION["ENTER_ROUNDABOUT"], 11)
        self.assertEqual(stock.actions("EXIT_RIGHT"), (4, 0))
        self.assertEqual(stock.actions("WAYPOINT"), (0, 35))
        source = fixture()
        source["sections"][1]["metadata"]["annotation"]["action"] = "SOMETHING_NEW"
        result = route_convert(source, target_frame="wgs84")
        self.assertIn("unmapped_route_actions:SOMETHING_NEW", result["exportBlockers"])
        self.assertEqual(result["candidateNaviToEHPData"]["guideGroups"][0]["segments"][0]["navigationMainAction"], 0)

    def test_description_is_the_stock_text(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        segments = converted["candidateNaviToEHPData"]["guideGroups"][0]["segments"]
        self.assertTrue(segments[0]["description"].startswith("travel "))
        self.assertTrue(segments[0]["description"].endswith("Turn right get into B"))
        self.assertEqual(segments[0]["navigationNextRoadName"], "B")
        self.assertTrue(segments[1]["description"].endswith("Nameless Road"))

    def test_minimal_legacy_real_fixture_is_supported_honestly(self):
        source = {"routeId": "minimal", "points": [[37.6, 55.75], [37.61, 55.75]]}
        result = route_convert(source, target_frame="wgs84")
        self.assertIn("missing_manoeuvre_sections", result["exportBlockers"])
        self.assertFalse(result["intermediate"]["sectionsKnown"])
        self.assertEqual(validate_payload({"data": [result["candidateNaviToEHPData"]]}), [])

    def test_invalid_input_is_rejected(self):
        for patch_ in ({"points": [[0, 0]]}, {"points": [[0, 0], [0, 0]]},
                       {"points": [[37.6, float("nan")], [37.61, 55.75]]},
                       {"coordinateFrame": "native-gcj-unrestricted-candidate"},
                       {"speedLimits": [10]}, {"speedLimits": [10, -1, 0]}):
            source = fixture()
            source.update(patch_)
            with self.subTest(patch=patch_), self.assertRaises(ContractError):
                route_convert(source, target_frame="wgs84")

    def test_section_overlap_gap_and_fraction_are_rejected(self):
        for bad_begin in (pos(1, .2), pos(1, .3), pos(1, 1.2)):
            source = fixture()
            source["sections"][1]["geometry"]["begin"] = bad_begin
            with self.subTest(position=bad_begin), self.assertRaises(ContractError):
                normalize(source)

    def test_coordinate_transform_once_moscow_only_on_the_stock_grid(self):
        source = fixture()
        transformed = route_convert(source, target_frame="native-gcj-unrestricted-candidate")
        coordinate = transformed["candidateNaviToEHPData"]["pnts"][0]
        self.assertGreater(abs(coordinate["y"]-55.75), .001)
        for point in transformed["candidateNaviToEHPData"]["pnts"]:
            self.assertEqual(round(point["x"]*3600000)/3600000.0, point["x"])     # F9
        self.assertEqual(source["points"][0], [37.6, 55.75])
        self.assertEqual(transformed["coordinateContract"]["transformApplications"], 1)
        source["points"] = [[-73, 40], [-72.9, 40], [-72.8, 40], [-72.7, 40]]
        with self.assertRaisesRegex(ContractError, "Moscow"):
            route_convert(source, target_frame="native-gcj-unrestricted-candidate")

    def test_fractional_progress_uses_new_section_at_boundary_and_remaining_offsets(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        result = map_progress(converted, sample(), now_ms=10500)
        self.assertTrue(result["accepted"])
        self.assertEqual((result["curStepId"], result["curLinkId"], result["globalLinkIndex"]), (1, 0, 2))
        self.assertAlmostEqual(result["matchedWgs84"][0], 37.60125)
        self.assertEqual(result["linkOffset"], converted["intermediate"]["links"][2]["wholeMetres"])
        self.assertIs(type(result["linkOffset"]), int)
        end = map_progress(converted, sample(position=pos(2, 1)), now_ms=10500)
        self.assertEqual((end["linkOffset"], end["distanceToNextStep"], end["distanceToDestinationM"]), (0, 0, 0))
        self.assertAlmostEqual(end["matchedWgs84"][0], 37.603)

    def test_stale_offroute_and_reroute_progress_is_rejected(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        for change in ({"generation": 8}, {"routeId": "old"}, {"generation": None},
                       {"capturedAtMs": 11000}, {"expiresAtMs": 10500},
                       {"capturedAtMs": 8000}, {"matched": False}, {"status": "cancelled"}):
            with self.subTest(change=change):
                self.assertFalse(map_progress(converted, sample(**change), now_ms=10500)["accepted"])

    def test_actual_guidance_schema_uses_fine_fix_freshness_and_explicit_clock(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        source = {"schema": "denza-yandex-guidance-v1", "coordinateFrame": "wgs84",
                  "routeId": "route-a", "generation": 7, "sequence": 21,
                  "capturedAtMs": 10000, "capturedUptimeMs": 3000,
                  "mode": "guidance", "freshLocation": True, "fixAgeMs": 100,
                  "sourcePolylinePosition": pos(1, .25), "location": native_location()}
        self.assertTrue(map_progress(converted, source, now_ms=10500)["accepted"])
        self.assertTrue(map_progress(converted, source, now_ms=3500, clock="uptime")["accepted"])
        for patch_ in ({"mode": "overview"}, {"freshLocation": False}, {"routeId": None},
                       {"fixAgeMs": 2100}, {"coordinateFrame": "unknown"},
                       {"locationClass": "EXTRAPOLATED"}, {"lastFineFixAgeMs": 1500}):
            with self.subTest(patch=patch_):
                self.assertFalse(map_progress(converted, dict(source, **patch_), now_ms=10500)["accepted"])
        self.assertTrue(map_progress(converted, dict(source, locationClass="FINE", lastFineFixAgeMs=200), now_ms=10500)["accepted"])
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
        a = route_convert(fixture(), target_frame="wgs84")["candidateNaviToEHPData"]["links"][0]["linkID"]
        code = ("import json; from tools.adas_bridge.bridge import route_convert; from tools.adas_bridge.tests.test_bridge import fixture; "
                "print(route_convert(fixture(), target_frame='wgs84')['candidateNaviToEHPData']['links'][0]['linkID'])")
        self.assertEqual(int(subprocess.check_output([sys.executable, "-c", code], cwd=ROOT)), a)


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
        self.assertEqual(self.receiver.progress(sample(), now_ms=10500)["reason"], "no_active_route")
        self.receiver.offer_route(self.converted)
        with self.assertRaises(ContractError):
            self.receiver.confirm(1)
        # As the stock: positions flow for the selected path before ADAS answers.
        self.assertTrue(self.receiver.progress(sample(), now_ms=10500)["accepted"])

    def test_reroute_takes_the_new_route_without_ending_navigation(self):
        self.select()
        self.assertTrue(self.receiver.progress(sample(), now_ms=10500)["accepted"])
        new = fixture()
        new.update(routeId="route-b", generation=8)
        converted = route_convert(new, target_frame="wgs84")
        self.receiver.offer_route(converted)
        self.assertIsNone(self.receiver.confirmed)
        self.assertEqual(self.receiver.progress(sample(capturedAtMs=10600, expiresAtMs=12600), now_ms=10700)["reason"], "route_mismatch")
        self.assertEqual(self.receiver.status(now_ms=10700), 101)       # DYN-04: no 110/100 across a reroute
        self.assertTrue(self.receiver.progress(sample(routeId="route-b", generation=8, capturedAtMs=10800, expiresAtMs=12800),
                                               now_ms=10900)["accepted"])

    def test_transient_loss_holds_the_position_and_status(self):
        """DYN-02: a stale sample holds; 101 continues; nothing is ended or resent."""
        self.select()
        first = self.receiver.progress(sample(), now_ms=10500)
        stale = self.receiver.progress(sample(capturedAtMs=11000, expiresAtMs=12000), now_ms=13000)
        self.assertFalse(stale["accepted"])
        self.assertEqual(stale["held"], first)
        self.assertEqual(self.receiver.status(now_ms=13000), 101)
        self.assertTrue(self.receiver.progress(sample(capturedAtMs=13000, expiresAtMs=15000), now_ms=13100)["accepted"])
        self.assertFalse(any(row["event"] == "end" for row in self.receiver.trace))

    def test_duplicate_and_out_of_order_are_rejections_not_aborts(self):
        """DYN-10."""
        self.select()
        self.receiver.progress(sample(), now_ms=10500)
        self.assertEqual(self.receiver.progress(sample(), now_ms=10600)["reason"], "out_of_order_or_duplicate")
        self.assertEqual(self.receiver.progress(sample(capturedAtMs=9000), now_ms=10600)["reason"], "out_of_order_or_duplicate")
        result = replay([{"event": "selection", "pathID": 5}, {"event": "status", "nowMs": 1}])
        self.assertIn("rejected", result["results"][0])
        self.assertEqual(result["results"][-1]["status"], 100)

    def test_progress_never_moves_back(self):
        """DYN-01."""
        self.select()
        self.assertTrue(self.receiver.progress(sample(position=pos(2, .5)), now_ms=10500)["accepted"])
        back = self.receiver.progress(sample(position=pos(1, .5), capturedAtMs=10600, expiresAtMs=12600), now_ms=10700)
        self.assertEqual(back["reason"], "behind_last_progress")
        self.assertEqual(back["held"]["globalLinkIndex"], 4)

    def test_status_110_only_after_arrival(self):
        """DYN-09: 100 before any route and on a cancel; 110 for 3 s only after an arrival."""
        self.assertEqual(self.receiver.status(now_ms=0), 100)
        self.receiver.end(now_ms=0)
        self.assertEqual(self.receiver.status(now_ms=1), 100)
        self.select()
        self.receiver.end(now_ms=10000)
        self.assertEqual(self.receiver.status(now_ms=10100), 100)
        self.select()
        self.receiver.end(now_ms=20000, arrived=True)
        self.assertEqual(self.receiver.status(now_ms=20100), 110)
        self.assertEqual(self.receiver.status(now_ms=23000), 100)


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
        self.assertEqual(encode_binder_route(text), stock.route(text, 1))
        with self.assertRaises(ContractError):
            decode_someip_route(binder)

    def test_bad_wire_lengths_are_rejected(self):
        for data in (b"", b"\x0a\x80", b"\x0a\xff" * 6, b"\x0a\x03\x22\x08x"):
            with self.subTest(data=data), self.assertRaises(ContractError):
                decode_binder_route(data)

    def test_stock_chunking_in_utf16_units(self):
        """F7: n.a() cuts at 524288 Java chars; a surrogate pair split across chunks joins again."""
        text = "a" * 524287 + "😎" + "b" * 10
        chunks = chunk_payload(text, time_ms=123)
        self.assertEqual([row["length"] for row in chunks], [2, 2])
        self.assertEqual([row["currentIndex"] for row in chunks], [0, 1])
        self.assertEqual(reassemble_chunks(chunks), text)
        self.assertEqual(len(chunk_payload("x" * 1000, time_ms=1)), 1)
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
    def test_bench_sends_the_stock_payload_without_nulls_and_chunks_oversize(self):
        golden = decode_someip_route(self.GOLDEN.read_bytes())
        converted = route_convert(fixture(), target_frame="wgs84")
        bench = make_bench(converted, golden, time_ms=10000)
        route = bench["payload"]["data"][0]
        self.assertEqual(route["links"][0]["roadclass"], -1)
        self.assertEqual(bench["manifest"]["nullFields"], [])
        self.assertEqual(bench["payload"]["endPOIInfo"], {"AVPMapIdList": [], "PTPStateFlag": 1})
        self.assertEqual(bench["receiver"].status(now_ms=10000), 101)
        self.assertEqual(decode_someip_route(bench["someip"])["payload"], bench["payload"])
        long_name = fixture()
        long_name["sections"][1]["metadata"]["annotation"]["toponym"] = "Ж" * 200000
        oversize = make_bench(route_convert(long_name, target_frame="wgs84"), golden, time_ms=10000)
        self.assertEqual(len(oversize["binders"]), 2)
        self.assertEqual(oversize["receiver"].route["candidateNaviToEHPData"]["linkCnt"], 5)

    @unittest.skipUnless(GOLDEN.exists(), "untracked local stock golden unavailable")
    def test_native_capture_ring_holds_through_a_loss(self):
        golden = decode_someip_route(self.GOLDEN.read_bytes())
        samples = [native(0), native(1, fresh=False), native(2)]
        result, benches = replay_capture(fixture(), samples, golden, target_frame="wgs84")
        self.assertEqual(result["sourceGenerations"], [7])
        self.assertEqual(result["acceptedOfflineGeometrySamples"], 2)
        self.assertEqual(len(benches), 1)
        self.assertFalse(any(row["event"] == "end" for row in result["trace"]))

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
    def test_live_engine_follows_the_stock_lifecycle(self):
        engine = LiveBridge(decode_someip_route(self.GOLDEN.read_bytes()), "wgs84")
        self.assertTrue(engine.tick(native(0), now_ms=3100, snapshot=fixture())["accepted"])
        self.assertEqual(engine.byte_receipts, 1)
        self.assertEqual(engine.tick(native(0), now_ms=3200)["reason"], "duplicate_read")
        stale = engine.tick(native(1), now_ms=6100)                     # read 2 s late
        self.assertFalse(stale["accepted"])
        self.assertTrue(engine.receiver.active)                         # DYN-02: no end
        self.assertEqual(stale["status"], 101)
        rerouting = engine.tick(native(2, mode="rerouting", fresh=False), now_ms=5050)
        self.assertEqual(rerouting["status"], 101)                      # DYN-04
        self.assertTrue(engine.tick(native(3, position=pos(1, .5)), now_ms=6050)["accepted"])
        self.assertEqual(engine.byte_receipts, 1)                       # no resend
        refreshed = fixture()
        refreshed["jamSegments"] = [{"jamType": "HARD", "speed": 1.0}] * 3
        engine.tick(native(4, position=pos(1, .6)), now_ms=7050, snapshot=refreshed)
        self.assertEqual(engine.byte_receipts, 1)                       # DYN-03: same table, same path
        finished = engine.tick(native(5, mode="off", routeId=None, routeStatus="ROUTE_FINISHED"), now_ms=8050)
        self.assertEqual(finished["status"], 110)
        self.assertEqual(engine.receiver.status(now_ms=11100), 100)
        late = engine.tick(native(6), now_ms=9050, snapshot=fixture())
        self.assertEqual(late["reason"], "retired_route")               # DYN-13
        mismatch = engine.tick(native(7, routeId="different", generation=8), now_ms=10050, snapshot=fixture())
        self.assertEqual(mismatch["reason"], "snapshot_progress_route_mismatch")

    @unittest.skipUnless(GOLDEN.exists(), "untracked local stock golden unavailable")
    def test_live_engine_changed_table_is_a_new_path(self):
        engine = LiveBridge(decode_someip_route(self.GOLDEN.read_bytes()), "wgs84")
        engine.tick(native(0), now_ms=3100, snapshot=fixture())
        changed = fixture()
        changed["speedLimits"] = [10, 12, None]
        receipt = engine.tick(native(1), now_ms=4100, snapshot=changed)
        self.assertEqual(engine.byte_receipts, 2)
        self.assertEqual(receipt["pathID"], 100003)

    @unittest.skipUnless(GOLDEN.exists(), "untracked local stock golden unavailable")
    def test_live_engine_process_session_restart_and_delayed_old_session(self):
        engine = LiveBridge(decode_someip_route(self.GOLDEN.read_bytes()), "wgs84")
        def sample_(session, generation, sequence, now):
            return {"schema": "denza-yandex-guidance-v1", "coordinateFrame": "wgs84",
                    "sourceSessionId": session, "routeId": "route-a", "generation": generation,
                    "positionRouteId": "route-a", "positionMatchesRoute": True,
                    "sequence": sequence, "capturedAtMs": now+7000, "capturedUptimeMs": now,
                    "mode": "guidance", "freshLocation": True, "fixAgeMs": 0,
                    "sourcePolylinePosition": pos(1, .25), "location": native_location(now+7000, now)}
        old = sample_("old-process", 7, 50, 3000)
        self.assertTrue(engine.tick(old, now_ms=3000, snapshot=dict(fixture(), sourceSessionId="old-process"))["accepted"])
        epoch = engine.receiver.publication_epoch
        new = sample_("new-process", 1, 0, 4000)
        self.assertEqual(engine.tick(new, now_ms=4000, snapshot=fixture())["reason"], "snapshot_progress_route_mismatch")
        new = sample_("new-process", 1, 1, 4100)
        self.assertTrue(engine.tick(new, now_ms=4100, snapshot=dict(fixture(), sourceSessionId="new-process"))["accepted"])
        self.assertGreater(engine.receiver.publication_epoch, epoch)
        self.assertEqual(engine.receiver.path_id(), 100002)            # a new process, its own ids
        self.assertEqual(engine.tick(old, now_ms=4200, snapshot=fixture())["reason"], "retired_or_missing_source_session")

    @unittest.skipUnless(GOLDEN.exists(), "untracked local stock golden unavailable")
    def test_cancel_generation_rejects_queued_previous_route_sample(self):
        engine = LiveBridge(decode_someip_route(self.GOLDEN.read_bytes()), "wgs84")
        source = native(10)
        self.assertTrue(engine.tick(source, now_ms=13000, snapshot=fixture())["accepted"])
        cancelled = dict(source, routeId=None, generation=8, sequence=12, mode="off", freshLocation=False)
        self.assertEqual(engine.tick(cancelled, now_ms=13100)["status"], 100)
        delayed = dict(source, sequence=11)
        self.assertEqual(engine.tick(delayed, now_ms=13200)["reason"], "old_source_generation_or_sequence")
        self.assertFalse(engine.receiver.active)

    def test_dynamic_proto_values_as_the_stock_sends_them(self):
        converted = route_convert(fixture(), target_frame="wgs84")
        path = converted["candidateNaviToEHPData"]["pathID"]
        source = sample()
        source["location"] = dict(native_location(), heading=91.7)
        source["speedLimitMps"] = 16.6667
        mapped = map_progress(converted, source, now_ms=10500)
        bundle = make_dynamic_packets(converted, source, mapped)
        position = decode_struct(bytes.fromhex(bundle["packets"][0]["binderProtobufHex"]), POSITION)
        self.assertEqual(position["curSDRouteID"], float(path))
        self.assertEqual(position["locationLatitude"], 55.75)
        self.assertEqual(position["locationLongitude"], 37.60125)
        self.assertEqual(position["GPSspeed"], 9)                           # 2.5 m/s in km/h
        self.assertEqual(position["reserve4"], 91)                          # course
        self.assertEqual(as_int32(position["currentRoadOwnership"]), -1)    # F5: sent explicitly
        self.assertEqual(position["linkOffset"], mapped["linkOffset"])
        traffic_hex = bundle["packets"][1]["binderProtobufHex"]
        traffic = decode_struct(bytes.fromhex(traffic_hex), TRAFFIC)
        self.assertEqual(as_int32(traffic["roadClass"]), -1)
        self.assertIn("9801ffffffff0f", traffic_hex)                        # F10: uint32, five bytes
        self.assertEqual(traffic["distToDsttn"], mapped["distanceToDestinationM"])   # F11
        self.assertEqual(traffic["spdLmtSpeedValue"], 60)
        bad = dict(mapped, curSDRouteID=(1 << 53)+1)
        with self.assertRaises(ContractError):
            make_dynamic_packets(converted, source, bad)


if __name__ == "__main__":
    unittest.main()
