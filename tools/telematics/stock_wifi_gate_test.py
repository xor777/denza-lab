#!/usr/bin/env python3
"""Preview or execute a stock-client Wi-Fi eligibility experiment.

Stateful: switches the stock APN policy and sends cloudmanager notify_nw(4),
the APN3-ready event, despite transport being Wi-Fi. Native registration, token
persistence, telemetry and inbound processing may follow automatically.
No execution is implied by the earlier manual single-report tests.
"""
import argparse
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import threading
import time

from wifi_notification_test import (
    event, now, properties, reader, run, save, snapshot, switch,
)

WINDOW = 75
GUARD_DELAY = 90


def restore(out, actor):
    with (out / "restore.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        if (out / "restored.json").exists():
            return
        current = properties()
        profile = current["persist.sys.byd.apn_type"]
        if profile not in ("double_apn", "triple_apn"):
            raise RuntimeError("Unexpected profile; stop and inspect")
        # -3 (ordinary Wi-Fi disconnect) does not clear the native gate.
        if profile == "double_apn":
            save(out, actor + "-notify-disconnected.json", {
                "time": now(), "result": run([
                    "shell", "service", "call", "cloudmanager", "1", "i32", "-5"])
            })
            time.sleep(1)
        if profile != "triple_apn" or current["persist.radio.net.lte.apn1.disable"] != "0":
            save(out, actor + "-restore-profile.json", switch("triple_apn"))
            time.sleep(3)
        final = snapshot(out, actor + "-restored-state")
        baseline = json.loads((out / "before.json").read_text())["properties"]
        difference = {k: [v, final["properties"][k]] for k, v in baseline.items()
                      if v != final["properties"][k]}
        p = final["properties"]
        ok = (p["persist.sys.byd.apn_type"] == "triple_apn"
              and p["persist.radio.net.lte.apn1.disable"] == "0"
              and final["wifi"]["validated_wifi"] and final["adb"]["stdout"] == "device"
              and "Parcel(00000000 00000000" in final["cloud"]["stdout"])
        save(out, actor + "-restoration-check.json", {
            "operational_restore": ok, "property_differences": difference,
            "persistent_registration_or_token_not_deleted": True,
            "native_memory_byte_for_byte_restoration_claimed": False,
        })
        if not ok:
            raise RuntimeError("Operational restoration NOT confirmed")
        save(out, "restored.json", {"time": now(), "actor": actor})
        event(out, "Original profile, disconnected stock TCP, Wi-Fi and ADB confirmed")


def guard(out, owner_pid=None, hold=False):
    end = None if hold else time.monotonic() + GUARD_DELAY
    while end is None or time.monotonic() < end:
        if (out / "restored.json").exists():
            return
        # A live owner handles explicit stop in its finally block. Do not race
        # it while it is still transitioning the profile/issuing the one event.
        if owner_pid is None and (out / "stop.requested").exists():
            break
        if owner_pid:
            try:
                os.kill(owner_pid, 0)
            except ProcessLookupError:
                break
        time.sleep(1 if end is None else min(1, max(0, end - time.monotonic())))
    restore(out, "owner-guard" if owner_pid else "deadline-guard")


def execute(out, hold=False):
    if out.exists():
        raise RuntimeError("Refusing to overwrite evidence")
    out.mkdir(parents=True)
    before = snapshot(out, "before")
    props = before["properties"]
    if not (props["persist.sys.byd.apn_type"] == "triple_apn"
            and props["persist.radio.net.lte.apn1.disable"] == "0"
            and not props["net.lte.apn1.ifname"] and not props["net.lte.apn3.ifname"]
            and before["wifi"]["validated_wifi"] and before["adb"]["stdout"] == "device"
            and all("Parcel(00000000 00000000" in before[k]["stdout"] for k in ("cloud", "mqtt"))):
        raise RuntimeError("Baseline differs; no change performed")
    pid = run(["shell", "pidof", "cloudmanager"])["stdout"]
    if not pid.isdigit():
        raise RuntimeError("Expected exactly one cloudmanager")
    logs = subprocess.Popen([
        "adb", "-s", "127.0.0.1:5555", "logcat", "-b", "main", "-b", "system",
        "-v", "threadtime", "--pid=" + pid, "-T", "1", "*:V"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace")
    thread = threading.Thread(target=reader, args=(logs, out / "native-redacted.txt"), daemon=True)
    thread.start()
    attempted = False
    try:
        with (out / "guard-output.txt").open("w") as target:
            guard_args = [sys.executable, str(Path(__file__).resolve()), "--guard", "--out", str(out),
                          "--owner-pid", str(os.getpid())]
            if hold:
                guard_args += ["--hold"]
            watchdog = subprocess.Popen(guard_args,
                stdout=target, stderr=subprocess.STDOUT, start_new_session=True)
        if watchdog.poll() is not None:
            raise RuntimeError("Rollback guard failed to start")
        started = time.monotonic()
        save(out, "timing.json", {"started": now(), "guard_pid": watchdog.pid,
                                  "owner_pid": os.getpid(), "mode": "until_owner_stop" if hold else "bounded",
                                  "window_seconds": None if hold else WINDOW,
                                  "guard_delay_seconds": None if hold else GUARD_DELAY})
        attempted = True
        if (out / "stop.requested").exists():
            return
        event(out, "One stock double_apn transition")
        save(out, "switch-double.json", switch("double_apn"))
        time.sleep(3)
        after = snapshot(out, "after-switch")
        if not (after["properties"]["persist.sys.byd.apn_type"] == "double_apn"
                and after["properties"]["persist.radio.net.lte.apn1.disable"] == "1"
                and after["wifi"]["validated_wifi"]):
            raise RuntimeError("Public profile or Wi-Fi not confirmed")
        if (out / "stop.requested").exists() or (out / "restored.json").exists():
            return
        event(out, "One synthetic APN3-ready event to native cloudmanager only; Wi-Fi transport")
        save(out, "notify-native-ready.json", {
            "time": now(), "result": run([
                "shell", "service", "call", "cloudmanager", "1", "i32", "4"])
        })
        schedule = iter((5, 15, 30, 50, WINDOW))
        next_observation = next(schedule)
        while True:
            while time.monotonic() < started + next_observation:
                if (out / "stop.requested").exists() or (out / "restored.json").exists():
                    break
                time.sleep(min(1, max(0, started + next_observation - time.monotonic())))
            if (out / "stop.requested").exists() or (out / "restored.json").exists():
                event(out, "Owner requested stop; restoring")
                break
            observation = snapshot(out, "latest" if hold else "observe-" + str(next_observation))
            if not observation["wifi"]["validated_wifi"] or observation["adb"]["stdout"] != "device":
                raise RuntimeError("Transport changed; end observation")
            if run(["shell", "pidof", "cloudmanager"])["stdout"] != pid:
                raise RuntimeError("Stock process changed; stop")
            if hold:
                next_observation = time.monotonic() - started + 30
            else:
                next_observation = next(schedule, None)
                if next_observation is None:
                    break
    finally:
        try:
            if attempted:
                restore(out, "main")
        finally:
            logs.terminate()
            try:
                logs.wait(timeout=5)
            except subprocess.TimeoutExpired:
                logs.kill()
                logs.wait(timeout=5)
            thread.join(timeout=3)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--hold", action="store_true", help="Continue until --stop; no timed rollback")
    parser.add_argument("--stop", action="store_true", help="Request restoration of an existing run")
    parser.add_argument("--guard", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--owner-pid", type=int, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.stop:
        if args.out is None or not (args.out / "timing.json").is_file():
            parser.error("--stop requires an existing run directory")
        (args.out / "stop.requested").touch()
        print(json.dumps({"stop_requested": True, "restoration_confirmed": (args.out / "restored.json").exists()}))
        return
    if not args.execute and not args.guard:
        print(json.dumps({"mode": "preview", "adb_used": False, "cloud_contacted": False,
            "plan": ["verify triple_apn, disconnected TCP/MQTT, validated Wi-Fi",
                     "start owner-process/stop guard; no deadline" if args.hold else "start independent 90-second rollback guard",
                     "stock double_apn transition", "one native notify_nw(4)",
                     "observe until explicit stop; native client owns subsequent exchange" if args.hold else "observe up to 75 seconds; native client owns subsequent exchange",
                     "notify_nw(-5) while double_apn; stock triple_apn restore",
                     "verify disconnected TCP and preserved Wi-Fi/ADB"],
            "effects": ["native client may register, persist token, send telemetry and process incoming commands",
                        "operational rollback does not delete a newly issued token or revert cloud registration"]}, indent=2))
        return
    if args.out is None:
        parser.error("--out is required for execution/guard")
    if args.guard:
        if args.owner_pid is not None and args.owner_pid < 2:
            parser.error("Invalid owner PID")
        guard(args.out.resolve(), args.owner_pid, args.hold)
    else:
        def interrupt(signum, _frame):
            raise KeyboardInterrupt("Signal " + str(signum))
        signal.signal(signal.SIGTERM, interrupt)
        execute(args.out.resolve(), args.hold)


if __name__ == "__main__":
    main()
