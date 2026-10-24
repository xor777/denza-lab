"""Synthetic captured-native bytes: identity, object boundaries and abstaining association."""
import copy
import contextlib
import hashlib
import io
import json
import math
import struct
import tempfile
import unittest
from pathlib import Path

from tools.adas_bridge.yandex_graph_capture import associate_route, main, parse_capture


SCALE = 6371008.8 * math.pi / 180


def xy(x, y=0):
    return [x / SCALE, y / SCALE]


def record(edge_id=11, sd=0, vertices=(7, 9), points=None, graph="0x1234", expected_points=None, attrs=0xAB):
    points = points if points is not None else [xy(0), xy(100)]
    header = bytearray(128)
    struct.pack_into("<QI", header, 0, edge_id, sd)
    struct.pack_into("<Q", header, 16, 0x100)
    header[0x50:0x5c] = bytes([attrs]) * 12
    header[0x60:] = bytes([0xDE]) * 32
    geometry = b"".join(struct.pack("<dd", lat, lon) for lon, lat in points)
    expected = 16 * (expected_points if expected_points is not None else len(points))
    return {"kind": "lookupEdge", "graph": graph, "edgeIdHex": hex(edge_id), "segmentIndexHex": hex(sd),
            "number": 1, "graphHeader": "12345678", "engaged": 1,
            "resultBytes": (struct.pack("<QQQ", *vertices, 0x200) + b"\x01").hex(),
            "edgePointer": "0x200", "edgeHeader": header.hex(), "geometryPointer": "0x100",
            "geometryHeader": struct.pack("<QQQ", 0x300, 0x300 + expected, 0x300 + expected).hex(),
            "geometrySizeBytes": expected, "geometryBytes": geometry.hex()}


def capture(*records):
    return "".join(json.dumps({"type": "send", "payload": row}) + "\n" for row in records).encode()


def graph(*records):
    return parse_capture(capture(*records))


def route(points=None):
    return {"schema": "denza-yandex-driving-route-v1", "coordinateFrame": "wgs84", "pointOrder": "lon,lat",
            "routeId": "synthetic", "points": points or [xy(0), xy(100)], "availability": {}}


class NativeDecodeTests(unittest.TestCase):
    def test_exact_geometry_order_object_size_and_raw_unknown_attributes(self):
        r = graph(record(points=[[12.5, 45.25], [12.6, 45.3]]))
        edge = r["frames"][0]["edges"][0]
        self.assertEqual(edge["geometry"]["points"], [[12.5, 45.25], [12.6, 45.3]])
        self.assertEqual(edge["vertexIndices"], [7, 9])
        self.assertTrue(edge["geometry"]["complete"])
        raw = edge["rawVariants"][0]
        self.assertEqual(len(bytes.fromhex(raw["edgeDataHex"])), 96)
        self.assertEqual(raw["unnamedAttributes0x50Through0x5bHex"], "ab" * 12)
        self.assertEqual(raw["outsideObjectTrailingHex"], "de" * 32)
        self.assertNotIn("roadClass", edge)
        self.assertNotIn("laneCount", edge)
        self.assertNotIn("oneWay", edge)

    def test_dedup_uses_actual_sd_start_not_requested_segment_index(self):
        a, b = record(), record()
        b["segmentIndexHex"] = "0x5"
        r = graph(a, b)
        self.assertEqual(r["summary"]["uniqueEdges"], 1)
        self.assertEqual(r["summary"]["duplicateObservations"], 1)
        self.assertEqual(len(r["frames"][0]["edges"][0]["observations"]), 2)
        self.assertEqual(r["frames"][0]["edges"][0]["observations"][1]["requestedSegmentIndexHex"], "0x5")

    def test_sd_start_and_large_edge_id_are_not_lost(self):
        r = graph(record(edge_id=2**63 + 7), record(edge_id=2**63 + 7, sd=3))
        self.assertEqual(r["summary"]["uniqueEdges"], 2)
        self.assertEqual(r["frames"][0]["edges"][0]["edgeIdHex"], hex(2**63 + 7))

    def test_graph_switch_or_observer_restart_separates_local_vertices_and_edges(self):
        r = graph(record(graph="0x11"), record(graph="0x22"), record(graph="0x11"),
                  {"kind": "observer_start"}, record(graph="0x11"))
        self.assertEqual(r["summary"]["graphFrames"], 4)
        self.assertEqual(r["summary"]["uniqueEdges"], 4)
        self.assertEqual(len({v["id"] for f in r["frames"] for v in f["vertices"]}), 8)

    def test_actual_endpoint_adjacency_keeps_reverse_edges_without_inventing_oneway(self):
        r = graph(record(edge_id=11), record(edge_id=12, vertices=(9, 7), points=[xy(100), xy(0)]),
                  record(edge_id=13, vertices=(9, 10), points=[xy(100), xy(200)]))
        frame = r["frames"][0]
        vertex = next(v for v in frame["vertices"] if v["index"] == 9)
        self.assertEqual(len(vertex["incidentEdgeIds"]), 3)
        self.assertEqual(len(vertex["storedEndpoint0EdgeIds"]), 2)
        self.assertEqual(vertex["coordinateSpreadM"], 0)
        self.assertNotIn("isDeadEnd", vertex)

    def test_geometry_prefix_does_not_claim_full_edge_or_second_endpoint_coordinate(self):
        r = graph(record(expected_points=20))
        edge = r["frames"][0]["edges"][0]
        self.assertFalse(edge["geometry"]["complete"])
        self.assertEqual(edge["geometry"]["expectedBytes"], 320)
        self.assertEqual(edge["geometry"]["capturedBytes"], 32)
        end = next(v for v in r["frames"][0]["vertices"] if v["index"] == 9)
        self.assertEqual(end["coordinateCandidates"], [])

    def test_partial_double_pair_is_not_read_past_capture(self):
        row = record()
        row["geometryBytes"] = row["geometryBytes"][:-2]
        r = graph(row)
        geometry = r["frames"][0]["edges"][0]["geometry"]
        self.assertEqual(geometry["partialPairBytes"], 15)
        self.assertEqual(len(geometry["points"]), 1)
        self.assertFalse(geometry["complete"])

    def test_disengaged_result_never_reads_stale_edge_data(self):
        row = record()
        row["resultBytes"] = (b"\0" * 25).hex()
        row["engaged"] = 0
        row["edgeHeader"] = "not hex"
        r = graph(row)
        self.assertEqual(r["summary"]["disengagedLookups"], 1)
        self.assertEqual(r["summary"]["uniqueEdges"], 0)
        self.assertEqual(r["issues"], [])

    def test_conflicting_geometry_is_retained_and_excluded_from_adjacency(self):
        r = graph(record(), record(points=[xy(0), xy(200)]))
        self.assertEqual(r["summary"]["conflictingEdges"], 1)
        self.assertEqual(len(r["frames"][0]["edges"][0]["geometryTopologyVariants"]), 1)
        self.assertEqual(r["frames"][0]["vertices"], [])

    def test_raw_unknown_attribute_changes_do_not_invent_geometry_conflicts(self):
        r = graph(record(attrs=1), record(attrs=2))
        edge = r["frames"][0]["edges"][0]
        self.assertEqual(edge["consistency"], "consistent")
        self.assertEqual(len(edge["rawVariants"]), 2)

    def test_invalid_pointer_extent_engagement_and_coordinates_are_issues(self):
        cases = []
        d = record(); d["edgePointer"] = "0x201"; cases.append(d)
        d = record(); d["geometryPointer"] = "0x101"; cases.append(d)
        d = record(); d["geometrySizeBytes"] = 48; cases.append(d)
        d = record(); d["resultBytes"] = d["resultBytes"][:-2]; cases.append(d)
        d = record(); d["engaged"] = 0; cases.append(d)
        d = record(points=[[0, math.nan], [0, 0]]); cases.append(d)
        d = record(points=[[0, 91], [0, 0]]); cases.append(d)
        d = record(); d["geometryBytes"] += "00"; cases.append(d)
        for i, value in enumerate(cases):
            with self.subTest(case=i):
                r = graph(value)
                self.assertEqual(r["summary"]["uniqueEdges"], 0)
                self.assertEqual(len(r["issues"]), 1)

    def test_partial_json_tail_is_reported_without_losing_previous_complete_record(self):
        r = parse_capture(capture(record()) + b'{"payload":')
        self.assertEqual(r["summary"]["uniqueEdges"], 1)
        self.assertTrue(r["issues"][0]["incompleteTailPossible"])

    def test_exact_capture_hash_and_input_not_mutated(self):
        row = record()
        before = copy.deepcopy(row)
        raw = capture(row)
        a, b = parse_capture(raw), parse_capture(raw)
        self.assertEqual(a, b)
        self.assertEqual(row, before)
        self.assertEqual(a["source"]["sha256"], hashlib.sha256(raw).hexdigest())

    def test_cli_preserves_exact_frozen_input_and_rejects_input_overwrite(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            source, output = root / "capture.jsonl", root / "graph.json"
            raw = capture(record())
            source.write_bytes(raw)
            with contextlib.redirect_stdout(io.StringIO()):
                main([str(source), "--out", str(output)])
            result = json.loads(output.read_text())
            frozen = Path(result["source"]["frozenCopyPath"])
            self.assertEqual(frozen.read_bytes(), raw)
            self.assertEqual(result["source"]["sha256"], hashlib.sha256(raw).hexdigest())
            with self.assertRaises(ValueError):
                main([str(source), "--out", str(source)])
            self.assertEqual(source.read_bytes(), raw)


class GeometricAssociationTests(unittest.TestCase):
    def test_geometry_and_stored_direction_select_candidate_without_claiming_road_identity(self):
        g = graph(record(edge_id=1), record(edge_id=2, vertices=(9, 7), points=[xy(100), xy(0)]))
        r = associate_route(g, route())
        self.assertEqual(r["sampledCoverage"]["unique_geometric_candidate"]["percent"], 100)
        self.assertEqual(r["status"], "estimated_geometric_association_only")
        self.assertTrue(all(len(s["plausibleCandidates"]) == 1 for s in r["samples"]))

    def test_distance_gate_does_not_force_far_nearest_edge(self):
        r = associate_route(graph(record(points=[xy(0, 50), xy(100, 50)])), route())
        self.assertEqual(r["sampledCoverage"]["no_captured_candidate"]["percent"], 100)

    def test_reverse_only_geometry_fails_direction_gate(self):
        r = associate_route(graph(record(points=[xy(100), xy(0)])), route())
        self.assertEqual(r["sampledCoverage"]["direction_rejected"]["percent"], 100)

    def test_parallel_roads_with_similar_distance_remain_ambiguous(self):
        r = associate_route(graph(record(edge_id=1), record(edge_id=2, points=[xy(0, 1), xy(100, 1)])), route())
        self.assertEqual(r["sampledCoverage"]["ambiguous"]["percent"], 100)
        self.assertTrue(all(len(s["plausibleCandidates"]) == 2 for s in r["samples"]))

    def test_junction_shared_endpoint_does_not_force_one_incoming_or_outgoing_edge(self):
        g = graph(record(edge_id=1, points=[xy(0), xy(50)]),
                  record(edge_id=2, vertices=(9, 10), points=[xy(50), xy(100)]))
        r = associate_route(g, route(), sample_step_m=200)
        self.assertEqual(r["samples"][0]["status"], "ambiguous")

    def test_prefix_and_conflicting_edges_are_not_association_inputs(self):
        for g in (graph(record(expected_points=100)), graph(record(), record(points=[xy(0), xy(200)]))):
            r = associate_route(g, route())
            self.assertEqual(r["sampledCoverage"]["no_captured_candidate"]["percent"], 100)

    def test_identical_geometry_in_different_graph_frames_is_not_silently_one_edge(self):
        r = associate_route(graph(record(graph="0x11"), record(graph="0x22")), route())
        self.assertEqual(r["sampledCoverage"]["ambiguous"]["percent"], 100)

    def test_dateline_uses_short_longitude_arc(self):
        points = [[179.9999, 0], [-179.9999, 0]]
        r = associate_route(graph(record(points=points)), route(points), sample_step_m=10)
        self.assertEqual(r["sampledCoverage"]["unique_geometric_candidate"]["percent"], 100)
        self.assertTrue(all(abs(s["samplePoint"][0]) > 179 for s in r["samples"]))

    def test_unbounded_or_direction_indistinguishable_parameters_are_rejected(self):
        for kwargs in ({"maximum_distance_m": math.inf}, {"sample_step_m": 0}, {"maximum_heading_degrees": 90}):
            with self.subTest(kwargs=kwargs), self.assertRaises(ValueError):
                associate_route(graph(record()), route(), **kwargs)


if __name__ == "__main__":
    unittest.main()
