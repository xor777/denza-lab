#!/usr/bin/env python3
"""Decode captured identity reads using the OEM ADS screen and OTA rules.

Offline only. Reject incomplete/mismatched results; retain unused bytes as raw.
Version strings do not identify a manufacturer or establish a calibration value.
"""
import argparse
import hashlib
import json
import re
from pathlib import Path

FIELDS = {
    "software": (0xF195, "ADS software", 2, "software"),
    "hardware": (0xF193, "ADS hardware", 1, "hardware"),
    "soc-app": (0x01A0, "SoC application string", 31, "ascii"),
    "soc": (0x0178, "Host SoC software numeric ID", 2, "number"),
    "mcu": (0x0170, "Host MCU software numeric ID", 2, "number"),
    "mcu-app": (0x0172, "Host MCU application numeric ID", 5, "number"),
    "bootloader": (0xF180, "ADS bootloader", 1, "bootloader"),
}
ORIGINAL_FIELDS = tuple(FIELDS)
FIELDS.update({
    "software-code": (0xF194, "ADS software code", 9, "hex"),
    "config-version": (0xFF95, "ADS configuration version", 2, "software"),
    "config-code": (0xFF94, "ADS configuration code", 9, "hex"),
})


def decode(name, path):
    did, label, width, kind = FIELDS[name]
    text = path.read_text()
    request = bytes((5, 7, 0x12, 3, 0x22, did >> 8, did & 255)).hex().upper()
    if re.findall(r"^REQUEST (\w+)$", text, re.M) != [request]:
        raise ValueError("Expected exactly one qualified request: " + name)
    if "TRANSPORT_RESULT 0\n" not in text or "UNREGISTERED\n" not in text:
        raise ValueError("Missing transport/cleanup evidence: " + name)
    if text.count("STATIONARY gear=1 park_brake=1 speed=0.0\n") != 2:
        raise ValueError("Missing stationary preflight: " + name)
    responses = re.findall(r"^RESPONSE status=(\d+) payload=([0-9A-F]*)$", text, re.M)
    if len(responses) != 1:
        raise ValueError("Expected one response: " + name)
    status, payload_hex = responses[0]
    payload = bytes.fromhex(payload_hex)
    result = {"name": name, "label": label, "did": "%04X" % did, "request": request,
              "response_status": int(status), "response_payload": payload_hex,
              "capture_sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    if status == "2" and payload == bytes((0x7f, 0x22, 0x31)):
        if "END NONPOSITIVE_OR_MISMATCHED_RESPONSE frames=1\n" not in text:
            raise ValueError("Negative response termination missing")
        result.update({"state": "negative", "nrc": "31", "meaning": "Request out of range / identifier not supported"})
        return result
    if status != "1" or payload[:3] != bytes((0x62, did >> 8, did & 255)) or "END COMPLETE frames=1\n" not in text:
        raise ValueError("Unqualified response: " + name)
    data = payload[3:]
    if len(data) < width:
        raise ValueError("Truncated OEM value: " + name)
    if "VALUE did=%04X bytes=%s\n" % (did, data.hex().upper()) not in text:
        raise ValueError("Value/transcript mismatch: " + name)
    selected = data[:width]
    number = int.from_bytes(selected, "big")
    if kind == "software":
        value = "%d.%02d.%02d" % (number // 10000, (number // 100) % 100, number % 100)
    elif kind == "hardware":
        value = "%d.%d.%d" % (number // 100, (number % 100) // 10, number % 10)
    elif kind == "bootloader":
        value = "%d.%d" % (number // 10, number % 10)
    elif kind == "number":
        value = str(number)
    elif kind == "hex":
        value = selected.hex().upper()
    else:
        value = selected.decode("ascii")
    result.update({"state": "complete", "oem_display_value": value,
                   "raw_value": data.hex().upper(), "oem_used_bytes": width,
                   "uninterpreted_tail": data[width:].hex().upper()})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("captures", type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--fields", nargs="+", choices=tuple(FIELDS), default=ORIGINAL_FIELDS)
    args = parser.parse_args()
    results = [decode(name, args.captures / (name + ".txt")) for name in args.fields]
    report = {"date": "2026-10-18", "profile": 170, "request_id": "0712", "response_id": "071A",
              "owner": 5, "results": results, "complete_count": sum(x["state"] == "complete" for x in results),
              "negative_count": sum(x["state"] == "negative" for x in results),
              "limitation": "Identification only. No speed-ceiling setting, ECU code, calibration or export comparison recovered."}
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({x["name"]: x.get("oem_display_value", "NRC " + x.get("nrc", "unknown")) for x in results}, indent=2))


if __name__ == "__main__":
    main()
