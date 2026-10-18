"""Offline decoder for the single-frame OEM DTC responses captured 2026-10-18.

No vehicle access. Multi-frame responses deliberately fail until a real trace
qualifies their assembly. Ignore only bytes after the declared payload length.
Usage: python3 decode_snapshot.py <dtc-count-live.txt> <dtc-list-live.txt> [body|lbms]
"""
import json
from pathlib import Path
import re
import sys

FLAGS = (
    "testFailed", "testFailedThisOperationCycle", "pendingDTC", "confirmedDTC",
    "testNotCompletedSinceLastClear", "testFailedSinceLastClear",
    "testNotCompletedThisOperationCycle", "warningIndicatorRequested",
)


TARGETS = {"body": (0x720, 0x728), "lbms": (0x762, 0x76A)}


def payload(text, subfunction, target="body"):
    request_id, response_id = TARGETS[target]
    frames = [bytes.fromhex(value) for value in re.findall(
        r"^FRAME phase=response raw=([0-9A-Fa-f]+)$", text, re.MULTILINE)]
    unique = list(dict.fromkeys(frames))
    if len(unique) != 1:
        raise ValueError("Need exactly one unique response; empty is not healthy")
    frame = unique[0]
    if len(frame) < 7 or frame[0] != 10 or frame[1:3] != response_id.to_bytes(2, "big"):
        raise ValueError("Wrong owner, address, or short header")
    if frame[4:6] != b"\x01\x01":
        raise ValueError("Multi-frame or invalid sequence is not qualified")
    if frame[6] != 1:
        raise ValueError(f"Diagnostic result {frame[6]} is not success")
    end = 7 + frame[3]
    if end > len(frame):
        raise ValueError("Truncated payload")
    data = frame[7:end]
    if len(data) < 3 or data[:2] != bytes([0x59, subfunction]):
        raise ValueError("Wrong UDS positive service or subfunction")
    return data


def decode(count_text, list_text, target="body"):
    request_id, response_id = TARGETS[target]
    count = payload(count_text, 1, target)
    listed = payload(list_text, 2, target)
    if len(count) != 6 or (len(listed) - 3) % 4:
        raise ValueError("Invalid count/record length")
    if count[2] != listed[2]:
        raise ValueError("Availability mask changed between requests")
    records = []
    for i in range(3, len(listed), 4):
        status = listed[i + 3]
        if status & ~listed[2]:
            raise ValueError("Record uses unsupported status bits")
        records.append({
            "dtc_raw": listed[i:i + 3].hex().upper(),
            "status_raw": f"{status:02X}",
            "status_flags": [name for bit, name in enumerate(FLAGS)
                             if status & (1 << bit)],
        })
    if len(records) != int.from_bytes(count[4:6], "big"):
        raise ValueError("Count/list differ; do not present a complete snapshot")
    if len({r['dtc_raw'] for r in records}) != len(records):
        raise ValueError("Duplicate DTC records")
    return {"request_id": f"0x{request_id:03X}", "response_id": f"0x{response_id:03X}",
            "format_identifier": count[3], "status_availability_mask": f"{count[2]:02X}",
            "read_count": len(records), "entries": records}


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        raise SystemExit(__doc__)
    print(json.dumps(decode(*(Path(p).read_text() for p in sys.argv[1:3]), target=sys.argv[3] if len(sys.argv) == 4 else "body"), indent=2))
