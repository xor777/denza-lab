#!/usr/bin/env python3
"""Single explicitly approved diagnostic 512 upload after accepted 220 login.

Uses a recent passive snapshot. Only missing 0x417 may use stock initial zeros,
and only with --allow-missing-0417. Charging override uses the stock getter.
No remote commands are executed. No automatic retry or persistent session.
"""
import argparse
import datetime as dt
import json
from pathlib import Path
import re
import struct
from registration_probe import ROOT, run
from tls_identity_probe import adb_read, DEFAULT_BINARY
from status512_body import build_body
from inspect_status512 import inspect_body
from cloud_identity import load_identity


def assemble(serial, capture, allow_missing, report):
    table = json.loads((ROOT/"captures/telematics-20260923/battery-upload/vehicle-condition-can-tables.json").read_text())["canfd"]
    text = capture.read_text()
    if "UNREGISTERED" not in text or not re.search(r'DONE received=\d+ dropped=0 callback_errors=0 queued=0', text):
        raise ValueError("Incomplete passive capture")
    frames = []
    for match in re.finditer(r't_ns=(\d+) id=0x([0-9A-F]+).*?payload=([0-9a-f]+)', text):
        frames.append((int(match[1]), int(match[2], 16), bytes.fromhex(match[3])))
    now_ns = float(adb_read(serial, ["shell", "cat", "/proc/uptime"]).split()[0]) * 1e9
    cache, missing, ages = [], [], []
    for entry in table:
        cid, group = int(entry["can_id"], 16), entry["group"]
        found = [f for f in frames if f[1] == cid and ((f[2][0] & 15) == group if cid == 0x3cd else group == 0 or f[2][0] == group)]
        if not found:
            if not (allow_missing and cid == 0x417 and group == 0):
                raise ValueError("Missing unapproved cache entry")
            cache.append(bytes(8)); missing.append(entry["index"])
        else:
            timestamp, _, data = found[-1]
            age = (now_ns - timestamp) / 1e9
            if not 0 <= age <= 20 or len(data) < 8:
                raise ValueError("Stale/invalid passive cache entry")
            ages.append(age); cache.append(data[:8])
    charging_reply = adb_read(serial, ["shell", "service", "call", "autoservice", "5", "i32", "1009", "i32", str(0x34400018)])
    charging_match = re.search(r'Parcel\(\s*00000000\s+([0-9a-f]{8})', charging_reply)
    if not charging_match or int(charging_match[1], 16) > 255:
        raise ValueError("Charging override getter unavailable")
    charging = int(charging_match[1], 16)
    body = build_body(cache, charging_override=charging)
    value = inspect_body(body)
    reply = adb_read(serial, ["shell", "service", "call", "autoservice", "7", "i32", "1014", "i32", str(0x4a505038)])
    match = re.search(r'Parcel\(\s*00000000\s+([0-9a-f]{8})', reply)
    if not match:
        raise ValueError("SOC getter unavailable")
    soc = struct.unpack(">f", bytes.fromhex(match[1]))[0]
    if value["soc_percent"] is None or abs(value["soc_percent"]-soc) > 0.2:
        raise ValueError("SOC sources disagree")
    report["status_body"] = {"length": len(body), "soc_percent": value["soc_percent"], "getter_soc_percent": soc,
        "oldest_field_seconds": round(max(ages), 3), "measured_entries": 25-len(missing),
        "missing_entries_using_stock_initial_zero": missing, "body_byte_102_unmeasured": bool(missing),
        "charging_state_source": "stock getInt device 1009 FID 0x34400018, matching callback 682e0 -> 5df50", "charging_override": charging, "charging_byte_98": body[98]}
    # Persist the state body, including any explicitly approved missing byte;
    # identity/key/UUID fields are absent from this unframed body.
    capture.with_suffix(".body512.bin").write_bytes(body)
    return body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--allow-missing-0417", action="store_true")
    parser.add_argument("--serial", default="127.0.0.1:5555")
    parser.add_argument("--binary", type=Path, default=DEFAULT_BINARY)
    parser.add_argument("--identity-file", type=Path,
                        help="Owner-only original SIM pair used for login; no modem writes")
    args = parser.parse_args()
    report = {"mode": "execute" if args.execute else "preview", "cloud_contacted": False, "telemetry_sent": False,
        "identity_source": "provided_original_pair" if args.identity_file else "modem",
        "official_phone_update_verified": False, "endpoint": "dilinknat0-cn.denzacloud.com:6041"}
    if args.execute:
        report["started_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
        try:
            identity = load_identity(args.identity_file) if args.identity_file else None
            body = assemble(args.serial, args.capture, args.allow_missing_0417, report)
            run(args.serial, args.binary, report, login=True, status_body=body, identity_override=identity)
        except Exception as error:
            report.update({"failed": True, "error_type": type(error).__name__, "instruction": "Stop; no automatic retry"})
        report["finished_utc"] = dt.datetime.now(dt.timezone.utc).isoformat()
    else:
        report["proposal"] = "Validate recent passive snapshot and independent SOC; one TLS/220 login; only on accepted login send one 512 frame; hold 5 seconds then close"
    print(json.dumps(report, indent=2))
    if args.execute and not report.get("telemetry_sent"):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
