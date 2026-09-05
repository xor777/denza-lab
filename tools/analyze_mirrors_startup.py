#!/usr/bin/env python3
"""Analyze Denza Mirrors startup timing logcat captures offline.

The analyzer never talks to the car and never infers physical pixel visibility.
It treats the TextureView first-update callback as the end of the measured
software path.  Two observation origins stay deliberately separate:

* ``window_first_observed`` is the first unambiguous ``window timing`` change
  for the still-active stock-window cycle;
* ``command_poll_observed`` is the poll timestamp printed on ``command: show``.

Typical use::

    python3 tools/analyze_mirrors_startup.py summary capture.log
    python3 tools/analyze_mirrors_startup.py compare control.log candidate.log
    python3 tools/analyze_mirrors_startup.py compare control.log candidate.log \
        --control-meta apk_sha256=... --candidate-meta apk_sha256=... \
        --control-meta config=stock --candidate-meta config=camera-scene \
        --json build/reports/mirrors-startup-comparison.json

``first_after_install`` is never guessed.  To assert it from external evidence,
pass metadata such as ``--meta first_after_install=24607:1`` (PID:generation).
Without that explicit annotation, the first attempt in each captured process
segment is labelled only ``first_observed``.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import re
import statistics
import sys
from collections import Counter, defaultdict
from pathlib import Path
from typing import Iterable, Sequence


MAX_COMMAND_REQUEST_GAP_MS = 250
MAX_WINDOW_REQUEST_GAP_MS = 10_000
SMALL_SAMPLE_COUNT = 5
RELEVANT_TAGS = {"DenzaMirrorMonitor", "DenzaClusterScene"}

LOGCAT_RE = re.compile(
    r"^(?P<date>\d\d-\d\d) (?P<time>\d\d:\d\d:\d\d\.\d+)\s+"
    r"(?P<pid>\d+)\s+(?P<tid>\d+)\s+[VDIWEF]\s+(?P<tag>[^:]+):\s(?P<message>.*)$"
)
WINDOW_RE = re.compile(
    r"window timing; side=(?P<side>LEFT|RIGHT|null) ambiguous=(?P<ambiguous>true|false).*?"
    r"observed_ms=(?P<observed>\d+)"
)
TRANSITION_RE = re.compile(
    r"transition (?P<old>[A-Z_]+) -> STARTING .*?requested=(?P<side>LEFT|RIGHT|null)"
)
COMMAND_RE = re.compile(
    r"command: show (?P<side>LEFT|RIGHT); observed_ms=(?P<observed>\d+) "
    r"command_ms=(?P<command>\d+)"
)
REQUEST_RE = re.compile(
    r"camera request; generation=(?P<generation>\d+) side=(?P<side>LEFT|RIGHT) "
    r"at_ms=(?P<at>\d+)"
)
DISPATCH_RE = re.compile(
    r"showCamera (?P<side>LEFT|RIGHT); generation=(?P<generation>\d+) "
    r"at_ms=(?P<at>\d+)"
)
READY_RE = re.compile(
    r"AVC ready; generation=(?P<generation>\d+) side=(?P<side>LEFT|RIGHT)"
)
FRAME_RE = re.compile(r"AVC first texture update; generation=(?P<generation>\d+) (?P<details>.*)$")
FAILURE_RE = re.compile(
    r"AVC failure; generation=(?P<generation>\d+) side=(?P<side>LEFT|RIGHT)"
)
DETAIL_RE = re.compile(r"(?P<name>[a-z_]+_ms)=(?P<value>-?\d+)")


METRIC_LABELS = {
    "window_first_observed_to_request_ms": "window-first-observed -> request",
    "command_poll_observed_to_request_ms": "command-poll-observed -> request",
    "request_to_dispatch_ms": "request -> dispatch",
    "dispatch_to_renderer_start_ms": "dispatch -> renderer-start",
    "renderer_to_bind_ready_ms": "renderer-start -> Binder-ready",
    "renderer_to_surface_ready_ms": "renderer-start -> surface-ready (overlaps Binder)",
    "bind_ready_to_first_update_ms": "Binder-ready -> first-update",
    "surface_ready_to_first_update_ms": "surface-ready -> first-update",
    "get_name_ms": "vendor getName",
    "buffer_type_ms": "vendor buffer-type",
    "init_display_ms": "vendor initDisplay",
    "viewpoint_ms": "vendor setViewpoint",
    "ready_to_first_update_ms": "READY -> first-update",
    "renderer_to_first_update_ms": "renderer-start -> first-update",
    "request_to_first_update_ms": "request -> first-update",
    "window_first_observed_to_first_update_ms": "window-first-observed -> first-update",
}

HUMAN_METRICS = (
    "window_first_observed_to_request_ms",
    "command_poll_observed_to_request_ms",
    "request_to_dispatch_ms",
    "dispatch_to_renderer_start_ms",
    "renderer_to_bind_ready_ms",
    "renderer_to_surface_ready_ms",
    "bind_ready_to_first_update_ms",
    "surface_ready_to_first_update_ms",
    "init_display_ms",
    "ready_to_first_update_ms",
    "request_to_first_update_ms",
    "window_first_observed_to_first_update_ms",
)


@dataclasses.dataclass
class WindowCycle:
    cycle_id: int
    side: str
    first_observed_ms: int
    correlation_observed_ms: int | None
    line_no: int
    show_attempt_ids: list[str] = dataclasses.field(default_factory=list)


@dataclasses.dataclass
class Attempt:
    attempt_id: str
    pid: int
    segment: int
    side: str
    line_no: int
    generation: int | None = None
    origin: str = "unknown"
    cohort: str = "subsequent"
    first_after_install: bool | None = None
    command_poll_observed_ms: int | None = None
    command_ms: int | None = None
    request_ms: int | None = None
    dispatch_ms: int | None = None
    ready_seen: bool = False
    frame_fields: dict[str, int] | None = None
    failure_seen: bool = False
    post_first_update_failure: bool = False
    teardown_before_frame: bool = False
    frame_line_no: int | None = None
    failure_line_no: int | None = None
    teardown_line_no: int | None = None
    window_cycle_id: int | None = None
    window_first_observed_ms: int | None = None
    repeated_show_same_window: bool = False
    potential_cancellation_reopen: bool = False
    mode_off_evidence: bool = False
    preempt_evidence: bool = False
    teardown_evidence: bool = False
    outcome: str = "pending"
    issues: list[str] = dataclasses.field(default_factory=list)

    def has_invalid_timing(self) -> bool:
        return any(issue.startswith("invalid timing:") for issue in self.issues)

    def has_correlation_conflict(self) -> bool:
        return any(issue.startswith("correlation conflict:") for issue in self.issues)

    def metrics(self) -> dict[str, int | None]:
        result: dict[str, int | None] = {name: None for name in METRIC_LABELS}
        if self.request_ms is not None:
            if self.window_first_observed_ms is not None:
                result["window_first_observed_to_request_ms"] = (
                    self.request_ms - self.window_first_observed_ms
                )
            if self.command_poll_observed_ms is not None:
                result["command_poll_observed_to_request_ms"] = (
                    self.request_ms - self.command_poll_observed_ms
                )
            if self.dispatch_ms is not None:
                result["request_to_dispatch_ms"] = self.dispatch_ms - self.request_ms

        fields = self.frame_fields
        if not fields:
            return result
        renderer_start = fields["renderer_start_ms"]
        first_update = fields["first_update_ms"]
        renderer_to_frame = fields["renderer_to_frame_ms"]
        bind_ready = fields["bind_ready_ms"]
        surface_ready = fields["surface_ready_ms"]
        result.update(
            {
                "renderer_to_bind_ready_ms": bind_ready,
                "renderer_to_surface_ready_ms": surface_ready,
                "bind_ready_to_first_update_ms": renderer_to_frame - bind_ready,
                "surface_ready_to_first_update_ms": renderer_to_frame - surface_ready,
                "get_name_ms": fields["get_name_ms"],
                "buffer_type_ms": fields["buffer_type_ms"],
                "init_display_ms": fields["init_display_ms"],
                "viewpoint_ms": fields["viewpoint_ms"],
                "ready_to_first_update_ms": fields["ready_to_frame_ms"],
                "renderer_to_first_update_ms": renderer_to_frame,
            }
        )
        if self.dispatch_ms is not None:
            result["dispatch_to_renderer_start_ms"] = renderer_start - self.dispatch_ms
        if self.request_ms is not None:
            result["request_to_first_update_ms"] = first_update - self.request_ms
        if self.window_first_observed_ms is not None:
            result["window_first_observed_to_first_update_ms"] = (
                first_update - self.window_first_observed_ms
            )
        return result


@dataclasses.dataclass
class SegmentState:
    pid: int
    number: int
    last_primary_ms: int | None = None
    max_generation: int | None = None
    active_window: WindowCycle | None = None
    next_window_cycle: int = 1
    latest_transition: tuple[int, str, str] | None = None
    last_command_line: int = -1
    commands: list[Attempt] = dataclasses.field(default_factory=list)
    attempts: list[Attempt] = dataclasses.field(default_factory=list)
    evidence: list[tuple[int, str]] = dataclasses.field(default_factory=list)
    inconclusive_order: bool = False
    warnings: list[str] = dataclasses.field(default_factory=list)


@dataclasses.dataclass
class Capture:
    source: str
    label: str
    metadata: dict[str, str]
    attempts: list[Attempt]
    warnings: list[str]
    orphan_events: list[str]


def parse_metadata(values: Sequence[str]) -> dict[str, str]:
    result: dict[str, str] = {}
    for value in values:
        if "=" not in value:
            raise ValueError(f"metadata must be KEY=VALUE, got {value!r}")
        key, item = value.split("=", 1)
        if not key or not item:
            raise ValueError(f"metadata must have non-empty KEY and VALUE, got {value!r}")
        result[key] = item
    return result


def _frame_details(message: str) -> dict[str, int] | None:
    match = FRAME_RE.search(message)
    if not match:
        return None
    fields = {item.group("name"): int(item.group("value")) for item in DETAIL_RE.finditer(match.group("details"))}
    required = {
        "renderer_start_ms",
        "first_update_ms",
        "renderer_to_frame_ms",
        "bind_ready_ms",
        "surface_ready_ms",
        "get_name_ms",
        "buffer_type_ms",
        "init_display_ms",
        "viewpoint_ms",
        "ready_to_frame_ms",
    }
    return fields if required <= fields.keys() else None


def analyze_lines(
    lines: Iterable[str],
    *,
    source: str = "<memory>",
    label: str | None = None,
    metadata: dict[str, str] | None = None,
) -> Capture:
    metadata = dict(metadata or {})
    states: dict[int, SegmentState] = {}
    all_segments: list[SegmentState] = []
    attempts: list[Attempt] = []
    request_attempts: dict[tuple[int, int, int], list[Attempt]] = defaultdict(list)
    orphan_events: list[str] = []
    capture_warnings: list[str] = []
    next_attempt = 1

    def new_state(pid: int, reason: str | None = None) -> SegmentState:
        number = states[pid].number + 1 if pid in states else 1
        state = SegmentState(pid, number)
        states[pid] = state
        all_segments.append(state)
        if reason:
            warning = f"pid {pid} segment {number}: {reason}; correlation does not cross this boundary"
            state.warnings.append(warning)
            capture_warnings.append(warning)
        return state

    def state_for(pid: int) -> SegmentState:
        return states.get(pid) or new_state(pid)

    def mark_primary(state: SegmentState, timestamp: int, line_no: int) -> None:
        if state.last_primary_ms is not None and timestamp < state.last_primary_ms:
            state.inconclusive_order = True
            warning = (
                f"pid {state.pid} segment {state.number}: monotonic event rollback at line {line_no} "
                f"({timestamp} < {state.last_primary_ms}); segment is inconclusive, not a proven restart"
            )
            if warning not in state.warnings:
                state.warnings.append(warning)
                capture_warnings.append(warning)
        state.last_primary_ms = max(timestamp, state.last_primary_ms or timestamp)

    def find_request(state: SegmentState, generation: int, line_no: int) -> Attempt | None:
        matches = request_attempts.get((state.pid, state.number, generation), ())
        return next((item for item in reversed(matches) if item.line_no < line_no), None)

    def create_attempt(state: SegmentState, side: str, line_no: int) -> Attempt:
        nonlocal next_attempt
        attempt = Attempt(f"a{next_attempt}", state.pid, state.number, side, line_no)
        next_attempt += 1
        state.attempts.append(attempt)
        attempts.append(attempt)
        return attempt

    for line_no, raw_line in enumerate(lines, start=1):
        log_match = LOGCAT_RE.match(raw_line.rstrip("\n"))
        if not log_match:
            continue
        pid = int(log_match.group("pid"))
        if log_match.group("tag").strip() not in RELEVANT_TAGS:
            continue
        message = log_match.group("message")
        state = state_for(pid)

        match = WINDOW_RE.search(message)
        if match:
            observed_ms = int(match.group("observed"))
            mark_primary(state, observed_ms, line_no)
            side = match.group("side")
            ambiguous = match.group("ambiguous") == "true"
            if side == "null":
                state.active_window = None
            elif state.active_window is None or state.active_window.side != side:
                state.active_window = WindowCycle(
                    state.next_window_cycle,
                    side,
                    observed_ms,
                    None if ambiguous else observed_ms,
                    line_no,
                )
                state.next_window_cycle += 1
            elif ambiguous:
                # The stock side may still be continuous for heuristic repeat detection,
                # but it is no longer an unambiguous latency origin.
                state.active_window.correlation_observed_ms = None
            elif state.active_window.correlation_observed_ms is None:
                # The stock cycle began ambiguously.  Correlation starts only when the
                # same cycle is explicitly logged as unambiguous.
                state.active_window.correlation_observed_ms = observed_ms
            continue

        match = TRANSITION_RE.search(message)
        if match:
            state.latest_transition = (line_no, match.group("old"), match.group("side"))
            continue

        match = COMMAND_RE.search(message)
        if match:
            command_ms = int(match.group("command"))
            observed_ms = int(match.group("observed"))
            mark_primary(state, command_ms, line_no)
            attempt = create_attempt(state, match.group("side"), line_no)
            attempt.command_poll_observed_ms = observed_ms
            attempt.command_ms = command_ms
            if state.latest_transition and state.latest_transition[0] > state.last_command_line:
                _, old_phase, requested_side = state.latest_transition
                if requested_side == attempt.side:
                    attempt.origin = "ordinary_idle" if old_phase == "IDLE" else "recovery"

            cycle = state.active_window
            if cycle and cycle.side == attempt.side:
                attempt.window_cycle_id = cycle.cycle_id
                if cycle.show_attempt_ids:
                    attempt.repeated_show_same_window = True
                    attempt.potential_cancellation_reopen = True
                    previous = cycle.show_attempt_ids[-1]
                    previous_attempt = next(item for item in attempts if item.attempt_id == previous)
                    between = [
                        kind
                        for evidence_line, kind in state.evidence
                        if previous_attempt.line_no < evidence_line < line_no
                    ]
                    attempt.mode_off_evidence = "mode_off" in between
                    attempt.preempt_evidence = "preempt" in between
                    attempt.teardown_evidence = "teardown" in between
                cycle.show_attempt_ids.append(attempt.attempt_id)

                if cycle.correlation_observed_ms is not None:
                    gap = command_ms - cycle.correlation_observed_ms
                    if 0 <= gap <= MAX_WINDOW_REQUEST_GAP_MS:
                        attempt.window_first_observed_ms = cycle.correlation_observed_ms
                    elif gap < 0:
                        attempt.issues.append("window observation is later than command; window origin unknown")
                    else:
                        attempt.issues.append(
                            f"active window observation is {gap} ms old (> {MAX_WINDOW_REQUEST_GAP_MS}); origin unknown"
                        )
                else:
                    attempt.issues.append("active stock-window cycle is ambiguous; latency origin unknown")
            else:
                attempt.issues.append("no matching unambiguous active stock-window observation")
            state.commands.append(attempt)
            state.last_command_line = line_no
            continue

        match = REQUEST_RE.search(message)
        if match:
            generation = int(match.group("generation"))
            request_ms = int(match.group("at"))
            side = match.group("side")
            if state.max_generation is not None and generation <= state.max_generation:
                state = new_state(
                    pid,
                    f"generation restarted/non-increased ({generation} after {state.max_generation})",
                )
            state.max_generation = generation
            mark_primary(state, request_ms, line_no)

            candidate = state.commands[-1] if state.commands else None
            attempt: Attempt
            if candidate and candidate.generation is None and candidate.side == side:
                assert candidate.command_ms is not None
                gap = request_ms - candidate.command_ms
                if 0 <= gap <= MAX_COMMAND_REQUEST_GAP_MS:
                    attempt = candidate
                else:
                    attempt = create_attempt(state, side, line_no)
                    candidate.issues.append(
                        f"request gap {gap} ms is outside 0..{MAX_COMMAND_REQUEST_GAP_MS}; request not correlated"
                    )
                    attempt.issues.append("no bounded matching Show command")
            else:
                attempt = create_attempt(state, side, line_no)
                attempt.issues.append("no matching latest Show command in this process segment")
            attempt.generation = generation
            attempt.request_ms = request_ms
            request_attempts[(pid, state.number, generation)].append(attempt)
            continue

        match = DISPATCH_RE.search(message)
        if match:
            generation = int(match.group("generation"))
            dispatch_ms = int(match.group("at"))
            attempt = find_request(state, generation, line_no)
            if attempt is None:
                orphan_events.append(f"line {line_no}: orphan dispatch pid={pid} generation={generation}")
            else:
                mark_primary(state, dispatch_ms, line_no)
                if attempt.side != match.group("side"):
                    attempt.issues.append("correlation conflict: dispatch side differs from request side")
                elif attempt.dispatch_ms is not None:
                    attempt.issues.append("correlation conflict: duplicate dispatch for generation")
                else:
                    attempt.dispatch_ms = dispatch_ms
            continue

        match = READY_RE.search(message)
        if match:
            generation = int(match.group("generation"))
            attempt = find_request(state, generation, line_no)
            if attempt is None:
                orphan_events.append(f"line {line_no}: orphan READY pid={pid} generation={generation}")
            elif attempt.side != match.group("side"):
                attempt.issues.append("correlation conflict: READY side differs from request side")
            else:
                attempt.ready_seen = True
            continue

        frame_match = FRAME_RE.search(message)
        if frame_match:
            generation = int(frame_match.group("generation"))
            fields = _frame_details(message)
            attempt = find_request(state, generation, line_no)
            if fields is not None and attempt is not None:
                # The frame event timestamp is first_update_ms. renderer_start_ms is
                # payload carried by this later event and must not drive rollback checks.
                mark_primary(state, fields["first_update_ms"], line_no)
            if attempt is None:
                orphan_events.append(f"line {line_no}: orphan first-update pid={pid} generation={generation}")
            elif fields is None:
                attempt.issues.append("first-update line is missing required timing fields")
            elif attempt.frame_fields is not None:
                attempt.issues.append("correlation conflict: duplicate first-update for generation")
            else:
                attempt.frame_fields = fields
                attempt.frame_line_no = line_no
            continue

        match = FAILURE_RE.search(message)
        if match:
            generation = int(match.group("generation"))
            attempt = find_request(state, generation, line_no)
            if attempt is None:
                orphan_events.append(f"line {line_no}: orphan failure pid={pid} generation={generation}")
            elif attempt.side != match.group("side"):
                attempt.issues.append("correlation conflict: failure side differs from request side")
            elif attempt.frame_fields is None:
                attempt.failure_seen = True
                attempt.failure_line_no = line_no
            else:
                attempt.post_first_update_failure = True
                attempt.failure_line_no = line_no
            continue

        if "turn-signal shadow: state=off" in message:
            state.evidence.append((line_no, "mode_off"))
            continue
        if "CAN preempt accepted;" in message:
            state.evidence.append((line_no, "preempt"))
            continue
        if "hideCamera: releasing surface" in message:
            state.evidence.append((line_no, "teardown"))
            pending = [
                item
                for item in state.attempts
                if item.line_no < line_no
                and item.request_ms is not None
                and item.frame_fields is None
                and not item.failure_seen
                and not item.teardown_before_frame
            ]
            if len(pending) == 1:
                pending[0].teardown_before_frame = True
                pending[0].teardown_line_no = line_no
            elif len(pending) > 1:
                capture_warnings.append(
                    f"pid {pid} segment {state.number} line {line_no}: teardown has multiple pending attempts"
                )

    segments_by_key = {(state.pid, state.number): state for state in all_segments}
    for state in all_segments:
        if state.attempts:
            state.attempts[0].cohort = "first_observed"

    explicit_first = metadata.get("first_after_install")
    if explicit_first:
        try:
            first_pid_text, first_generation_text = explicit_first.split(":", 1)
            first_key = (int(first_pid_text), int(first_generation_text))
        except (ValueError, TypeError):
            capture_warnings.append(
                "invalid first_after_install metadata; expected PID:generation and no cold label was applied"
            )
        else:
            matches = [
                item for item in attempts if (item.pid, item.generation) == first_key
            ]
            if len(matches) == 1:
                matches[0].first_after_install = True
                # Explicit cold evidence is its own A/B cohort even if an earlier
                # partial command in the same captured segment became an attempt.
                matches[0].cohort = "first_after_install"
            else:
                capture_warnings.append(
                    f"first_after_install={explicit_first} matched {len(matches)} attempts; no cold label was applied"
                )
    else:
        capture_warnings.append(
            "first-after-install is unknown; first attempts are labelled first_observed only"
        )

    for attempt in attempts:
        state = segments_by_key[(attempt.pid, attempt.segment)]
        if state.inconclusive_order:
            attempt.outcome = "inconclusive_segment_order"
            continue
        if attempt.request_ms is None:
            attempt.outcome = "missing_request"
            continue
        if attempt.frame_fields is not None and attempt.failure_seen:
            attempt.outcome = "inconclusive_first_update_after_failure"
        elif attempt.frame_fields is not None and attempt.teardown_before_frame:
            attempt.outcome = "inconclusive_first_update_after_teardown"
        elif attempt.frame_fields is not None:
            attempt.issues.extend(validate_attempt_timing(attempt))
            if attempt.has_correlation_conflict():
                attempt.outcome = "inconclusive_correlation"
            elif attempt.has_invalid_timing():
                attempt.outcome = "invalid_timing"
            else:
                attempt.outcome = "success"
        elif attempt.failure_seen:
            attempt.outcome = "explicit_failure"
        elif attempt.teardown_before_frame:
            attempt.outcome = "teardown_before_first_update"
        else:
            attempt.outcome = "missing_first_update_at_capture_end"

    if orphan_events:
        capture_warnings.append(f"{len(orphan_events)} orphan generation event(s); capture may be truncated")
    if any(item.outcome == "missing_first_update_at_capture_end" for item in attempts):
        capture_warnings.append("one or more attempts end without first-update/failure/teardown; capture is inconclusive")
    if any(item.post_first_update_failure for item in attempts):
        capture_warnings.append(
            "one or more attempts logged AVC failure after first-update; startup timing is retained but stability failed"
        )
    categories = {(item.origin, item.side) for item in attempts}
    if len(categories) > 1:
        capture_warnings.append(
            "capture contains mixed origin/side categories; overall statistics are descriptive only"
        )

    return Capture(
        Path(source).name,
        label or Path(source).stem,
        metadata,
        attempts,
        list(dict.fromkeys(capture_warnings)),
        orphan_events,
    )


def validate_attempt_timing(attempt: Attempt) -> list[str]:
    fields = attempt.frame_fields
    if fields is None:
        return []
    errors: list[str] = []
    renderer_start = fields["renderer_start_ms"]
    first_update = fields["first_update_ms"]
    renderer_to_frame = fields["renderer_to_frame_ms"]
    if first_update - renderer_start != renderer_to_frame:
        errors.append("renderer_to_frame does not match first_update-renderer_start")
    duration_fields = (
        "renderer_to_frame_ms",
        "bind_ready_ms",
        "surface_ready_ms",
        "get_name_ms",
        "buffer_type_ms",
        "init_display_ms",
        "viewpoint_ms",
        "ready_to_frame_ms",
    )
    for name in duration_fields:
        if fields[name] < 0:
            errors.append(f"{name} is negative")
    if fields["bind_ready_ms"] > renderer_to_frame:
        errors.append("Binder-ready occurs after first-update")
    if fields["surface_ready_ms"] > renderer_to_frame:
        errors.append("surface-ready occurs after first-update")
    for name in ("get_name_ms", "buffer_type_ms", "init_display_ms", "viewpoint_ms"):
        if fields[name] > renderer_to_frame:
            errors.append(f"{name} exceeds renderer-to-first-update")
    if fields["ready_to_frame_ms"] > renderer_to_frame:
        errors.append("READY-to-first-update exceeds renderer-to-first-update")
    covered_lower_bound = max(fields["bind_ready_ms"], fields["surface_ready_ms"]) + sum(
        fields[name]
        for name in (
            "get_name_ms",
            "buffer_type_ms",
            "init_display_ms",
            "viewpoint_ms",
            "ready_to_frame_ms",
        )
    )
    if covered_lower_bound > renderer_to_frame:
        errors.append("overlapping readiness plus sequential vendor/READY stages exceed renderer-to-first-update")
    if attempt.request_ms is not None and first_update < attempt.request_ms:
        errors.append("first-update precedes request")
    if attempt.request_ms is not None and renderer_start < attempt.request_ms:
        errors.append("renderer-start precedes request")
    if (
        attempt.request_ms is not None
        and attempt.command_poll_observed_ms is not None
        and attempt.command_poll_observed_ms > attempt.request_ms
    ):
        errors.append("command poll observation follows request")
    if attempt.dispatch_ms is not None:
        if attempt.request_ms is not None and attempt.dispatch_ms < attempt.request_ms:
            errors.append("dispatch precedes request")
        if renderer_start < attempt.dispatch_ms:
            errors.append("renderer-start precedes dispatch")
    return [f"invalid timing: {error}" for error in errors]


def stat(values: Sequence[int]) -> dict[str, int | float] | None:
    if not values:
        return None
    return {
        "count": len(values),
        "median": statistics.median(values),
        "min": min(values),
        "max": max(values),
    }


def window_context(attempt: Attempt) -> str:
    if attempt.repeated_show_same_window:
        return "continuous_window_repeat"
    if attempt.window_cycle_id is not None:
        return "new_window_cycle"
    return "unknown_window"


def category_key(attempt: Attempt) -> tuple[str, str, str, str]:
    return attempt.origin, attempt.side, attempt.cohort, window_context(attempt)


def category_name(key: tuple[str, ...]) -> str:
    return "/".join(key)


def grouped_attempts(capture: Capture) -> dict[tuple[str, str, str, str], list[Attempt]]:
    groups: dict[tuple[str, str, str, str], list[Attempt]] = defaultdict(list)
    for attempt in capture.attempts:
        groups[category_key(attempt)].append(attempt)
    return dict(sorted(groups.items()))


def metric_stats(attempts: Sequence[Attempt]) -> dict[str, dict[str, int | float]]:
    result: dict[str, dict[str, int | float]] = {}
    successes = [item for item in attempts if item.outcome == "success"]
    for metric in METRIC_LABELS:
        values = [value for item in successes if (value := item.metrics()[metric]) is not None]
        summary = stat(values)
        if summary is not None:
            result[metric] = summary
    return result


def attempt_dict(attempt: Attempt) -> dict[str, object]:
    return {
        "id": attempt.attempt_id,
        "pid": attempt.pid,
        "segment": attempt.segment,
        "generation": attempt.generation,
        "side": attempt.side,
        "origin": attempt.origin,
        "cohort": attempt.cohort,
        "first_after_install": attempt.first_after_install,
        "outcome": attempt.outcome,
        "post_first_update_failure": attempt.post_first_update_failure,
        "window_cycle": attempt.window_cycle_id,
        "repeated_show_same_window": attempt.repeated_show_same_window,
        "potential_cancellation_reopen": attempt.potential_cancellation_reopen,
        "evidence": {
            "mode_off": attempt.mode_off_evidence,
            "preempt": attempt.preempt_evidence,
            "teardown": attempt.teardown_evidence,
        },
        "timestamps_ms": {
            "window_first_observed": attempt.window_first_observed_ms,
            "command_poll_observed": attempt.command_poll_observed_ms,
            "command": attempt.command_ms,
            "request": attempt.request_ms,
            "dispatch": attempt.dispatch_ms,
            "renderer_start": (attempt.frame_fields or {}).get("renderer_start_ms"),
            "first_update": (attempt.frame_fields or {}).get("first_update_ms"),
        },
        "metrics_ms": attempt.metrics(),
        "issues": attempt.issues,
    }


def capture_dict(capture: Capture) -> dict[str, object]:
    groups = grouped_attempts(capture)
    return {
        "label": capture.label,
        "source": capture.source,
        "metadata": capture.metadata,
        "warnings": capture.warnings,
        "orphan_events": capture.orphan_events,
        "totals": {
            "attempts": len(capture.attempts),
            "outcomes": dict(sorted(Counter(item.outcome for item in capture.attempts).items())),
            "potential_cancellation_reopens": sum(
                item.potential_cancellation_reopen for item in capture.attempts
            ),
        },
        "attempts": [attempt_dict(item) for item in capture.attempts],
        "overall_success_metrics_ms": metric_stats(capture.attempts),
        "categories": {
            category_name(key): {
                "attempts": len(items),
                "outcomes": dict(sorted(Counter(item.outcome for item in items).items())),
                "metrics_ms": metric_stats(items),
            }
            for key, items in groups.items()
        },
    }


def compare_captures(control: Capture, candidate: Capture) -> dict[str, object]:
    control_groups = grouped_attempts(control)
    candidate_groups = grouped_attempts(candidate)
    matched = sorted(control_groups.keys() & candidate_groups.keys())
    warnings = [
        "median deltas are descriptive; this tool does not verify APK identity, configuration, or causal speedup"
    ]
    for label, capture in (("control", control), ("candidate", candidate)):
        missing = [key for key in ("apk_sha256", "config") if key not in capture.metadata]
        if missing:
            warnings.append(f"{label} metadata missing {','.join(missing)}")
        if any("first-after-install is unknown" in item for item in capture.warnings):
            warnings.append(f"{label} first-after-install status is unknown")
        post_frame_failures = sum(item.post_first_update_failure for item in capture.attempts)
        if post_frame_failures:
            warnings.append(
                f"{label} has {post_frame_failures} AVC failure(s) after first-update; timing exists but stability failed"
            )

    categories: dict[str, object] = {}
    for key in matched:
        left_items = control_groups[key]
        right_items = candidate_groups[key]
        left_successes = sum(item.outcome == "success" for item in left_items)
        right_successes = sum(item.outcome == "success" for item in right_items)
        if left_successes < SMALL_SAMPLE_COUNT or right_successes < SMALL_SAMPLE_COUNT:
            warnings.append(
                f"small sample {category_name(key)}: control success n={left_successes}, "
                f"candidate success n={right_successes}"
            )
        if left_successes != len(left_items) or right_successes != len(right_items):
            warnings.append(
                f"non-success attempts excluded from timing {category_name(key)}: "
                f"control {len(left_items) - left_successes}, candidate {len(right_items) - right_successes}"
            )
        left_stats = metric_stats(left_items)
        right_stats = metric_stats(right_items)
        metrics: dict[str, object] = {}
        for metric in sorted(left_stats.keys() & right_stats.keys()):
            metrics[metric] = {
                "control": left_stats[metric],
                "candidate": right_stats[metric],
                "candidate_minus_control_median_ms": (
                    right_stats[metric]["median"] - left_stats[metric]["median"]
                ),
            }
        categories[category_name(key)] = {
            "control_attempts": len(left_items),
            "candidate_attempts": len(right_items),
            "control_outcomes": dict(sorted(Counter(item.outcome for item in left_items).items())),
            "candidate_outcomes": dict(sorted(Counter(item.outcome for item in right_items).items())),
            "metrics_ms": metrics,
        }

    only_control = sorted(control_groups.keys() - candidate_groups.keys())
    only_candidate = sorted(candidate_groups.keys() - control_groups.keys())
    if only_control:
        warnings.append("unmatched control categories: " + ", ".join(map(category_name, only_control)))
    if only_candidate:
        warnings.append("unmatched candidate categories: " + ", ".join(map(category_name, only_candidate)))
    if not matched:
        warnings.append("no matched origin/side/cohort categories; comparison is inconclusive")

    return {
        "control": capture_dict(control),
        "candidate": capture_dict(candidate),
        "comparison": {
            "category_contract": (
                "origin/side/cohort/window-context must match; first_observed, new-window and "
                "continuous-window-repeat attempts are never pooled"
            ),
            "warnings": list(dict.fromkeys(warnings)),
            "categories": categories,
        },
    }


def _number(value: int | float | None) -> str:
    if value is None:
        return "?"
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


def _stat_text(summary: dict[str, int | float] | None) -> str:
    if not summary:
        return "n=0"
    return (
        f"n={summary['count']} median={_number(summary['median'])} "
        f"min={summary['min']} max={summary['max']} ms"
    )


def render_capture(capture: Capture) -> str:
    lines = [f"Capture: {capture.label} ({capture.source})"]
    outcomes = Counter(item.outcome for item in capture.attempts)
    lines.append(
        f"Attempts: {len(capture.attempts)}; outcomes: "
        + (", ".join(f"{key}={value}" for key, value in sorted(outcomes.items())) or "none")
    )
    lines.append(
        "Potential cancellation reopens: "
        f"{sum(item.potential_cancellation_reopen for item in capture.attempts)} "
        "(continuous-window heuristic; not proof of cancelled intent)"
    )
    lines.append("Attempts:")
    for item in capture.attempts:
        metrics = item.metrics()
        flags = []
        if item.potential_cancellation_reopen:
            flags.append(
                "potential-reopen["
                + ",".join(
                    name
                    for name, present in (
                        ("OFF", item.mode_off_evidence),
                        ("preempt", item.preempt_evidence),
                        ("teardown", item.teardown_evidence),
                    )
                    if present
                )
                + "]"
            )
        if item.post_first_update_failure:
            flags.append("post-first-update-AVC-failure")
        cold = " explicit-first-after-install" if item.first_after_install else ""
        lines.append(
            f"  {item.attempt_id} pid={item.pid}/{item.segment} gen={item.generation or '?'} "
            f"{item.side} {item.origin}/{item.cohort}{cold} outcome={item.outcome}; "
            f"window->request={_number(metrics['window_first_observed_to_request_ms'])} ms, "
            f"request->first-update={_number(metrics['request_to_first_update_ms'])} ms"
            + (f"; {','.join(flags)}" if flags else "")
        )
        for issue in item.issues:
            lines.append(f"    inconclusive: {issue}")

    lines.append("Overall successful timing (mixed descriptive roll-up; never used as matched A/B):")
    overall = metric_stats(capture.attempts)
    for metric in HUMAN_METRICS:
        if metric in overall:
            lines.append(f"  {METRIC_LABELS[metric]}: {_stat_text(overall[metric])}")

    lines.append("Exact cohorts:")
    for key, items in grouped_attempts(capture).items():
        success_count = sum(item.outcome == "success" for item in items)
        lines.append(
            f"  {category_name(key)}: attempts={len(items)} success={success_count}"
        )
        summaries = metric_stats(items)
        for metric in HUMAN_METRICS:
            if metric in summaries:
                lines.append(f"    {METRIC_LABELS[metric]}: {_stat_text(summaries[metric])}")

    if capture.warnings:
        lines.append("Warnings:")
        lines.extend(f"  - {warning}" for warning in capture.warnings)
    if capture.orphan_events:
        lines.append("Orphan events:")
        lines.extend(f"  - {event}" for event in capture.orphan_events)
    lines.append(
        "Boundary: first-update is a TextureView callback, not proof of correct pixels or physical scanout."
    )
    return "\n".join(lines)


def render_comparison(result: dict[str, object]) -> str:
    comparison = result["comparison"]
    assert isinstance(comparison, dict)
    control = result["control"]
    candidate = result["candidate"]
    assert isinstance(control, dict) and isinstance(candidate, dict)
    lines = [f"Comparison: {control['label']} -> {candidate['label']}"]
    lines.append(str(comparison["category_contract"]))
    categories = comparison["categories"]
    assert isinstance(categories, dict)
    for name, raw_category in categories.items():
        category = raw_category
        assert isinstance(category, dict)
        lines.append(
            f"Matched {name}: attempts control={category['control_attempts']} "
            f"candidate={category['candidate_attempts']}"
        )
        lines.append(
            "  outcomes: control="
            + ",".join(f"{key}:{value}" for key, value in category["control_outcomes"].items())
            + "; candidate="
            + ",".join(f"{key}:{value}" for key, value in category["candidate_outcomes"].items())
        )
        metrics = category["metrics_ms"]
        assert isinstance(metrics, dict)
        for metric in HUMAN_METRICS:
            if metric not in metrics:
                continue
            item = metrics[metric]
            assert isinstance(item, dict)
            lines.append(
                f"  {METRIC_LABELS[metric]}: control {_stat_text(item['control'])}; "
                f"candidate {_stat_text(item['candidate'])}; "
                f"candidate-control median={_number(item['candidate_minus_control_median_ms'])} ms"
            )
    lines.append("Warnings:")
    lines.extend(f"  - {warning}" for warning in comparison["warnings"])
    lines.append("No percentage or speedup claim is inferred from these descriptive deltas.")
    return "\n".join(lines)


def read_capture(path: Path, label: str | None, metadata: dict[str, str]) -> Capture:
    with path.open(encoding="utf-8", errors="replace") as stream:
        return analyze_lines(stream, source=str(path), label=label, metadata=metadata)


def write_json(path: str, payload: dict[str, object]) -> None:
    rendered = json.dumps(payload, indent=2, sort_keys=True) + "\n"
    if path == "-":
        sys.stdout.write(rendered)
        return
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(rendered, encoding="utf-8")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    summary = subparsers.add_parser("summary", help="summarize one logcat capture")
    summary.add_argument("log", type=Path)
    summary.add_argument("--label")
    summary.add_argument("--meta", action="append", default=[], metavar="KEY=VALUE")
    summary.add_argument("--json", metavar="PATH|-", help="also write machine-readable JSON")

    compare = subparsers.add_parser("compare", help="compare only matched timing cohorts")
    compare.add_argument("control", type=Path)
    compare.add_argument("candidate", type=Path)
    compare.add_argument("--control-label")
    compare.add_argument("--candidate-label")
    compare.add_argument("--control-meta", action="append", default=[], metavar="KEY=VALUE")
    compare.add_argument("--candidate-meta", action="append", default=[], metavar="KEY=VALUE")
    compare.add_argument("--json", metavar="PATH|-", help="also write machine-readable JSON")
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        if args.command == "summary":
            capture = read_capture(args.log, args.label, parse_metadata(args.meta))
            payload = capture_dict(capture)
            if args.json == "-":
                write_json("-", payload)
            else:
                print(render_capture(capture))
                if args.json:
                    write_json(args.json, payload)
            return 0

        control = read_capture(
            args.control,
            args.control_label,
            parse_metadata(args.control_meta),
        )
        candidate = read_capture(
            args.candidate,
            args.candidate_label,
            parse_metadata(args.candidate_meta),
        )
        result = compare_captures(control, candidate)
        if args.json == "-":
            write_json("-", result)
        else:
            print(render_comparison(result))
            if args.json:
                write_json(args.json, result)
        return 0
    except (OSError, ValueError) as error:
        parser.error(str(error))
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
