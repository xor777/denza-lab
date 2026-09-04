#!/usr/bin/env python3
"""Rank raw-CAN payload bits against labelled turn-indicator phases.

The capture is produced by raw_can_turn_probe.sh. The first part of every
label must be one of neutral, left, right, or hazard; suffixes identify repeats
(for example, left_2). The first part of each phase is ignored by default so
human reaction time does not become evidence for a CAN bit.
"""

from __future__ import annotations

import argparse
import bisect
import collections
import dataclasses
import re
import sys
from pathlib import Path
from typing import Iterable


STATES = ("neutral", "left", "right", "hazard")
FRAME_RE = re.compile(
    r"FRAME seq=(?P<seq>\d+) t_ns=(?P<time>\d+) "
    r"id=0x(?P<id>[0-9A-Fa-f]+) sub=0x(?P<sub>[0-9A-Fa-f]+) "
    r"ch=(?P<channel>\d+) cnt=(?P<counter>\d+) len=(?P<length>\d+) "
    r"payload=(?P<payload>[0-9A-Fa-f]*)"
)
MARK_RE = re.compile(r"MARK t_ns=(?P<time>\d+) label=(?P<label>[A-Za-z0-9_-]+)")
DONE_RE = re.compile(
    r"DONE received=(?P<received>\d+) dropped=(?P<dropped>\d+) "
    r"callback_errors=(?P<errors>\d+) queued=(?P<queued>\d+)"
)


@dataclasses.dataclass(frozen=True)
class Address:
    can_id: int
    sub_id: int
    channel: int
    payload_length: int

    def display(self) -> str:
        return (
            f"id=0x{self.can_id:X} sub=0x{self.sub_id:02X} "
            f"ch={self.channel} len={self.payload_length}"
        )


@dataclasses.dataclass(frozen=True)
class Frame:
    sequence: int
    time_ns: int
    address: Address
    payload: bytes


@dataclasses.dataclass(frozen=True)
class Marker:
    time_ns: int
    label: str
    state: str


@dataclasses.dataclass(frozen=True)
class BitCandidate:
    address: Address
    bit_index: int
    fractions: dict[str, float]
    samples: dict[str, int]
    left_score: float
    left_active_high: bool
    right_score: float
    right_active_high: bool

    @property
    def byte_index(self) -> int:
        return self.bit_index // 8

    @property
    def bit_in_byte(self) -> int:
        return self.bit_index % 8


@dataclasses.dataclass(frozen=True)
class Capture:
    frames: list[Frame]
    markers: list[Marker]
    received: int | None
    dropped: int | None
    callback_errors: int | None


def canonical_state(label: str) -> str | None:
    lowered = label.lower()
    for state in STATES:
        if lowered == state or lowered.startswith(state + "_") or lowered.startswith(state + "-"):
            return state
    return None


def parse_capture(lines: Iterable[str]) -> Capture:
    frames: list[Frame] = []
    markers: list[Marker] = []
    received = dropped = callback_errors = None

    for line in lines:
        marker_match = MARK_RE.search(line)
        if marker_match:
            label = marker_match.group("label")
            state = canonical_state(label)
            if state is not None:
                markers.append(Marker(int(marker_match.group("time")), label, state))
            continue

        frame_match = FRAME_RE.search(line)
        if frame_match:
            declared_length = int(frame_match.group("length"))
            try:
                payload = bytes.fromhex(frame_match.group("payload"))
            except ValueError:
                continue
            if len(payload) != declared_length:
                continue
            address = Address(
                int(frame_match.group("id"), 16),
                int(frame_match.group("sub"), 16),
                int(frame_match.group("channel")),
                declared_length,
            )
            frames.append(
                Frame(
                    int(frame_match.group("seq")),
                    int(frame_match.group("time")),
                    address,
                    payload,
                )
            )
            continue

        done_match = DONE_RE.search(line)
        if done_match:
            received = int(done_match.group("received"))
            dropped = int(done_match.group("dropped"))
            callback_errors = int(done_match.group("errors"))

    markers.sort(key=lambda marker: marker.time_ns)
    frames.sort(key=lambda frame: frame.time_ns)
    return Capture(frames, markers, received, dropped, callback_errors)


def labelled_frames(capture: Capture, settle_ms: int) -> list[tuple[str, Frame]]:
    if not capture.markers:
        return []
    marker_times = [marker.time_ns for marker in capture.markers]
    settle_ns = settle_ms * 1_000_000
    result: list[tuple[str, Frame]] = []
    for frame in capture.frames:
        marker_index = bisect.bisect_right(marker_times, frame.time_ns) - 1
        if marker_index < 0:
            continue
        marker = capture.markers[marker_index]
        if frame.time_ns - marker.time_ns < settle_ns:
            continue
        result.append((marker.state, frame))
    return result


def separation_score(
    fractions: dict[str, float],
    positive_states: tuple[str, str],
    negative_states: tuple[str, str],
) -> tuple[float, bool]:
    positives = [fractions[state] for state in positive_states]
    negatives = [fractions[state] for state in negative_states]
    high_contrast = sum(positives) / 2.0 - sum(negatives) / 2.0
    high_margin = min(positives) - max(negatives)
    low_contrast = -high_contrast
    low_margin = min(negatives) - max(positives)
    if high_contrast >= low_contrast:
        return 0.7 * max(0.0, high_contrast) + 0.3 * max(0.0, high_margin), True
    return 0.7 * max(0.0, low_contrast) + 0.3 * max(0.0, low_margin), False


def rank_bits(labelled: list[tuple[str, Frame]]) -> list[BitCandidate]:
    payloads: dict[Address, dict[str, list[bytes]]] = collections.defaultdict(
        lambda: collections.defaultdict(list)
    )
    for state, frame in labelled:
        payloads[frame.address][state].append(frame.payload)

    candidates: list[BitCandidate] = []
    for address, by_state in payloads.items():
        if any(not by_state.get(state) for state in STATES):
            continue
        sample_counts = {state: len(by_state[state]) for state in STATES}
        for bit_index in range(address.payload_length * 8):
            byte_index = bit_index // 8
            mask = 1 << (bit_index % 8)
            fractions = {
                state: sum(1 for payload in by_state[state] if payload[byte_index] & mask)
                / len(by_state[state])
                for state in STATES
            }
            left_score, left_active_high = separation_score(
                fractions, ("left", "hazard"), ("neutral", "right")
            )
            right_score, right_active_high = separation_score(
                fractions, ("right", "hazard"), ("neutral", "left")
            )
            if max(left_score, right_score) >= 0.05:
                candidates.append(
                    BitCandidate(
                        address,
                        bit_index,
                        fractions,
                        sample_counts,
                        left_score,
                        left_active_high,
                        right_score,
                        right_active_high,
                    )
                )
    return candidates


def active_fraction(candidate: BitCandidate, state: str, active_high: bool) -> float:
    value = candidate.fractions[state]
    return value if active_high else 1.0 - value


def pair_score(left: BitCandidate, right: BitCandidate) -> float:
    expected = {
        "neutral": (0.0, 0.0),
        "left": (1.0, 0.0),
        "right": (0.0, 1.0),
        "hazard": (1.0, 1.0),
    }
    correct = []
    for state, (left_expected, right_expected) in expected.items():
        left_active = active_fraction(left, state, left.left_active_high)
        right_active = active_fraction(right, state, right.right_active_high)
        correct.append(left_active if left_expected else 1.0 - left_active)
        correct.append(right_active if right_expected else 1.0 - right_active)
    return sum(correct) / len(correct)


def rank_pairs(candidates: list[BitCandidate]) -> list[tuple[float, BitCandidate, BitCandidate]]:
    by_address: dict[Address, list[BitCandidate]] = collections.defaultdict(list)
    for candidate in candidates:
        by_address[candidate.address].append(candidate)

    pairs = []
    for address_candidates in by_address.values():
        left = sorted(address_candidates, key=lambda item: item.left_score, reverse=True)[:24]
        right = sorted(address_candidates, key=lambda item: item.right_score, reverse=True)[:24]
        for left_candidate in left:
            for right_candidate in right:
                if left_candidate.bit_index == right_candidate.bit_index:
                    continue
                pairs.append(
                    (
                        pair_score(left_candidate, right_candidate),
                        left_candidate,
                        right_candidate,
                    )
                )
    return sorted(pairs, key=lambda item: item[0], reverse=True)


def bit_description(candidate: BitCandidate, side: str) -> str:
    score = candidate.left_score if side == "left" else candidate.right_score
    active_high = candidate.left_active_high if side == "left" else candidate.right_active_high
    fractions = " ".join(f"{state[0].upper()}={candidate.fractions[state]:.2f}" for state in STATES)
    return (
        f"score={score:.3f} {candidate.address.display()} "
        f"byte={candidate.byte_index} bit={candidate.bit_in_byte} "
        f"active={'high' if active_high else 'low'} {fractions}"
    )


def sequence_gaps(frames: list[Frame]) -> int:
    gaps = 0
    for previous, current in zip(frames, frames[1:]):
        if current.sequence > previous.sequence + 1:
            gaps += current.sequence - previous.sequence - 1
    return gaps


def print_report(capture: Capture, settle_ms: int, top: int) -> int:
    labelled = labelled_frames(capture, settle_ms)
    states_present = {state for state, _ in labelled}
    missing_states = [state for state in STATES if state not in states_present]

    print("capture summary")
    print(f"  parsed frames: {len(capture.frames)}")
    print(f"  phase markers: {len(capture.markers)}")
    print(f"  labelled frames after {settle_ms} ms settling: {len(labelled)}")
    print(f"  sequence gaps in printed stream: {sequence_gaps(capture.frames)}")
    if capture.received is not None:
        print(
            "  device summary: "
            f"received={capture.received} dropped={capture.dropped} "
            f"callback_errors={capture.callback_errors}"
        )
    if missing_states:
        print("missing labelled states: " + ", ".join(missing_states), file=sys.stderr)
        return 2

    candidates = rank_bits(labelled)
    if not candidates:
        print("no payload bit produced a repeatable state contrast")
        return 3

    print("\nbest LEFT candidates")
    for candidate in sorted(candidates, key=lambda item: item.left_score, reverse=True)[:top]:
        print("  " + bit_description(candidate, "left"))

    print("\nbest RIGHT candidates")
    for candidate in sorted(candidates, key=lambda item: item.right_score, reverse=True)[:top]:
        print("  " + bit_description(candidate, "right"))

    print("\nbest LEFT/RIGHT pairs on one frame")
    for score, left, right in rank_pairs(candidates)[:top]:
        print(
            f"  pair_score={score:.3f} {left.address.display()} "
            f"left=byte{left.byte_index}.bit{left.bit_in_byte}"
            f"({'H' if left.left_active_high else 'L'}) "
            f"right=byte{right.byte_index}.bit{right.bit_in_byte}"
            f"({'H' if right.right_active_high else 'L'})"
        )
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("capture", type=Path)
    parser.add_argument("--settle-ms", type=int, default=1500)
    parser.add_argument("--top", type=int, default=12)
    args = parser.parse_args()
    if args.settle_ms < 0:
        parser.error("--settle-ms must not be negative")
    if args.top < 1:
        parser.error("--top must be positive")
    with args.capture.open(encoding="utf-8", errors="replace") as capture_file:
        capture = parse_capture(capture_file)
    return print_report(capture, args.settle_ms, args.top)


if __name__ == "__main__":
    raise SystemExit(main())
