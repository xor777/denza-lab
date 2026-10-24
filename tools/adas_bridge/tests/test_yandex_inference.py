"""Evidence extent, availability failures and deliberately uncertain Yandex estimates.

All test geometry is synthetic. No road dataset, network, device or wire sender is
needed. Tests distinguish point/sign evidence from road-wide attributes.
"""
import copy
import hashlib
import json
import math
from pathlib import Path
import tempfile
import unittest

from tools.adas_bridge.yandex_inference import infer, main, summarize


def pos(value):
    return {"segmentIndex": int(value), "segmentPosition": value - int(value)}


def interval(begin, end):
    return {"begin": pos(begin), "end": pos(end)}


def section(begin, end, action="STRAIGHT", name=None, distance=200, time=20):
    return {"geometry": interval(begin, end), "metadata": {
        "annotation": {"action": action, "toponym": name},
        "weight": {"distance": {"value": distance}, "time": {"value": time},
                   "timeWithTraffic": {"value": time * 2}}}}


def snapshot(edges=20):
    step = 20 / (6371008.8 * math.pi / 180)
    return {"schema": "denza-yandex-driving-route-v1", "coordinateFrame": "wgs84",
            "pointOrder": "lon,lat", "routeId": "synthetic-only", "units": {"speed": "m/s", "time": "s", "distance": "m"},
            "points": [[i * step, 0.0] for i in range(edges + 1)], "sections": [section(0, edges)],
            "availability": {}, "metadata": {"flags": {"requiresAccessPass": False}},
            "highways": [], "tunnels": [], "ferries": [], "ruggedRoads": [], "fordCrossings": [], "tollRoads": [],
            "hdSections": [], "standingSegments": [], "zoneCrossings": [],
            "laneSigns": [], "trafficLights": [], "directionSigns": []}


def lane(kind="PLAIN_LANE", directions=None, highlighted=None):
    return {"laneKind": kind, "directions": directions or [], "highlightedDirection": highlighted}


def sign(at, lanes):
    return {"position": pos(at), "lanes": lanes}


def at(report, scalar):
    return next(row["attributes"] for row in report["segments"]
                if row["scope"]["beginScalar"] <= scalar < row["scope"]["endScalar"])


class ScopeAndUnknownTests(unittest.TestCase):
    def test_vertices_are_neither_junctions_nor_evidence_boundaries(self):
        r = infer(snapshot(100))
        self.assertEqual(len(r["segments"]), 1)
        self.assertEqual(at(r, 5)["junctionTopology"]["status"], "unknown")
        self.assertEqual(r["coverage"]["attributes"]["roadClass"]["percent"]["unknown"], 100)

    def test_fractional_ranges_are_exact_evidence_scopes_without_common_link_default(self):
        d = snapshot()
        d["tunnels"] = [{"position": interval(2.25, 4.75)}]
        r = infer(d)
        self.assertEqual([(v["scope"]["beginScalar"], v["scope"]["endScalar"]) for v in r["segments"]],
                         [(0, 2.25), (2.25, 4.75), (4.75, 20)])
        self.assertEqual(at(r, 3)["linkTypeFromAnnotation"]["value"], 2)
        self.assertEqual(at(r, 5)["sdkTunnel"]["value"], "not_annotated")
        self.assertEqual(at(r, 5)["linkTypeFromAnnotation"]["status"], "unknown")
        self.assertAlmostEqual(r["coverage"]["positiveSdkAnnotationM"]["sdkTunnel"], 50, places=3)

    def test_unknown_parent_prevents_consuming_present_value(self):
        d = snapshot()
        d["highways"] = [{"position": interval(0, 20)}]
        d["availability"]["highways"] = "unknown"
        r = infer(d)
        self.assertEqual(at(r, 5)["sdkHighway"]["status"], "unknown")
        self.assertEqual(at(r, 5)["amapRoadClassHypothesis"]["status"], "unknown")

    def test_partial_range_list_keeps_positive_but_cannot_prove_absence(self):
        d = snapshot()
        d["ferries"] = [{"position": interval(2, 3)}]
        d["availability"]["ferries"] = "partial"
        r = infer(d)
        self.assertEqual(at(r, 2.5)["sdkFerry"]["status"], "observed")
        self.assertEqual(at(r, 4)["sdkFerry"]["status"], "unknown")

    def test_unknown_range_component_is_not_promoted(self):
        d = snapshot()
        d["ruggedRoads"] = [{"position": interval(2, 3)}]
        d["availability"]["ruggedRoads[0].position.begin.segmentIndex"] = "unknown"
        r = infer(d)
        self.assertEqual(at(r, 2.5)["sdkRuggedRoad"]["status"], "unknown")
        self.assertEqual(len(r["extractionIssues"]), 1)

    def test_null_speed_does_not_spread_neighbouring_limit_or_section_speed(self):
        d = snapshot(4)
        d["speedLimits"] = [25, None, None, 25]
        r = infer(d)
        self.assertEqual(at(r, .5)["postedSpeedKmh"]["value"], 90)
        self.assertEqual(at(r, 2)["postedSpeedKmh"]["status"], "unknown")
        self.assertEqual(at(r, 2)["sectionFreeFlowKmh"]["value"], 36)
        self.assertEqual(r["coverage"]["attributes"]["postedSpeedKmh"]["percent"]["unknown"], 50)

    def test_unknown_speed_element_does_not_override_null_policy(self):
        d = snapshot(2)
        d["speedLimits"] = [30, 30]
        d["availability"]["speedLimits[1]"] = "unknown"
        r = infer(d)
        self.assertEqual(at(r, 1.5)["postedSpeedKmh"]["status"], "unknown")

    def test_partial_section_uses_known_children_and_retains_section_weight_scope(self):
        d = snapshot()
        d["sections"][0]["metadata"]["annotation"]["toponym"] = "Local name"
        d["availability"].update({"sections": "partial", "sections[0].metadata.annotation.toponym": "unknown"})
        d["tunnels"] = [{"position": interval(4, 6)}]
        r = infer(d)
        attrs = at(r, 5)
        self.assertEqual(attrs["sectionRoadName"]["status"], "unknown")
        self.assertEqual(attrs["sectionFreeFlowKmh"]["value"], 36)
        self.assertEqual(attrs["sectionTrafficTimeS"]["sourceScope"]["endScalar"], 20)
        self.assertIn("not travel time", attrs["sectionTrafficTimeS"]["limitation"])
        self.assertEqual(attrs["sectionAction"]["sourceScope"]["kind"], "point")

    def test_restriction_point_and_route_access_flag_do_not_define_entire_road(self):
        d = snapshot()
        d["restrictedEntries"] = [{"position": pos(7)}]
        d["metadata"]["flags"]["requiresAccessPass"] = True
        r = infer(d)
        self.assertEqual(len(r["segments"]), 1)
        self.assertEqual(at(r, 3)["roadAccess"]["status"], "unknown")
        self.assertEqual(r["routeFlags"]["requiresAccessPass"]["scope"]["kind"], "route")
        self.assertEqual(r["pointEvidence"][0]["scope"]["scalar"], 7)

    def test_failed_metadata_and_partial_sign_item_remain_unknown(self):
        d = snapshot()
        d["metadata"] = None
        d["directionSigns"] = [{"position": pos(5), "items": [None]}]
        d["availability"]["directionSigns[0].items[0]"] = "unknown"
        r = infer(d)
        self.assertEqual(r["routeFlags"], {})
        self.assertEqual(r["pointEvidence"][0]["items"], [])


class LaneTests(unittest.TestCase):
    def test_sign_counts_through_turn_special_and_unknown_separately(self):
        d = snapshot()
        d["laneSigns"] = [sign(15, [lane(directions=["STRAIGHT_AHEAD", "RIGHT90"], highlighted="STRAIGHT_AHEAD"),
                                  lane(directions=["RIGHT90"]), lane("BUS_LANE"), lane()])]
        r = infer(d)
        row = next(v for v in r["pointEvidence"] if v["kind"] == "laneSign")
        self.assertEqual(row["scope"]["kind"], "point")
        self.assertEqual(row["counts"]["value"], {"displayedLanes": 4, "plainLanes": 3, "specialLanes": 1,
            "throughBearingPlainLanes": 1, "turnOnlyPlainLanes": 1, "unclassifiedPlainLanes": 1,
            "highlightedLanes": 1, "unknownHighlightLanes": 0, "unknownKindLanes": 0})
        self.assertEqual(r["approachLaneHypotheses"], [])
        self.assertEqual(r["coverage"]["pointOnlyLaneCoverageM"], 0)
        self.assertEqual(r["coverage"]["attributes"]["roadLaneCount"]["percent"]["unknown"], 100)

    def test_optional_approach_is_100m_maximum_and_stops_at_range_boundary(self):
        d = snapshot()
        d["laneSigns"] = [sign(15, [lane(directions=["STRAIGHT_AHEAD"])])]
        d["tunnels"] = [{"position": interval(14, 16)}]
        r = infer(d, approach_lanes_m=100)
        hypothesis = r["approachLaneHypotheses"][0]
        self.assertEqual(hypothesis["scope"]["beginScalar"], 14)
        self.assertAlmostEqual(hypothesis["scope"]["endM"] - hypothesis["scope"]["beginM"], 20, places=5)
        self.assertEqual(hypothesis["estimate"]["status"], "estimated")
        self.assertEqual(hypothesis["roadLaneCount"]["status"], "unknown")

    def test_previous_sign_section_start_and_traffic_light_bound_approach(self):
        d = snapshot()
        d["sections"] = [section(0, 10), section(10, 20)]
        d["laneSigns"] = [sign(9, [lane()]), sign(11, [lane()]), sign(12, [lane()]), sign(15, [lane()])]
        d["trafficLights"] = [{"position": pos(14)}]
        rows = infer(d, approach_lanes_m=100)["approachLaneHypotheses"]
        self.assertEqual([round(v["scope"]["beginScalar"], 6) for v in rows], [4, 10, 11, 14])
        for row in rows:
            self.assertLessEqual(row["scope"]["endM"] - row["scope"]["beginM"], 100.000001)

    def test_unknown_lane_kind_disables_count_extrapolation(self):
        d = snapshot()
        d["laneSigns"] = [sign(15, [lane()])]
        d["availability"]["laneSigns[0].lanes[0].laneKind"] = "unknown"
        r = infer(d, approach_lanes_m=100)
        self.assertEqual(r["approachLaneHypotheses"], [])

    def test_partial_directions_do_not_claim_turn_only_lane(self):
        d = snapshot()
        d["laneSigns"] = [sign(15, [lane(directions=["RIGHT90", "STRAIGHT_AHEAD"])])]
        d["availability"]["laneSigns[0].lanes[0].directions[1]"] = "unknown"
        r = infer(d)
        count = r["pointEvidence"][0]["counts"]["value"]
        self.assertEqual(count["turnOnlyPlainLanes"], 0)
        self.assertEqual(count["unclassifiedPlainLanes"], 1)

    def test_known_null_highlight_is_distinct_from_unknown_highlight(self):
        d = snapshot()
        d["laneSigns"] = [sign(15, [lane(), lane()])]
        d["availability"]["laneSigns[0].lanes[1].highlightedDirection"] = "unknown"
        row = infer(d)["pointEvidence"][0]
        self.assertIsNone(row["lanes"][0]["value"]["highlightedDirection"])
        self.assertNotIn("highlightedDirection", row["lanes"][1]["value"])
        self.assertEqual(row["counts"]["value"]["unknownHighlightLanes"], 1)

    def test_estimate_bound_rejects_unbounded_extrapolation(self):
        for bound in (-1, 101, math.inf):
            with self.subTest(bound=bound), self.assertRaises(ValueError):
                infer(snapshot(), approach_lanes_m=bound)


class RoadHypothesisTests(unittest.TestCase):
    def test_highway_speed_threshold_is_candidate_only_and_null_has_no_preferred_class(self):
        d = snapshot(3)
        d["highways"] = [{"position": interval(0, 3)}]
        d["speedLimits"] = [25, 20, None]
        r = infer(d)
        for scalar, expected in ((.5, 0), (1.5, 6), (2.5, None)):
            attrs = at(r, scalar)
            self.assertEqual(attrs["roadClass"]["status"], "unknown")
            self.assertEqual(attrs["amapRoadClassHypothesis"]["status"], "estimated")
            self.assertEqual(attrs["amapRoadClassHypothesis"]["value"], {"candidates": [0, 6], "preferred": expected})
            self.assertEqual(attrs["oneWay"]["status"], "unknown")
            self.assertEqual(attrs["ownership"]["status"], "unknown")

    def test_high_speed_without_highway_does_not_select_amap_class(self):
        d = snapshot()
        d["speedLimits"] = [30] * 20
        r = infer(d)
        self.assertEqual(at(r, 3)["amapRoadClassHypothesis"]["status"], "unknown")
        self.assertIn("high_speed_ordinary_road", at(r, 3)["roadProfileHypothesis"]["value"])

    def test_signed_road_reference_does_not_become_current_name_or_highway(self):
        d = snapshot()
        d["sections"][0]["metadata"]["annotation"]["toponym"] = "Local street"
        d["directionSigns"] = [{"position": pos(5), "items": [
            {"road": {"name": "М4"}, "toponym": {"text": "Other town"}},
            {"road": {"name": "I-5"}}]}]
        r = infer(d)
        self.assertEqual(at(r, 5)["sectionRoadName"]["value"], "Local street")
        self.assertEqual(at(r, 5)["sdkHighway"]["value"], "not_annotated")
        self.assertEqual(r["pointEvidence"][0]["items"][0]["signedRoadReference"]["value"], "М4")
        self.assertEqual(r["pointEvidence"][0]["currentRoadIdentity"]["status"], "unknown")

    def test_roundabout_is_bounded_by_pair_and_unpaired_entry_does_not_fill_route(self):
        d = snapshot()
        d["sections"] = [section(0, 5), section(5, 8, "ENTER_ROUNDABOUT"), section(8, 20, "LEAVE_ROUNDABOUT")]
        r = infer(d)
        self.assertEqual(at(r, 6)["amapFormwayHypothesis"]["value"], 4)
        self.assertEqual(at(r, 6)["amapFormwayHypothesis"]["status"], "estimated")
        self.assertEqual(at(r, 9)["amapFormwayHypothesis"]["status"], "unknown")
        d["sections"][2]["metadata"]["annotation"]["action"] = "RIGHT"
        self.assertEqual(at(infer(d), 6)["amapFormwayHypothesis"]["status"], "unknown")

    def test_rugged_does_not_establish_unpaved_surface_or_amap_road_class(self):
        d = snapshot()
        d["ruggedRoads"] = [{"position": interval(3, 9)}]
        attrs = at(infer(d), 5)
        self.assertEqual(attrs["sdkRuggedRoad"]["status"], "observed")
        self.assertEqual(attrs["roadClass"]["status"], "unknown")
        self.assertIn("ordinary_road_with_local_roughness", attrs["roadProfileHypothesis"]["value"])

    def test_roundabout_pair_does_not_bridge_missing_section_geometry(self):
        d = snapshot()
        d["sections"] = [section(0, 5), section(5, 8, "ENTER_ROUNDABOUT"), section(10, 20, "LEAVE_ROUNDABOUT")]
        self.assertEqual(at(infer(d), 7)["amapFormwayHypothesis"]["status"], "unknown")


class ValidationAndOutputTests(unittest.TestCase):
    def test_invalid_geometry_units_alignment_and_ranges_are_rejected(self):
        cases = []
        for key, value in (("coordinateFrame", "gcj02"), ("pointOrder", "lat,lon")):
            d = snapshot(); d[key] = value; cases.append(d)
        d = snapshot(); d["points"][0][0] = math.nan; cases.append(d)
        d = snapshot(); d["points"][0][0] = 200; cases.append(d)
        d = snapshot(); d["points"] = [[0, 0], [0, 0]]; cases.append(d)
        d = snapshot(); d["speedLimits"] = [25]; cases.append(d)
        d = snapshot(); d["units"]["speed"] = "km/h"; cases.append(d)
        d = snapshot(); d["tunnels"] = [{"position": interval(8, 2)}]; cases.append(d)
        d = snapshot(); d["laneSigns"] = [sign(20.5, [])]; cases.append(d)
        for i, value in enumerate(cases):
            with self.subTest(case=i), self.assertRaises(ValueError):
                infer(value)

    def test_input_is_not_mutated_and_output_is_deterministic(self):
        d = snapshot()
        before = copy.deepcopy(d)
        a, b = infer(d), infer(d)
        self.assertEqual(a, b)
        self.assertEqual(d, before)
        json.dumps(a, allow_nan=False)

    def test_cli_receipt_hashes_original_bytes_and_writes_aggregate(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source = root / "sample.json"
            raw = json.dumps(snapshot(), indent=1).encode()
            source.write_bytes(raw)
            main([str(source), "--out-dir", str(root / "out"), "--approach-lanes-m", "100"])
            report = json.loads((root / "out/sample.json").read_text())
            self.assertEqual(report["source"]["sha256"], hashlib.sha256(raw).hexdigest())
            self.assertEqual(json.loads((root / "out/coverage.json").read_text())["aggregate"]["pointOnlyLaneCoverageM"], 0)

    def test_aggregate_preserves_unknowns_and_never_counts_signs_as_distance(self):
        d = snapshot()
        d["laneSigns"] = [sign(10, [lane()])]
        r = infer(d)
        aggregate = summarize([r, r])["aggregate"]
        self.assertEqual(aggregate["laneSigns"], 2)
        self.assertEqual(aggregate["pointOnlyLaneCoverageM"], 0)
        self.assertEqual(aggregate["attributes"]["roadLaneCount"]["percent"]["unknown"], 100)
        self.assertEqual(aggregate["attributes"]["ownership"]["percent"]["unknown"], 100)


if __name__ == "__main__":
    unittest.main()
