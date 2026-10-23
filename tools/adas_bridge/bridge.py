#!/usr/bin/env python3
"""Offline route conversion with explicit unknowns; output is never publishable.

Run from the repository root: python3 -m tools.adas_bridge.bridge --help.
The candidate JSON intentionally contains nulls for unavailable SDK attributes.
It is an inspectable approximation, not an ADAS eligibility or safety contract.
"""
from __future__ import annotations

import argparse
import bisect
import copy
import functools
import hashlib
import importlib.util
import json
import math
from pathlib import Path


class ContractError(ValueError):
    pass


ROOT = Path(__file__).resolve().parents[2]
FRAMES = ("wgs84", "native-gcj-unrestricted-candidate")
RANGES = ("highways", "tunnels", "tollRoads", "hdSections", "standingSegments")
EXTRAS = RANGES + ("laneSigns", "trafficLights", "jamSegments", "events",
                   "speedBumps", "railwayCrossings", "pedestrianCrossings",
                   "directionSigns", "zlevels", "speedLimits", "ferries", "fordCrossings",
                   "ruggedRoads", "zoneCrossings", "checkpoints", "restrictedEntries",
                   "restrictedTurns", "zlevelBreakpoints", "wayPoints", "legIndex", "conditionsOutdated")
# Stock MainAction.java. These are route actions, NOT HUD field-28 icons.
# Deliberately no guesses for FINISH/EXIT_LEFT/EXIT_RIGHT/WAYPOINT/UNKNOWN.
MAIN_ACTION = {
    "STRAIGHT": 8, "LEFT": 1, "RIGHT": 2,
    "SLIGHT_LEFT": 3, "SLIGHT_RIGHT": 4, "HARD_LEFT": 5, "HARD_RIGHT": 6,
    "UTURN_LEFT": 7, "UTURN_RIGHT": 7,
    "ENTER_ROUNDABOUT": 11, "LEAVE_ROUNDABOUT": 12,
}


def finite(value, path):
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value):
        raise ContractError(f"{path}: expected finite number")
    return value


def signed64(value, path="id"):
    if type(value) is not int or not -(1 << 63) <= value < (1 << 63):
        raise ContractError(f"{path}: expected signed 64-bit JSON integer (never float)")
    return value


def stable_id(*parts):
    """Synthetic positive signed64; stable across Python processes and reruns."""
    encoded = json.dumps(parts, ensure_ascii=False, separators=(",", ":")).encode()
    return int.from_bytes(hashlib.sha256(encoded).digest()[:8], "big") & ((1 << 63) - 1)


def stable_path_id(route_id):
    # sdVehicleLocation.curSDRouteID is protobuf DOUBLE, although selection and
    # route JSON use int64. Keep synthetic route IDs exact in all three forms.
    return (stable_id("yandex-route-v1", route_id) & ((1 << 53)-1)) or 1


def distance(a, b):
    p, q = math.radians(a[1]), math.radians(b[1])
    d = math.sin((p-q)/2)**2 + math.cos(p)*math.cos(q)*math.sin(math.radians(a[0]-b[0])/2)**2
    return 2 * 6371008.8 * math.asin(min(1, math.sqrt(d)))


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


def unpack_position(scalar, edge_count):
    if scalar == edge_count:
        return {"segmentIndex": edge_count - 1, "segmentPosition": 1.0}
    return {"segmentIndex": int(scalar), "segmentPosition": scalar - int(scalar)}


def known(snapshot, key):
    return (key in snapshot and snapshot[key] is not None
            and snapshot.get("availability", {}).get(key) != "unknown")


def point_at(points, scalar):
    edge = min(int(scalar), len(points)-2)
    part = scalar - edge
    return [points[edge][i] + part*(points[edge+1][i]-points[edge][i]) for i in (0, 1)]


def length_at(cumulative, scalar):
    edge = min(int(scalar), len(cumulative)-2)
    return cumulative[edge] + (scalar-edge)*(cumulative[edge+1]-cumulative[edge])


@functools.lru_cache(maxsize=1)
def coordinate_module():
    spec = importlib.util.spec_from_file_location("adas_routing_coordinates", ROOT / "research/stock-map-native/routing_coordinates.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def transform(point, target_frame):
    if target_frame == "wgs84":
        return list(point)
    # Reuse the bounded, documented corpus-derived Moscow candidate, once.
    try:
        return list(coordinate_module().wgs84_to_sdk(*point, frame=target_frame))
    except ValueError as exc:
        raise ContractError(str(exc)) from exc


def normalize(snapshot):
    if not isinstance(snapshot, dict):
        raise ContractError("snapshot: expected object")
    route_id = snapshot.get("routeId")
    if not isinstance(route_id, str) or not route_id:
        raise ContractError("routeId: expected nonempty string")
    if snapshot.get("coordinateFrame", "wgs84") != "wgs84":
        raise ContractError("input must explicitly remain WGS84; refusing second coordinate transform")
    if snapshot.get("pointOrder", "lon,lat") != "lon,lat":
        raise ContractError("pointOrder must be lon,lat")
    points = copy.deepcopy(snapshot.get("points"))
    if not isinstance(points, list) or len(points) < 2:
        raise ContractError("points: at least two WGS84 points required")
    for i, point in enumerate(points):
        if not isinstance(point, list) or len(point) != 2:
            raise ContractError(f"points[{i}]: expected [longitude,latitude]")
        lon, lat = (finite(v, f"points[{i}]") for v in point)
        if not -180 <= lon <= 180 or not -90 <= lat <= 90:
            raise ContractError(f"points[{i}]: invalid WGS84 coordinate")
    cumulative = [0.0]
    for a, b in zip(points, points[1:]):
        cumulative.append(cumulative[-1] + distance(a, b))
    if cumulative[-1] <= 0:
        raise ContractError("route geometry has zero length")
    edge_count = len(points)-1
    cuts = {0.0, float(edge_count)}
    sections = []
    if known(snapshot, "sections"):
        if not isinstance(snapshot["sections"], list):
            raise ContractError("sections: expected list")
        previous = 0.0
        for i, raw in enumerate(snapshot["sections"]):
            geometry = raw.get("geometry") or {}
            begin = position(geometry.get("begin"), edge_count, f"sections[{i}].begin")
            end = position(geometry.get("end"), edge_count, f"sections[{i}].end")
            if abs(begin-previous) > 1e-9 or end <= begin:
                raise ContractError(f"sections[{i}]: require ordered, contiguous, positive ranges")
            sections.append({"begin": begin, "end": end, "metadata": raw.get("metadata")})
            cuts.update((begin, end))
            previous = end
        if sections and abs(previous-edge_count) > 1e-9:
            raise ContractError("sections do not cover route end")
    sections_known = bool(sections)
    if not sections:
        sections = [{"begin": 0.0, "end": float(edge_count), "metadata": None}]
    ranges = {}
    for key in RANGES:
        ranges[key] = None
        if known(snapshot, key):
            if not isinstance(snapshot[key], list):
                raise ContractError(f"{key}: expected list")
            ranges[key] = []
            for i, row in enumerate(snapshot[key]):
                sub = row.get("position", row)
                begin = position(sub.get("begin"), edge_count, f"{key}[{i}].begin")
                end = position(sub.get("end"), edge_count, f"{key}[{i}].end")
                if end <= begin:
                    raise ContractError(f"{key}[{i}]: empty/reversed range")
                ranges[key].append((begin, end))
                cuts.update((begin, end))
    speed_limits = snapshot.get("speedLimits") if known(snapshot, "speedLimits") else None
    if speed_limits is not None:
        if not isinstance(speed_limits, list) or len(speed_limits) != edge_count:
            raise ContractError("speedLimits: expected one nullable m/s value per source edge")
        for i, value in enumerate(speed_limits):
            if value is not None and finite(value, f"speedLimits[{i}]") < 0:
                raise ContractError("negative speed limit")
            if i and value != speed_limits[i-1]:
                cuts.add(float(i))
    lights = None
    if known(snapshot, "trafficLights"):
        if not isinstance(snapshot["trafficLights"], list):
            raise ContractError("trafficLights: expected list")
        lights = []
        for i, row in enumerate(snapshot["trafficLights"]):
            lights.append({"at": position(row.get("position"), edge_count, f"trafficLights[{i}].position"), "id": row.get("id")})
    jams = snapshot.get("jamSegments") if known(snapshot, "jamSegments") else None
    if jams is not None:
        if not isinstance(jams, list) or len(jams) not in (0, edge_count):
            raise ContractError("jamSegments: expected one item per source edge")
        for i, row in enumerate(jams):
            if row is not None:
                if not isinstance(row, dict):
                    raise ContractError(f"jamSegments[{i}]: expected object or unknown null")
                speed = row.get("speed")
                if speed is not None and finite(speed, f"jamSegments[{i}].speed") < 0:
                    raise ContractError("negative jam speed")
            if i and row != jams[i-1]:
                cuts.add(float(i))
    waypoints = None
    if known(snapshot, "wayPoints"):
        if not isinstance(snapshot["wayPoints"], list):
            raise ContractError("wayPoints: expected list")
        waypoints = [position(value, edge_count, f"wayPoints[{i}]") for i, value in enumerate(snapshot["wayPoints"])]
        if any(a > b for a, b in zip(waypoints, waypoints[1:])):
            raise ContractError("wayPoints: expected route order")
    terminal_known = (snapshot.get("availability", {}).get("wayPoints", "known") == "known" and
                      waypoints is not None and len(waypoints) >= 2 and waypoints[0] == 0 and waypoints[-1] == edge_count)
    return {"source": snapshot, "routeId": route_id, "points": points,
            "cumulative": cumulative, "sections": sections, "sectionsKnown": sections_known,
            "ranges": ranges, "cuts": sorted(cuts), "lights": lights,
            "speedLimits": speed_limits, "jams": jams, "terminalKnown": terminal_known,
            "waypoints": waypoints}


def route_convert(snapshot, *, target_frame):
    if target_frame not in FRAMES:
        raise ContractError("target coordinate frame must be selected explicitly")
    route = normalize(snapshot)
    source_points, cumulative = route["points"], route["cumulative"]
    links, wire_links, wire_points = [], [], []
    for i, (begin, end) in enumerate(zip(route["cuts"], route["cuts"][1:])):
        scalars = [begin] + list(range(math.floor(begin)+1, math.ceil(end))) + [end]
        local_points = [point_at(source_points, scalar) for scalar in scalars]
        output_points = [transform(p, target_frame) for p in local_points]
        middle = (begin+end)/2
        section = next(j for j, s in enumerate(route["sections"]) if s["begin"] <= middle < s["end"])
        flags = {key: None if intervals is None else any(a <= middle < b for a,b in intervals)
                 for key, intervals in route["ranges"].items()}
        section_link = sum(1 for link in links if link["sectionIndex"] == section)
        # Stock signals belong to the incoming link, including a cut boundary.
        # A signal exactly at route start has no known incoming link in our cut.
        light_count = None if route["lights"] is None else sum(begin < row["at"] <= end for row in route["lights"])
        record = {"index": i, "sectionIndex": section, "linkInSection": section_link,
                  "sourceBegin": begin, "sourceEnd": end,
                  "sourcePositions": [unpack_position(s, len(source_points)-1) for s in scalars],
                  "sourcePointsWgs84": local_points,
                  "distanceFromStartM": length_at(cumulative, begin),
                  "lengthM": length_at(cumulative, end)-length_at(cumulative, begin),
                  "rangeMembership": flags,
                  "speedLimitMps": None if route["speedLimits"] is None else route["speedLimits"][min(int(middle), len(source_points)-2)],
                  "jam": None if not route["jams"] else copy.deepcopy(route["jams"][min(int(middle), len(source_points)-2)]),
                  "trafficLightCount": light_count}
        links.append(record)
        # Unknown is null; lane sign width is never promoted to road lane count.
        wire_link = dict.fromkeys(("adminCode", "assistantAction", "direction", "formway",
                                  "hasMixFork", "hasMultiOut", "hasParallel", "laneNum",
                                  "linktype", "mainAction", "ownership", "roadclass", "roadname"))
        wire_link.update({"linkID": stable_id("yandex-link-v1", route["routeId"], begin, end),
                          "hasTrafficLight": None if light_count is None else bool(light_count),
                          "len": record["lengthM"], "pntBegIdx": len(wire_points),
                          "pntCnt": len(output_points), "speedLimit": 0})
        if flags["tunnels"] is True:
            wire_link["linktype"] = 2  # Verified stock LinkTypeTunnel enum, no inference outside known ranges.
        wire_links.append(wire_link)
        wire_points.extend({"x": p[0], "y": p[1]} for p in output_points)
    segments = []
    for i, section in enumerate(route["sections"]):
        # Yandex annotation applies at this section's BEGIN. Stock navigation
        # segment action is the instruction at its END: use the next section's
        # entry annotation only at their shared geometry boundary. Never index
        # the same annotation onto both phases.
        next_section = route["sections"][i+1] if i+1 < len(route["sections"]) else None
        metadata = (next_section["metadata"] or {}) if next_section is not None else {}
        annotation = metadata.get("annotation") or {}
        action = annotation.get("action")
        selected = [link for link in links if link["sectionIndex"] == i]
        segments.append({"crntSegmLinkCnt": len(selected), "linkBegIdx": selected[0]["index"],
                         "navigationLen": sum(link["lengthM"] for link in selected),
                         "navigationMainAction": MAIN_ACTION.get(action),
                         "navigationAssitAction": None,
                         "trafficLightNum": None if route["lights"] is None else sum(link["trafficLightCount"] for link in selected),
                         "isArriveWayPoint": None, "description": annotation.get("descriptionText"),
                         "navigationNextRoadName": annotation.get("toponym")})
        if next_section is None and route["terminalKnown"]:
            segments[-1].update(navigationMainAction=0, navigationAssitAction=36,
                                isArriveWayPoint=True, navigationNextRoadName="")
        section["endInstructionSourceSectionIndex"] = i+1 if next_section is not None else None
        section["endInstructionYandexAction"] = action
    traffic_lights = None if route["lights"] is None else [dict(zip(("x", "y"), transform(point_at(source_points, row["at"]), target_frame))) for row in route["lights"]]
    candidate = {"pathID": stable_path_id(route["routeId"]),
                 "linkCnt": len(wire_links), "links": wire_links,
                 "pntCnt": len(wire_points), "pnts": wire_points,
                 "guideGroups": [{"groupName": None, "groupIconType": segments[0]["navigationMainAction"],
                                  "groupLen": cumulative[-1], "groupTime": None,
                                  "groupTrafficLightsCount": None if traffic_lights is None else len(traffic_lights),
                                  "groupEnterCoord": wire_points[0], "segments": segments}],
                 "trafficLights": traffic_lights, "viaRoadInfo": None}
    blockers = ["receiver_coordinate_frame_unverified", "synthetic_link_id_semantics_unverified",
                "single_writer_not_established", "candidate_contains_unavailable_sdk_fields"]
    if not route["terminalKnown"]:
        blockers.append("terminal_arrival_action_unresolved")
    if route["waypoints"] is not None and len(route["waypoints"]) > 2:
        blockers.append("intermediate_waypoint_actions_unresolved")
    if route["lights"] is not None and any(row["at"] == 0 for row in route["lights"]):
        blockers.append("route_start_signal_incoming_link_unavailable")
    if "coordinateFrame" not in snapshot or "pointOrder" not in snapshot:
        blockers.append("legacy_input_frame_or_point_order_assumed")
    if target_frame == "wgs84":
        blockers.append("wgs84_is_geometry_inspection_only_not_stock_frame")
    if not route["sectionsKnown"]:
        blockers.append("missing_manoeuvre_sections")
    for key in EXTRAS:
        if not known(snapshot, key):
            blockers.append("unknown_yandex_" + key)
    unknown_actions = sorted({str(((s["metadata"] or {}).get("annotation") or {}).get("action")) for s in route["sections"]
                              if ((s["metadata"] or {}).get("annotation") or {}).get("action") not in MAIN_ACTION})
    if unknown_actions:
        blockers.append("unmapped_route_actions:" + ",".join(unknown_actions))
    return {"schema": "denza-adas-offline-candidate-v1", "offlineOnly": True,
            "publicationReady": False, "routeId": route["routeId"],
            "generation": snapshot.get("generation"),
            "sourceSessionId": snapshot.get("sourceSessionId"),
            "coordinateContract": {"source": "wgs84", "target": target_frame,
                                   "transformApplications": 0 if target_frame == "wgs84" else 1,
                                   "status": "inspection" if target_frame == "wgs84" else "Moscow-only candidate, receiver datum unverified"},
            "instructionBoundaryPolicy": "Yandex section BEGIN annotation -> preceding stock segment END; final arrival only with known endpoint-consistent wayPoints",
            "exportBlockers": blockers,
            "intermediate": {"sourcePointCount": len(source_points), "lengthM": cumulative[-1],
                             "sourceCumulativeDistanceM": cumulative, "sectionsKnown": route["sectionsKnown"],
                             "sections": route["sections"], "links": links,
                             "sourceExtras": {key: copy.deepcopy(snapshot[key]) for key in EXTRAS if key in snapshot},
                             "sourceMetadata": copy.deepcopy(snapshot.get("metadata")),
                             "metadataWeightScope": "SDK values at snapshot time may change during guidance; never substituted for full geometry length",
                             "sourceAvailability": copy.deepcopy(snapshot.get("availability", {})),
                             "availability": {key: snapshot.get("availability", {}).get(key, "known" if known(snapshot, key) else "unknown") for key in ("sections",)+EXTRAS},
                             "sourceIssues": copy.deepcopy(snapshot.get("issues", {}))},
            "candidateNaviToEHPData": candidate}


def map_progress(converted, sample, *, now_ms, max_age_ms=2000, clock="wall"):
    """Offline position candidate. Expiry and generation are required, no replay trust."""
    result = {"accepted": False, "publicationReady": False}
    native = sample.get("schema") == "denza-yandex-guidance-v1"
    if clock not in ("wall", "uptime"):
        raise ContractError("clock must be wall or uptime")
    if converted.get("sourceSessionId") != sample.get("sourceSessionId"):
        return dict(result, reason="source_session_mismatch")
    if sample.get("routeId") != converted["routeId"] or sample.get("generation") != converted.get("generation"):
        return dict(result, reason="route_or_generation_mismatch")
    if sample.get("generation") is None:
        return dict(result, reason="missing_generation")
    timestamp = sample.get("capturedUptimeMs" if clock == "uptime" else "capturedAtMs")
    expiry = timestamp + max_age_ms if native and type(timestamp) is int else sample.get("expiresAtMs")
    if type(timestamp) is not int or type(expiry) is not int:
        return dict(result, reason="missing_clock_or_expiry")
    if timestamp > now_ms or expiry <= now_ms or now_ms-timestamp > max_age_ms or expiry <= timestamp:
        return dict(result, reason="stale_or_invalid_time")
    active = sample.get("mode") == "guidance" and sample.get("freshLocation") is True if native else sample.get("status") == "guidance" and sample.get("matched") is True
    if not active:
        return dict(result, reason="not_matched_guidance", navigationStatus=110, curSDRouteID=-1)
    if native:
        fix_age = sample.get("fixAgeMs")
        if type(fix_age) is not int or fix_age < 0 or fix_age+now_ms-timestamp > 2500:
            return dict(result, reason="stale_or_unknown_location")
        if sample.get("coordinateFrame") != "wgs84":
            return dict(result, reason="unexpected_location_frame")
        owner_known = "positionRouteId" in sample or "positionMatchesRoute" in sample
        if owner_known or sample.get("sourceSessionId") is not None:
            if sample.get("positionRouteId") != sample.get("routeId") or sample.get("positionMatchesRoute") is not True:
                return dict(result, reason="position_route_owner_unknown_or_mismatch")
    middle = converted["intermediate"]
    scalar = position(sample.get("sourcePolylinePosition" if native else "position"), middle["sourcePointCount"]-1, "progress.position")
    links = middle["links"]
    index = min(bisect.bisect_right([link["sourceBegin"] for link in links], scalar)-1, len(links)-1)
    link = links[index]
    cumulative = middle["sourceCumulativeDistanceM"]
    passed = length_at(cumulative, scalar)
    section = middle["sections"][link["sectionIndex"]]
    # Interpolate on source edge coordinates rather than transformed coordinates.
    source_positions = [p["segmentIndex"] + p["segmentPosition"] for p in link["sourcePositions"]]
    local = min(bisect.bisect_right(source_positions, scalar)-1, len(source_positions)-2)
    span = source_positions[local+1]-source_positions[local]
    fraction = (scalar-source_positions[local])/span
    wgs = [link["sourcePointsWgs84"][local][axis] + fraction*(link["sourcePointsWgs84"][local+1][axis]-link["sourcePointsWgs84"][local][axis]) for axis in (0,1)]
    pose_check = None
    if native:
        location = sample.get("location")
        if not isinstance(location, dict):
            return dict(result, reason="missing_native_location")
        lon, lat, accuracy = location.get("longitude"), location.get("latitude"), location.get("accuracy")
        if (any(isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value)
                for value in (lon, lat, accuracy)) or not -180 <= lon <= 180 or not -90 <= lat <= 90 or not 0 <= accuracy <= 100):
            return dict(result, reason="invalid_or_unknown_location_accuracy")
        pose_times = []
        for key, capture_key in (("absoluteTimestamp", "capturedAtMs"), ("relativeTimestamp", "capturedUptimeMs")):
            value, capture = location.get(key), sample.get(capture_key)
            if value is not None:
                if (isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or type(capture) is not int):
                    return dict(result, reason="invalid_native_location_time")
                age = capture-value+now_ms-timestamp
                if age < -250 or age > 2500:
                    return dict(result, reason="stale_or_future_native_location_time")
                pose_times.append(age)
        if not pose_times:
            return dict(result, reason="unknown_native_location_time")
        gap = distance([lon, lat], wgs)
        tolerance = min(100, max(25, accuracy+15))
        pose_check = {"distanceFromSourceGeometryM": gap, "diagnosticToleranceM": tolerance,
                      "locationAgesMs": pose_times, "policy": "geometry compatibility guard, not ADAS localization acceptance"}
        if gap > tolerance:
            return dict(result, reason="location_source_geometry_mismatch", poseCompatibility=pose_check)
    sdk = transform(wgs, converted["coordinateContract"]["target"])
    dynamic = None
    if native:
        dynamic = {key: copy.deepcopy(sample.get(key)) for key in
                   ("sequence", "location", "distanceToFinishM", "timeToFinishS", "speedLimitMps", "nextManoeuvre", "hudLaneHint", "hudLaneHintScope", "issues")}
        next_manoeuvre = sample.get("nextManoeuvre") or {}
        dynamic["candidateNextRouteMainAction"] = MAIN_ACTION.get(next_manoeuvre.get("action"))
    return dict(result, accepted=True, reason="offline_geometry_only", navigationStatus=101,
                clock=clock, expiryPolicy="host max_age_ms" if native else "explicit expiresAtMs",
                sourceDynamic=dynamic,
                poseCompatibility=pose_check,
                positionOwnershipPolicy="exporter route owner proof" if native and "positionRouteId" in sample else "legacy geometry compatibility only; owner proof unavailable",
                currentSourceJam=copy.deepcopy(link.get("jam")), currentSourceSpeedLimitMps=link.get("speedLimitMps"),
                curSDRouteID=converted["candidateNaviToEHPData"]["pathID"], curStepId=link["sectionIndex"],
                curLinkId=link["linkInSection"], globalLinkIndex=index,
                linkOffset=max(0.0, link["distanceFromStartM"]+link["lengthM"]-passed),
                distanceToNextStep=max(0.0, length_at(cumulative, section["end"])-passed),
                distanceToDestinationM=max(0.0, middle["lengthM"]-passed),
                matchedWgs84=wgs, matchedTarget=sdk, currentRoadOwnership=None, roadClass=None)


def validate_payload(payload):
    """Validate structural invariants without inferring eligibility or equivalence."""
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
            length = link.get("len")
            if isinstance(length, bool) or not isinstance(length, (int, float)) or not math.isfinite(length) or length < 0:
                errors.append(f"{prefix}.links[{li}]: invalid length")
            lane_num = link.get("laneNum")
            if lane_num is not None and (type(lane_num) is not int or lane_num < 1):
                errors.append(f"{prefix}.links[{li}]: known laneNum must be a positive integer")
            try:
                signed64(link.get("linkID"), f"{prefix}.links[{li}].linkID")
            except ContractError as exc:
                errors.append(str(exc))
            start, count = link.get("pntBegIdx"), link.get("pntCnt")
            if type(start) is not int or type(count) is not int or start != next_point or count < 2 or start+count > len(points):
                errors.append(f"{prefix}.links[{li}]: invalid sequential point slice")
                continue
            if li and points[start] != points[start-1]:
                errors.append(f"{prefix}.links[{li}]: boundary endpoint must be duplicated")
            next_point = start+count
        if next_point != len(points):
            errors.append(prefix + ": points not consumed by links")
        covered = 0
        for group in route.get("guideGroups") or []:
            for segment in group.get("segments") or []:
                start, count = segment.get("linkBegIdx"), segment.get("crntSegmLinkCnt")
                if type(start) is not int or type(count) is not int or start != covered or count <= 0:
                    errors.append(prefix + ": invalid segment link coverage")
                    continue
                covered += count
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
                    differences.append({"path": where+"."+key, "reason": "missing_left" if key not in a else "missing_right"})
                else:
                    walk(a[key], b[key], where+"."+key)
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
            Path(args.out).write_text(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False)+"\n")
            print(json.dumps({"out": args.out, "links": result["candidateNaviToEHPData"]["linkCnt"], "publicationReady": False, "blockers": result["exportBlockers"]}))
        elif args.command == "validate":
            errors = validate_payload(read_json(args.payload))
            print(json.dumps({"valid": not errors, "errors": errors}, indent=2))
            return bool(errors)
        elif args.command == "diff":
            differences = recursive_diff(read_json(args.left), read_json(args.right))
            print(json.dumps({"comparison": "structural only; same corridor is not established", "differences": differences}, ensure_ascii=False, indent=2))
            return bool(differences)
        elif args.command == "progress":
            print(json.dumps(map_progress(read_json(args.converted), read_json(args.sample), now_ms=args.now_ms, clock=args.clock), indent=2))
    except (ContractError, ValueError, KeyError, TypeError) as exc:
        parser.exit(2, f"invalid input: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
