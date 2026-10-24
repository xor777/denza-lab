"""Decode saved Yandex lookupEdge observations, entirely on the host.

Confirmed layout only: result = two graph-local vertex indices, EdgeData pointer,
optional-engaged byte at 24. EdgeData is 0x60 bytes: ID at 0, SD start at 8,
geometry pointer at 16. The geometry begins with vector begin/end pointers and
contains little-endian double (latitude, longitude) pairs. Captured bytes beyond
EdgeData+0x60 are explicitly outside the object, never attributes of this edge.

    python3 -m tools.adas_bridge.yandex_graph_capture capture.jsonl --out graph.json \
        --route denza-hud-native-route.json

The optional route association is a geometric hypothesis on the captured subset,
with distance/direction gates and explicit ambiguity. It is not an ADAS feed.
"""
from __future__ import annotations

import argparse
import bisect
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import struct


SCHEMA = "denza-yandex-graph-capture-v1"
EDGE_SIZE = 0x60
RADIUS = 6371008.8


def _hex(value, name, minimum=0):
    if not isinstance(value, str):
        raise ValueError(name + " is missing")
    try:
        raw = bytes.fromhex(value)
    except ValueError as error:
        raise ValueError(name + " is not hexadecimal") from error
    if len(raw) < minimum:
        raise ValueError(name + " is truncated")
    return raw


def _pointer(value):
    try:
        number = int(value, 16) if isinstance(value, str) else int(value)
    except (TypeError, ValueError) as error:
        raise ValueError("invalid pointer") from error
    if not 0 < number <= 0xFFFFFFFFFFFFFFFF:
        raise ValueError("invalid pointer bounds")
    return number


def _distance(a, b):
    lon1, lat1, lon2, lat2 = map(math.radians, (*a, *b))
    h = math.sin((lat2 - lat1) / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    return 2 * RADIUS * math.asin(min(1, math.sqrt(h)))


def _decode(row):
    result = _hex(row.get("resultBytes"), "resultBytes", 25)
    engaged = result[24]
    if row.get("engaged", engaged) != engaged:
        raise ValueError("engaged field disagrees with result byte 24")
    if engaged not in (0, 1):
        raise ValueError("invalid optional engagement byte")
    if not engaged:
        return None
    vertex0, vertex1, edge_pointer = struct.unpack_from("<QQQ", result)
    if not edge_pointer:
        raise ValueError("engaged lookup has null EdgeData pointer")
    if "edgePointer" in row and _pointer(row["edgePointer"]) != edge_pointer:
        raise ValueError("EdgeData pointer mismatch")
    header = _hex(row.get("edgeHeader"), "edgeHeader", EDGE_SIZE)
    edge_id, sd_start = struct.unpack_from("<QI", header)
    geometry_pointer = struct.unpack_from("<Q", header, 16)[0]
    if not geometry_pointer:
        raise ValueError("null geometry pointer")
    if "geometryPointer" in row and _pointer(row["geometryPointer"]) != geometry_pointer:
        raise ValueError("geometry pointer mismatch")
    geometry_header = _hex(row.get("geometryHeader"), "geometryHeader", 16)
    begin, end = struct.unpack_from("<QQ", geometry_header)
    if not begin or end < begin or (end - begin) % 16:
        raise ValueError("invalid geometry vector begin/end")
    expected = end - begin
    if row.get("geometrySizeBytes", expected) != expected:
        raise ValueError("geometry byte count disagrees with vector extent")
    captured = _hex(row.get("geometryBytes", ""), "geometryBytes")
    if len(captured) > expected or len(captured) > 2048:
        raise ValueError("captured geometry exceeds vector extent or capture cap")
    points = []
    for offset in range(0, len(captured) - 15, 16):
        lat, lon = struct.unpack_from("<dd", captured, offset)
        if not math.isfinite(lat) or not math.isfinite(lon) or abs(lat) > 90 or abs(lon) > 180:
            raise ValueError("geometry contains invalid WGS84 coordinate")
        points.append([lon, lat])
    complete = len(captured) == expected and len(captured) % 16 == 0
    requested_edge = row.get("edgeIdHex")
    return {"edgeIdHex": hex(edge_id), "sdStart": sd_start, "vertexIndices": [vertex0, vertex1],
            "vertexIndicesHex": [hex(vertex0), hex(vertex1)],
            "geometry": {"coordinateFrame": "wgs84", "pointOrder": "lon,lat", "points": points,
                "expectedBytes": expected, "capturedBytes": len(captured), "complete": complete,
                "partialPairBytes": len(captured) % 16,
                "capturedLengthM": round(sum(_distance(a, b) for a, b in zip(points, points[1:])), 6),
                "scope": "full_stored_polyline" if complete else "captured_prefix_only"},
            "raw": {"edgeDataHex": header[:EDGE_SIZE].hex(),
                "lookupResultHex": result.hex(),
                "unnamedAttributes0x50Through0x5bHex": header[0x50:0x5c].hex(),
                "outsideObjectTrailingHex": header[EDGE_SIZE:].hex(),
                "outsideObjectTrailingBytes": len(header) - EDGE_SIZE,
                "geometryHeaderHex": geometry_header.hex(),
                "warning": "Bytes at EdgeData+0x60 and above are outside this 96-byte object. Unknown attributes are not decoded."},
            "lookup": {"number": row.get("number"), "requestedEdgeIdHex": requested_edge,
                "requestedSegmentIndexHex": row.get("segmentIndexHex"),
                "requestedEdgeMatchesData": int(requested_edge, 16) == edge_id if requested_edge else None,
                "edgePointer": hex(edge_pointer), "geometryPointer": hex(geometry_pointer)}}


def parse_capture(raw, *, source="in-memory"):
    """Decode one immutable byte snapshot of a JSONL capture; bad rows stay issues."""
    raw = raw.encode() if isinstance(raw, str) else raw
    digest = hashlib.sha256(raw).hexdigest()
    frames, issues, kinds = [], [], Counter()
    epoch, frame_number, current, frame = 0, 0, None, None
    line_count, lookups, absent, duplicates = 0, 0, 0, 0
    for line_number, line in enumerate(raw.splitlines(), 1):
        line_count = line_number
        if not line.strip():
            continue
        try:
            message = json.loads(line)
            row = message.get("payload", message)
            if not isinstance(row, dict):
                raise ValueError("record payload is not an object")
            kind = row.get("kind", message.get("type", "unknown"))
            kinds[kind] += 1
            if kind == "observer_start":
                epoch += 1
                current = None
                continue
            if kind != "lookupEdge":
                continue
            lookups += 1
            graph = hex(_pointer(row.get("graph")))
            if graph != current:
                frame_number += 1
                current = graph
                frame = {"id": f"{digest[:16]}:observer{epoch}:frame{frame_number}:{graph}",
                         "observerEpoch": epoch, "graphPointer": graph, "firstSourceLine": line_number,
                         "lastSourceLine": line_number, "graphHeaderHexVariants": [], "_edges": {}}
                frames.append(frame)
            frame["lastSourceLine"] = line_number
            graph_header = row.get("graphHeader")
            if graph_header is not None:
                _hex(graph_header, "graphHeader")
                if graph_header not in frame["graphHeaderHexVariants"]:
                    frame["graphHeaderHexVariants"].append(graph_header)
            decoded = _decode(row)
            if decoded is None:
                absent += 1
                continue
            key = (decoded["edgeIdHex"], decoded["sdStart"])
            geometry_signature = (decoded["vertexIndices"], decoded["geometry"])
            observation = {"sourceLine": line_number, **decoded.pop("lookup")}
            if key not in frame["_edges"]:
                decoded.update({"graphFrameId": frame["id"], "id": f"{frame['id']}/edge{key[0]}/sd{key[1]}",
                    "observations": [observation], "rawVariants": [decoded.pop("raw")],
                    "geometryTopologyVariants": [], "consistency": "consistent"})
                frame["_edges"][key] = decoded
            else:
                duplicates += 1
                edge = frame["_edges"][key]
                edge["observations"].append(observation)
                if decoded["raw"] not in edge["rawVariants"]:
                    edge["rawVariants"].append(decoded["raw"])
                if (edge["vertexIndices"], edge["geometry"]) != geometry_signature:
                    variant = {"vertexIndices": decoded["vertexIndices"], "geometry": decoded["geometry"]}
                    if variant not in edge["geometryTopologyVariants"]:
                        edge["geometryTopologyVariants"].append(variant)
                    edge["consistency"] = "conflicting_geometry_or_topology"
        except (ValueError, TypeError, KeyError, AttributeError, struct.error) as error:
            issues.append({"sourceLine": line_number, "reason": str(error),
                           "incompleteTailPossible": line_number == len(raw.splitlines()) and not raw.endswith(b"\n")})
    for frame in frames:
        edges = list(frame.pop("_edges").values())
        vertices = {}
        for edge in edges:
            if edge["consistency"] != "consistent":
                continue
            for endpoint, index in enumerate(edge["vertexIndices"]):
                vertex = vertices.setdefault(index, {"index": index, "id": frame["id"] + f"/vertex{index}",
                    "incidentEdgeIds": [], "storedEndpoint0EdgeIds": [], "storedEndpoint1EdgeIds": [],
                    "coordinateCandidates": []})
                if edge["id"] not in vertex["incidentEdgeIds"]:
                    vertex["incidentEdgeIds"].append(edge["id"])
                vertex[f"storedEndpoint{endpoint}EdgeIds"].append(edge["id"])
                points = edge["geometry"]["points"]
                if points and (endpoint == 0 or edge["geometry"]["complete"]):
                    coordinate = points[0] if endpoint == 0 else points[-1]
                    if coordinate not in vertex["coordinateCandidates"]:
                        vertex["coordinateCandidates"].append(coordinate)
        for vertex in vertices.values():
            candidates = vertex["coordinateCandidates"]
            vertex["coordinateSpreadM"] = round(max((_distance(a, b) for a in candidates for b in candidates), default=0), 6)
        frame["edges"], frame["vertices"] = edges, list(vertices.values())
    all_edges = [edge for frame in frames for edge in frame["edges"]]
    return {"schema": SCHEMA, "purpose": "host_only_captured_yandex_graph_subset",
            "source": {"path": source, "sha256": digest, "bytes": len(raw), "lines": line_count},
            "identityContract": "Vertex indices and edge identities are local to each capture/observer epoch/contiguous graph-pointer frame. Pointer reuse without an observed switch cannot be excluded; frames are never silently merged.",
            "topologyContract": "Only observed edges and endpoint indices. Missing reverse edges or adjacency do not establish one-way roads or true dead ends. Stored geometry order is not a decoded legal-access attribute.",
            "knownLayout": {"resultBytes": "uint64 endpoint0@0, endpoint1@8, EdgeData*@16; engaged byte@24",
                "edgeDataSize": EDGE_SIZE, "edgeId": "uint64@0", "sdStart": "uint32@8",
                "geometryPointer": "uint64@16", "geometryVector": "begin/end pointers@0/8; little-endian float64(lat,lon) pairs",
                "unnamedAttributeBytes": "0x50..0x5b; retained raw only"},
            "frames": frames, "issues": issues,
            "summary": {"recordKinds": dict(kinds), "lookupRecords": lookups, "disengagedLookups": absent,
                "graphFrames": len(frames), "uniqueEdges": len(all_edges), "duplicateObservations": duplicates,
                "vertices": sum(len(frame["vertices"]) for frame in frames),
                "completeGeometryEdges": sum(e["geometry"]["complete"] for e in all_edges),
                "conflictingEdges": sum(e["consistency"] != "consistent" for e in all_edges),
                "outOfObjectTrailingBytesObserved": sum(v["outsideObjectTrailingBytes"] for e in all_edges for v in e["rawVariants"]),
                "issueCount": len(issues)}}


def _bearing(a, b):
    x1, y1, x2, y2 = map(math.radians, (*a, *b))
    return math.degrees(math.atan2(math.sin(x2 - x1) * math.cos(y2),
        math.cos(y1) * math.sin(y2) - math.sin(y1) * math.cos(y2) * math.cos(x2 - x1))) % 360


def _project(point, a, b):
    # Local tangent plane is used only for the distance gate on a short stored edge.
    scale = math.pi / 180 * RADIUS
    cosine = math.cos(math.radians(point[1]))
    ax, ay = ((a[0] - point[0] + 180) % 360 - 180) * scale * cosine, (a[1] - point[1]) * scale
    bx, by = ((b[0] - point[0] + 180) % 360 - 180) * scale * cosine, (b[1] - point[1]) * scale
    dx, dy = bx - ax, by - ay
    denominator = dx * dx + dy * dy
    fraction = max(0, min(1, -(ax * dx + ay * dy) / denominator)) if denominator else 0
    return math.hypot(ax + fraction * dx, ay + fraction * dy), fraction


def associate_route(graph, route, *, maximum_distance_m=12, maximum_heading_degrees=35,
                    ambiguity_distance_m=3, sample_step_m=20):
    """Conservative sampled association; nearby alternatives cause abstention.

    Results describe the captured subset, not full road-network coverage or class
    accuracy. No candidate is fabricated when geometry/direction gates fail.
    """
    from tools.adas_bridge.yandex_inference import _Snapshot

    s = _Snapshot(route)
    for value in (maximum_distance_m, maximum_heading_degrees, ambiguity_distance_m, sample_step_m):
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value <= 0:
            raise ValueError("association bounds must be positive and finite")
    if maximum_heading_degrees >= 90:
        raise ValueError("heading gate must distinguish opposite directions")
    lines = []
    for frame in graph["frames"]:
        for edge in frame["edges"]:
            if edge["consistency"] != "consistent" or not edge["geometry"]["complete"]:
                continue
            points = edge["geometry"]["points"]
            for a, b in zip(points, points[1:]):
                if _distance(a, b) > .01:
                    lines.append((edge["id"], a, b, _bearing(a, b)))
    total = s.cumulative[-1]
    points = route["points"]
    bins, coverage = [], Counter()
    count = max(1, math.ceil(total / sample_step_m))
    for i in range(count):
        begin, end = i * total / count, (i + 1) * total / count
        distance = (begin + end) / 2
        index = min(bisect.bisect_right(s.cumulative, distance) - 1, s.edges - 1)
        a, b = points[index], points[index + 1]
        span = s.cumulative[index + 1] - s.cumulative[index]
        fraction = (distance - s.cumulative[index]) / span if span else 0
        point = [(a[0] + fraction * ((b[0] - a[0] + 180) % 360 - 180) + 180) % 360 - 180,
                 a[1] + fraction * (b[1] - a[1])]
        heading = _bearing(a, b)
        candidates, nearby = {}, False
        for edge_id, start, finish, edge_heading in lines:
            gap, position = _project(point, start, finish)
            if gap > maximum_distance_m:
                continue
            nearby = True
            angle = abs((edge_heading - heading + 180) % 360 - 180)
            if angle > maximum_heading_degrees:
                continue
            candidate = {"edgeId": edge_id, "distanceM": round(gap, 6), "headingDifferenceDegrees": round(angle, 6),
                         "atStoredSegmentEndpoint": position < .001 or position > .999}
            if edge_id not in candidates or gap < candidates[edge_id]["distanceM"]:
                candidates[edge_id] = candidate
        candidates = sorted(candidates.values(), key=lambda c: (c["distanceM"], c["edgeId"]))
        plausible = [c for c in candidates if c["distanceM"] <= candidates[0]["distanceM"] + ambiguity_distance_m] if candidates else []
        if not plausible:
            status = "direction_rejected" if nearby else "no_captured_candidate"
        elif len(plausible) > 1:
            status = "ambiguous"
        elif plausible[0]["atStoredSegmentEndpoint"]:
            status = "endpoint_only_candidate"
        else:
            status = "unique_geometric_candidate"
        coverage[status] += end - begin
        bins.append({"beginM": round(begin, 6), "endM": round(end, 6), "sampleM": round(distance, 6),
                     "samplePoint": point, "routeHeadingDegrees": round(heading, 6), "status": status,
                     "plausibleCandidates": plausible, "gatedCandidateCount": len(candidates)})
    return {"status": "estimated_geometric_association_only", "sourceGraphSha256": graph["source"]["sha256"],
            "routeId": route.get("routeId"), "routeTotalM": round(total, 6),
            "bounds": {"maximumDistanceM": maximum_distance_m, "maximumHeadingDegrees": maximum_heading_degrees,
                       "ambiguityDistanceM": ambiguity_distance_m, "sampleStepM": sample_step_m},
            "limitation": "Coverage weights midpoint samples by bin length; it is an approximation, not continuous map matching. The captured graph subset may omit competing roads. Candidate uniqueness never proves correct road identity, legal direction, class or lanes. Incomplete/conflicting edges are excluded; no nearest-road fallback.",
            "sampledCoverage": {key: {"metres": round(value, 3), "percent": round(value / total * 100, 3)} for key, value in sorted(coverage.items())},
            "samples": bins}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--route", type=Path)
    args = parser.parse_args(argv)
    if args.out.resolve() == args.capture.resolve() or (args.route and args.out.resolve() == args.route.resolve()):
        raise ValueError("output must not replace an input capture or route")
    captured_raw = args.capture.read_bytes()
    graph = parse_capture(captured_raw, source=str(args.capture))
    if args.route:
        raw = args.route.read_bytes()
        graph["routeAssociation"] = associate_route(graph, json.loads(raw))
        graph["routeAssociation"]["routeSource"] = {"path": str(args.route), "sha256": hashlib.sha256(raw).hexdigest()}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    frozen = args.out.with_suffix(".source.jsonl")
    frozen.write_bytes(captured_raw)
    graph["source"]["frozenCopyPath"] = str(frozen)
    args.out.write_text(json.dumps(graph, indent=2, ensure_ascii=False, allow_nan=False) + "\n")
    print(json.dumps({"out": str(args.out), **graph["summary"],
                     "sampledCoverage": graph.get("routeAssociation", {}).get("sampledCoverage")}, indent=2))


if __name__ == "__main__":
    main()
