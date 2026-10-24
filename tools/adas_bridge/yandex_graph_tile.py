"""Read captured Yandex road_graph_layer.tile protobuf, entirely on the host.

    python3 -m tools.adas_bridge.yandex_graph_tile TILE.bin [TILE.bin ...] --out graph.json

The wire schema and orientation follow the pinned Navigator29.8.1 native parser
and tile converter. This reconstructs directed topology, geometry, road names,
speed limits and graph levels. It does not assign road-class/lane semantics to
unnamed fields or merge tile fragments by coordinates. Input and output are
local; this module contains no device, network, publisher or OSM dependency.
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


SCHEMA = "denza-yandex-graph-tile-v1"
COLLECTION_SCHEMA = "denza-yandex-graph-tiles-v1"
NATIVE_EVIDENCE = "research/adas-bridge/yandex-graph-tile-protobuf-evidence-20261024.json"


@dataclass(frozen=True)
class Limits:
    max_bytes: int = 8 * 1024 * 1024
    max_fields: int = 500000
    max_entities: int = 100000
    max_points: int = 500000
    max_string_bytes: int = 65536
    max_tiles: int = 32


class TileError(ValueError):
    """The input exceeds bounds or contradicts the confirmed wire schema."""


class _Reader:
    def __init__(self, limits):
        self.limits, self.fields_used, self.points_used = limits, 0, 0
        if any(type(v) is not int or v <= 0 for v in vars(limits).values()):
            raise TileError("parser limits must be positive integers")

    @staticmethod
    def varint(data, offset):
        value = 0
        for index in range(10):
            if offset >= len(data):
                raise TileError("truncated protobuf varint")
            byte = data[offset]
            offset += 1
            if index == 9 and byte > 1:
                raise TileError("protobuf varint exceeds uint64")
            value |= (byte & 127) << (7 * index)
            if byte < 128:
                return value, offset
        raise TileError("protobuf varint exceeds uint64")

    def fields(self, data):
        result, offset = defaultdict(list), 0
        while offset < len(data):
            self.fields_used += 1
            if self.fields_used > self.limits.max_fields:
                raise TileError("protobuf field budget exceeded")
            tag, offset = self.varint(data, offset)
            number, wire = tag >> 3, tag & 7
            if not 0 < number < (1 << 29):
                raise TileError("invalid protobuf field number")
            if wire == 0:
                value, offset = self.varint(data, offset)
            elif wire in (1, 2, 5):
                size = 8 if wire == 1 else 4
                if wire == 2:
                    size, offset = self.varint(data, offset)
                if size > len(data) - offset:
                    raise TileError("protobuf field exceeds message boundary")
                value = data[offset:offset + size]
                offset += size
            else:
                raise TileError("unsupported protobuf wire type " + str(wire))
            result[number].append((wire, value))
        return result

    def integers(self, fields, number):
        result = []
        for wire, value in fields.get(number, []):
            if wire == 0:
                result.append(value)
            elif wire == 2:
                offset = 0
                while offset < len(value):
                    self.fields_used += 1
                    if self.fields_used > self.limits.max_fields:
                        raise TileError("packed integer budget exceeded")
                    item, offset = self.varint(value, offset)
                    result.append(item)
            else:
                raise TileError("invalid repeated integer wire type")
        return result

    def polyline(self, data):
        fields, axes = self.fields(data), []
        for number in (1, 2):
            axis = self.fields(_one(fields, number, 2, required=True))
            first = _one(axis, 1, 0, required=True)
            packed = _one(axis, 2, 2, required=True)
            coordinate, offset = _unzig(first), 0
            values = [coordinate]
            while offset < len(packed):
                if len(values) > self.limits.max_points:
                    raise TileError("polyline point budget exceeded")
                value, offset = self.varint(packed, offset)
                coordinate += _unzig(value)
                values.append(coordinate)
            axes.append(values)
        if len(axes[0]) != len(axes[1]):
            raise TileError("polyline coordinate axes have different lengths")
        self.points_used += len(axes[0])
        if self.points_used > self.limits.max_points:
            raise TileError("tile point budget exceeded")
        points = [[lon / 1e6, lat / 1e6] for lon, lat in zip(*axes)]
        if any(abs(lon) > 180 or abs(lat) > 90 for lon, lat in points):
            raise TileError("polyline contains invalid WGS84 coordinate")
        return points


def _one(fields, number, wire, *, required=False):
    values = fields.get(number, [])
    if not values:
        if required:
            raise TileError("missing required field " + str(number))
        return None
    if len(values) != 1 or values[0][0] != wire:
        raise TileError("duplicate or wrong wire type for field " + str(number))
    return values[0][1]


def _unzig(value):
    return (value >> 1) ^ -(value & 1)


def _int32(value):
    if value is None:
        return None
    value &= 0xFFFFFFFF
    return value - (1 << 32) if value & (1 << 31) else value


def _uint32(value):
    if value > 0xFFFFFFFF:
        raise TileError("uint32 field exceeds bounds")
    return value


def _unknown(fields, known):
    return [{"field": number, "wire": wire,
             **({"hex": value.hex()} if isinstance(value, bytes) else {"unsignedDecimal": str(value)})}
            for number, values in sorted(fields.items()) if number not in known for wire, value in values]


def _insert(table, key, value, kind):
    if key in table:
        raise TileError("duplicate " + kind + " ID " + hex(key))
    table[key] = value


def _length(points):
    result = 0.0
    for a, b in zip(points, points[1:]):
        lon1, lat1, lon2, lat2 = map(math.radians, (*a, *b))
        h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
        result += 2 * 6371008.8 * math.asin(min(1, math.sqrt(h)))
    return round(result, 6)


def parse_tile(raw, *, source="in-memory", limits=None):
    """Decode one bounded immutable tile snapshot; unresolved endpoints stay null.

    IDs are the vendor's actual uint64 values rendered as hex strings. Their
    stability across map releases is unproved. Per-tile identity includes the
    source hash, because the same vendor edge may have different tile fragments.
    """
    limits = limits or Limits()
    reader = _Reader(limits)
    if not isinstance(raw, (bytes, bytearray, memoryview)):
        raise TileError("tile input must be bytes")
    if len(raw) > limits.max_bytes:
        raise TileError("tile byte budget exceeded")
    raw = bytes(raw)
    digest = hashlib.sha256(raw).hexdigest()
    root = reader.fields(raw)
    entities = sum(len(root.get(number, [])) for number in (1, 2, 3, 4))
    if entities > limits.max_entities:
        raise TileError("tile entity budget exceeded")
    if not entities:
        raise TileError("tile has no confirmed graph entities")
    rows = {}
    for number in (1, 2, 3, 4):
        if any(wire != 2 for wire, _ in root.get(number, [])):
            raise TileError("graph entity must be a length-delimited message")
        rows[number] = [reader.fields(value) for _, value in root.get(number, [])]
    roads, vertices, data, edges = {}, {}, {}, {}
    for row in rows[4]:
        key = _uint32(_one(row, 1, 0, required=True))
        name = _one(row, 2, 2, required=True)
        if len(name) > limits.max_string_bytes:
            raise TileError("road name byte budget exceeded")
        try:
            name = name.decode("utf-8")
        except UnicodeDecodeError as error:
            raise TileError("invalid UTF-8 road name") from error
        _insert(roads, key, {"id": hex(key), "name": name, "unknownFields": _unknown(row, {1, 2})}, "Road")
    incoming, outgoing = defaultdict(set), defaultdict(set)
    for row in rows[1]:
        key = _one(row, 1, 0, required=True)
        ins, outs = reader.integers(row, 3), reader.integers(row, 4)
        value = {"id": hex(key), "zLevel": _int32(_one(row, 5, 0)),
                 "rawIncomingEdgeIds": [hex(i) for i in ins], "rawOutgoingEdgeIds": [hex(i) for i in outs],
                 "incomingEdgeIds": [], "outgoingEdgeIds": [], "unknownFields": _unknown(row, {1, 3, 4, 5})}
        _insert(vertices, key, value, "Vertex")
        for edge in ins:
            incoming[edge].add(key)
        for edge in outs:
            outgoing[edge].add(key)
    for row in rows[3]:
        key = _one(row, 1, 0, required=True)
        points = reader.polyline(_one(row, 6, 2, required=True))
        field7, field8 = (_uint32(_one(row, n, 0, required=True)) for n in (7, 8))
        field11 = _one(row, 11, 0)
        if field11 is not None:
            _uint32(field11)
        road_ids = [_uint32(i) for i in reader.integers(row, 9)]
        speed_raw = _one(row, 10, 5)
        speed = None if speed_raw is None else struct.unpack("<f", speed_raw)[0]
        if speed is not None and (not math.isfinite(speed) or speed < 0):
            raise TileError("invalid speed limit")
        value = {"id": hex(key), "geometry": {"points": points, "complete": True,
                    "scope": "full_stored_tile_fragment", "lengthM": _length(points)},
                 "roadIds": [hex(i) for i in road_ids], "names": [roads[i]["name"] for i in road_ids if i in roads],
                 "missingRoadIds": [hex(i) for i in road_ids if i not in roads], "speedLimitMps": speed,
                 "raw": {"field7": field7, "field8": field8, "field11": field11, "field11Present": 11 in row,
                         "unknownFields": _unknown(row, {1, 6, 7, 8, 9, 10, 11})}}
        _insert(data, key, value, "EdgeData")
    issues = []
    for row in rows[2]:
        key = _one(row, 1, 0, required=True)
        data_key = _one(row, 2, 0, required=True)
        reverse = _one(row, 3, 0, required=True)
        if reverse not in (0, 1):
            raise TileError("invalid reverse-geometry boolean")
        _insert(edges, key, None, "Edge")
        record = data.get(data_key)
        reasons, endpoints = {}, []
        marker_pair = None if record is None else [record["raw"]["field7"], record["raw"]["field8"]]
        if reverse and marker_pair is not None:
            marker_pair.reverse()
        for end, candidates, index in (("from", outgoing[key], 0), ("to", incoming[key], 1)):
            reason = None
            if record is None:
                reason = "missing_edge_data"
            elif marker_pair[index] != 0:
                reason = "native_converter_uses_anonymous_endpoint_for_nonzero_raw_field"
            elif len(record["geometry"]["points"]) < 2:
                reason = "insufficient_geometry_points"
            elif not candidates:
                reason = "vertex_adjacency_missing_in_captured_tile"
            elif len(candidates) != 1:
                reason = "conflicting_vertex_adjacency"
            endpoints.append(None if reason else hex(next(iter(candidates))))
            if reason:
                reasons[end] = reason
        points = [] if record is None else record["geometry"]["points"]
        if reverse:
            points = list(reversed(points))
        raw_candidates = [sorted(outgoing[key]), sorted(incoming[key])]
        levels = [vertices[v[0]]["zLevel"] if len(v) == 1 else None for v in raw_candidates]
        edge = {"id": digest[:16] + "/edge" + hex(key), "edgeIdHex": hex(key), "edgeDataIdHex": hex(data_key),
                "from": endpoints[0], "to": endpoints[1], "reverseGeometry": bool(reverse),
                "geometry": None if record is None else {**record["geometry"], "points": points},
                "names": [] if record is None else record["names"],
                "speedLimitMps": None if record is None else record["speedLimitMps"],
                "graphLevels": {"begin": levels[0], "end": levels[1]},
                "topology": {"completeStoredEndpoints": not reasons, "unresolvedEndpoints": reasons},
                "raw": {"fromVertexCandidates": [hex(i) for i in raw_candidates[0]],
                        "toVertexCandidates": [hex(i) for i in raw_candidates[1]],
                        "edgeUnknownFields": _unknown(row, {1, 2, 3})}}
        if record is not None:
            edge["raw"].update(record["raw"])
            edge["roadIds"] = record["roadIds"]
            edge["missingRoadIds"] = record["missingRoadIds"]
        edges[key] = edge
        for end, reason in reasons.items():
            issues.append({"edgeIdHex": hex(key), "endpoint": end, "reason": reason})
        for end, name in ((0, "outgoingEdgeIds"), (1, "incomingEdgeIds")):
            if endpoints[end] is not None:
                vertices[int(endpoints[end], 16)][name].append(hex(key))
    summary = {"vertices": len(vertices), "directedEdges": len(edges), "edgeData": len(data), "roads": len(roads),
               "geometryPoints": reader.points_used, "edgesWithResolvedEndpoints": sum(e["topology"]["completeStoredEndpoints"] for e in edges.values()),
               "unresolvedEndpoints": len(issues), "missingEdgeData": sum(e["geometry"] is None for e in edges.values()),
               "field11Present": sum(v["raw"]["field11Present"] for v in data.values()),
               "rawField7Values": dict(Counter(str(v["raw"]["field7"]) for v in data.values())),
               "rawField8Values": dict(Counter(str(v["raw"]["field8"]) for v in data.values())),
               "rawField11Values": dict(Counter(str(v["raw"]["field11"]) for v in data.values())),
               "zLevelValues": dict(Counter(str(v["zLevel"]) for v in vertices.values()))}
    return {"schema": SCHEMA, "source": {"name": str(source), "sha256": digest, "bytes": len(raw)},
            "coordinateFrame": "wgs84", "pointOrder": "lon,lat", "topologyScope": "captured_tile_only",
            "identityScope": "vendor_ids_within_unidentified_map_release; tile_fragments_may_differ",
            "nativeEvidence": NATIVE_EVIDENCE, "summary": summary,
            "vertices": list(vertices.values()), "edges": list(edges.values()), "edgeData": list(data.values()),
            "roads": list(roads.values()), "issues": issues, "unknownRootFields": _unknown(root, {1, 2, 3, 4})}


def decode_files(paths, *, limits=None):
    """Keep tiles separate: shared IDs do not justify joining clipped fragments."""
    limits = limits or Limits()
    tiles = []
    for path in paths:
        if len(tiles) >= limits.max_tiles:
            raise TileError("tile collection budget exceeded")
        path = Path(path)
        with path.open("rb") as stream:
            raw = stream.read(limits.max_bytes + 1)
        tiles.append(parse_tile(raw, source=str(path), limits=limits))
    totals = {key: sum(tile["summary"][key] for tile in tiles)
              for key in ("vertices", "directedEdges", "edgeData", "roads", "geometryPoints", "edgesWithResolvedEndpoints", "unresolvedEndpoints", "field11Present")}
    return {"schema": COLLECTION_SCHEMA, "coordinateFrame": "wgs84", "pointOrder": "lon,lat",
            "summary": {"tiles": len(tiles), **totals}, "tiles": tiles,
            "limitations": ["Tile boundary gaps stay unresolved; no coordinate-based junctions or cross-tile merging.",
                            "Stored direction is not a legal one-way-road determination.",
                            "No road class, lane count, formway or ownership semantics are assigned."]}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tiles", type=Path, nargs="+")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args(argv)
    result = decode_files(args.tiles)
    args.out.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n")
    print(json.dumps({"schema": result["schema"], "summary": result["summary"], "out": str(args.out)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
