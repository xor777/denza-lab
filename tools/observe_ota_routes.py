#!/usr/bin/env python3
"""Build/run a bounded two-tag logger; never start OTA or send ECU requests.

The remote helper owns restoration even after host loss. Only sanitized route
events reach disk. This is not a safety interlock for the stock OTA workflow.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import shlex
import shutil
import signal
import socket
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools/vehicle-diagnostics/OtaRouteObserver.java"
RESEARCH = ROOT / "research/vehicle-diagnostics"
sys.path.insert(0, str(RESEARCH))
from filter_ota_route_stream import RouteLogFilter, FilterLimitError  # noqa: E402
from parse_ota_route_logs import parse_bytes  # noqa: E402

BASE = "/data/local/tmp/denza-ota-route-observer"
KEYS = ("log.tag.OTA_GCY_UPGRADE_DiagnosePresenter", "log.tag.OTA_GCY_UPGRADE_CanDiagnostic")
CLASS = "dev.denza.diagnostics.probe.OtaRouteObserver"
SERIALS = ("127.0.0.1:5555", "emulator-5580")
STATUSES = {"STARTING", "OBSERVING", "RESTORING", "STOPPED", "RESTORE_FAILED"}
JAVA = Path("/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home/bin")
SDK = Path("/opt/homebrew/share/android-commandlinetools")


class ObserverError(Exception):
    """Fixed reason codes only; never include raw tool output."""


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def save_json(path, data):
    with os.fdopen(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def run(command, *, seconds=10, input_data=None):
    try:
        result = subprocess.run(command, input=input_data, stdout=subprocess.PIPE,
                                stderr=subprocess.DEVNULL, timeout=seconds, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise ObserverError("command_unavailable_or_timed_out") from None
    if result.returncode:
        raise ObserverError("command_failed")
    return result.stdout


def build(destination):
    source_sha = digest(SOURCE)
    destination.mkdir(mode=0o700, parents=False, exist_ok=False)
    classes = destination / "classes"
    classes.mkdir(mode=0o700)
    run([str(JAVA / "javac"), "--release", "8", "-d", str(classes), str(SOURCE)], seconds=30)
    run([str(SDK / "build-tools/35.0.0/d8"), "--min-api", "28", "--output", str(destination),
         *map(str, sorted(classes.rglob("*.class")))], seconds=30)
    if digest(SOURCE) != source_sha:
        raise ObserverError("source_changed_during_build")
    manifest = {"schemaVersion": 1, "sourceSha256": source_sha,
                "dexSha256": digest(destination / "classes.dex"), "mainClass": CLASS}
    save_json(destination / "build.json", manifest)
    return manifest


def validate_build(directory):
    manifest = json.loads((directory / "build.json").read_text())
    if manifest != {"schemaVersion": 1, "sourceSha256": digest(SOURCE),
                    "dexSha256": digest(directory / "classes.dex"), "mainClass": CLASS}:
        raise ObserverError("stale_or_mismatched_build")
    return manifest


def validate_state(raw, nonce):
    if len(raw) > 8192:
        raise ObserverError("invalid_state")
    try:
        state = json.loads(raw)
        if state.get("nonce") != nonce:
            return None  # Another session's report is never treated as ours.
        valid = (
            type(state["schemaVersion"]) is int and state["schemaVersion"] == 1
            and state["status"] in STATUSES
            and type(state["pid"]) is int and state["pid"] > 0
            and type(state["durationSeconds"]) is int and 1 <= state["durationSeconds"] <= 350
            and type(state["elapsedMs"]) is int and state["elapsedMs"] >= 0
            and isinstance(state["reason"], str) and re.fullmatch(r"[a-z_]{1,64}", state["reason"])
            and type(state["restoredVerified"]) is bool and type(state["childExited"]) is bool
            and isinstance(state["originals"], dict) and set(state["originals"]) == set(KEYS)
            and all(isinstance(v, str) and len(v) <= 91 for v in state["originals"].values())
            and set(state) == {"schemaVersion", "nonce", "pid", "durationSeconds", "status", "reason",
                               "elapsedMs", "originals", "restoredVerified", "childExited"}
        )
        if not valid:
            raise ValueError()
        if state["status"] == "STOPPED":
            if not state["restoredVerified"] or not state["childExited"]:
                raise ValueError()
    except (ValueError, KeyError, TypeError, AttributeError):
        raise ObserverError("invalid_state") from None
    return state


class Adb:
    def __init__(self, serial):
        if serial not in SERIALS:
            raise ObserverError("unqualified_serial")
        # Refuse a missing server; this tool does not intentionally start one.
        try:
            with socket.create_connection(("127.0.0.1", 5037), timeout=2):
                pass
        except OSError:
            raise ObserverError("existing_adb_server_required") from None
        self.command = [shutil.which("adb") or str(SDK / "platform-tools/adb"), "-s", serial]
        if self.call("get-state").strip() != b"device" or self.shell("id", "-u").strip() != b"2000":
            raise ObserverError("existing_shell_uid_transport_required")

    def call(self, *args, input_data=None):
        return run(self.command + list(args), input_data=input_data)

    def shell(self, *args):
        return self.call("shell", "-T", shlex.join(args))

    def state(self, nonce):
        return validate_state(self.shell("cat", BASE + "/state.json"), nonce)

    def stop(self, nonce):
        state = self.state(nonce)
        if state and state["status"] not in {"STOPPED", "RESTORE_FAILED"}:
            # The helper ignores a nonce that does not match its own session.
            self.call("shell", "-T", "umask 077; cat > " + shlex.quote(BASE + "/stop.request"),
                      input_data=(nonce + "\n").encode("ascii"))


def launch_command(remote, duration, nonce):
    if not re.fullmatch(r"[0-9a-f]{32}", nonce) or not 1 <= duration <= 350:
        raise ObserverError("invalid_launch_arguments")
    return "CLASSPATH=" + shlex.quote(remote) + " " + shlex.join([
        "/system/bin/nohup", "/system/bin/app_process", "/system/bin", CLASS,
        "--duration-seconds", str(duration), "--nonce", nonce]) + " </dev/null"


def observe(args):
    manifest = validate_build(args.build_dir)
    if not 1 <= args.duration <= 350 or (args.stop_after is not None and
            (args.serial != "emulator-5580" or not 0 < args.stop_after < args.duration)):
        raise ObserverError("invalid_observation_bounds")
    adb = Adb(args.serial)
    args.output_dir.mkdir(mode=0o700, parents=False, exist_ok=False)
    nonce = secrets.token_hex(16)
    remote = "/data/local/tmp/denza-ota-observer-" + nonce + ".dex"
    record = {"schemaVersion": 1, "nonce": nonce, "serial": args.serial, "build": manifest,
              "durationSeconds": args.duration, "observerStarted": False, "otaTriggered": False,
              "deviceWatchdogQualifiedOnThisRun": False, "cleanupVerified": False,
              "eligibleForProductAllowlist": False, "error": None,
              "stagedDexStatus": "not_staged", "stagedDexRemoved": False,
              "filterSha256": digest(RESEARCH / "filter_ota_route_stream.py"),
              "parserSha256": digest(RESEARCH / "parse_ota_route_logs.py")}
    save_json(args.output_dir / "launch.json", record)
    process = None
    push_completed = False
    state = None
    output = os.fdopen(os.open(args.output_dir / "events.log", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w")
    filtered = RouteLogFilter(output.write)
    selector = selectors.DefaultSelector()
    try:
        record["stagedDexStatus"] = "push_attempted"
        adb.call("push", str(args.build_dir / "classes.dex"), remote)
        push_completed = True
        record["stagedDexStatus"] = "staged"
        actual = adb.shell("sha256sum", remote).decode("ascii").split()[0]
        if actual != manifest["dexSha256"]:
            raise ObserverError("staged_dex_mismatch")
        process = subprocess.Popen(adb.command + ["shell", "-T", launch_command(remote, args.duration, nonce)],
                                   stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        os.set_blocking(process.stdout.fileno(), False)
        selector.register(process.stdout, selectors.EVENT_READ)
        began = time.monotonic()
        next_state = began
        stop_sent = False
        stream_open = True
        while time.monotonic() - began < args.duration + 25:
            for key, _ in selector.select(0.15):
                chunk = os.read(key.fd, 4096)
                if chunk:
                    filtered.feed(chunk)
                    output.flush()
                else:
                    selector.unregister(key.fileobj)
                    stream_open = False
            now = time.monotonic()
            if now >= next_state:
                try:
                    state = adb.state(nonce)
                except ObserverError:
                    if now - began > 10:
                        raise
                if state and state["status"] == "OBSERVING" and not record["observerStarted"]:
                    record["observerStarted"] = True
                    save_json(args.output_dir / "ready.json", state)
                    print(json.dumps({"status": "OBSERVING", "otaTriggered": False}), flush=True)
                next_state = now + (2 if not record["observerStarted"] else 5)
            if args.stop_after is not None and now - began >= args.stop_after and not stop_sent:
                adb.stop(nonce)
                stop_sent = True
            if process.poll() is not None and not stream_open:
                break
        else:
            raise ObserverError("host_deadline")
        record["remoteExitCode"] = process.wait(timeout=2)
        if record["remoteExitCode"] != 0:
            record["error"] = "remote_refused_or_failed"
    except (ObserverError, FilterLimitError) as exc:
        record["error"] = str(exc)
    except KeyboardInterrupt:
        record["error"] = "host_interrupted"
    except (OSError, ValueError, subprocess.TimeoutExpired):
        record["error"] = "host_io_failed"
    finally:
        # Do not kill the helper on uncertainty. Its own deadline restores tags.
        if process is not None:
            try:
                adb.stop(nonce)
                end = time.monotonic() + 12
                while time.monotonic() < end:
                    state = adb.state(nonce)
                    if state and state["status"] in {"STOPPED", "RESTORE_FAILED"}:
                        break
                    time.sleep(0.2)
                if state:
                    record["deviceState"] = state
                    verified = state["status"] == "STOPPED" and state["restoredVerified"] and state["childExited"]
                    if verified:
                        verified = all(adb.shell("getprop", key).decode().rstrip("\r\n") == state["originals"][key] for key in KEYS)
                    record["cleanupVerified"] = verified
            except (ObserverError, UnicodeError):
                record["cleanupReadbackError"] = "device_cleanup_not_verified"
            if process.poll() is None:
                process.terminate()  # This invocation's host adb client, never server or other clients.
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
            process.stdout.close()
        selector.close()
        if not filtered.failed:
            filtered.mark_gap("capture_cutoff")
        filtered.finish()
        output.close()
        report = filtered.report()
        report.update(offlineOnly=False, observerStarted=record["observerStarted"])
        save_json(args.output_dir / "filter-report.json", report)
        save_json(args.output_dir / "routes.json", parse_bytes((args.output_dir / "events.log").read_bytes()))
        if record["cleanupVerified"] or (process is None and push_completed):
            try:
                adb.shell("rm", "-f", remote)  # Unique staged dex owned by this invocation only.
                record["stagedDexRemoved"] = True
                record["stagedDexStatus"] = "removed"
            except ObserverError:
                record["stagedDexStatus"] = "pending_cleanup"
        elif record["stagedDexStatus"] != "not_staged":
            record["stagedDexStatus"] = "pending_cleanup"
        save_json(args.output_dir / "run.json", record)
    print(json.dumps({"status": "STOPPED" if record["cleanupVerified"] else "UNVERIFIED",
                      "cleanupVerified": record["cleanupVerified"], "error": record["error"],
                      "acceptedRecords": report["acceptedRecords"]}), flush=True)
    return 0 if record["cleanupVerified"] and record["error"] is None else 2


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    make = sub.add_parser("build", help="Compile locally; no device access")
    make.add_argument("--output-dir", type=Path, required=True)
    collect = sub.add_parser("observe", help="Temporarily enable only two tags; no OTA trigger")
    collect.add_argument("--serial", choices=SERIALS, required=True)
    collect.add_argument("--build-dir", type=Path, required=True)
    collect.add_argument("--output-dir", type=Path, required=True)
    collect.add_argument("--duration", type=int, default=30)
    collect.add_argument("--stop-after", type=float, help="Emulator lifecycle test only")
    args = parser.parse_args(argv)
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt()))
    try:
        if args.command == "build":
            print(json.dumps(build(args.output_dir)))
            return 0
        return observe(args)
    except (ObserverError, OSError, ValueError):
        print("observer_setup_failed", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
