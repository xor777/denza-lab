#!/usr/bin/env python3
"""Parked ADAS experiment scenarios: a semantic description of one complete parked feed.

Each scenario is a full description of what the on-device player
(`experiments/adas-parked-player-probe`) fires toward ADAS while the car is parked at the
start position: the route body, the selection, the NavigationStatus sequence (with the stock's
100 -> 106 -> 101 phase), the ODD region request the stock sends before the route, and the 1 Hz
dynamic feed (position, TrafficInfo, region, light, tunnel, speedIntersection, AdvAutoFunInfo,
tidal lane, next action). Nothing here touches a device; it only writes scenario files and prints
timelines.

The scenarios form a chain. H0 is the positive control: the stock map's own recorded start feed,
re-stamped with a fresh pathId. Every later scenario is derived from its predecessor by changing
exactly **one** declared variable toward what our live bridge sent on 2026-10-24
(`captures/adas-live-drive-20261024`), so a single run isolates the cause of the missing NOA
offer. The chain and its single-variable discipline are asserted mechanically by
`tools/adas_bridge/tests/test_scenario.py`.

| id   | changed toward the bridge                                  | source |
| ---- | ---------------------------------------------------------- | ------ |
| H0   | (none - the stock feed verbatim)                           | ANALYSIS A, the stock route/wire capture |
| H1   | coordinate frame GCJ -> WGS-84 (route points + positions)  | ANALYSIS A.3 |
| H2a  | link roadclass/formway -> -1                               | ANALYSIS A.2 / R01,R02 |
| H2b  | link ownership -> -1                                       | R03 |
| H2c  | link direction -> 0                                        | R04 |
| H2d  | link laneNum -> 0                                           | R05 |
| H2e  | link adminCode -> 0 and Region -> zeros                    | R07 / G01 |
| H2f  | position ownership -> -1 and TrafficInfo roadClass -> -1   | P01 / T01 |
| H2g  | TrafficInfo legal speed limit -> non-zero (20)            | T02 |
| H3   | no ODD request and no 106 phase (our protocol)             | ANALYSIS A.2, D |
| H4   | 5 Hz dynamic rates                                         | ANALYSIS A.2 |
| H5   | the remaining bridge field values (historySpeed, light)    | ANALYSIS A.2 / S01, X01 |
| H6   | H0 + the stock SETTING_MPC_IFC_NAVI_ST_SET side-signal     | research README "A second stock channel" |

H6 branches from H0 (not from H5): it is the positive control plus the one stock side channel
that the map SOME/IP feed does not carry.

The dynamic field values are the recorded ones, with provenance in comments. The route body for H0
is reproduced from the stock wire capture through the byte-exact `stock.py` oracle, so H0's route
JSON equals the recording except its pathId and time. H6 reuses H0's body.
"""
from __future__ import annotations

import argparse
import copy
import json
from pathlib import Path

from . import stock

ROOT = Path(__file__).resolve().parents[2]
SCHEMA = "denza-adas-parked-scenario-v1"

# The recorded stock start feed. captures/ is git-ignored and lives in the main checkout, not in a
# worktree, so resolve it against the main repo root when this tool runs from a worktree.
STOCK_ROUTE_REL = "captures/stock-map-car-lanes-20261023/reports/route-wire/first-full/route-01.wire-json.txt"


def _capture_roots():
    roots = [ROOT]
    marker = "/.claude/worktrees/"
    text = str(ROOT)
    if marker in text:
        roots.append(Path(text[: text.index(marker)]))
    return roots


def find_capture(rel):
    """The first existing capture path (worktree, then the main checkout), or None."""
    for root in _capture_roots():
        candidate = root / rel
        if candidate.exists():
            return candidate
    return None


def stock_route_payload_path():
    path = find_capture(STOCK_ROUTE_REL)
    if path is None:
        raise FileNotFoundError(
            "stock route capture not found; expected " + STOCK_ROUTE_REL + " under the repo root")
    return path

# The canonical pathId placeholder the player replaces with a fresh id per run.
PATH_ID_TOKEN = 100002

GCJ = "native-gcj-unrestricted-candidate"
WGS84 = "wgs84"

# ---- the stock start feed, field for field (ANALYSIS A.2 and the first-full / resend wire) ----

# Vehicle location 0x8202/0x8008: matched point and associate road in the stock GCJ frame.
STOCK_POSITION = {
    "frame": GCJ,
    # matched point (locationLatitude / locationLongitude)
    "lat": 55.51775138888889, "lon": 37.07066361111111,
    # associate-road point (locationLatitudeAssociateRoad / ...Longitude...)
    "roadLat": 55.517721944444446, "roadLon": 37.07063083333333,
    "vehicleSpeed": 0, "gpsSpeed": 0, "course": 0,
    "segIdx": 0, "linkIdx": 0, "segDist": 38, "linkRemain": 38,
    "ownership": 0,        # stock parked matched ownership 0; bridge sends -1 (P01)
}

# TrafficInfo 7/0x8003.
STOCK_TRAFFIC = {
    "jamLength": 0, "distToJam": -1, "jamStatus": 0, "passTime": 0, "construction": 0,
    "cLon": 0.0, "cLat": 0.0,
    "roadClass": 9,              # stock real class; bridge -1 (T01)
    "eyeSpeed": 0, "eyeDist": 0,
    "iStartLon": 0.0, "iStartLat": 0.0, "iEndLon": 0.0, "iEndLat": 0.0, "iSpeed": 0,
    "offCourse": False,
    "spdLmtSpeedValue": 0,       # stock 0; bridge non-zero (T02)
    "distToDest": 4202, "timeToDest": 957,
}

# Region 0x8202/0x8009: 0xf1b31 = 990001 on all three ids, country 1.
STOCK_REGION = {"regionId": 990001, "country": 1, "province": 990001, "city": 990001, "weather": 0}

# Light 0x8202/0x8001: stock sends exist 1, no coordinates; bridge sends exist 2 with coords (X01).
STOCK_LIGHT = {"exist": 1, "lon": 0.0, "lat": 0.0, "state": 0, "start": 0, "end": 0, "dir": 0, "wait": 0}

# Tunnel 0x8202/0x8004: empty.
STOCK_TUNNEL = {"present": False, "state": 0, "dist": 0,
                "startLon": 0.0, "startLat": 0.0, "endLon": 0.0, "endLat": 0.0}

# speedIntersection 0x8202/0x800A: historySpeed 25 (stock) vs 7 (bridge, S01).
STOCK_SPEED_INTERSECTION = {"flow": 0, "history": 25, "parallel": 2, "mix": 2, "solid": 0,
                            "dist": -1, "voice": ""}

# AdvAutoFunInfo 7/0x8004: MAX, no-ODD distance.
STOCK_ADV_AUTO = {"noOddRegionDist": 2147483647}

# tidalLane 0x8202/0x8007.
STOCK_TIDAL = {"ramp": -1, "tidal": 1}

# naviActionAndCamera 0x8202/0x800B: iconType 3, distance 38, cameraType 0, cameraDistance -1.
STOCK_ACTION = {"icon": 3, "main": 0, "assistant": 0, "distance": 38, "cameraType": 0, "cameraDistance": -1}

# NavigationStatus 7/0x8001 before the route: idle 100 then the route-result 106.
STOCK_PRE_ROUTE_PHASE = [{"status": 100, "holdS": 2}, {"status": 106, "holdS": 3}]
LOOP_STATUS = 101
# The stock end: 110 for 3 s (its d() runnable counts 1..3 at 1 Hz), then back to 100.
END_SEQUENCE = [{"status": 110, "holdS": 3}, {"status": 100, "holdS": 0}]

STOCK_RATES = {"position": 1.0, "trafficInfo": 1.0, "region": 1.0, "light": 1.0, "tunnel": 1.0,
               "status": 1.0, "advAuto": 1.0, "tidal": 1.0, "action": 1.0, "speedIntersection": 1.0}
# The live bridge ran position/TrafficInfo/region/light/tunnel near 5 Hz (ANALYSIS A.2).
BRIDGE_RATES = dict(STOCK_RATES, position=5.0, trafficInfo=5.0, region=5.0, light=5.0, tunnel=5.0)


def _inverse_gcj(lon, lat, *, iterations=40):
    """WGS-84 of a GCJ grid point: the iterative inverse of stock._forward, Moscow envelope."""
    wlon, wlat = lon, lat
    for _ in range(iterations):
        glon, glat = stock.to_sdk(wlon, wlat, GCJ)
        wlon += lon - glon
        wlat += lat - glat
    return wlon, wlat


def _load_stock_path():
    """The stock route as a structured path (grid-int points), plus its RouteType."""
    inner = json.loads(stock_route_payload_path().read_text(encoding="utf-8-sig"))["payload"]
    path, route_type = stock.path_from_payload(inner)
    return path, route_type


def _point_wgs84(path):
    """A deep copy of path with every link point re-expressed in WGS-84 (grid ints)."""
    out = copy.deepcopy(path)
    for seg in out["segments"]:
        for link in seg["links"]:
            lons, lats = [], []
            for gx, gy in zip(link["lon"], link["lat"]):
                wlon, wlat = _inverse_gcj(gx / stock.SCALE, gy / stock.SCALE)
                lons.append(stock.java_round(wlon * stock.SCALE))
                lats.append(stock.java_round(wlat * stock.SCALE))
            link["lon"], link["lat"] = lons, lats
    # trafficLights carried as grid-double tuples in path_from_payload output.
    out["trafficLights"] = [tuple(_inverse_gcj(x, y)) for x, y in out.get("trafficLights", [])]
    return out


def _base_scenario(path, route_type):
    return {
        "schema": SCHEMA,
        "id": "H0",
        "label": "stock start feed as recorded (positive control)",
        "derivedFrom": None,
        "changedVariable": None,
        "durationS": 120,
        "route": {"pathIdToken": PATH_ID_TOKEN, "routeType": route_type, "frame": GCJ, "path": path},
        "selection": {"pathIdToken": PATH_ID_TOKEN},
        "oddRequest": True,
        "navStatus": {"preRoutePhase": copy.deepcopy(STOCK_PRE_ROUTE_PHASE), "loopStatus": LOOP_STATUS},
        "endSequence": copy.deepcopy(END_SEQUENCE),
        "position": copy.deepcopy(STOCK_POSITION),
        "trafficInfo": copy.deepcopy(STOCK_TRAFFIC),
        "region": copy.deepcopy(STOCK_REGION),
        "light": copy.deepcopy(STOCK_LIGHT),
        "tunnel": copy.deepcopy(STOCK_TUNNEL),
        "speedIntersection": copy.deepcopy(STOCK_SPEED_INTERSECTION),
        "advAutoFunInfo": copy.deepcopy(STOCK_ADV_AUTO),
        "tidalLane": copy.deepcopy(STOCK_TIDAL),
        "action": copy.deepcopy(STOCK_ACTION),
        "rates": copy.deepcopy(STOCK_RATES),
        "naviSt": None,
    }


# ---- the single-variable deltas, and the fields each one is allowed to touch ----

def _links(scn):
    for seg in scn["route"]["path"]["segments"]:
        for link in seg["links"]:
            yield link


def _d_frame(scn):
    scn["route"]["frame"] = WGS84
    scn["route"]["path"] = _point_wgs84(scn["route"]["path"])
    scn["position"]["frame"] = WGS84
    wlon, wlat = _inverse_gcj(scn["position"]["lon"], scn["position"]["lat"])
    rlon, rlat = _inverse_gcj(scn["position"]["roadLon"], scn["position"]["roadLat"])
    scn["position"].update(lon=wlon, lat=wlat, roadLon=rlon, roadLat=rlat)


def _d_class_formway(scn):
    for link in _links(scn):
        link["roadClass"] = -1
        link["formway"] = -1


def _d_link_ownership(scn):
    for link in _links(scn):
        link["ownership"] = -1


def _d_direction(scn):
    for link in _links(scn):
        link["direction"] = 0


def _d_lane_num(scn):
    for link in _links(scn):
        link["laneNum"] = 0


def _d_admincode_region(scn):
    for link in _links(scn):
        link["adcode"] = 0
    scn["region"] = {"regionId": 0, "country": 1, "province": 0, "city": 0, "weather": 0}


def _d_position_ownership_roadclass(scn):
    scn["position"]["ownership"] = -1
    scn["trafficInfo"]["roadClass"] = -1


def _d_legal_speed(scn):
    scn["trafficInfo"]["spdLmtSpeedValue"] = 20


def _d_protocol(scn):
    scn["oddRequest"] = False
    scn["navStatus"]["preRoutePhase"] = []


def _d_rates(scn):
    scn["rates"] = copy.deepcopy(BRIDGE_RATES)


def _d_bridge_remainder(scn):
    scn["speedIntersection"]["history"] = 7
    scn["position"]["course"] = 156
    scn["light"] = {"exist": 2, "lon": 37.067931944444446, "lat": 55.53337388888889,
                    "state": 0, "start": 0, "end": 0, "dir": 0, "wait": 0}


def _d_navi_st(scn):
    scn["naviSt"] = {"value": 2}


# id -> (parent id, human label, delta fn, the content paths it may change)
CHAIN = [
    ("H1", "H0", "coordinate frame GCJ -> WGS-84", _d_frame,
     {"route.frame", "route.path.points", "position.frame", "position.lat", "position.lon",
      "position.roadLat", "position.roadLon"}),
    ("H2a", "H1", "link roadclass/formway -> -1", _d_class_formway,
     {"route.path.link.roadClass", "route.path.link.formway"}),
    ("H2b", "H2a", "link ownership -> -1", _d_link_ownership, {"route.path.link.ownership"}),
    ("H2c", "H2b", "link direction -> 0", _d_direction, {"route.path.link.direction"}),
    ("H2d", "H2c", "link laneNum -> 0", _d_lane_num, {"route.path.link.laneNum"}),
    ("H2e", "H2d", "link adminCode -> 0 and Region -> zeros", _d_admincode_region,
     {"route.path.link.adcode", "region.regionId", "region.province", "region.city"}),
    ("H2f", "H2e", "position ownership -> -1 and TrafficInfo roadClass -> -1",
     _d_position_ownership_roadclass, {"position.ownership", "trafficInfo.roadClass"}),
    ("H2g", "H2f", "TrafficInfo legal speed limit -> 20", _d_legal_speed,
     {"trafficInfo.spdLmtSpeedValue"}),
    ("H3", "H2g", "no ODD request and no 106 phase", _d_protocol,
     {"oddRequest", "navStatus.preRoutePhase"}),
    ("H4", "H3", "5 Hz dynamic rates", _d_rates,
     {"rates.position", "rates.trafficInfo", "rates.region", "rates.light", "rates.tunnel"}),
    ("H5", "H4", "remaining bridge field values", _d_bridge_remainder,
     {"speedIntersection.history", "position.course", "light.exist", "light.lon", "light.lat"}),
    ("H6", "H0", "H0 + stock NaviSt side-signal (SETTING_MPC_IFC_NAVI_ST_SET)", _d_navi_st,
     {"naviSt"}),
]
ALLOWED = {entry[0]: entry[4] for entry in CHAIN}
PARENT = {entry[0]: entry[1] for entry in CHAIN}


def build_scenarios():
    """The ordered chain of scenarios, each a full dict. Data-driven; reorder/skip at the caller."""
    path, route_type = _load_stock_path()
    base = _base_scenario(path, route_type)
    by_id = {"H0": base}
    scenarios = [base]
    for ident, parent, label, delta, _allowed in CHAIN:
        scn = copy.deepcopy(by_id[parent])
        scn.update(id=ident, label=label, derivedFrom=parent, changedVariable=ident)
        delta(scn)
        by_id[ident] = scn
        scenarios.append(scn)
    return scenarios


# ---- payload emission and content signature ----

def route_payload_json(scn):
    """The stock-gson inner MessageSend JSON for this scenario's route (pathId = token)."""
    path = copy.deepcopy(scn["route"]["path"])
    path["pathId"] = scn["route"]["pathIdToken"]
    return stock.message_send(path, scn["route"]["routeType"])


def signature(scn):
    """A flat {dotted-path: value} of the wire-relevant content, without volatile/meta keys."""
    out = {}

    def walk(prefix, value):
        if isinstance(value, dict):
            for key in sorted(value):
                walk(f"{prefix}.{key}" if prefix else key, value[key])
        elif isinstance(value, (list, tuple)):
            for i, item in enumerate(value):
                walk(f"{prefix}[{i}]", item)
        else:
            out[prefix] = value

    body = {k: v for k, v in scn.items() if k not in ("schema", "id", "label", "derivedFrom", "changedVariable")}
    # Route path: keep attributes and a frame-independent fingerprint of the points, but index the
    # points so a frame change shows up as one logical change (route.path.points), not per-point.
    body = copy.deepcopy(body)
    pts = []
    for seg in body["route"]["path"]["segments"]:
        for link in seg["links"]:
            pts.append((tuple(link.pop("lon")), tuple(link.pop("lat"))))
    walk("", body)
    out["route.path.points"] = hash(tuple(pts))
    return out


def diff_variable_ok(parent_scn, child_scn):
    """Keys whose value changed from parent to child, and whether all fall under the declared var."""
    before, after = signature(parent_scn), signature(child_scn)
    changed = set()
    for key in set(before) | set(after):
        if before.get(key) != after.get(key):
            changed.add(key)
    allowed = ALLOWED[child_scn["id"]]

    def permitted(key):
        for prefix in allowed:
            if key == prefix or key.startswith(prefix + ".") or key.startswith(prefix + "["):
                return True
            # link-level allowances apply to every indexed link under route.path
            if prefix.startswith("route.path.link.") and key.startswith("route.path.segments["):
                field = prefix[len("route.path.link."):]
                if key.endswith("." + field):
                    return True
            if prefix == "route.path.points" and key == "route.path.points":
                return True
        return False

    return changed, {k for k in changed if not permitted(k)}


def write_scenarios(out_dir):
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    written = []
    for scn in build_scenarios():
        emitted = copy.deepcopy(scn)
        emitted["route"]["payloadJson"] = route_payload_json(scn)
        del emitted["route"]["path"]           # the player sends payloadJson, not the structure
        path = out / f"scenario-{scn['id']}.json"
        path.write_text(json.dumps(emitted, ensure_ascii=False, indent=2) + "\n")
        links = sum(len(s["links"]) for s in scn["route"]["path"]["segments"])
        written.append({"id": scn["id"], "file": str(path), "changedVariable": scn["changedVariable"],
                        "frame": scn["route"]["frame"], "links": links,
                        "routeBytes": len(emitted["route"]["payloadJson"].encode("utf-8"))})
    return written


# ---- rehearsal: the exact per-message timeline ----

def rehearse(scn):
    """The ordered (t_ms, message) timeline the player would produce, as a list of dict rows."""
    rows = []
    t = 0

    def emit(t_ms, kind, detail):
        rows.append({"t_ms": t_ms, "kind": kind, "detail": detail})

    if scn["naviSt"] is not None:
        emit(t, "naviSt.write", {"value": scn["naviSt"]["value"], "device": 1023,
                                 "fid": "SETTING_MPC_IFC_NAVI_ST_SET(0x4CA0004B)"})
    if scn["oddRequest"]:
        emit(t, "oddRequest", {"service": 13, "method": "0x0002"})
    for step in scn["navStatus"]["preRoutePhase"]:
        emit(t, "NavigationStatus", {"status": step["status"]})
        t += int(step.get("holdS", 1) * 1000)
    emit(t, "route", {"service": 43, "event": "0x8002", "frame": scn["route"]["frame"]})
    emit(t, "pathSelected", {"service": 45, "event": "0x8001"})
    loop_t = t + 100
    end_at = scn["durationS"] * 1000
    streams = {
        "NavigationStatus": (scn["rates"]["status"], {"status": scn["navStatus"]["loopStatus"]}),
        "VehicleLocation": (scn["rates"]["position"], {"ownership": scn["position"]["ownership"]}),
        "TrafficInfo": (scn["rates"]["trafficInfo"],
                        {"roadClass": scn["trafficInfo"]["roadClass"],
                         "spdLmt": scn["trafficInfo"]["spdLmtSpeedValue"]}),
        "Region": (scn["rates"]["region"], {"regionId": scn["region"]["regionId"]}),
        "TrafficLight": (scn["rates"]["light"], {"exist": scn["light"]["exist"]}),
        "Tunnel": (scn["rates"]["tunnel"], {"present": scn["tunnel"]["present"]}),
        "SpeedIntersection": (scn["rates"]["speedIntersection"],
                              {"history": scn["speedIntersection"]["history"]}),
        "AdvAutoFunInfo": (scn["rates"]["advAuto"], {"noOddRegionDist": scn["advAutoFunInfo"]["noOddRegionDist"]}),
        "TidalLane": (scn["rates"]["tidal"], {"ramp": scn["tidalLane"]["ramp"]}),
        "NextAction": (scn["rates"]["action"], {"icon": scn["action"]["icon"]}),
    }
    ticks = []
    for name, (hz, detail) in streams.items():
        if hz <= 0:
            ticks.append((loop_t, name, detail))
            continue
        period = int(round(1000.0 / hz))
        tick = loop_t
        while tick < end_at:
            ticks.append((tick, name, detail))
            tick += period
    for tick, name, detail in sorted(ticks, key=lambda row: (row[0], row[1])):
        emit(tick, name, detail)
    end_t = end_at
    for step in scn["endSequence"]:
        emit(end_t, "NavigationStatus(end)", {"status": step["status"]})
        end_t += int(step.get("holdS", 0) * 1000)
    if scn["naviSt"] is not None:
        emit(end_t, "naviSt.restore", {"device": 1023, "fid": "SETTING_MPC_IFC_NAVI_ST_SET(0x4CA0004B)"})
    return rows


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    gen = sub.add_parser("generate", help="write scenario-*.json files for the player")
    gen.add_argument("--out", required=True, type=Path)
    reh = sub.add_parser("rehearse", help="print the exact per-message timeline of one scenario")
    reh.add_argument("id", help="scenario id, e.g. H0, H2f")
    reh.add_argument("--max-rows", type=int, default=60)
    sub.add_parser("list", help="list the scenario chain")
    args = parser.parse_args(argv)
    if args.command == "generate":
        written = write_scenarios(args.out)
        print(json.dumps({"out": str(args.out), "scenarios": written}, indent=2, ensure_ascii=False))
    elif args.command == "list":
        for scn in build_scenarios():
            print(f"{scn['id']:4} {scn['changedVariable'] or '(base)':24} {scn['label']}")
    else:
        scenarios = {scn["id"]: scn for scn in build_scenarios()}
        if args.id not in scenarios:
            parser.exit(2, f"unknown scenario {args.id}\n")
        scn = scenarios[args.id]
        rows = rehearse(scn)
        print(f"# {scn['id']}: {scn['label']}  (frame={scn['route']['frame']}, duration={scn['durationS']}s, "
              f"oddRequest={scn['oddRequest']}, naviSt={scn['naviSt']})")
        head = rows[:args.max_rows]
        for row in head:
            print(f"{row['t_ms']:8d} ms  {row['kind']:22} {json.dumps(row['detail'], ensure_ascii=False)}")
        if len(rows) > len(head):
            print(f"... {len(rows) - len(head)} more rows; {len(rows)} total")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
