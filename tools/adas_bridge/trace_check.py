#!/usr/bin/env python3
"""Check a dry-run trace of the adas-link feed (schema denza-adas-dry-run-v1), HOST ONLY.

Re-decodes every fired payload with the oracle (tools/adas_bridge/stock.py) and compares it with
the recorder's own "decoded"; reports per-topic counts, NavigationStatus/position cadence, status
transitions, path changes and route publications, any 110 that does not follow a naviArrive
input, and position progress that moves back on one path. --interleave merges the trace with the
idle stock map's 1 Hz NavigationStatus 100 / pathId -1 stream (DYN-05) and counts the seconds in
which ADAS would hear both writers disagree.

    python3 -m tools.adas_bridge.trace_check trace.jsonl [--interleave] [--stock-phase-ms N | --stock-log drive.json]

The JSON report (--out) is plain per-topic rows that a stock-vs-bridge comparator can read
directly: one row per fire with its monotonic time and the oracle's decoded values.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import statistics
import sys

from . import stock


def read_trace(path):
    records = []
    for number, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        if line.strip():
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as exc:
                raise ValueError(f"line {number}: {exc}") from exc
    return records


def _same(a, b):
    """Recorder JSON (Java longs) versus oracle values: uint64 compared modulo 2**64, floats exactly."""
    if isinstance(a, dict) and isinstance(b, dict):
        return a.keys() == b.keys() and all(_same(a[k], b[k]) for k in a)
    if isinstance(a, list) and isinstance(b, list):
        return len(a) == len(b) and all(_same(x, y) for x, y in zip(a, b))
    if isinstance(a, bool) or isinstance(b, bool):
        return a is b
    if isinstance(a, int) and isinstance(b, int):
        return (a - b) % (1 << 64) == 0
    if isinstance(a, (int, float)) and isinstance(b, (int, float)):
        return float(a) == float(b)
    return a == b


def _inner(decoded):
    return next(iter(decoded.values())) if isinstance(decoded, dict) and len(decoded) == 1 and isinstance(next(iter(decoded.values())), dict) else decoded


def check(records, *, interleave=False, stock_phase_ms=None):
    header = next((r for r in records if r.get("kind") == "header"), None)
    fires = [r for r in records if r.get("kind") == "fire"]
    inputs = [r for r in records if r.get("kind") == "input"]
    problems, rows = [], []
    counts = {}
    for r in fires:
        counts[r["name"]] = counts.get(r["name"], 0) + 1
        try:
            decoded = stock.decode_topic(int(r["topic"]), bytes.fromhex(r["payloadHex"]))
        except stock.StockError as exc:
            problems.append({"at": r.get("monotonicMs"), "name": r["name"], "problem": "oracle cannot decode: " + str(exc)})
            continue
        if decoded is not None and not _same(decoded, r.get("decoded")):
            problems.append({"at": r.get("monotonicMs"), "name": r["name"], "problem": "decoded differs from the oracle"})
        rows.append({"monotonicMs": r.get("monotonicMs"), "service": r.get("service"), "event": r.get("event"),
                     "name": r["name"], "values": _inner(decoded)})

    def times(name):
        return [r["monotonicMs"] for r in fires if r["name"] == name and r.get("monotonicMs") is not None]

    def cadence(name):
        t = times(name)
        gaps = [b - a for a, b in zip(t, t[1:])]
        return {"count": len(t), "medianIntervalMs": statistics.median(gaps) if gaps else None,
                "maxIntervalMs": max(gaps) if gaps else None}

    statuses = [(row["monotonicMs"], row["values"].get("navigationStatus", 0)) for row in rows if row["name"] == "NavigationStatus"]
    transitions = []
    for at, state in statuses:
        if not transitions or transitions[-1]["state"] != state:
            transitions.append({"fromMs": at, "state": state, "count": 0})
        transitions[-1]["count"] += 1
    arrivals = [r["monotonicMs"] for r in inputs if r.get("input") == "naviArrive"]
    for run in transitions:
        if run["state"] == 110 and not any(a <= run["fromMs"] for a in arrivals):
            problems.append({"at": run["fromMs"], "name": "NavigationStatus", "problem": "110 without a preceding naviArrive (DYN-09)"})
    paths, regressions, last = [], [], {}
    for row in rows:
        if row["name"] != "sdVehicleLocation":
            continue
        v = row["values"]
        path = v.get("curSDRouteID", 0.0)
        if not paths or paths[-1]["pathId"] != path:
            paths.append({"fromMs": row["monotonicMs"], "pathId": path})
        if path == -1.0:
            continue
        place = (stock._int32(v.get("curStepId", 0)), stock._int32(v.get("curLinkId", 0)), -stock._int32(v.get("distanceToNextStep", 0)))
        if path in last and place < last[path]:
            regressions.append({"at": row["monotonicMs"], "pathId": path, "from": last[path], "to": place})
        last[path] = max(place, last.get(path, place))
    for r in regressions:
        problems.append({"at": r["at"], "name": "sdVehicleLocation", "problem": "progress moved back on one path (DYN-01)"})
    routes = [{"at": row["monotonicMs"], "chars": len(row["values"].get("navigationSDLink2", ""))} for row in rows if row["name"] == "naviSDRoute"]
    selections = [{"at": row["monotonicMs"], "pathIDReq": row["values"].get("pathIDReq")} for row in rows if row["name"] == "pathSelected"]
    report = {"header": header, "fires": len(fires), "inputs": len(inputs), "counts": counts,
              "cadence": {"NavigationStatus": cadence("NavigationStatus"), "sdVehicleLocation": cadence("sdVehicleLocation")},
              "statusRuns": transitions, "positionPaths": paths, "routePublications": routes, "selections": selections,
              "problems": problems}
    if interleave:
        report["interleave"] = interleave_with_idle_stock(rows, stock_phase_ms or 0)
    return report, rows


def interleave_with_idle_stock(rows, phase_ms):
    """Seconds in which our feed says 101 / a path while the idle stock map says 100 / -1."""
    ours = [(row["monotonicMs"], row["values"].get("navigationStatus", 0)) for row in rows if row["name"] == "NavigationStatus"]
    positions = [(row["monotonicMs"], row["values"].get("curSDRouteID", 0.0)) for row in rows if row["name"] == "sdVehicleLocation"]
    if not ours:
        return {"seconds": 0}
    start, end = ours[0][0], ours[-1][0]
    conflict_status = conflict_path = guiding = 0
    second = start - (start - phase_ms) % 1000
    while second <= end:
        window = [s for t, s in ours if second <= t < second + 1000]
        paths = [p for t, p in positions if second <= t < second + 1000]
        if 101 in window:
            guiding += 1
            conflict_status += 1                          # the idle stock map sends 100 in this second
        if any(p != -1.0 for p in paths):
            conflict_path += 1                            # ...and a pathId -1 position
        second += 1000
    return {"stockPhaseMs": phase_ms, "secondsGuiding": guiding, "secondsBoth101And100": conflict_status,
            "secondsBothPathAndMinusOne": conflict_path,
            "note": "the idle stock map is not silenced by anything inside the protocol; ADAS arbitration is unknown"}


def stock_phase(drive_positions):
    samples = json.loads(Path(drive_positions).read_text())
    ms = [int(r["t"].split(".")[-1]) for r in samples if r.get("path") == -1]
    return int(statistics.median(ms)) if ms else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("trace")
    parser.add_argument("--interleave", action="store_true")
    parser.add_argument("--stock-phase-ms", type=int)
    parser.add_argument("--stock-log", help="stock drive positions JSON (captures/adas-bridge-review/dynamics/stock-drive01-positions.json)")
    parser.add_argument("--out")
    args = parser.parse_args()
    phase = args.stock_phase_ms if args.stock_phase_ms is not None else (stock_phase(args.stock_log) if args.stock_log else 0)
    report, rows = check(read_trace(args.trace), interleave=args.interleave, stock_phase_ms=phase)
    if args.out:
        Path(args.out).write_text(json.dumps({"report": report, "rows": rows}, ensure_ascii=False, indent=1) + "\n")
    summary = {k: v for k, v in report.items() if k not in ("statusRuns", "positionPaths", "header")}
    summary["statusRuns"] = [(r["state"], r["count"]) for r in report["statusRuns"]]
    summary["positionPaths"] = [p["pathId"] for p in report["positionPaths"]]
    print(json.dumps(summary, ensure_ascii=False, indent=1))
    return 1 if report["problems"] else 0


if __name__ == "__main__":
    sys.exit(main())
