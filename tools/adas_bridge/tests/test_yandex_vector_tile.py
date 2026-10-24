"""Pinned renderer dictionaries, unknown retention, and hostile input bounds."""
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest

from tools.adas_bridge.yandex_vector_tile import Limits, TileError, decode_files, parse_tile


def vi(value):
    result = bytearray()
    while value > 127:
        result.append((value & 127) | 128)
        value >>= 7
    return bytes(result + bytes([value]))


def integer(field, value):
    return vi(field << 3) + vi(value)


def message(field, raw):
    return vi(field << 3 | 2) + vi(len(raw)) + raw


def fixed(field, value):
    return vi(field << 3 | 5) + struct.pack("<f", value)


def packed(field, values):
    return message(field, b"".join(map(vi, values)))


def attrs(index=0, indices=(0, 1), extra=b""):
    return integer(1, index) + packed(2, indices) + extra


def feature(attributes=None, geometry=None, extra=b""):
    return (message(2, integer(1, 2) + packed(2, [11, 12]) + packed(3, [13])) if geometry is None else geometry) + (
        packed(4, [7, 8]) + integer(5, 2**63 + 17) +
        message(6, attrs() if attributes is None else attributes) + message(7, integer(90, 2)) + extra)


def tile(*, keys=("type", "lanes"), values=None, features=None, extra=b""):
    values = [message(1, b"ROAD_3"), integer(3, 4)] if values is None else values
    return (integer(1, 3) + packed(2, [1, 4]) + packed(3, [10, 11]) +
            message(4, b"".join(message(1, k.encode()) for k in keys)) +
            b"".join(message(5, row) for row in values) +
            message(6, message(1, b"roads") + b"".join(message(2, row) for row in
                    ([feature()] if features is None else features))) + extra)


class VectorTileTests(unittest.TestCase):
    def test_zero_based_dictionary_references_and_raw_geometry(self):
        raw = tile()
        result = parse_tile(raw)
        self.assertEqual(result["source"]["sha256"], hashlib.sha256(raw).hexdigest())
        self.assertEqual(result["dictionaries"]["keySets"][0]["keys"], ["type", "lanes"])
        self.assertEqual(result["dictionaries"]["values"][0]["value"], "ROAD_3")
        row = result["layers"][0]["features"][0]
        self.assertEqual(row["attributes"]["entries"], [
            {"keyIndex": 0, "valueIndex": 0, "resolved": True},
            {"keyIndex": 1, "valueIndex": 1, "resolved": True}])
        self.assertEqual(row["field5Uint64"], str(2**63 + 17))
        self.assertEqual(row["geometries"][0]["field2"], ["11", "12"])
        self.assertEqual(result["sharedBuffers"], {"field2Sint32": [-1, 2], "field3Uint32": [10, 11]})
        self.assertEqual(result["summary"]["literalRoadStyleValues"], {"ROAD_3": 1})
        for guessed in ("roadClass", "laneCount", "wgs84", "coordinates", "adasClass"):
            self.assertNotIn(guessed, row)

    def test_all_attribute_types_have_correct_array_tags_and_exact_uint64(self):
        values = [message(1, "улица".encode()), integer(2, 3), integer(3, 0xFFFFFFFF),
                  integer(4, 0xFFFFFFFFFFFFFFFF), fixed(5, 1.25), integer(6, 7),
                  message(7, message(1, struct.pack("<2f", 2.5, 3.5)) + fixed(1, 4.5)),
                  message(8, message(1, b"a") + message(1, b"b")), b""]
        result = parse_tile(tile(values=values, features=[]))["dictionaries"]["values"]
        self.assertEqual([x["value"] for x in result], ["улица", -2, 0xFFFFFFFF,
                         "18446744073709551615", 1.25, True, [2.5, 3.5, 4.5], ["a", "b"], None])
        self.assertEqual(result[5]["rawUnsignedDecimal"], "7")

    def test_unknowns_preserved_at_tile_feature_attributes_and_value(self):
        raw = tile(values=[message(1, b"ROAD_3") + integer(55, 99), integer(3, 4)],
                   features=[feature(attrs(extra=message(90, b"attrs")), extra=integer(91, 5))],
                   extra=message(99, b"opaque"))
        result = parse_tile(raw)
        self.assertEqual(result["unknownFields"], [{"field": 99, "wire": 2, "hex": b"opaque".hex()}])
        self.assertEqual(result["dictionaries"]["values"][0]["unknownFields"][0]["unsignedDecimal"], "99")
        row = result["layers"][0]["features"][0]
        self.assertEqual(row["unknownFields"][0]["field"], 91)
        self.assertEqual(row["attributes"]["unknownFields"][0]["hex"], b"attrs".hex())
        self.assertEqual(row["metadataField7Hex"], integer(90, 2).hex())

    def test_tile_field7_binary_glb_header_from_native_success_capture(self):
        # Exact first 24 bytes of the 51920-byte root f7 in runtime/vector-001.bin
        # (tile SHA256 c3116b8886075a191a3f26946d1c341af051be57a1189f084baf5fd1554e6943).
        # The GLB length header's D0 at offset 8 caused the original UTF-8 failure.
        raw_field = bytes.fromhex("676c544602000000d0ca0000141000004a534f4e7b22617373")
        result = parse_tile(tile(extra=message(7, raw_field)))
        self.assertEqual(bytes.fromhex(result["field7Hex"]), raw_field)
        self.assertIsNone(result["field7String"])
        self.assertEqual(result["field7Contract"], "opaque_length_delimited_bytes_semantics_unknown")

    def test_tile_field7_optional_utf8_empty_absent_and_binary(self):
        for raw_field, text in ((b"", ""), ("текст".encode(), "текст"), (b"\xff\x00\xfe", None)):
            with self.subTest(raw_field=raw_field):
                result = parse_tile(tile(extra=message(7, raw_field)))
                self.assertEqual(result["field7Hex"], raw_field.hex())
                self.assertEqual(result["field7String"], text)
        result = parse_tile(tile())
        self.assertIsNone(result["field7Hex"])
        self.assertIsNone(result["field7String"])
        # Opaque bytes use the input byte budget, independently of text strings.
        result = parse_tile(message(7, b"\xff" * 9), limits=replace(Limits(), max_string_bytes=8))
        self.assertEqual(result["field7Hex"], "ff" * 9)
        with self.assertRaises(TileError):
            parse_tile(message(7, b"\xff" * 9), limits=replace(Limits(), max_bytes=10))
        # Dictionary text remains strict; only the unknown rootf7 is opaque.
        with self.assertRaisesRegex(TileError, "invalid UTF-8"):
            parse_tile(tile(values=[message(1, b"\xff")], features=[]))

    def test_missing_dictionary_indices_stay_explicit(self):
        result = parse_tile(tile(features=[feature(attrs(9, (500,))), feature(attrs(0, (500,))) ]))
        first, second = [r["attributes"] for r in result["layers"][0]["features"]]
        self.assertIn("missing_or_out_of_range_key_set", first["issues"])
        self.assertEqual(first["entries"][0]["keyIndex"], None)
        self.assertFalse(first["entries"][0]["resolved"])
        self.assertIn("key_value_count_mismatch", second["issues"])
        self.assertIn("out_of_range_value_index:500", second["issues"])

    def test_empty_point_and_polygon_raw_fields(self):
        geometries = message(1, b"") + message(3, packed(1, [2, 3]) + integer(2, 4) + packed(3, [2**40]))
        rows = parse_tile(tile(features=[feature(geometry=geometries)]))["layers"][0]["features"][0]["geometries"]
        self.assertEqual(rows[0], {"field": 1, "type": "Point", "unknownFields": []})
        self.assertEqual(rows[1]["field1Uint32"], [2, 3])
        self.assertEqual(rows[1]["field2"], 4)
        self.assertEqual(rows[1]["field3Uint64"], [str(2**40)])

    def test_mixed_packed_and_scalar_indices(self):
        result = parse_tile(tile(features=[feature(integer(1, 0) + integer(2, 0) + packed(2, [1]))]))
        self.assertEqual(result["layers"][0]["features"][0]["attributes"]["valueIndices"], [0, 1])

    def test_nonfinite_floats_are_json_safe_and_bit_preserved(self):
        value = parse_tile(tile(values=[fixed(5, float("nan"))], features=[]))["dictionaries"]["values"][0]
        self.assertEqual(value["value"], "nan")
        self.assertEqual(len(value["rawHex"]), 8)
        json.dumps(value, allow_nan=False)

    def test_rejects_truncation_overflow_wrong_wire_and_oneof_conflicts(self):
        for raw in (b"\x00", b"\x80", b"\x0a\x05x", b"\x0b", b"\x08" + b"\xff" * 10,
                    tile(values=[integer(3, 2**32)], features=[]),
                    tile(values=[message(1, b"x") + integer(2, 1)], features=[]),
                    tile(values=[message(7, message(1, b"abc"))], features=[]),
                    message(4, integer(1, 2)), tile(extra=integer(1, 4)),
                    tile(extra=integer(7, 1)), tile(extra=message(7, b"a") + message(7, b"b"))):
            with self.subTest(raw=raw[:20]), self.assertRaises(TileError):
                parse_tile(raw)

    def test_size_field_message_string_and_reference_amplification_budgets(self):
        raw = tile()
        for limits in (replace(Limits(), max_bytes=len(raw)-1), replace(Limits(), max_fields=4),
                       replace(Limits(), max_entities=2), replace(Limits(), max_string_bytes=2)):
            with self.subTest(limits=limits), self.assertRaises(TileError):
                parse_tile(raw, limits=limits)
        # Repeated features referencing a large key set cannot expand without a budget.
        raw = tile(keys=tuple("key" + str(x) for x in range(100)),
                   features=[feature(attrs(0, ())) for _ in range(20)])
        with self.assertRaisesRegex(TileError, "field budget"):
            parse_tile(raw, limits=replace(Limits(), max_fields=1000))

    def test_file_count_bounded_read_and_output_source(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "tile.bin"
            path.write_bytes(tile())
            result = decode_files([path])
            self.assertEqual(result["tiles"][0]["source"]["path"], str(path))
            with self.assertRaises(TileError):
                decode_files([path] * 5)
            with self.assertRaises(TileError):
                decode_files([path], limits=replace(Limits(), max_bytes=8))


if __name__ == "__main__":
    unittest.main()
