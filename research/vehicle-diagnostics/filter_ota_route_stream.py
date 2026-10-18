#!/usr/bin/env python3
"""Offline bounded filter for future two-tag OTA observation.

This tool never calls ADB, changes properties, starts OTA, or opens a socket.
Malformed relevant, truncated, overlong and undecodable records become a fixed gap marker.
Only exact stock events approved by parse_ota_route_logs are retained. The marker
also breaks correlation in that parser; filtering must never silently bridge a
missing identity/index/route event. An input path is not emitted in the report.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
from typing import Callable

from parse_ota_route_logs import GAP_MARKER, needs_gap_marker, sanitize_threadtime_line

TAGS = ("OTA_GCY_UPGRADE_DiagnosePresenter", "OTA_GCY_UPGRADE_CanDiagnostic")
MAX_LINE_BYTES = 1024
MAX_INPUT_BYTES = 4 * 1024 * 1024
MAX_EVENTS = 20000
TRANSPORT_GAPS = frozenset(("transport_eof", "transport_error", "logcat_drop", "observer_restart", "capture_cutoff"))
_HEADER = re.compile(
    r"^\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3,6}\s+"
    r"[1-9]\d{0,9}\s+[1-9]\d{0,9}\s+[VDIWEF]\s+"
    r"(?P<tag>OTA_GCY_UPGRADE_(?:DiagnosePresenter|CanDiagnostic))\s*: ")


class FilterLimitError(ValueError):
    """Code only: never embed an input line in an exception."""


class RouteLogFilter:
    def __init__(self, emit: Callable[[str], None], *, max_input_bytes=MAX_INPUT_BYTES,
                 max_events=MAX_EVENTS):
        if not 1 <= max_input_bytes <= MAX_INPUT_BYTES or not 1 <= max_events <= MAX_EVENTS:
            raise ValueError("invalid_filter_bounds")
        self.emit = emit
        self.max_input_bytes = max_input_bytes
        self.max_events = max_events
        self.pending = bytearray()
        self.discarding = False
        self.finished = False
        self.failed = False
        self.input_bytes = 0
        self.input_hash = hashlib.sha256()
        self.accepted = 0
        self.gaps = 0
        self.rejected = {}
        self.ignored = {}
        self.last_was_gap = False

    def _gap(self, reason):
        self.rejected[reason] = self.rejected.get(reason, 0) + 1
        if not self.last_was_gap:
            self.emit(GAP_MARKER + "\n")
            self.gaps += 1
            self.last_was_gap = True

    def _line(self, raw):
        try:
            line = raw.decode("utf-8", errors="strict")
        except UnicodeError:
            self._gap("invalid_utf8")
            return
        if not _HEADER.match(line):
            if any(tag in line for tag in TAGS) and needs_gap_marker(line):
                self._gap("malformed_exact_tag_record")
            else:
                self.ignored["outside_exact_two_tags"] = self.ignored.get("outside_exact_two_tags", 0) + 1
            return
        canonical = sanitize_threadtime_line(line)
        if canonical is None:
            if needs_gap_marker(line):
                self._gap("invalid_relevant_event")
            else:
                self.ignored["routine_or_unrelated_event"] = self.ignored.get("routine_or_unrelated_event", 0) + 1
            return
        if self.accepted >= self.max_events:
            self.failed = True
            self._gap("event_limit")
            raise FilterLimitError("event_limit")
        self.emit(canonical + "\n")
        self.accepted += 1
        self.last_was_gap = False

    def mark_gap(self, reason: str):
        """Future transport adapter hook. Only fixed, content-free reason codes."""
        if reason not in TRANSPORT_GAPS:
            raise ValueError("unknown_transport_gap_reason")
        if self.finished or self.failed:
            raise FilterLimitError("filter_closed")
        self.pending.clear()
        self.discarding = False
        self._gap(reason)

    def feed(self, chunk: bytes):
        if self.finished or self.failed:
            raise FilterLimitError("filter_closed")
        if type(chunk) is not bytes:
            raise TypeError("bytes_required")
        if self.input_bytes + len(chunk) > self.max_input_bytes:
            self.failed = True
            self._gap("input_limit")
            raise FilterLimitError("input_limit")
        self.input_bytes += len(chunk)
        self.input_hash.update(chunk)
        for byte in chunk:
            if byte == 10:
                if self.discarding:
                    self.discarding = False
                else:
                    self._line(bytes(self.pending))
                self.pending.clear()
            elif self.discarding:
                continue
            elif len(self.pending) >= MAX_LINE_BYTES:
                self.pending.clear()
                self.discarding = True
                self._gap("overlong_record")
            else:
                self.pending.append(byte)

    def finish(self):
        if self.finished:
            return
        if self.pending or self.discarding:
            self._gap("incomplete_final_record")
        self.pending.clear()
        self.discarding = False
        self.finished = True

    def report(self):
        if not self.finished:
            raise ValueError("finish_required")
        return {
            "schemaVersion": 1, "offlineOnly": True, "observerStarted": False,
            "eligibleForProductAllowlist": False, "exactTags": list(TAGS),
            "inputBytesHashed": self.input_bytes,
            "inputSha256": self.input_hash.hexdigest(),
            "fingerprintScope": "processed_prefix" if self.failed else "whole_input",
            "acceptedRecords": self.accepted, "gapMarkers": self.gaps,
            "rejectedRecordsByReason": self.rejected,
            "ignoredRecordsByReason": self.ignored,
            "completeInput": not self.failed and not self.rejected.get("incomplete_final_record"),
            "captureCoverageVerified": False,
            "limits": {"lineBytes": MAX_LINE_BYTES, "inputBytes": self.max_input_bytes,
                       "acceptedRecords": self.max_events},
        }


def _new_file(path: Path):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    return os.fdopen(fd, "w", encoding="utf-8", newline="\n")


def filter_file(source: Path, destination: Path):
    # A fresh directory protects prior evidence and makes partial output explicit.
    destination.mkdir(mode=0o700, parents=False, exist_ok=False)
    error = None
    with _new_file(destination / "events.log") as output:
        filtered = RouteLogFilter(output.write)
        try:
            with source.open("rb") as stream:
                while chunk := stream.read(4096):
                    filtered.feed(chunk)
        except FilterLimitError as exc:
            error = str(exc)
        except OSError:
            error = "input_io_error"
            filtered.failed = True
            filtered._gap(error)
        finally:
            filtered.finish()
    report = filtered.report()
    report["error"] = error
    with _new_file(destination / "filter-report.json") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args(argv)
    try:
        report = filter_file(args.input, args.output_dir)
    except OSError:
        parser.error("cannot create fresh private evidence directory; no contents emitted")
    print(json.dumps({"offlineOnly": True, "acceptedRecords": report["acceptedRecords"],
                      "gapMarkers": report["gapMarkers"], "error": report["error"]}))
    return 1 if report["error"] else 0


if __name__ == "__main__":
    sys.exit(main())
