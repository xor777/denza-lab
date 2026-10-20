#!/usr/bin/env python3
"""Prepare, or explicitly run, one separately authorized metadata-only session.

Default is an entirely local plan. This is NOT permission to execute a session.
No ADB server/tunnel setup, forwards, APK installs, stock OTA calls or ECU calls.
The reviewed Java helper keeps all four private inputs on the device in RAM.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import selectors
import shlex
import socket
import subprocess
import time

HERE = Path(__file__).resolve().parent
ADB = ("adb", "-s", "127.0.0.1:5555")
LEASE = "/data/local/tmp/denza-ecu-map-metadata"
NICE_NAME = "denza-ecu-map"
PORTS = {46320, 46321, 46322}
ALL_PORTS = PORTS | {5320, 5321, 5322}
RECEIVER = "com.byd.bydlogtool/.LogReceiver"
STOP = f"am broadcast --user 0 -n {RECEIVER} -a action.system.STOP_SOCKET_SERVER"
START = (f"am broadcast --user 0 -n {RECEIVER} -a action.system.START_SOCKET_SERVER "
         "--ei zmq_router_port 46320 --ei file_transfer_port_user0 46321 "
         "--ei file_transfer_port_user10 46322")
APKS = {
    "com.byd.otaupdate": (
        "/system/app/OTAUpdatePlatformizedUI/OTAUpdatePlatformizedUI.apk",
        "a866917a5466cc72c9fd0fda9b2d6467654529478dd1c6a5c08c3ec5d14c3752"),
    "com.byd.bydlogtool": (
        "/system/app/BydLogTool/BydLogTool.apk",
        "589ec5b9555a0b64354aeee9393eb18ab9ca984ef3f0c6148d7a110eacbd97d5"),
}
ECU_FIELDS = ("ecuId", "ecuDiaId", "ecuReprogramType", "ecuChannel", "networkNumber",
              "logicAddress", "ecuDId", "ecuHardDid", "ecuConfigDid", "seq")
MAX_LINE = 4 * 1024 * 1024  # 2 MiB response plus up to 2048 derived route objects.
HELPER_STAGES = frozenset(("start", "input_cert", "input_key", "input_prefs", "input_version",
                          "input_validate", "trust", "go_wait", "https", "projection"))
# Explicit static Java Failure codes. Unknown strings are never reflected.
HELPER_CODES = frozenset("""
operation_failed runtime_guard arguments deadline identity_encoding identity_algorithm
identity_usage identity_pair identity_expired identity_not_yet_valid
certificate_invalid key_encoding tls_handshake io_timeout token_missing token_invalid uuid_invalid
version_invalid trust_invalid prefs_declaration prefs_invalid prefs_missing input_index
missing_input0 missing_input1 missing_input2 missing_input3 input_access_denied
transfer_header transfer_metadata transfer_name transfer_size transfer_md5 transfer_trailing
truncated go_invalid go_eof go_timeout tls_protocol http_auth http_status http_truncated
http_line http_headers http_headers_limit http_header_syntax http_header_duplicate
http_content_type http_encoding http_framing http_length http_chunk
http_trailer http_trailing response_length application_auth application_registration
application_status ecu_list invalid_ecu_projection privacy_projection json_object utf8
json_trailing json_truncated json_limit json_key json_duplicate json_colon json_separator
json_number json_literal json_string json_escape json_surrogate
""".split())


class Refused(Exception):
    """Only fixed, non-sensitive codes may be passed here."""


class HelperFailure(Refused):
    def __init__(self, stage, code):
        super().__init__("helper_" + code)
        self.stage, self.code = stage, code


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def unique_pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise Refused("duplicate_json_key")
        result[key] = value
    return result


def parse_message(raw):
    if len(raw) > MAX_LINE:
        raise Refused("helper_output_too_large")
    try:
        value = json.loads(raw, object_pairs_hook=unique_pairs,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    except (ValueError, UnicodeError, RecursionError):
        raise Refused("helper_output_invalid") from None
    if type(value) is not dict:
        raise Refused("helper_output_invalid")
    # Never propagate helper error text or an unknown object to stdout/disk.
    if value.get("state") == "ERROR":
        stage, code = value.get("stage"), value.get("code")
        if (set(value) == {"state", "stage", "code"} and type(stage) is str
                and type(code) is str and stage in HELPER_STAGES and code in HELPER_CODES):
            raise HelperFailure(stage, code)
        raise Refused("helper_reported_error")
    return value


def validate_result(value):
    if (set(value) != {"state", "profile", "evidence", "eligibleForProductAllowlist", "ecus"}
            or value["state"] != "RESULT" or value["profile"] is not None
            or value["evidence"] != "server_candidate_only"
            or value["eligibleForProductAllowlist"] is not False
            or type(value["ecus"]) is not list or not 1 <= len(value["ecus"]) <= 2048):
        raise Refused("invalid_result_schema")
    seen = set()
    for row in value["ecus"]:
        if type(row) is not dict or set(row) - set(ECU_FIELDS) - {
                "route", "evidence", "eligibleForProductAllowlist"}:
            raise Refused("invalid_ecu_schema")
        if (row.get("evidence") != "server_candidate_only"
                or row.get("eligibleForProductAllowlist") is not False
                or type(row.get("ecuId")) is not str
                or not re.fullmatch(r"[A-Za-z0-9_ -]{1,128}", row["ecuId"])
                or not row["ecuId"].strip()):
            raise Refused("invalid_ecu_schema")
        if row["ecuId"] in seen:
            raise Refused("duplicate_ecu_identity")
        seen.add(row["ecuId"])
        for field in ECU_FIELDS:
            item = row.get(field)
            pattern = r"[A-Za-z0-9_ -]{1,128}" if field == "ecuId" else r"[A-Za-z0-9_./:-]{0,128}"
            if item is not None and (type(item) is not str or not re.fullmatch(pattern, item)):
                raise Refused("invalid_ecu_field")
        pair = re.fullmatch(r"([0-9a-fA-F]{1,4})/([0-9a-fA-F]{1,4})", row.get("ecuDiaId") or "")
        if not pair and row.get("ecuDiaId") not in (None, ""):
            raise Refused("invalid_ecu_route")
        left, right = (int(x, 16) for x in pair.groups()) if pair else (None, None)
        if row.get("ecuReprogramType") in ("can", "canfd"):
            if pair is None:
                raise Refused("invalid_ecu_route")
            expected = {"kind": "can", "requestId": left, "responseId": right, "physicalBus": None}
        elif row.get("ecuReprogramType") in ("ethernet", "doip"):
            logical = row.get("logicAddress")
            if (logical and not re.fullmatch(r"[0-9a-fA-F]{1,4}", logical)) or (not logical and pair is None):
                raise Refused("invalid_ecu_route")
            expected = {"kind": "ethernet", "pairTarget": left, "pairClient": right,
                        "target": int(logical, 16) if logical else left}
        else:
            raise Refused("invalid_ecu_route")
        route = row.get("route")
        if (type(route) is not dict or route != expected
                or any(type(route[key]) is not type(item) for key, item in expected.items())):
            raise Refused("invalid_ecu_route")
    return value


def listener_ports(raw):
    if raw.count("local_address") != 2:
        raise Refused("socket_inventory_incomplete")
    result = set()
    try:
        for line in raw.splitlines():
            parts = line.split()
            if len(parts) > 3 and parts[3] == "0A":
                result.add(int(parts[1].rsplit(":", 1)[1], 16))
    except (ValueError, IndexError):
        raise Refused("socket_inventory_invalid") from None
    return result


def scripts(nonce, pid):
    """Host and watchdog share one serial START/STOP transition and owner token.

    At 30 seconds the watchdog begins a persistent STOP transition. A pending
    START must finish before its compensating STOP. An unresponsive receiver
    cannot be forcibly bounded without killing the existing app. Unverified
    closure retains the lease/watchdog and never permits GO. No delayed STOP
    can follow a terminal watchdog acknowledgement.
    """
    if not re.fullmatch(r"[0-9a-f]{32}", nonce) or not re.fullmatch(r"[1-9][0-9]*", pid):
        raise Refused("invalid_owner")
    common = f'''#!/system/bin/sh
umask 077
cd {LEASE} || exit 10
[ "$(cat nonce 2>/dev/null)" = "{nonce}" ] || exit 11
owner_matches() {{
  [ "$(pidof com.byd.bydlogtool)" = "{pid}" ] || return 1
  owner_stat=$(cat /proc/{pid}/stat 2>/dev/null) || return 1
  owner_fields=${{owner_stat##*) }}
  set -- $owner_fields
  [ "$#" -ge 20 ] && [ "${{20}}" = "$(cat bydlog.start)" ]
}}
'''
    transition = common + f'''
mkdir transition.lock 2>/dev/null || exit 12
trap 'rmdir transition.lock' EXIT
owner_matches || exit 13
phase=$(cat phase) || exit 14
case "$1:$phase" in
  start:prepared)
    [ "$(cat watchdog 2>/dev/null)" = armed ] || exit 15
    [ ! -e deadline ] || exit 19
    echo active > phase
    {START} >/dev/null 2>&1 || exit 16
    if [ -e deadline ]; then echo pending_stop > phase; exit 19; fi
    ;;
  stop:active|stop:pending_stop)
    {STOP} >/dev/null 2>&1 || exit 17
    echo stopped > phase
    ;;
  stop:prepared) echo stopped > phase ;;
  stop:stopped) ;;
  *) exit 18 ;;
esac
echo transitioned
'''
    watchdog = common + '''
echo armed > watchdog
n=0
while [ "$n" -lt 30 ]; do
  if [ -e cancel ]; then
    [ "$(cat phase)" = stopped ] || exit 20
    echo cancelled > watchdog
    exit 0
  fi
  sleep 1
  n=$((n+1))
done
# Inhibit any START which has not entered the serialized transition yet.
# A START already inside am may still activate late. Never abandon its lease
# or retire this compensator while it can do so. No force-stop of the app.
touch deadline
echo compensating > watchdog
while true; do
  if sh transition.sh stop >/dev/null 2>&1; then
    echo deadline_stopped > watchdog
    exit 0
  else
    result=$?
    if [ "$result" -eq 13 ] || [ "$result" -eq 11 ]; then
      # Never chase a restarted app or a reused PID with a global STOP.
      echo owner_lost > watchdog
      exit 21
    fi
  fi
  sleep 1
done
'''
    return transition, watchdog


class Helper:
    def __init__(self, proc):
        self.proc = proc
        self.buffer = bytearray()
        self.selector = selectors.DefaultSelector()
        self.selector.register(proc.stdout, selectors.EVENT_READ)

    def message(self, timeout):
        deadline = time.monotonic() + timeout
        while True:
            if b"\n" in self.buffer:
                raw, _, tail = self.buffer.partition(b"\n")
                self.buffer[:] = tail
                return parse_message(raw)
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not self.selector.select(remaining):
                raise Refused("helper_deadline")
            chunk = os.read(self.proc.stdout.fileno(), 8192)
            if not chunk:
                raise Refused("helper_ended_without_result")
            self.buffer.extend(chunk)
            if len(self.buffer) > MAX_LINE:
                raise Refused("helper_output_too_large")

    def go(self, nonce):
        # Only this small approval token crosses stdin; never credentials.
        try:
            self.proc.stdin.write(f"GO {nonce}\n".encode("ascii"))
            self.proc.stdin.flush()
            self.proc.stdin.close()
        except (OSError, ValueError):
            raise Refused("helper_input_closed") from None

    def finish(self):
        try:
            self.proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            raise Refused("helper_exit_unverified") from None
        if self.proc.returncode != 0 or self.buffer or self.proc.stdout.read(MAX_LINE + 1):
            raise Refused("helper_trailing_output")

    def close(self):
        try:
            if not self.proc.stdin.closed:
                self.proc.stdin.close()
        finally:
            self.selector.close()
            if self.proc.poll() is None:
                self.proc.terminate()  # Owned host adb child only; device has hard deadline.
                try:
                    self.proc.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    self.proc.kill()
                    self.proc.wait(timeout=2)


class Device:
    # Other BydLog readers also claim this directory with plain mkdir. Never
    # reuse it: closed ports alone do not mean a different session has retired.
    # This gate cannot validate another reader's eventual cleanup policy.
    EXPORT_DIRECTORY = "/data/local/tmp/logtool"

    def existing_adb_server(self):
        # adb clients auto-start a missing server. Refuse that normal case
        # before every invocation; never run start-server/connect/reconnect.
        try:
            with socket.create_connection(("127.0.0.1", 5037), timeout=1):
                pass
        except OSError:
            raise Refused("existing_adb_server_unavailable") from None

    def command(self, args, timeout=4):
        self.existing_adb_server()
        try:
            value = subprocess.run([*ADB, *args], capture_output=True, timeout=timeout)
        except (OSError, subprocess.TimeoutExpired):
            raise Refused("adb_unavailable_or_deadline") from None
        if value.returncode or len(value.stdout) > MAX_LINE:
            raise Refused("adb_command_failed")
        try:
            return value.stdout.decode("utf-8", "strict").strip()
        except UnicodeError:
            raise Refused("adb_output_invalid") from None

    def shell(self, command, timeout=4):
        return self.command(["shell", command], timeout)

    def identity(self):
        for package, (path, digest) in APKS.items():
            if self.shell(f"pm path {package}") != "package:" + path:
                raise Refused("apk_path_mismatch")
            if self.shell(f"sha256sum {path}").split() != [digest, path]:
                raise Refused("apk_hash_mismatch")

    def ports(self):
        return listener_ports(self.shell("cat /proc/net/tcp /proc/net/tcp6"))

    def idle_threads(self, pid):
        # Read only this package's thread names. Inaccessible inventory refuses.
        names = self.shell(f"for f in /proc/{pid}/task/*/comm; do cat \"$f\" || exit 1; done", timeout=1)
        if not names:
            raise Refused("thread_inventory_unavailable")
        if any(n.startswith(("Zmq-", "FileTransfer")) for n in names.splitlines()):
            raise Refused("export_threads_present")

    def process_start(self, pid):
        value = self.shell(f"cat /proc/{pid}/stat")
        try:
            # comm (field 2) may contain spaces/parentheses; starttime is field 22.
            start = value.rsplit(") ", 1)[1].split()[19]
        except (IndexError, ValueError):
            raise Refused("process_identity_unavailable") from None
        if not start.isdigit():
            raise Refused("process_identity_unavailable")
        return start

    def preflight(self):
        if self.command(["get-state"]) != "device":
            raise Refused("existing_transport_unavailable")
        if self.shell("id -u") != "2000":
            raise Refused("unexpected_shell_identity")
        if self.shell("getprop persist.sys.AutoType; getprop sys.car.protocol") != "170\nCANFD":
            raise Refused("vehicle_profile_mismatch")
        self.identity()
        self.shell(f"test ! -e {LEASE} && test ! -L {LEASE}")
        if self.shell(f"if [ -e {self.EXPORT_DIRECTORY} ] || [ -L {self.EXPORT_DIRECTORY} ]; "
                      "then echo occupied; else echo absent; fi") != "absent":
            raise Refused("shared_export_directory_occupied")
        if self.ports() & ALL_PORTS:
            raise Refused("export_listener_present")
        if self.shell(f"pidof {NICE_NAME} || true"):
            raise Refused("helper_already_present")
        pid = self.shell("pidof com.byd.bydlogtool")
        if not re.fullmatch(r"[1-9][0-9]*", pid):
            raise Refused("bydlog_pid_unavailable")
        self.owner_start = self.process_start(pid)
        self.idle_threads(pid)
        return pid

    def claim(self, nonce, pid):
        self.shell(f"umask 077; mkdir {LEASE} && printf '%s\\n' {nonce} > {LEASE}/nonce "
                   f"&& printf '%s\\n' {pid} > {LEASE}/bydlog.pid "
                   f"&& printf '%s\\n' {self.owner_start} > {LEASE}/bydlog.start && echo prepared > {LEASE}/phase")

    def stage(self, artifact, digest, nonce, pid):
        self.command(["push", str(artifact), LEASE + "/classes.dex"], timeout=8)
        if self.shell(f"sha256sum {LEASE}/classes.dex").split() != [digest, LEASE + "/classes.dex"]:
            raise Refused("staged_artifact_mismatch")
        transition, watchdog = scripts(nonce, pid)
        for name, content in (("transition.sh", transition), ("watchdog.sh", watchdog)):
            self.shell(f"umask 077; printf %s {shlex.quote(content)} > {LEASE}/{name}")
        # Atomic contention check after preflight. A failure cannot arm or
        # STOP another reader; our own lease stays for explicit investigation.
        state = self.shell(f"mkdir {self.EXPORT_DIRECTORY} && echo created")
        if state != "created":
            raise Refused("shared_export_directory_claim_unverified")
        return True

    def arm(self):
        self.shell(f"nohup sh {LEASE}/watchdog.sh </dev/null >/dev/null 2>&1 &")
        self.wait_watchdog({"armed"}, 2)

    def start(self):
        self.shell(f"sh {LEASE}/transition.sh start", timeout=4)
        deadline = time.monotonic() + 2
        while self.ports() & ALL_PORTS != PORTS:
            if time.monotonic() >= deadline:
                raise Refused("listeners_not_ready")
            time.sleep(0.1)

    def launch_helper(self, nonce):
        cmd = (f"CLASSPATH={LEASE}/classes.dex app_process /system/bin "
               f"--nice-name={NICE_NAME} EcuMapMetadataProbe --nonce {nonce}")
        self.existing_adb_server()
        return Helper(subprocess.Popen([*ADB, "shell", "-T", cmd], stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL))

    def wait_watchdog(self, acceptable, timeout):
        deadline = time.monotonic() + timeout
        while True:
            status = self.shell(f"cat {LEASE}/watchdog 2>/dev/null || true", timeout=1)
            if status in acceptable:
                return status
            if status == "owner_lost" or time.monotonic() >= deadline:
                raise Refused("watchdog_unverified")
            time.sleep(0.1)

    def close_export(self, pid):
        # No cancellation until STOP and listener/thread closure are confirmed.
        self.shell(f"sh {LEASE}/transition.sh stop", timeout=4)
        deadline = time.monotonic() + 2
        while self.ports() & ALL_PORTS:
            if time.monotonic() >= deadline:
                raise Refused("listeners_still_present")
            time.sleep(0.1)
        if self.shell("pidof com.byd.bydlogtool", timeout=1) != pid:
            raise Refused("bydlog_pid_changed")
        if self.process_start(pid) != self.owner_start:
            raise Refused("bydlog_process_changed")
        self.idle_threads(pid)
        self.shell(f"test \"$(cat {LEASE}/phase)\" = stopped && touch {LEASE}/cancel", timeout=1)
        self.wait_watchdog({"cancelled", "deadline_stopped"}, 2)
        # Terminal ack precedes GO and lease release, including at the deadline.
        if self.ports() & ALL_PORTS:
            raise Refused("listeners_reappeared")

    def release(self, nonce, made_dir):
        if self.shell(f"pidof {NICE_NAME} || true"):
            raise Refused("helper_still_present")
        self.shell(f"test \"$(cat {LEASE}/nonce)\" = {nonce} && "
                   f"test \"$(cat {LEASE}/phase)\" = stopped && "
                   f"case \"$(cat {LEASE}/watchdog)\" in cancelled|deadline_stopped) ;; *) exit 1;; esac && "
                   f"rm -f {LEASE}/deadline && rm {LEASE}/classes.dex {LEASE}/transition.sh {LEASE}/watchdog.sh "
                   f"{LEASE}/bydlog.pid {LEASE}/bydlog.start {LEASE}/phase {LEASE}/watchdog "
                   f"{LEASE}/cancel {LEASE}/nonce && rmdir {LEASE}")
        if made_dir:
            self.shell(f"rmdir {self.EXPORT_DIRECTORY}")


def run(device, artifact, digest, nonce):
    state = {"status": "refused", "exportClosed": False, "watchdogTerminal": False,
             "leaseReleased": False, "postApkIdentityVerified": False, "goSent": False}
    claimed = armed = False
    helper = None
    made_dir = False
    try:
        pid = device.preflight()
        device.claim(nonce, pid)
        claimed = True
        made_dir = device.stage(artifact, digest, nonce, pid)
        # Treat ambiguous arm/start failures as potentially active. Cleanup is
        # allowed only after our atomic lease claim, never on preflight refusal.
        armed = True
        device.arm()
        device.start()
        helper = device.launch_helper(nonce)
        ready = helper.message(12)
        if ready != {"state": "INPUTS_READY", "nonce": nonce}:
            raise Refused("ready_nonce_or_schema_mismatch")
        device.close_export(pid)
        state["exportClosed"] = state["watchdogTerminal"] = True
        helper.go(nonce)
        state["goSent"] = True
        result = validate_result(helper.message(27))
        helper.finish()
        state["result"] = result
        state["status"] = "complete"
    except HelperFailure as error:
        state["error"] = str(error)
        state["helperError"] = {"stage": error.stage, "code": error.code}
    except Refused as error:
        state["error"] = str(error)
    except BaseException:
        state["error"] = "session_interrupted_or_internal_error"
    finally:
        if helper is not None:
            try:
                helper.close()
            except BaseException:
                state["helperCleanupUnverified"] = True
        if armed and not state["exportClosed"]:
            try:
                device.close_export(pid)
                state["exportClosed"] = state["watchdogTerminal"] = True
            except BaseException:
                state["cleanupError"] = "export_closure_unverified_lease_retained"
        if claimed:
            try:
                device.identity()
                state["postApkIdentityVerified"] = True
            except BaseException:
                state["status"] = "incomplete"
                state["identityError"] = "post_apk_identity_unverified"
        if state["exportClosed"] and state["watchdogTerminal"]:
            try:
                device.release(nonce, made_dir)
                state["leaseReleased"] = True
            except BaseException:
                state["cleanupError"] = "lease_retained"
        if not state["leaseReleased"] or not state["postApkIdentityVerified"]:
            if state["status"] == "complete":
                state["status"] = "incomplete"
    return state


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-acknowledged", action="store_true",
                        help="execute only after separate explicit human authorization of this session")
    parser.add_argument("--artifact", type=Path)
    parser.add_argument("--artifact-sha256")
    parser.add_argument("--helper-source-sha256")
    parser.add_argument("--orchestrator-sha256")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    hashes = {"orchestratorSha256": sha(Path(__file__)),
              "helperSourceSha256": sha(HERE / "EcuMapMetadataProbe.java")}
    if not args.run_acknowledged:
        print(json.dumps({"mode": "local_plan_only", "adbInvoked": False, **hashes,
                          "ports": sorted(PORTS), "watchdogStopSequenceSeconds": 30,
                          "pendingStartCompensation": "retain_lease_and_watchdog_until_settled",
                          "hardClosureDeadlineGuaranteed": False, "helperClass": "EcuMapMetadataProbe",
                          "sequence": ["preflight_and_lease", "stage_and_verify",
                                       "watchdog_armed", "start_export", "inputs_ready",
                                       "stop_and_verify_closed", "watchdog_terminal", "GO", "one_metadata_POST"],
                          "requiresSeparateHumanAuthorization": True}, indent=2))
        return 0
    if (args.artifact is None or not args.artifact.is_file() or args.output is None
            or args.output.exists() or sha(args.artifact) != args.artifact_sha256
            or hashes["helperSourceSha256"] != args.helper_source_sha256
            or hashes["orchestratorSha256"] != args.orchestrator_sha256):
        parser.error("exact artifact/source hashes and a new output directory are required")
    # Output permission/existence is checked before any device operation.
    args.output.mkdir(mode=0o700)
    state = run(Device(), args.artifact, args.artifact_sha256, secrets.token_hex(16))
    state.update(hashes, artifactSha256=args.artifact_sha256)
    fd = os.open(args.output / "session.json", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        json.dump(state, stream, indent=2)
        stream.write("\n")
    print(json.dumps({key: state[key] for key in ("status", "goSent", "exportClosed", "leaseReleased")}))
    return 0 if state["status"] == "complete" else 1


if __name__ == "__main__":
    raise SystemExit(main())
