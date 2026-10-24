#!/usr/bin/env python3
"""Post-drive review of the Yandex -> ADAS bridge from evidence pulled off the car. HOST ONLY.

    python3 tools/adas_bridge/drive_review.py <pulled-evidence-dir> --out <dir> [--tz-hours 3]

Writes <dir>/review.json (every number with the file:line or the elapsed time it came from) and
<dir>/review.md (short, in Russian, for the owner). Exit 0 whatever the drive did; exit 2 only when
the evidence directory cannot be read. Nothing is guessed: evidence that is absent or does not cover
a moment is reported as missing.

What it reads (found by file name anywhere under the directory, and inside *.tar/*.tar.gz there):
  denza-adas-live.jsonl[.N]     the live journal (DryRunRecorder rows: liveSession.*, liveEgress.*,
                                header, routeResult, variantApplied, ...), Navigator files/
  adas-journal.jsonl[.N]        the drive's change journal (adas_state rows), files/denza-drive-evidence/
  adas-packets.jsonl[.N]        passive ADAS packets and compact repeats, same directory
  progress.jsonl[.N]            1 Hz guidance samples, recorder stats, rare ADAS answers
  denza-adas-live-state.json, -live-arm.json, -live-lineage.json, -gates.json, -navi-st.json,
  -instrument-navi-status.json  Navigator files (last state, arm, lineage, side-channel ledgers)
  *.json with schema denza-adas-live-rollback-v1   the host's live_session.py ledger (restores)
  *crash*.txt, logcat*.txt      crash markers
Ring generations (.1 newest predecessor) are merged; identical lines in two copies count once.

All times are Android elapsedRealtime milliseconds (live monotonicMs, packet receivedAtElapsedMs,
guidance capturedUptimeMs, journal elapsedMs are one clock); wall time is derived from the median
capturedAtMs - elapsed of the evidence and printed in the --tz-hours zone (Moscow by default).

The answers (review.json keys):
  sessions        armed sessions: arm, variant, start/end, first cause, stock end statuses, release
  paths           per Yandex route ID its ADAS pathIds, why each new one was made, its selection ACK
  cadence         per outbound topic median/p95 interval while guided, intervals above 2.5 s
  pausesAndGaps   LIVE pauses (named prerequisite, length, FINE guidance) and position gaps > 2.5 s
  semantics       matched positions/TrafficInfo carrying the unmatched markers; link attribute mix
  adasState       DNP/ICC/ACC episodes from service-13 PilotStatus, aligned to what was being sent
  sideChannels    NaviSt (0x4CA0004B) and instrument navigation status (0x43E0003A) writes/restores
  health          processes, resumed arms, dropped callbacks, ring coverage, crash markers
A new path for a Yandex route ID is classified from the live journal rows between it and the route's
previous path: offline withdrawal, new or resumed session, DNP-switch resend (the stock's) and the
retry of a route that never reached ADAS are expected; a guidance restart, a path dropped by the
guard and anything unexplained are not.
"""
from __future__ import annotations

import argparse
import base64
import bisect
import collections
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import tarfile

if __package__ in (None, ""):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from tools.adas_bridge import feed  # noqa: E402
else:
    from . import feed

SCHEMA = "denza-adas-drive-review-v1"


def _event(service, ident, kind=4):
    return (kind << 48) | (service << 32) | (service << 16) | ident


NAVIGATION_STATUS = _event(7, 0x8001)
TRAFFIC_INFO = _event(7, 0x8003)
ROUTE = _event(43, 0x8002)
PATH_SELECTED = _event(45, 0x8001)
VEHICLE_LOCATION = _event(0x8202, 0x8008)
REGION = _event(0x8202, 0x8009)
SPEED_INTERSECTION = _event(0x8202, 0x800A)
NEXT_ACTION = _event(0x8202, 0x800B)
SELECTED = _event(44, 0x8002)
PATH_MATCH = _event(44, 0x8001)
PILOT = _event(13, 0x8002)
ALARM = _event(13, 0x8003)
MODE = _event(13, 0x8005)
PILOT_AVAILABLE, PILOT_LOST = _event(13, 1, 1), _event(13, 2, 1)
PATH_AVAILABLE, PATH_LOST = _event(44, 1, 1), _event(44, 2, 1)
PASSIVE_MESSAGES = {PILOT: "PilotStatusNotify", ALARM: "PilotAlarmAndNoticeInfoNotify", MODE: "NewBroadcastInfoNotify",
                    SELECTED: "NaviPathUserSelectStsConfirmNotify", PATH_MATCH: "NavigationPathMatchStatusNotify"}
# The stock's 1 Hz family (status loop, location bundle, NaviInfo senders, region).
ONE_HZ = (NAVIGATION_STATUS, TRAFFIC_INFO, VEHICLE_LOCATION, REGION, NEXT_ACTION, SPEED_INTERSECTION)
GAP_MS = 2500
END_STATUSES = (110, 100)
NAVI_ST_FID, INSTRUMENT_FID = 0x4CA0004B, 0x43E0003A
STREAMS = (("live", re.compile(r"^denza-adas-live\.jsonl(?:\.(\d+))?$")),
           ("dryRun", re.compile(r"^denza-adas-dry-run\.jsonl(?:\.(\d+))?$")),
           ("journal", re.compile(r"^adas-journal\.jsonl(?:\.(\d+))?$")),
           ("packets", re.compile(r"^adas-packets\.jsonl(?:\.(\d+))?$")),
           ("progress", re.compile(r"^progress\.jsonl(?:\.(\d+))?$")))
STATE_FILES = {"denza-adas-live-state.json": "liveState", "denza-adas-live-arm.json": "arm",
               "denza-adas-live-lineage.json": "lineage", "denza-adas-consumer.json": "consumer",
               "denza-adas-gates.json": "gates", "denza-adas-navi-st.json": "naviStLedger",
               "denza-adas-instrument-navi-status.json": "instrumentLedger",
               "denza-adas-stock-isolation.json": "isolation"}
# The current build's ring sizes (DriveEvidenceStreams): generations kept per stream.
GENERATIONS = {"live": 6, "packets": 7, "progress": 3, "journal": 4, "dryRun": 5}
CRASH_MARKERS = re.compile(r"FATAL EXCEPTION|Fatal signal|SIGABRT|SIGSEGV|Abort message|ANR in|am_crash|"
                           r"am_proc_died.*yandexnavi|am_kill.*yandexnavi|com\.byd\.avc.*(?:crash|died|FATAL)")
MAX_LIST = 400
PATH_EVENTS = ("liveEgress.route", "liveEgress.ack", "navStop", "liveEgress.end", "liveEgress.invalidated",
               "liveEgress.closed", "liveSession.stopped", "liveEgress.terminal")


class InputError(Exception):
    pass


# ---------------------------------------------------------------------------------------- loading

def _topic(value):
    if isinstance(value, bool):
        return None
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        text = value[2:] if value.lower().startswith("0x") else value
        try:
            return int(text, 16)
        except ValueError:
            return None
    return None


def _number(value):
    return value if isinstance(value, (int, float)) and not isinstance(value, bool) else None


class Evidence:
    """Every recognised file under the root, its rows with `_ref` (file:line) and `_t` (elapsed ms)."""

    def __init__(self, root):
        self.root = Path(root)
        self.inventory, self.parse_errors = [], []
        self.streams = {name: [] for name, _ in STREAMS}
        self.generations = {name: set() for name, _ in STREAMS}
        self.states = collections.defaultdict(list)
        self.rollback, self.logs = [], []
        self._seen_files = set()
        self._lines = {name: collections.Counter() for name, _ in STREAMS}
        if not self.root.is_dir():
            raise InputError("not a readable directory: " + str(root))
        try:
            self._walk()
        except OSError as error:
            raise InputError("cannot read " + str(root) + ": " + str(error)) from error
        for name in self.streams:
            self.streams[name].sort(key=lambda r: (r["_t"] if r["_t"] is not None else -1, r["_seq"]))

    def _walk(self):
        found = []
        for directory, dirs, files in os.walk(self.root, onerror=self._walk_error):
            dirs.sort()
            for name in sorted(files):
                path = Path(directory, name)
                if path.is_symlink() and not path.is_file():
                    continue
                found.append(path)
        tars, plain = [], []
        for path in found:
            (tars if re.search(r"\.(tar|tar\.gz|tgz)$", path.name) else plain).append(path)
        entries = []
        for path in plain:
            entries.append((str(path.relative_to(self.root)), path.name, path.read_bytes))
        for path in tars:
            label = str(path.relative_to(self.root))
            try:
                with tarfile.open(path) as archive:
                    for member in archive.getmembers():
                        if not member.isfile() or not self._interesting(Path(member.name).name):
                            continue
                        data = archive.extractfile(member).read()
                        entries.append((label + "!" + member.name, Path(member.name).name, lambda d=data: d))
            except (tarfile.TarError, OSError) as error:
                self.parse_errors.append({"file": label, "error": "unreadable archive: " + str(error)})
        # Oldest generation first so the merged order is the write order.
        def order(entry):
            for name, pattern in STREAMS:
                m = pattern.match(entry[1])
                if m:
                    return (0, name, -(int(m.group(1)) if m.group(1) else 0), entry[0])
            return (1, "", 0, entry[0])
        for label, base, read in sorted(entries, key=order):
            self._load(label, base, read)

    def _walk_error(self, error):
        raise error

    @staticmethod
    def _interesting(base):
        return (any(p.match(base) for _, p in STREAMS) or base in STATE_FILES or base.endswith(".json")
                or ("crash" in base or base.startswith("logcat")) and base.endswith((".txt", ".log")))

    def _load(self, label, base, read):
        if not self._interesting(base):
            return
        data = read()
        digest = hashlib.sha256(data).hexdigest()
        entry = {"file": label, "bytes": len(data), "sha256": digest}
        if digest in self._seen_files:
            entry["duplicateOf"] = "an identical file already read"
            self.inventory.append(entry)
            return
        self._seen_files.add(digest)
        for name, pattern in STREAMS:
            m = pattern.match(base)
            if m:
                generation = int(m.group(1)) if m.group(1) else 0
                entry.update(stream=name, generation=generation)
                entry["rows"] = self._jsonl(name, label, generation, data)
                self.generations[name].add(generation)
                self.inventory.append(entry)
                return
        if base in STATE_FILES or base.endswith(".json"):
            if len(data) > 4 << 20:
                return
            try:
                obj = json.loads(data.decode("utf-8"))
            except (UnicodeDecodeError, ValueError) as error:
                if base in STATE_FILES:
                    self.parse_errors.append({"file": label, "error": str(error)[:200]})
                return
            if base in STATE_FILES and isinstance(obj, dict):
                entry["state"] = STATE_FILES[base]
                self.states[STATE_FILES[base]].append((obj, label))
                self.inventory.append(entry)
            elif isinstance(obj, dict) and obj.get("schema") == "denza-adas-live-rollback-v1":
                entry["state"] = "rollbackLedger"
                self.rollback.append((obj, label))
                self.inventory.append(entry)
            return
        entry["log"] = True
        self.logs.append((label, data.decode("utf-8", "replace").splitlines()))
        self.inventory.append(entry)

    def _jsonl(self, stream, label, generation, data):
        rows, seen_here, counted = self.streams[stream], collections.Counter(), 0
        merged = self._lines[stream]
        last_t = None
        for number, raw in enumerate(data.decode("utf-8", "replace").splitlines(), 1):
            if not raw.strip():
                continue
            seen_here[raw] += 1
            if seen_here[raw] <= merged[raw]:
                continue  # The same line in an earlier copy of this stream.
            try:
                row = json.loads(raw)
            except ValueError:
                self.parse_errors.append({"file": label, "line": number, "error": "not JSON (a line cut by the pull?)"})
                continue
            if not isinstance(row, dict):
                continue
            row["_ref"] = label + ":" + str(number)
            row["_seq"] = len(rows)
            row["_gen"] = generation
            t = _row_time(stream, row)
            if t is None and stream == "live":
                t = last_t
            last_t = t if t is not None else last_t
            row["_t"] = t
            rows.append(row)
            counted += 1
        for raw, count in seen_here.items():
            merged[raw] = max(merged[raw], count)
        return counted

    def state(self, name, arm_id=None):
        """The newest copy of a state file (by its own elapsed time), optionally only for `arm_id`."""
        best = None
        for obj, label in self.states.get(name, []):
            if arm_id is not None and obj.get("armId") not in (None, arm_id):
                continue
            stamp = _number(obj.get("elapsedMs")) or _number(obj.get("updatedAtElapsedMs")) or 0
            if best is None or stamp >= best[2]:
                best = (obj, label, stamp)
        return (best[0], best[1]) if best else (None, None)


def _row_time(stream, row):
    for key in {"live": ("monotonicMs",), "dryRun": ("monotonicMs",),
                "journal": ("elapsedMs",),
                "packets": ("receivedAtElapsedMs", "receivedUptimeMs"),
                "progress": ("capturedUptimeMs", "receivedAtElapsedMs", "receivedUptimeMs")}[stream]:
        value = _number(row.get(key))
        if value is not None and value >= 0:
            return value
    return None


# ---------------------------------------------------------------------------------------- helpers

def quantile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def median(values):
    if not values:
        return None
    ordered = sorted(values)
    middle = len(ordered) // 2
    return ordered[middle] if len(ordered) % 2 else (ordered[middle - 1] + ordered[middle]) / 2


def capped(items, limit=MAX_LIST):
    items = list(items)
    return items if len(items) <= limit else items[:limit] + [{"truncated": len(items) - limit}]


class Clock:
    """elapsedRealtime -> wall time, from the evidence's own pairs."""

    def __init__(self, evidence, tz_hours):
        diffs = []
        for row in evidence.streams["packets"]:
            if _number(row.get("capturedAtMs")) and row["_t"] is not None and row.get("kind") == "adas_passive_packet":
                diffs.append(row["capturedAtMs"] - row["_t"])
        for row in evidence.streams["progress"]:
            if row.get("kind") == "guidance" and _number(row.get("capturedAtMs")) and row["_t"] is not None:
                diffs.append(row["capturedAtMs"] - row["_t"])
        for row in evidence.streams["journal"]:
            if _number(row.get("capturedAtMs")) and row["_t"] is not None:
                diffs.append(row["capturedAtMs"] - row["_t"])
        self.offset = int(median(diffs)) if diffs else None
        self.pairs = len(diffs)
        self.spread = (quantile(diffs, 0.99) - quantile(diffs, 0.01)) if diffs else None
        self.zone = datetime.timezone(datetime.timedelta(hours=tz_hours))
        self.tz_hours = tz_hours

    def wall(self, t):
        if t is None or self.offset is None:
            return None
        return datetime.datetime.fromtimestamp((self.offset + t) / 1000, self.zone).isoformat(timespec="milliseconds")

    def short(self, t):
        if t is None:
            return "—"
        if self.offset is None:
            return "e+%.3f с" % (t / 1000)
        return datetime.datetime.fromtimestamp((self.offset + t) / 1000, self.zone).strftime("%H:%M:%S.%f")[:-3]

    def describe(self):
        return {"wallMinusElapsedMs": self.offset, "pairs": self.pairs, "spreadMs": self.spread,
                "timezoneHours": self.tz_hours,
                "method": "median(capturedAtMs - elapsed) over packets, guidance and journal rows",
                "warning": ("several boots or a stalled recorder: wall-elapsed spread above 60 s"
                            if self.spread is not None and self.spread > 60000 else None)}


def at(t, row=None, clock=None):
    out = {"elapsedMs": t}
    if clock is not None:
        out["wall"] = clock.wall(t)
    if row is not None:
        out["ref"] = row["_ref"]
    return out


def decode_out(row):
    """A live journal packet row's payload, decoded presence-aware (absent proto3 fields stay absent)."""
    if "_d" in row:
        return row["_d"]
    topic, payload = _topic(row.get("topic")), row.get("payloadHex")
    decoded = None
    if topic in feed.TOPICS and isinstance(payload, str):
        try:
            decoded = feed.flatten(feed.TOPICS[topic][1], bytes.fromhex(payload))
        except (ValueError, TypeError, UnicodeDecodeError):
            decoded = None
    row["_d"] = decoded
    return decoded


def decode_passive(topic, payload):
    message = PASSIVE_MESSAGES.get(topic)
    if message is None or payload is None:
        return None
    try:
        return feed.flatten(message, payload)
    except (ValueError, TypeError, UnicodeDecodeError, IndexError):
        return None


def topic_name(topic):
    if topic in feed.TOPICS:
        return feed.TOPICS[topic][0]
    return {PILOT: "PilotStatus", ALARM: "Alarm", MODE: "NoaMode"}.get(topic, hex(topic) if topic is not None else None)


def _json_detail(text):
    if not isinstance(text, str):
        return None
    try:
        value = json.loads(text)
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


# ---------------------------------------------------------------------------------------- the drive model

class Drive:
    """The parsed evidence: sends, routes, answers, pilot observations, windows."""

    def __init__(self, evidence, clock):
        self.e, self.clock = evidence, clock
        self.live = evidence.streams["live"]
        self.journal = evidence.streams["journal"]
        self.packets = evidence.streams["packets"]
        self.progress = evidence.streams["progress"]
        self.missing = []
        self.sends = [r for r in self.live if r.get("input") == "liveEgress.packet" and r.get("result") == 0]
        for r in self.sends:
            r["_topic"] = _topic(r.get("topic"))
        self.guidance = [r for r in self.progress if r.get("kind") == "guidance" and r["_t"] is not None]
        self._routes()
        self._answers()
        self._pilot()
        self.windows, self.windows_inferred = self._guided_windows()
        self.live_times = [r["_t"] if r["_t"] is not None else -1 for r in self.live]
        self.send_times = [r["_t"] for r in self.sends]
        self.by_topic = collections.defaultdict(lambda: ([], []))
        for r in self.sends:
            times, rows = self.by_topic[r["_topic"]]
            times.append(r["_t"])
            rows.append(r)
        self.path_events = [r for r in self.live if r.get("input") in PATH_EVENTS and r["_t"] is not None]

    def last_send(self, t, topic=None):
        times, rows = (self.send_times, self.sends) if topic is None else self.by_topic.get(topic, ([], []))
        i = bisect.bisect_right(times, t)
        return rows[i - 1] if i else None

    def live_between(self, lo, hi):
        return self.live[bisect.bisect_right(self.live_times, lo):bisect.bisect_left(self.live_times, hi)]

    # Routes as published: the reassembled route JSON per pathId.
    def _routes(self):
        self.route_json, assembler = {}, feed.RouteAssembler()
        for r in self.sends:
            if r["_topic"] != ROUTE:
                continue
            fields = decode_out(r)
            if not fields:
                continue
            try:
                payload, _ = assembler.add(dict(fields))
            except (ValueError, TypeError, KeyError):
                continue
            if isinstance(payload, dict) and isinstance(payload.get("data"), list) and payload["data"]:
                route = payload["data"][0]
                if isinstance(route, dict) and _number(route.get("pathID")) is not None:
                    self.route_json[int(route["pathID"])] = {"route": route, "t": r["_t"], "ref": r["_ref"]}

    # Every service-44 answer, from every recorder, one entry per receipt.
    def _answers(self):
        seen, answers = set(), []

        def add(kind, t, path_ids, status, receiver, row, extra=None):
            key = (kind, t, receiver, tuple(path_ids), status)
            if key in seen:
                return
            seen.add(key)
            entry = {"kind": kind, "t": t, "pathIds": path_ids, "status": status, "receiver": receiver, "ref": row["_ref"]}
            if extra:
                entry.update(extra)
            answers.append(entry)

        for stream in (self.packets, self.progress):
            for row in stream:
                topic = _topic(row.get("topic"))
                if row.get("kind") != "adas_passive_packet" or topic not in (SELECTED, PATH_MATCH):
                    continue
                payload = _b64(row.get("base64"))
                fields = decode_passive(topic, payload) or {}
                t = row["_t"]
                if topic == SELECTED:
                    add("selected", t, [fields["pathIDResp"]] if "pathIDResp" in fields else [], fields.get("statusCode"),
                        row.get("receiver"), row)
                else:
                    infos = fields.get("routeInfoArray") or []
                    add("pathMatch", t, [i.get("pathID") for i in infos if isinstance(i, dict) and "pathID" in i],
                        fields.get("code"), row.get("receiver"), row, {"routeInfo": infos})
        for row in self.journal:
            if row.get("kind") != "adas_state":
                continue
            if row.get("what") == "selected":
                add("selected", row["_t"], [row["pathId"]] if row.get("pathId") is not None else [], row.get("statusCode"),
                    row.get("receiver"), row)
            elif row.get("what") == "pathMatch":
                ids = []
                for item in str(row.get("routes") or "").split(";"):
                    head = item.split(":", 1)[0]
                    if head.lstrip("-").isdigit():
                        ids.append(int(head))
                add("pathMatch", row["_t"], ids, row.get("code"), row.get("receiver"), row, {"routes": row.get("routes")})
        for row in self.live:
            if row.get("input") != "liveFeedback":
                continue
            t = _number(row.get("receivedAtElapsedMs")) or row["_t"]
            payload = row.get("payloadHex")
            if isinstance(payload, str):
                fields = decode_passive(SELECTED, bytes.fromhex(payload)) or {}
                add("selected", t, [fields["pathIDResp"]] if "pathIDResp" in fields else [], fields.get("statusCode"), "live", row)
            else:
                add("selected", t, [], None, "live", row, {"payloadNotJournalled": True})
        answers.sort(key=lambda a: (a["t"] if a["t"] is not None else -1))
        self.answers = answers

    # Pilot, mode observations: packets (presence-aware) and the change journal (absent read as 0).
    def _pilot(self):
        last_full, by_receiver, modes, alarms = {}, collections.defaultdict(list), [], []
        self.orphan_repeats = 0
        for row in self.packets:
            topic = _topic(row.get("topic"))
            if topic not in (PILOT, MODE, ALARM):
                continue
            receiver = row.get("receiver")
            if row.get("kind") == "adas_passive_packet":
                payload = _b64(row.get("base64"))
                last_full[(receiver, topic)] = payload
            elif row.get("kind") == "adas_passive_repeat":
                payload = last_full.get((receiver, topic))
                if payload is None:
                    self.orphan_repeats += 1
                    continue
            else:
                continue
            fields = decode_passive(topic, payload)
            if fields is None:
                continue
            obs = {"t": row["_t"], "ref": row["_ref"], "source": "packets:" + str(receiver)}
            if topic == PILOT:
                obs.update(dnp=fields.get("dNPStatus"), icc=fields.get("iCCStatus"), acc=fields.get("aCCStatus"),
                           takeover=fields.get("takeoverStatus"))
                by_receiver[receiver].append(obs)
            elif topic == MODE:
                obs["noaMode"] = fields.get("noaMode")
                modes.append(obs)
        receiver = "overlay" if by_receiver.get("overlay") else (sorted(by_receiver)[0] if by_receiver else None)
        pilots = list(by_receiver.get(receiver, [])) if receiver else []
        journal_pilots = 0
        for row in self.journal:
            if row.get("kind") != "adas_state":
                continue
            if row.get("what") == "pilot":
                journal_pilots += 1
                pilots.append({"t": row["_t"], "ref": row["_ref"], "source": "journal",
                               "dnp": row.get("dnp"), "icc": row.get("icc"), "acc": row.get("acc"), "takeover": row.get("takeover")})
            elif row.get("what") == "noaMode":
                modes.append({"t": row["_t"], "ref": row["_ref"], "source": "journal", "noaMode": row.get("noaMode")})
        pilots = [p for p in pilots if p["t"] is not None]
        pilots.sort(key=lambda p: p["t"])
        modes = [m for m in modes if m["t"] is not None]
        modes.sort(key=lambda m: m["t"])
        self.pilots, self.modes = pilots, modes
        self.pilot_sources = {"packetsReceiver": receiver, "packetObservations": len(by_receiver.get(receiver, [])) if receiver else 0,
                              "journalTransitions": journal_pilots,
                              "receiversSeen": sorted(str(k) for k in by_receiver)}

    def _guided_windows(self):
        """Navigator guidance as the feed models it: navStart .. navStop (or the session's end)."""
        windows, open_at, state, inferred = [], None, None, False
        first_t = next((r["_t"] for r in self.live if r["_t"] is not None), None)
        for r in self.live:
            t, inp = r["_t"], r.get("input")
            if t is None:
                continue
            if r.get("kind") == "header" or inp in ("liveSession.stopped", "liveEgress.closed", "liveSession.armed"):
                if state:
                    windows.append((open_at, t))
                state = False if state is not None or r.get("kind") == "header" else state
                continue
            if inp == "navStart" and not state:
                open_at, state = t, True
            elif inp == "navStop":
                if state:
                    windows.append((open_at, t))
                elif state is None and first_t is not None:
                    windows.append((first_t, t))
                    inferred = True
                state = False
        if state:
            windows.append((open_at, self.live[-1]["_t"]))
        if not windows and state is None:
            positions = [r["_t"] for r in self.sends if r["_topic"] == VEHICLE_LOCATION]
            if positions:
                windows, inferred = [(positions[0], positions[-1])], True
        return windows, inferred

    def guided(self, t):
        return any(a <= t <= b for a, b in self.windows)

    def window_of(self, t):
        for i, (a, b) in enumerate(self.windows):
            if a <= t <= b:
                return i
        return None

    def live_covers(self, t):
        return bool(self.live) and self.live[0]["_t"] is not None and self.live[0]["_t"] <= t


def _b64(text):
    if not isinstance(text, str):
        return None
    try:
        return base64.b64decode(text)
    except ValueError:
        return None


# ---------------------------------------------------------------------------------------- 1 sessions

def _end_sequences(drive):
    sequences, current = [], None
    for r in drive.live:
        inp = r.get("input")
        if inp == "liveEgress.end":
            reason = str(r.get("reason") or "")
            m = re.search(r"\[([\d,\s]+)\]", reason)
            expected = [int(x) for x in m.group(1).split(",")] if m else None
            current = {"start": r["_t"], "ref": r["_ref"], "reason": reason, "expected": expected, "sent": [], "outcome": None}
            sequences.append(current)
        elif current is None or current["outcome"] is not None:
            continue
        elif inp == "liveEgress.packet" and r.get("result") == 0 and _topic(r.get("topic")) == NAVIGATION_STATUS:
            status = (decode_out(r) or {}).get("navigationStatus")
            if status in END_STATUSES:
                current["sent"].append({"status": status, "elapsedMs": r["_t"], "ref": r["_ref"]})
        elif inp == "liveEgress.idle" and "end sequence sent" in str(r.get("reason")):
            current.update(outcome="complete", end=r["_t"], endRef=r["_ref"])
        elif inp in ("liveEgress.endCancelled", "liveEgress.endTimeout"):
            current.update(outcome="cancelled" if inp.endswith("Cancelled") else "timeout", end=r["_t"], endRef=r["_ref"],
                           why=r.get("reason"))
        elif inp == "liveEgress.closed":
            current.update(outcome="closedBeforeComplete", end=r["_t"], endRef=r["_ref"])
    for s in sequences:
        values = [x["status"] for x in s["sent"]]
        s["sentValues"] = values
        s["stockShape"] = values in ([110, 110, 110, 100, 100, 100], [100, 100, 100]) or (
            len(values) >= 3 and values[-3:] == [100, 100, 100] and all(v in END_STATUSES for v in values))
    return sequences


def sessions(drive, clock):
    e = drive.e
    by_arm = collections.OrderedDict()
    for r in drive.live:
        inp = str(r.get("input") or "")
        if not inp.startswith("liveSession.") or not r.get("armId"):
            continue
        by_arm.setdefault(r["armId"], []).append(r)
    arm_file, arm_ref = e.state("arm")
    if not by_arm and arm_file and arm_file.get("armId"):
        by_arm[arm_file["armId"]] = []
    sequences = _end_sequences(drive)
    first_live = drive.live[0]["_t"] if drive.live else None
    out, ordered = [], list(by_arm.items())
    for index, (arm_id, rows) in enumerate(ordered):
        s = {"armId": arm_id}
        armed = [r for r in rows if r.get("input") == "liveSession.armed"]
        stopping = [r for r in rows if r.get("input") == "liveSession.stopping"]
        stopped = [r for r in rows if r.get("input") == "liveSession.stopped"]
        arm_obj, arm_obj_ref = e.state("arm", arm_id)
        if armed:
            start = armed[0]["_t"]
            s["start"] = dict(at(start, armed[0], clock), how="liveSession.armed")
        elif rows and rows[0]["_t"] == min((r["_t"] for rs in by_arm.values() for r in rs if r["_t"] is not None), default=None):
            start = first_live
            s["start"] = dict(at(start, drive.live[0], clock), how="the live journal's first retained row (armed rotated out)")
        else:
            start = rows[0]["_t"] if rows else None
            s["start"] = dict(at(start, rows[0], clock), how="first retained liveSession row") if rows else None
        if arm_obj and arm_obj.get("armId") == arm_id and _number(arm_obj.get("issuedAtElapsedMs")) is not None:
            s["armIssued"] = {"elapsedMs": arm_obj["issuedAtElapsedMs"], "wall": clock.wall(arm_obj["issuedAtElapsedMs"]),
                              "ref": arm_obj_ref, "expiresAtElapsedMs": arm_obj.get("expiresAtElapsedMs")}
        end = stopped[-1]["_t"] if stopped else None
        s["end"] = dict(at(end, stopped[-1], clock), detail=stopped[-1].get("detail")) if stopped else None
        if not stopped:
            drive.missing.append("сеанс " + arm_id[:8] + ": нет строки liveSession.stopped (шёл во время снятия или потеряна)")
        if stopping:
            first = stopping[0]
            s["firstCause"] = dict(at(first["_t"], first, clock), detail=first.get("detail"))
        elif stopped:
            s["firstCause"] = dict(at(stopped[0]["_t"], stopped[0], clock), detail=stopped[0].get("detail"),
                                   note="no liveSession.stopping row (an older build): the stopped row's reason")
        else:
            s["firstCause"] = None
        state, state_ref = e.state("liveState", arm_id)
        if state and state.get("armId") == arm_id:
            s["liveState"] = {"ref": state_ref, "state": state.get("state"), "stopReason": state.get("stopReason"),
                              "stopDetail": state.get("stopDetail"), "firstFailure": state.get("firstFailure"),
                              "heldPathId": state.get("heldPathId"), "armProcesses": state.get("armProcesses")}
        s["variant"] = _session_variant(drive, arm_id, rows, start, end)
        processes = []
        for r in rows:
            p = r.get("processSession")
            if p and p not in processes:
                processes.append(p)
        s["processes"] = processes
        s["resumedArms"] = [dict(at(r["_t"], r, clock), detail=r.get("detail")) for r in armed if "resumed" in str(r.get("detail"))]
        lo = start if start is not None else -math.inf
        hi = end if end is not None else math.inf
        s["_window"] = (lo, hi)
        live_headers = [r for r in drive.live if r.get("kind") == "header" and r.get("publisher") == "LIVE" and lo <= (r["_t"] or -1) <= hi]
        s["feedStarted"] = at(live_headers[0]["_t"], live_headers[0], clock) if live_headers else None
        # End statuses: the session's last end sequence that begins at its first stop (or the arm's expiry lead).
        stop_t = stopping[0]["_t"] if stopping else end
        final = [q for q in sequences if stop_t is not None and stop_t - 1 <= q["start"] <= (end if end is not None else math.inf) + 1]
        within = [q for q in sequences if lo <= q["start"] <= hi]
        s["endSequences"] = [_sequence_view(q, clock) for q in within]
        acked = _session_acked_paths(drive, lo, hi)
        s["pathsConfirmed"] = acked
        if final:
            q = final[-1]
            s["endStatuses"] = {"sent": q["outcome"] == "complete", "values": q["sentValues"], "expected": q["expected"],
                                "stockShape": q["stockShape"], "outcome": q["outcome"], "ref": q["ref"]}
        elif stop_t is None:
            s["endStatuses"] = {"sent": None, "why": "the session's end is not retained"}
        else:
            after = [r for r in drive.sends if r["_topic"] == NAVIGATION_STATUS and stop_t <= r["_t"] <= (end if end is not None else math.inf)
                     and (decode_out(r) or {}).get("navigationStatus") in END_STATUSES]
            s["endStatuses"] = {"sent": False, "values": [(decode_out(r) or {}).get("navigationStatus") for r in after],
                                "why": "no stock end sequence (liveEgress.end) at the session's end"
                                       + ("" if acked else "; no path of this session was confirmed")}
        s["release"] = _release(drive, clock, acked, sequences, stop_t, end, s["endStatuses"])
        out.append(s)
    if not out:
        drive.missing.append("сеансов передачи нет (ни строк liveSession, ни файла разрешения)")
    return out, sequences


def _sequence_view(q, clock):
    return {"start": at(q["start"], None, clock) | {"ref": q["ref"]}, "reason": q["reason"], "expected": q["expected"],
            "sent": q["sentValues"], "outcome": q["outcome"], "stockShape": q["stockShape"],
            "end": at(q.get("end"), None, clock) | {"ref": q.get("endRef")} if q.get("end") is not None else None}


def _session_acked_paths(drive, lo, hi):
    paths = []
    for r in drive.live:
        if r.get("input") == "liveEgress.ack" and r.get("reason") == "ACCEPTED" and lo <= (r["_t"] or -1) <= hi:
            pid = r.get("pathId")
            if isinstance(pid, int) and pid > 0 and pid not in paths:
                paths.append(pid)
    return paths


def _release(drive, clock, paths, sequences, stop_t, end, end_statuses):
    if not paths:
        return {"released": None, "why": "no confirmed path of ours in this session"}
    held = paths[-1]
    finish = None
    if end_statuses.get("sent"):
        finish = max(q.get("end") or q["start"] for q in sequences if stop_t is not None and q["start"] >= stop_t - 1)
    if finish is None:
        finish = end if end is not None else stop_t
    if finish is None:
        return {"released": None, "heldPathId": held, "why": "the session's end is not retained"}
    later = [a for a in drive.answers if a["t"] is not None and a["t"] > finish and set(a["pathIds"]) & set(paths)]
    covering = [r["_t"] for s in (drive.packets, drive.journal, drive.progress) for r in s
                if r["_t"] is not None and r["_t"] > finish and r.get("kind") in ("adas_passive_packet", "adas_passive_repeat", "adas_state")]
    result = {"heldPathId": held, "after": at(finish, None, clock), "laterAnswersOnOurPaths": len(later),
              "answers": capped([{"kind": a["kind"], "pathIds": a["pathIds"], "status": a["status"], "elapsedMs": a["t"],
                                  "wall": clock.wall(a["t"]), "ref": a["ref"]} for a in later], 50),
              "passiveCoverageUntil": at(max(covering), None, clock) if covering else None}
    if later:
        result["released"] = False
    elif covering:
        result["released"] = True
    else:
        result["released"] = None
        result["why"] = "no passive ADAS record after the end: release cannot be judged"
    return result


def _session_variant(drive, arm_id, rows, start, end):
    for r in rows:
        if r.get("input") == "liveSession.variant":
            value = _json_detail(r.get("detail"))
            if value:
                return _variant_view(value, r["_ref"])
    lo = start if start is not None else -math.inf
    hi = end if end is not None else math.inf
    for r in drive.live:
        if r.get("kind") == "header" and isinstance(r.get("variant"), dict) and lo <= (r["_t"] or -1) <= hi:
            return _variant_view(r["variant"], r["_ref"])
    for r in drive.journal:
        if r.get("what") == "variant" and r.get("armId") == arm_id and isinstance(r.get("variant"), dict):
            return _variant_view(r["variant"], r["_ref"])
    state, ref = drive.e.state("liveState", arm_id)
    if state and state.get("armId") == arm_id and isinstance(state.get("variant"), dict):
        return _variant_view(state["variant"], ref)
    arm, ref = drive.e.state("arm", arm_id)
    if arm and arm.get("armId") == arm_id and isinstance(arm.get("variant"), dict):
        return _variant_view(arm["variant"], ref, effective=False)
    for ledger, ref in drive.e.rollback:
        if ledger.get("armId") == arm_id and isinstance(ledger.get("variantEffective"), dict):
            return _variant_view(ledger["variantEffective"], ref)
    drive.missing.append("сеанс " + arm_id[:8] + ": вариант не записан (сборка без вариантов или строки потеряны)")
    return None


def _variant_view(value, ref, effective=True):
    overrides = value.get("overrides")
    if overrides is None and not effective:
        overrides = sorted(k for k in value if k != "id")
    return {"id": value.get("id"), "overrides": overrides, "effective": value if effective else None,
            "asArmed": None if effective else value, "ref": ref}


# ---------------------------------------------------------------------------------------- 2 paths

ALLOWED_NEW_PATH = {"offline_withdrawal", "new_session", "dnp_switch_resend", "retry_unsent"}


def paths(drive, clock):
    results = [(i, r) for i, r in enumerate(drive.live) if r.get("input") == "routeResult"]
    if not results:
        results = [(i, dict(r, pathId=r.get("pathId"), routeId=None)) for i, r in enumerate(drive.live)
                   if r.get("input") == "liveEgress.route"]
        if results:
            drive.missing.append("нет строк routeResult: пути только из liveEgress.route, без ID маршрутов Яндекса")
    if not results:
        drive.missing.append("в журнале передачи нет публикаций маршрута")
        return {"routes": [], "publications": 0}
    selections = {}
    for r in drive.sends:
        if r["_topic"] == PATH_SELECTED:
            pid = (decode_out(r) or {}).get("pathIDReq")
            if pid is not None:
                selections.setdefault(int(pid), r)
    accepted = {}
    for r in drive.live:
        if r.get("input") == "liveEgress.ack" and r.get("reason") == "ACCEPTED" and isinstance(r.get("pathId"), int):
            accepted.setdefault(r["pathId"], r)
    routes = collections.OrderedDict()
    previous_by_route, previous_any = {}, None
    variant_notes = _variant_notes(drive)
    for index, row in results:
        route_id, pid = row.get("routeId"), row.get("pathId")
        entry = {"pathId": pid, "routeId": route_id, "published": at(row["_t"], row, clock),
                 "amapRouteType": row.get("amapRouteType"), "links": row.get("links"), "segments": row.get("segments")}
        prior = previous_by_route.get(route_id) if route_id is not None else None
        if prior is None:
            between = drive.live[(previous_any + 1 if previous_any is not None else 0):index]
            kinds = {r.get("input") for r in between}
            entry["reason"] = "first_for_route"
            entry["routeKind"] = {1: "new guidance", 2: "yaw reroute", 5: "route replacement (traffic)"}.get(row.get("amapRouteType"), "type " + str(row.get("amapRouteType")))
            if previous_any is not None:
                entry["afterYandexReroute"] = "reroute" in kinds or row.get("amapRouteType") == 2
            entry["expected"] = True
        else:
            entry["reason"], entry["evidence"] = _new_path_reason(drive.live[prior + 1:index])
            entry["expected"] = entry["reason"] in ALLOWED_NEW_PATH
            entry["sincePreviousPathMs"] = row["_t"] - drive.live[prior]["_t"] if row["_t"] is not None else None
        sel = selections.get(pid)
        entry["selectionSent"] = at(sel["_t"], sel, clock) if sel else None
        entry["routeSent"] = pid in drive.route_json
        ack = next((a for a in drive.answers if a["kind"] == "selected" and pid in a["pathIds"] and a["status"] == 200
                    and (sel is None or a["t"] >= sel["_t"] - 5)), None)
        if ack:
            entry["ack200"] = {"elapsedMs": ack["t"], "wall": clock.wall(ack["t"]), "ref": ack["ref"], "receiver": ack["receiver"],
                               "afterSelectionMs": ack["t"] - sel["_t"] if sel else None}
        elif pid in accepted:
            a = accepted[pid]
            entry["ack200"] = {"elapsedMs": a["_t"], "wall": clock.wall(a["_t"]), "ref": a["_ref"], "receiver": "live",
                               "afterSelectionMs": a["_t"] - sel["_t"] if sel else None,
                               "note": "guard ACCEPTED (RealPathAck: same path, status 200); the payload is not journalled"}
        else:
            entry["ack200"] = None
        others = [a for a in drive.answers if a["kind"] == "selected" and pid in a["pathIds"] and a["status"] != 200]
        if others:
            entry["otherSelectionAnswers"] = [{"status": a["status"], "elapsedMs": a["t"], "ref": a["ref"]} for a in others[:10]]
        matches = [a for a in drive.answers if a["kind"] == "pathMatch" and pid in a["pathIds"]]
        entry["pathMatches"] = len(matches)
        if matches:
            entry["firstPathMatch"] = {"elapsedMs": matches[0]["t"], "ref": matches[0]["ref"]}
        if pid in variant_notes:
            entry["variantApplied"] = variant_notes[pid]
        routes.setdefault(route_id, []).append(entry)
        previous_by_route[route_id] = index
        previous_any = index
    out = []
    for route_id, items in routes.items():
        span = (items[-1]["published"]["elapsedMs"] - items[0]["published"]["elapsedMs"]) if len(items) > 1 else 0
        out.append({"routeId": route_id, "paths": len(items), "spanMs": span,
                    "unexpectedNewPaths": sum(1 for p in items if not p["expected"]),
                    "reasons": dict(collections.Counter(p["reason"] for p in items)),
                    "withAck200": sum(1 for p in items if p["ack200"]), "items": items})
    return {"publications": len(results), "routes": out}


def _new_path_reason(between):
    kinds = [r.get("input") for r in between]
    rows = {k: r for r, k in zip(between, kinds)}
    for kind, reason in (("liveSession.offlineWithdrawal", "offline_withdrawal"), ("liveSession.armed", "new_session"),
                         ("routeResend", "dnp_switch_resend"), ("routeRetry", "retry_unsent")):
        if kind in rows:
            return reason, {"row": kind, "ref": rows[kind]["_ref"], "detail": rows[kind].get("detail") or rows[kind].get("reason")}
    if "navStop" in rows and "navStart" in rows:
        return "guidance_restart", {"row": "navStop/navStart", "ref": rows["navStart"]["_ref"]}
    for kind in ("liveEgress.invalidated", "liveEgress.terminal"):
        if kind in rows:
            return "after_invalidation", {"row": kind, "ref": rows[kind]["_ref"], "detail": rows[kind].get("reason")}
    return "unexplained", None


def _variant_notes_cached(drive):
    if not hasattr(drive, "_variant_notes"):
        drive._variant_notes = _variant_notes(drive)
    return drive._variant_notes


def _variant_notes(drive):
    notes = collections.defaultdict(list)
    for r in drive.live:
        if r.get("input") != "variantApplied":
            continue
        pid = r.get("pathId")
        item = {k: r.get(k) for k in ("field", "value", "where", "historySpeed", "routeOrdinal", "routeId", "variantId", "links") if k in r}
        item["elapsedMs"], item["ref"] = r["_t"], r["_ref"]
        notes[pid].append(item)
    return notes


# ---------------------------------------------------------------------------------------- 3 cadence

def cadence(drive, clock):
    if not drive.sends:
        drive.missing.append("в журнале передачи нет ни одной отправки (liveEgress.packet result 0)")
        return {"guidedWindows": [], "topics": []}
    by_topic = collections.defaultdict(list)
    for r in drive.sends:
        by_topic[r["_topic"]].append(r)
    topics = []
    for topic, rows in sorted(by_topic.items(), key=lambda kv: -len(kv[1])):
        intervals = []
        for a, b in zip(rows, rows[1:]):
            wa, wb = drive.window_of(a["_t"]), drive.window_of(b["_t"])
            if wa is not None and wa == wb:
                intervals.append((b["_t"] - a["_t"], a, b))
        values = [i[0] for i in intervals]
        over = [{"ms": d, "from": at(a["_t"], a, clock), "to": at(b["_t"], b, clock)} for d, a, b in intervals if d > GAP_MS]
        longest = max(intervals, key=lambda i: i[0]) if intervals else None
        med = median(values)
        topics.append({"topic": topic, "name": topic_name(topic), "sentTotal": len(rows), "intervalsWhileGuided": len(values),
                       "medianMs": med, "p95Ms": quantile(values, 0.95),
                       "maxMs": longest[0] if longest else None,
                       "max": {"from": longest[1]["_ref"], "to": longest[2]["_ref"]} if longest else None,
                       "expectedAbout1s": topic in ONE_HZ,
                       "cadenceOk": (None if med is None or topic not in ONE_HZ else 800 <= med <= 1200),
                       "over2500ms": capped(over, 100),
                       "files": sorted({r["_ref"].rsplit(":", 1)[0] for r in rows})})
    return {"guidedWindows": [{"from": at(a, None, clock), "to": at(b, None, clock), "ms": b - a} for a, b in drive.windows],
            "guidedWindowsInferred": drive.windows_inferred,
            "guidedDefinition": "navStart .. navStop of the feed (or the session's end); a window opened before the "
                                "retained journal is inferred from its first navStop",
            "topics": topics}


# ---------------------------------------------------------------------------------------- 4 pauses and gaps

def _guidance_during(drive, lo, hi):
    rows = [g for g in drive.guidance if lo <= g["_t"] <= hi]
    if not rows:
        covered = bool(drive.guidance) and drive.guidance[0]["_t"] <= lo and drive.guidance[-1]["_t"] >= hi
        return {"samples": 0, "covered": covered}
    fine = sum(1 for g in rows if g.get("locationClass") == "FINE")
    fresh = sum(1 for g in rows if g.get("freshLocation") is True)
    return {"samples": len(rows), "fine": fine, "fresh": fresh, "allFine": fine == len(rows),
            "modes": dict(collections.Counter(str(g.get("mode")) for g in rows)),
            "routeIds": dict(collections.Counter(str(g.get("routeId")) for g in rows)),
            "first": rows[0]["_ref"], "last": rows[-1]["_ref"]}


def pauses_and_gaps(drive, clock, cadence_out):
    pauses, current = [], None
    for r in drive.live:
        inp = r.get("input")
        if inp == "liveEgress.suspended":
            if current is None:
                current = {"start": r["_t"], "startRef": r["_ref"], "prerequisite": r.get("reason"), "reasons": [r.get("reason")],
                           "pathId": r.get("pathId")}
                pauses.append(current)
            elif r.get("reason") not in current["reasons"]:
                current["reasons"].append(r.get("reason"))
        elif current is not None and inp in ("liveEgress.resumed", "liveEgress.closed", "liveEgress.terminal", "liveSession.stopped"):
            current.update(end=r["_t"], endRef=r["_ref"], endedBy=inp)
            current = None
    last_t = drive.live[-1]["_t"] if drive.live else None
    out_pauses = []
    for p in pauses:
        end = p.get("end", last_t)
        view = {"prerequisite": p["prerequisite"], "otherReasons": p["reasons"][1:], "pathId": p["pathId"],
                "start": at(p["start"], None, clock) | {"ref": p["startRef"]},
                "lengthMs": (end - p["start"]) if end is not None else None,
                "endedBy": p.get("endedBy", "still paused at the end of the journal"), "endRef": p.get("endRef"),
                "guidance": _guidance_during(drive, p["start"], end if end is not None else p["start"])}
        out_pauses.append(view)
    prerequisites = [dict(at(r["_t"], r, clock), detail=r.get("detail")) for r in drive.live if r.get("input") == "liveSession.prerequisite"]
    drops = [dict(at(r["_t"], r, clock), reason=r.get("reason"), pathId=r.get("pathId"))
             for r in drive.live if r.get("input") in ("liveEgress.invalidated", "liveEgress.terminal")]
    gaps = []
    positions = [r for r in drive.sends if r["_topic"] == VEHICLE_LOCATION]
    for a, b in zip(positions, positions[1:]):
        wa = drive.window_of(a["_t"])
        if wa is None or wa != drive.window_of(b["_t"]) or b["_t"] - a["_t"] <= GAP_MS:
            continue
        inside = drive.live_between(a["_t"], b["_t"])
        rejections = collections.Counter(str(r.get("reason")) for r in inside
                                         if r.get("input") == "liveEgress.rejected" and _topic(r.get("topic")) == VEHICLE_LOCATION)
        gaps.append({"ms": b["_t"] - a["_t"], "from": at(a["_t"], a, clock), "to": at(b["_t"], b, clock),
                     "pathBefore": (decode_out(a) or {}).get("curSDRouteID"), "pathAfter": (decode_out(b) or {}).get("curSDRouteID"),
                     "pausesInside": [p["prerequisite"] for p in pauses if p["start"] < b["_t"] and p.get("end", math.inf) > a["_t"]],
                     "pathDropsInside": [dict(reason=r.get("reason"), ref=r["_ref"]) for r in inside
                                         if r.get("input") in ("liveEgress.invalidated", "liveEgress.terminal")][:10],
                     "routePublicationsInside": [dict(pathId=r.get("pathId"), ref=r["_ref"]) for r in inside if r.get("input") == "routeResult"],
                     "positionRejections": dict(rejections.most_common(8)),
                     "guidance": _guidance_during(drive, a["_t"], b["_t"])})
    if not drive.guidance:
        drive.missing.append("нет образцов ведения (progress.jsonl): FINE во время пауз и разрывов не оценить")
    return {"pauses": out_pauses, "prerequisiteChanges": capped(prerequisites, 200), "pathDrops": capped(drops, 100),
            "positionGapsOver2500ms": gaps}


# ---------------------------------------------------------------------------------------- 5 semantics

def semantics(drive, clock):
    notes = _variant_notes(drive)
    filled = collections.defaultdict(dict)
    for pid, items in notes.items():
        for item in items:
            if item.get("field") in ("position.ownership", "traffic.roadClass"):
                filled[pid].setdefault(item["field"], (item.get("value"), item["elapsedMs"], item["ref"]))
    positions = {"sent": 0, "matched": 0, "ownershipMinus1": 0, "ownershipVariantFilled": 0, "ownershipFromYandex": 0,
                 "ownershipZeroOnWire": 0, "firstMinus1": None}
    traffic = {"sent": 0, "matched": 0, "roadClassMinus1": 0, "roadClassVariantFilled": 0, "roadClassFromYandex": 0,
               "roadClassZeroOnWire": 0, "unattributed": 0, "firstMinus1": None}
    last_position = None
    for r in drive.sends:
        topic = r["_topic"]
        if topic == VEHICLE_LOCATION:
            fields = decode_out(r) or {}
            positions["sent"] += 1
            path = fields.get("curSDRouteID")
            last_position = (r["_t"], path)
            if not isinstance(path, (int, float)) or path <= 0:
                continue
            positions["matched"] += 1
            pid = int(path)
            value = fields.get("currentRoadOwnership")
            if value is None:  # proto3 leaves a 0 off the wire
                positions["ownershipZeroOnWire"] += 1
                value = 0
            fill = filled.get(pid, {}).get("position.ownership")
            if value == -1:
                positions["ownershipMinus1"] += 1
                positions["firstMinus1"] = positions["firstMinus1"] or r["_ref"]
            elif fill and fill[1] <= r["_t"] and str(fill[0]) == str(value):
                positions["ownershipVariantFilled"] += 1
            else:
                positions["ownershipFromYandex"] += 1
        elif topic == TRAFFIC_INFO:
            fields = decode_out(r) or {}
            traffic["sent"] += 1
            if last_position is None or r["_t"] - last_position[0] > 1500:
                traffic["unattributed"] += 1
                continue
            path = last_position[1]
            if not isinstance(path, (int, float)) or path <= 0:
                continue
            traffic["matched"] += 1
            value = fields.get("roadClass")
            if value is None:
                traffic["roadClassZeroOnWire"] += 1
                value = 0
            fill = filled.get(int(path), {}).get("traffic.roadClass")
            if value == -1:
                traffic["roadClassMinus1"] += 1
                traffic["firstMinus1"] = traffic["firstMinus1"] or r["_ref"]
            elif fill and fill[1] <= r["_t"] and str(fill[0]).replace("link:", "") == str(value):
                traffic["roadClassVariantFilled"] += 1
            else:
                traffic["roadClassFromYandex"] += 1
    positions["definition"] = ("matched: curSDRouteID > 0; -1 = the unmatched marker Yandex left (no variant filled it); "
                               "variantFilled = equals the variant's variantApplied value for that path (sent after its note); "
                               "zeroOnWire = the field is absent, which proto3 means 0")
    traffic["definition"] = ("attributed to the path of the position sent within 1.5 s before it; "
                             "unattributed = no such position")
    links = []
    for pid, item in sorted(drive.route_json.items(), key=lambda kv: kv[1]["t"]):
        route_links = item["route"].get("links") or []
        dist = {}
        for key in ("roadclass", "formway", "direction", "ownership", "laneNum"):
            counter = collections.Counter(link.get(key) for link in route_links if isinstance(link, dict))
            dist[key] = {"counts": {str(k): v for k, v in sorted(counter.items(), key=lambda kv: str(kv[0]))},
                         "minus1": counter.get(-1, 0), "zero": counter.get(0, 0)}
        links.append({"pathId": pid, "links": len(route_links), "ref": item["ref"], "fields": dist})
    if not drive.route_json:
        drive.missing.append("не разобран ни один отправленный маршрут: атрибуты звеньев неизвестны")
    variants = {str(pid): items for pid, items in notes.items()}
    return {"positions": positions, "trafficInfo": traffic, "linksPerPath": links, "variantApplied": variants}


# ---------------------------------------------------------------------------------------- 6 ADAS state

def adas_state(drive, clock, paths_out, sessions_out):
    if not drive.pilots:
        drive.missing.append("нет PilotStatus service 13 (ни в кольце пакетов, ни в журнале): эпизоды ADAS неизвестны")
    episodes = []
    for key, label in (("dnp", "DNP"), ("icc", "ICC"), ("acc", "ACC")):
        for value, meaning in ((1, "active"), (2, "available")):
            current = None
            for obs in drive.pilots:
                if obs.get(key) == value:
                    if current is None:
                        current = {"function": label, "value": value, "meaning": meaning, "first": obs, "count": 0, "gap": 0,
                                   "packets": False}
                    else:
                        current["gap"] = max(current["gap"], obs["t"] - current["last"]["t"])
                    current["count"] += 1
                    current["last"] = obs
                    current["packets"] = current["packets"] or obs["source"].startswith("packets")
                elif current is not None:
                    current["gap"] = max(current["gap"], obs["t"] - current["last"]["t"])
                    current["following"] = obs
                    episodes.append(current)
                    current = None
            if current is not None:
                episodes.append(current)
    episodes.sort(key=lambda ep: ep["first"]["t"])
    out = []
    for ep in episodes:
        start = ep["first"]["t"]
        view = {"function": ep["function"], "value": ep["value"], "meaning": ep["meaning"],
                "start": at(start, None, clock) | {"ref": ep["first"]["ref"], "source": ep["first"]["source"]},
                "observations": ep["count"],
                "lengthMs": (ep["following"]["t"] - start) if "following" in ep else None,
                "lastExplicitAfterMs": ep["last"]["t"] - start,
                "maxObservationGapMs": ep["gap"] if ep["packets"] else None,
                "recordingHole": bool(ep["packets"] and ep["gap"] > 2000),
                "endRef": ep["following"]["ref"] if "following" in ep else None,
                "open": "following" not in ep,
                "noa": _noa_family(drive, start), "aligned": _align(drive, clock, start, sessions_out)}
        out.append(view)
    return {"sources": drive.pilot_sources, "repeatsWithoutRetainedOriginal": drive.orphan_repeats,
            "presence": "packets: absent proto3 field = unknown, never 0; journal rows read an absent field as 0",
            "length": "lengthMs runs to the first observation without the value; recordingHole: packets more than 2 s apart "
                      "inside the episode (the recorder, not ADAS), so only lastExplicitAfterMs is proved",
            "episodes": out}


def _noa_family(drive, t):
    before = [m for m in drive.modes if m["t"] <= t and m.get("noaMode") is not None]
    if not before:
        return None
    m = before[-1]
    return {"noaMode": m["noaMode"], "family": {1: "HNOA (highway)", 2: "CNOA (city)"}.get(m["noaMode"], "unknown value"),
            "ageMs": t - m["t"], "ref": m["ref"]}


def _align(drive, clock, t, sessions_out):
    if not drive.live:
        return {"known": False, "why": "no live journal"}
    if not drive.live_covers(t):
        return {"known": False, "why": "the live journal starts after this moment (rotated out or not running)"}
    # Path state at t from the guard's own decisions.
    path, confirmed, ended = None, None, None
    for r in drive.path_events:
        if r["_t"] > t:
            break
        inp = r.get("input")
        if inp == "liveEgress.route":
            path, confirmed, ended = r.get("pathId"), None, None
        elif inp == "liveEgress.ack":
            if r.get("reason") == "ACCEPTED" and r.get("pathId") == path:
                confirmed = r["_t"]
        elif path is not None and ended is None:
            ended = (inp, r["_t"], r["_ref"])
    last = drive.last_send(t)
    session = None
    for s in sessions_out:
        lo, hi = s["_window"]
        if lo <= t <= hi:
            session = s
    stopped_before = [s for s in sessions_out if s.get("end") and s["end"]["elapsedMs"] is not None and s["end"]["elapsedMs"] <= t]
    out = {"known": True,
           "sessionLive": session is not None and (session.get("end") is None or session["end"]["elapsedMs"] > t),
           "session": session["armId"] if session else None,
           "variant": (session.get("variant") or {}).get("id") if session else None,
           "livePath": path if path is not None and ended is None else None,
           "livePathConfirmed": bool(confirmed) if path is not None and ended is None else False,
           "lastPathOfOursAtAdas": path if path is not None and ended is not None and ended[0] != "liveEgress.end" else None,
           "pathEndedBy": {"row": ended[0], "elapsedMs": ended[1], "ref": ended[2]} if ended else None,
           "sinceLastActualSendMs": (t - last["_t"]) if last else None,
           "lastActualSend": {"topic": topic_name(last["_topic"]), "ref": last["_ref"]} if last else None}
    if stopped_before and not out["sessionLive"]:
        s = stopped_before[-1]
        out["sinceSessionStoppedMs"] = t - s["end"]["elapsedMs"]
        out["stoppedSession"] = {"armId": s["armId"], "endStatusesSent": (s.get("endStatuses") or {}).get("sent"),
                                 "ref": s["end"]["ref"]}
    route_path = out["livePath"] or out["lastPathOfOursAtAdas"]
    for note in _variant_notes_cached(drive).get(route_path, []):
        if "historySpeed" in note:
            out["historySpeedVariant"] = {"value": note["historySpeed"], "routeOrdinal": note.get("routeOrdinal"),
                                          "routeId": note.get("routeId"), "ref": note["ref"]}
    speed = drive.last_send(t, SPEED_INTERSECTION)
    if speed is not None:
        out["historySpeedSent"] = {"value": (decode_out(speed) or {}).get("historySpeed"), "ref": speed["_ref"],
                                   "ageMs": t - speed["_t"]}
    under = _road_under(drive, t)
    if under:
        out["roadUnderCar"] = under
    return out


def _road_under(drive, t):
    position = drive.last_send(t, VEHICLE_LOCATION)
    if position is None:
        return None
    fields = decode_out(position) or {}
    path = fields.get("curSDRouteID")
    out = {"pathId": path, "ownership": fields.get("currentRoadOwnership"), "positionRef": position["_ref"],
           "positionAgeMs": t - position["_t"]}
    route = drive.route_json.get(int(path)) if isinstance(path, (int, float)) and path > 0 else None
    if route:
        segments = [s for g in route["route"].get("guideGroups") or [] for s in g.get("segments") or []]
        step, link = fields.get("curStepId", 0), fields.get("curLinkId", 0)
        if 0 <= step < len(segments):
            index = segments[step].get("linkBegIdx", 0) + link
            links = route["route"].get("links") or []
            if 0 <= index < len(links):
                l = links[index]
                out["link"] = {"index": index, "roadclass": l.get("roadclass"), "formway": l.get("formway"),
                               "ownership": l.get("ownership"), "laneNum": l.get("laneNum"), "direction": l.get("direction"),
                               "roadname": l.get("roadname"), "routeRef": route["ref"]}
    traffic = drive.last_send(t, TRAFFIC_INFO)
    if traffic is not None:
        out["trafficRoadClass"] = (decode_out(traffic) or {}).get("roadClass")
        out["trafficRef"] = traffic["_ref"]
    return out


# ---------------------------------------------------------------------------------------- 7 side channels

def side_channels(drive, clock):
    out = {}
    for channel, note, what, ledger_name, fid, rollback_key in (
            ("naviSt", "liveSession.naviSt", "naviSt", "naviStLedger", NAVI_ST_FID, "naviStRestore"),
            ("instrumentNaviStatus", "liveSession.instrumentNaviStatus", "instrumentNaviStatus", "instrumentLedger",
             INSTRUMENT_FID, "instrumentNaviStatusRestore")):
        events = {}

        def add(event, arm_id, ref):
            n = event.get("n")
            key = (arm_id, n if n is not None else ref)
            item = events.setdefault(key, {"armId": arm_id, "n": n, "op": event.get("op"), "purpose": event.get("purpose"),
                                           "value": event.get("value"), "ok": event.get("ok"), "error": event.get("error"),
                                           "elapsedMs": event.get("elapsedMs"), "refs": []})
            item["refs"].append(ref)

        for r in drive.live:
            if r.get("input") == note:
                event = _json_detail(r.get("detail"))
                if event:
                    add(event, r.get("armId"), r["_ref"])
        for r in drive.journal:
            if r.get("kind") == "adas_state" and r.get("what") == what:
                add(r, r.get("armId"), r["_ref"])
        ledger, ledger_ref = drive.e.state(ledger_name)
        if ledger:
            for event in ledger.get("events") or []:
                if isinstance(event, dict):
                    add(event, ledger.get("armId"), ledger_ref)
        items = sorted(events.values(), key=lambda x: (str(x["armId"]), x["n"] if isinstance(x["n"], int) else 0))
        for item in items:
            item["wall"] = clock.wall(item["elapsedMs"]) if _number(item["elapsedMs"]) is not None else None
        writes = [i for i in items if i["op"] == "write"]
        restores = [i for i in writes if str(i["purpose"] or "").startswith("restore")]
        guidance = [i for i in writes if not str(i["purpose"] or "").startswith("restore")]
        host = [(lg.get(rollback_key), ref) for lg, ref in drive.e.rollback if isinstance(lg.get(rollback_key), dict)]
        view = {"fid": hex(fid), "events": capped(items, 200), "writes": len(writes), "guidanceWrites": len(guidance),
                "restoreWrites": len(restores),
                "ledger": {k: ledger.get(k) for k in ("state", "original", "dirty", "reason", "updatedAtElapsedMs")} | {"ref": ledger_ref} if ledger else None,
                "hostRestore": [dict(result, ref=ref) for result, ref in host]}
        if not items and not ledger and not host:
            view["journalled"] = False
            view["verdict"] = "not_journalled"
        else:
            view["journalled"] = True
            view["verdict"] = _side_verdict(items, ledger, host)
        view["verdictText"] = SIDE_VERDICTS[view["verdict"]][0]
        out[channel] = view
    return out


SIDE_VERDICTS = {
    "not_journalled": ("not journalled (variant switch off, or a build without the channel)", "не записывался (переключатель варианта выключен или сборка без канала)"),
    "host_verified": ("restored and verified by the host's read-back", "восстановлен, хост перечитал и подтвердил"),
    "never_written": ("read, never written", "читался, но не записывался"),
    "ledger_clean_no_restore": ("ledger says clean, but no restore write is journalled after the last guidance write",
                                "реестр считает чистым, но записи восстановления после последней записи ведения нет"),
    "not_restored": ("NOT restored: the last guidance write has no successful restore after it",
                     "НЕ восстановлен: после последней записи ведения нет успешного восстановления"),
    "restored_readback": ("restored: the read-back equals the arm-time original", "восстановлен: перечитанное равно исходному значению"),
    "restore_unverified": ("restore written; read-back of the original not established", "восстановление записано, перечитывание не подтверждено"),
}
NEW_PATH_RU = {"first_for_route": "первый", "offline_withdrawal": "отзыв офлайн-карты", "new_session": "новый сеанс",
               "dnp_switch_resend": "повтор после включения DNP", "retry_unsent": "повтор неотправленного",
               "guidance_restart": "перезапуск ведения", "after_invalidation": "после сброса пути охраной",
               "unexplained": "без причины"}


def _side_verdict(items, ledger, host):
    if any(h.get("verified") for h, _ in host):
        return "host_verified"
    writes = [i for i in items if i["op"] == "write"]
    if not writes:
        return "never_written"
    last_guidance = max((i["n"] for i in writes if not str(i["purpose"] or "").startswith("restore") and isinstance(i["n"], int)), default=None)
    last_restore = max((i["n"] for i in writes if str(i["purpose"] or "").startswith("restore") and i["ok"] and isinstance(i["n"], int)), default=None)
    if last_guidance is not None and (last_restore is None or last_restore < last_guidance):
        if ledger and ledger.get("dirty") is False:
            return "ledger_clean_no_restore"
        return "not_restored"
    readback = [i for i in items if i["op"] == "read" and str(i["purpose"] or "").startswith("restore") and isinstance(i["n"], int)
                and last_restore is not None and i["n"] > last_restore]
    original = ledger.get("original") if ledger else None
    if readback and original is not None and readback[-1]["value"] == original:
        return "restored_readback"
    return "restore_unverified"


# ---------------------------------------------------------------------------------------- 8 health

def health(drive, clock, sessions_out):
    e = drive.e
    out = {"rings": _rings(drive, clock, sessions_out)}
    start = (out["rings"].get("driveStart") or {}).get("elapsedMs")
    processes = collections.OrderedDict()
    for r in drive.guidance:
        p = r.get("sourceSessionId")
        if p:
            item = processes.setdefault(p, {"first": r["_t"], "firstRef": r["_ref"], "samples": 0})
            item["samples"] += 1
            item["last"], item["lastRef"] = r["_t"], r["_ref"]
            if isinstance(r.get("recorder"), dict):
                item["recorder"], item["recorderRef"] = r["recorder"], r["_ref"]
    for r in drive.packets:
        p = r.get("sourceSessionId")
        if p and p not in processes:
            processes[p] = {"first": r["_t"], "firstRef": r["_ref"], "samples": 0, "fromPackets": True}
    out["navigatorProcesses"] = [{"sourceSessionId": k, "first": at(v["first"], None, clock), "last": at(v.get("last"), None, clock) if v.get("last") is not None else None,
                                  "guidanceSamples": v["samples"], "recorder": v.get("recorder"), "recorderRef": v.get("recorderRef"),
                                  "firstRef": v["firstRef"]} for k, v in processes.items()]
    during = [k for k, v in processes.items() if start is None or v.get("last", v["first"]) >= start]
    out["navigatorProcessesDuringDrive"] = during
    out["navigatorRestarts"] = max(0, len(during) - 1)
    out["navigatorRestartsDefinition"] = "Navigator process sessions (sourceSessionId) seen from the drive's start on, minus one"
    live_processes = []
    for r in drive.live:
        p = r.get("processSession")
        if p and p not in live_processes:
            live_processes.append(p)
    out["liveProcesses"] = live_processes
    out["resumedArms"] = [x for s in sessions_out for x in s.get("resumedArms", [])]
    lineage, lineage_ref = e.state("lineage")
    out["lineage"] = dict(lineage, ref=lineage_ref) if lineage else None
    state, state_ref = e.state("liveState")
    if state:
        out["callbacks"] = {"ref": state_ref, "stats": state.get("callbacks"), "observationsCoalesced": state.get("observationsCoalesced")}
    else:
        out["callbacks"] = None
        drive.missing.append("нет denza-adas-live-state.json: счётчики очереди колбэков неизвестны")
    drops = {}
    for p in out["navigatorProcesses"]:
        rec = p.get("recorder") or {}
        for key in ("rawDropped", "criticalDropped", "rawPostFailures", "criticalPostFailures", "historyWriteErrors",
                    "packetWriteErrors", "journalWriteErrors", "journalDropped"):
            if _number(rec.get(key)) is not None:
                drops[key] = drops.get(key, 0) + rec[key]
    out["recorderLosses"] = drops
    out["journalFull"] = [r["_ref"] for r in drive.journal if r.get("kind") == "adas_state_journal_full"]
    markers, seen = [], set()
    for label, lines in e.logs:
        for number, line in enumerate(lines, 1):
            if not CRASH_MARKERS.search(line) or line.strip() in seen:
                continue
            seen.add(line.strip())
            t = _logcat_elapsed(line, clock, start)
            markers.append({"ref": label + ":" + str(number), "line": line.strip()[:240], "elapsedMs": t,
                            "kind": "kill" if re.search(r"am_kill|am_proc_died", line) else "crash",
                            "duringDrive": None if t is None or start is None else t >= start,
                            "navigator": "yandexnavi" in line, "avc": "com.byd.avc" in line})
    for r in drive.live:
        if r.get("input") in ("liveSession.stopping", "liveSession.stopped") and "Ошибка" in str(r.get("detail")):
            markers.append({"ref": r["_ref"], "line": str(r.get("detail"))[:240], "liveSessionError": True})
    out["crashMarkers"] = capped(markers, 100)
    if not e.logs:
        drive.missing.append("нет файла crash/logcat: падения видны только по журналам")
    return out


LOGCAT_TIME = re.compile(r"^\s*(\d{2})-(\d{2})\s+(\d{2}):(\d{2}):(\d{2})\.(\d{3})")


def _logcat_elapsed(line, clock, reference):
    """A threadtime logcat line's wall time on the evidence's elapsed clock; None when it cannot be placed."""
    m = LOGCAT_TIME.match(line)
    if not m or clock.offset is None or reference is None:
        return None
    year = datetime.datetime.fromtimestamp((clock.offset + reference) / 1000, clock.zone).year
    month, day, hour, minute, second, milli = (int(x) for x in m.groups())
    try:
        wall = datetime.datetime(year, month, day, hour, minute, second, milli * 1000, tzinfo=clock.zone)
    except ValueError:
        return None
    return int(wall.timestamp() * 1000) - clock.offset


def _rings(drive, clock, sessions_out):
    starts = []
    for s in sessions_out:
        if s.get("armIssued"):
            starts.append(("arm issued", s["armIssued"]["elapsedMs"], s["armIssued"]["ref"]))
        if s.get("start") and s["start"].get("how") == "liveSession.armed":
            starts.append(("liveSession.armed", s["start"]["elapsedMs"], s["start"]["ref"]))
    journal_main = [r for r in drive.journal if r["_gen"] == 0 and r["_t"] is not None]
    if journal_main:
        starts.append(("change journal's first row", journal_main[0]["_t"], journal_main[0]["_ref"]))
    if not starts:
        for name in ("live", "packets", "progress"):
            rows = [r for r in drive.e.streams[name] if r["_t"] is not None]
            if rows:
                starts.append(("earliest retained " + name, rows[0]["_t"], rows[0]["_ref"]))
    drive_start = min(starts, key=lambda s: s[1]) if starts else None
    out = {"driveStart": {"how": drive_start[0], "elapsedMs": drive_start[1], "wall": clock.wall(drive_start[1]), "ref": drive_start[2]} if drive_start else None,
           "streams": []}
    for name in ("live", "packets", "progress", "journal"):
        rows = [r for r in drive.e.streams[name] if r["_t"] is not None]
        generations = sorted(drive.e.generations[name])
        item = {"stream": name, "files": len(generations), "generations": generations, "currentBuildKeeps": GENERATIONS[name],
                "rows": len(drive.e.streams[name])}
        if rows:
            item["first"] = at(rows[0]["_t"], rows[0], clock)
            item["last"] = at(rows[-1]["_t"], rows[-1], clock)
        if name == "journal":
            item["note"] = "rotates only between drives (30 min idle); .1-.3 are earlier drives"
        elif rows and drive_start:
            late = rows[0]["_t"] - drive_start[1]
            item["startsAfterDriveStartMs"] = late
            item["lostDriveStart"] = late > 5000
            item["rotated"] = any(g > 0 for g in generations)
        elif not rows:
            item["lostDriveStart"] = None
        out["streams"].append(item)
    return out


# ---------------------------------------------------------------------------------------- review

def review(root, tz_hours=3):
    evidence = Evidence(root)
    clock = Clock(evidence, tz_hours)
    drive = Drive(evidence, clock)
    for name, label in (("live", "denza-adas-live.jsonl"), ("journal", "adas-journal.jsonl"),
                        ("packets", "adas-packets.jsonl"), ("progress", "progress.jsonl")):
        if not evidence.streams[name]:
            drive.missing.append(label + " не найден")
    sessions_out, _ = sessions(drive, clock)
    paths_out = paths(drive, clock)
    cadence_out = cadence(drive, clock)
    pauses_out = pauses_and_gaps(drive, clock, cadence_out)
    semantics_out = semantics(drive, clock)
    adas_out = adas_state(drive, clock, paths_out, sessions_out)
    side_out = side_channels(drive, clock)
    health_out = health(drive, clock, sessions_out)
    for s in sessions_out:
        s.pop("_window", None)
    return {"schema": SCHEMA, "evidence": str(root), "clock": clock.describe(),
            "inventory": evidence.inventory, "parseErrors": capped(evidence.parse_errors, 100),
            "sessions": sessions_out, "paths": paths_out, "cadence": cadence_out, "pausesAndGaps": pauses_out,
            "semantics": semantics_out, "adasState": adas_out, "sideChannels": side_out, "health": health_out,
            "missing": sorted(set(drive.missing))}, clock


# ---------------------------------------------------------------------------------------- Russian summary

def _s(ms):
    return "—" if ms is None else ("%.1f с" % (ms / 1000) if abs(ms) >= 1000 else "%d мс" % ms)


MEANING_RU = {"active": "активен", "available": "доступен"}


def _length(ep):
    if ep["open"]:
        return "до конца записи"
    if ep.get("recordingHole"):
        return "не меньше %s (дыра в записи %s)" % (_s(ep["lastExplicitAfterMs"]), _s(ep["maxObservationGapMs"]))
    return _s(ep["lengthMs"])


def _yes(value):
    return {True: "да", False: "нет", None: "неизвестно"}[value]


def markdown(result, clock):
    t = clock.short
    lines = ["# Разбор поездки: мост Яндекс → ADAS", "",
             "Источник: `%s`. Время — %s (UTC%+d), получено из часов самих записей." % (
                 result["evidence"], "местное" if clock.offset is not None else "только elapsed", clock.tz_hours), ""]
    if result["missing"]:
        lines += ["**Нет данных:** " + "; ".join(result["missing"]) + ".", ""]

    lines += ["## 1. Сеансы", ""]
    if not result["sessions"]:
        lines.append("Сеансов в записях нет.")
    for s in result["sessions"]:
        v = s.get("variant") or {}
        es = s.get("endStatuses") or {}
        rel = s.get("release") or {}
        cause = (s.get("firstCause") or {}).get("detail")
        start = s.get("start") or {}
        issued = s.get("armIssued")
        lines.append("- Сеанс `%s`, вариант **%s**%s: %s%s до %s. Первая причина конца: «%s»." % (
            s["armId"][:8], v.get("id") or "не записан",
            " (" + ", ".join(v.get("overrides") or []) + ")" if v.get("overrides") else "",
            "разрешение выдано " + t(issued["elapsedMs"]) + ", " if issued else "",
            ("журнал с " if start.get("how") != "liveSession.armed" else "с ") + t(start.get("elapsedMs")),
            t((s.get("end") or {}).get("elapsedMs")), cause or "не записана"))
        held = rel.get("heldPathId")
        lines.append("  Конечные статусы стока: %s%s. Ответы ADAS (выбор/PathMatch) о нашем пути после конца: %s%s." % (
            _yes(es.get("sent")), " (" + ", ".join(map(str, es.get("values") or [])) + ")" if es.get("values") else "",
            {True: "нет" + (" — путь отпущен" if es.get("sent") else ""),
             False: "есть, %d — путь не отпущен" % rel.get("laterAnswersOnOurPaths", 0),
             None: "неизвестно"}[rel.get("released")],
            " (но без конечных статусов путь %s мог остаться у ADAS)" % held if held and es.get("sent") is False else ""))
        if s.get("resumedArms"):
            lines.append("  Возобновлений после перезапуска Навигатора: %d." % len(s["resumedArms"]))
    lines.append("")

    lines += ["## 2. Пути", "", "Публикаций маршрута: %d." % result["paths"].get("publications", 0), ""]
    for route in result["paths"].get("routes", []):
        ok = route["unexpectedNewPaths"] == 0 and route["paths"] >= 1
        lines.append("- Маршрут `%s`: путей **%d** за %s, лишних %d, с ACK 200: %d.%s" % (
            route["routeId"], route["paths"], _s(route["spanMs"]), route["unexpectedNewPaths"], route["withAck200"],
            "" if ok else " Причины: " + ", ".join("%s ×%d" % (NEW_PATH_RU.get(k, k), n) for k, n in route["reasons"].items()) + "."))
    lines.append("")

    lines += ["## 3. Частота отправки (во время ведения)", "", "| Тема | всего | медиана | p95 | макс | > 2,5 с |", "|---|---|---|---|---|---|"]
    for topic in result["cadence"].get("topics", []):
        if topic["intervalsWhileGuided"] == 0 and not topic["expectedAbout1s"]:
            continue
        flag = "" if topic["cadenceOk"] in (None, True) else " ⚠"
        lines.append("| %s%s | %d | %s | %s | %s | %d |" % (topic["name"], flag, topic["sentTotal"], _s(topic["medianMs"]),
                                                      _s(topic["p95Ms"]), _s(topic["maxMs"]), len(topic["over2500ms"])))
    if result["cadence"].get("guidedWindowsInferred"):
        lines.append("")
        lines.append("Начало ведения выведено из первого navStop: журнал начинается посреди ведения.")
    lines.append("")

    lines += ["## 4. Паузы и разрывы", ""]
    pg = result["pausesAndGaps"]
    if not pg["pauses"]:
        lines.append("Пауз LIVE нет.")
    for p in pg["pauses"][:30]:
        g = p["guidance"]
        lines.append("- Пауза %s, %s: «%s». Ведение Яндекса: %s." % (
            t(p["start"]["elapsedMs"]), _s(p["lengthMs"]), p["prerequisite"],
            "%d/%d FINE" % (g.get("fine", 0), g["samples"]) if g["samples"] else "нет образцов"))
    if pg["pathDrops"]:
        lines.append("- Путь сброшен охраной: %d раз (первый %s, «%s»)." % (
            len(pg["pathDrops"]), t(pg["pathDrops"][0]["elapsedMs"]), pg["pathDrops"][0]["reason"]))
    for gap in pg["positionGapsOver2500ms"][:30]:
        g = gap["guidance"]
        lines.append("- Разрыв позиций %s с %s; Яндекс в это время: %s." % (
            _s(gap["ms"]), t(gap["from"]["elapsedMs"]),
            "%d/%d FINE" % (g.get("fine", 0), g["samples"]) if g["samples"] else "нет образцов"))
    if not pg["positionGapsOver2500ms"]:
        lines.append("Разрывов позиций больше 2,5 с нет.")
    lines.append("")

    sem = result["semantics"]
    pos, tr = sem["positions"], sem["trafficInfo"]
    lines += ["## 5. Смысл полей", "",
              "Позиций отправлено %d, на нашем пути %d; ownership −1 (у Яндекса нет значения): %d, заполнено вариантом: %d." % (
                  pos["sent"], pos["matched"], pos["ownershipMinus1"], pos["ownershipVariantFilled"]),
              "TrafficInfo %d, на пути %d; roadClass −1: %d, заполнено вариантом: %d." % (
                  tr["sent"], tr["matched"], tr["roadClassMinus1"], tr["roadClassVariantFilled"]), ""]
    if sem["linksPerPath"]:
        lines += ["| Путь | звеньев | class −1 | dir 0 | own −1 | lanes 0 |", "|---|---|---|---|---|---|"]
        for item in sem["linksPerPath"][:40]:
            f = item["fields"]
            lines.append("| %s | %d | %d | %d | %d | %d |" % (item["pathId"], item["links"], f["roadclass"]["minus1"],
                                                          f["direction"]["zero"], f["ownership"]["minus1"], f["laneNum"]["zero"]))
        lines.append("")

    lines += ["## 6. Что делал ADAS", ""]
    eps = result["adasState"]["episodes"]
    shown = [ep for ep in eps if ep["function"] == "DNP" or ep["value"] == 1]
    if not eps:
        lines.append("Эпизодов DNP/ICC/ACC нет" + (" (нет данных service 13)." if not result["adasState"]["sources"]["packetObservations"]
                                                     and not result["adasState"]["sources"]["journalTransitions"] else "."))
    for ep in shown[:40]:
        a = ep["aligned"]
        noa = (ep.get("noa") or {}).get("family")
        if a.get("known"):
            what = ("наш путь %s%s" % (a["livePath"], "" if a["livePathConfirmed"] else " (без ACK)")) if a.get("livePath") else (
                "сеанс остановлен %s назад, у ADAS может оставаться путь %s" % (_s(a["sinceSessionStoppedMs"]), a.get("lastPathOfOursAtAdas"))
                if a.get("sinceSessionStoppedMs") is not None else "нашего пути нет")
            road = a.get("roadUnderCar") or {}
            link = road.get("link") or {}
            lines.append("- %s %s (%s) %s, %s%s: %s; последняя отправка %s назад; вариант %s; historySpeed %s%s; класс звена %s." % (
                ep["function"], MEANING_RU[ep["meaning"]], ep["value"], t(ep["start"]["elapsedMs"]), _length(ep),
                ", " + noa if noa else "", what, _s(a.get("sinceLastActualSendMs")), a.get("variant") or "—",
                (a.get("historySpeedSent") or {}).get("value", "—"),
                " (вариант для маршрута: %s)" % a["historySpeedVariant"]["value"] if a.get("historySpeedVariant") else "",
                link.get("roadclass", road.get("trafficRoadClass", "—"))))
        else:
            lines.append("- %s %s %s, %s%s: что отправлялось — неизвестно (%s)." % (
                ep["function"], MEANING_RU[ep["meaning"]], t(ep["start"]["elapsedMs"]), _length(ep), ", " + noa if noa else "", a.get("why")))
    if len(shown) > 40:
        lines.append("- … ещё %d в review.json." % (len(shown) - 40))
    lines.append("")

    lines += ["## 7. Боковые каналы", ""]
    for name, label in (("naviSt", "NaviSt 0x4CA0004B"), ("instrumentNaviStatus", "статус навигации приборки 0x43E0003A")):
        c = result["sideChannels"][name]
        lines.append("- %s: записей %d (ведение %d, восстановление %d) — %s." % (
            label, c["writes"], c["guidanceWrites"], c["restoreWrites"], SIDE_VERDICTS[c["verdict"]][1]))
    lines.append("")

    h = result["health"]
    lines += ["## 8. Здоровье", "",
              "- Перезапусков Навигатора: %d; возобновлённых сеансов: %d." % (h["navigatorRestarts"], len(h["resumedArms"]))]
    cb = (h.get("callbacks") or {}).get("stats") or {}
    if cb:
        lines.append("- Колбэки: выброшено старых %s, ошибок постинга %s, ошибок задач %s." % (
            cb.get("droppedOldest"), cb.get("postFailures"), cb.get("taskFailures")))
    if h["recorderLosses"]:
        lines.append("- Потери записи: " + ", ".join("%s %s" % kv for kv in h["recorderLosses"].items() if kv[1]) + "." if any(h["recorderLosses"].values()) else "- Потерь записи нет.")
    for ring in h["rings"]["streams"]:
        if ring.get("lostDriveStart"):
            lines.append("- Кольцо %s начинается через %s после начала поездки — начало потеряно." % (
                ring["stream"], _s(ring["startsAfterDriveStartMs"])))
    if h["journalFull"]:
        lines.append("- Журнал изменений переполнен.")
    crashes = [m for m in h["crashMarkers"] if isinstance(m, dict) and m.get("ref")]
    during = [m for m in crashes if m.get("duringDrive") or m.get("liveSessionError")]
    lines.append("- За поездку: падений %d, остановок процесса %d%s." % (
        sum(1 for m in during if m.get("kind") != "kill"), sum(1 for m in during if m.get("kind") == "kill"),
        " (ещё %d отметок до неё)" % (len(crashes) - len(during)) if len(crashes) > len(during) else ""))
    for m in during[:5]:
        lines.append("  - `%s`: %s" % (m["ref"], m["line"][:160]))
    return "\n".join(lines) + "\n"


def main(argv=None):
    parser = argparse.ArgumentParser(description="Post-drive review of the Yandex -> ADAS bridge evidence.")
    parser.add_argument("evidence", help="directory with the files pulled from the car")
    parser.add_argument("--out", required=True, help="directory for review.json and review.md")
    parser.add_argument("--tz-hours", type=int, default=3, help="wall-time zone offset (default 3, Moscow)")
    args = parser.parse_args(argv)
    try:
        result, clock = review(args.evidence, args.tz_hours)
    except InputError as error:
        print("drive_review: " + str(error), file=sys.stderr)
        return 2
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    (out / "review.json").write_text(json.dumps(result, ensure_ascii=False, indent=1, default=str) + "\n", encoding="utf-8")
    (out / "review.md").write_text(markdown(result, clock), encoding="utf-8")
    print(str(out / "review.md"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
