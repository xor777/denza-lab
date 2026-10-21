#!/usr/bin/env python3
"""One isolated GCY checkVersion, never a stock update. Default is local plan.

Uses the existing export ownership/watchdog transport without changing it.
A live run requires the pinned build and explicit authorization of the temporary
BydLog listeners and RAM-only identity use. No login refresh or retries.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import time

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("ecu_metadata_base", HERE / "run_ecu_map_metadata.py")
base = importlib.util.module_from_spec(spec)
spec.loader.exec_module(base)
STAGES = base.HELPER_STAGES | {"input_cache", "input_plans"}
CODES = base.HELPER_CODES | {"check_projection", "check_cache_schema", "check_cache_version",
    "check_cache_enctype", "check_cache_empty", "check_cache_missing", "check_duplicate_ecu"}
FIELDS = {"ecuId", "ecuSoftCode", "ecuSoftVer", "ecuHardVer", "ecuConfigCode", "ecuConfigVer"}
PACKAGES = {"sourcePackageInfo", "targetPackageInfo", "configSourceInfo", "configPackageInfo"}
PLAN_PATHS = {f"/data/ota_package/{directory}/result/strategy.json" for directory in
              ("ota", "ota_ghy", "ota_productline")}


def require(ok):
    if not ok:
        raise base.Refused("invalid_check_projection")


def text(value, pattern, maximum):
    require(type(value) is str and len(value) <= maximum and re.fullmatch(pattern, value) is not None)


def rows(value, complete=False, packages=False):
    require(type(value) is list and len(value) <= 2048)
    ids = set()
    for row in value:
        require(type(row) is dict and "ecuId" in row and not set(row) - FIELDS -
                ({"encryptSHA256", "hasDownloadUrl"} if packages else set()))
        if complete:
            require(set(row) == FIELDS)
        for key, item in row.items():
            if key == "hasDownloadUrl": require(type(item) is bool)
            elif key == "encryptSHA256": text(item, r"[0-9a-fA-F]{64}", 64)
            else: text(item, r"[A-Za-z0-9_ -]{1,128}" if key == "ecuId" else r"[A-Za-z0-9_.-]{0,128}", 128)
        require(bool(row["ecuId"].strip()))
        if not packages:
            require(row["ecuId"] not in ids)
            ids.add(row["ecuId"])


def plan(value, response=False):
    require(type(value) is dict and set(value) == PACKAGES | {"sha256", "causeKind", "strategy"} |
            ({"status"} if response else set()))
    text(value["sha256"], r"[a-f0-9]{64}", 64)
    if response: text(value["status"], r"[0-9]{3}", 3)
    require(value["causeKind"] in ("absent", "ecu_strategy_mismatch", "other_omitted"))
    s = value["strategy"]
    require(type(s) is dict and not set(s) - {"batchId", "strategyVer", "oldStrategyVer",
                                            "isForce", "strategyType", "sequence"})
    for key, item in s.items():
        if key == "sequence": rows(item)
        else: text(item, r"[A-Za-z0-9_.-]{0,128}", 128)
    for key in PACKAGES: rows(value[key], packages=True)


def snapshot(value):
    require(type(value) is dict and set(value) == {"cache", "plans"})
    c = value["cache"]
    require(type(c) is dict and set(c) == {"version", "ecuInfo", "sha256"})
    text(c["version"], r"V[A-Za-z0-9_.-]{1,63}", 64)
    text(c["sha256"], r"[a-f0-9]{64}", 64)
    rows(c["ecuInfo"], complete=True)
    require(bool(c["ecuInfo"]))
    require(type(value["plans"]) is dict and set(value["plans"]) == PLAN_PATHS)
    for p in value["plans"].values():
        if p is not None: plan(p)


def parse_message(raw):
    require(len(raw) <= base.MAX_LINE)
    try:
        value = json.loads(raw, object_pairs_hook=base.unique_pairs,
                           parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    except (ValueError, UnicodeError, RecursionError):
        raise base.Refused("helper_output_invalid") from None
    require(type(value) is dict)
    if value.get("state") == "ERROR":
        require(set(value) == {"state", "stage", "code"})
        require(type(value["stage"]) is str and type(value["code"]) is str)
        require(value["stage"] in STAGES and value["code"] in CODES)
        raise base.HelperFailure(value["stage"], value["code"])
    return value


class Helper(base.Helper):
    def message(self, timeout):
        deadline = time.monotonic() + timeout
        while True:
            if b"\n" in self.buffer:
                raw, _, tail = self.buffer.partition(b"\n")
                self.buffer[:] = tail
                return parse_message(raw)
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not self.selector.select(remaining):
                raise base.Refused("helper_deadline")
            chunk = os.read(self.proc.stdout.fileno(), 8192)
            if not chunk: raise base.Refused("helper_ended_without_result")
            self.buffer.extend(chunk)
            if len(self.buffer) > base.MAX_LINE: raise base.Refused("helper_output_too_large")


class Device(base.Device):
    def launch_helper(self, nonce):
        command = (f"CLASSPATH={base.LEASE}/classes.dex app_process /system/bin "
                   f"--nice-name={base.NICE_NAME} OtaCheckVersionProbe --nonce {nonce}")
        self.existing_adb_server()
        return Helper(subprocess.Popen([*base.ADB, "shell", "-T", command], stdin=subprocess.PIPE,
                                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL))


def run(device, artifact, digest, nonce, save_snapshot):
    state = {"status": "refused", "exportClosed": False, "watchdogTerminal": False,
             "leaseReleased": False, "postApkIdentityVerified": False, "goSent": False}
    claimed = armed = made_dir = False
    helper = None
    try:
        pid = device.preflight()
        device.claim(nonce, pid)
        claimed = True
        made_dir = device.stage(artifact, digest, nonce, pid)
        armed = True
        device.arm()
        device.start()
        helper = device.launch_helper(nonce)
        ready = helper.message(20)
        require(set(ready) == {"state", "nonce", "snapshot"} and ready["state"] == "INPUTS_READY"
                and ready["nonce"] == nonce)
        snapshot(ready["snapshot"])
        state["snapshot"] = ready["snapshot"]
        device.close_export(pid)
        state["exportClosed"] = state["watchdogTerminal"] = True
        save_snapshot(ready["snapshot"])
        helper.go(nonce)
        state["goSent"] = True
        result = helper.message(27)
        require(set(result) == {"state", "response"} and result["state"] == "RESULT")
        plan(result["response"], response=True)
        helper.finish()
        state["response"] = result["response"]
        state["status"] = "complete"
    except base.HelperFailure as error:
        state["error"] = str(error)
        state["helperError"] = {"stage": error.stage, "code": error.code}
    except base.Refused as error:
        state["error"] = str(error)
    except BaseException:
        state["error"] = "session_interrupted_or_internal_error"
    finally:
        if helper is not None:
            try: helper.close()
            except BaseException: state["helperCleanupUnverified"] = True
        if armed and not state["exportClosed"]:
            try:
                device.close_export(pid)
                state["exportClosed"] = state["watchdogTerminal"] = True
            except BaseException: state["cleanupError"] = "export_closure_unverified_lease_retained"
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
            except BaseException: state["cleanupError"] = "lease_retained"
        if not state["leaseReleased"] or not state["postApkIdentityVerified"]:
            if state["status"] == "complete": state["status"] = "incomplete"
    return state


def write_json(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        json.dump(value, stream, indent=2)
        stream.write("\n")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-acknowledged", action="store_true")
    p.add_argument("--build", type=Path)
    p.add_argument("--output", type=Path)
    args = p.parse_args()
    sources = [Path(__file__), HERE / "OtaCheckVersionProbe.java", HERE / "EcuMapMetadataProbe.java",
               HERE / "run_ecu_map_metadata.py"]
    hashes = {f.name: base.sha(f) for f in sources}
    if not args.run_acknowledged:
        print(json.dumps({"mode": "local_plan_only", "adbInvoked": False, "sourceHashes": hashes,
            "endpoint": "https://fota-tsp.denzacloud.com:6113/ota/otaService/checkVersion",
            "requests": 1, "ecuDataSource": "existing ECUVersion.json; age unknown",
            "stockOtaInvoked": False, "downloads": False, "installation": False,
            "ports": sorted(base.PORTS), "privateInputs": "existing GCY identity/token in device RAM",
            "requiresSessionAuthorization": True}, indent=2))
        return
    if args.build is None or args.output is None or args.output.exists():
        p.error("pinned build and a new output directory required")
    manifest = json.loads((args.build / "manifest.json").read_text())
    artifact = args.build / "dex/classes.dex"
    if manifest.get("sourceHashes") != hashes or base.sha(artifact) != manifest.get("artifactSha256"):
        p.error("build/source hashes differ")
    args.output.mkdir(mode=0o700)
    result = run(Device(), artifact, manifest["artifactSha256"], secrets.token_hex(16),
                 lambda value: write_json(args.output / "snapshot-before-request.json", value))
    result.update(sourceHashes=hashes, artifactSha256=manifest["artifactSha256"])
    write_json(args.output / "session.json", result)
    print(json.dumps({key: result[key] for key in ("status", "goSent", "exportClosed", "leaseReleased")}))
    raise SystemExit(0 if result["status"] == "complete" else 1)


if __name__ == "__main__": main()
