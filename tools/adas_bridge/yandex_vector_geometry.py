"""Bounded host decoding of captured Yandex renderer geometry.

No device, network, road store, or publisher. The protobuf reader remains in
``yandex_vector_tile``. A TileId must come from the same observed native request;
neither geography nor feature IDs are used to guess it.
"""
from __future__ import annotations

from dataclasses import dataclass
import argparse
from collections import defaultdict, Counter
import csv
import hashlib
import json
import math
from pathlib import Path
import struct

from .yandex_vector_tile import TileError, Limits as TileLimits, parse_tile


SCHEMA = "denza-yandex-vector-geometry-v1"
WORLD_SIZE = 1 << 30
LOCAL_EVIDENCE = {
    "deltaPath": "libmaps-mobile.so@0x17aefd8",
    "zeroDeltaCollapse": "libmaps-mobile.so@0x17af060..0x17af068",
    "normalization": "libmaps-mobile.so@0x17f809c",
    "tileAffine": "libmaps-mobile.so@0x17ac110..0x17ac148",
    "tileBounds": "libmaps-mobile.so@0x1b90e1c/0x1b90e44",
}


class GeometryError(ValueError):
    """Geometry is malformed, over budget, or lacks required provenance."""


@dataclass(frozen=True)
class GeometryLimits:
    max_tiles: int = 8
    max_total_bytes: int = 8 * 1024 * 1024
    max_features: int = 100000
    max_paths: int = 200000
    max_points: int = 500000
    max_points_per_path: int = 100000


@dataclass(frozen=True)
class TileId:
    x: int
    y: int
    z: int

    def __post_init__(self):
        if any(type(v) is not int for v in (self.x, self.y, self.z)):
            raise GeometryError("TileId components must be integers")
        if not 0 <= self.z <= 30 or not 0 <= self.x < 1 << self.z or not 0 <= self.y < 1 << self.z:
            raise GeometryError("TileId outside 30-bit tile pyramid")

    def to_dict(self):
        return {"x": self.x, "y": self.y, "z": self.z}


def _integer(value, name, minimum, maximum):
    if type(value) is not int or not minimum <= value <= maximum:
        raise GeometryError(name + " outside integer bounds")
    return value


def _limits(value):
    value = value or GeometryLimits()
    if not isinstance(value, GeometryLimits) or any(type(v) is not int or v <= 0 for v in vars(value).values()):
        raise GeometryError("geometry limits must be positive integers")
    return value


def inspect_layout(tile, *, limits=None):
    """Verify shared-buffer budgets and structural cardinalities, no projection.

    The geometry converter consumes f3 path lengths in feature order. Point
    contributes one path; MLS f1 is its path count. Polygon f1 gives ring counts,
    and polygon f2 adds independent auxiliary paths. The latter roles are kept
    distinct when decoded; they are never invented as driving graph edges.
    """
    limits = _limits(limits)
    try:
        coords = tile["sharedBuffers"]["field2Sint32"]
        lengths = tile["sharedBuffers"]["field3Uint32"]
        layers = tile["layers"]
    except (KeyError, TypeError) as error:
        raise GeometryError("missing parsed shared geometry buffers") from error
    if not isinstance(coords, list) or not isinstance(lengths, list) or not isinstance(layers, list):
        raise GeometryError("invalid geometry buffer types")
    if len(coords) % 2:
        raise GeometryError("coordinate buffer has an unpaired scalar")
    if len(coords) // 2 > limits.max_points or len(lengths) > limits.max_paths:
        raise GeometryError("shared geometry budget exceeded")
    for value in coords:
        _integer(value, "coordinate delta", -(1 << 31), (1 << 31) - 1)
    for value in lengths:
        _integer(value, "path length", 0, limits.max_points_per_path)
    if sum(lengths) != len(coords) // 2:
        raise GeometryError("path lengths do not consume coordinate buffer exactly")
    features = expected_paths = 0
    kinds = {"Point": 0, "MultiLineString": 0, "MultiPolygon": 0}
    for layer in layers:
        for feature in layer["features"]:
            features += 1
            if features > limits.max_features:
                raise GeometryError("feature budget exceeded")
            if len(feature["geometries"]) != 1:
                raise GeometryError("each feature must have exactly one geometry")
            geometry = feature["geometries"][0]
            kind = geometry["type"]
            if kind not in kinds:
                raise GeometryError("unsupported geometry type")
            kinds[kind] += 1
            if kind == "Point":
                expected_paths += 1
            elif kind == "MultiLineString":
                count = _integer(1 if geometry["field1Uint32"] is None else geometry["field1Uint32"],
                                 "line count", 0, limits.max_paths)
                expected_paths += count
                if 0 < len(geometry["field2"]) < count:
                    raise GeometryError("line distance metadata cardinality mismatch")
            else:
                rings = geometry["field1Uint32"]
                if not isinstance(rings, list) or len(rings) > limits.max_paths:
                    raise GeometryError("polygon ring count budget exceeded")
                for count in rings:
                    expected_paths += _integer(count, "polygon ring count", 0, limits.max_paths)
                count = _integer(geometry["field2"] or 0, "polygon auxiliary count", 0, limits.max_paths)
                expected_paths += count
            if expected_paths > limits.max_paths:
                raise GeometryError("geometry path budget exceeded")
    if expected_paths != len(lengths):
        raise GeometryError("geometry counts do not consume path buffer exactly")
    bits = _integer(tile["field1Uint32"], "local grid bits", 1, 31)
    return {"features": features, "paths": len(lengths), "sourcePoints": len(coords) // 2,
            "geometryTypes": kinds, "gridBits": bits, "gridMinimum": -(1 << (bits - 1)),
            "gridMaximum": (1 << (bits - 1)) - 1, "gridExtent": (1 << bits) - 1}


def local_to_world(point, *, grid_bits, tile_id):
    """Native affine on the centered integer grid, with denominator2**b-1.

    Native normalize.c rejects coordinates outside the local grid. This does
    not extrapolate, clamp, wrap, or guess a TileId.
    """
    if not isinstance(tile_id, TileId):
        raise GeometryError("explicit observed TileId required")
    bits = _integer(grid_bits, "local grid bits", 1, 31)
    if not isinstance(point, (tuple, list)) or len(point) != 2:
        raise GeometryError("local point must have two coordinates")
    minimum, extent = -(1 << (bits - 1)), (1 << bits) - 1
    size = 1 << (30 - tile_id.z)
    return [origin * size + size * (_integer(value, "local point", minimum, minimum + extent) - minimum) / extent
            for origin, value in zip((tile_id.x, tile_id.y), point)]


class _Paths:
    def __init__(self, tile, limits):
        self.coords = tile["sharedBuffers"]["field2Sint32"]
        self.lengths = tile["sharedBuffers"]["field3Uint32"]
        self.length_index = self.point_index = self.output_points = 0
        self.limits = limits
        self.minimum = -(1 << (tile["field1Uint32"] - 1))
        self.maximum = (1 << (tile["field1Uint32"] - 1)) - 1

    def next(self):
        if self.length_index >= len(self.lengths):
            raise GeometryError("path cursor outside shared buffer")
        count = self.lengths[self.length_index]
        start = self.point_index * 2
        result, x, y, collapsed = [], 0, 0, 0
        for index in range(count):
            offset = start + index * 2
            dx, dy = self.coords[offset:offset + 2]
            if index == 0:
                x, y = dx, dy
            else:
                x, y = x + dx, y + dy
                if dx == dy == 0:
                    collapsed += 1
                    continue
            _integer(x, "accumulated x", -(1 << 31), (1 << 31) - 1)
            _integer(y, "accumulated y", -(1 << 31), (1 << 31) - 1)
            _integer(x, "normalized x", self.minimum, self.maximum)
            _integer(y, "normalized y", self.minimum, self.maximum)
            result.append([x, y])
        self.output_points += len(result)
        if self.output_points > self.limits.max_points:
            raise GeometryError("decoded point budget exceeded")
        path = {"coordinates": result, "sourcePointCount": count, "collapsedZeroDeltas": collapsed,
                "sourceLengthIndex": self.length_index, "sourceScalarOffset": start}
        self.length_index += 1
        self.point_index += count
        return path

    def point(self):
        # Native17ad820 peeks first pair;17ad274 consumes N pairs without delta
        # decoding the rest. Do not accidentally decode a Point as a polyline.
        count = self.lengths[self.length_index]
        if count == 0:
            raise GeometryError("empty Point path unsupported: native peeks the next pair")
        start = self.point_index * 2
        coordinates = self.coords[start:start + 2]
        for value in coordinates:
            _integer(value, "normalized Point", self.minimum, self.maximum)
        result = {"coordinates": coordinates, "sourcePointCount": count,
                  "ignoredStoredPointPairs": count - 1, "sourceLengthIndex": self.length_index,
                  "sourceScalarOffset": start}
        self.length_index += 1
        self.point_index += count
        self.output_points += 1
        return result


def decode_local(tile, *, limits=None):
    """Consume all shared buffers according to the native feature converter.

    Each path starts with an absolute pair, followed by deltas. Only zero deltas
    collapse; nonconsecutive equal points and unclosed rings remain unchanged.
    Native int32 overflow wraps; this host verifier rejects it conservatively.
    """
    limits = _limits(limits)
    layout = inspect_layout(tile, limits=limits)
    paths, features = _Paths(tile, limits), []
    for layer in tile["layers"]:
        for index, feature in enumerate(layer["features"]):
            raw = feature["geometries"][0]
            kind, evidence = raw["type"], []
            if kind == "Point":
                point = paths.point()
                coordinates = point.pop("coordinates")
                evidence.append(point)
                geometry = {"type": kind, "coordinates": coordinates}
            elif kind == "MultiLineString":
                coordinates = []
                for _ in range(1 if raw["field1Uint32"] is None else raw["field1Uint32"]):
                    path = paths.next()
                    coordinates.append(path.pop("coordinates"))
                    evidence.append(path)
                geometry = {"type": kind, "coordinates": coordinates,
                            "rawField2Scalars": list(raw["field2"]),
                            "rawField3Scalars": list(raw["field3Uint64"])}
            else:
                coordinates = []
                for ring_count in raw["field1Uint32"]:
                    rings = []
                    for _ in range(ring_count):
                        path = paths.next()
                        rings.append(path.pop("coordinates"))
                        evidence.append(path)
                    coordinates.append(rings)
                auxiliary = []
                for _ in range(raw["field2"] or 0):
                    path = paths.next()
                    auxiliary.append(path.pop("coordinates"))
                    evidence.append(path)
                geometry = {"type": kind, "coordinates": coordinates, "auxiliaryPaths": auxiliary,
                            "auxiliaryRole": "unknown", "rawField3Scalars": list(raw["field3Uint64"])}
            geometry["pathEvidence"] = evidence
            geometry["rawUnknownFields"] = raw.get("unknownFields", [])
            features.append({"layer": layer["name"], "featureIndex": index,
                "field5Uint64": feature["field5Uint64"], "geometry": geometry,
                "attributes": feature.get("attributes"),
                "rawFeatureUnknownFields": feature.get("unknownFields", []),
                "metadataField7Hex": feature.get("metadataField7Hex")})
    if paths.length_index != len(paths.lengths) or paths.point_index * 2 != len(paths.coords):
        raise GeometryError("geometry cursors did not consume shared buffers")
    return {"schema": SCHEMA, "source": tile.get("source"), "coordinateFrame": "native_centered_integer_grid",
        "nativeEvidence": dict(LOCAL_EVIDENCE), "gridBits": layout["gridBits"], "features": features,
        "dictionaries": tile.get("dictionaries"),
        "rawTileMetadata": {"field7Hex": tile.get("field7Hex"), "unknownFields": tile.get("unknownFields", [])},
        "summary": {**layout, "decodedPoints": paths.output_points,
                    "collapsedOrIgnoredPointPairs": layout["sourcePoints"] - paths.output_points},
        "limitations": ["Point with zero stored pairs rejected instead of native cross-path peek",
                         "Accumulated int32 overflow rejected instead of native wrapping",
                         "Polygon auxiliary-path role and feature-ID graph association remain unknown"]}


def _transform_geometry(geometry, transform):
    result = dict(geometry)
    if geometry["type"] == "Point":
        result["coordinates"] = transform(geometry["coordinates"])
    elif geometry["type"] == "MultiLineString":
        result["coordinates"] = [[transform(point) for point in line] for line in geometry["coordinates"]]
    else:
        result["coordinates"] = [[[transform(point) for point in ring] for ring in polygon]
                                 for polygon in geometry["coordinates"]]
        result["auxiliaryPaths"] = [[transform(point) for point in line] for line in geometry["auxiliaryPaths"]]
    return result


def to_world(local, tile_id):
    if not isinstance(tile_id, TileId):
        raise GeometryError("explicit observed TileId required")
    if local.get("coordinateFrame") != "native_centered_integer_grid":
        raise GeometryError("expected local integer geometry")
    result = {**local, "coordinateFrame": "yandex_30bit_world", "tileId": tile_id.to_dict(),
              "tileContextProof": {"verified": False, "method": "explicit_caller_input"}}
    convert = lambda point: local_to_world(point, grid_bits=local["gridBits"], tile_id=tile_id)
    result["features"] = [{**feature, "geometry": _transform_geometry(feature["geometry"], convert)}
                          for feature in local["features"]]
    extent, size = (1 << local["gridBits"]) - 1, 1 << (30 - tile_id.z)
    result["worldTileBounds"] = [tile_id.x * size, tile_id.y * size,
                                 (tile_id.x + 1) * size, (tile_id.y + 1) * size]
    for feature in result["features"]:
        geometry = feature["geometry"]
        if geometry["type"] == "MultiLineString":
            count = len(geometry["coordinates"])
            for raw_key, output_key in (("rawField2Scalars", "field2WorldScalars"),
                                         ("rawField3Scalars", "field3WorldScalars")):
                values = geometry[raw_key]
                geometry[output_key] = [size * int(values[i]) / extent if i < len(values) else 0.0
                                       for i in range(count)]
    return result


_ECCENTRICITY = 0.08181919092890692
_INVERSE_COEFFICIENTS = (0.003356551468879694, 6.57187271079536e-6,
                         1.764564338702e-8, 5.328478445e-11)
PROJECTION_EVIDENCE = {"forward": "libmaps-mobile.so@0x1abf9dc",
                       "inverse": "libmaps-mobile.so@0x1abfad8",
                       "tileSelection": "libmaps-mobile.so@0x1b9fb4c..0x1b9fb60",
                       "model": "ellipsoidal WGS84, not spherical Web Mercator"}


def wgs84_to_world(point):
    if not isinstance(point, (list, tuple)) or len(point) != 2 or any(
            type(v) not in (int, float) or not math.isfinite(v) for v in point):
        raise GeometryError("WGS84 point must contain two finite numbers")
    lon, lat = point
    if not -180 <= lon <= 180 or not -90 <= lat <= 90:
        raise GeometryError("WGS84 coordinate out of range")
    phi = math.radians(max(-89.3, min(89.3, lat)))
    sine = math.sin(phi)
    q = math.tan(math.pi / 4 + phi / 2) * ((1 - _ECCENTRICITY * sine) /
         (1 + _ECCENTRICITY * sine)) ** (_ECCENTRICITY / 2)
    u = max(-math.pi, min(math.pi, math.log(q)))
    return [WORLD_SIZE * (lon + 180) / 360, WORLD_SIZE * (math.pi - u) / (2 * math.pi)]


def world_to_wgs84(point):
    if not isinstance(point, (list, tuple)) or len(point) != 2 or any(
            type(v) not in (int, float) or not math.isfinite(v) for v in point):
        raise GeometryError("world point must contain two finite numbers")
    x, y = point
    # Adjacent-tile clipping buffers may exceed nominal worldbounds. The native
    # formula is preserved without a longitude wrap or geographic guess.
    u = math.pi - 2 * math.pi * y / WORLD_SIZE
    if abs(u) > 700:
        raise GeometryError("world point exceeds finite projection range")
    chi = math.pi / 2 - 2 * math.atan(math.exp(-u))
    phi = chi + sum(c * math.sin(2 * (i + 1) * chi) for i, c in enumerate(_INVERSE_COEFFICIENTS))
    return [360 * x / WORLD_SIZE - 180, math.degrees(phi)]


def to_wgs84(world):
    if world.get("coordinateFrame") != "yandex_30bit_world":
        raise GeometryError("expected 30-bit world geometry")
    bounds = world["worldTileBounds"]
    northwest, southeast = world_to_wgs84(bounds[:2]), world_to_wgs84(bounds[2:])
    return {**world, "coordinateFrame": "wgs84", "pointOrder": "lon,lat",
        "tileWgs84Bounds": [northwest[0], southeast[1], southeast[0], northwest[1]],
        "projectionEvidence": dict(PROJECTION_EVIDENCE),
        "features": [{**feature, "geometry": _transform_geometry(feature["geometry"], world_to_wgs84)}
                     for feature in world["features"]]}


def decode_geometry(raw, *, tile_id, source="in-memory", limits=None, parser_limits=None):
    limits = _limits(limits)
    if not isinstance(raw, (bytes, bytearray, memoryview)) or len(raw) > limits.max_total_bytes:
        raise GeometryError("geometry input byte budget exceeded")
    try:
        parsed = parse_tile(raw, source=source, limits=parser_limits or TileLimits(max_tiles=limits.max_tiles))
    except TileError as error:
        raise GeometryError(str(error)) from error
    return to_wgs84(to_world(decode_local(parsed, limits=limits), tile_id))


def _file_hash(path):
    return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "bytes": path.stat().st_size}


def _observed_id(value):
    if not isinstance(value, dict):
        raise GeometryError("missing observed TileId")
    try:
        result = TileId(value["x"], value["y"], value["z"])
        raw = bytes.fromhex(value["rawHex"])
    except (KeyError, TypeError, ValueError) as error:
        raise GeometryError("malformed observed TileId") from error
    if len(raw) != 12 or struct.unpack("<III", raw) != (result.x, result.y, result.z):
        raise GeometryError("observed TileId bytes disagree with components")
    return result


def decode_capture(directory, *, limits=None, events_name="events.jsonl"):
    """Decode only hash-pinned, native-success, three-context-equal capture rows.

    A wrapper/request TileId alone is insufficient because overzoom can use a
    different render context. The observer records its thread-nested contexts.
    """
    limits = _limits(limits)
    directory = Path(directory)
    event_path = directory / events_name
    with event_path.open("rb") as stream:
        events_raw = stream.read(1024 * 1024 + 1)
    if len(events_raw) > 1024 * 1024:
        raise GeometryError("capture metadata byte budget exceeded")
    rows = events_raw.splitlines()
    if len(rows) > 1000:
        raise GeometryError("capture metadata row budget exceeded")
    tiles, names, total_bytes, total_points, total_features = [], set(), 0, 0, 0
    for line in rows:
        try:
            row = json.loads(line)
        except (ValueError, UnicodeError) as error:
            raise GeometryError("invalid capture metadata JSON") from error
        if not isinstance(row, dict):
            raise GeometryError("capture metadata rows must be objects")
        if "binaryFile" not in row:
            continue
        name = row["binaryFile"]
        if not isinstance(name, str) or Path(name).name != name or name in names:
            raise GeometryError("invalid or repeated capture binary filename")
        names.add(name)
        if len(names) > limits.max_tiles:
            raise GeometryError("capture tile count budget exceeded")
        payload = row.get("payload", {})
        if payload.get("nativeParseSucceeded") is not True:
            raise GeometryError("capture lacks successful native parse")
        _integer(payload.get("threadId"), "native context thread id", 1, (1 << 32) - 1)
        ids = [_observed_id(payload.get(key)) for key in ("tileId", "renderTileId", "layerSourceTileId")]
        if len(set(ids)) != 1:
            raise GeometryError("wrapper, render and layer TileId contexts disagree")
        path = directory / name
        with path.open("rb") as stream:
            raw = stream.read(min(limits.max_total_bytes, TileLimits().max_bytes) + 1)
        total_bytes += len(raw)
        if total_bytes > limits.max_total_bytes:
            raise GeometryError("capture total byte budget exceeded")
        if row.get("binarySize") != len(raw) or row.get("sha256") != hashlib.sha256(raw).hexdigest():
            raise GeometryError("capture binary size/hash mismatch")
        tile = decode_geometry(raw, tile_id=ids[0], source=path, limits=limits)
        tile["tileContextProof"] = {"verified": True, "method": "native_thread_nested_three_context_equality",
            "wrapperTileId": payload["tileId"], "renderTileId": payload["renderTileId"],
            "layerSourceTileId": payload["layerSourceTileId"], "threadId": payload.get("threadId"),
            "decoderOffset": payload.get("decoderOffset"), "layerOffset": payload.get("layerOffset")}
        total_points += tile["summary"]["sourcePoints"]
        total_features += tile["summary"]["features"]
        if total_points > limits.max_points or total_features > limits.max_features:
            raise GeometryError("capture total geometry budget exceeded")
        tiles.append(tile)
    if not tiles:
        raise GeometryError("capture contains no tile binaries")
    return {"schema": "denza-yandex-vector-world-capture-v1", "metadataSource": {
        "path": str(event_path), "sha256": hashlib.sha256(events_raw).hexdigest(), "bytes": len(events_raw)},
        "tiles": tiles, "summary": {"tiles": len(tiles), "bytes": total_bytes,
        "sourcePoints": total_points, "features": total_features,
        "verifiedTileContexts": sum(t["tileContextProof"]["verified"] for t in tiles)}}


def road_lines(capture):
    """Renderer road lines only; no raw class/fc-to-driving-class conversion."""
    result = []
    for tile in capture["tiles"]:
        for feature in tile["features"]:
            if feature["layer"] != "road" or feature["geometry"]["type"] != "MultiLineString":
                continue
            for index, points in enumerate(feature["geometry"]["coordinates"]):
                result.append({"id": tile["source"]["sha256"][:16] + "/" +
                    str(feature["featureIndex"]) + "/" + str(index), "points": points,
                    "featureId": feature["field5Uint64"], "tileSha256": tile["source"]["sha256"]})
    return result


def graph_lines(graph_tiles, *, max_edges=200000, max_points=400000):
    result, points_used = [], 0
    for tile in graph_tiles:
        for edge in tile["edges"]:
            if not edge.get("geometry"):
                continue
            points = edge["geometry"]["points"]
            points_used += len(points)
            if len(result) >= max_edges or points_used > max_points:
                raise GeometryError("graph comparison extraction budget exceeded")
            canonical = min(tuple(map(tuple, points)), tuple(map(tuple, reversed(points))))
            key = edge["edgeDataIdHex"] + "/" + hashlib.sha256(repr(canonical).encode()).hexdigest()[:16]
            result.append({"id": edge["id"], "physicalKey": key, "points": points})
    return result


def compare_polylines(source_lines, target_lines, *, maximum_distance_m=3.0,
                      maximum_heading_degrees=15.0, sample_step_m=10.0,
                      max_samples=100000, max_segments=200000):
    """Evidence-only distance/undirected-heading comparison, never attribute join.

    Samples carry segment-length weights. Multiple physical candidates remain
    ambiguous; absent candidates remain unmatched. Graph direction is ignored
    because renderer polyline orientation is not a legal direction assertion.
    The metric is a local equirectangular approximation, bounded to two degrees.
    """
    if any(type(v) not in (int, float) or not math.isfinite(v) or v <= 0 for v in
           (maximum_distance_m, maximum_heading_degrees, sample_step_m)) or maximum_heading_degrees > 90:
        raise GeometryError("invalid comparison thresholds")
    _integer(max_samples, "sample budget", 1, 1000000)
    _integer(max_segments, "segment budget", 1, 1000000)
    def bounded_rows(values):
        result, points = [], 0
        for row in values:
            if len(result) >= max_segments:
                raise GeometryError("comparison line budget exceeded")
            if not isinstance(row, dict) or not isinstance(row.get("id"), str) or not isinstance(row.get("points"), list):
                raise GeometryError("invalid comparison polyline")
            points += len(row["points"])
            if points > max_segments * 2:
                raise GeometryError("comparison point budget exceeded")
            result.append(row)
        return result
    source_lines, target_lines = bounded_rows(source_lines), bounded_rows(target_lines)
    if len(source_lines) + len(target_lines) > max_segments:
        raise GeometryError("comparison line budget exceeded")
    source_points = [p for row in source_lines for p in row["points"]]
    if not source_points:
        return {"sourceLengthM": 0, "uniqueMatchedM": 0, "ambiguousM": 0, "unmatchedM": 0,
                "samples": 0, "coverage": None, "distanceM": None, "sourceLines": [],
                "method": {"maximumDistanceM": maximum_distance_m, "maximumUndirectedHeadingDegrees": maximum_heading_degrees,
                    "sampleStepM": sample_step_m, "metric": "local_equirectangular", "referenceLat": None,
                    "attributeAssociation": False, "unmatchedAndAmbiguousRetained": True}}
    if len(source_points) > max_segments * 2:
        raise GeometryError("comparison source point budget exceeded")
    for point in source_points:
        wgs84_to_world(point)  # Finite/range validation; no projection-based match.
    lons, lats = [p[0] for p in source_points], [p[1] for p in source_points]
    if max(lons) - min(lons) > 2 or max(lats) - min(lats) > 2:
        raise GeometryError("comparison exceeds bounded local metric extent")
    lon0, lat0 = (min(lons) + max(lons)) / 2, (min(lats) + max(lats)) / 2
    kx, ky = 111195.0802335329 * math.cos(math.radians(lat0)), 111195.0802335329
    convert = lambda p: ((p[0] - lon0) * kx, (p[1] - lat0) * ky)
    source_xy = [convert(p) for p in source_points]
    radius, cell = maximum_distance_m, max(10.0, maximum_distance_m * 2)
    bounds = [min(p[0] for p in source_xy) - radius, min(p[1] for p in source_xy) - radius,
              max(p[0] for p in source_xy) + radius, max(p[1] for p in source_xy) + radius]
    buckets, segments, physical_ids = defaultdict(set), [], defaultdict(set)
    index_entries = 0
    for row in target_lines:
        points = row["points"]
        for point in points:
            wgs84_to_world(point)
        key = row.get("physicalKey", row["id"])
        physical_ids[key].add(row["id"])
        for first, second in zip(points, points[1:]):
            a, b = convert(first), convert(second)
            dx, dy = b[0] - a[0], b[1] - a[1]
            length2 = dx * dx + dy * dy
            if length2 == 0:
                continue
            if len(segments) >= max_segments:
                raise GeometryError("comparison target segment budget exceeded")
            xmin, xmax = max(bounds[0], min(a[0], b[0]) - radius), min(bounds[2], max(a[0], b[0]) + radius)
            ymin, ymax = max(bounds[1], min(a[1], b[1]) - radius), min(bounds[3], max(a[1], b[1]) + radius)
            if xmin > xmax or ymin > ymax:
                continue
            xr, yr = range(math.floor(xmin / cell), math.floor(xmax / cell) + 1), range(math.floor(ymin / cell), math.floor(ymax / cell) + 1)
            index_entries += len(xr) * len(yr)
            if index_entries > max_segments * 20:
                raise GeometryError("comparison spatial index budget exceeded")
            index = len(segments)
            segments.append((a, dx, dy, length2, key))
            for x in xr:
                for y in yr:
                    buckets[x, y].add(index)
    totals, distances, details, sample_count = Counter(), [], [], 0
    heading_cos = math.cos(math.radians(maximum_heading_degrees))
    for row in source_lines:
        counts = Counter()
        candidates = Counter()
        candidate_max_distance, candidate_max_heading = {}, {}
        line_samples = 0
        for first, second in zip(row["points"], row["points"][1:]):
            a, b = convert(first), convert(second)
            sx, sy = b[0] - a[0], b[1] - a[1]
            length = math.hypot(sx, sy)
            if length == 0:
                continue
            number = max(1, math.ceil(length / sample_step_m))
            if sample_count + number > max_samples:
                raise GeometryError("comparison sample budget exceeded")
            weight = length / number
            for n in range(number):
                px, py = a[0] + sx * (n + 0.5) / number, a[1] + sy * (n + 0.5) / number
                eligible = {}
                eligible_heading = {}
                near = False
                for index in buckets.get((math.floor(px / cell), math.floor(py / cell)), ()):
                    start, dx, dy, length2, key = segments[index]
                    fraction = max(0, min(1, ((px - start[0]) * dx + (py - start[1]) * dy) / length2))
                    distance = math.hypot(px - start[0] - fraction * dx, py - start[1] - fraction * dy)
                    if distance > radius:
                        continue
                    near = True
                    cosine = min(1, abs(sx * dx + sy * dy) / (length * math.sqrt(length2)))
                    if cosine < heading_cos:
                        continue
                    if distance < eligible.get(key, float("inf")):
                        eligible[key], eligible_heading[key] = distance, math.degrees(math.acos(cosine))
                counts["sourceLengthM"] += weight
                if near:
                    counts["withinDistanceM"] += weight
                if eligible:
                    distances.append(min(eligible.values()))
                    for key in eligible:
                        candidates[key] += weight
                        candidate_max_distance[key] = max(candidate_max_distance.get(key, 0), eligible[key])
                        candidate_max_heading[key] = max(candidate_max_heading.get(key, 0), eligible_heading[key])
                    counts["uniqueMatchedM" if len(eligible) == 1 else "ambiguousM"] += weight
                else:
                    counts["unmatchedM"] += weight
                sample_count += 1
                line_samples += 1
        totals.update(counts)
        details.append({"id": row["id"], **dict(counts), "sampleCount": line_samples,
            "candidateCount": len(candidates), "candidatePhysicalKeys": [
            {"key": key, "sampleWeightM": weight, "targetIds": sorted(physical_ids[key]),
             "maximumDistanceM": candidate_max_distance[key], "maximumUndirectedHeadingDegrees": candidate_max_heading[key]}
            for key, weight in candidates.most_common(8)]})
    distances.sort()
    distance_summary = None if not distances else {"count": len(distances),
        "p50": distances[(len(distances) - 1) // 2], "p95": distances[int((len(distances) - 1) * .95)], "max": distances[-1]}
    length = totals["sourceLengthM"]
    return {**{key: totals[key] for key in ("sourceLengthM", "withinDistanceM", "uniqueMatchedM", "ambiguousM", "unmatchedM")},
        "samples": sample_count, "coverage": (totals["uniqueMatchedM"] + totals["ambiguousM"]) / length if length else None,
        "distanceM": distance_summary, "sourceLines": details,
        "method": {"maximumDistanceM": radius, "maximumUndirectedHeadingDegrees": maximum_heading_degrees,
                   "sampleStepM": sample_step_m, "metric": "local_equirectangular", "referenceLat": lat0,
                   "dimension": 2, "statisticsScope": "segment-weighted midpoint samples only",
                   "attributeAssociation": False, "unmatchedAndAmbiguousRetained": True}}


LITERAL_ROAD_KEYS = ("class", "fc", "oneway", "struct", "struct:toll", "toll", "hd_covered",
                     "hide_in_road3d", "on_service_area", "zlev", "min_zlev", "max_zlev", "z_level")


def enrichment_table(capture, graph_tiles, *, minimum_coverage=.95, maximum_ambiguity=.01,
                     minimum_length_m=15.0):
    """Review-only graph-fragment→renderer-feature table, preserving literal types.

    The confidence gate needs one candidate over95% of a fragment, <=1%
    ambiguity, >=15m and >=3 samples. These are explicit host review thresholds,
    not road facts. There is no class conversion or transfer to ADAS.
    """
    if any(type(v) not in (int, float) or not math.isfinite(v) for v in
           (minimum_coverage, maximum_ambiguity, minimum_length_m)) or not 0 < minimum_coverage <= 1 or not 0 <= maximum_ambiguity <= 1 or minimum_length_m <= 0:
        raise GeometryError("invalid enrichment review thresholds")
    vector_lines, vector_by_key = [], {}
    for tile in capture["tiles"]:
        for feature in tile["features"]:
            if feature["layer"] != "road" or feature["geometry"]["type"] != "MultiLineString":
                continue
            attribute = feature["attributes"]
            literals, issues = {}, []
            if attribute is not None:
                issues = list(attribute["issues"])
                if not issues:
                    keys = tile["dictionaries"]["keySets"][attribute["keySetIndex"]]["keys"]
                    for key, index in zip(keys, attribute["valueIndices"]):
                        if key in LITERAL_ROAD_KEYS:
                            literals[key] = tile["dictionaries"]["values"][index]["value"]
            for part, points in enumerate(feature["geometry"]["coordinates"]):
                canonical = min(tuple(map(tuple, points)), tuple(map(tuple, reversed(points))))
                key = str(feature["field5Uint64"]) + "/" + hashlib.sha256(
                    repr((canonical, sorted(literals.items()))).encode()).hexdigest()[:16]
                identifier = tile["source"]["sha256"][:16] + "/" + str(feature["featureIndex"]) + "/" + str(part)
                vector_lines.append({"id": identifier, "physicalKey": key, "points": points})
                vector_by_key.setdefault(key, {"vectorFeatureId": feature["field5Uint64"], "literalAttributes": literals,
                                              "attributeIssues": issues, "sources": []})["sources"].append({
                    "tileSha256": tile["source"]["sha256"], "featureIndex": feature["featureIndex"], "part": part})
    sources = {}
    for tile in graph_tiles:
        edges_by_id = {e["id"]: e for e in tile["edges"]}
        for line in graph_lines([tile]):
            source = sources.setdefault(line["physicalKey"], {"id": line["physicalKey"],
                "points": line["points"], "directedGraphEdges": []})
            edge = edges_by_id[line["id"]]
            source["directedGraphEdges"].append({"edgeIdHex": edge["edgeIdHex"],
                "edgeDataIdHex": edge["edgeDataIdHex"], "tileSha256": tile["source"]["sha256"],
                "fromVertexId": edge["from"], "toVertexId": edge["to"],
                "graphLevels": edge.get("graphLevels"),
                "unresolvedEndpoints": edge["topology"]["unresolvedEndpoints"]})
    comparison = compare_polylines(sources.values(), vector_lines, sample_step_m=5.0)
    rows, statuses = [], Counter()
    for row in comparison["sourceLines"]:
        length, candidates = row.get("sourceLengthM", 0), row["candidatePhysicalKeys"]
        ambiguity = row.get("ambiguousM", 0) / length if length else 0
        top_coverage = candidates[0]["sampleWeightM"] / length if candidates and length else 0
        status = "unmatched"
        if candidates:
            if ambiguity > maximum_ambiguity:
                status = "ambiguous_geometry"
            elif top_coverage < minimum_coverage:
                status = "partial_geometry"
            elif length < minimum_length_m or row["sampleCount"] < 3:
                status = "insufficient_extent"
            else:
                status = "single_candidate_geometry_supported"
        statuses[status] += 1
        if not candidates:
            continue
        rows.append({"graphFragmentKey": row["id"], "directedGraphEdges": sources[row["id"]]["directedGraphEdges"],
            "spatialScope": "captured_graph_fragment_only; original unclipped edge extent unknown",
            "geometryStatus": status, "fragmentLengthM": length, "sampleCount": row["sampleCount"],
            "ambiguityFraction": ambiguity, "candidateCount": row["candidateCount"], "candidates": [{
                **candidate, **vector_by_key[candidate["key"]], "coveredFraction": candidate["sampleWeightM"] / length}
                for candidate in candidates]})
    return {"schema": "denza-yandex-vector-graph-enrichment-review-v1", "summary": {
        "physicalGraphFragments": len(sources), "candidateRows": len(rows), "geometryStatuses": dict(statuses)},
        "thresholds": {**comparison["method"], "minimumSingleCandidateCoverage": minimum_coverage,
            "maximumAmbiguityFraction": maximum_ambiguity, "minimumFragmentLengthM": minimum_length_m, "minimumSamples": 3},
        "limitations": ["Review evidence only; no attribute transfer, Amap enum conversion, or feed publication",
            "Literal oneway is not interpreted as legal direction; heading comparison is undirected",
            "Matching source namespaces remain distinct; field5 renderer ID is never compared with graph edge ID",
            "A supported fragment does not establish attributes for an entire unclipped vendor edge",
            "Coverage/distance/heading describe weighted midpoint samples; unsampled gaps remain unverified",
            "Comparison is2D; graphLevels and literal vector z fields are retained without assuming a shared level namespace",
            "Candidates beyond the top8 are counted but omitted; ambiguous rows cannot pass confidence gate"], "rows": rows}


def write_enrichment_csv(table, path):
    columns = ["graph_edge_ids", "graph_tile_hashes", "graph_fragment_key", "vector_feature_id", "vector_tile_hashes",
               "geometry_status", "fragment_length_m", "covered_fraction", "ambiguity_fraction", "maximum_distance_m",
               "maximum_undirected_heading_degrees", *LITERAL_ROAD_KEYS]
    with Path(path).open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns)
        writer.writeheader()
        for row in table["rows"]:
            for candidate in row["candidates"]:
                writer.writerow({"graph_edge_ids": "|".join(sorted({e["edgeIdHex"] for e in row["directedGraphEdges"]})),
                    "graph_tile_hashes": "|".join(sorted({e["tileSha256"] for e in row["directedGraphEdges"]})),
                    "graph_fragment_key": row["graphFragmentKey"], "vector_feature_id": candidate["vectorFeatureId"],
                    "vector_tile_hashes": "|".join(sorted({e["tileSha256"] for e in candidate["sources"]})),
                    "geometry_status": row["geometryStatus"], "fragment_length_m": row["fragmentLengthM"],
                    "covered_fraction": candidate["coveredFraction"], "ambiguity_fraction": row["ambiguityFraction"],
                    "maximum_distance_m": candidate["maximumDistanceM"],
                    "maximum_undirected_heading_degrees": candidate["maximumUndirectedHeadingDegrees"],
                    **candidate["literalAttributes"]})


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--route", type=Path)
    parser.add_argument("--graph-tiles", type=Path, nargs="*")
    parser.add_argument("--verification-out", type=Path)
    parser.add_argument("--enrichment-out", type=Path)
    args = parser.parse_args(argv)
    try:
        result = decode_capture(args.capture)
        lines = road_lines(result)
        evidence = {"schema": "denza-yandex-vector-geometry-verification-v1", "capture": result["metadataSource"],
                    "summary": result["summary"], "roadLines": len(lines)}
        if args.graph_tiles:
            from .yandex_graph_tile import decode_files as decode_graph_files
            graphs = decode_graph_files(args.graph_tiles)["tiles"]
            evidence["graphSources"] = [g["source"] for g in graphs]
            evidence["graphCorpusScope"] = "combined supplied file corpus, not an assertion about current in-memory cache"
            evidence["vectorToGraph"] = compare_polylines(lines, graph_lines(graphs))
            if args.enrichment_out:
                table = enrichment_table(result, graphs)
                table["vectorSources"] = [t["source"] for t in result["tiles"]]
                table["graphSources"] = evidence["graphSources"]
                args.enrichment_out.write_text(json.dumps(table, ensure_ascii=False, allow_nan=False, indent=2) + "\n")
                write_enrichment_csv(table, args.enrichment_out.with_suffix(".csv"))
                evidence["enrichmentSummary"] = table["summary"]
        if args.route:
            raw = args.route.read_bytes()
            if len(raw) > 4 * 1024 * 1024:
                raise GeometryError("route JSON byte budget exceeded")
            route = json.loads(raw)
            if route.get("coordinateFrame") != "wgs84" or route.get("pointOrder") != "lon,lat":
                raise GeometryError("route lacks explicit WGS84 lon,lat frame")
            evidence["routeSource"] = _file_hash(args.route)
            evidence["routeToVector"] = compare_polylines([{"id": "route", "points": route["points"]}], lines)
        args.out.write_text(json.dumps(result, ensure_ascii=False, allow_nan=False, separators=(",", ":")) + "\n")
        if args.verification_out:
            args.verification_out.write_text(json.dumps(evidence, ensure_ascii=False, allow_nan=False, indent=2) + "\n")
        print(json.dumps({key: value for key, value in evidence.items() if key not in ("vectorToGraph", "routeToVector")}, ensure_ascii=False))
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.exit(2, str(error) + "\n")


if __name__ == "__main__":
    main()
