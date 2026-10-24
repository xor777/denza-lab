"""Wire boundaries, real directed adjacency and conservative partial-tile topology."""
from dataclasses import replace
import hashlib
import math
from pathlib import Path
import struct
import tempfile
import unittest

from tools.adas_bridge.yandex_graph_tile import Limits, TileError, decode_files, parse_tile


def varint(value):
    value &= 0xFFFFFFFFFFFFFFFF
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result + bytes([value]))


def integer(number, value):
    return varint(number << 3) + varint(value)


def message(number, value):
    return varint(number << 3 | 2) + varint(len(value)) + value


def zigzag(value):
    return (value << 1) ^ (value >> 63)


def axis(values):
    return integer(1, zigzag(values[0])) + message(2, b"".join(varint(zigzag(b - a)) for a, b in zip(values, values[1:])))


def geometry(points):
    return message(1, axis([round(p[0] * 1e6) for p in points])) + message(2, axis([round(p[1] * 1e6) for p in points]))


def vertex(key, incoming=(), outgoing=(), level=0):
    return (integer(1, key) + message(3, b"".join(map(varint, incoming))) +
            message(4, b"".join(map(varint, outgoing))) + integer(5, level))


def data(key=101, points=((10.0, 20.0), (10.01, 20.01)), markers=(0, 0), field11=None, extra=b""):
    result = (integer(1, key) + message(6, geometry(points)) + integer(7, markers[0]) + integer(8, markers[1]) +
              message(9, varint(5)) + varint(10 << 3 | 5) + struct.pack("<f", 10.0))
    return result + (b"" if field11 is None else integer(11, field11)) + extra


def edge(key=11, data_id=101, reverse=False, extra=b""):
    return integer(1, key) + integer(2, data_id) + integer(3, int(reverse)) + extra


def tile(*, markers=(0, 0), field11=None, reverse=True, data_bytes=None, vertices=None, edges=None, extra=b""):
    vertices = vertices if vertices is not None else [vertex(7, (12,) if reverse else (), (11,), -1),
                                                     vertex(9, (11,), (12,) if reverse else (), 1)]
    edges = edges if edges is not None else [edge()] + ([edge(12, reverse=True)] if reverse else [])
    return (b"".join(message(1, v) for v in vertices) + b"".join(message(2, e) for e in edges) +
            message(3, data_bytes if data_bytes is not None else data(markers=markers, field11=field11)) +
            message(4, integer(1, 5) + message(2, b"Main Street")) + extra)


class GraphTileTests(unittest.TestCase):
    def test_vendor_direction_geometry_names_limits_and_signed_levels(self):
        result = parse_tile(tile())
        forward, backward = result["edges"]
        self.assertEqual((forward["from"], forward["to"]), ("0x7", "0x9"))
        self.assertEqual((backward["from"], backward["to"]), ("0x9", "0x7"))
        self.assertEqual(backward["geometry"]["points"], list(reversed(forward["geometry"]["points"])))
        self.assertEqual(forward["graphLevels"], {"begin": -1, "end": 1})
        self.assertEqual(backward["graphLevels"], {"begin": 1, "end": -1})
        self.assertEqual(forward["names"], ["Main Street"])
        self.assertEqual(forward["speedLimitMps"], 10.0)
        self.assertTrue(forward["geometry"]["complete"])
        self.assertEqual(forward["geometry"]["scope"], "full_stored_tile_fragment")
        self.assertEqual(result["vertices"][0]["incomingEdgeIds"], ["0xc"])
        for absent in ("oneWay", "roadClass", "laneCount", "formway", "ownership"):
            self.assertNotIn(absent, forward)

    def test_nonzero_native_marker_never_joins_clipped_endpoint_by_vertex_or_coordinate(self):
        result = parse_tile(tile(markers=(13, 0)))
        forward, backward = result["edges"]
        self.assertIsNone(forward["from"])
        self.assertEqual(forward["to"], "0x9")
        self.assertEqual(backward["from"], "0x9")
        self.assertIsNone(backward["to"])
        self.assertEqual(forward["raw"]["fromVertexCandidates"], ["0x7"])
        self.assertEqual(result["vertices"][0]["incomingEdgeIds"], [])
        self.assertEqual(result["vertices"][0]["outgoingEdgeIds"], [])
        self.assertEqual(result["summary"]["unresolvedEndpoints"], 2)

    def test_unnamed_fields_zero_presence_and_unknown_wire_are_preserved(self):
        result = parse_tile(tile(field11=0, data_bytes=data(field11=0, extra=message(99, b"opaque")), extra=integer(101, 9)))
        raw = result["edges"][0]["raw"]
        self.assertEqual((raw["field7"], raw["field8"], raw["field11"]), (0, 0, 0))
        self.assertTrue(raw["field11Present"])
        self.assertEqual(raw["unknownFields"], [{"field": 99, "wire": 2, "hex": b"opaque".hex()}])
        self.assertEqual(result["unknownRootFields"], [{"field": 101, "wire": 0, "unsignedDecimal": "9"}])
        self.assertFalse(parse_tile(tile())["edges"][0]["raw"]["field11Present"])

    def test_large_ids_preserve_uint64_without_json_float_precision_loss(self):
        key = 2**63 + 17
        raw = tile(reverse=False, vertices=[vertex(7, outgoing=(key,)), vertex(9, incoming=(key,))], edges=[edge(key)])
        self.assertEqual(parse_tile(raw)["edges"][0]["edgeIdHex"], hex(key))

    def test_missing_and_conflicting_adjacency_are_unresolved(self):
        for vs, reason in (([vertex(7)], "vertex_adjacency_missing_in_captured_tile"),
                           ([vertex(7, outgoing=(11,)), vertex(8, outgoing=(11,)), vertex(9, incoming=(11,))], "conflicting_vertex_adjacency")):
            with self.subTest(reason=reason):
                edge_row = parse_tile(tile(reverse=False, vertices=vs))["edges"][0]
                self.assertIsNone(edge_row["from"])
                self.assertEqual(edge_row["topology"]["unresolvedEndpoints"]["from"], reason)

    def test_missing_edge_data_never_creates_geometry_or_topology(self):
        result = parse_tile(tile(reverse=False, edges=[edge(data_id=999)]))
        self.assertIsNone(result["edges"][0]["geometry"])
        self.assertIsNone(result["edges"][0]["from"])
        self.assertEqual(result["summary"]["missingEdgeData"], 1)

    def test_source_digest_and_repeated_parsing_are_deterministic(self):
        raw = tile()
        a, b = parse_tile(raw), parse_tile(raw)
        self.assertEqual(a, b)
        self.assertEqual(a["source"]["sha256"], hashlib.sha256(raw).hexdigest())

    def test_duplicate_ids_and_scalars_are_rejected(self):
        for raw in (tile(vertices=[vertex(7), vertex(7)]), tile(edges=[edge(), edge()]),
                    tile(data_bytes=data(extra=integer(1, 101)))):
            with self.subTest(raw=raw[:20]):
                with self.assertRaises(TileError):
                    parse_tile(raw)

    def test_malformed_wire_and_invalid_boolean_are_rejected(self):
        cases = [b"\x0a\x80", b"\x0a\x02\x01", b"\x00", b"\x0b", b"\xff" * 11,
                 tile(reverse=False, edges=[integer(1, 11) + integer(2, 101) + integer(3, 2)])]
        for raw in cases:
            with self.subTest(raw=raw[:20]):
                with self.assertRaises(TileError):
                    parse_tile(raw)

    def test_mismatched_coordinate_axes_out_of_bounds_and_nonfinite_speed_are_rejected(self):
        geom = message(1, axis([100, 101])) + message(2, axis([200]))
        bad_data = integer(1, 101) + message(6, geom) + integer(7, 0) + integer(8, 0)
        nan_speed = integer(1, 101) + message(6, geometry([[10, 20], [11, 21]])) + integer(7, 0) + integer(8, 0) + varint(85) + struct.pack("<f", math.nan)
        for record in (bad_data, data(points=((181, 0), (182, 0))), nan_speed):
            with self.assertRaises(TileError):
                parse_tile(tile(data_bytes=record))

    def test_byte_field_entity_point_and_string_budgets_fail_closed(self):
        for limits in (replace(Limits(), max_bytes=1), replace(Limits(), max_fields=4),
                       replace(Limits(), max_entities=1), replace(Limits(), max_points=1),
                       replace(Limits(), max_string_bytes=1), replace(Limits(), max_fields=0)):
            with self.subTest(limits=limits):
                with self.assertRaises(TileError):
                    parse_tile(tile(), limits=limits)

    def test_cross_tile_identity_does_not_merge_different_fragments(self):
        with tempfile.TemporaryDirectory() as folder:
            a, b = Path(folder) / "a.bin", Path(folder) / "b.bin"
            a.write_bytes(tile())
            b.write_bytes(tile(data_bytes=data(points=((10.005, 20.005), (10.01, 20.01)), markers=(1, 0))))
            result = decode_files([a, b])
            first, second = [t["edges"][0] for t in result["tiles"]]
            self.assertEqual(first["edgeIdHex"], second["edgeIdHex"])
            self.assertNotEqual(first["id"], second["id"])
            self.assertEqual(result["summary"]["tiles"], 2)
            with self.assertRaises(TileError):
                decode_files([a, b], limits=replace(Limits(), max_tiles=1))


if __name__ == "__main__":
    unittest.main()
