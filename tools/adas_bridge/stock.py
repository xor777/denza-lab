#!/usr/bin/env python3
"""Host oracle of libraries/adas-link: the stock map's ADAS route and messages, HOST ONLY.

An independent Python implementation of the Java library (RouteConverter, StockRouteJson,
StockMessages, Pb, Coordinates, YandexRoute.fromSnapshot): Yandex route snapshot -> Amap-style
PathInfo -> the stock NaviToEHPData JSON exactly as BydLaunchermap's Gson writes it, NaviDataDetail
chunking as n.a(), and the protobuf payloads as protobuf-java lite writes them. Equivalence with the
Java library is checked byte for byte by tests/test_stock_equivalence.py. Standard library only;
no sockets, ADB or Binder.

    python3 -m tools.adas_bridge.stock convert route.json --out payload.json [--frame wgs84]
"""
from __future__ import annotations

import argparse
import decimal
import hashlib
import json
import math
import struct
from pathlib import Path


class StockError(ValueError):
    pass


# ---------------------------------------------------------------- Java number semantics

def java_round(x):
    """Math.round(double): half up, without floor(x + 0.5)'s 0.49999999999999994 error."""
    r = math.floor(x)
    return int(r) + 1 if x - r >= 0.5 else int(r)


def half_up(x):
    """RouteConverter.round: (int) Math.floor(x + 0.5), literally."""
    return int(math.floor(x + 0.5))


def java_int(x):
    """(int) / (long) cast of a double: truncation toward zero."""
    return int(x)


def _sci_digits(value):
    """Shortest round-trip digits and decimal exponent: value = 0.d1d2... * 10**exponent."""
    d = decimal.Decimal(repr(value))
    sign, digits, exponent = d.as_tuple()
    digits = "".join(map(str, digits)).rstrip("0") or "0"
    # position of the decimal point relative to the digit string
    point = len(d.as_tuple().digits) + exponent
    return digits, point


def java_double(value):
    """Double.toString: plain in [1e-3, 1e7), else d.dddE±n; always one fractional digit at least."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise StockError("expected number")
    value = float(value)
    if not math.isfinite(value):
        raise StockError("non-finite double")
    if value == 0:
        return "-0.0" if math.copysign(1, value) < 0 else "0.0"
    sign = "-" if value < 0 else ""
    a = abs(value)
    if a >= 2.0 ** 53 or a < 2.2250738585072014e-308:
        # JDK FloatingDecimal is not shortest-digit there (e.g. 2e23 -> 1.9999999999999998E23,
        # 5e-324 -> 4.9E-324); no stock payload double lives there, so the oracle refuses.
        raise StockError("double outside the oracle's exact Double.toString domain")
    digits, point = _sci_digits(a)
    if 1e-3 <= a < 1e7:
        if point <= 0:
            return sign + "0." + "0" * (-point) + digits
        if point >= len(digits):
            return sign + digits + "0" * (point - len(digits)) + ".0"
        return sign + digits[:point] + "." + digits[point:]
    mantissa = digits[0] + "." + (digits[1:] or "0")
    return sign + mantissa + "E" + str(point - 1)


def java_float(value):
    """Float.toString of a float32 value (shortest digits that round-trip through float32)."""
    f = struct.unpack("<f", struct.pack("<f", float(value)))[0]
    if not math.isfinite(f):
        raise StockError("non-finite float")
    if f == 0:
        return "-0.0" if math.copysign(1, f) < 0 else "0.0"
    for precision in range(1, 10):
        text = "%.*e" % (precision - 1, f)
        if struct.unpack("<f", struct.pack("<f", float(text)))[0] == f:
            break
    a = abs(float(text))
    sign = "-" if f < 0 else ""
    d = decimal.Decimal(text.lstrip("-"))
    _, dig, exponent = d.as_tuple()
    digits = "".join(map(str, dig)).rstrip("0") or "0"
    point = len(dig) + exponent
    if 1e-3 <= a < 1e7:
        if point <= 0:
            return sign + "0." + "0" * (-point) + digits
        if point >= len(digits):
            return sign + digits + "0" * (point - len(digits)) + ".0"
        return sign + digits[:point] + "." + digits[point:]
    return sign + digits[0] + "." + (digits[1:] or "0") + "E" + str(point - 1)


class F:
    """A float (float32) bean field, as Gson writes a Java float."""
    __slots__ = ("value",)

    def __init__(self, value):
        self.value = value


class D:
    """A double bean field holding an integral value (Java prints 1.0, not 1)."""
    __slots__ = ("value",)

    def __init__(self, value):
        self.value = value


def gson(value):
    """Gson with serializeNulls().disableHtmlEscaping(): dicts keep insertion order."""
    out = []
    _gson(out, value)
    return "".join(out)


def _gson(out, v):
    if v is None:
        out.append("null")
    elif isinstance(v, bool):
        out.append("true" if v else "false")
    elif isinstance(v, F):
        out.append(java_float(v.value))
    elif isinstance(v, D):
        out.append(java_double(v.value))
    elif isinstance(v, float):
        out.append(java_double(v))
    elif isinstance(v, int):
        out.append(str(v))
    elif isinstance(v, str):
        _string(out, v)
    elif isinstance(v, dict):
        out.append("{")
        for i, (k, item) in enumerate(v.items()):
            if i:
                out.append(",")
            _string(out, k)
            out.append(":")
            _gson(out, item)
        out.append("}")
    elif isinstance(v, (list, tuple)):
        out.append("[")
        for i, item in enumerate(v):
            if i:
                out.append(",")
            _gson(out, item)
        out.append("]")
    else:
        raise StockError(f"unsupported JSON value {type(v)}")


_ESCAPES = {'"': '\\"', "\\": "\\\\", "\t": "\\t", "\b": "\\b", "\n": "\\n", "\r": "\\r", "\f": "\\f"}


def _string(out, s):
    out.append('"')
    # Gson escapes per UTF-16 unit; astral characters pass unescaped as their two units do.
    for ch in s:
        e = _ESCAPES.get(ch)
        if e is not None:
            out.append(e)
        elif ord(ch) < 0x20 or ch in "  ":
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    out.append('"')


def java_length(s):
    """String.length(): UTF-16 code units."""
    return len(s.encode("utf-16-le")) // 2


def java_substring(s, begin, end=None):
    units = s.encode("utf-16-le")
    n = len(units) // 2
    end = n if end is None else end
    if begin < 0 or end > n or begin > end:
        raise IndexError(f"begin {begin}, end {end}, length {n}")
    return units[2 * begin:2 * end].decode("utf-16-le", errors="surrogatepass")


# ---------------------------------------------------------------- coordinates (Coordinates.java)

NATIVE_SCALE = 3686400
SCALE = 3600000
DEG = math.pi / 180.0
FRAMES = ("wgs84", "native-gcj-unrestricted-candidate")


def _forward(lon, lat):
    x, y = lon - 105.0, lat - 35.0
    root = math.sqrt(abs(x))
    common = (math.sin(x * 18.84955592153876) + math.sin(x * 6.283185307179586)) * 13.33333333333333
    dlat = (-100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * root
            + common
            + (math.sin(y * math.pi) + 2.0 * math.sin(y * 1.047197551196598)) * 13.33333333333333
            + (math.sin(y * 0.2617993877991494) + 2.0 * math.sin(y * 0.1047197551196598)) * 106.6666666666667)
    dlon = (x + 300.0 + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * root
            + common
            + (math.sin(x * math.pi) + 2.0 * math.sin(x * 1.047197551196598)) * 13.33333333333333
            + (math.sin(x * 0.2617993877991494) + 2.0 * math.sin(x * 0.1047197551196598)) * 100.0)
    radians = 0.0174532925199433
    sinlat, coslat = math.sin(lat * radians), math.cos(lat * radians)
    magic = 1.0 - 0.00669342 * sinlat * sinlat
    rootmagic = math.sqrt(magic)
    return (lon + rootmagic * dlon / (6378245.0 * coslat) / radians,
            lat + magic * rootmagic * dlat / 6335552.7273521 / radians)


def to_sdk(lon, lat, frame):
    if not (math.isfinite(lon) and math.isfinite(lat)):
        raise StockError("non-finite coordinate")
    if frame == "wgs84":
        return lon, lat
    if frame != "native-gcj-unrestricted-candidate":
        raise StockError("unknown frame " + repr(frame))
    if not (35.0 <= lon <= 40.0 and 54.0 <= lat <= 58.0):
        raise StockError("Candidate port supports only the Moscow research envelope")
    qlon = java_int(lon * NATIVE_SCALE) / NATIVE_SCALE
    qlat = java_int(lat * NATIVE_SCALE) / NATIVE_SCALE
    r = _forward(qlon, qlat)
    return java_int(r[0] * NATIVE_SCALE) / NATIVE_SCALE, java_int(r[1] * NATIVE_SCALE) / NATIVE_SCALE


def units(degrees):
    return java_round(degrees * SCALE)


def grid(degrees):
    return units(degrees) / 3600000.0


def distance(lon1, lat1, lon2, lat2):
    p, q = lat1 * DEG, lat2 * DEG
    s = math.sin((p - q) / 2)
    t = math.sin((lon1 - lon2) * DEG / 2)
    d = s * s + math.cos(p) * math.cos(q) * t * t
    return 2 * 6371008.8 * math.asin(min(1, math.sqrt(d)))


# ---------------------------------------------------------------- Yandex route (YandexRoute.java)

def _num(v):
    if isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v):
        raise StockError(f"expected finite number, got {v!r}")
    return float(v)


def _known(s, key):
    if s.get(key) is None:
        return False
    availability = s.get("availability")
    return not (isinstance(availability, dict) and availability.get(key) == "unknown")


def _position(value, edges):
    if not isinstance(value, dict):
        raise StockError("missing polyline position")
    index = int(_num(value.get("segmentIndex")))
    fraction = _num(value["segmentPosition"] if "segmentPosition" in value else value.get("fraction"))
    if not (0 <= fraction <= 1) or index < 0 or index > edges or (index == edges and fraction != 0):
        raise StockError("polyline position outside geometry")
    return index + fraction


def route_from_snapshot(s):
    if s.get("coordinateFrame", "wgs84") != "wgs84":
        raise StockError("route must be WGS-84")
    if s.get("pointOrder", "lon,lat") != "lon,lat":
        raise StockError("point order must be lon,lat")
    route_id = s.get("routeId")
    if not isinstance(route_id, str) or not route_id:
        raise StockError("routeId")
    points = s.get("points")
    if not isinstance(points, list) or len(points) < 2:
        raise StockError("at least two points")
    lon = [_num(p[0]) for p in points]
    lat = [_num(p[1]) for p in points]
    edges = len(lon) - 1
    sections = []
    if _known(s, "sections"):
        for section in s["sections"]:
            geometry = section.get("geometry") or {}
            metadata = section.get("metadata")
            annotation = metadata.get("annotation") if isinstance(metadata, dict) else None
            time = float("nan")
            weight = metadata.get("weight") if isinstance(metadata, dict) else None
            if isinstance(weight, dict):
                t = weight.get("timeWithTraffic") if weight.get("timeWithTraffic") is not None else weight.get("time")
                if isinstance(t, dict) and isinstance(t.get("value"), (int, float)) and not isinstance(t.get("value"), bool):
                    time = _num(t["value"])
            sections.append({"begin": _position(geometry.get("begin"), edges), "end": _position(geometry.get("end"), edges),
                             "action": annotation.get("action") if isinstance(annotation, dict) else None,
                             "toponym": annotation.get("toponym") if isinstance(annotation, dict) else None,
                             "timeS": time})
    limits = None
    if _known(s, "speedLimits"):
        raw = s["speedLimits"]
        if len(raw) != edges:
            raise StockError("speedLimits: one per edge")
        limits = [None if v is None else _num(v) for v in raw]
    lights = None
    if _known(s, "trafficLights"):
        lights = [_position(row.get("position"), edges) for row in s["trafficLights"]]
    waypoints = None
    if _known(s, "wayPoints"):
        waypoints = [_position(v, edges) for v in s["wayPoints"]]
    cameras = []
    if _known(s, "events"):
        for e in s["events"]:
            if e.get("polylinePosition") is None:
                continue
            tags = [str(t) for t in e.get("tags")] if isinstance(e.get("tags"), list) else []
            limit = e.get("speedLimit")
            cameras.append({"position": _position(e["polylinePosition"], edges),
                            "speedLimitMps": _num(limit) if isinstance(limit, (int, float)) and not isinstance(limit, bool) else float("nan"),
                            "tags": tags})

    def ranges(key):
        if not _known(s, key):
            return None
        out = []
        for row in s[key]:
            sub = row.get("position", row) if "position" in row else row
            b, e = _position(sub.get("begin"), edges), _position(sub.get("end"), edges)
            if not e > b:
                raise StockError(key + ": empty or reversed range")
            out.append((b, e))
        return out
    return {"routeId": route_id, "lon": lon, "lat": lat, "sections": sections, "speedLimits": limits,
            "trafficLights": lights, "tunnels": ranges("tunnels"), "ferries": ranges("ferries"),
            "wayPoints": waypoints, "cameras": cameras}


# ---------------------------------------------------------------- converter (RouteConverter.java)

MIN_LINK_M = 1.0
ROAD_CLASS_NULL = FORMWAY_NULL = LINK_TYPE_NULL = OWNERSHIP_UNKNOWN = -1
LINK_TYPE_FERRY, LINK_TYPE_TUNNEL = 1, 2

_ACTIONS = {"STRAIGHT": (8, 0), "LEFT": (1, 0), "RIGHT": (2, 0),
            "SLIGHT_LEFT": (3, 0), "FORK_LEFT": (3, 0), "EXIT_LEFT": (3, 0),
            "SLIGHT_RIGHT": (4, 0), "FORK_RIGHT": (4, 0), "EXIT_RIGHT": (4, 0),
            "HARD_LEFT": (5, 0), "HARD_RIGHT": (6, 0), "UTURN_LEFT": (7, 0), "UTURN_RIGHT": (7, 0),
            "ENTER_ROUNDABOUT": (11, 0), "LEAVE_ROUNDABOUT": (12, 0), "BOARD_FERRY": (0, 19),
            "LEAVE_FERRY": (0, 20), "WAYPOINT": (0, 35), "FINISH": (0, 36)}
_ICONS = {"STRAIGHT": 9, "LEFT": 2, "RIGHT": 3, "SLIGHT_LEFT": 4, "FORK_LEFT": 4, "EXIT_LEFT": 4,
          "SLIGHT_RIGHT": 5, "FORK_RIGHT": 5, "EXIT_RIGHT": 5, "HARD_LEFT": 6, "HARD_RIGHT": 7,
          "UTURN_LEFT": 8, "UTURN_RIGHT": 19, "ENTER_ROUNDABOUT": 11, "LEAVE_ROUNDABOUT": 12,
          "BOARD_FERRY": 46, "WAYPOINT": 10, "FINISH": 15}


def actions(yandex):
    """Yandex Action -> Amap (MainAction, AssistantAction) for the manoeuvre at a segment's end."""
    return _ACTIONS.get(yandex, (0, 0))


def icon(yandex):
    return _ICONS.get(yandex, 0)


def metres(cumulative, scalar):
    e = min(int(math.floor(scalar)), len(cumulative) - 2)
    return cumulative[e] + (scalar - e) * (cumulative[e + 1] - cumulative[e])


def _sdk_at(route, frame, scalar):
    edges = len(route["lon"]) - 1
    e = min(int(math.floor(scalar)), edges - 1)
    f = scalar - e
    lon = route["lon"][e] + f * (route["lon"][e + 1] - route["lon"][e])
    lat = route["lat"][e] + f * (route["lat"][e + 1] - route["lat"][e])
    return to_sdk(lon, lat, frame)


def wgs_at(route, scalar):
    edges = len(route["lon"]) - 1
    e = min(int(math.floor(scalar)), edges - 1)
    f = scalar - e
    return (route["lon"][e] + f * (route["lon"][e + 1] - route["lon"][e]),
            route["lat"][e] + f * (route["lat"][e + 1] - route["lat"][e]))


def _inside(ranges, s):
    return bool(ranges) and any(b <= s < e for b, e in ranges)


def topo_id(lons, lats):
    text = "".join(f"{x},{y};" for x, y in zip(lons, lats))
    digest = hashlib.sha256(text.encode("ascii")).digest()
    value = int.from_bytes(digest[:8], "big") & ((1 << 63) - 1)
    return value or 1


def whole_metres(exact):
    total = half_up(sum_java(exact))
    floors = [int(math.floor(e)) for e in exact]
    extra = total - sum(floors)
    if extra < 0 or extra > len(exact):
        raise StockError("length rounding")
    order = sorted(range(len(exact)), key=lambda i: (-(exact[i] - math.floor(exact[i])), i))
    for i in order[:extra]:
        floors[i] += 1
    return floors


def sum_java(values):
    total = 0.0
    for v in values:
        total += v
    return total


def convert(route, frame):
    """RouteConverter.convert: the PathInfo (pathId 0) and the placement geometry."""
    if frame not in FRAMES:
        raise StockError("target coordinate frame must be selected explicitly")
    lon, lat = route["lon"], route["lat"]
    edges = len(lon) - 1
    cumulative = [0.0] * (edges + 1)
    for i in range(edges):
        cumulative[i + 1] = cumulative[i] + distance(lon[i], lat[i], lon[i + 1], lat[i + 1])
    total = cumulative[edges]
    if not total > 0:
        raise StockError("route geometry has zero length")
    sections = list(route["sections"]) or [{"begin": 0.0, "end": float(edges), "action": None, "toponym": None, "timeS": float("nan")}]
    previous = 0.0
    for s in sections:
        if abs(s["begin"] - previous) > 1e-9 or not s["end"] > s["begin"]:
            raise StockError("sections not contiguous")
        previous = s["end"]
    if abs(previous - edges) > 1e-9:
        raise StockError("sections do not reach the route end")

    lights = sorted(route["trafficLights"] or [])
    candidates = [(s, 1) for s in lights if 0 < s < edges]
    limits = route["speedLimits"]
    if limits is not None:
        candidates += [(float(i), 0) for i in range(1, edges) if limits[i] != limits[i - 1]]
    for ranges in (route["tunnels"], route["ferries"]):
        for r in ranges or ():
            candidates += [(s, 0) for s in r if 0 < s < edges]
    candidates.sort(key=lambda c: (c[0], -c[1]))

    segments, link_segment, link_in_segment = [], [], []
    link_begin, link_end, link_begin_s, link_end_s = [], [], [], []
    seg_begin, seg_end = [], []
    for si, section in enumerate(sections):
        kept = [(section["begin"], 2)]
        for c in candidates:
            if not (section["begin"] < c[0] < section["end"]):
                continue
            last = kept[-1]
            if c[0] == last[0]:
                continue
            if metres(cumulative, c[0]) - metres(cumulative, last[0]) < MIN_LINK_M:
                if c[1] == 1 and last[1] == 0:
                    kept[-1] = c
                continue
            kept.append(c)
        if len(kept) > 1 and metres(cumulative, section["end"]) - metres(cumulative, kept[-1][0]) < MIN_LINK_M:
            kept.pop()
        kept.append((section["end"], 2))
        nxt = sections[si + 1] if si + 1 < len(sections) else None
        main, assistant = (0, 36) if nxt is None else actions(nxt["action"])
        segment = {"links": [], "length": 0, "travelTime": 0 if math.isnan(section["timeS"]) else half_up(section["timeS"]),
                   "mainAction": main, "assistantAction": assistant, "trafficLightNum": 0}
        exact = []
        for k in range(len(kept) - 1):
            a, c = kept[k][0], kept[k + 1][0]
            scalars = [a] + [float(v) for v in range(int(math.floor(a)) + 1, int(math.ceil(c)))] + [c]
            grid_points = []
            for s in scalars:
                p = _sdk_at(route, frame, s)
                g = (units(p[0]), units(p[1]))
                if not grid_points or grid_points[-1] != g:
                    grid_points.append(g)
            if len(grid_points) == 1:
                grid_points.append(grid_points[0])
            link = new_link()
            link["roadName"] = section["toponym"] or ""
            link["lon"] = [g[0] for g in grid_points]
            link["lat"] = [g[1] for g in grid_points]
            link["topoId"] = topo_id(link["lon"], link["lat"])
            middle = (a + c) / 2
            if _inside(route["tunnels"], middle):
                link["linkType"] = LINK_TYPE_TUNNEL
            elif _inside(route["ferries"], middle):
                link["linkType"] = LINK_TYPE_FERRY
            exact.append(metres(cumulative, c) - metres(cumulative, a))
            segment["links"].append(link)
            link_segment.append(si)
            link_in_segment.append(k)
            link_begin.append(metres(cumulative, a))
            link_end.append(metres(cumulative, c))
            link_begin_s.append(a)
            link_end_s.append(c)
        lengths = whole_metres(exact)
        for link, length in zip(segment["links"], lengths):
            link["length"] = length
        segment["length"] = sum(lengths)
        seg_begin.append(metres(cumulative, section["begin"]))
        seg_end.append(metres(cumulative, section["end"]))
        segments.append(segment)

    all_links = [link for segment in segments for link in segment["links"]]
    for s in lights:
        d = metres(cumulative, s)
        owner, best = -1, float("inf")
        for j in range(len(all_links)):
            gap = abs(link_end[j] - d)
            if gap < best:
                best, owner = gap, j
        if best > MIN_LINK_M:
            owner = -1
            for j in range(len(all_links)):
                if link_begin_s[j] < s <= link_end_s[j]:
                    owner = j
        if d < MIN_LINK_M:
            owner = -1
        if owner < 0:
            continue
        all_links[owner]["hasTrafficLight"] = True
        segments[link_segment[owner]]["trafficLightNum"] += 1
    traffic_lights = []
    for s in lights:
        p = _sdk_at(route, frame, s)
        traffic_lights.append((grid(p[0]), grid(p[1])))
    light_m = None if route["trafficLights"] is None else [metres(cumulative, s) for s in lights]
    tunnel_m, tunnel_ends = [], []
    for b, e in route["tunnels"] or ():
        tunnel_m.append((metres(cumulative, b), metres(cumulative, e)))
        pb, pe = _sdk_at(route, frame, b), _sdk_at(route, frame, e)
        tunnel_ends.append((grid(pb[0]), grid(pb[1]), grid(pe[0]), grid(pe[1])))
    cameras = sorted(((metres(cumulative, c["position"]), half_up(c["speedLimitMps"] * 3.6))
                      for c in route["cameras"]
                      if "SPEED_CONTROL" in c["tags"] and math.isfinite(c["speedLimitMps"]) and c["speedLimitMps"] > 0),
                     key=lambda c: c[0])
    path = {"pathId": 0, "segments": segments, "trafficLights": traffic_lights}
    return {"routeId": route["routeId"], "path": path, "totalM": total, "cumulative": cumulative,
            "linkSegment": link_segment, "linkInSegment": link_in_segment,
            "linkBeginM": link_begin, "linkEndM": link_end, "linkBeginScalar": link_begin_s, "linkEndScalar": link_end_s,
            "segmentBeginM": seg_begin, "segmentEndM": seg_end, "lightM": light_m,
            "tunnelM": tunnel_m, "tunnelEnds": tunnel_ends, "speedCameras": cameras,
            "contentKey": content_key(path)}


def new_link():
    return {"topoId": 0, "roadClass": ROAD_CLASS_NULL, "roadName": "", "length": 0, "linkType": LINK_TYPE_NULL,
            "formway": FORMWAY_NULL, "hasTrafficLight": False, "laneNum": 0, "hasMultiOut": False,
            "mainAction": 0, "assistantAction": 0, "adcode": 0, "ownership": OWNERSHIP_UNKNOWN,
            "hasParallel": False, "direction": 0, "hasMixFork": False, "lon": [], "lat": []}


def content_key(path):
    """Everything the serializer reads except pathId: one key, one link table."""
    parts = []
    for s in path["segments"]:
        parts.append(("S", s["length"], s["travelTime"], s["mainAction"], s["assistantAction"], s["trafficLightNum"]))
        for link in s["links"]:
            parts.append(("L", tuple(sorted((k, tuple(v) if isinstance(v, list) else v) for k, v in link.items()))))
    parts.append(("T", tuple(path["trafficLights"])))
    return hashlib.sha256(repr(parts).encode()).hexdigest()


def link_index(converted, passed):
    begins = converted["linkBeginM"]
    lo, hi = 0, len(begins) - 1
    while lo < hi:
        mid = (lo + hi + 1) >> 1
        if begins[mid] <= passed:
            lo = mid
        else:
            hi = mid - 1
    return lo


def navi_info(converted, passed):
    """Amap NaviInfo of a position: segIdx, linkIdx, globalLink, linkRemain, segRemain, routeRemain, remainLights."""
    link = link_index(converted, passed)
    seg = converted["linkSegment"][link]
    segment = converted["path"]["segments"][seg]
    linkinfo = segment["links"][converted["linkInSegment"][link]]
    clamp = lambda v, lo, hi: max(lo, min(hi, v))
    link_remain = clamp(half_up(converted["linkEndM"][link] - passed), 0, linkinfo["length"])
    seg_remain = clamp(half_up(converted["segmentEndM"][seg] - passed), 0, segment["length"])
    route_remain = max(0, half_up(converted["totalM"] - passed))
    lights = sum(1 for d in converted["lightM"] or () if d > passed)
    return {"segIdx": seg, "linkIdx": converted["linkInSegment"][link], "globalLink": link,
            "linkRemain": link_remain, "segRemain": seg_remain, "routeRemain": route_remain, "remainLights": lights}


class PathIds:
    """Amap-style per-process path ids: 100002, 100003, ...; the same link table keeps its id."""
    def __init__(self, start=100001):
        self.next = start
        self.by_content = {}

    def assign(self, converted):
        key = converted["contentKey"]
        if key not in self.by_content:
            self.next += 1
            self.by_content[key] = self.next
        converted["path"]["pathId"] = self.by_content[key]
        return self.by_content[key]


# ---------------------------------------------------------------- serializer (StockRouteJson.java)

STRINGS = {
    "EN": {"driving": "travel", "space": " ", "km": "km", "meter": "meter(s)", "enter": "get into",
           "noNameRoad": "Nameless Road",
           "actions": ["Straight ahead", "Turn left", "Turn right", "Drive forward to the left", "Drive right ahead",
                       "Turn left and back", "Turn right back", "Turn left and turn around", "Enter the roundabout",
                       "Drive out of the roundabout", "Passing through the service", "area"]},
    "DEFAULT": {"driving": "行驶", "space": "", "km": "公里", "meter": "米", "enter": "进入", "noNameRoad": "无名道路",
                "actions": ["直行", "左转", "右转", "向左前方行驶", "向右前方行驶", "向左后转", "向右后转", "左转调头",
                            "进入环岛", "驶出环岛", "经过服务区", "经过收费站"]},
}
UNNAMED = "无名道路"
CHUNK, CHUNK_COUNT_DIVISOR = 524288, 524264


def route_type(amap, resend):
    if resend:
        return 14
    table = {16: 99, 28: 99, 17: 17, 26: 17, 23: 9, 35: 35, 998: 98, 1: 1, 2: 2, 3: 3, 4: 4, 5: 99, 6: 99, 7: 99,
             9: 9, 10: 99, 11: 99, 13: 99, 12: 12, 14: 14}
    return table.get(amap, 50)


def display_action(main, assistant):
    if assistant == 5:
        return 16
    special = {33: 13, 34: 14, 35: 10, 36: 15}
    if assistant in special:
        return special[assistant]
    return {1: 2, 2: 3, 3: 4, 9: 4, 4: 5, 10: 5, 5: 6, 6: 7, 7: 8, 11: 11, 12: 12}.get(main, 9)


def _action_text(action, s):
    index = {2: 1, 3: 2, 4: 3, 5: 4, 6: 5, 7: 6, 8: 7, 11: 8, 12: 9, 13: 10, 14: 11}.get(action, 0)
    return s["actions"][index]


def _distance_text(length, s):
    metres_ = java_int(length) if not isinstance(length, int) else length
    if metres_ >= 1000:
        km, tenth = metres_ // 1000, (metres_ % 1000) // 100
        text = f"{km}.{tenth}" if km < 10 and tenth > 0 else str(km)
        return text + " " + s["km"]
    return str(metres_) + " " + s["meter"]


def description(segment, next_road, s):
    action = display_action(segment["mainAction"], segment["assistantAction"])
    out = s["driving"] + s["space"] + _distance_text(segment["length"], s) + s["space"] + _action_text(action, s)
    road = next_road
    if not road and action not in (11, 12):
        road = s["noNameRoad"]
    if road:
        out += s["space"] + s["enter"] + s["space"] + road
    return out


def _last_road_name(segment):
    name = ""
    for link in segment["links"]:
        name = link["roadName"]
    return name if name else UNNAMED


def _int32(v):
    v &= 0xFFFFFFFF
    return v - (1 << 32) if v >= 1 << 31 else v


def navi_to_ehp(path, strings):
    links, pnts, counts = [], [], [0, 0]
    segments = path["segments"]
    groups = []
    group = _group(path, 0, len(segments), UNNAMED, links, pnts, counts, strings)
    if segments:
        groups.append(group)
    lights = [{"x": D(x), "y": D(y)} for x, y in path["trafficLights"]]
    return {"guideGroups": groups, "linkCnt": counts[0], "links": links, "pathID": path["pathId"],
            "pntCnt": counts[1], "pnts": pnts, "trafficLights": lights, "viaRoadInfo": []}


def _group(path, start, count, name, links, pnts, counts, strings):
    light_count = time = length = 0
    icon_type = 0
    enter = None
    segments = []
    for i in range(count):
        segment = path["segments"][start + i]
        light_count = _int32(light_count + segment["trafficLightNum"])
        time = _int32(time + segment["travelTime"])
        length = _int32(length + segment["length"])
        if i == 0:
            icon_type = segment["mainAction"]
            if segment["links"] and segment["links"][0]["lon"]:
                enter = {"x": D(segment["links"][0]["lon"][0] / 3600000.0), "y": D(segment["links"][0]["lat"][0] / 3600000.0)}
        segments.append(_segment(path, start + i, links, pnts, counts, strings))
    return {"groupIconType": icon_type, "groupLen": length, "groupName": name, "groupTime": time,
            "groupTrafficLightsCount": light_count, "groupEnterCoord": enter, "segments": segments}


def _segment(path, index, links, pnts, counts, strings):
    segment = path["segments"][index]
    next_road = ""
    if index + 1 < len(path["segments"]) and path["segments"][index + 1]["links"]:
        next_road = _last_road_name(path["segments"][index + 1])
    out = {"crntSegmLinkCnt": len(segment["links"]), "description": description(segment, next_road, strings),
           "isArriveWayPoint": segment["assistantAction"] in (35, 36), "linkBegIdx": counts[0],
           "navigationAssitAction": segment["assistantAction"], "navigationLen": _int32(segment["length"]),
           "navigationMainAction": segment["mainAction"], "navigationNextRoadName": next_road,
           "trafficLightNum": segment["trafficLightNum"]}
    for link in segment["links"]:
        counts[0] += 1
        links.append(_link(link, counts[1]))
        for x, y in zip(link["lon"], link["lat"]):
            counts[1] += 1
            pnts.append({"x": D(x / 3600000.0), "y": D(y / 3600000.0)})
    return out


def _link(link, pnt_beg):
    ownership = 1 if 1 <= link["ownership"] <= 4 else link["ownership"]
    return {"adminCode": _int32(link["adcode"]), "assistantAction": link["assistantAction"], "direction": link["direction"],
            "formway": link["formway"], "hasMixFork": link["hasMixFork"], "hasMultiOut": link["hasMultiOut"],
            "hasParallel": link["hasParallel"], "hasTrafficLight": link["hasTrafficLight"], "laneNum": link["laneNum"],
            "len": F(link["length"]), "linkID": link["topoId"], "linktype": link["linkType"], "mainAction": link["mainAction"],
            "ownership": ownership, "pntBegIdx": pnt_beg, "pntCnt": len(link["lon"]), "roadclass": link["roadClass"],
            "roadname": link["roadName"], "speedLimit": 0}


def message_send(path, payload_route_type, strings="EN"):
    """k.g(): MessageSend JSON for one path, endPOIInfo PTPStateFlag 1, mapProvider 1."""
    s = STRINGS[strings] if isinstance(strings, str) else strings
    send = {"code": 200, "data": [navi_to_ehp(path, s)], "endPOIInfo": {"AVPMapIdList": [], "PTPStateFlag": 1},
            "mapProvider": 1, "msg": "response", "RouteType": payload_route_type}
    return gson(send)


def chunks(payload, path_count, now_ms):
    """n.a(): NaviDataDetail strings in send order (raises IndexError where Java's substring throws)."""
    length = java_length(payload)
    if length + 24 < CHUNK:
        return [_detail(0, 1, path_count, payload, now_ms)]
    count = length // CHUNK_COUNT_DIVISOR + (0 if length % CHUNK_COUNT_DIVISOR == 0 else 1)
    out = []
    for i in range(count):
        part = java_substring(payload, i * CHUNK, (i + 1) * CHUNK) if i < count - 1 else java_substring(payload, i * CHUNK)
        out.append(_detail(i, count, path_count, part, now_ms))
    return out


def _detail(index, length, path_count, payload, time):
    return gson({"currentIndex": index, "length": length, "pathCount": path_count, "payload": payload, "time": time})


# ---------------------------------------------------------------- golden (Golden.java)

def path_from_payload(payload_text):
    """The PathInfo a stock NaviToEHPData was made from (all group time on segment 0)."""
    send = json.loads(payload_text)
    data = send["data"][0]
    group = data["guideGroups"][0]
    links, pnts = data["links"], data["pnts"]
    path = {"pathId": data["pathID"], "segments": [], "trafficLights": [(t["x"], t["y"]) for t in data["trafficLights"]]}
    for i, s in enumerate(group["segments"]):
        segment = {"links": [], "length": s["navigationLen"], "travelTime": group["groupTime"] if i == 0 else 0,
                   "mainAction": s["navigationMainAction"], "assistantAction": s["navigationAssitAction"],
                   "trafficLightNum": s["trafficLightNum"]}
        for l in links[s["linkBegIdx"]:s["linkBegIdx"] + s["crntSegmLinkCnt"]]:
            link = new_link()
            link.update(topoId=l["linkID"], roadClass=l["roadclass"], roadName=l["roadname"], length=int(l["len"]),
                        linkType=l["linktype"], formway=l["formway"], hasTrafficLight=l["hasTrafficLight"], laneNum=l["laneNum"],
                        hasMultiOut=l["hasMultiOut"], mainAction=l["mainAction"], assistantAction=l["assistantAction"],
                        adcode=l["adminCode"], ownership=l["ownership"], hasParallel=l["hasParallel"],
                        direction=l["direction"], hasMixFork=l["hasMixFork"])
            pts = pnts[l["pntBegIdx"]:l["pntBegIdx"] + l["pntCnt"]]
            link["lon"] = [java_round(p["x"] * SCALE) for p in pts]
            link["lat"] = [java_round(p["y"] * SCALE) for p in pts]
            segment["links"].append(link)
        path["segments"].append(segment)
    return path, send["RouteType"]


def someip_frame(header16, detail_json, path_count):
    """The IVI's native SOME/IP struct of a Binder route event: CRC, counter, count, length, BOM, JSON, NUL."""
    text = b"\xef\xbb\xbf" + detail_json.encode("utf-8") + b"\0"
    body = struct.pack("!IBHI", 0, 0, path_count, len(text)) + text
    return header16[:4] + struct.pack("!I", len(body) + 8) + header16[8:16] + body


def parse_someip_route(data):
    if len(data) < 27 or struct.unpack("!HH", data[:4]) != (43, 0x8002):
        raise StockError("not a 43/0x8002 route")
    size = struct.unpack("!I", data[23:27])[0]
    string = data[27:27 + size]
    if string[:3] != b"\xef\xbb\xbf" or string[-1:] != b"\0":
        raise StockError("no BOM/NUL")
    return data[:16], string[3:-1].decode("utf-8")


# ---------------------------------------------------------------- protobuf (Pb.java)

DOUBLE, FLOAT, INT32, UINT32, UINT64, BOOL, STRING, BYTES, MESSAGE, MESSAGE_LIST = range(10)


class Msg:
    def __init__(self, name, *fields):
        self.name, self.fields = name, fields
        numbers = [f[0] for f in fields]
        if numbers != sorted(set(numbers)):
            raise StockError(name + ": fields out of order")

    def encode(self, *values):
        if len(values) != len(self.fields):
            raise StockError(f"{self.name}: {len(values)} values for {len(self.fields)} fields")
        out = bytearray()
        for (number, kind, _name, _msg), value in zip(self.fields, values):
            _write(out, number, kind, value)
        return bytes(out)

    def decode(self, data):
        by_number = {f[0]: f for f in self.fields}
        result, at = {}, 0
        while at < len(data):
            tag, at = _read_varint(data, at)
            number, wire = tag >> 3, tag & 7
            if number not in by_number:
                raise StockError(f"{self.name}: unknown field {number}")
            _, kind, name, msg = by_number[number]
            if wire == 0:
                raw, at = _read_varint(data, at)
                if kind == BOOL:
                    value = raw != 0
                elif kind == UINT32:
                    value = raw & 0xFFFFFFFF
                elif kind == INT32:
                    value = _int32(raw)
                else:
                    value = raw
            elif wire == 1:
                value = struct.unpack("<d", data[at:at + 8])[0]
                at += 8
            elif wire == 5:
                value = float(struct.unpack("<f", data[at:at + 4])[0])
                at += 4
            elif wire == 2:
                size, at = _read_varint(data, at)
                if at + size > len(data):
                    raise StockError(f"{self.name}: truncated field {number}")
                raw = data[at:at + size]
                at += size
                if kind == STRING:
                    value = raw.decode("utf-8")
                elif kind in (MESSAGE, MESSAGE_LIST):
                    value = msg.decode(raw)
                else:
                    value = raw.hex()
            else:
                raise StockError(f"{self.name}: wire type {wire}")
            if kind == MESSAGE_LIST:
                result.setdefault(name, []).append(value)
            else:
                if name in result:
                    raise StockError(f"{self.name}: repeated field {number}")
                result[name] = value
        return result


def _varint(out, value):
    value &= (1 << 64) - 1
    while value > 0x7F:
        out.append((value & 0x7F) | 0x80)
        value >>= 7
    out.append(value)


def _read_varint(data, at):
    value = shift = 0
    while shift < 70:
        if at >= len(data):
            raise StockError("truncated varint")
        b = data[at]
        at += 1
        value |= (b & 0x7F) << shift
        if b < 0x80:
            return value, at
        shift += 7
    raise StockError("oversized varint")


def _tag(out, number, wire):
    _varint(out, (number << 3) | wire)


def _write(out, number, kind, value):
    if value is None:
        return
    if kind == DOUBLE:
        bits = struct.pack("<d", float(value))
        if bits == b"\0" * 8:
            return
        _tag(out, number, 1)
        out += bits
    elif kind == FLOAT:
        bits = struct.pack("<f", float(value))
        if bits == b"\0" * 4:
            return
        _tag(out, number, 5)
        out += bits
    elif kind == UINT32:
        v = int(value) & 0xFFFFFFFF
        if v:
            _tag(out, number, 0)
            _varint(out, v)
    elif kind == INT32:
        v = _int32(int(value))
        if v:
            _tag(out, number, 0)
            _varint(out, v)
    elif kind == UINT64:
        v = int(value) & ((1 << 64) - 1)
        if v:
            _tag(out, number, 0)
            _varint(out, v)
    elif kind == BOOL:
        if value:
            _tag(out, number, 0)
            out.append(1)
    elif kind in (STRING, BYTES):
        data = value.encode("utf-8") if kind == STRING else bytes(value)
        if data:
            _tag(out, number, 2)
            _varint(out, len(data))
            out += data
    elif kind == MESSAGE:
        _tag(out, number, 2)
        _varint(out, len(value))
        out += value
    elif kind == MESSAGE_LIST:
        for item in value:
            _tag(out, number, 2)
            _varint(out, len(item))
            out += item
    else:
        raise StockError(f"type {kind}")


def f(number, kind, name):
    return (number, kind, name, None)


def m(number, name, msg):
    return (number, MESSAGE, name, msg)


SD_VEHICLE_LOCATION = Msg("sdVehicleLocation",
    f(1, DOUBLE, "locationLatitude"), f(2, DOUBLE, "locationLongitude"), f(3, DOUBLE, "locationLatitudeAssociateRoad"),
    f(4, DOUBLE, "locationLongitudeAssociateRoad"), f(5, UINT32, "vehicleSpeed"), f(6, UINT32, "GPSspeed"),
    f(7, DOUBLE, "curSDRouteID"), f(8, UINT32, "reserve4"), f(9, UINT32, "curStepId"), f(10, UINT32, "curLinkId"),
    f(11, UINT32, "linkOffset"), f(12, UINT32, "distanceToNextStep"), f(13, UINT32, "currentRoadOwnership"),
    f(14, UINT32, "reserve2"), f(15, FLOAT, "reserve3"))
SD_VEHICLE_LOCATION_NOTIFY = Msg("sdVehicleLocationNotify", m(1, "sdVehicleLocation", SD_VEHICLE_LOCATION))
REGIONAL_AND_WEATHER = Msg("regionalAndWeather",
    f(1, UINT64, "regionId"), f(2, UINT64, "countryCode"), f(3, UINT64, "provinceCode"), f(4, UINT64, "cityCode"),
    f(5, UINT32, "weatherType"), f(6, UINT64, "reserve1"), f(7, UINT64, "reserve2"), f(8, DOUBLE, "reserve3"))
REGIONAL_AND_WEATHER_NOTIFY = Msg("regionalAndWeatherNotify", m(1, "regionalAndWeather", REGIONAL_AND_WEATHER))
TUNNEL = Msg("tunnel", f(1, UINT32, "tunneStates"), f(2, DOUBLE, "tunnelStartPiointLat"), f(3, DOUBLE, "tunnelStartPiointLon"),
    f(4, DOUBLE, "tunnelEndPiointLat"), f(5, DOUBLE, "tunnelEndPiointLon"), f(6, UINT32, "toTunnelDist"),
    f(7, UINT64, "reserve1"), f(8, UINT32, "reserve2"), f(9, FLOAT, "reserve3"))
TUNNEL_NOTIFY = Msg("tunnelNotify", m(1, "tunnel", TUNNEL))
SD_TRAFFIC_INCIDENT_ITEM = Msg("sdTraffiIncidentItem",
    f(1, UINT32, "naviCongestionInfo"), f(2, UINT32, "occupiedLane"), f(3, DOUBLE, "cnstrctnCrdLatitude"),
    f(4, DOUBLE, "cnstrctnCrdLongitude"), f(5, UINT64, "naviCongestionDistLen"), f(6, UINT32, "occupiedLaneDtl"), f(7, FLOAT, "reserve3"))
SD_TRAFFIC_INCIDENT_NOTIFY = Msg("sdTraffiIncidentNotify", (1, MESSAGE_LIST, "sdTraffiIncident", SD_TRAFFIC_INCIDENT_ITEM))
SD_TRAFFIC_LIGHT = Msg("sdTrafficLight",
    f(1, UINT32, "trafficLightExist"), f(2, DOUBLE, "trafficLightLat"), f(3, DOUBLE, "trafficLightLon"),
    f(4, UINT32, "lightStateType"), f(5, UINT64, "startTime"), f(6, UINT64, "endTime"), f(7, UINT32, "lightDir"),
    f(8, UINT32, "lightWaitNum"), f(9, UINT64, "reserve1"), f(10, UINT32, "trafficLightDist"), f(11, FLOAT, "reserve3"))
SD_TRAFFIC_LIGHT_NOTIFY = Msg("sdTrafficLightNotify", m(1, "sdTrafficLight", SD_TRAFFIC_LIGHT))
TIDAL_LANE = Msg("tidalLane", f(1, UINT32, "tidalLane"), f(2, UINT32, "rampRmnd"), f(3, UINT64, "reserve1"),
    f(4, UINT32, "reserve2"), f(5, FLOAT, "reserve3"))
TIDAL_LANE_NOTIFY = Msg("tidalLaneNotify", m(1, "tidalLane", TIDAL_LANE))
NAVI_ACTION_AND_CAMERA = Msg("naviActionAndCamera",
    f(1, UINT32, "iconType"), f(2, UINT32, "mainAction"), f(3, UINT32, "assistantAction"), f(4, UINT32, "distance"),
    f(5, UINT32, "cameraType"), f(6, UINT32, "cameraDistance"), f(7, UINT64, "reserve1"), f(8, UINT64, "reserve2"), f(9, DOUBLE, "reserve3"))
NAVI_ACTION_AND_CAMERA_NOTIFY = Msg("naviActionAndCameraNotify", m(1, "naviActionAndCamera", NAVI_ACTION_AND_CAMERA))
SPEED_INTERSECTION = Msg("speedIntersectionInformation",
    f(1, UINT32, "trafficFlowSpeed"), f(2, UINT32, "historySpeed"), f(3, UINT32, "hasParallelRoad"), f(4, UINT32, "hasMixFork"),
    f(5, UINT32, "hasLongSolidLane"), f(6, UINT32, "assistantActionDistance"), f(7, STRING, "naviVoiceCommand"),
    f(8, UINT64, "reserve1"), f(9, UINT64, "reserve2"), f(10, DOUBLE, "reserve3"))
SPEED_INTERSECTION_NOTIFY = Msg("speedIntersectionInformationNotify", m(1, "speedIntersectionInformation", SPEED_INTERSECTION))
AHEAD_LANES_NOTIFY = Msg("aheadIntersectionsLanesInfoNotify", f(1, STRING, "aheadIntersectionsLanesInfo"))
NAVIGATION_STATUS = Msg("NavigationStatus_LinkInfoNotifyStruct",
    f(1, UINT32, "checksum"), f(2, UINT32, "counter"), f(3, DOUBLE, "timestamp"), f(4, UINT32, "navigationStatus"),
    f(5, UINT32, "matchingTableStatus"), f(6, UINT32, "remainDistance"), f(7, UINT32, "viaPointDistance"),
    f(8, UINT32, "hDStartDistance"), f(9, UINT32, "dNPSwitch"), f(10, UINT32, "aNPRoad"), f(11, UINT32, "mapVersion"))
NAVIGATION_STATUS_NOTIFY = Msg("NavigationStatus_LinkInfoNotify", m(1, "navigationStatusLinkInfoNotifyStruct", NAVIGATION_STATUS))
TRAFFIC_INFO = Msg("TrafficInfo",
    f(1, UINT32, "trafficJamDist"), f(2, UINT32, "distToStartTrafficJam"), f(3, UINT32, "trafficJamStatus"), f(4, UINT32, "passTime"),
    f(5, UINT32, "cnstrctnRmnd"), f(6, DOUBLE, "cnstrctnCrdLatitude"), f(7, DOUBLE, "cnstrctnCrdLongitude"),
    f(8, UINT32, "spdLmtSpeedValue"), f(9, UINT32, "spdLmtEleEyeSpeedValue"), f(10, UINT32, "spdLmtEleEyeDist"),
    f(11, DOUBLE, "intervalCameraStartPointLon"), f(12, DOUBLE, "intervalCameraStartPointLat"),
    f(13, DOUBLE, "intervalCameraEndPointLon"), f(14, DOUBLE, "intervalCameraEndPointLat"),
    f(15, UINT32, "intervalCameraSpeedValue"), f(16, BOOL, "offCourse"), f(17, UINT32, "distToDsttn"),
    f(18, UINT32, "timeToDsttn"), f(19, UINT32, "roadClass"))
TRAFFIC_INFO_NOTIFY = Msg("TrafficInfoNotify", m(1, "trafficInfo", TRAFFIC_INFO))
ADV_AUTO_FUN_INFO = Msg("AdvAutoFunInfo", f(1, UINT32, "noODDRegionDist"))
ADV_AUTO_FUN_INFO_NOTIFY = Msg("AdvAutoFunInfoNotify", m(1, "advAutoFunInfo", ADV_AUTO_FUN_INFO))
GENERAL_NAVIGATION = Msg("genernalNavigation",
    f(1, UINT32, "intervalCameraSpeedDisttoStart"), f(2, UINT32, "intervalCameraSpeedDisttoEnd"), f(3, UINT32, "newNoODDRegionDist"),
    f(4, UINT64, "reserve1"), f(5, UINT32, "reserve2"), f(6, DOUBLE, "reserve3"), f(7, DOUBLE, "reserve4"),
    f(8, DOUBLE, "reserve5"), f(9, DOUBLE, "reserve6"), f(10, DOUBLE, "reserve7"))
GENERAL_NAVIGATION_NOTIFY = Msg("genernalNavigationNotify", m(1, "genernalNavigation", GENERAL_NAVIGATION))
NAVI_SD_ROUTE = Msg("naviSDRouteStruct", f(1, UINT32, "checksCRC32"), f(2, UINT32, "counter"),
    f(3, UINT32, "navigationSDLink2Num"), f(4, STRING, "navigationSDLink2"))
NAVI_SD_ROUTE_NOTIFY = Msg("naviSDRouteNotify", m(1, "naviSDRouteStruct", NAVI_SD_ROUTE))
PATH_SELECT = Msg("NaviPathUserSelectStsStruct", f(1, UINT32, "checksCRC32"), f(2, UINT32, "counter"),
    f(3, UINT64, "pathIDReq"), f(4, UINT64, "reserve1"), f(5, UINT32, "reserve2"), f(6, FLOAT, "reserve3"))
PATH_SELECT_NOTIFY = Msg("NaviPathUserSelectStsNotify", m(1, "naviPathUserSelectStsStruct", PATH_SELECT))
PATH_SELECT_CONFIRM = Msg("NaviPathUserSelectStsConfirmStruct", f(1, UINT32, "checksCRC32"), f(2, UINT32, "counter"),
    f(3, UINT64, "pathIDResp"), f(4, INT32, "statusCode"), f(5, STRING, "errorMsg"), f(6, UINT64, "reserve1"),
    f(7, UINT32, "reserve2"), f(8, FLOAT, "reserve3"))
PATH_SELECT_CONFIRM_NOTIFY = Msg("NaviPathUserSelectStsConfirmNotify", m(1, "naviPathUserSelectStsConfirmStruct", PATH_SELECT_CONFIRM))


def _event(service, ident):
    return (4 << 48) | (service << 32) | (service << 16) | ident


TOPICS = {
    _event(7, 0x8001): ("NavigationStatus", NAVIGATION_STATUS_NOTIFY),
    _event(7, 0x8003): ("TrafficInfo", TRAFFIC_INFO_NOTIFY),
    _event(7, 0x8004): ("AdvAutoFunInfo", ADV_AUTO_FUN_INFO_NOTIFY),
    _event(7, 0x8006): ("genernalNavigation", GENERAL_NAVIGATION_NOTIFY),
    _event(43, 0x8002): ("naviSDRoute", NAVI_SD_ROUTE_NOTIFY),
    _event(45, 0x8001): ("pathSelected", PATH_SELECT_NOTIFY),
    _event(0x8202, 0x8001): ("sdTrafficLight", SD_TRAFFIC_LIGHT_NOTIFY),
    _event(0x8202, 0x8002): ("sdTraffiIncident", SD_TRAFFIC_INCIDENT_NOTIFY),
    _event(0x8202, 0x8004): ("tunnel", TUNNEL_NOTIFY),
    _event(0x8202, 0x8007): ("tidalLane", TIDAL_LANE_NOTIFY),
    _event(0x8202, 0x8008): ("sdVehicleLocation", SD_VEHICLE_LOCATION_NOTIFY),
    _event(0x8202, 0x8009): ("regionalAndWeather", REGIONAL_AND_WEATHER_NOTIFY),
    _event(0x8202, 0x800A): ("speedIntersectionInformation", SPEED_INTERSECTION_NOTIFY),
    _event(0x8202, 0x800B): ("naviActionAndCamera", NAVI_ACTION_AND_CAMERA_NOTIFY),
    _event(0x8202, 0x800E): ("aheadIntersectionsLanesInfo", AHEAD_LANES_NOTIFY),
    _event(44, 0x8002): ("MMTPathSelected", PATH_SELECT_CONFIRM_NOTIFY),
}
TOPIC_NAVIGATION_STATUS = _event(7, 0x8001)
TOPIC_TRAFFIC_INFO = _event(7, 0x8003)
TOPIC_ROUTE = _event(43, 0x8002)
TOPIC_PATH_SELECTED = _event(45, 0x8001)
TOPIC_VEHICLE_LOCATION = _event(0x8202, 0x8008)


def decode_topic(topic, payload):
    entry = TOPICS.get(topic)
    return None if entry is None else entry[1].decode(payload)


# ---------------------------------------------------------------- senders (StockMessages.java)

def vehicle_location(lon, lat, road_lon, road_lat, vehicle_speed, gps_speed, course, path_id, seg_idx, link_idx,
                     seg_dist, link_remain, ownership):
    inner = SD_VEHICLE_LOCATION.encode(lat, lon, road_lat, road_lon, vehicle_speed, gps_speed, float(path_id), course,
                                       seg_idx, link_idx, link_remain, seg_dist, ownership, None, None)
    return SD_VEHICLE_LOCATION_NOTIFY.encode(inner)


def traffic_info(jam_length, dist_to_jam, jam_status, pass_time, construction, c_lon, c_lat, road_speed, eye_speed, eye_dist,
                 i_start_lon, i_start_lat, i_end_lon, i_end_lat, i_speed, off_course, dist_to_dest, time_to_dest, road_class):
    inner = TRAFFIC_INFO.encode(jam_length, dist_to_jam, jam_status, pass_time, construction, c_lat, c_lon, road_speed,
                                eye_speed, eye_dist, i_start_lon, i_start_lat, i_end_lon, i_end_lat, i_speed, off_course,
                                dist_to_dest, time_to_dest, road_class)
    return TRAFFIC_INFO_NOTIFY.encode(inner)


def navigation_status(state):
    return NAVIGATION_STATUS_NOTIFY.encode(NAVIGATION_STATUS.encode(None, None, None, state, None, None, None, None, None, None, None))


def region(region_id, country, province, city, weather):
    return REGIONAL_AND_WEATHER_NOTIFY.encode(REGIONAL_AND_WEATHER.encode(region_id, country, province, city, weather, None, None, None))


def tunnel(dist, state, start_lon, start_lat, end_lon, end_lat):
    return TUNNEL_NOTIFY.encode(TUNNEL.encode(state, start_lat, start_lon, end_lat, end_lon, dist, None, None, None))


def incidents(items):
    return SD_TRAFFIC_INCIDENT_NOTIFY.encode([SD_TRAFFIC_INCIDENT_ITEM.encode(t, lane, lat, lon, None, None, None)
                                             for t, lane, lon, lat in items])


def traffic_light(exist, lon, lat, state, start, end, direction, wait):
    return SD_TRAFFIC_LIGHT_NOTIFY.encode(SD_TRAFFIC_LIGHT.encode(exist, lat, lon, state, start, end, direction, wait, None, None, None))


def tidal_lane(ramp, tidal):
    return TIDAL_LANE_NOTIFY.encode(TIDAL_LANE.encode(tidal, ramp, None, None, None))


def action_and_camera(icon_, main, assistant, dist, camera_type, camera_dist):
    return NAVI_ACTION_AND_CAMERA_NOTIFY.encode(NAVI_ACTION_AND_CAMERA.encode(icon_, main, assistant, dist, camera_type, camera_dist, None, None, None))


def speed_intersection(flow, history, parallel, mix, solid, dist, voice):
    return SPEED_INTERSECTION_NOTIFY.encode(SPEED_INTERSECTION.encode(flow, history, parallel, mix, solid, dist, voice, None, None, None))


def ahead_lanes(text):
    return AHEAD_LANES_NOTIFY.encode(text)


def general_navigation(to_start, to_end):
    return GENERAL_NAVIGATION_NOTIFY.encode(GENERAL_NAVIGATION.encode(to_start, to_end, -1, None, None, None, None, None, None, None))


def no_odd_region_distance(dist):
    return ADV_AUTO_FUN_INFO_NOTIFY.encode(ADV_AUTO_FUN_INFO.encode(dist))


def route(chunk, path_count):
    return NAVI_SD_ROUTE_NOTIFY.encode(NAVI_SD_ROUTE.encode(None, None, path_count, chunk))


def path_selected(path_id):
    return PATH_SELECT_NOTIFY.encode(PATH_SELECT.encode(None, None, path_id, None, None, None))


def path_select_confirm(path_id, status):
    return PATH_SELECT_CONFIRM_NOTIFY.encode(PATH_SELECT_CONFIRM.encode(None, None, path_id, status, None, None, None, None))


# ---------------------------------------------------------------- CLI

def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    conv = sub.add_parser("convert")
    conv.add_argument("snapshot")
    conv.add_argument("--out", required=True)
    conv.add_argument("--frame", choices=FRAMES, default="native-gcj-unrestricted-candidate")
    conv.add_argument("--path-id", type=int, default=100002)
    conv.add_argument("--route-type", type=int, default=1)
    args = parser.parse_args()
    try:
        snapshot = json.loads(Path(args.snapshot).read_text(encoding="utf-8"))
        converted = convert(route_from_snapshot(snapshot), args.frame)
        converted["path"]["pathId"] = args.path_id
        text = message_send(converted["path"], args.route_type)
        Path(args.out).write_bytes(text.encode("utf-8"))
        links = sum(len(s["links"]) for s in converted["path"]["segments"])
        print(json.dumps({"out": args.out, "segments": len(converted["path"]["segments"]), "links": links,
                          "chars": java_length(text)}))
    except (StockError, ValueError, KeyError, TypeError) as exc:
        parser.exit(2, f"invalid input: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
