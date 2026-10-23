#!/usr/bin/env python3
"""Offline route conversion through the stock-equivalent oracle; output is never published.

Run from the repository root: python3 -m tools.adas_bridge.bridge --help.
The route itself comes from tools/adas_bridge/stock.py, the byte-for-byte host mirror of
libraries/adas-link: links cut at lights, limit changes and tunnel/ferry boundaries (never at jams),
whole-metre lengths, the stock's own NULL sentinels for attributes Yandex does not give, link ids
from the link's own grid points, Amap-style path ids per process keyed by the link table. This module
adds input validation, the intermediate placement table and the progress mapper with its guards.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
from pathlib import Path

from . import stock


class ContractError(ValueError):
    pass


ROOT = Path(__file__).resolve().parents[2]
FRAMES = stock.FRAMES
RANGES = ("highways", "tunnels", "tollRoads", "hdSections", "standingSegments")
EXTRAS = RANGES + ("laneSigns", "trafficLights", "jamSegments", "events",
                   "speedBumps", "railwayCrossings", "pedestrianCrossings",
                   "directionSigns", "zlevels", "speedLimits", "ferries", "fordCrossings",
                   "ruggedRoads", "zoneCrossings", "checkpoints", "restrictedEntries",
                   "restrictedTurns", "zlevelBreakpoints", "wayPoints", "legIndex", "conditionsOutdated")
# Yandex Action -> Amap MainAction for actions whose assistant action is NULL (stock.actions has all).
MAIN_ACTION = {k: v[0] for k, v in stock._ACTIONS.items() if v[1] == 0}
# Attributes Yandex does not give, sent as the stock's own values for "unknown".
STOCK_UNKNOWN_VALUES = {"roadclass": -1, "formway": -1, "linktype": "0 (common) except tunnel 2 / ferry 1",
                        "ownership": -1, "laneNum": 0, "direction": 0, "adminCode": 0, "mainAction": 0,
                        "assistantAction": 0, "hasMultiOut": False, "hasParallel": False, "hasMixFork": False,
                        "speedLimit": 0, "viaRoadInfo": []}


def finite(value, path):
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value):
        raise ContractError(f"{path}: expected finite number")
    return value


def signed64(value, path="id"):
    if type(value) is not int or not -(1 << 63) <= value < (1 << 63):
        raise ContractError(f"{path}: expected signed 64-bit JSON integer (never float)")
    return value


def stable_id(*parts):
    """Deterministic positive signed64 of JSON parts; stable across processes."""
    encoded = json.dumps(parts, ensure_ascii=False, separators=(",", ":")).encode()
    return int.from_bytes(hashlib.sha256(encoded).digest()[:8], "big") & ((1 << 63) - 1)


def distance(a, b):
    return stock.distance(a[0], a[1], b[0], b[1])


def position(value, edge_count, path):
    if not isinstance(value, dict):
        raise ContractError(f"{path}: expected PolylinePosition")
    index = value.get("segmentIndex")
    fraction = value.get("segmentPosition", value.get("fraction"))
    if type(index) is not int:
        raise ContractError(f"{path}.segmentIndex: expected integer")
    finite(fraction, path + ".segmentPosition")
    if not 0 <= fraction <= 1 or not 0 <= index < edge_count + 1:
        raise ContractError(f"{path}: outside source geometry")
    if index == edge_count and fraction != 0:
        raise ContractError(f"{path}: terminal point requires zero fraction")
    return index + fraction


def known(snapshot, key):
    return (key in snapshot and snapshot[key] is not None
            and snapshot.get("availability", {}).get(key) != "unknown")


def normalize(snapshot):
    """Strict input validation of a denza-yandex-driving-route-v1 snapshot."""
    if not isinstance(snapshot, dict):
        raise ContractError("snapshot: expected object")
    route_id = snapshot.get("routeId")
    if not isinstance(route_id, str) or not route_id:
        raise ContractError("routeId: expected nonempty string")
    if snapshot.get("coordinateFrame", "wgs84") != "wgs84":
        raise ContractError("input must explicitly remain WGS84; refusing second coordinate transform")
    if snapshot.get("pointOrder", "lon,lat") != "lon,lat":
        raise ContractError("pointOrder must be lon,lat")
    points = snapshot.get("points")
    if not isinstance(points, list) or len(points) < 2:
        raise ContractError("points: at least two WGS84 points required")
    for i, point in enumerate(points):
        if not isinstance(point, list) or len(point) != 2:
            raise ContractError(f"points[{i}]: expected [longitude,latitude]")
        lon, lat = (finite(v, f"points[{i}]") for v in point)
        if not -180 <= lon <= 180 or not -90 <= lat <= 90:
            raise ContractError(f"points[{i}]: invalid WGS84 coordinate")
    total = sum(distance(a, b) for a, b in zip(points, points[1:]))
    if total <= 0:
        raise ContractError("route geometry has zero length")
    edge_count = len(points) - 1
    sections_known = False
    if known(snapshot, "sections"):
        if not isinstance(snapshot["sections"], list):
            raise ContractError("sections: expected list")
        previous = 0.0
        for i, raw in enumerate(snapshot["sections"]):
            geometry = raw.get("geometry") or {}
            begin = position(geometry.get("begin"), edge_count, f"sections[{i}].begin")
            end = position(geometry.get("end"), edge_count, f"sections[{i}].end")
            if abs(begin - previous) > 1e-9 or end <= begin:
                raise ContractError(f"sections[{i}]: require ordered, contiguous, positive ranges")
            previous = end
        sections_known = bool(snapshot["sections"])
        if sections_known and abs(previous - edge_count) > 1e-9:
            raise ContractError("sections do not cover route end")
    for key in RANGES + ("ferries",):
        if known(snapshot, key):
            if not isinstance(snapshot[key], list):
                raise ContractError(f"{key}: expected list")
            for i, row in enumerate(snapshot[key]):
                sub = row.get("position", row)
                if position(sub.get("end"), edge_count, f"{key}[{i}].end") <= position(sub.get("begin"), edge_count, f"{key}[{i}].begin"):
                    raise ContractError(f"{key}[{i}]: empty/reversed range")
    if known(snapshot, "speedLimits"):
        limits = snapshot["speedLimits"]
        if not isinstance(limits, list) or len(limits) != edge_count:
            raise ContractError("speedLimits: expected one nullable m/s value per source edge")
        for i, value in enumerate(limits):
            if value is not None and finite(value, f"speedLimits[{i}]") < 0:
                raise ContractError("negative speed limit")
    if known(snapshot, "trafficLights"):
        if not isinstance(snapshot["trafficLights"], list):
            raise ContractError("trafficLights: expected list")
        for i, row in enumerate(snapshot["trafficLights"]):
            position(row.get("position"), edge_count, f"trafficLights[{i}].position")
    if known(snapshot, "jamSegments"):
        jams = snapshot["jamSegments"]
        if not isinstance(jams, list) or len(jams) not in (0, edge_count):
            raise ContractError("jamSegments: expected one item per source edge")
        for i, row in enumerate(jams):
            if row is not None:
                if not isinstance(row, dict):
                    raise ContractError(f"jamSegments[{i}]: expected object or unknown null")
                if row.get("speed") is not None and finite(row["speed"], f"jamSegments[{i}].speed") < 0:
                    raise ContractError("negative jam speed")
    waypoints = None
    if known(snapshot, "wayPoints"):
        waypoints = [position(v, edge_count, f"wayPoints[{i}]") for i, v in enumerate(snapshot["wayPoints"])]
        if any(a > b for a, b in zip(waypoints, waypoints[1:])):
            raise ContractError("wayPoints: expected route order")
    return {"routeId": route_id, "edgeCount": edge_count, "sectionsKnown": sections_known, "waypoints": waypoints}


def route_convert(snapshot, *, target_frame, path_ids=None):
    """Snapshot -> the stock NaviToEHPData (no null anywhere) plus the placement table.

    path_ids: the per-process stock.PathIds; a fresh one (first id 100002) when omitted.
    """
    if target_frame not in FRAMES:
        raise ContractError("target coordinate frame must be selected explicitly")
    checked = normalize(snapshot)
    try:
        route = stock.route_from_snapshot(snapshot)
        converted = stock.convert(route, target_frame)
    except stock.StockError as exc:
        raise ContractError(str(exc)) from exc
    (path_ids or stock.PathIds()).assign(converted)
    payload = stock.message_send(converted["path"], 1)
    data = json.loads(payload)["data"][0]
    sections = route["sections"] or [{"begin": 0.0, "end": float(checked["edgeCount"]), "action": None, "toponym": None}]
    links, index = [], 0
    for si, segment in enumerate(converted["path"]["segments"]):
        for k, link in enumerate(segment["links"]):
            links.append({"index": index, "sectionIndex": si, "linkInSection": k,
                          "sourceBegin": converted["linkBeginScalar"][index], "sourceEnd": converted["linkEndScalar"][index],
                          "distanceFromStartM": converted["linkBeginM"][index],
                          "lengthM": converted["linkEndM"][index] - converted["linkBeginM"][index],
                          "wholeMetres": link["length"], "hasTrafficLight": link["hasTrafficLight"],
                          "linkType": link["linkType"]})
            index += 1
    blockers = ["receiver_coordinate_frame_unverified", "synthetic_link_id_semantics_unverified",
                "single_writer_not_established", "unknown_attributes_sent_as_stock_null_values"]
    if target_frame == "wgs84":
        blockers.append("wgs84_is_geometry_inspection_only_not_stock_frame")
    if not checked["sectionsKnown"]:
        blockers.append("missing_manoeuvre_sections")
    if "coordinateFrame" not in snapshot or "pointOrder" not in snapshot:
        blockers.append("legacy_input_frame_or_point_order_assumed")
    for key in EXTRAS:
        if not known(snapshot, key):
            blockers.append("unknown_yandex_" + key)
    unmapped = sorted({str(s["action"]) for s in sections[1:] if stock.actions(s["action"]) == (0, 0)})
    if unmapped:
        blockers.append("unmapped_route_actions:" + ",".join(unmapped))
    return {"schema": "denza-adas-offline-candidate-v2", "offlineOnly": True,
            "publicationReady": False, "routeId": checked["routeId"],
            "generation": snapshot.get("generation"), "sourceSessionId": snapshot.get("sourceSessionId"),
            "coordinateContract": {"source": "wgs84", "target": target_frame,
                                   "transformApplications": 0 if target_frame == "wgs84" else 1,
                                   "grid": "Coord2DInt32, 1/3600000 degree",
                                   "status": "inspection" if target_frame == "wgs84" else "Moscow-only candidate, receiver datum unverified"},
            "unknownAttributePolicy": STOCK_UNKNOWN_VALUES,
            "exportBlockers": blockers,
            "contentKey": converted["contentKey"],
            "stockGeometry": {key: converted[key] for key in ("totalM", "cumulative", "linkSegment", "linkInSegment",
                                                              "linkBeginM", "linkEndM", "linkBeginScalar", "linkEndScalar",
                                                              "segmentBeginM", "segmentEndM", "lightM", "tunnelM",
                                                              "tunnelEnds", "speedCameras", "contentKey", "routeId")},
            "stockPath": converted["path"],
            "messageSendJson": payload,
            "intermediate": {"sourcePointCount": checked["edgeCount"] + 1, "lengthM": converted["totalM"],
                             "sourceCumulativeDistanceM": converted["cumulative"],
                             "sourcePointsWgs84": [list(p) for p in snapshot["points"]],
                             "sectionsKnown": checked["sectionsKnown"], "sections": sections, "links": links,
                             "availability": {key: snapshot.get("availability", {}).get(key, "known" if known(snapshot, key) else "unknown")
                                              for key in ("sections",) + EXTRAS},
                             "sourceIssues": snapshot.get("issues", {})},
            "candidateNaviToEHPData": data}


def _geometry(converted):
    g = dict(converted["stockGeometry"])
    g["path"] = converted["stockPath"]
    return g


def location_age(sample, now_ms, timestamp):
    """CAP-1: the age of the last FINE fix when exported, else the observed stamp age."""
    if "lastFineFixAgeMs" in sample:
        age = sample.get("lastFineFixAgeMs")
    else:
        age = sample.get("fixAgeMs")
    if type(age) is not int or age < 0:
        return None
    return age + now_ms - timestamp


def map_progress(converted, sample, *, now_ms, max_age_ms=2000, clock="wall", max_fix_age_ms=1000):
    """Place one guidance sample on the published link table, with the freshness/ownership guards."""
    result = {"accepted": False, "publicationReady": False}
    native = sample.get("schema") == "denza-yandex-guidance-v1"
    if clock not in ("wall", "uptime"):
        raise ContractError("clock must be wall or uptime")
    if converted.get("sourceSessionId") != sample.get("sourceSessionId"):
        return dict(result, reason="source_session_mismatch")
    if sample.get("routeId") != converted["routeId"]:
        return dict(result, reason="route_mismatch")
    if not native and sample.get("generation") != converted.get("generation"):
        return dict(result, reason="route_or_generation_mismatch")
    timestamp = sample.get("capturedUptimeMs" if clock == "uptime" else "capturedAtMs")
    expiry = timestamp + max_age_ms if native and type(timestamp) is int else sample.get("expiresAtMs")
    if type(timestamp) is not int or type(expiry) is not int:
        return dict(result, reason="missing_clock_or_expiry")
    if timestamp > now_ms or expiry <= now_ms or now_ms - timestamp > max_age_ms or expiry <= timestamp:
        return dict(result, reason="stale_or_invalid_time")
    if native:
        if "locationClass" in sample and sample.get("locationClass") != "FINE":
            return dict(result, reason="not_a_fine_fix")
        active = sample.get("mode") == "guidance" and sample.get("freshLocation") is True
    else:
        active = sample.get("status") == "guidance" and sample.get("matched") is True
    if not active:
        return dict(result, reason="not_matched_guidance")
    if native:
        age = location_age(sample, now_ms, timestamp)
        if age is None or age > max_fix_age_ms:
            return dict(result, reason="stale_or_unknown_location")
        if sample.get("coordinateFrame") != "wgs84":
            return dict(result, reason="unexpected_location_frame")
        owner_known = "positionRouteId" in sample or "positionMatchesRoute" in sample
        if owner_known or sample.get("sourceSessionId") is not None:
            if sample.get("positionRouteId") != sample.get("routeId") or sample.get("positionMatchesRoute") is not True:
                return dict(result, reason="position_route_owner_unknown_or_mismatch")
    middle = converted["intermediate"]
    edges = middle["sourcePointCount"] - 1
    scalar = position(sample.get("sourcePolylinePosition" if native else "position"), edges, "progress.position")
    geometry = _geometry(converted)
    passed = stock.metres(geometry["cumulative"], scalar)
    info = stock.navi_info(geometry, passed)
    wgs = _wgs_at(middle, scalar)
    pose_check = None
    if native:
        location = sample.get("location")
        if not isinstance(location, dict):
            return dict(result, reason="missing_native_location")
        lon, lat, accuracy = location.get("longitude"), location.get("latitude"), location.get("accuracy")
        if (any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)
                for value in (lon, lat, accuracy)) or not -180 <= lon <= 180 or not -90 <= lat <= 90 or not 0 <= accuracy <= 100):
            return dict(result, reason="invalid_or_unknown_location_accuracy")
        stamped = False
        for key, capture_key in (("absoluteTimestamp", "capturedAtMs"), ("relativeTimestamp", "capturedUptimeMs")):
            value, capture = location.get(key), sample.get(capture_key)
            if value is None:
                continue
            if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or type(capture) is not int:
                return dict(result, reason="invalid_native_location_time")
            fix_age = capture - value + now_ms - timestamp
            if fix_age < -250 or fix_age > max_fix_age_ms:
                return dict(result, reason="stale_or_future_native_location_time")
            stamped = True
        if not stamped:
            return dict(result, reason="unknown_native_location_time")
        gap = distance([lon, lat], wgs)
        tolerance = min(100, max(25, accuracy + 15))
        # F6: Yandex's classified location is already snapped to the route; this is a consistency
        # check of two Yandex outputs, never an independent localization guard.
        pose_check = {"distanceFromSourceGeometryM": gap, "diagnosticToleranceM": tolerance,
                      "policy": "consistency of Yandex location and route position; not an independence guard"}
        if gap > tolerance:
            return dict(result, reason="location_source_geometry_mismatch", poseCompatibility=pose_check)
    try:
        sdk = stock.to_sdk(wgs[0], wgs[1], converted["coordinateContract"]["target"])
    except stock.StockError as exc:
        raise ContractError(str(exc)) from exc
    return dict(result, accepted=True, reason="offline_geometry_only", navigationStatus=101, clock=clock,
                poseCompatibility=pose_check, passedM=passed,
                curSDRouteID=converted["candidateNaviToEHPData"]["pathID"], curStepId=info["segIdx"],
                curLinkId=info["linkIdx"], globalLinkIndex=info["globalLink"],
                linkOffset=info["linkRemain"], distanceToNextStep=info["segRemain"],
                distanceToDestinationM=info["routeRemain"], remainLights=info["remainLights"],
                matchedWgs84=list(wgs), matchedTarget=[stock.grid(sdk[0]), stock.grid(sdk[1])],
                currentRoadOwnership=stock.OWNERSHIP_UNKNOWN, roadClass=stock.ROAD_CLASS_NULL)


def _wgs_at(middle, scalar):
    snapshot_points = middle["sourcePointsWgs84"]
    edges = len(snapshot_points) - 1
    e = min(int(math.floor(scalar)), edges - 1)
    f = scalar - e
    a, b = snapshot_points[e], snapshot_points[e + 1]
    return (a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1]))


def validate_payload(payload):
    """Validate structural invariants of a NaviToEHPData payload (stock or ours)."""
    errors = []
    if not isinstance(payload, dict) or not isinstance(payload.get("data"), list):
        return ["MessageSend.data must be a list"]
    for ri, route in enumerate(payload["data"]):
        prefix = f"data[{ri}]"
        try:
            signed64(route.get("pathID"), prefix + ".pathID")
        except ContractError as exc:
            errors.append(str(exc))
        links, points = route.get("links"), route.get("pnts")
        if not isinstance(links, list) or not isinstance(points, list):
            errors.append(prefix + ": missing links/pnts arrays")
            continue
        if route.get("linkCnt") != len(links) or route.get("pntCnt") != len(points):
            errors.append(prefix + ": declared counts do not match arrays")
        for pi, point in enumerate(points):
            if (not isinstance(point, dict) or
                    any(isinstance(point.get(key), bool) or not isinstance(point.get(key), (int, float)) or
                        not math.isfinite(point[key]) for key in ("x", "y"))):
                errors.append(f"{prefix}.pnts[{pi}]: expected finite x/y coordinates")
            elif not -180 <= point["x"] <= 180 or not -90 <= point["y"] <= 90:
                errors.append(f"{prefix}.pnts[{pi}]: coordinates outside geographic bounds")
        next_point = 0
        for li, link in enumerate(links):
            for key, value in link.items():
                if value is None:
                    errors.append(f"{prefix}.links[{li}].{key}: null in a primitive slot")
            length = link.get("len")
            if isinstance(length, bool) or not isinstance(length, (int, float)) or not math.isfinite(length) or length < 0:
                errors.append(f"{prefix}.links[{li}]: invalid length")
            try:
                signed64(link.get("linkID"), f"{prefix}.links[{li}].linkID")
            except ContractError as exc:
                errors.append(str(exc))
            start, count = link.get("pntBegIdx"), link.get("pntCnt")
            if type(start) is not int or type(count) is not int or start != next_point or count < 2 or start + count > len(points):
                errors.append(f"{prefix}.links[{li}]: invalid sequential point slice")
                continue
            next_point = start + count
        if next_point != len(points):
            errors.append(prefix + ": points not consumed by links")
        covered = 0
        for group in route.get("guideGroups") or []:
            lights = 0
            for segment in group.get("segments") or []:
                start, count = segment.get("linkBegIdx"), segment.get("crntSegmLinkCnt")
                if type(start) is not int or type(count) is not int or start != covered or count <= 0:
                    errors.append(prefix + ": invalid segment link coverage")
                    continue
                for key in ("navigationLen", "trafficLightNum"):
                    if type(segment.get(key)) is not int:
                        errors.append(f"{prefix}: segment {key} must be a JSON integer")
                if type(segment.get("navigationLen")) is int and links[start:start + count] and \
                        sum(int(l["len"]) for l in links[start:start + count]) != segment["navigationLen"]:
                    errors.append(prefix + ": sum(link len) != navigationLen")
                lights += segment.get("trafficLightNum") or 0
                covered += count
            for key in ("groupLen", "groupTime", "groupTrafficLightsCount"):
                if type(group.get(key)) is not int:
                    errors.append(f"{prefix}: {key} must be a JSON integer")
            if group.get("groupTrafficLightsCount") != lights:
                errors.append(prefix + ": groupTrafficLightsCount != sum(segment trafficLightNum)")
        if covered != len(links):
            errors.append(prefix + ": segments do not consume links")
    return errors


def recursive_diff(left, right, path="$", *, limit=200):
    differences = []
    def walk(a, b, where):
        if len(differences) >= limit:
            return
        if type(a) is not type(b):
            differences.append({"path": where, "left": a, "right": b, "reason": "type"})
        elif isinstance(a, dict):
            for key in sorted(a.keys() | b.keys()):
                if key not in a or key not in b:
                    differences.append({"path": where + "." + key, "reason": "missing_left" if key not in a else "missing_right"})
                else:
                    walk(a[key], b[key], where + "." + key)
                if len(differences) >= limit:
                    break
        elif isinstance(a, list):
            if len(a) != len(b):
                differences.append({"path": where, "leftCount": len(a), "rightCount": len(b), "reason": "length"})
            for i, (aa, bb) in enumerate(zip(a, b)):
                walk(aa, bb, f"{where}[{i}]")
        elif a != b:
            differences.append({"path": where, "left": a, "right": b, "reason": "value"})
    walk(left, right, path)
    return differences


def read_json(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    convert = sub.add_parser("convert")
    convert.add_argument("snapshot")
    convert.add_argument("--target-frame", choices=FRAMES, required=True)
    convert.add_argument("--out", required=True)
    convert.add_argument("--generation", type=int, help="bind an observed guidance generation to this route archive")
    convert.add_argument("--source-session", help="bind the observed source process session ID")
    validate = sub.add_parser("validate")
    validate.add_argument("payload")
    diff = sub.add_parser("diff")
    diff.add_argument("left")
    diff.add_argument("right")
    progress = sub.add_parser("progress")
    progress.add_argument("converted")
    progress.add_argument("sample")
    progress.add_argument("--now-ms", required=True, type=int)
    progress.add_argument("--clock", choices=("wall", "uptime"), default="wall")
    args = parser.parse_args()
    try:
        if args.command == "convert":
            snapshot = read_json(args.snapshot)
            if args.generation is not None:
                snapshot["generation"] = args.generation
            if args.source_session is not None:
                snapshot["sourceSessionId"] = args.source_session
            result = route_convert(snapshot, target_frame=args.target_frame)
            Path(args.out).parent.mkdir(parents=True, exist_ok=True)
            Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n")
            print(json.dumps({"out": args.out, "links": result["candidateNaviToEHPData"]["linkCnt"],
                              "pathID": result["candidateNaviToEHPData"]["pathID"], "publicationReady": False,
                              "blockers": result["exportBlockers"]}))
        elif args.command == "validate":
            errors = validate_payload(read_json(args.payload))
            print(json.dumps({"valid": not errors, "errors": errors}, indent=2))
            return bool(errors)
        elif args.command == "diff":
            differences = recursive_diff(read_json(args.left), read_json(args.right))
            print(json.dumps({"comparison": "structural only; same corridor is not established", "differences": differences},
                             ensure_ascii=False, indent=2))
            return bool(differences)
        elif args.command == "progress":
            print(json.dumps(map_progress(read_json(args.converted), read_json(args.sample), now_ms=args.now_ms, clock=args.clock), indent=2))
    except (ContractError, ValueError, KeyError, TypeError) as exc:
        parser.exit(2, f"invalid input: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
