#!/usr/bin/env python3
"""Bounded GHY metadata experiment. Default is a local plan, never ADB.

Needs separate explicit authorization: temporary BydLog export, three public
factory certificate calls and at most two factory-signed TLS handshakes. Stock
crypto readiness/recovery side effects remain possible. No stock OTA workflow.
"""
import argparse
import json
from pathlib import Path
import re
import secrets
import subprocess

import run_ota_check_metadata as gcy
base = gcy.base
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ADAPTER = ROOT / "captures/telematics-20260924/oncar-native-session/sources/OncarTls.java"
ADAPTER_SHA256 = "95bbb8e871dc306145d298eaa8a70af5611c113a73726b703bf47dac6aecfbc9"
# This source was exercised in the completed September experiment. Build pins it
# again; no general signer entry point or old adapter main is invoked here.
GHY_APK = ("/system/app/OTAUpdate2/OTAUpdate2.apk",
           "1529790d11ecf3c3a6883096f30a685e9af6ca3c6d57eb4359daec90a7afdb04")
DIAGNOSTIC_CODES = {"ghy_factory_other", "ghy_mutual_tls_not_established",
    "ghy_owner_certificate_changed", "ghy_dns", "ghy_tcp_connect", "ghy_timeout",
    "ghy_certificate", "ghy_peer_unverified", "ghy_tls_handshake", "ghy_tls", "ghy_io"}


def source_paths():
    return [Path(__file__), HERE / "build_ghy_check_metadata.py", HERE / "GhyCheckVersionProbe.java",
            HERE / "OtaCheckVersionProbe.java", HERE / "EcuMapMetadataProbe.java",
            HERE / "run_ota_check_metadata.py", HERE / "run_ecu_map_metadata.py", ADAPTER]


def hashes():
    if base.sha(ADAPTER) != ADAPTER_SHA256:
        raise base.Refused("retained_factory_adapter_changed")
    return {str(p.relative_to(ROOT)): base.sha(p) for p in source_paths()}


def snapshot(value):
    gcy.require(type(value) is dict and set(value) == {"cache", "ghyVersion", "ghyVersionSource"})
    # Reuse exactly the already-tested cache schema; the synthetic plans exist
    # only in this validator, are never returned or treated as file observations.
    gcy.snapshot({"cache": value["cache"], "plans": {p: None for p in gcy.PLAN_PATHS}})
    gcy.text(value["ghyVersion"], r"[vV][A-Za-z0-9_.-]{1,63}", 64)
    gcy.require(type(value["ghyVersionSource"]) is str and value["ghyVersionSource"] in
                {"file", "stock_default_exporter_missing", "stock_default_empty"})
    if value["ghyVersionSource"] != "file":
        gcy.require(value["ghyVersion"] == "v1.0.0")


def ids(value):
    gcy.require(type(value) is list and len(value) <= 2048)
    for item in value:
        gcy.text(item, r"[A-Za-z0-9_ -]{1,128}", 128)
        gcy.require(bool(item.strip()))
    gcy.require(len(set(value)) == len(value))


def response(value):
    gcy.require(type(value) is dict and set(value) == {
        "getEcuList", "missingCacheIds", "checkVersion", "checkFailure", "outcome"})
    cat = value["getEcuList"]
    gcy.require(type(cat) is dict and set(cat) == {"status", "sha256", "ecuIds"})
    gcy.text(cat["status"], r"[0-9]{3}", 3)
    gcy.text(cat["sha256"], r"[a-f0-9]{64}", 64)
    ids(cat["ecuIds"]); ids(value["missingCacheIds"])
    gcy.require(set(value["missingCacheIds"]) <= set(cat["ecuIds"]))
    outcome, check, failure = value["outcome"], value["checkVersion"], value["checkFailure"]
    if outcome == "catalogue_rejected":
        gcy.require(cat["status"] != "100" and not cat["ecuIds"] and not value["missingCacheIds"]
                    and check is None and failure is None)
    else:
        gcy.require(cat["status"] == "100" and bool(cat["ecuIds"]))
        if outcome == "cache_incomplete":
            gcy.require(bool(value["missingCacheIds"]) and check is None and failure is None)
        elif outcome == "check_failed":
            gcy.require(not value["missingCacheIds"] and check is None and type(failure) is str
                        and failure in gcy.CODES | DIAGNOSTIC_CODES)
        else:
            gcy.require(outcome == "check_returned" and not value["missingCacheIds"] and failure is None)
            gcy.plan(check, response=True)


class Helper(gcy.Helper):
    def parse(self, raw):
        return gcy.parse_message(raw, codes=gcy.CODES | DIAGNOSTIC_CODES)


class Device(gcy.Device):
    def peer_processes(self):
        result = {}
        for name in ("com.byd.otaupdate", "com.byd.otaupdate2", "safekeyservice"):
            pid = self.shell("pidof " + name)
            if not re.fullmatch(r"[1-9][0-9]*", pid):
                raise base.Refused("peer_process_unavailable")
            result[name] = {"pid": pid, "start": self.process_start(pid)}
        return result

    def preflight(self):
        self.peer_postflight_verified = False
        pid = super().preflight()
        self.peers = self.peer_processes()
        return pid

    def identity(self):
        super().identity()
        path, digest = GHY_APK
        if self.shell("pm path com.byd.otaupdate2") != "package:" + path:
            raise base.Refused("apk_path_mismatch")
        if self.shell("sha256sum " + path).split() != [digest, path]:
            raise base.Refused("apk_hash_mismatch")
        if hasattr(self, "peers"):
            if self.peer_processes() != self.peers:
                raise base.Refused("peer_process_changed")
            self.peer_postflight_verified = True

    def launch_helper(self, nonce):
        command = (f"CLASSPATH={base.LEASE}/classes.dex app_process /system/bin "
                   f"--nice-name={base.NICE_NAME} GhyCheckVersionProbe --nonce {nonce}")
        self.existing_adb_server()
        return Helper(subprocess.Popen([*base.ADB, "shell", "-T", command], stdin=subprocess.PIPE,
                                      stdout=subprocess.PIPE, stderr=subprocess.DEVNULL))


def run(device, artifact, digest, nonce, save_snapshot):
    return gcy.run(device, artifact, digest, nonce, save_snapshot,
                   snapshot_validator=snapshot, response_validator=response, result_timeout=48)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run-acknowledged", action="store_true")
    p.add_argument("--build", type=Path)
    p.add_argument("--output", type=Path)
    args = p.parse_args()
    if not args.run_acknowledged:
        print(json.dumps({"mode": "local_plan_only", "adbInvoked": False,
            "host": "api-ota-cn.denzacloud.com:443", "maxHttpRequests": 2,
            "operations": ["getEcuList", "checkVersion only if every requested ECU has a cache row"],
            "factoryPublicCertificateCalls": 3, "maxFactoryTlsSignatures": 2,
            "stockCryptoReadinessRecoveryMayRun": True,
            "stockOtaInvoked": False, "downloads": False, "installation": False,
            "ecuSource": "unchanged cached six-field records; source measurement time unknown",
            "ghyVersion": "SourceVehicleVersion; stock v1.0.0 only for verified exporter FILE_NOT_FOUND or empty file",
            "credentials": "GHY prefs stay in device RAM; no private-key export",
            "exportPorts": sorted(base.PORTS), "requiresNewSessionAuthorization": True,
            "sourceHashes": hashes()}, indent=2))
        return
    if args.build is None or args.output is None or args.output.exists():
        p.error("pinned build and a new output directory required")
    manifest = json.loads((args.build / "manifest.json").read_text())
    artifact = args.build / "dex/classes.dex"
    if manifest.get("sourceHashes") != hashes() or base.sha(artifact) != manifest.get("artifactSha256"):
        p.error("build/source hashes differ")
    args.output.mkdir(mode=0o700)
    device = Device()
    result = run(device, artifact, manifest["artifactSha256"], secrets.token_hex(16),
                 lambda v: gcy.write_json(args.output / "snapshot-before-request.json", v))
    result.update(sourceHashes=hashes(), artifactSha256=manifest["artifactSha256"])
    result["peerProcessIdentityVerified"] = getattr(device, "peer_postflight_verified", False)
    result["peerProcessesBefore"] = getattr(device, "peers", None)
    gcy.write_json(args.output / "session.json", result)
    print(json.dumps({k: result[k] for k in ("status", "goSent", "exportClosed", "leaseReleased")}))
    raise SystemExit(0 if result["status"] == "complete" else 1)


if __name__ == "__main__": main()
