#!/usr/bin/env python3
"""Plan, preflight, or perform one separately approved existing-cache export.

No VHDS invocation, properties, ECU requests, credentials or factory HTTP.
BydLogTool itself exposes all-interface listeners including deletion; fresh
one-session owner approval is required despite the read-only client protocol.
"""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import secrets
import shlex
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
sys.path.insert(0, str(HERE))
import run_ecu_map_metadata as lifecycle

RESEARCH = ROOT / "research/vehicle-diagnostics"
sys.path.insert(0, str(RESEARCH))
READ_ERRORS = frozenset("""
missing total_timeout socket_timeout socket_error invalid_loopback_endpoint
request_not_allowed truncated_response unexpected_trailing_bytes duplicate_json_key
invalid_json_number invalid_response_header json_length_out_of_bounds invalid_json
response_status_conflict remote_error_2 remote_error_3 remote_error_4 remote_error_5
invalid_file_size invalid_directory_listing invalid_listing_name duplicate_listing_name
file_name_conflict file_size_out_of_bounds file_size_conflict invalid_md5 md5_mismatch
""".split())


def reader_module():
    import read_vhds_config_export
    return read_vhds_config_export


def plan():
    paths = [Path(__file__).resolve(), HERE / "run_ecu_map_metadata.py",
             RESEARCH / "read_vhds_config_export.py", RESEARCH / "read_ecu_cache_export.py"]
    return {
        "schema": "denza-lab.vhds-cache-session-plan.v1",
        "sourceSha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                         for p in paths},
        "file": "/data/vhds/trigger_list_cfg/allone.txt",
        "directoryListings": 1, "fileDownloads": 1, "retries": 0,
        "maxFileBytes": 1048576, "readDeadlineSeconds": 20,
        "watchdogBeginsStopSeconds": 30, "hardClosureDeadlineGuaranteed": False,
        "privateResponseSaved": False, "ecuRequests": 0, "factoryRequests": 0,
        "vhdsInvocations": 0, "freshOwnerApprovalRequired": True,
    }


class Device(lifecycle.Device):
    def identity(self):
        package = "com.byd.bydlogtool"
        path, digest = lifecycle.APKS[package]
        if self.shell(f"pm path {package}") != "package:" + path:
            raise lifecycle.Refused("apk_path_mismatch")
        if self.shell(f"sha256sum {path}").split() != [digest, path]:
            raise lifecycle.Refused("apk_hash_mismatch")

    def stage_export(self, nonce, pid):
        transition, watchdog = lifecycle.scripts(nonce, pid)
        for name, content in (("transition.sh", transition), ("watchdog.sh", watchdog)):
            self.shell(f"umask 077; printf %s {shlex.quote(content)} > {lifecycle.LEASE}/{name}")
        if self.shell(f"mkdir {self.EXPORT_DIRECTORY} && echo created") != "created":
            raise lifecycle.Refused("shared_export_directory_claim_unverified")
        return True

    def forward(self):
        value = self.command(["forward", "tcp:0", "tcp:46321"])
        if not re.fullmatch(r"[1-9][0-9]{0,4}", value) or int(value) > 65535:
            raise lifecycle.Refused("forward_port_unverified")
        return int(value)

    def remove_forward(self, port):
        expected = [lifecycle.ADB[2], f"tcp:{port}", "tcp:46321"]
        rows = [row.split() for row in self.command(["forward", "--list"]).splitlines()]
        matches = [row for row in rows if len(row) == 3 and row[1] == f"tcp:{port}"]
        if matches != [expected]:
            raise lifecycle.Refused("forward_owner_unverified")
        self.command(["forward", "--remove", f"tcp:{port}"])
        rows = [row.split() for row in self.command(["forward", "--list"]).splitlines()]
        if any(len(row) == 3 and row[1] == f"tcp:{port}" for row in rows):
            raise lifecycle.Refused("forward_removal_unverified")

    def release(self, nonce, made_dir):
        lease = lifecycle.LEASE
        self.shell(f"test \"$(cat {lease}/nonce)\" = {nonce} && "
                   f"test \"$(cat {lease}/phase)\" = stopped && "
                   f"case \"$(cat {lease}/watchdog)\" in cancelled|deadline_stopped) ;; *) exit 1;; esac && "
                   f"rm -f {lease}/deadline && rm {lease}/transition.sh {lease}/watchdog.sh "
                   f"{lease}/bydlog.pid {lease}/bydlog.start {lease}/phase {lease}/watchdog "
                   f"{lease}/cancel {lease}/nonce && rmdir {lease}")
        if made_dir:
            self.shell(f"rmdir {self.EXPORT_DIRECTORY}")


def run(device, reader, nonce):
    state = {"schema": "denza-lab.vhds-cache-session.v1", "status": "refused",
             "exportClosed": False, "watchdogTerminal": False, "leaseReleased": False,
             "forwardRemoved": False, "postApkIdentityVerified": False}
    claimed = armed = made_dir = forward_attempted = False
    port = raw = None
    stage = "preflight"
    try:
        pid = device.preflight()
        stage = "claim"
        device.claim(nonce, pid)
        claimed = True
        stage = "stage"
        made_dir = device.stage_export(nonce, pid)
        stage = "forward"
        forward_attempted = True
        port = device.forward()
        stage = "arm"
        armed = True  # An ambiguous arm/start is potentially active.
        device.arm()
        stage = "start"
        device.start()
        stage = "read"
        raw = reader.read_config("127.0.0.1", port)
    except BaseException as error:
        # Never reflect remote strings, paths or arbitrary exception text.
        state["error"] = "acquisition_failed_or_interrupted"
        state["failureStage"] = stage
        if stage == "read" and str(error) in READ_ERRORS:
            state["readError"] = str(error)
    finally:
        if armed:
            try:
                device.close_export(pid)
                state["exportClosed"] = state["watchdogTerminal"] = True
            except BaseException:
                state["cleanupError"] = "export_closure_unverified_lease_retained"
        if port is not None:
            try:
                device.remove_forward(port)
                state["forwardRemoved"] = True
            except BaseException:
                state["forwardError"] = "forward_cleanup_unverified"
        elif forward_attempted:
            state["forwardError"] = "forward_allocation_unverified"
        if claimed:
            try:
                device.identity()
                state["postApkIdentityVerified"] = True
            except BaseException:
                state["identityError"] = "post_apk_identity_unverified"
        if state["exportClosed"] and state["watchdogTerminal"]:
            try:
                device.release(nonce, made_dir)
                state["leaseReleased"] = True
            except BaseException:
                state["cleanupError"] = "release_unverified"
    # No response projection/save before confirmed closure and owner cleanup.
    complete = all(state[key] for key in ("exportClosed", "watchdogTerminal", "leaseReleased",
                                         "forwardRemoved", "postApkIdentityVerified"))
    if raw is not None and complete:
        try:
            state["result"] = reader.project_config(raw)
            state["status"] = "complete"
        except BaseException:
            state["error"] = "cache_projection_rejected"
    elif claimed and not complete:
        state["status"] = "incomplete"
    return state


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--preflight", action="store_true", help="read-only car checks, no lease/START")
    mode.add_argument("--run-acknowledged", action="store_true", help="requires fresh owner approval")
    parser.add_argument("--reviewed-plan", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args(argv)
    if args.preflight:
        try:
            Device().preflight()
            print(json.dumps({"status": "ready", "exportStarted": False}))
            return 0
        except lifecycle.Refused as error:
            print(json.dumps({"status": "refused", "reason": str(error), "exportStarted": False}))
            return 2
    if not args.run_acknowledged:
        print(json.dumps(plan(), indent=2))
        return 0
    if args.reviewed_plan is None or args.output is None:
        parser.error("execution needs --reviewed-plan and a new --output directory")
    reviewed = json.loads(args.reviewed_plan.read_text())
    if reviewed != plan():
        parser.error("reviewed source/plan mismatch")
    # Consume before even preflight: a failed session cannot silently reuse it.
    marker = args.reviewed_plan.with_name(args.reviewed_plan.name + ".consumed")
    with marker.open("x") as stream:
        stream.write("one session consumed\n")
    args.output.mkdir(mode=0o700, parents=False, exist_ok=False)
    started = datetime.datetime.now(datetime.timezone.utc).isoformat()
    state = run(Device(), reader_module(), secrets.token_hex(16))
    state["startedUtc"] = started
    state["finishedUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    state["plan"] = reviewed
    with (args.output / "result.json").open("x") as stream:
        json.dump(state, stream, indent=2)
        stream.write("\n")
    print(json.dumps({key: value for key, value in state.items() if key not in ("plan", "result")}))
    return 0 if state["status"] == "complete" else 2


if __name__ == "__main__":
    raise SystemExit(main())
