#!/usr/bin/env python3
"""Explicit future stationary-session setup/restore. Development/tests never call a device.

`plan` and `arm --dry-run` are local only. `build-reader` compiles the read-only
shell helper locally. `arm`/`restore` are deliberately separate, explicit ADB
operations: no install, launch, key management, tunnels, vehicle setters or
automatic APK-version inference. A rollback ledger is durable before mutation.
"""
from __future__ import annotations

import argparse
import base64
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
import hashlib
import json
import math
import os
from pathlib import Path
import re
import shlex
import struct
import subprocess
import tempfile
import time
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "ru.yandex.yandexnavi"
STOCK = "com.byd.launchermap"
ARM = "files/denza-adas-live-arm.json"
HEARTBEAT = "files/denza-adas-consumer.json"
STATE = "files/denza-adas-live-state.json"
GATES = "files/denza-adas-gates.json"
STOP = "files/denza-adas-reader-stop.json"
ISOLATION = "files/denza-adas-stock-isolation.json"
FILES = frozenset((ARM, HEARTBEAT, STATE, GATES, STOP, ISOLATION))
MAX_JSON = 16384
MAX_LIFETIME_MS = 4 * 60 * 60 * 1000
MAX_AGE_MS = 5000
READER_MAX_AGE_MS = 2500
PARK_FID = 0x05500030
SPEED_FID_SIGNED = -1807745016  # 0x94400008, device1013 FLOAT transaction7.
PARCEL = re.compile(r"^\s*Result:\s*Parcel\(([0-9a-fA-F]{8})\s+([0-9a-fA-F]{8})\s*(?:'[^'\r\n]*')?\)\s*$")
REQUIRED_FIDS = (1126170651, 1126170658, 1126170664, 700448824, 700448826,
                 481296416, 535830576, 535838789, 535842878)


class SessionError(ValueError):
    pass


def validate_serial(serial):
    if not isinstance(serial, str) or not re.fullmatch(r"[A-Za-z0-9_.:-]{1,128}", serial) or serial.startswith("-"):
        raise SessionError("explicit safe ADB serial required")
    return serial


def boot_count_uuid(value):
    if not isinstance(value, str) or not re.fullmatch(r"0|[1-9][0-9]{0,9}", value):
        raise SessionError("BOOT_COUNT unavailable/noncanonical")
    count = integer(int(value), "BOOT_COUNT", 0, 2**31 - 1)
    raw = ("denza-adas-boot-count-v1:" + str(count)).encode("utf-8")
    return str(uuid.UUID(bytes=hashlib.md5(raw).digest(), version=3))


def integer(value, label, minimum=0, maximum=2**63 - 1):
    if type(value) is not int or not minimum <= value <= maximum:
        raise SessionError("invalid " + label)
    return value


def canonical_uuid(value):
    if not isinstance(value, str) or len(value) != 36:
        raise SessionError("invalid UUID")
    try:
        if str(uuid.UUID(value)) == value:
            return value
    except ValueError:
        pass
    raise SessionError("invalid canonical UUID")


def sha256_file(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def checked_sha(value):
    if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{64}", value):
        raise SessionError("explicit lowercase SHA256 required")
    return value


@dataclass(frozen=True)
class ExpectedApk:
    sha256: str
    version_code: int
    version_name: str

    def __post_init__(self):
        checked_sha(self.sha256)
        integer(self.version_code, "APK versionCode", 1, 2**31 - 1)
        if not isinstance(self.version_name, str) or not re.fullmatch(r"[A-Za-z0-9_.+-]{1,100}", self.version_name):
            raise SessionError("explicit APK versionName required")

    def verify(self, actual):
        if (actual["sha256"], actual["versionCode"], actual["versionName"]) != (self.sha256, self.version_code, self.version_name):
            raise SessionError("installed APK identity differs from explicit expected identity")


def parse_package(text):
    def one(pattern):
        matches = re.findall(pattern, text, re.MULTILINE)
        if len(matches) != 1:
            raise SessionError("ambiguous/missing package metadata")
        return matches[0]
    state = int(one(r"^\s*User 0:.*?\benabled=([0-4])\b.*$"))
    installed = one(r"^\s*User 0:.*?\binstalled=(true|false)\b.*$") == "true"
    return {"enabledState": state, "installed": installed, "versionCode": int(one(r"^\s*versionCode=(\d+)\b.*$")),
            "versionName": one(r"^\s*versionName=([^\s]+)\s*$")}


def parcel_word(text):
    if not isinstance(text, str) or len(text) > MAX_JSON:
        raise SessionError("invalid autoservice reply")
    match = PARCEL.fullmatch(text)
    if not match or match.group(1) != "00000000":
        raise SessionError("autoservice status/shape not proven")
    return int(match.group(2), 16)


def legacy_park(text):
    value = parcel_word(text)
    if value not in (0, 1):
        raise SessionError("legacy parked predicate unknown")
    return value


def zero_speed(text):
    value = struct.unpack("<f", struct.pack("<I", parcel_word(text)))[0]
    if not math.isfinite(value) or value != 0:
        raise SessionError("actual zero speed not established")
    return value


def decode_json(data):
    if len(data) > MAX_JSON:
        raise SessionError("private JSON cap")
    try:
        row = json.loads(data.decode("utf-8"), object_pairs_hook=unique_keys, parse_constant=nonfinite_json)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise SessionError("invalid private JSON") from error
    if not isinstance(row, dict):
        raise SessionError("expected JSON object")
    return row


def nonfinite_json(value):
    raise SessionError("nonfinite JSON")


def unique_keys(pairs):
    row = {}
    for key, value in pairs:
        if key in row:
            raise SessionError("duplicate JSON key")
        row[key] = value
    return row


def fresh_tuple(row, schema, boot, consumer, now, timestamp="elapsedMs"):
    if row.get("schema") != schema or row.get("bootId") != boot or row.get("consumerSession") != consumer:
        raise SessionError("schema/boot/consumer changed")
    canonical_uuid(row.get("bootId")); canonical_uuid(row.get("consumerSession"))
    stamp = integer(row.get(timestamp), timestamp)
    if now < stamp or now - stamp > MAX_AGE_MS:
        raise SessionError("stale/future private snapshot")
    return row


class Device:
    """Fixed command builders only; no caller-controlled shell fragment or default ADB target."""
    def __init__(self, adb, serial, *, runner=subprocess.run):
        self.adb, self.serial, self.runner = str(adb), validate_serial(serial), runner
        self.deadline = time.monotonic() + 90
        self.command_count = 0

    def command(self, args, *, body=None, cap=131072):
        self.command_count += 1
        remaining = self.deadline - time.monotonic()
        if remaining <= 0 or self.command_count > 128:
            raise SessionError("bounded ADB session budget exhausted")
        result = self.runner([self.adb, "-s", self.serial, *args], input=body, capture_output=True,
                             timeout=min(5, remaining), check=False)
        if len(result.stdout) > cap or len(result.stderr) > MAX_JSON:
            raise SessionError("ADB output cap")
        if result.returncode:
            raise SessionError("bounded ADB command failed: " + result.stderr.decode(errors="replace")[:200])
        return result.stdout

    def shell(self, *tokens):
        return self.command(["shell", " ".join(shlex.quote(str(t)) for t in tokens)]).decode("utf-8")

    def execute(self, *tokens, body=None, cap=131072):
        return self.command(["exec-out", " ".join(shlex.quote(str(t)) for t in tokens)], body=body, cap=cap)

    def boot(self):
        return boot_count_uuid(self.shell("settings", "get", "global", "boot_count").strip())

    def elapsed(self):
        parts = self.shell("cat", "/proc/uptime").split()
        if not parts:
            raise SessionError("elapsedRealtime source unavailable")
        first = parts[0]
        if not re.fullmatch(r"[0-9]{1,12}(?:\.[0-9]{1,9})?", first):
            raise SessionError("invalid elapsedRealtime source")
        try:
            return integer(int(Decimal(first) * 1000), "elapsedRealtime")
        except InvalidOperation as error:
            raise SessionError("invalid uptime") from error

    def private(self, name):
        if name not in FILES:
            raise SessionError("private-file allowlist")
        # All script content is fixed; only one constant from the allowlist is substituted.
        script = f"if [ -f {name} ]; then head -c {MAX_JSON + 1} {name}; fi"
        data = self.execute("run-as", PACKAGE, "sh", "-c", script, cap=MAX_JSON + 1)
        return decode_json(data) if data else None

    def write_private(self, name, row):
        if name not in (ARM, STOP):
            raise SessionError("private-write allowlist")
        data = (json.dumps(row, separators=(",", ":"), allow_nan=False) + "\n").encode()
        if len(data) > MAX_JSON:
            raise SessionError("private-write cap")
        # Android adb exec-out can keep remote stdin open after the host has sent
        # its complete input. A cat-to-EOF write then never reaches the atomic mv.
        # Base64 is a bounded shell-safe alphabet; JSON never becomes shell code.
        encoded = base64.b64encode(data).decode("ascii")
        script = (f"umask 077; printf %s {shlex.quote(encoded)} | base64 -d > {name}.tmp"
                  f" && mv {name}.tmp {name}")
        self.execute("run-as", PACKAGE, "sh", "-c", script)
        if self.private(name) != row:
            raise SessionError("private write readback mismatch")

    def remove_arm(self):
        self.execute("run-as", PACKAGE, "rm", "-f", ARM, ARM + ".tmp")
        if self.private(ARM) is not None:
            raise SessionError("disarm readback failed")

    def app(self, package):
        if package not in (PACKAGE, STOCK):
            raise SessionError("package allowlist")
        info = parse_package(self.shell("dumpsys", "package", package))
        if not info["installed"]:
            raise SessionError("package not installed for user0")
        paths = self.shell("pm", "path", "--user", "0", package).strip().splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            raise SessionError("one installed base APK required; split/absent package refused")
        apk = paths[0][8:]
        if not re.fullmatch(r"/(?:data/app|system/(?:app|priv-app))/[A-Za-z0-9_./+=:@~-]+\.apk", apk) or "/../" in apk:
            raise SessionError("unexpected installed APK path")
        hashes = self.shell("sha256sum", apk).split()
        if not hashes:
            raise SessionError("installed APK hash unavailable")
        digest = hashes[0]
        info.update({"path": apk, "sha256": checked_sha(digest)})
        return info

    def stationary(self):
        park_reply = self.shell("service", "call", "autoservice", "5", "i32", "1011", "i32", str(PARK_FID))
        speed_reply = self.shell("service", "call", "autoservice", "7", "i32", "1013", "i32", str(SPEED_FID_SIGNED))
        if legacy_park(park_reply) != 1:
            raise SessionError("legacy parked predicate is not1")
        return {"legacyParkRaw": 1, "legacyParkDevice": 1011, "legacyParkFid": PARK_FID, "legacyParkTransact": 5,
                "speedMps": zero_speed(speed_reply), "speedDevice": 1013, "speedFid": SPEED_FID_SIGNED, "speedTransact": 7,
                "meaning": "Human stationary confirmation + legacy parked predicate + measured zero speed; not independent selector-P proof"}

    def stock_pids(self):
        # AOSP Toybox ps.c blob304fe006...: NAME is argv[0], CMD is the 15-byte comm;
        # -w disables truncation. Also require a real full-length Navigator NAME witness.
        result = self.shell("ps", "-A", "-w", "-o", "PID,NAME")
        lines = result.strip().splitlines()
        if len(lines) < 2 or lines[0].split() != ["PID", "NAME"]:
            raise SessionError("missing exact process-list header/data")
        pids, full_name_witness = [], False
        for line in lines[1:]:
            parts = line.split()
            if len(parts) != 2 or not parts[0].isdigit() or int(parts[0]) <= 0 or "+" in parts[1]:
                raise SessionError("unknown process-list format")
            if parts[1] == PACKAGE:
                full_name_witness = True
            if parts[1] == STOCK or parts[1].startswith(STOCK + ":"):
                pids.append(int(parts[0]))
        if not full_name_witness:
            raise SessionError("full Navigator process-name witness unavailable; absence cannot be inferred")
        return pids

    def stock_enabled(self, value):
        command = {0: "default-state", 1: "enable", 3: "disable-user"}.get(value)
        if command is None:
            raise SessionError("original enabled state not proven restorable by shell")
        self.shell("pm", command, "--user", "0", STOCK)
        if parse_package(self.shell("dumpsys", "package", STOCK))["enabledState"] != value:
            raise SessionError("package enabled-state readback mismatch")

    def stock_stop(self):
        self.shell("am", "force-stop", "--user", "0", STOCK)
        if self.stock_pids():
            raise SessionError("stock process still present after force-stop")

    def reader(self, jar, digest, consumer, reader, lifetime):
        canonical_uuid(consumer); canonical_uuid(reader)
        integer(lifetime, "reader lifetime", 1000, MAX_LIFETIME_MS)
        remote = "/data/local/tmp/denza-adas-reader-" + reader + ".jar"
        log = "/data/local/tmp/denza-adas-reader-" + reader + ".log"
        self.command(["push", str(Path(jar).resolve()), remote])
        hashes = self.shell("sha256sum", remote).split()
        if not hashes or hashes[0] != digest:
            raise SessionError("reader transfer hash mismatch")
        # Every substituted argument is a canonical UUID, bounded decimal or fixed derived path.
        # Firmware inventory files-system.txt:2105 includes nohup. Keep all three standard
        # descriptors detached so the bounded reader does not depend on the ADB shell lifetime.
        script = (f"CLASSPATH={shlex.quote(remote)} /system/bin/nohup /system/bin/app_process /system/bin dev.denza.adasprobe.AdasGateReader "
                  f"{consumer} {reader} {lifetime} > {shlex.quote(log)} 2>&1 < /dev/null & echo $!")
        output = self.shell("sh", "-c", script).strip()
        if not re.fullmatch(r"[1-9][0-9]{0,9}", output):
            raise SessionError("reader launch PID not established")
        return {"pid": int(output), "jar": remote, "log": log}


def save_ledger(path, row, *, create=False):
    path = Path(path)
    data = (json.dumps(row, indent=2, sort_keys=True, allow_nan=False) + "\n").encode()
    if len(data) > 65536:
        raise SessionError("rollback ledger cap")
    path.parent.mkdir(parents=True, exist_ok=True)
    if create:
        descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(data); stream.flush(); os.fsync(stream.fileno())
    else:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".", delete=False) as stream:
            temporary = Path(stream.name); stream.write(data); stream.flush(); os.fsync(stream.fileno())
        os.replace(temporary, path)
    directory = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)


def consumer_now(device, boot, consumer=None):
    row = device.private(HEARTBEAT)
    if row is None:
        raise SessionError("runtime consumer heartbeat missing")
    who = canonical_uuid(row.get("consumerSession"))
    fresh_tuple(row, "denza-adas-consumer-v1", boot, consumer or who, device.elapsed())
    return who


def quiescent(device, boot, consumer, *, stable=False, sleep=time.sleep):
    previous = None
    for _ in range(16 if stable else 1):
        row = device.private(STATE)
        if row is not None:
            fresh_tuple(row, "denza-adas-live-state-v1", boot, consumer, device.elapsed())
            if row.get("state") not in ("DISARMED", "WAITING", "STOPPED", "BLOCKED", "LIVE"):
                raise SessionError("unknown runtime state")
            if not stable and row.get("state") == "LIVE":
                raise SessionError("runtime not in a stopped/waiting state")
            closed = all(row.get(key) is True for key in ("quiescent", "serverClosed", "answersClosed"))
            last = integer(row.get("lastActualSendElapsedMs"), "last actual send", -1)
            if last > row["elapsedMs"]:
                raise SessionError("future actual-send state")
            if closed and row.get("state") != "LIVE":
                if not stable:
                    return row
                if previous is not None and row["elapsedMs"] > previous["elapsedMs"]:
                    if last != previous["lastActualSendElapsedMs"]:
                        raise SessionError("actual egress changed during restore quiescence check")
                    return row
                previous = row
        sleep(0.25)
    raise SessionError("fresh stable quiescence/closed-endpoint proof missing; stock restore pending")


def reader_ready(device, row, *, sleep=time.sleep):
    for _ in range(16):
        gate = device.private(GATES)
        if gate and gate.get("readerSession") == row["readerSession"]:
            now = device.elapsed()
            fresh_tuple(gate, "denza-adas-gates-v1", row["bootId"], row["consumerSession"], now, "completedAtElapsedMs")
            integer(gate.get("sequence"), "reader sequence", 1)
            began = integer(gate.get("startedAtElapsedMs"), "reader batch start")
            if not 0 <= gate["completedAtElapsedMs"] - began <= READER_MAX_AGE_MS or not 0 <= now - began <= READER_MAX_AGE_MS:
                raise SessionError("reader batch times invalid")
            values = gate.get("values")
            if not isinstance(values, dict):
                raise SessionError("reader has no integer values")
            for fid in REQUIRED_FIDS:
                value = values.get(str(fid), {})
                if value.get("status") != "ok" or value.get("transact") != 5:
                    raise SessionError("reader required integer unavailable")
                integer(value.get("value"), "reader integer", 0, 2**31 - 1)
            isolation = device.private(ISOLATION)
            if not isolation or isolation.get("readerSession") != row["readerSession"]:
                sleep(0.25); continue
            now = device.elapsed()
            fresh_tuple(isolation, "denza-adas-stock-isolation-v1", row["bootId"], row["consumerSession"],
                        now, "completedAtElapsedMs")
            start = integer(isolation.get("startedAtElapsedMs"), "stock isolation start")
            if (isolation.get("stockAbsent") is not True or not 0 <= isolation["completedAtElapsedMs"] - start <= READER_MAX_AGE_MS
                    or not 0 <= now - start <= READER_MAX_AGE_MS):
                raise SessionError("reader independent stock absence proof invalid")
            return gate
        sleep(0.25)
    raise SessionError("same-session reader snapshot not established")


def arm_accepted(device, row, *, sleep=time.sleep):
    for _ in range(16):
        state = device.private(STATE)
        if state:
            fresh_tuple(state, "denza-adas-live-state-v1", row["bootId"], row["consumerSession"], device.elapsed())
            if state.get("armId") == row["armId"] and state["elapsedMs"] >= row["arm"]["issuedAtElapsedMs"]:
                if state.get("state") in ("STOPPED", "BLOCKED"):
                    raise SessionError("runtime rejected/stopped this arm")
                if state.get("state") in ("WAITING", "LIVE") and state.get("quiescent") is False:
                    return state
        sleep(0.25)
    raise SessionError("fresh runtime acceptance of this exact armId not established")


def arm(device, expected, jar, jar_sha, lifetime, ledger_path, *, stationary_confirmed=False, sleep=time.sleep):
    integer(lifetime, "arm lifetime", 1000, MAX_LIFETIME_MS)
    checked_sha(jar_sha)
    if not stationary_confirmed:
        raise SessionError("explicit --stationary-confirmed human confirmation is required")
    if sha256_file(jar) != jar_sha:
        raise SessionError("local reader JAR hash mismatch")
    if Path(ledger_path).exists():
        raise SessionError("rollback ledger already exists; never overwrite a previous session")
    boot = device.boot(); expected.verify(device.app(PACKAGE))
    stationary = device.stationary()
    consumer = consumer_now(device, boot)
    if device.private(ARM) is not None:
        raise SessionError("existing arm file; restore its own session first")
    initial = quiescent(device, boot, consumer)
    stock = device.app(STOCK)
    if stock["enabledState"] not in (0, 1, 3):
        raise SessionError("original stock state not proven shell-restorable")
    row = {"schema": "denza-adas-live-rollback-v1", "serial": device.serial, "bootId": boot,
           "bootIdentitySource": "Settings.Global.BOOT_COUNT; Java nameUUIDFromBytes UTF8 denza-adas-boot-count-v1:<decimal>",
           "consumerSession": consumer, "readerSession": str(uuid.uuid4()), "armId": str(uuid.uuid4()),
           "apk": {"sha256": expected.sha256, "versionCode": expected.version_code, "versionName": expected.version_name},
           "stockPackage": STOCK, "originalStock": stock, "stockPidsBefore": device.stock_pids(),
           "initialQuiescence": initial, "stationary": stationary, "lifetimeMs": lifetime,
           "readerJarSha256": jar_sha, "phase": "ROLLBACK_RECORDED", "armWritten": False}
    save_ledger(ledger_path, row, create=True)  # Must precede the first package or device-file mutation.
    try:
        device.stock_enabled(3); row["phase"] = "STOCK_DISABLED"; save_ledger(ledger_path, row)
        device.stock_stop()
        row["reader"] = device.reader(jar, jar_sha, consumer, row["readerSession"], lifetime)
        row["phase"] = "READER_STARTED"; save_ledger(ledger_path, row)
        reader_ready(device, row, sleep=sleep)
        consumer_now(device, boot, consumer)
        if device.boot() != boot:
            raise SessionError("boot changed during setup")
        expected.verify(device.app(PACKAGE)); device.stationary()
        if device.app(STOCK)["enabledState"] != 3 or device.stock_pids():
            raise SessionError("stock isolation no longer established")
        issued = device.elapsed()
        record = {"schema": "denza-adas-live-arm-v1", "bootId": boot, "consumerSession": consumer,
                  "readerSession": row["readerSession"], "armId": row["armId"], "issuedAtElapsedMs": issued,
                  "expiresAtElapsedMs": issued + lifetime, "stockPackage": STOCK, "stockDisabled": True}
        # Unknown outcome after write must take the normal disarm/closure-proof rollback path.
        row["phase"] = "ARM_WRITE_PENDING"; row["armWritten"] = True; row["arm"] = record; save_ledger(ledger_path, row)
        device.write_private(ARM, record)
        consumer_now(device, boot, consumer)
        row["acceptance"] = arm_accepted(device, row, sleep=sleep)
        row["phase"] = "ARMED"; save_ledger(ledger_path, row)
        return row
    except Exception as failed:
        row["setupError"] = type(failed).__name__ + ": " + str(failed)[:300]
        save_ledger(ledger_path, row)
        try:
            restore(device, ledger_path, sleep=sleep)
        except Exception as rollback:
            row["phase"] = "ROLLBACK_PENDING"; row["rollbackError"] = str(rollback)[:300]; save_ledger(ledger_path, row)
            raise SessionError("setup failed; arm removed when possible, safe stock restore still pending: " + str(rollback)) from failed
        raise SessionError("setup failed; disarmed and original stock enabled state restored: " + str(failed)) from failed


def restore(device, ledger_path, *, sleep=time.sleep):
    path = Path(ledger_path)
    if path.stat().st_size > 65536:
        raise SessionError("ledger cap")
    try:
        row = json.loads(path.read_text(), object_pairs_hook=unique_keys, parse_constant=nonfinite_json)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise SessionError("invalid rollback ledger JSON") from error
    if not isinstance(row, dict) or not isinstance(row.get("originalStock"), dict):
        raise SessionError("invalid rollback ledger object")
    if row.get("schema") != "denza-adas-live-rollback-v1" or row.get("serial") != device.serial or row.get("stockPackage") != STOCK:
        raise SessionError("ledger ownership mismatch")
    for key in ("bootId", "consumerSession", "readerSession", "armId"):
        canonical_uuid(row.get(key))
    original = integer(row.get("originalStock", {}).get("enabledState"), "original stock state", 0, 4)
    if original not in (0, 1, 3):
        raise SessionError("original state not shell-restorable")
    active = device.private(ARM)
    if active is not None and any(active.get(k) != row[k] for k in ("bootId", "consumerSession", "readerSession", "armId")):
        raise SessionError("another session owns the current arm; refusing to remove it")
    try:
        device.remove_arm()  # Always first device mutation. No package restore before proven egress closure.
        row["phase"] = "DISARMED"; save_ledger(path, row)
        device.write_private(STOP, {"schema": "denza-adas-reader-stop-v1", "bootId": row["bootId"],
                                   "consumerSession": row["consumerSession"], "readerSession": row["readerSession"]})
        if device.boot() != row["bootId"]:
            raise SessionError("boot changed; independent Binder-death cleanup proof required")
        row["quiescence"] = quiescent(device, row["bootId"], row["consumerSession"], stable=True, sleep=sleep)
        actual = device.app(STOCK)
        if any(actual[key] != row["originalStock"][key] for key in ("sha256", "versionCode", "versionName")):
            raise SessionError("stock APK identity changed; original-state restore requires review")
        device.stock_enabled(original)
    except Exception as error:
        row["phase"] = "ROLLBACK_PENDING"; row["rollbackError"] = str(error)[:300]; save_ledger(path, row)
        raise
    row["phase"] = "RESTORED"; save_ledger(path, row)
    return row


def build_reader(output, java_home, android_sdk):
    output = Path(output).resolve(); output.mkdir(parents=True, exist_ok=False)
    java, sdk = Path(java_home), Path(android_sdk)
    android = sdk / "platforms/android-35/android.jar"
    d8 = sdk / "build-tools/36.0.0/d8"
    sources = [ROOT / "tools/adas_bridge/AdasGateReader.java", *(ROOT / "libraries/adas-link/src/main/java/dev/denza/adaslink" / name for name in ("Json.java", "AdasGateSnapshot.java", "BootIdentity.java"))]
    with tempfile.TemporaryDirectory(prefix="reader-build-", dir=output) as work:
        work = Path(work); classes = work / "classes"; classes.mkdir(); dex = work / "dex"; dex.mkdir()
        commands = [[java / "bin/javac", "--release", "8", "-encoding", "UTF-8", "-cp", android, "-d", classes, *sources],
                    [java / "bin/jar", "cf", work / "classes.jar", "-C", classes, "."],
                    [d8, "--min-api", "26", "--lib", android, "--output", dex, work / "classes.jar"]]
        for index, command in enumerate(commands):
            result = subprocess.run(list(map(str, command)), cwd=ROOT, capture_output=True, timeout=60, check=False)
            (output / ("build-" + str(index) + ".log")).write_bytes(result.stdout + result.stderr)
            if result.returncode:
                raise SessionError("reader local build failed; see build logs")
        jar = output / "adas-gate-reader.dex.jar"
        with zipfile.ZipFile(jar, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.write(dex / "classes.dex", "classes.dex")
    manifest = {"schema": "denza-adas-reader-build-v1", "jar": str(jar), "sha256": sha256_file(jar),
                "sources": {str(p.relative_to(ROOT)): sha256_file(p) for p in sources}, "minApi": 26,
                "localOnly": True, "deviceCalls": 0}
    (output / "build.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    build = commands.add_parser("build-reader")
    build.add_argument("--out", type=Path, required=True)
    build.add_argument("--java-home", default="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home")
    build.add_argument("--android-sdk", default="/opt/homebrew/share/android-commandlinetools")
    for name in ("plan", "arm", "restore"):
        command = commands.add_parser(name)
        command.add_argument("--adb", default="/opt/homebrew/share/android-commandlinetools/platform-tools/adb")
        command.add_argument("--serial", required=True)
        command.add_argument("--ledger", type=Path, required=True)
        if name != "restore":
            command.add_argument("--apk-sha256", required=True)
            command.add_argument("--version-code", required=True, type=int)
            command.add_argument("--version-name", required=True)
            command.add_argument("--reader-jar", required=True, type=Path)
            command.add_argument("--reader-sha256", required=True)
            command.add_argument("--lifetime-ms", required=True, type=int)
            command.add_argument("--stationary-confirmed", action="store_true")
            command.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    try:
        if args.command == "build-reader":
            result = build_reader(args.out, args.java_home, args.android_sdk)
        elif args.command == "restore":
            result = restore(Device(args.adb, args.serial), args.ledger)
        else:
            validate_serial(args.serial)
            expected = ExpectedApk(args.apk_sha256, args.version_code, args.version_name)
            integer(args.lifetime_ms, "lifetime", 1000, MAX_LIFETIME_MS); checked_sha(args.reader_sha256)
            if sha256_file(args.reader_jar) != args.reader_sha256:
                raise SessionError("reader JAR hash mismatch")
            if args.command == "plan" or args.dry_run:
                result = {"schema": "denza-adas-live-plan-v1", "deviceCalls": 0, "serial": args.serial,
                          "stockPackage": STOCK, "apk": expected.__dict__, "ledger": str(args.ledger),
                          "readerJar": str(args.reader_jar), "readerSha256": args.reader_sha256,
                          "lifetimeMs": args.lifetime_ms,
                          "stationaryConfirmationRequired": True,
                          "steps": ["Verify exact APK SHA/version, boot and fresh consumer", "Require human stationary confirmation, legacy park raw1 and FLOAT speed0",
                                    "Require no existing arm and fresh quiescent/closed runtime", "Durably record original stock enabled state and identity",
                                    "disable-user state3; force-stop; verify all stock/stock: PIDs absent", "Start own bounded reader; verify same-session snapshot",
                                    "Recheck identities/stationarity/isolation; write nine-field atomic arm", "On failure disarm + stop own reader; restore stock only after stable closure proof"],
                          "limits": ["No independent selector-P proof", "No process-absence/Binder-cleanup restore fallback; missing closure proof leaves explicit rollback pending"]}
            else:
                result = arm(Device(args.adb, args.serial), expected, args.reader_jar, args.reader_sha256, args.lifetime_ms,
                             args.ledger, stationary_confirmed=args.stationary_confirmed)
        print(json.dumps(result, indent=2, allow_nan=False)); return 0
    except (SessionError, OSError, subprocess.SubprocessError) as error:
        parser.exit(2, str(error) + "\n")


if __name__ == "__main__":
    raise SystemExit(main())
