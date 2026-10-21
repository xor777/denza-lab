#!/usr/bin/env python3
"""Read fixed OTA history files, keeping only ECU fields; default is an offline plan.

Requires owner authorization for temporary BydLog export. Reuses the metadata
runner's exclusive lease, APK/process pins, serialized START/STOP and watchdog.
No Java helper, crypto, ECU request, OEM request, package download or install.
Raw preferences (including unrelated credentials) exist only in host memory.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import secrets
import shlex
import socket
import struct
import sys

import run_ecu_map_metadata as lifecycle

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "research/vehicle-diagnostics"))
import read_ecu_cache_export as wire
import read_ota_inventory_export as inventory

PREFS = tuple(f"/data/user/0/{package}/shared_prefs/share_data_prefs.xml"
              for package in ("com.byd.otaupdate", "com.byd.otaupdate2"))
RESULTS = tuple(f"/data/ota_package/{client}/result/{filename}"
                for client in ("ota", "ota_ghy", "ota_productline")
                for filename in ("upgradeInfo.json", "ecuOtaResult.json", "backUpEcuOtaResult.json"))
FILES = PREFS + RESULTS
DIRECTORIES = frozenset(str(PurePosixPath(p).parent) for p in FILES)
PREF_KEYS = ("repairUpgradeEcuListInfo", "ecuDiagnosisResultListInfo")
FIELDS = tuple(dict.fromkeys((*inventory.ECU_FIELDS, "ecuOriginSVer", "ecuStatus", "ecuUpdateFlag")))
NEWEST_FIELDS = ("ecuSoftCode", "ecuSoftVer", "ecuConfigCode", "ecuConfigVer")
SUB_FIELDS = ("subModelId", "ecuOriginSVer", "ecuSoftCode", "ecuSoftVer",
              "subModelStatus", "subModelUpdateFlag")


def rows(value):
    if type(value) is not list or len(value) > 2048:
        raise wire.ExportError("history_rows_invalid")
    result = inventory._project_rows(value, FIELDS, "ecuId")
    for source, target in zip(value, result):
        newest = source.get("ecuNewestInfo")
        if newest is not None:
            target["ecuNewestInfo"] = inventory._project_rows(
                [dict(newest, ecuId=source["ecuId"])] if type(newest) is dict else [None],
                NEWEST_FIELDS, "ecuId")[0]
        sub = source.get("subModel")
        if sub is not None:
            target["subModel"] = inventory._project_rows(sub, SUB_FIELDS, "subModelId")
    return result


def project(path, raw):
    if path not in FILES or type(raw) is not bytes or len(raw) > wire.MAX_FILE:
        raise wire.ExportError("history_source_invalid")
    if path in PREFS:
        selected = {}
        for key in PREF_KEYS:
            value = inventory._preference(raw, key)
            if value is None:
                selected[key] = {"status": "key_missing"}
            elif not value.strip() or inventory._json(value) is None:
                selected[key] = {"status": "key_no_data"}
            else:
                selected[key] = {"status": "present", "ecus": rows(inventory._json(value))}
        return {"preferences": selected}
    doc = inventory._json(raw)
    if type(doc) is not dict or "ecuModel" not in doc:
        raise wire.ExportError("history_result_schema")
    # UUID, VIN, network addresses and free-text reason fields are not projected.
    return {"ecus": rows(doc["ecuModel"])}


def request(action, path):
    allowed = FILES if type(action) is int and action == 1 else (
        DIRECTORIES if type(action) is int and action == 2 else ())
    if path not in allowed:
        raise wire.ExportError("request_not_allowed")
    key = "filePath" if action == 1 else "directory"
    body = json.dumps({key: wire.EXPORT_PREFIX + path.lstrip("/")}, separators=(",", ":")).encode()
    return struct.pack(">III", wire.MAGIC, action, len(body)) + body


def exchange(endpoint, action, path, deadline, size=None):
    message = request(action, path)
    try:
        with socket.create_connection(endpoint, timeout=deadline.timeout()) as sock:
            sock.settimeout(deadline.timeout())
            sock.sendall(message)
            doc = wire._envelope(sock, deadline)
            if action == 2:
                result = wire._list(doc)
                wire._eof(sock, deadline)
                return result
            return wire._download(doc, PurePosixPath(path).name, size, sock, deadline)
    except socket.timeout:
        raise wire.ExportError("socket_timeout") from None
    except OSError:
        raise wire.ExportError("socket_error") from None


def read_history(endpoint, result=None):
    deadline, listings = wire.Deadline(), {}
    if result is None:
        result = []
    for path in FILES:
        parent, name = str(PurePosixPath(path).parent), PurePosixPath(path).name
        if parent not in listings:
            try:
                listings[parent] = exchange(endpoint, 2, parent, deadline)
            except wire.MissingFile:
                listings[parent] = None
        listing = listings[parent]
        record = {"source": path}
        result.append(record)
        if listing is None:
            record["status"] = "parent_missing"
        elif name not in listing:
            record["status"] = "not_listed"
        else:
            if listing[name] > wire.MAX_FILE:
                raise wire.ExportError("file_size_out_of_bounds")
            try:
                raw = exchange(endpoint, 1, path, deadline, listing[name])
            except wire.MissingFile:
                record["status"] = "missing_at_read"
                continue
            record.update(status="read", rawSizeBytes=len(raw),
                          rawSha256=hashlib.sha256(raw).hexdigest(), **project(path, raw))
            del raw
        deadline.timeout()
    return result


class HistoryDevice(lifecycle.Device):
    def stage_history(self, nonce, pid):
        for name, content in zip(("transition.sh", "watchdog.sh"), lifecycle.scripts(nonce, pid)):
            self.shell(f"umask 077; printf %s {shlex.quote(content)} > {lifecycle.LEASE}/{name}")
        if self.shell(f"mkdir {self.EXPORT_DIRECTORY} && echo created") != "created":
            raise lifecycle.Refused("shared_export_directory_claim_unverified")

    def release_history(self, nonce):
        lease = lifecycle.LEASE
        self.shell(f"test \"$(cat {lease}/nonce)\" = {nonce} && "
                   f"test \"$(cat {lease}/phase)\" = stopped && "
                   f"case \"$(cat {lease}/watchdog)\" in cancelled|deadline_stopped) ;; *) exit 1;; esac && "
                   f"rm -f {lease}/deadline && rm {lease}/transition.sh {lease}/watchdog.sh "
                   f"{lease}/bydlog.pid {lease}/bydlog.start {lease}/phase {lease}/watchdog "
                   f"{lease}/cancel {lease}/nonce && rmdir {lease} && rmdir {self.EXPORT_DIRECTORY}")


def run(device):
    state = {"schemaVersion": 1, "startedUtc": datetime.now(timezone.utc).isoformat(),
             "status": "refused", "exportClosed": False, "watchdogTerminal": False,
             "leaseReleased": False, "forwardRemoved": False}
    nonce, port, armed, claimed = secrets.token_hex(16), None, False, False
    try:
        pid = device.preflight()
        forwards = device.command(["forward", "--list"])
        crash = device.shell("logcat -b crash -d -v brief")
        state["preCrashSha256"] = hashlib.sha256(crash.encode()).hexdigest()
        device.claim(nonce, pid)
        claimed = True
        device.stage_history(nonce, pid)
        armed = True
        device.arm()
        device.start()
        value = device.command(["forward", "tcp:0", "tcp:46321"])
        if not re.fullmatch(r"[1-9][0-9]{0,4}", value) or int(value) > 65535:
            raise lifecycle.Refused("allocated_forward_unverified")
        port = int(value)
        state["files"] = []
        read_history(wire._endpoint("127.0.0.1", port), state["files"])
        state["status"] = "complete"
    except (lifecycle.Refused, wire.ExportError) as error:
        state["error"] = str(error)
    except BaseException:
        state["error"] = "session_interrupted_or_internal_error"
    finally:
        if armed:
            try:
                device.close_export(pid)
                state["exportClosed"] = state["watchdogTerminal"] = True
            except BaseException:
                state["cleanupError"] = "export_closure_unverified_lease_retained"
        if port is not None:
            try:
                device.command(["forward", "--remove", f"tcp:{port}"])
                state["forwardRemoved"] = device.command(["forward", "--list"]) == forwards
            except BaseException:
                state["forwardCleanupError"] = "forward_removal_unverified"
        if state["exportClosed"]:
            try:
                device.release_history(nonce)
                state["leaseReleased"] = True
            except BaseException:
                state["cleanupError"] = "lease_retained"
        if claimed:
            try:
                device.identity()
                state["postApkIdentityVerified"] = True
                post_crash = device.shell("logcat -b crash -d -v brief")
                state["postCrashSha256"] = hashlib.sha256(post_crash.encode()).hexdigest()
                state["crashBufferUnchanged"] = crash == post_crash
                state["postExportPortsAbsent"] = not (device.ports() & lifecycle.ALL_PORTS)
            except BaseException:
                state["postflightError"] = "postflight_unverified"
        checks = ("exportClosed", "watchdogTerminal", "leaseReleased", "forwardRemoved",
                  "postApkIdentityVerified", "crashBufferUnchanged", "postExportPortsAbsent")
        if state["status"] == "complete" and not all(state.get(k) for k in checks):
            state["status"] = "incomplete"
        state["finishedUtc"] = datetime.now(timezone.utc).isoformat()
    return state


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-acknowledged", action="store_true")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.run_acknowledged:
        print(json.dumps({"mode": "offline_plan", "files": FILES, "ecuRequests": 0,
                          "oemRequests": 0, "fileByteLimit": wire.MAX_FILE,
                          "readSecondsLimit": wire.TOTAL_TIMEOUT, "watchdogSeconds": 30}, indent=2))
        return 0
    if args.output is None:
        parser.error("--output required")
    args.output.mkdir(mode=0o700, parents=True, exist_ok=False)
    result = run(HistoryDevice())
    result["readerSha256"] = lifecycle.sha(Path(__file__))
    result["dependencies"] = {str(Path(m.__file__).resolve().relative_to(ROOT)): lifecycle.sha(Path(m.__file__))
                              for m in (lifecycle, wire, inventory)}
    wire._save_new(args.output / "session.json", (json.dumps(result, indent=2) + "\n").encode())
    print(json.dumps({k: v for k, v in result.items() if k != "files"}, indent=2))
    return 0 if result["status"] == "complete" else 1


if __name__ == "__main__":
    sys.exit(main())
