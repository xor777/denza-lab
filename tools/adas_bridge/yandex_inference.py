"""Host-only Yandex snapshot inventory and explicitly uncertain road hypotheses.

No map store, network, device access, wire serializer or publisher is used. A snapshot
can describe an SDK annotation precisely without establishing an Amap road attribute.
In particular, geometry vertices are not junctions, a lane sign is point evidence,
and a signed road reference may name a road other than the one being travelled.

    python3 -m tools.adas_bridge.yandex_inference --out-dir captures/inference \
        --approach-lanes-m 100 path/to/denza-hud-native-route.json

The optional lane extrapolation is bounded to at most 100 m and never fills the
exact road lane-count field. All distances are along the supplied WGS84 polyline.
"""
from __future__ import annotations

import argparse
import bisect
import hashlib
import json
import math
from pathlib import Path
import re


SCHEMA = "denza-yandex-only-inference-v1"
RANGES = {
    "highways": "sdkHighway", "tunnels": "sdkTunnel", "ferries": "sdkFerry",
    "ruggedRoads": "sdkRuggedRoad", "fordCrossings": "sdkFordCrossing",
    "tollRoads": "sdkTollRoad", "hdSections": "sdkHdSection",
    "standingSegments": "sdkStandingSegment", "zoneCrossings": "sdkZoneCrossing",
}
POINTS = ("trafficLights", "restrictedEntries", "restrictedTurns", "speedBumps",
          "pedestrianCrossings", "checkpoints", "railwayCrossings", "wayPoints", "events")
EXACT_UNSUPPORTED = {
    "roadClass": "No observed per-road Amap class in this snapshot contract.",
    "formway": "No observed per-road formway; hypotheses are separate.",
    "roadLaneCount": "Junction sign width does not establish the road's total lane count.",
    "oneWay": "Travel along this route does not establish opposing-traffic access.",
    "ownership": "Neither a highway annotation nor routing establishes public ownership.",
    "roadAccess": "Restricted-entry points and route flags do not define per-road access rules.",
    "junctionTopology": "A polyline and guidance annotations do not enumerate road junctions.",
    "globalLinkId": "The snapshot has no globally meaningful road-link identity.",
}
STATUSES = ("observed", "derived", "estimated", "unknown")


def _number(value):
    if isinstance(value, bool) or not isinstance(value, (float, int)) or not math.isfinite(value):
        raise ValueError("expected a finite number")
    return float(value)


def _distance(a, b):
    x1, y1, x2, y2 = map(math.radians, (*a, *b))
    h = math.sin((y2 - y1) / 2) ** 2 + math.cos(y1) * math.cos(y2) * math.sin((x2 - x1) / 2) ** 2
    return 2 * 6371008.8 * math.asin(min(1, math.sqrt(h)))


def _claim(status, value=None, paths=(), rule=None, reason=None):
    out = {"status": status, "value": value, "provenance": ["$." + p for p in paths]}
    if rule:
        out["rule"] = rule
    if reason:
        out["limitation"] = reason
    return out


class _Snapshot:
    def __init__(self, data):
        self.data = data
        if data.get("schema") != "denza-yandex-driving-route-v1":
            raise ValueError("unsupported Yandex snapshot schema")
        if data.get("coordinateFrame") != "wgs84" or data.get("pointOrder") != "lon,lat":
            raise ValueError("requires WGS84 lon,lat points")
        for key, unit in (("speed", "m/s"), ("distance", "m"), ("time", "s")):
            if data.get("units", {}).get(key, unit) != unit:
                raise ValueError("unsupported " + key + " unit")
        self.availability = data.get("availability", {})
        points = data.get("points")
        if not isinstance(points, list) or len(points) < 2 or not self.usable("points", points):
            raise ValueError("at least two known geometry points required")
        self.cumulative = [0.0]
        for i, p in enumerate(points):
            if not isinstance(p, list) or len(p) != 2:
                raise ValueError("invalid geometry point")
            if (not self.usable(f"points[{i}]", p) or
                    any(not self.readable(f"points[{i}][{j}]") for j in (0, 1))):
                raise ValueError("unknown geometry point")
            lon, lat = map(_number, p)
            if abs(lon) > 180 or abs(lat) > 90:
                raise ValueError("geometry outside WGS84 bounds")
        for a, b in zip(points, points[1:]):
            self.cumulative.append(self.cumulative[-1] + _distance(a, b))
        if self.cumulative[-1] <= 0:
            raise ValueError("zero-length route")
        self.edges = len(points) - 1
        self.issues = []

    def readable(self, path):
        # A partial parent can contain usable children; an unknown parent cannot.
        ancestors = [path[:m.start()] for m in re.finditer(r"[.\[]", path)] + [path]
        return all(self.availability.get(p) != "unknown" for p in ancestors)

    def usable(self, path, value):
        return value is not None and self.readable(path)

    def rows(self, key):
        value = self.data.get(key)
        if not self.usable(key, value):
            return []
        if not isinstance(value, list):
            raise ValueError(key + " must be an array")
        return [(i, v) for i, v in enumerate(value) if self.usable(f"{key}[{i}]", v)]

    def scalar(self, position, path=None):
        if not isinstance(position, dict):
            raise ValueError("missing polyline position")
        if path is not None:
            fraction_key = "segmentPosition" if "segmentPosition" in position else "fraction"
            if (not self.usable(path + ".segmentIndex", position.get("segmentIndex")) or
                    not self.usable(path + "." + fraction_key, position.get(fraction_key))):
                raise ValueError(path + " has an unknown position component")
        index = _number(position.get("segmentIndex"))
        fraction = _number(position.get("segmentPosition", position.get("fraction")))
        if index != int(index) or index < 0 or index > self.edges or not 0 <= fraction <= 1:
            raise ValueError("polyline position outside geometry")
        if index == self.edges and fraction != 0:
            raise ValueError("polyline position beyond route end")
        return index + fraction

    def metres(self, scalar):
        edge = min(int(scalar), self.edges - 1)
        return self.cumulative[edge] + (scalar - edge) * (self.cumulative[edge + 1] - self.cumulative[edge])

    def at_metres(self, metres):
        i = min(bisect.bisect_right(self.cumulative, metres) - 1, self.edges - 1)
        span = self.cumulative[i + 1] - self.cumulative[i]
        return i + (metres - self.cumulative[i]) / span if span else float(i)

    def scope(self, begin, end=None):
        if end is None:
            return {"kind": "point", "scalar": begin, "distanceM": round(self.metres(begin), 6)}
        return {"kind": "interval", "beginScalar": begin, "endScalar": end,
                "beginM": round(self.metres(begin), 6), "endM": round(self.metres(end), 6),
                "bounds": "[begin,end)" if end < self.edges else "[begin,end]"}

    def interval(self, value, path):
        if not isinstance(value, dict):
            raise ValueError(path + " must be a range")
        wrapped = "position" in value
        value = value.get("position", value)
        if not self.usable(path, value):
            return None
        # Explicit unknown positions must not turn into positive annotations.
        base = path + (".position" if wrapped else "")
        if not self.usable(base + ".begin", value.get("begin")) or not self.usable(base + ".end", value.get("end")):
            self.issues.append({"path": path, "reason": "unknown range endpoint"})
            return None
        for key in ("begin", "end"):
            pos = value[key]
            for component in ("segmentIndex", "segmentPosition" if "segmentPosition" in pos else "fraction"):
                if not self.usable(base + "." + key + "." + component, pos.get(component)):
                    self.issues.append({"path": path, "reason": "unknown range position component"})
                    return None
        begin, end = self.scalar(value.get("begin")), self.scalar(value.get("end"))
        if end <= begin:
            raise ValueError(path + " is empty or reversed")
        return begin, end

def _ranges(s):
    result = {}
    for key in RANGES:
        result[key] = []
        for i, row in s.rows(key):
            path = f"{key}[{i}]"
            interval = s.interval(row, path)
            if interval:
                result[key].append((*interval, path))
    return result


def _sections(s):
    out = []
    for i, row in s.rows("sections"):
        path = f"sections[{i}]"
        if not s.usable(path + ".geometry", row.get("geometry")):
            continue
        interval = s.interval(row["geometry"], path + ".geometry")
        if not interval:
            continue
        b, e = interval
        if out and b < out[-1][1]:
            raise ValueError("section geometry overlaps or is out of order")
        out.append((b, e, path, row.get("metadata") or {}))
    return out


def _edge_runs(s, key, transform):
    values = s.data.get(key)
    if not s.usable(key, values):
        return []
    if not isinstance(values, list) or len(values) != s.edges:
        raise ValueError(key + " must contain one value per geometry edge")
    out = []
    for i, value in enumerate(values):
        path = f"{key}[{i}]"
        transformed = transform(value, path) if s.usable(path, value) else None
        if out and transformed == out[-1][2]:
            out[-1][1] = i + 1
        else:
            out.append([i, i + 1, transformed])
    return out


def _weight_claim(s, metadata, path, key):
    weight = metadata.get("weight") or {}
    value = (weight.get(key) or {}).get("value")
    source = path + ".metadata.weight." + key + ".value"
    if not s.usable(source, value):
        return _claim("unknown", reason="Section weight unavailable.")
    value = _number(value)
    if value < 0:
        raise ValueError("negative section weight")
    return _claim("observed", value, [source])


def _section_claims(s, section):
    if section is None:
        return {key: _claim("unknown", reason="No usable section covers this interval.")
                for key in ("sectionRoadName", "sectionAction", "sectionFreeFlowKmh", "sectionTrafficTimeS")}
    begin, end, path, metadata = section
    annotation = metadata.get("annotation") or {}
    out = {}
    for source_key, dest in (("toponym", "sectionRoadName"), ("action", "sectionAction")):
        value = annotation.get(source_key)
        source = path + ".metadata.annotation." + source_key
        out[dest] = (_claim("observed", value, [source], reason="Section guidance context; not a global road identity.")
                     if s.usable(source, value) else _claim("unknown", reason="No usable section annotation."))
    distance = _weight_claim(s, metadata, path, "distance")
    time = _weight_claim(s, metadata, path, "time")
    if distance["status"] == time["status"] == "observed" and time["value"] > 0:
        out["sectionFreeFlowKmh"] = _claim("derived", distance["value"] / time["value"] * 3.6,
            [path + ".metadata.weight.distance.value", path + ".metadata.weight.time.value"],
            "section_weight_distance_divided_by_time", "Section planning average; not measured speed or a posted limit.")
    else:
        out["sectionFreeFlowKmh"] = _claim("unknown", reason="No positive usable section time and distance.")
    out["sectionTrafficTimeS"] = _weight_claim(s, metadata, path, "timeWithTraffic")
    for key, claim in out.items():
        claim["sourceScope"] = s.scope(begin) if key == "sectionAction" else s.scope(begin, end)
    out["sectionTrafficTimeS"]["limitation"] = "Total for the source section, repeated as context; not travel time for this evidence interval."
    return out


def _lane_evidence(s):
    out = []
    turn_directions = {"LEFT45", "LEFT90", "LEFT135", "LEFT180", "RIGHT45", "RIGHT90", "RIGHT135", "RIGHT180"}
    for i, sign in s.rows("laneSigns"):
        path = f"laneSigns[{i}]"
        if not s.usable(path + ".position", sign.get("position")):
            continue
        scalar = s.scalar(sign["position"], path + ".position")
        lanes = sign.get("lanes")
        counts = {"displayedLanes": None, "plainLanes": 0, "specialLanes": 0,
                  "throughBearingPlainLanes": 0, "turnOnlyPlainLanes": 0,
                  "unclassifiedPlainLanes": 0, "highlightedLanes": 0, "unknownHighlightLanes": 0,
                  "unknownKindLanes": 0}
        lane_rows = []
        if s.usable(path + ".lanes", lanes) and isinstance(lanes, list):
            counts["displayedLanes"] = len(lanes)
            for j, lane in enumerate(lanes):
                lp = f"{path}.lanes[{j}]"
                if not isinstance(lane, dict) or not s.usable(lp, lane):
                    counts["unknownKindLanes"] += 1
                    counts["unknownHighlightLanes"] += 1
                    lane_rows.append(_claim("unknown", reason="Lane unavailable."))
                    continue
                observed = {key: lane[key] for key in ("laneKind", "directions", "highlightedDirection")
                            if key in lane and s.usable(lp + "." + key, lane[key])}
                if "highlightedDirection" in lane and s.readable(lp + ".highlightedDirection"):
                    observed["highlightedDirection"] = lane["highlightedDirection"]
                if "directions" in observed:
                    observed["directions"] = [v for k, v in enumerate(observed["directions"])
                                              if s.usable(f"{lp}.directions[{k}]", v)]
                lane_rows.append(_claim("observed", observed, [lp + "." + key for key in observed]))
                kind, directions = observed.get("laneKind"), observed.get("directions")
                raw_directions = lane.get("directions") or []
                directions_complete = (s.availability.get(lp + ".directions", "known") == "known" and
                                       isinstance(directions, list) and len(directions) == len(raw_directions))
                if kind == "PLAIN_LANE":
                    counts["plainLanes"] += 1
                    if isinstance(directions, list) and "STRAIGHT_AHEAD" in directions:
                        counts["throughBearingPlainLanes"] += 1
                    elif directions_complete and directions and set(directions) <= turn_directions:
                        counts["turnOnlyPlainLanes"] += 1
                    else:
                        counts["unclassifiedPlainLanes"] += 1
                elif kind is None:
                    counts["unknownKindLanes"] += 1
                else:
                    counts["specialLanes"] += 1
                if observed.get("highlightedDirection") is not None:
                    counts["highlightedLanes"] += 1
                elif "highlightedDirection" not in observed:
                    counts["unknownHighlightLanes"] += 1
        out.append({"kind": "laneSign", "scope": s.scope(scalar), "lanes": lane_rows,
                    "counts": _claim("derived" if counts["displayedLanes"] is not None else "unknown", counts,
                        [path + ".lanes"], "count_displayed_lane_kinds_and_directions",
                        "Point evidence. Turn-only is a sign-direction category, not proof of a physical turn pocket; opposing lanes unknown."),
                    "roadLaneCount": _claim("unknown", reason=EXACT_UNSUPPORTED["roadLaneCount"])})
    return sorted(out, key=lambda row: row["scope"]["scalar"])


def _point_evidence(s):
    out = []
    for key in POINTS:
        for i, row in s.rows(key):
            path = f"{key}[{i}]"
            value = row if key == "wayPoints" else row.get("polylinePosition" if key == "events" else "position")
            pp = path if key == "wayPoints" else path + (".polylinePosition" if key == "events" else ".position")
            if not s.usable(pp, value):
                continue
            out.append({"kind": key, "scope": s.scope(s.scalar(value, pp)),
                        "fact": _claim("observed", True, [pp], reason="Point annotation; no inferred junction or surrounding access rule.")})
    for i, row in s.rows("directionSigns"):
        path = f"directionSigns[{i}]"
        if not s.usable(path + ".position", row.get("position")):
            continue
        items = []
        for j, item in enumerate(row.get("items") or []):
            ip = f"{path}.items[{j}]"
            if not isinstance(item, dict) or not s.usable(ip, item):
                continue
            fields = {}
            for key, text_key in (("road", "name"), ("toponym", "text"), ("exit", "name")):
                value = (item.get(key) or {}).get(text_key)
                p = ip + "." + key + "." + text_key
                if s.usable(p, value):
                    fields[{"road": "signedRoadReference", "toponym": "signedDestination", "exit": "signedExit"}[key]] = _claim("observed", value, [p])
            if fields:
                items.append(fields)
        out.append({"kind": "directionSign", "scope": s.scope(s.scalar(row["position"], path + ".position")), "items": items,
                    "currentRoadIdentity": _claim("unknown", reason="A direction sign may refer to a branch or destination, not the current road.")})
    return sorted(out, key=lambda row: (row["scope"]["scalar"], row["kind"]))


def _roundabouts(s, sections):
    out, entry = [], None
    for section in sections:
        b, e, path, metadata = section
        annotation = metadata.get("annotation") or {}
        ap = path + ".metadata.annotation.action"
        action = annotation.get("action") if s.usable(ap, annotation.get("action")) else None
        if action == "ENTER_ROUNDABOUT":
            entry = section
        elif action == "LEAVE_ROUNDABOUT" and entry is not None:
            # Each section's action describes entering that section. The next exit
            # bounds the estimate; an entry alone never extends to route end.
            if b > entry[0] and abs(b - entry[1]) <= 1e-9:
                out.append((entry[0], b, [entry[2] + ".metadata.annotation.action", ap]))
            entry = None
        elif entry is not None:
            # Do not bridge missing geometry or unknown/non-roundabout guidance.
            entry = None
    return out


def _profile(attrs):
    paths = []
    if attrs["sdkHighway"]["value"] == "annotated":
        paths += attrs["sdkHighway"]["provenance"]
        candidates = ["limited_access_highway", "urban_expressway"]
        rule = "explicit_highway_profile_candidates"
    elif attrs["sdkRuggedRoad"]["value"] == "annotated":
        paths += attrs["sdkRuggedRoad"]["provenance"]
        candidates = ["rough_or_low_standard_section", "ordinary_road_with_local_roughness"]
        rule = "explicit_rugged_profile_candidates"
    elif attrs["sdkFerry"]["value"] == "annotated":
        paths += attrs["sdkFerry"]["provenance"]
        candidates, rule = ["ferry_crossing"], "explicit_ferry_profile"
    else:
        limit, free = attrs["postedSpeedKmh"], attrs["sectionFreeFlowKmh"]
        speed = limit["value"] if limit["status"] == "derived" else free["value"]
        if speed is None or speed <= 0:
            return _claim("unknown", reason="No usable highway/surface/ferry or speed context.")
        paths += (limit if limit["status"] == "derived" else free)["provenance"]
        if speed >= 90 - 1e-6:
            candidates = ["high_speed_ordinary_road", "expressway_candidate"]
        elif speed >= 50 - 1e-6:
            candidates = ["arterial_or_collector", "ordinary_road", "expressway_or_ramp_with_local_limit"]
        else:
            candidates = ["local_or_access_road", "turn_or_ramp", "higher_class_road_with_local_slowdown"]
        rule = "speed_context_profile_candidates_v1"
    # _claim accepts unprefixed paths. These already came from claims.
    return _claim("estimated", candidates, [p[2:] for p in paths], rule,
                  "Uncalibrated alternatives, not probabilities or Amap classes. Posted/free-flow speeds do not uniquely identify road class.")


def _approaches(s, lanes, sections, ranges, points, bound):
    out, previous = [], 0.0
    barriers = {0.0}
    for rows in ranges.values():
        for b, e, _ in rows:
            barriers.update((s.metres(b), s.metres(e)))
    for row in points:
        if row["kind"] in {"trafficLights", "restrictedEntries", "restrictedTurns"}:
            barriers.add(s.metres(row["scope"]["scalar"]))
    for row in lanes:
        end = s.metres(row["scope"]["scalar"])
        section = next((v for v in sections if v[0] < row["scope"]["scalar"] <= v[1]), None)
        counts = row["counts"]["value"]
        # Same-coordinate duplicate signs do not create a second overlapping estimate.
        if bound and section and end > previous and counts["displayedLanes"] and counts["unknownKindLanes"] == 0:
            start = max(end - bound, s.metres(section[0]), previous,
                        max((b for b in barriers if b < end - 1e-9), default=0))
            if end - start > 1e-6:
                out.append({"scope": s.scope(s.at_metres(start), row["scope"]["scalar"]),
                            "estimate": _claim("estimated", {
                                "plainTravelDirectionLanes": counts["plainLanes"],
                                "throughBearingPlainLanes": counts["throughBearingPlainLanes"],
                                "turnOnlyPlainLanes": counts["turnOnlyPlainLanes"],
                                "specialDisplayedLanes": counts["specialLanes"],
                                "unclassifiedPlainLanes": counts["unclassifiedPlainLanes"],
                            }, [p[2:] for p in row["counts"]["provenance"]], "bounded_upstream_lane_sign_extrapolation_v1",
                            "Assumes sign layout persists upstream; no observed extent. Travel-direction approach only; never total Amap LaneNum."),
                            "boundM": bound, "roadLaneCount": _claim("unknown", reason=EXACT_UNSUPPORTED["roadLaneCount"])})
        previous = end
    return out


def infer(snapshot, *, source="in-memory", source_sha256=None, approach_lanes_m=0):
    """Extract reproducible evidence and separate estimates; never returns a wire feed."""
    bound = _number(approach_lanes_m)
    if not 0 <= bound <= 100:
        raise ValueError("approach lane extrapolation must be between 0 and 100 metres")
    s = _Snapshot(snapshot)
    ranges, sections = _ranges(s), _sections(s)
    range_complete = {key: (s.usable(key, snapshot.get(key)) and
                           s.availability.get(key, "known") == "known" and
                           len(rows) == len(snapshot[key])) for key, rows in ranges.items()}
    speeds = _edge_runs(s, "speedLimits", lambda value, path: _number(value) * 3.6 if _number(value) > 0 else None)
    jams = _edge_runs(s, "jamSegments", lambda value, path: {
        key: value[key] for key in ("jamType", "speed") if s.usable(path + "." + key, value.get(key))})
    lanes, points = _lane_evidence(s), _point_evidence(s)
    roundabouts = _roundabouts(s, sections)
    cuts = {0.0, float(s.edges)}
    for rows in ranges.values():
        for b, e, _ in rows:
            cuts.update((b, e))
    for b, e, *_ in sections + speeds + roundabouts:
        cuts.update((b, e))
    ordered = sorted(cuts)
    segments = []
    for b, e in zip(ordered, ordered[1:]):
        mid = (b + e) / 2
        attrs = {}
        for key, attr in RANGES.items():
            hits = [p for begin, end, p in ranges[key] if begin <= mid < end]
            attrs[attr] = (_claim("observed", "annotated", hits) if hits else
                           _claim("observed", "not_annotated", [key], reason="No SDK annotation here; not proof of absence in the physical road.")
                           if range_complete[key] else
                           _claim("unknown", reason="Missing, partial or unknown range list cannot establish absence."))
        section = next((v for v in sections if v[0] <= mid < v[1]), None)
        attrs.update(_section_claims(s, section))
        speed = next((v for v in speeds if v[0] <= mid < v[1]), None)
        attrs["postedSpeedKmh"] = (_claim("derived", speed[2], [f"speedLimits[{speed[0]}:{speed[1]}]"], "metres_per_second_times_3_6")
                                   if speed and speed[2] is not None else
                                   _claim("unknown", reason="No usable positive per-edge speed limit; no filling from neighbouring edges or section average."))
        attrs.update({key: _claim("unknown", reason=reason) for key, reason in EXACT_UNSUPPORTED.items()})
        positive = [(attr, value) for attr, value in (("sdkTunnel", 2), ("sdkFerry", 1)) if attrs[attr]["value"] == "annotated"]
        attrs["linkTypeFromAnnotation"] = (_claim("derived", positive[0][1], [p[2:] for p in attrs[positive[0][0]]["provenance"]],
            "translate_explicit_sdk_tunnel_or_ferry", "No common/bridge/elevated default outside an explicit annotation.")
            if len(positive) == 1 else _claim("unknown", reason="No unique positive tunnel/ferry annotation; bridges and elevated roads unobserved."))
        attrs["roadProfileHypothesis"] = _profile(attrs)
        ring = next((r for r in roundabouts if r[0] <= mid < r[1]), None)
        highway = attrs["sdkHighway"]["value"] == "annotated"
        limit = attrs["postedSpeedKmh"]["value"]
        hp = [p[2:] for p in attrs["sdkHighway"]["provenance"]]
        attrs["amapRoadClassHypothesis"] = (_claim("estimated", {
            "candidates": [0, 6], "preferred": (0 if limit >= 90 - 1e-6 else 6) if limit is not None else None},
            hp + ([p[2:] for p in attrs["postedSpeedKmh"]["provenance"]] if limit is not None else []),
            "existing_highway_90kmh_class0_else_class6_heuristic",
            "Unvalidated cross-map mapping. A lower local highway limit does not establish city-speedway class; missing limit has no preferred class.")
            if highway else _claim("unknown", reason="No explicit SDK highway interval; speed or sign reference alone cannot select an Amap class."))
        attrs["amapFormwayHypothesis"] = (_claim("estimated", 4 if ring else 1, ring[2] if ring else hp,
            "paired_roundabout_actions" if ring else "explicit_highway_divided_hypothesis",
            "Cross-map semantic estimate; exact formway remains unknown.") if ring or highway else
            _claim("unknown", reason="No bounded roundabout pair or highway annotation."))
        attrs["oneWayHypothesis"] = (_claim("estimated", True, ring[2] if ring else hp,
            "roundabout_or_highway_carriageway_hypothesis", "Opposing traffic/legal direction is not observed.") if ring or highway else
            _claim("unknown", reason="No one-way source; routing direction does not prove one-way access."))
        if ring:
            attrs["roadProfileHypothesis"] = _claim("estimated", ["roundabout"], ring[2], "paired_roundabout_actions",
                                                    "Bounded by entry and next exit action; physical roundabout extent unverified.")
        segments.append({"scope": s.scope(b, e), "attributes": attrs})
    approaches = _approaches(s, lanes, sections, ranges, points, bound)
    coverage = {}
    total = s.cumulative[-1]
    for segment in segments:
        length = segment["scope"]["endM"] - segment["scope"]["beginM"]
        for key, claim in segment["attributes"].items():
            row = coverage.setdefault(key, {status: 0.0 for status in STATUSES})
            row[claim["status"]] += length
    for key, row in coverage.items():
        coverage[key] = {"metres": {k: round(v, 3) for k, v in row.items()},
                         "percent": {k: round(v / total * 100, 3) for k, v in row.items()}}
    positive_m = {attr: round(sum(row["scope"]["endM"] - row["scope"]["beginM"] for row in segments
                                if row["attributes"][attr]["value"] == "annotated"), 3) for attr in RANGES.values()}
    lane_m = sum(row["scope"]["endM"] - row["scope"]["beginM"] for row in approaches)
    inventory = {key: {"availability": s.availability.get(key, "present_unlabelled" if key in snapshot else "missing"),
                       "entries": len(value) if isinstance(value, list) else None}
                 for key, value in snapshot.items() if key not in {"points", "availability"}}
    flags = (snapshot.get("metadata") or {}).get("flags") or {}
    route_flags = {key: _claim("observed", value, ["metadata.flags." + key],
        reason="Route-wide context only; does not locate the affected road or establish per-road access.")
        for key, value in flags.items() if s.usable("metadata.flags." + key, value)}
    for claim in route_flags.values():
        claim["scope"] = {"kind": "route"}
    digest = source_sha256 or hashlib.sha256(json.dumps(snapshot, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()
    return {"schema": SCHEMA, "purpose": "offline_evidence_and_hypotheses_only", "source": {
        "path": source, "sha256": digest, "routeId": snapshot.get("routeId"),
        "capturedAtUnixMs": snapshot.get("capturedAtUnixMs"), "geometryPoints": s.edges + 1,
        "conditionsOutdated": snapshot.get("conditionsOutdated"),
        "snapshotComplete": snapshot.get("complete"), "snapshotIssueCount": len(snapshot.get("issues", [])),
        "availabilityUnknownCount": sum(v == "unknown" for v in s.availability.values())},
        "scopeContract": {"distance": "metres along supplied WGS84 route geometry", "scalar": "segmentIndex + segmentPosition",
            "segments": "Evidence partitions, not physical road links or inferred junctions.",
            "claims": "Every segment claim inherits that segment's scope; point claims inherit their point's scope.",
            "notAnnotated": "Only absence of an SDK annotation in a known list; not proof of a negative physical attribute.",
            "accuracy": "Coverage is availability or hypothesis extent, never validation or road-attribute accuracy."},
        "inventory": inventory, "routeFlags": route_flags, "segments": segments,
        "pointEvidence": sorted(lanes + points, key=lambda row: (row["scope"]["scalar"], row["kind"])),
        "jamContext": [{"scope": s.scope(b, e), "fact": _claim("observed" if value else "unknown", value,
            [f"jamSegments[{b}:{e}]"], reason="Snapshot traffic context; speed is m/s, not a posted limit or stable road class.")}
            for b, e, value in jams],
        "approachLaneHypotheses": approaches, "extractionIssues": s.issues,
        "heuristicAssessment": {"existingJava": "RouteConverter.attributes: explicit highway + no link-ending traffic light; legal limit >=90 -> class0, lower -> class6, missing -> unknown class. Also sets divided/oneway/public and end-sign width.",
            "assessment": "No paired road ground truth is used here. Threshold/class, divided/oneway/public and road-wide lane width are unvalidated assumptions.",
            "thisPrototype": "Produces candidates per exact SDK interval; does not reproduce the Java link-ending-light gate, because partitions are not physical links. Ownership and exact road lane count stay unknown.",
            "roadReferences": "Kept at their sign points; no reference/name patterns or region-specific rules determine a road class."},
        "coverage": {"totalM": round(total, 3), "evidenceIntervals": len(segments), "attributes": coverage,
            "positiveSdkAnnotationM": positive_m, "laneSigns": len(lanes),
            "directionSigns": sum(row["kind"] == "directionSign" for row in points),
            "pointOnlyLaneCoverageM": 0, "boundedApproachLaneEstimateM": round(lane_m, 3),
            "boundedApproachLaneEstimatePercent": round(lane_m / total * 100, 3),
            "boundedApproachMaxM": bound}}


def summarize(reports):
    """Aggregate only distance-based coverage; point evidence never becomes metres."""
    total = sum(r["coverage"]["totalM"] for r in reports)
    attributes = {}
    for report in reports:
        for key, row in report["coverage"]["attributes"].items():
            dest = attributes.setdefault(key, {status: 0.0 for status in STATUSES})
            for status, value in row["metres"].items():
                dest[status] += value
    return {"schema": SCHEMA + "-coverage", "routes": [{"source": r["source"], "coverage": r["coverage"]} for r in reports],
            "aggregate": {"totalM": round(total, 3),
                "distanceBasis": "Sum of snapshot route distances; overlapping roads are counted per snapshot.", "attributes": {
                key: {"metres": {s: round(v, 3) for s, v in row.items()},
                      "percent": {s: round(v / total * 100, 3) if total else 0 for s, v in row.items()}}
                for key, row in attributes.items()}, "laneSigns": sum(r["coverage"]["laneSigns"] for r in reports),
                "pointOnlyLaneCoverageM": 0,
                "boundedApproachLaneEstimateM": round(sum(r["coverage"]["boundedApproachLaneEstimateM"] for r in reports), 3),
                "boundedApproachLaneEstimatePercent": round(sum(r["coverage"]["boundedApproachLaneEstimateM"] for r in reports) / total * 100, 3) if total else 0},
            "accuracy": "No ground truth evaluated. Estimated coverage does not establish accuracy."}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshots", nargs="+", type=Path)
    parser.add_argument("--out-dir", required=True, type=Path)
    parser.add_argument("--approach-lanes-m", type=float, default=0,
                        help="Opt-in upstream sign-layout estimate, 0..100 m; exact road lane count stays unknown.")
    args = parser.parse_args(argv)
    reports, labels = [], []
    for path in args.snapshots:
        label = path.parent.name if path.name == "denza-hud-native-route.json" else path.stem
        if label in labels:
            raise ValueError("duplicate output label: " + label)
        labels.append(label)
        raw = path.read_bytes()
        report = infer(json.loads(raw), source=str(path), source_sha256=hashlib.sha256(raw).hexdigest(),
                       approach_lanes_m=args.approach_lanes_m)
        reports.append(report)
    args.out_dir.mkdir(parents=True, exist_ok=True)
    for label, report in zip(labels, reports):
        (args.out_dir / (label + ".json")).write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False) + "\n")
    summary = summarize(reports)
    (args.out_dir / "coverage.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2, allow_nan=False) + "\n")
    print(json.dumps({"outDir": str(args.out_dir), "routes": len(reports), **{k: v for k, v in summary["aggregate"].items() if k != "attributes"}}, indent=2))


if __name__ == "__main__":
    main()
