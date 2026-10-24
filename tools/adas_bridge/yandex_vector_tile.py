"""Decode captured Navigator 29.8.1 vmap3 dictionaries and features on the host.

    python3 -m tools.adas_bridge.yandex_vector_tile TILE.bin [...] --out vector.json

This is the renderer protobuf, not the matching graph. Original attribute keys,
typed values, indices, geometry buffers and unknown fields are retained. Geometry
coordinates, renderer ROAD categories, road classes and lane counts are not
inferred. Attribute entries reference the tile's value dictionary to avoid
expanding a repeated value into an unbounded output. No network/device access.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
import hashlib
import json
import math
from pathlib import Path
import struct


SCHEMA = "denza-yandex-vector-tile-v1"
NATIVE_EVIDENCE = "research/adas-bridge/yandex-vector-tile-protobuf-evidence-20261024.json"


class TileError(ValueError):
    """Malformed or over-budget renderer protobuf."""


@dataclass(frozen=True)
class Limits:
    max_bytes: int = 2 * 1024 * 1024
    max_fields: int = 500000
    max_entities: int = 100000
    max_string_bytes: int = 65536
    max_tiles: int = 4


def _zigzag(value):
    return (value >> 1) ^ -(value & 1)


def _float(raw):
    value = struct.unpack("<f", raw)[0]
    return value if math.isfinite(value) else str(value)


class _Reader:
    def __init__(self, limits):
        self.limits, self.fields_used, self.entities_used = limits, 0, 0
        if any(type(v) is not int or v <= 0 for v in vars(limits).values()):
            raise TileError("limits must be positive integers")

    def charge(self):
        self.fields_used += 1
        if self.fields_used > self.limits.max_fields:
            raise TileError("protobuf field budget exceeded")

    @staticmethod
    def varint(raw, offset):
        result = 0
        for index in range(10):
            if offset >= len(raw):
                raise TileError("truncated varint")
            byte = raw[offset]
            offset += 1
            if index == 9 and byte > 1:
                raise TileError("varint exceeds uint64")
            result |= (byte & 127) << (index * 7)
            if byte < 128:
                return result, offset
        raise TileError("varint exceeds uint64")

    def fields(self, raw):
        self.entities_used += 1
        if self.entities_used > self.limits.max_entities:
            raise TileError("message budget exceeded")
        result, offset = defaultdict(list), 0
        while offset < len(raw):
            self.charge()
            tag, offset = self.varint(raw, offset)
            field, wire = tag >> 3, tag & 7
            if not 0 < field < (1 << 29):
                raise TileError("invalid protobuf field number")
            if wire == 0:
                value, offset = self.varint(raw, offset)
            elif wire in (1, 2, 5):
                size = 8 if wire == 1 else 4
                if wire == 2:
                    size, offset = self.varint(raw, offset)
                if size > len(raw) - offset:
                    raise TileError("field exceeds message boundary")
                value, offset = raw[offset:offset + size], offset + size
            else:
                raise TileError("unsupported protobuf wire type " + str(wire))
            result[field].append((wire, value))
        return result

    def integers(self, fields, number, bits=32):
        values = []
        for wire, raw in fields.get(number, []):
            if wire == 0:
                items = [raw]
            elif wire == 2:
                items, offset = [], 0
                while offset < len(raw):
                    self.charge()
                    value, offset = self.varint(raw, offset)
                    items.append(value)
            else:
                raise TileError("wrong repeated integer wire type")
            if any(v >= 1 << bits for v in items):
                raise TileError("integer exceeds uint" + str(bits))
            values.extend(items)
        return values

    def text(self, raw):
        if len(raw) > self.limits.max_string_bytes:
            raise TileError("string byte budget exceeded")
        try:
            return raw.decode("utf-8")
        except UnicodeDecodeError as error:
            raise TileError("invalid UTF-8 string") from error

    def messages(self, fields, number):
        for wire, raw in fields.get(number, []):
            if wire != 2:
                raise TileError("wrong message wire type")
            yield self.fields(raw)

    def value(self, fields):
        # Native oneof tags at parser 0x31075f4; array constructor vtables pin 7/8.
        cases = [number for number in range(1, 9) if number in fields]
        if len(cases) > 1:
            raise TileError("multiple AttributeValue oneof cases")
        result = {"field": cases[0] if cases else None, "type": "unset", "value": None,
                  "unknownFields": _unknown(fields, set(range(1, 9)))}
        if not cases:
            return result
        number = cases[0]
        wire = 2 if number in (1, 7, 8) else 5 if number == 5 else 0
        raw = _one(fields, number, wire)
        kinds = {1: "string", 2: "sint32", 3: "uint32", 4: "uint64", 5: "float32",
                 6: "bool", 7: "float32_array", 8: "string_array"}
        result["type"] = kinds[number]
        if number == 1:
            result["value"] = self.text(raw)
        elif number in (2, 3):
            if raw > 0xFFFFFFFF:
                raise TileError("AttributeValue exceeds uint32")
            result["value"] = _zigzag(raw) if number == 2 else raw
        elif number == 4:
            result["value"] = str(raw)
        elif number == 5:
            result["value"], result["rawHex"] = _float(raw), raw.hex()
        elif number == 6:
            result["value"], result["rawUnsignedDecimal"] = bool(raw), str(raw)
        else:
            array = self.fields(raw)
            if number == 8:
                values = []
                for array_wire, item in array.get(1, []):
                    if array_wire != 2:
                        raise TileError("wrong StringArray wire type")
                    values.append(self.text(item))
            else:
                values = []
                for array_wire, item in array.get(1, []):
                    if array_wire not in (2, 5) or len(item) % 4:
                        raise TileError("wrong FloatArray wire type or size")
                    for start in range(0, len(item), 4):
                        self.charge()
                        values.append(_float(item[start:start + 4]))
                result["rawArrayFields"] = _unknown(array, set())
            result["value"], result["arrayUnknownFields"] = values, _unknown(array, {1})
        return result


def _one(fields, number, wire):
    rows = fields.get(number, [])
    if not rows:
        return None
    if len(rows) != 1 or rows[0][0] != wire:
        raise TileError("duplicate or wrong wire type for field " + str(number))
    return rows[0][1]


def _unknown(fields, known):
    return [{"field": number, "wire": wire,
             **({"hex": value.hex()} if isinstance(value, bytes) else {"unsignedDecimal": str(value)})}
            for number, rows in sorted(fields.items()) if number not in known for wire, value in rows]


def _attributes(reader, fields, key_sets, values):
    key_index, indices = _one(fields, 1, 0), reader.integers(fields, 2)
    result = {"keySetIndex": key_index, "valueIndices": indices, "entries": [], "issues": [],
              "unknownFields": _unknown(fields, {1, 2})}
    if key_index is None or key_index >= len(key_sets):
        result["issues"].append("missing_or_out_of_range_key_set")
        keys = []
    else:
        keys = key_sets[key_index]["keys"]
        if len(keys) != len(indices):
            result["issues"].append("key_value_count_mismatch")
    for position in range(max(len(keys), len(indices))):
        reader.charge()  # Also bound dictionary-reference output amplification.
        value_index = indices[position] if position < len(indices) else None
        result["entries"].append({"keyIndex": position if position < len(keys) else None,
                                  "valueIndex": value_index,
                                  "resolved": value_index is not None and value_index < len(values)})
        if value_index is not None and value_index >= len(values):
            result["issues"].append("out_of_range_value_index:" + str(value_index))
    return result


def parse_tile(raw, *, source="in-memory", limits=None):
    """Decode the pinned protobuf without assigning road or geometry semantics.

    Dictionary indices are zero-based, confirmed by native 0x1798104 and
    0x1798f8c. Invalid indices are kept with explicit issues. Singular duplicates
    and conflicting oneof cases are rejected rather than silently normalised.
    """
    limits = limits or Limits()
    reader = _Reader(limits)
    if not isinstance(raw, (bytes, bytearray, memoryview)):
        raise TileError("tile input must be bytes")
    if not raw or len(raw) > limits.max_bytes:
        raise TileError("empty tile or tile byte budget exceeded")
    raw = bytes(raw)
    fields = reader.fields(raw)
    key_sets = []
    for row in reader.messages(fields, 4):
        if any(wire != 2 for wire, _ in row.get(1, [])):
            raise TileError("wrong AttributeKeys wire type")
        key_sets.append({"keys": [reader.text(item) for _, item in row.get(1, [])],
                         "unknownFields": _unknown(row, {1})})
    values = [reader.value(row) for row in reader.messages(fields, 5)]
    layers, key_counts, type_counts, road_labels = [], Counter(), Counter(), Counter()
    total_features, total_issues = 0, 0
    for layer in reader.messages(fields, 6):
        name = _one(layer, 1, 2)
        features = []
        for feature in reader.messages(layer, 2):
            geometries = []
            for number, kind in ((1, "Point"), (2, "MultiLineString"), (3, "MultiPolygon")):
                for geometry in reader.messages(feature, number):
                    item = {"field": number, "type": kind}
                    if number == 1:
                        item["unknownFields"] = _unknown(geometry, set())
                    else:
                        item.update({"field1Uint32": _one(geometry, 1, 0) if number == 2 else reader.integers(geometry, 1),
                                     "field2": [str(x) for x in reader.integers(geometry, 2, 64)] if number == 2
                                               else reader.integers(geometry, 1),
                                     "field3Uint64": [str(x) for x in reader.integers(geometry, 3, 64)],
                                     "unknownFields": _unknown(geometry, {1, 2, 3})})
                        if number == 3:
                            # MultiPolygon f1 is repeated uint32, f2 singular uint32.
                            item["field2"] = _one(geometry, 2, 0)
                    geometries.append(item)
                    type_counts[kind] += 1
            attribute_raw = _one(feature, 6, 2)
            attributes = None if attribute_raw is None else _attributes(reader, reader.fields(attribute_raw), key_sets, values)
            if attributes:
                total_issues += len(attributes["issues"])
                for entry in attributes["entries"]:
                    if entry["keyIndex"] is not None:
                        key_counts[key_sets[attributes["keySetIndex"]]["keys"][entry["keyIndex"]]] += 1
                    if entry["resolved"]:
                        value = values[entry["valueIndex"]]["value"]
                        if isinstance(value, str) and (value == "ROAD" or value.startswith("ROAD_")):
                            road_labels[value] += 1
            feature_id = _one(feature, 5, 0)
            metadata = _one(feature, 7, 2)
            features.append({"field5Uint64": None if feature_id is None else str(feature_id),
                             "field4Uint32": reader.integers(feature, 4), "geometries": geometries,
                             "attributes": attributes, "metadataField7Hex": None if metadata is None else metadata.hex(),
                             "unknownFields": _unknown(feature, {1, 2, 3, 4, 5, 6, 7})})
        total_features += len(features)
        layers.append({"name": None if name is None else reader.text(name), "features": features,
                       "unknownFields": _unknown(layer, {1, 2})})
    field7 = _one(fields, 7, 2)
    # Tile.f7 is length-delimited native storage (parser0x3105efc), not a
    # proven UTF-8 field. Native-success captures contain binary GLB payloads.
    # Its bytes are bounded by max_bytes; dictionary strings keep their own
    # max_string_bytes and strict UTF-8 checks above.
    field7_text = None
    if field7 is not None:
        try:
            field7_text = field7.decode("utf-8")
        except UnicodeDecodeError:
            pass
    result = {"schema": SCHEMA, "nativeEvidence": NATIVE_EVIDENCE,
              "source": {"path": str(source), "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()},
              "field1Uint32": _one(fields, 1, 0),
              "sharedBuffers": {"field2Sint32": [_zigzag(x) for x in reader.integers(fields, 2)],
                                "field3Uint32": reader.integers(fields, 3)},
              "field7Hex": None if field7 is None else field7.hex(),
              "field7String": field7_text,
              "field7Contract": "opaque_length_delimited_bytes_semantics_unknown",
              "dictionaries": {"keySets": key_sets, "values": values}, "layers": layers,
              "unknownFields": _unknown(fields, {1, 2, 3, 4, 5, 6, 7}),
              "summary": {"layers": len(layers), "features": total_features, "keySets": len(key_sets),
                          "values": len(values), "geometryTypes": dict(type_counts), "attributeKeys": dict(key_counts),
                          "attributeIssues": total_issues, "literalRoadStyleValues": dict(road_labels)}}
    return result


def decode_files(paths, *, limits=None):
    limits = limits or Limits()
    paths = list(paths)
    if not paths or len(paths) > limits.max_tiles:
        raise TileError("tile count budget exceeded")
    tiles = []
    for value in paths:
        path = Path(value)
        with path.open("rb") as stream:
            raw = stream.read(limits.max_bytes + 1)
        tiles.append(parse_tile(raw, source=path, limits=limits))
    return {"schema": "denza-yandex-vector-tiles-v1", "tiles": tiles}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tiles", nargs="+", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    try:
        result = decode_files(args.tiles)
    except (OSError, TileError) as error:
        parser.exit(2, str(error) + "\n")
    output = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    if args.out:
        args.out.write_text(output, encoding="utf-8")
    else:
        print(output, end="")


if __name__ == "__main__":
    main()
