#!/usr/bin/env python3
"""Offline duration-weighted NOA availability; no device access or dependencies.

Input: {window: {startMs, endMs, pathId}, guidance: [{tMs, speedMps}],
        path: [{tMs, pathId}], pilot: [{tMs, dnp}]} in ONE millisecond clock.
Samples may precede the window; only the latest past sample can apply. Intervals
are half open. Speed lasts 1500 ms, pilot 1000 ms, path until the next change.
A null value or valid:false explicitly ends validity; omitted samples do not
refresh it. Unknown DNP integers are invalid, never unavailable (DNP 0).

The moving denominator requires known speed >1.5 m/s AND the selected path.
Unknown speed on that path and unknown path are reported separately. Coverage
and bounds concern only that measurable moving denominator. Active episodes
include stationary/unknown-speed time on the selected path: movement never
chops an episode. Classification requires at least 95% known speed on the
selected path and 95% pilot coverage while measurably moving (configurable);
uncertainty bounds remain visible when coverage fails. Episodes end at a different pilot value, expiry, path exit or
window end. Their held interval and actually observed active samples are both
reported; censoring never asserts an unobserved transition.

python3 tools/adas_bridge/availability_metric.py input.json [--output output.json]
"""
from __future__ import annotations

import argparse
from bisect import bisect_right
import json
import math
from pathlib import Path


DEFAULT_GUIDANCE_TTL_MS = 1500
DEFAULT_PILOT_TTL_MS = 1000
DEFAULT_MOVING_SPEED_MPS = 1.5
DEFAULT_MIN_GUIDANCE_COVERAGE = 0.95
DEFAULT_MIN_PILOT_COVERAGE = 0.95


def _integer(value, name):
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise ValueError(f"{name} must be a nonnegative integer")
    return value


def _number(value, name):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f"{name} must be a finite number")
    return value


def _series(data, name, field):
    rows = data.get(name, [])
    if not isinstance(rows, list):
        raise ValueError(f"{name} must be an array")
    seen = {}
    for row in rows:
        if not isinstance(row, dict):
            raise ValueError(f"{name} sample must be an object")
        t = _integer(row.get("tMs"), f"{name}.tMs")
        valid = row.get("valid", True)
        if not isinstance(valid, bool):
            raise ValueError(f"{name}.valid must be boolean")
        if field not in row and valid:
            raise ValueError(f"{name}.{field} is required (or valid:false)")
        raw = row.get(field)
        if raw is not None:
            if field == "speedMps":
                _number(raw, f"{name}.{field}")
                if raw < 0:
                    raise ValueError("speedMps must not be negative")
            elif field == "dnp":
                if isinstance(raw, bool) or not isinstance(raw, int):
                    raise ValueError("pilot.dnp must be an integer")
            elif isinstance(raw, bool) or not isinstance(raw, (str, int)):
                raise ValueError("pathId must be a string, integer or null")
        # Compare original value as well as validity: don't silently choose one
        # of two conflicting observations merely because both are unknown.
        observation = (valid, raw)
        if t in seen and seen[t] != observation:
            raise ValueError(f"conflicting {name} samples at {t}")
        seen[t] = observation
    return [(t, raw if valid else None) for t, (valid, raw) in sorted(seen.items())]


def _state(rows, times, t, ttl=None):
    i = bisect_right(times, t) - 1
    if i < 0:
        return None
    sample_t, value = rows[i]
    return value if ttl is None or t < sample_t + ttl else None


def analyze(data, *, guidance_ttl_ms=DEFAULT_GUIDANCE_TTL_MS,
            pilot_ttl_ms=DEFAULT_PILOT_TTL_MS, moving_speed_mps=DEFAULT_MOVING_SPEED_MPS,
            min_guidance_coverage=DEFAULT_MIN_GUIDANCE_COVERAGE,
            min_pilot_coverage=DEFAULT_MIN_PILOT_COVERAGE):
    """Return JSON-compatible durations, uncertainty bounds and active episodes."""
    if not isinstance(data, dict) or not isinstance(data.get("window"), dict):
        raise ValueError("window object is required")
    window = data["window"]
    start = _integer(window.get("startMs"), "window.startMs")
    end = _integer(window.get("endMs"), "window.endMs")
    if end <= start:
        raise ValueError("window.endMs must be greater than startMs")
    selected = window.get("pathId")
    if isinstance(selected, bool) or not isinstance(selected, (str, int)):
        raise ValueError("window.pathId must be a string or integer")
    for name, value in (("guidance_ttl_ms", guidance_ttl_ms), ("pilot_ttl_ms", pilot_ttl_ms)):
        if _integer(value, name) == 0:
            raise ValueError(f"{name} must be positive")
    _number(moving_speed_mps, "moving_speed_mps")
    if moving_speed_mps < 0:
        raise ValueError("moving_speed_mps must not be negative")
    for name, value in (("min_guidance_coverage", min_guidance_coverage),
                        ("min_pilot_coverage", min_pilot_coverage)):
        _number(value, name)
        if not 0 <= value <= 1:
            raise ValueError(f"{name} must be between zero and one")
    guidance = _series(data, "guidance", "speedMps")
    paths = _series(data, "path", "pathId")
    pilots = _series(data, "pilot", "dnp")
    gt, pt, dt = ([t for t, _ in rows] for rows in (guidance, paths, pilots))
    boundaries = {start, end}
    for rows, ttl in ((guidance, guidance_ttl_ms), (paths, None), (pilots, pilot_ttl_ms)):
        for t, _ in rows:
            if start < t < end:
                boundaries.add(t)
            if ttl is not None and start < t + ttl < end:
                boundaries.add(t + ttl)
    edges = sorted(boundaries)
    durations = dict.fromkeys(("window", "selectedPath", "otherPath", "unknownPath",
                              "moving", "stationary", "unknownSpeed", "pilotKnown",
                              "offered", "active", "unavailable", "pilotUnknownInvalid"), 0)
    durations["window"] = end - start
    episodes = []
    episode = None
    previous_path = None
    previous_pilot = None
    for a, b in zip(edges, edges[1:]):
        path = _state(paths, pt, a)
        speed = _state(guidance, gt, a, guidance_ttl_ms)
        pilot = _state(pilots, dt, a, pilot_ttl_ms)
        valid_pilot = pilot in (0, 1, 2)
        selected_now = path == selected
        active_now = selected_now and pilot == 1
        if episode is not None and not active_now:
            # A real different valid observation closes the episode; expiration,
            # invalid observations and loss of route attribution are censored.
            different = selected_now and valid_pilot
            episode.update(endMs=a, rightCensored=not different,
                           endReason=("differentPilot" if different else "pathExit" if not selected_now else
                                      "pilotInvalid" if a in dt else "pilotExpired"),
                           nextDifferentSampleMs=a if different else None)
            episodes.append(episode)
            episode = None
        if active_now:
            if episode is None:
                # A sample before the window/path entry does not establish onset.
                episode = dict(startMs=a, endMs=None, heldActiveMs=0,
                               firstActiveSampleMs=None, lastActiveSampleMs=None,
                               leftCensored=(a == start or previous_path != selected or
                                             previous_pilot not in (0, 2)))
                i = bisect_right(dt, a) - 1
                if i >= 0 and pilots[i][1] == 1:
                    episode["firstActiveSampleMs"] = pilots[i][0]
                    episode["lastActiveSampleMs"] = pilots[i][0]
            episode["heldActiveMs"] += b - a
            i = bisect_right(dt, a) - 1
            if i >= 0 and pilots[i][1] == 1:
                episode["lastActiveSampleMs"] = pilots[i][0]
        duration = b - a
        if not selected_now:
            durations["unknownPath" if path is None else "otherPath"] += duration
        else:
            durations["selectedPath"] += duration
            if speed is None:
                durations["unknownSpeed"] += duration
            elif speed <= moving_speed_mps:
                durations["stationary"] += duration
            else:
                durations["moving"] += duration
                if valid_pilot:
                    durations["pilotKnown"] += duration
                    durations[{0: "unavailable", 1: "active", 2: "offered"}[pilot]] += duration
                else:
                    durations["pilotUnknownInvalid"] += duration
        previous_path, previous_pilot = path, pilot if valid_pilot else None
    if episode is not None:
        episode.update(endMs=end, rightCensored=True, endReason="windowEnd", nextDifferentSampleMs=None)
        episodes.append(episode)
    moving, known = durations["moving"], durations["pilotKnown"]
    available = durations["offered"] + durations["active"]
    lower = available / moving if moving else None
    upper = (available + durations["pilotUnknownInvalid"]) / moving if moving else None
    guidance_coverage = ((durations["moving"] + durations["stationary"]) / durations["selectedPath"]
                         if durations["selectedPath"] else None)
    pilot_coverage = known / moving if moving else None
    if (not moving or not known or guidance_coverage < min_guidance_coverage or
            pilot_coverage < min_pilot_coverage):
        category = "insufficientCoverage"
    elif lower >= 0.5:
        category = "atLeast50Percent"
    elif upper <= 0.1:
        category = "atMost10Percent"
    elif lower > 0.1 and upper < 0.5:
        category = "between10And50Percent"
    else:
        category = "inconclusive"
    return dict(schema="denza-noa-availability-v1", window=dict(startMs=start, endMs=end, pathId=selected),
                config=dict(guidanceTtlMs=guidance_ttl_ms, pilotTtlMs=pilot_ttl_ms,
                            movingSpeedMps=moving_speed_mps, minGuidanceCoverage=min_guidance_coverage,
                            minPilotCoverage=min_pilot_coverage, interval="[startMs,endMs)",
                            episodeScope="selected path, independent of movement"),
                durationsMs=durations,
                ratios=dict(guidanceCoverage=guidance_coverage, pilotCoverage=pilot_coverage,
                            offeredOrActiveAmongKnown=available / known if known else None,
                            offeredOrActiveLowerBound=lower, offeredOrActiveUpperBound=upper),
                classification=category, activeEpisodes=episodes)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("--output", "--out", type=Path)
    parser.add_argument("--guidance-ttl-ms", type=int, default=DEFAULT_GUIDANCE_TTL_MS)
    parser.add_argument("--pilot-ttl-ms", type=int, default=DEFAULT_PILOT_TTL_MS)
    parser.add_argument("--moving-speed-mps", type=float, default=DEFAULT_MOVING_SPEED_MPS)
    parser.add_argument("--min-guidance-coverage", type=float, default=DEFAULT_MIN_GUIDANCE_COVERAGE)
    parser.add_argument("--min-pilot-coverage", type=float, default=DEFAULT_MIN_PILOT_COVERAGE)
    args = parser.parse_args(argv)
    try:
        result = analyze(json.loads(args.input.read_text()), guidance_ttl_ms=args.guidance_ttl_ms,
                         pilot_ttl_ms=args.pilot_ttl_ms, moving_speed_mps=args.moving_speed_mps,
                         min_guidance_coverage=args.min_guidance_coverage,
                         min_pilot_coverage=args.min_pilot_coverage)
    except (ValueError, OSError) as error:
        parser.error(str(error))
    rendered = json.dumps(result, indent=2, allow_nan=False) + "\n"
    if args.output:
        args.output.write_text(rendered)
    else:
        print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
