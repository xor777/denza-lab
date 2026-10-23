#!/usr/bin/env python3
"""Bounded read-only emulator watcher → incomplete bytes → local fake receiver.

Refuses all serials except emulator-N and verifies ro.kernel.qemu=1 before
reading app files. It never starts a service, installs, launches, publishes,
opens a socket or writes a device file. Real car ACKs are never synthesized as
evidence: every confirmation here is a labelled local mock.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

from .bench import make_bench
from .bridge import ContractError, route_convert
from .stock import PathIds
from .receiver import FakeReceiver
from .wire import decode_someip_route
from .dynamic import make_dynamic_packets, status_bytes

MAX_FILE_BYTES = 16 * 1024 * 1024


def validate_serial(serial):
    if not isinstance(serial, str) or not re.fullmatch(r"emulator-[0-9]{1,6}", serial):
        raise ContractError("watcher accepts only an explicit emulator-N serial; car serials are refused")


class EmulatorReader:
    def __init__(self, adb, serial, package, *, command_timeout=5):
        validate_serial(serial)
        if not re.fullmatch(r"[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)+", package):
            raise ContractError("invalid Android package name")
        self.adb, self.serial, self.package = adb, serial, package
        self.command_timeout = command_timeout
        self.authorized = False
        self.deadline = None

    def command(self, arguments):
        budget = self.command_timeout
        if self.deadline is not None:
            budget = min(budget, self.deadline-time.monotonic())
            if budget <= 0:
                raise ContractError("bounded watcher deadline reached")
        completed = subprocess.run([self.adb, "-s", self.serial] + list(arguments),
                                   check=False, capture_output=True, timeout=budget)
        if completed.returncode:
            raise ContractError("emulator read failed: " + completed.stderr.decode(errors="replace")[:300])
        return completed.stdout

    def verify(self):
        if self.command(["shell", "getprop", "ro.kernel.qemu"]).strip() != b"1":
            raise ContractError("ro.kernel.qemu is not 1; refusing non-emulator device")
        self.authorized = True

    def uptime_ms(self):
        if not self.authorized:
            raise ContractError("emulator guard not verified")
        # GuidanceSnapshot's capturedUptimeMs is SystemClock.elapsedRealtime().
        return int(float(self.command(["exec-out", "cat", "/proc/uptime"]).split()[0]) * 1000)

    def json_file(self, filename):
        if not self.authorized:
            raise ContractError("emulator guard not verified")
        if filename not in ("denza-hud-native-route.json", "denza-adas-progress.json"):
            raise ContractError("file is outside the diagnostic read allowlist")
        try:
            data = self.command(["exec-out", "run-as", self.package, "head", "-c",
                                 str(MAX_FILE_BYTES+1), "files/"+filename])
        except ContractError:
            # Already-root emulator only; no adb root/su action is performed.
            data = self.command(["exec-out", "head", "-c", str(MAX_FILE_BYTES+1),
                                 f"/data/user/0/{self.package}/files/{filename}"])
        if len(data) > MAX_FILE_BYTES:
            raise ContractError("diagnostic JSON exceeds size bound")
        return json.loads(data.decode("utf-8"))


class LiveBridge:
    """Local state only, following the stock lifecycle: one route publication per pathId, a
    transient loss or reroute holds the position and never ends navigation (DYN-02/04), the same
    link table keeps its pathId (DYN-03), a finished route is retired (DYN-13)."""
    def __init__(self, golden_record, target_frame, roads=None):
        self.golden = golden_record
        self.target_frame = target_frame
        self.roads = roads
        self.receiver = FakeReceiver()
        self.bench = None
        self.converted = None
        self.byte_receipts = 0
        self.source_session = None
        self.retired_sessions = set()
        self.retired_routes = set()
        self.path_ids = PathIds()
        self.highest_generation = -1
        self.highest_sequence = -1
        self.dynamic_packets_parsed = 0

    def needs_route(self, sample):
        return (sample.get("routeId") is not None and (
            self.converted is None or sample.get("routeId") != self.converted["routeId"] or
            sample.get("sourceSessionId") != self.source_session))

    def tick(self, sample, *, now_ms, snapshot=None):
        receipt = {"offlineOnly": True, "publicationReady": False,
                   "sourceGeneration": sample.get("generation"), "sequence": sample.get("sequence"),
                   "deviceElapsedRealtimeMs": now_ms, "mockConfirmationOnly": True}
        session = sample.get("sourceSessionId")
        if session is not None and (not isinstance(session, str) or not session):
            raise ContractError("invalid sourceSessionId")
        if session in self.retired_sessions or (session is None and self.source_session is not None):
            return dict(receipt, accepted=False, reason="retired_or_missing_source_session")
        if session != self.source_session:
            if self.source_session is not None:
                self.retired_sessions.add(self.source_session)
            # A new Navigator process is a new stock process: its own path ids, its own feed.
            self.receiver.end(now_ms=now_ms)
            old = self.receiver
            self.receiver = FakeReceiver()
            self.receiver.trace = old.trace
            self.receiver.publication_epoch = old.publication_epoch
            self.source_session = session
            self.converted = self.bench = None
            self.path_ids = PathIds()
            self.retired_routes = set()
            self.highest_generation = self.highest_sequence = -1
            receipt["sourceSessionChanged"] = True
        generation, sequence = sample.get("generation"), sample.get("sequence")
        if type(generation) is not int or generation < 0 or type(sequence) is not int or sequence < 0:
            raise ContractError("missing source generation/sequence")
        if generation < self.highest_generation or sequence < self.highest_sequence:
            return dict(receipt, accepted=False, reason="old_source_generation_or_sequence",
                        status=self.receiver.status(now_ms=now_ms))
        if sequence == self.highest_sequence:
            return dict(receipt, accepted=False, reason="duplicate_read", status=self.receiver.status(now_ms=now_ms))
        self.highest_generation = generation
        self.highest_sequence = sequence
        route_id = sample.get("routeId")
        if sample.get("mode") == "off" or route_id is None:
            # Navigator stopped guiding: the stock onNavStop (110 only after an arrival).
            if self.receiver.active:
                if self.converted is not None:
                    self.retired_routes.add(self.converted["routeId"])
                self.receiver.end(now_ms=now_ms, arrived=sample.get("routeStatus") == "ROUTE_FINISHED")
            status = self.receiver.status(now_ms=now_ms)
            return dict(receipt, accepted=False, reason="navigation_stopped", status=status,
                        navigationStatusBinderHex=status_bytes(status).hex())
        if route_id in self.retired_routes:
            return dict(receipt, accepted=False, reason="retired_route", status=self.receiver.status(now_ms=now_ms))
        if self.needs_route(sample) or (snapshot is not None and snapshot.get("routeId") == route_id):
            if snapshot is None or snapshot.get("routeId") != route_id or snapshot.get("sourceSessionId") != session:
                return dict(receipt, accepted=False, reason="snapshot_progress_route_mismatch",
                            status=self.receiver.status(now_ms=now_ms))
            source = copy.deepcopy(snapshot)
            source["generation"] = generation
            source["sourceSessionId"] = session
            converted = route_convert(source, target_frame=self.target_frame, path_ids=self.path_ids, roads=self.roads)
            same = self.converted is not None and converted["contentKey"] == self.converted["contentKey"] and self.receiver.active
            if not same:
                self.bench = make_bench(converted, self.golden, time_ms=sample.get("capturedAtMs", 0), receiver=self.receiver)
                self.receiver = self.bench["receiver"]
                self.byte_receipts += 1
                receipt.update(routeBytesParsed=True, binderBytes=sum(len(b) for b in self.bench["binders"]),
                               chunks=len(self.bench["binders"]),
                               binderSHA256=hashlib.sha256(b"".join(self.bench["binders"])).hexdigest(),
                               links=converted["candidateNaviToEHPData"]["linkCnt"],
                               pathID=converted["candidateNaviToEHPData"]["pathID"])
            self.converted = converted
        if self.converted is None:
            return dict(receipt, accepted=False, reason="no_route_yet", status=self.receiver.status(now_ms=now_ms))
        result = self.receiver.progress(sample, now_ms=now_ms, clock="uptime")
        bundle = None
        if result["accepted"]:
            bundle = make_dynamic_packets(self.receiver.route, sample, result)
            self.receiver.consume_dynamic(bundle)
            self.dynamic_packets_parsed += len(bundle["packets"])
        return dict(receipt, accepted=result["accepted"], reason=result["reason"],
                    status=self.receiver.status(now_ms=now_ms),
                    publicationEpoch=self.receiver.publication_epoch, positionCandidate=result,
                    dynamicBinderPackets=bundle)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--package", default="ru.yandex.yandexnavi")
    parser.add_argument("--duration-s", type=float, default=60)
    parser.add_argument("--interval-s", type=float, default=1)
    parser.add_argument("--golden-someip", required=True)
    parser.add_argument("--target-frame", choices=("wgs84", "native-gcj-unrestricted-candidate"), required=True)
    parser.add_argument("--out-dir", required=True)
    args = parser.parse_args()
    if not 1 <= args.duration_s <= 300 or not .2 <= args.interval_s <= 5:
        parser.exit(2, "duration must be 1..300 seconds and interval 0.2..5 seconds\n")
    try:
        reader = EmulatorReader(args.adb, args.serial, args.package)
        reader.verify()
        engine = LiveBridge(decode_someip_route(Path(args.golden_someip).read_bytes()), args.target_frame)
        out = Path(args.out_dir)
        out.mkdir(parents=True, exist_ok=True)
        deadline = time.monotonic()+args.duration_s
        reader.deadline = deadline
        receipts, errors, accepted = [], 0, 0
        last_publication = None
        run_id = uuid.uuid4().hex[:12]
        last_device_now = 0
        with (out / "receipts.jsonl").open("w") as journal:
            while time.monotonic() < deadline:
                started = time.monotonic()
                try:
                    sample = reader.json_file("denza-adas-progress.json")
                    snapshot = reader.json_file("denza-hud-native-route.json") if engine.needs_route(sample) else None
                    now = reader.uptime_ms()
                    last_device_now = now
                    receipt = engine.tick(sample, now_ms=now, snapshot=snapshot)
                    publication = (engine.source_session, engine.converted.get("generation") if engine.converted else None,
                                   engine.receiver.publication_epoch)
                    if engine.bench is not None and publication != last_publication and receipt.get("routeBytesParsed"):
                        last_publication = publication
                        session_name = hashlib.sha256(str(engine.source_session).encode()).hexdigest()[:10]
                        prefix = f"run-{run_id}-session-{session_name}-generation-{publication[1]}-epoch-{publication[2]}"
                        for i, (binder, someip) in enumerate(zip(engine.bench["binders"], engine.bench["someips"])):
                            (out / (prefix+f".route-{i:02d}.binder.protobuf.bin")).write_bytes(binder)
                            (out / (prefix+f".route-{i:02d}.someip.bin")).write_bytes(someip)
                        (out / (prefix+".selection.binder.protobuf.bin")).write_bytes(engine.bench["selection"])
                        (out / (prefix+".candidate.json")).write_text(json.dumps(engine.converted, ensure_ascii=False, indent=2)+"\n")
                        (out / (prefix+".manifest.json")).write_text(json.dumps(engine.bench["manifest"], ensure_ascii=False, indent=2)+"\n")
                    accepted += bool(receipt.get("accepted"))
                except (ValueError, TypeError, KeyError, subprocess.TimeoutExpired) as exc:
                    errors += 1
                    # A failed read is a transient loss, not the end of navigation (DYN-02).
                    receipt = {"accepted": False, "reason": "read_or_contract_error", "error": str(exc)[:500], "offlineOnly": True}
                receipts.append(receipt)
                journal.write(json.dumps(receipt, ensure_ascii=False, allow_nan=False)+"\n")
                journal.flush()
                remaining = min(args.interval_s-(time.monotonic()-started), deadline-time.monotonic())
                if remaining > 0:
                    time.sleep(remaining)
        # All cleanup is local mock state; there was never an on-device feed.
        engine.receiver.end(now_ms=last_device_now)
        summary = {"schema": "denza-adas-emulator-watch-v1", "serial": args.serial, "qemuVerified": True,
                   "watchRunId": run_id,
                   "offlineMockOnly": True, "publicationReady": False, "realAdasAcceptanceEstablished": False,
                   "durationRequestedS": args.duration_s, "iterations": len(receipts),
                   "acceptedOfflineGeometrySamples": accepted, "byteReceipts": engine.byte_receipts,
                   "dynamicBinderPacketsParsed": engine.dynamic_packets_parsed,
                   "readErrors": errors, "deviceCommands": "read-only allowlisted diagnostics", "trace": engine.receiver.trace}
        (out / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2)+"\n")
        print(json.dumps({key: value for key, value in summary.items() if key != "trace"}))
    except (ValueError, OSError, subprocess.TimeoutExpired) as exc:
        parser.exit(2, f"emulator watcher refused/failed: {exc}\n")


if __name__ == "__main__":
    main()
