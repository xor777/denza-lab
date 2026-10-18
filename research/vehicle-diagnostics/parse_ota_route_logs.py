"""Offline extraction of short stock OTA logs; never starts/captures a workflow.

No HTTP, raw log bodies, filenames, tokens or vehicle identifiers are emitted.
Ordered cross-thread observations retain context, but never assign an ECU.
Source contract and applicability are described in the adjacent research report.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass, field
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
from typing import Iterable

PREFIX = r"OTA_(?:GCY|GHY)_UPGRADE_"
# A collector may emit this constant instead of private/invalid/dropped data.
# It is deliberately not a firmware event and breaks every pending sequence.
GAP_MARKER = "OTA_GCY_UPGRADE_DiagnosePresenter OBSERVER_GAP"
LINE = re.compile(r"^(?P<time>\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3,6})\s+"
                  r"(?P<pid>[1-9]\d{0,9})\s+(?P<tid>[1-9]\d{0,9})\s+"
                  r"(?P<level>[VDIWEF])\s+(?P<tag>" + PREFIX +
                  r"(?:App|DiagnosePresenter|CanDiagnostic))\s*: (?P<body>.*)$")
ID = r"(?:mcu_default_id|[A-Za-z][A-Za-z0-9]{0,23}_[A-Za-z0-9_.-]{1,71})"
HEX = r"[0-9A-Fa-f]{1,4}"
PAIR = rf"{HEX}/{HEX}"
DIDS = r"[0-9A-Fa-f]{4}/[0-9A-Fa-f]{4}"
OPERATIONS = {
    "sendEcuDiagnoseAvailableDiag": None,
    "sendEcuMainVersionCanInfo": "ecuDId",
    "sendEcuMainSoftCodeCanInfo": "ecuDId",
    "sendEcuHardVersionCanInfo": "ecuHardDid",
    # Firmware logs this same label for BOTH config version and config code.
    "sendEcuConfigSoftCodeCanInfo": "ecuDId",
}
EXACT = {
    "requestEcuListInfo: called.": "cycle",
    "clear: called.": "clear",
    "cancelLoopRequestEcuListInfo: called.": "cancel",
    "dealCancelLoopRequestEcuListInfoEvent: called.": "cancel",
    "loopRequestEcuItemInfo reset mReceiveDid array": "reset",
    ">>>>>--------App Application onCreate Start--------": "process_start",
}
PATTERNS = [
    ("index", re.compile(r"loopRequestEcuItemInfo called, mEcuListInfo index : (?P<value>\d{1,6})")),
    ("retry", re.compile(r"loopRequestEcuItemInfo mEcuListInfoCurrentRetryCount : (?P<value>\d{1,3})")),
    ("cancel_flag", re.compile(r"loopRequestEcuItemInfo mCancelLoopRequestEcuListInfo : (?P<value>true|false)")),
    ("item", re.compile(r"loopRequestEcuItemInfo item\.ecuId: (?P<value>" + ID + r")")),
    ("loop_again", re.compile(r"loopRequestEcuItemInfo needLoopAgain: (?P<value>true|false)")),
    ("quit", re.compile(r"quitLoopRequestEcuListInfo validCarPowerLevel: (?P<value>true|false)")),
    ("metadata", re.compile(r"loopRequestEcuItemInfo item\.(?P<name>ecuSoftCode|ecuSoftVer|ecuHardVer|ecuConfigCode|ecuConfigVer): (?P<value>[A-Za-z0-9_.-]{0,64})")),
]
RELEVANT = re.compile(r"^(?:requestEcuListInfo:|loopRequestEcuItemInfo (?:called,|mEcuListInfoCurrentRetryCount|mCancelLoopRequestEcuListInfo|item\.|reset )|sendEcu|clear:|cancelLoopRequest|dealCancelLoopRequest|quitLoopRequest)")


def _decode_line(line: str) -> dict | None:
    """Return only known events; malformed relevant bodies carry no body/value."""
    m = LINE.fullmatch(line.rstrip("\r\n"))
    if not m:
        return None
    try:
        datetime.strptime("2000-" + m["time"], "%Y-%m-%d %H:%M:%S.%f")
    except ValueError:
        return None
    body, tag = m["body"], m["tag"]
    role = tag.rsplit("_", 1)[1]
    result = {"timestamp": m["time"], "pid": int(m["pid"]), "tid": int(m["tid"]),
              "priority": m["level"], "tag": tag, "family": tag.rsplit("_", 1)[0]}
    kind = EXACT.get(body)
    if kind:
        required = "App" if kind == "process_start" else "CanDiagnostic" if kind == "reset" else "DiagnosePresenter"
        if role == required:
            return dict(result, kind=kind, canonicalBody=body)
    if role == "DiagnosePresenter":
        for kind, pattern in PATTERNS:
            n = pattern.fullmatch(body)
            if n:
                return dict(result, kind=kind, canonicalBody=body, **n.groupdict())
    if role == "CanDiagnostic":
        for operation, prop in OPERATIONS.items():
            suffix = "" if prop is None else rf" , {prop}: (?P<did>{DIDS if prop == 'ecuDId' else '[0-9A-Fa-f]{4}'})"
            n = re.fullmatch(rf"{operation} ecuDiaId: (?P<pair>{PAIR}){suffix}", body)
            if n:
                request, response = [int(x, 16) for x in n["pair"].split("/")]
                if request == 0 or response == 0:
                    break
                return dict(result, kind="route", canonicalBody=body, operation=operation,
                            pair={"printed": n["pair"], "request": request, "response": response},
                            didField=prop, didPrinted=n.groupdict().get("did"),
                            dids=[int(x, 16) for x in n["did"].split("/")] if prop else [])
    if RELEVANT.match(body):
        return dict(result, kind="invalid")
    return None


def sanitize_threadtime_line(line: str) -> str | None:
    """Strict allowlist for offline/future collectors; no unknown/raw data retained."""
    e = _decode_line(line)
    if not e or e["kind"] == "invalid":
        return None
    return f"{e['timestamp']} {e['pid']:5d} {e['tid']:5d} {e['priority']} {e['tag']}: {e['canonicalBody']}"


def needs_gap_marker(line: str) -> bool:
    """Discarded structural records must not disappear between valid events.

    Routine unknown messages, HTTP tags and post-loop e.ecuId are ignored.
    Collectors must also emit GAP_MARKER for their own byte/UTF-8/drop gaps.
    """
    line = line.rstrip("\r\n")
    if line == GAP_MARKER:
        return True
    if not re.search(PREFIX + r"(?:App|DiagnosePresenter|CanDiagnostic)(?:\s|:|$)", line):
        return False
    m = LINE.fullmatch(line)
    if not m:
        return True
    try:
        datetime.strptime("2000-" + m["time"], "%Y-%m-%d %H:%M:%S.%f")
    except ValueError:
        return True
    e = _decode_line(line)
    return e is not None and e["kind"] == "invalid"


def _anchor(e: dict, line: int) -> dict:
    return {"line": line, **{k: e[k] for k in ("timestamp", "pid", "tid", "priority", "tag")}}


@dataclass
class Candidate:
    ordinal: int
    cycle: int | None
    index: int
    index_event: dict
    retry: int | None = None
    identities: list = field(default_factory=list)
    resets: list = field(default_factory=list)
    routes: list = field(default_factory=list)
    metadata: dict = field(default_factory=dict)
    reasons: set = field(default_factory=set)


class Parser:
    def __init__(self):
        self.states = {}
        self.candidates = []
        self.observations = []
        self.rejections = []
        self.cycle = 0
        self.ordinal = 0

    def close(self, s, reason, anchor=None, normal=False):
        c = s.get("candidate")
        if c is None:
            return
        s["candidate"] = None
        ids = {x["ecuId"] for x in c.identities}
        if len(c.identities) != 1:
            c.reasons.add("missing_or_multiple_item_identity")
        if len(c.resets) != 1 or not c.routes:
            c.reasons.add("incomplete_index_item_reset_route_sequence")
        if c.cycle is None:
            c.reasons.add("missing_cycle_start")
        if not normal:
            c.reasons.add(reason)
        if anchor and anchor["tid"] != c.index_event["tid"]:
            c.reasons.add("closing_tid_differs")
        tids = {c.index_event["tid"]}
        tids.update(x["event"]["tid"] for x in c.identities)
        tids.update(x["tid"] for x in c.resets)
        tids.update(self.observations[x - 1]["event"]["tid"] for x in c.routes)
        if len(tids) != 1:
            c.reasons.add("cross_thread_identity_route_unverified")
        pairs = {tuple(self.observations[x - 1]["route"][k] for k in ("request", "response")) for x in c.routes}
        if len(pairs) > 1:
            c.reasons.add("conflicting_route_pairs")
        did_by_op = {}
        for x in c.routes:
            o = self.observations[x - 1]
            prev = did_by_op.setdefault(o["operation"], o["dids"])
            if prev != o["dids"]:
                c.reasons.add("conflicting_dids_for_operation")
        ecu_id = next(iter(ids)) if len(ids) == 1 else None
        qualified = not c.reasons
        for x in c.routes:
            o = self.observations[x - 1]
            o["assignedEcuId"] = ecu_id if qualified else None
            o["correlation"] = "same_thread_closed_stock_sequence" if qualified else "ordered_stock_candidate_unverified"
            o["reasons"] = sorted(c.reasons)
        self.candidates.append({"ordinal": c.ordinal, "cycleOrdinal": c.cycle,
            "index": c.index, "retryCount": c.retry, "ecuId": ecu_id,
            "indexEvent": c.index_event, "identityObservations": c.identities,
            "resetEvents": c.resets, "routeObservationOrdinals": c.routes,
            "initialItemMetadata": c.metadata, "closingEvent": anchor,
            "closingReason": reason, "correlationQualified": qualified,
            "reasons": sorted(c.reasons)})

    def break_all(self, reason):
        for s in self.states.values():
            self.close(s, reason)
            s["cycle"] = None

    def feed(self, e, line):
        a = _anchor(e, line)
        family, pid, kind = e["family"], e["pid"], e["kind"]
        for (f, p), other in self.states.items():
            if f == family and p != pid:
                self.close(other, "pid_changed", a)
                other["cycle"] = None
        key = (family, pid)
        s = self.states.setdefault(key, {"cycle": None, "candidate": None, "lastTime": None, "cycleReasons": set()})
        if s["lastTime"] and e["timestamp"] < s["lastTime"]:
            self.close(s, "timestamp_regression", a)
            s["cycle"] = None
        s["lastTime"] = e["timestamp"]
        if kind == "process_start":
            self.close(s, "process_restart", a)
            s["cycle"] = None
            return
        if kind == "cycle":
            overlap = s["candidate"] is not None
            self.close(s, "overlapping_or_restarted_cycle", a)
            self.cycle += 1
            s["cycle"] = self.cycle
            s["cycleReasons"] = {"overlapping_or_restarted_cycle"} if overlap else set()
            return
        c = s["candidate"]
        if kind == "index":
            index = int(e["value"])
            incomplete = c is not None and bool(c.identities) and not c.routes
            if c:
                if index != c.index + 1:
                    c.reasons.add("nonsequential_candidate_boundary")
                self.close(s, "next_index", a, normal=True)
            self.ordinal += 1
            s["candidate"] = Candidate(self.ordinal, s["cycle"], index, a)
            s["candidate"].reasons.update(s["cycleReasons"])
            if incomplete:
                s["candidate"].reasons.add("preceding_candidate_incomplete_overlap")
            return
        if kind in ("clear", "cancel", "quit") or (kind == "cancel_flag" and e["value"] == "true"):
            self.close(s, kind, a, normal=kind == "quit")
            s["cycle"] = None
            return
        if kind == "invalid":
            if c:
                c.reasons.add("invalid_or_truncated_relevant_record")
            self.rejections.append({"event": a, "reason": "invalid_or_truncated_relevant_record"})
            return
        if kind == "item":
            if c:
                if c.resets or c.routes:
                    c.reasons.add("item_after_reset_or_route")
                c.identities.append({"ecuId": e["value"], "event": a})
            else:
                self.rejections.append({"event": a, "reason": "item_without_index", "ecuId": e["value"]})
        elif kind == "retry" and c:
            if c.retry is not None or c.identities or a["tid"] != c.index_event["tid"]:
                c.reasons.add("retry_boundary_conflict")
            c.retry = int(e["value"])
        elif kind == "metadata" and c:
            if len(c.identities) != 1 or c.resets or a["tid"] != c.identities[0]["event"]["tid"]:
                c.reasons.add("metadata_outside_item_sequence")
            elif e["name"] in c.metadata and c.metadata[e["name"]]["value"] != e["value"]:
                c.reasons.add("conflicting_item_metadata")
            else:
                c.metadata[e["name"]] = {"value": e["value"], "event": a}
        elif kind == "reset":
            if c:
                if len(c.identities) != 1 or c.resets or c.routes:
                    c.reasons.add("missing_item_or_overlapping_reset")
                c.resets.append(a)
            else:
                self.rejections.append({"event": a, "reason": "reset_without_candidate"})
        elif kind == "route":
            o = {"ordinal": len(self.observations) + 1, "event": a,
                 "operation": e["operation"], "route": e["pair"],
                 "didField": e["didField"], "didPrinted": e["didPrinted"], "dids": e["dids"],
                 "operationAmbiguity": "config_version_or_code" if e["operation"] == "sendEcuConfigSoftCodeCanInfo" else None,
                 "contextCandidateOrdinal": c.ordinal if c else None,
                 "assignedEcuId": None, "correlation": "unqualified_orphan",
                 "reasons": ["route_without_candidate"], "responseStatus": "not_observed",
                 "eligibleForProductAllowlist": False}
            self.observations.append(o)
            if c:
                if len(c.identities) != 1 or len(c.resets) != 1:
                    c.reasons.add("route_before_unique_item_and_reset")
                c.routes.append(o["ordinal"])


def parse_bytes(data: bytes, source_ordinal: int = 1) -> dict:
    parser = Parser()
    text = data.decode("utf-8", errors="strict")
    lines = text.splitlines()
    for number, line in enumerate(lines, 1):
        if number == len(lines) and not data.endswith(b"\n"):
            parser.break_all("unterminated_final_log_line")
            parser.rejections.append({"line": number, "reason": "unterminated_final_log_line"})
            break
        if line == GAP_MARKER:
            parser.break_all("observer_gap")
            parser.rejections.append({"line": number, "reason": "observer_gap"})
            continue
        e = _decode_line(line)
        if e:
            parser.feed(e, number)
        elif needs_gap_marker(line):
            parser.break_all("malformed_threadtime_record")
            parser.rejections.append({"line": number, "reason": "malformed_threadtime_record"})
    parser.break_all("end_of_file_without_closing_boundary")
    return {"sourceOrdinal": source_ordinal, "sha256": hashlib.sha256(data).hexdigest(),
            "bytes": len(data), "endsWithNewline": not data or data.endswith(b"\n"),
            "candidates": parser.candidates, "routeObservations": parser.observations,
            "rejectedRecords": parser.rejections}


def parse_saved_logs(paths: Iterable[Path | str]) -> dict:
    return {"schemaVersion": 1, "offlineOnly": True, "eligibleForProductAllowlist": False,
            "applicability": {"profile": None, "installedEcusVerified": False,
                              "dtcSupportVerified": False},
            "loggingCoverage": "unknown; log.tag=M can suppress source logging despite app debug=true",
            "files": [parse_bytes(Path(p).read_bytes(), i) for i, p in enumerate(paths, 1)]}


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("logs", nargs="+", type=Path)
    ap.add_argument("--output", type=Path)
    args = ap.parse_args(argv)
    if args.output and args.output.resolve() in {p.resolve() for p in args.logs}:
        ap.error("output must not overwrite an input log")
    try:
        result = parse_saved_logs(args.logs)
    except (OSError, UnicodeError):
        ap.error("cannot read a saved UTF-8 log; file contents were not emitted")
    encoded = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(encoded, encoding="utf-8")
    else:
        print(encoded, end="")


if __name__ == "__main__":
    main()
