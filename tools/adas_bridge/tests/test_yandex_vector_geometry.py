"""Native geometry cursor, affine, projection, and hostile-budget regressions."""
from dataclasses import replace
import json
import hashlib
import math
from pathlib import Path
import struct
import tempfile
import unittest

from tools.adas_bridge.yandex_vector_geometry import (
    GeometryError, GeometryLimits, TileId, decode_geometry, decode_local,
    inspect_layout, local_to_world, to_world, wgs84_to_world, world_to_wgs84,
    decode_capture, compare_polylines, graph_lines, enrichment_table,
)
from tools.adas_bridge.yandex_vector_tile import parse_tile


def vi(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result + bytes([value]))


def integer(field, value):
    return vi(field << 3) + vi(value)


def message(field, value):
    return vi(field << 3 | 2) + vi(len(value)) + value


def packed(field, values):
    return message(field, b"".join(vi(v) for v in values))


def line(count=1, before=(), after=()):
    return message(2, (integer(1, count) if count is not None else b"") +
                   packed(2, before) + packed(3, after))


def polygon(rings=(1,), auxiliary=0, metadata=()):
    return message(3, packed(1, rings) + integer(2, auxiliary) + packed(3, metadata))


def raw_tile(lengths, points, geometries, bits=14):
    scalars = [v for p in points for v in p]
    zigzags = [v * 2 if v >= 0 else -v * 2 - 1 for v in scalars]
    features = [message(2, geom + integer(5, index + 1)) for index, geom in enumerate(geometries)]
    return integer(1, bits) + packed(2, zigzags) + packed(3, lengths) + message(
        6, message(1, b"road") + b"".join(features))


def local(lengths, points, geometries, bits=14, limits=None):
    return decode_local(parse_tile(raw_tile(lengths, points, geometries, bits)), limits=limits)


class GeometryTests(unittest.TestCase):
    def test_delta_reset_zero_delta_collapse_and_nonconsecutive_repeat(self):
        result = local([5, 2], [(10, 20), (3, 4), (0, 0), (-3, -4), (1, 0),
                                (-3, -5), (1, 2)], [line(2)])
        geom = result["features"][0]["geometry"]
        self.assertEqual(geom["coordinates"], [[[10, 20], [13, 24], [10, 20], [11, 20]],
                                               [[-3, -5], [-2, -3]]])
        self.assertEqual(geom["pathEvidence"][0]["collapsedZeroDeltas"], 1)
        self.assertEqual(geom["pathEvidence"][1]["sourceScalarOffset"], 10)
        self.assertEqual(result["summary"]["collapsedOrIgnoredPointPairs"], 1)

    def test_point_peeks_first_pair_but_skips_all_stored_pairs(self):
        result = local([2, 2], [(7, 9), (2**31 - 1, 2**31 - 1), (100, 200), (1, 2)],
                       [message(1, b""), line()])
        self.assertEqual(result["features"][0]["geometry"]["coordinates"], [7, 9])
        self.assertEqual(result["features"][0]["geometry"]["pathEvidence"][0]["ignoredStoredPointPairs"], 1)
        self.assertEqual(result["features"][1]["geometry"]["coordinates"], [[[100, 200], [101, 202]]])

    def test_polygon_outer_holes_reset_and_auxiliary_paths_are_separate(self):
        result = local([3, 3, 2], [(1, 1), (3, 0), (0, 3), (2, 2), (1, 0), (0, 1), (8, 9), (2, 1)],
                       [polygon((2,), 1, (999,))])
        geom = result["features"][0]["geometry"]
        self.assertEqual(geom["coordinates"], [[[[1, 1], [4, 1], [4, 4]], [[2, 2], [3, 2], [3, 3]]]])
        self.assertEqual(geom["auxiliaryPaths"], [[[8, 9], [10, 10]]])
        self.assertEqual(geom["auxiliaryRole"], "unknown")
        self.assertNotEqual(geom["coordinates"][0][0][0], geom["coordinates"][0][0][-1])

    def test_absent_line_count_defaults_one_and_short_optional_scalars_default_zero(self):
        result = local([1], [(0, 0)], [line(None)])
        self.assertEqual(len(result["features"][0]["geometry"]["coordinates"]), 1)
        result = to_world(local([1, 1], [(0, 0), (1, 1)], [line(2, (), (7,))], bits=3), TileId(0, 0, 30))
        geom = result["features"][0]["geometry"]
        self.assertEqual(geom["field2WorldScalars"], [0, 0])
        self.assertEqual(geom["field3WorldScalars"], [1, 0])
        with self.assertRaisesRegex(GeometryError, "metadata cardinality"):
            local([1, 1], [(0, 0), (1, 1)], [line(2, (7,))])

    def test_zero_line_and_ring_counts_and_zero_length_paths_preserved(self):
        result = local([0], [], [line(), line(0), polygon((0,), 0)])
        self.assertEqual([f["geometry"]["coordinates"] for f in result["features"]], [[[]], [], [[]]])
        with self.assertRaisesRegex(GeometryError, "empty Point"):
            local([0], [], [message(1, b"")])

    def test_grid_min_and_max_map_to_exact_tile_edges_with_2_power_b_minus_one(self):
        tile_id = TileId(2, 1, 2)
        size = 2**28
        self.assertEqual(local_to_world([-4, -4], grid_bits=3, tile_id=tile_id), [2 * size, size])
        self.assertEqual(local_to_world([3, 3], grid_bits=3, tile_id=tile_id), [3 * size, 2 * size])
        midpoint = local_to_world([0, 0], grid_bits=3, tile_id=tile_id)
        self.assertAlmostEqual(midpoint[0], 2 * size + 4 * size / 7)
        self.assertNotEqual(midpoint[0], 2.5 * size)
        with self.assertRaisesRegex(GeometryError, "bounds"):
            local_to_world([4, 4], grid_bits=3, tile_id=tile_id)

    def test_invalid_or_missing_tile_context_is_not_guessed(self):
        for xyz in ((1, 0, 0), (-1, 0, 5), (0, 0, 31), (True, 0, 1), (0.0, 0, 1)):
            with self.subTest(xyz=xyz), self.assertRaises(GeometryError):
                TileId(*xyz)
        with self.assertRaisesRegex(GeometryError, "TileId"):
            local_to_world([0, 0], grid_bits=14, tile_id=None)

    def test_native_ellipsoidal_kazan_anchor_and_axis_order(self):
        # Independent native-projection agent's pinned instruction/literal receipt.
        xy = wgs84_to_world([49.106414, 55.796127])
        self.assertAlmostEqual(xy[0], 683336496.8290532, places=6)
        self.assertAlmostEqual(xy[1], 336388190.17105573, places=6)
        phi = math.radians(55.796127)
        spherical_y = 2**30 * (math.pi - math.log(math.tan(math.pi / 4 + phi / 2))) / (2 * math.pi)
        self.assertGreater(abs(xy[1] - spherical_y), 900000)
        self.assertEqual(wgs84_to_world([0, 0]), [2**29, 2**29])
        self.assertEqual(wgs84_to_world([-180, 90]), [0, 0])
        self.assertEqual(wgs84_to_world([180, -90]), [2**30, 2**30])
        self.assertEqual(world_to_wgs84([2**29, 2**29]), [0, 0])
        self.assertGreater(world_to_wgs84([2**30 + 100, 2**29])[0], 180)

    def test_projection_roundtrips_and_rejects_nonfinite_inputs(self):
        for lon in (-179.9, -10, 49.1, 179.9):
            for lat in (-80, -55.8, 0, 55.8, 80):
                xy = wgs84_to_world([lon, lat])
                actual = world_to_wgs84(xy)
                self.assertAlmostEqual(actual[0], lon, places=10)
                self.assertAlmostEqual(actual[1], lat, places=8)
        for point in ([float("nan"), 1], [1, float("inf")], [True, 1], [181, 1], [1, 91]):
            with self.subTest(point=point), self.assertRaises(GeometryError):
                wgs84_to_world(point)

    def test_exact_buffer_consumption_overflow_geometry_and_all_budgets(self):
        parsed = parse_tile(raw_tile([2], [(1, 2), (3, 4)], [line()]))
        variants = []
        value = json.loads(json.dumps(parsed)); value["sharedBuffers"]["field2Sint32"].pop(); variants.append(value)
        value = json.loads(json.dumps(parsed)); value["sharedBuffers"]["field3Uint32"] = [1]; variants.append(value)
        value = json.loads(json.dumps(parsed)); value["layers"][0]["features"][0]["geometries"] = []; variants.append(value)
        value = json.loads(json.dumps(parsed)); value["field1Uint32"] = 0; variants.append(value)
        for value in variants:
            with self.assertRaises(GeometryError):
                decode_local(value)
        with self.assertRaisesRegex(GeometryError, "accumulated"):
            local([2], [(2**30 - 1, 0), (2**31 - 1, 0)], [line()], bits=31)
        for limits in (replace(GeometryLimits(), max_points=1),
                       replace(GeometryLimits(), max_points_per_path=1),
                       replace(GeometryLimits(), max_features=0)):
            with self.assertRaises(GeometryError):
                decode_local(parsed, limits=limits)
        with self.assertRaisesRegex(GeometryError, "budget"):
            local([1, 1], [(0, 0), (1, 1)], [line(2)], limits=replace(GeometryLimits(), max_paths=1))
        with self.assertRaisesRegex(GeometryError, "byte budget"):
            decode_geometry(b"abc", tile_id=TileId(0, 0, 0), limits=replace(GeometryLimits(), max_total_bytes=2))

    def test_real_old_corpus_consumes_all_buffers_without_guessing_tile_id(self):
        root = Path("captures/yandex-only-richer-20261024/runtime-vector")
        if not (root / "vector-001.bin").exists():
            self.skipTest("local untracked capture corpus not installed")
        expected = [(3345, 6379, 27512), (2, 2, 15), (2, 2, 10), (6260, 11318, 48442)]
        for number, counts in enumerate(expected, 1):
            parsed = parse_tile((root / ("vector-%03d.bin" % number)).read_bytes())
            result = decode_local(parsed)
            summary = result["summary"]
            self.assertEqual((summary["features"], summary["paths"], summary["sourcePoints"]), counts)
            self.assertEqual(summary["decodedPoints"], counts[2])
            self.assertNotIn("tileId", result)

    def test_capture_requires_success_hash_and_three_equal_native_contexts(self):
        raw = raw_tile([1], [(0, 0)], [message(1, b"")])
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "vector-001.bin").write_bytes(raw)
            context = {"x": 0, "y": 0, "z": 0, "rawHex": struct.pack("<III", 0, 0, 0).hex()}
            row = {"binaryFile": "vector-001.bin", "binarySize": len(raw), "sha256": hashlib.sha256(raw).hexdigest(),
                   "payload": {"nativeParseSucceeded": True, "threadId": 12,
                               "tileId": context, "renderTileId": context, "layerSourceTileId": context}}
            def save(value):
                (root / "events.jsonl").write_text(json.dumps(value) + "\n")
            save(row)
            result = decode_capture(root)
            self.assertEqual(result["summary"]["verifiedTileContexts"], 1)
            self.assertEqual(result["tiles"][0]["coordinateFrame"], "wgs84")
            for field, value in (("sha256", "0" * 64), ("binarySize", 1), ("binaryFile", "../outside.bin")):
                altered = json.loads(json.dumps(row)); altered[field] = value; save(altered)
                with self.subTest(field=field), self.assertRaises(GeometryError):
                    decode_capture(root)
            for field in ("nativeParseSucceeded", "renderTileId", "layerSourceTileId"):
                altered = json.loads(json.dumps(row))
                altered["payload"][field] = False if field == "nativeParseSucceeded" else {
                    "x": 1, "y": 0, "z": 1, "rawHex": struct.pack("<III", 1, 0, 1).hex()}
                save(altered)
                with self.subTest(field=field), self.assertRaises(GeometryError):
                    decode_capture(root)
            save(row)
            with self.assertRaisesRegex(GeometryError, "byte budget"):
                decode_capture(root, limits=replace(GeometryLimits(), max_total_bytes=len(raw) - 1))
            second = {**row, "binaryFile": "vector-002.bin"}
            (root / "vector-002.bin").write_bytes(raw)
            (root / "events.jsonl").write_text(json.dumps(row) + "\n" + json.dumps(second) + "\n")
            with self.assertRaisesRegex(GeometryError, "tile count"):
                decode_capture(root, limits=replace(GeometryLimits(), max_tiles=1))
            with self.assertRaisesRegex(GeometryError, "total geometry"):
                decode_capture(root, limits=replace(GeometryLimits(), max_points=1))

    def test_comparison_keeps_parallel_candidates_ambiguous_and_missing_unmatched(self):
        source = [{"id": "route", "points": [[0, 0], [.0002, 0]]}]
        targets = [{"id": "left", "points": [[0, .00001], [.0002, .00001]]},
                   {"id": "right", "points": [[0, -.00001], [.0002, -.00001]]}]
        result = compare_polylines(source, targets)
        self.assertAlmostEqual(result["ambiguousM"], result["sourceLengthM"])
        self.assertEqual(result["uniqueMatchedM"], 0)
        self.assertEqual(len(result["sourceLines"][0]["candidatePhysicalKeys"]), 2)
        result = compare_polylines(source, [])
        self.assertAlmostEqual(result["unmatchedM"], result["sourceLengthM"])
        self.assertEqual(result["coverage"], 0)
        self.assertIsNone(result["distanceM"])

    def test_comparison_reverse_graph_edges_collapse_but_crossroads_heading_does_not(self):
        points = [[0, 0], [.0002, 0]]
        edges = [{"id": "forward", "edgeDataIdHex": "0x12", "geometry": {"points": points}},
                 {"id": "reverse", "edgeDataIdHex": "0x12", "geometry": {"points": list(reversed(points))}}]
        target = graph_lines([{"edges": edges}])
        target.append({"id": "crossing", "points": [[.0001, -.0001], [.0001, .0001]]})
        result = compare_polylines([{"id": "route", "points": points}], target)
        self.assertAlmostEqual(result["uniqueMatchedM"], result["sourceLengthM"])
        self.assertEqual(result["ambiguousM"], 0)
        self.assertAlmostEqual(result["distanceM"]["max"], 0)
        self.assertEqual(result["sourceLines"][0]["candidatePhysicalKeys"][0]["targetIds"], ["forward", "reverse"])

    def test_comparison_rejects_unbounded_work_and_wide_metric_extent(self):
        source = [{"id": "route", "points": [[0, 0], [.0002, 0]]}]
        with self.assertRaisesRegex(GeometryError, "sample budget"):
            compare_polylines(source, [], max_samples=1)
        with self.assertRaisesRegex(GeometryError, "line budget"):
            compare_polylines(source, source, max_segments=1)
        with self.assertRaisesRegex(GeometryError, "local metric extent"):
            compare_polylines([{"id": "wide", "points": [[0, 0], [3, 0]]}], [])

    def test_enrichment_preserves_literal_types_directed_ids_and_fragment_scope(self):
        points = [[0, 0], [.0002, 0]]
        feature = {"layer": "road", "featureIndex": 0, "field5Uint64": "123",
                   "geometry": {"type": "MultiLineString", "coordinates": [points]},
                   "attributes": {"keySetIndex": 0, "valueIndices": [0, 1, 2], "issues": []}}
        capture = {"tiles": [{"source": {"sha256": "a" * 64}, "features": [feature],
                    "dictionaries": {"keySets": [{"keys": ["class", "fc", "oneway"]}],
                                     "values": [{"value": "7_pedestrian"}, {"value": 7}, {"value": "1"}]}}]}
        def edge(name, reverse=False):
            return {"id": name, "edgeIdHex": name, "edgeDataIdHex": "0x99", "from": None, "to": "0x1",
                    "topology": {"unresolvedEndpoints": {"from": "clipped"}},
                    "geometry": {"points": list(reversed(points)) if reverse else points}}
        graphs = [{"source": {"sha256": "b" * 64}, "edges": [edge("0x80"), edge("0x81", True)]}]
        result = enrichment_table(capture, graphs)
        self.assertEqual(result["summary"]["physicalGraphFragments"], 1)
        row = result["rows"][0]
        self.assertEqual(row["geometryStatus"], "single_candidate_geometry_supported")
        self.assertEqual({e["edgeIdHex"] for e in row["directedGraphEdges"]}, {"0x80", "0x81"})
        self.assertIn("fragment_only", row["spatialScope"])
        self.assertEqual(row["candidates"][0]["literalAttributes"], {"class": "7_pedestrian", "fc": 7, "oneway": "1"})
        self.assertAlmostEqual(row["candidates"][0]["coveredFraction"], 1)
        # A second renderer object with identical geometry and different ID is
        # real ambiguity, even if each one individually covers the whole edge.
        capture["tiles"][0]["features"].append({**feature, "field5Uint64": "124", "featureIndex": 1})
        row = enrichment_table(capture, graphs)["rows"][0]
        self.assertEqual(row["geometryStatus"], "ambiguous_geometry")
        self.assertEqual(row["ambiguityFraction"], 1)
        # Numeric equality of unrelated namespaces must never manufacture a join.
        for e in graphs[0]["edges"]:
            e["edgeIdHex"] = "0x7b"
            e["geometry"]["points"] = [[1, 1], [1.0002, 1]]
        self.assertEqual(enrichment_table(capture, graphs)["rows"], [])

    def test_new_native_success_corpus_with_binary_root_metadata(self):
        root = Path("captures/yandex-vector-world-20261024/runtime")
        if not (root / "vector-008.bin").exists():
            self.skipTest("local untracked native-success8tile corpus not installed")
        result = decode_capture(root)
        self.assertEqual(result["summary"]["tiles"], 8)
        self.assertEqual(result["summary"]["verifiedTileContexts"], 8)
        self.assertEqual(result["summary"]["features"], 17895)
        self.assertEqual(result["summary"]["sourcePoints"], 214456)
        self.assertEqual(sum(t["summary"]["paths"] for t in result["tiles"]), 38030)


if __name__ == "__main__":
    unittest.main()
