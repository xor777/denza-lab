#!/usr/bin/env python3
"""Host runner for the parked ADAS experiment: play each scenario on the car in order and read
ADAS's answers, to find by controlled experiment why NOA was offered from the stock feed at the
start position but not from our bridge.

It checks preconditions (the stock map absent and disabled, no valid Navigator live arm), installs
the player probe, and for each scenario pushes it, prompts the owner once in Russian (brake, P->D,
pull the stalk, photograph the cluster), launches one bounded run, pulls the result, and prints a
comparison table (the DNP/ICC/ACC timeline, noaMode, the MMTPathSelected status, PathMatch presence
and fields, alarm/notice codes). At the end it uninstalls the player it added and writes a receipt
of what it pushed and the before/after stock/arm state.

For a scenario that declares `naviSt` (H6) the runner itself drives the stock
SETTING_MPC_IFC_NAVI_ST_SET channel over `adb shell` (shell UID; device 1023 is out of the app
UID's reach, while the SOME/IP feed must stay in the player's app UID): read and record, write 2 at
guidance start just before the route (the stock's setNaviTypeAndInit(0) trigger), then after the
player's end sequence restore the read value and read it back - also on error, timeout and Ctrl-C.
An unreadable or unexpected value means no write and the scenario is NOT RUN.

Every ADB command is a fixed builder with an injected runner, bounded and deadlined; `plan` makes
zero device calls. Development and tests never touch a car - the test injects a fake adb. This tool
is written to work over `adb -s <serial>` (the car at 127.0.0.1:5555) but must not be run against
the car without the owner present and the stationary gate satisfied.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time

from . import feed
from .live_session import SessionError as _LiveSessionError, parcel_word

PACKAGE = "dev.denza.adasplayer.probe"
# SETTING_MPC_IFC_NAVI_ST_SET (0x4CA0004B) on BYDAUTO_DEVICE_SETTING, decimal as vehicle_control.py.
# The stock writes it through PlatformApiImpl.setNaviSt from NaviService.setNaviTypeAndInit (GPS
# navigation -> 2), called when BaseNaviPresenter's NaviService connects at guidance start, and
# writes 0 on onNaviStop/onDestroy. Device 1023 needs a BYDAUTO permission the app UID lacks, so the
# runner does it over `adb shell` (shell UID); the player (app UID, needed for SOME/IP) never does.
NAVI_ST_DEVICE = 1023
NAVI_ST_FID = 1285554251
NAVI_ST_VALUES = (0, 1, 2)


def navi_st_read_command():
    return f"service call autoservice 5 i32 {NAVI_ST_DEVICE} i32 {NAVI_ST_FID}"


def navi_st_write_command(value):
    return f"service call autoservice 6 i32 {NAVI_ST_DEVICE} i32 {NAVI_ST_FID} i32 {int(value)} null"
ACTIVITY = PACKAGE + "/.PlayerActivity"
STOCK = "com.byd.launchermap"
NAVIGATOR = "ru.yandex.yandexnavi"
ARM_FILE = "files/denza-adas-live-arm.json"
FILES = {"scenario": "files/scenario.json", "preconditions": "files/preconditions.json",
         "result": "files/player.jsonl", "once": "files/player-started.once"}
PRECONDITIONS_SCHEMA = "denza-adas-parked-preconditions-v1"
MAX_OUTPUT = 8 * 1024 * 1024


def _event_topic(service, ident):
    return (4 << 48) | (service << 32) | (service << 16) | ident


ANSWER_MESSAGES = {
    _event_topic(13, 0x8002): ("PilotStatus", "PilotStatusNotify"),
    _event_topic(13, 0x8003): ("Alarm", "PilotAlarmAndNoticeInfoNotify"),
    _event_topic(13, 0x8005): ("NoaMode", "NewBroadcastInfoNotify"),
    _event_topic(44, 0x8002): ("MMTPathSelected", "NaviPathUserSelectStsConfirmNotify"),
    _event_topic(44, 0x8001): ("PathMatch", "NavigationPathMatchStatusNotify"),
}


class SessionError(ValueError):
    pass


def validate_serial(serial):
    if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", serial) or serial.startswith("-"):
        raise SessionError("explicit safe ADB serial required")
    return serial


class Device:
    """Fixed command builders with an injected runner; no default target, bounded output/deadline."""

    def __init__(self, adb, serial, *, runner=subprocess.run, deadline_s=600):
        self.adb = str(adb)
        self.serial = validate_serial(serial)
        self.runner = runner
        self.deadline = time.monotonic() + deadline_s
        self.count = 0

    def _run(self, args, *, body=None):
        self.count += 1
        remaining = self.deadline - time.monotonic()
        if remaining <= 0 or self.count > 2000:
            raise SessionError("bounded ADB session budget exhausted")
        result = self.runner([self.adb, "-s", self.serial, *args], input=body,
                             capture_output=True, timeout=min(30, remaining), check=False)
        out = result.stdout if isinstance(result.stdout, (bytes, bytearray)) else (result.stdout or "").encode()
        err = result.stderr if isinstance(result.stderr, (bytes, bytearray)) else (result.stderr or "").encode()
        if len(out) > MAX_OUTPUT:
            raise SessionError("ADB output cap")
        if result.returncode:
            raise SessionError("ADB command failed: " + err.decode("utf-8", "replace")[:200])
        return out

    def shell(self, *tokens):
        return self._run(["shell", " ".join(shlex.quote(str(t)) for t in tokens)]).decode("utf-8", "replace")

    def run_as(self, package, script):
        if package not in (PACKAGE, NAVIGATOR):
            raise SessionError("run-as package allowlist")
        command = " ".join(shlex.quote(str(t)) for t in ("run-as", package, "sh", "-c", script))
        return self._run(["exec-out", command])

    def push_private(self, relpath, data):
        encoded = base64.b64encode(data).decode("ascii")
        script = (f"umask 077; printf %s {shlex.quote(encoded)} | base64 -d > {relpath}.tmp"
                  f" && mv {relpath}.tmp {relpath}")
        self.run_as(PACKAGE, script)
        if self.read_private(PACKAGE, relpath) != data:
            raise SessionError("private file readback mismatch")

    def read_private(self, package, relpath, *, cap=MAX_OUTPUT):
        script = f"if [ -f {relpath} ]; then head -c {cap} {relpath}; fi"
        return self.run_as(package, script)

    def rm_private(self, *relpaths):
        self.run_as(PACKAGE, "rm -f " + " ".join(relpaths))

    def install(self, apk):
        self._run(["install", "-r", "-g", str(Path(apk).resolve())])

    def uninstall(self):
        self._run(["uninstall", PACKAGE])

    def am_start_once(self):
        self.shell("am", "start", "-W", "-n", ACTIVITY, "--ez", "parkedOnce", "true")

    def stock_running(self):
        out = self.shell("ps", "-A", "-o", "PID,NAME")
        for line in out.splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and (parts[-1] == STOCK or parts[-1].startswith(STOCK + ":")):
                return True
        return False

    def stock_enabled_state(self):
        out = self.shell("dumpsys", "package", STOCK)
        matches = re.findall(r"^\s*User 0:.*?\benabled=([0-4])\b.*$", out, re.MULTILINE)
        if len(matches) != 1:
            raise SessionError("ambiguous/missing stock enabled state")
        return int(matches[0])

    def navigator_arm_valid(self, now_elapsed_ms):
        try:
            data = self.read_private(NAVIGATOR, ARM_FILE, cap=65536)
        except SessionError as error:
            raise SessionError("Navigator arm state unreadable") from error
        if not data:
            return False
        try:
            arm = json.loads(data.decode("utf-8"))
        except (ValueError, UnicodeError) as error:
            raise SessionError("Navigator arm state malformed") from error
        expires = arm.get("expiresAtElapsedMs")
        return isinstance(expires, int) and expires > now_elapsed_ms

    def navi_st_read(self):
        """The current NaviSt value; SessionError on any reply but status 0 with a known value."""
        reply = self.shell(*navi_st_read_command().split())
        try:
            value = parcel_word(reply)
        except _LiveSessionError as error:
            raise SessionError("NaviSt read reply not proven: " + str(error)) from error
        if value not in NAVI_ST_VALUES:
            raise SessionError("NaviSt read returned unexpected value " + str(value))
        return value

    def navi_st_write(self, value):
        reply = self.shell(*navi_st_write_command(value).split())
        try:
            parcel_word(reply)
        except _LiveSessionError as error:
            raise SessionError("NaviSt write reply not proven: " + str(error)) from error

    def elapsed_ms(self):
        parts = self.shell("cat", "/proc/uptime").split()
        if not parts or not re.fullmatch(r"[0-9]{1,12}(?:\.[0-9]{1,9})?", parts[0]):
            raise SessionError("elapsedRealtime source unavailable")
        return int(float(parts[0]) * 1000)


def gather_preconditions(device, *, listen_only=False):
    now = device.elapsed_ms()
    state = {"schema": PRECONDITIONS_SCHEMA,
             "stockRunning": device.stock_running(),
             "stockEnabledState": device.stock_enabled_state(),
             "armValid": device.navigator_arm_valid(now),
             "listenOnly": listen_only,
             "elapsedMs": now}
    state["safe"] = (not state["armValid"] and (listen_only or
                     (not state["stockRunning"] and state["stockEnabledState"] == 3)))
    return state


def decode_answers(rows):
    """Turn the player's recorded answers into a compact per-topic summary."""
    pilot, alarms = [], []
    noa_modes, selected, matches = [], [], []
    for row in rows:
        if row.get("kind") != "answer":
            continue
        topic = row["topic"]
        entry = ANSWER_MESSAGES.get(topic)
        if entry is None:
            continue
        name, message = entry
        try:
            fields = feed.flatten(message, base64.b64decode(row["base64"]))
        except (ValueError, KeyError):
            continue
        t = row.get("elapsedMs")
        if name == "PilotStatus":
            pilot.append({"t": t, "dnp": fields.get("dNPStatus", 0), "icc": fields.get("iCCStatus", 0),
                          "acc": fields.get("aCCStatus", 0), "takeover": bool(fields.get("takeoverStatus"))})
        elif name == "Alarm":
            alarms.append({"t": t, "reason": fields.get("pilotAlarmReason"), "notice": fields.get("pilotNotice")})
        elif name == "NoaMode":
            noa_modes.append({"t": t, "noaMode": fields.get("noaMode"), "notice": fields.get("notice")})
        elif name == "MMTPathSelected":
            selected.append({"t": t, "pathIDResp": fields.get("pathIDResp"), "statusCode": fields.get("statusCode")})
        elif name == "PathMatch":
            info = fields.get("routeInfoArray") or []
            matches.append({"t": t, "code": fields.get("code"), "routeInfo": info})
    return {"pilot": pilot, "alarms": alarms, "noaModes": noa_modes, "selected": selected, "pathMatch": matches}


def _runs(values):
    """Collapse a status timeline into runs of equal (dnp,icc,acc)."""
    out = []
    for p in values:
        key = (p["dnp"], p["icc"], p["acc"])
        if not out or out[-1]["state"] != key:
            out.append({"state": key, "from": p["t"], "to": p["t"], "n": 1})
        else:
            out[-1]["to"] = p["t"]
            out[-1]["n"] += 1
    return out


def comparison_table(scenario_id, summary, navi=None):
    lines = [f"=== {scenario_id} ==="]
    if navi is not None:
        if navi.get("notRun"):
            lines.append(f"  NOT RUN: NaviSt read failed ({navi.get('reason')}); no write, no play")
            return "\n".join(lines)
        lines.append(f"  NaviSt: before={navi.get('before')} written={navi.get('written')} "
                     f"restored={navi.get('restored')} after={navi.get('after')} "
                     f"verified={navi.get('restoreVerified')}")
    runs = _runs(summary["pilot"])
    if runs:
        lines.append("  DNP/ICC/ACC timeline:")
        for r in runs:
            lines.append(f"    {r['state']}  x{r['n']}  ({r['from']}..{r['to']} ms)")
        offered = any(r["state"][0] in (1, 2) for r in runs)
        lines.append(f"    NOA offered (DNP 1/2 seen): {offered}")
    else:
        lines.append("  DNP/ICC/ACC: no PilotStatus answers recorded")
    modes = sorted({m["noaMode"] for m in summary["noaModes"] if m["noaMode"] is not None})
    lines.append(f"  noaMode values: {modes or 'none'}")
    if summary["selected"]:
        s = summary["selected"][-1]
        lines.append(f"  MMTPathSelected: pathIDResp={s['pathIDResp']} statusCode={s['statusCode']}")
    else:
        lines.append("  MMTPathSelected: none")
    if summary["pathMatch"]:
        m = summary["pathMatch"][-1]
        lines.append(f"  PathMatch present: code={m['code']} routeInfo={m['routeInfo']}")
    else:
        lines.append("  PathMatch: none")
    codes = sorted({(a["reason"], a["notice"]) for a in summary["alarms"]})
    lines.append(f"  alarm(reason,notice): {codes or 'none'}")
    return "\n".join(lines)


def _pre_route_s(scenario):
    phase = (scenario.get("navStatus") or {}).get("preRoutePhase") or []
    return sum(float(step.get("holdS", 1)) for step in phase)


def run_scenario(device, scenario_path, preconditions, prompt, *, settle_s=5, sleep=time.sleep):
    scenario = json.loads(Path(scenario_path).read_text(encoding="utf-8"))
    scenario_id = scenario.get("id", "?")
    data = Path(scenario_path).read_bytes()
    navi = scenario.get("naviSt")
    if scenario.get("listenOnly") and navi is not None:
        raise SessionError("LISTEN cannot change NaviSt")
    navi_record = None
    if navi is not None:
        navi_record = {"device": NAVI_ST_DEVICE, "fid": NAVI_ST_FID, "target": int(navi["value"]),
                       "readCommand": navi_st_read_command(),
                       "writeCommand": navi_st_write_command(navi["value"])}
        try:
            navi_record["before"] = device.navi_st_read()
        except SessionError as error:
            # No proven value to restore: do not write, do not play, do not guess.
            navi_record.update(notRun=True, reason=str(error))
            return {"id": scenario_id, "pushedSha256": None, "rows": 0, "notRun": True,
                    "naviSt": navi_record, "summary": decode_answers([]), "refused": []}
    prompt((f"Сценарий {scenario_id}: только запись ответов ADAS штатной карте; "
            "собственный фид, ODD и конечные статусы не отправляются.") if scenario.get("listenOnly") else
           (f"Сценарий {scenario_id}: тормоз, P→D, потяните подрулевой рычаг дважды, "
            f"сфотографируйте приборку, затем продолжите."))
    duration = int(scenario.get("durationS", 120))
    total_s = min(duration + settle_s, 320)
    write_attempted = False
    try:
        device.rm_private(FILES["once"], FILES["result"])
        device.push_private(FILES["scenario"], data)
        device.push_private(FILES["preconditions"], (json.dumps(preconditions) + "\n").encode())
        device.am_start_once()
        waited = 0.0
        if navi_record is not None:
            # The stock writes NaviSt 2 at guidance start, the same trigger as status 101 and the
            # route publication; write at the end of the pre-route phase, just before the route.
            waited = _pre_route_s(scenario)
            sleep(waited)
            write_attempted = True
            device.navi_st_write(navi_record["target"])
            navi_record["written"] = navi_record["target"]
        sleep(max(0.0, total_s - waited))      # past the player's 110x3 s -> 100 end sequence
        result = device.read_private(PACKAGE, FILES["result"])
    except BaseException as error:             # error, timeout, KeyboardInterrupt: restore below
        error.parked_navi = navi_record
        raise
    finally:
        if write_attempted:
            restore = navi_record["before"]
            try:
                device.navi_st_write(restore)
                navi_record["restored"] = restore
                after = device.navi_st_read()
                navi_record["after"] = after
                navi_record["restoreVerified"] = after == restore
            except (SessionError, subprocess.SubprocessError, OSError) as error:
                navi_record["restoreVerified"] = False
                navi_record["restoreError"] = str(error)[:200]
    rows = [json.loads(line) for line in result.decode("utf-8", "replace").splitlines() if line.strip()]
    summary = decode_answers(rows)
    outcome = {"id": scenario_id, "pushedSha256": hashlib.sha256(data).hexdigest(),
               "rows": len(rows), "summary": summary,
               "refused": [r for r in rows if r.get("kind") == "note" and r.get("event") == "refused"]}
    if navi_record is not None:
        outcome["naviSt"] = navi_record
    return outcome


def run(device, apk, scenario_dir, order, prompt, *, settle_s=5, sleep=time.sleep):
    listen_only = bool(order) and all(
        (Path(scenario_dir) / f"scenario-{sid}.json").exists() and
        json.loads((Path(scenario_dir) / f"scenario-{sid}.json").read_text()).get("listenOnly") is True
        for sid in order)
    before = gather_preconditions(device, listen_only=listen_only)
    receipt = {"schema": "denza-adas-parked-session-v1", "serial": device.serial,
               "apkSha256": hashlib.sha256(Path(apk).read_bytes()).hexdigest(),
               "stockBefore": {"running": before["stockRunning"], "enabledState": before["stockEnabledState"]},
               "armBefore": before["armValid"], "scenarios": []}
    if not before["safe"]:
        receipt["aborted"] = "preconditions not safe: " + json.dumps(
            {k: before[k] for k in ("stockRunning", "stockEnabledState", "armValid")})
        return receipt
    device.install(apk)
    receipt["installed"] = True
    try:
        for scenario_id in order:
            path = Path(scenario_dir) / f"scenario-{scenario_id}.json"
            if not path.exists():
                receipt["scenarios"].append({"id": scenario_id, "skipped": "no scenario file"})
                continue
            try:
                outcome = run_scenario(device, path, before, prompt, settle_s=settle_s, sleep=sleep)
            except Exception as error:
                receipt["scenarios"].append({"id": scenario_id, "error": str(error)[:300],
                                             "naviSt": getattr(error, "parked_navi", None)})
                receipt["stoppedAt"] = scenario_id
                break
            print(comparison_table(scenario_id, outcome["summary"], outcome.get("naviSt")))
            entry = {"id": outcome["id"], "pushedSha256": outcome["pushedSha256"],
                     "rows": outcome["rows"], "refused": bool(outcome["refused"]),
                     "summary": outcome["summary"]}
            if "naviSt" in outcome:
                entry["naviSt"] = outcome["naviSt"]
            if outcome.get("notRun"):
                entry["notRun"] = True
            receipt["scenarios"].append(entry)
    finally:
        try:
            device.uninstall()
            receipt["uninstalled"] = True
        except SessionError as error:
            receipt["uninstallError"] = str(error)
        after = gather_preconditions(device, listen_only=listen_only)
        receipt["stockAfter"] = {"running": after["stockRunning"], "enabledState": after["stockEnabledState"]}
        receipt["armAfter"] = after["armValid"]
    return receipt


def _default_order():
    return ["H0", "H1", "H2a", "H2b", "H2c", "H2d", "H2e", "H2f", "H2g", "H3", "H4", "H5", "H6"]


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    plan = sub.add_parser("plan", help="print the session plan; no device calls")
    plan.add_argument("--scenario-dir", required=True, type=Path)
    plan.add_argument("--order", nargs="*", default=None)
    r = sub.add_parser("run", help="run the parked session on the car (owner present, stationary)")
    r.add_argument("--adb", default="/opt/homebrew/share/android-commandlinetools/platform-tools/adb")
    r.add_argument("--serial", required=True)
    r.add_argument("--apk", required=True, type=Path)
    r.add_argument("--scenario-dir", required=True, type=Path)
    r.add_argument("--out", required=True, type=Path)
    r.add_argument("--order", nargs="*", default=None)
    r.add_argument("--yes", action="store_true", help="auto-confirm each step instead of prompting")
    args = parser.parse_args(argv)
    order = args.order or _default_order()
    if args.command == "plan":
        steps, navi_steps, listen_steps = [], [], []
        for sid in order:
            path = args.scenario_dir / f"scenario-{sid}.json"
            if path.exists():
                steps.append(sid)
                scenario = json.loads(path.read_text(encoding="utf-8"))
                if scenario.get("listenOnly"):
                    listen_steps.append(sid)
                if scenario.get("naviSt") is not None:
                    navi_steps.append(sid)
        print(json.dumps({"schema": "denza-adas-parked-session-plan-v1", "deviceCalls": 0,
                          "order": steps, "scenarioDir": str(args.scenario_dir),
                          "listenOnly": listen_steps,
                          "naviStByHost": {"scenarios": navi_steps, "read": navi_st_read_command(),
                                           "write": navi_st_write_command(2),
                                           "order": ["read and record", "am start player (app UID)",
                                                     "write 2 at guidance start, before the route",
                                                     "after the 110x3 s -> 100 end: restore the read value",
                                                     "read again to verify; restore also on error/timeout/Ctrl-C",
                                                     "read fails or unexpected parcel: no write, scenario NOT RUN"]},
                          "notes": ["owner stationary; stock disabled and no live arm first (live_session.py)",
                                    "one bounded run per scenario; player always sends the stock end sequence",
                                    "SOME/IP stays in the app UID; NaviSt goes over adb shell (shell UID)"]},
                         indent=2, ensure_ascii=False))
        return 0
    validate_serial(args.serial)

    def prompt(message):
        if args.yes:
            print("[auto] " + message)
            return
        input(message + "\n[Enter когда готово] ")

    device = Device(args.adb, args.serial)
    receipt = run(device, args.apk, args.scenario_dir, order, prompt)
    args.out.write_text(json.dumps(receipt, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps({"out": str(args.out), "scenarios": [s["id"] for s in receipt["scenarios"] if "id" in s],
                      "aborted": receipt.get("aborted")}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
