#!/usr/bin/env python3
"""Recover complete ECU objects from a saved, possibly truncated OTA log line.

Offline only. The exact status/ecuList prefix is required; an incomplete object
is never repaired. Only source configuration is retained, never installed/DTC
coverage. CAN and Ethernet address semantics remain separate.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re


LINE = re.compile(r"^(?P<time>\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})\s+"
                  r"(?P<pid>\d+)\s+(?P<tid>\d+)\s+I "
                  r"OTA_GCY_UPGRADE_HttpApiService:\s+.*?responseStr :(?P<body>.*)$")
PREFIX = re.compile(r'^\s*\{\s*"status"\s*:\s*"100"\s*,\s*"ecuList"\s*:\s*\[')
FIELDS = ("ecuId", "ecuDiaId", "ecuReprogramType", "ecuChannel", "networkNumber",
          "logicAddress", "ecuDId", "ecuHardDid", "ecuConfigDid", "seq")
IDENTITY = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")
PAIR = re.compile(r"([0-9A-Fa-f]{1,4})/([0-9A-Fa-f]{1,4})\Z")


def pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


DECODER = json.JSONDecoder(object_pairs_hook=pairs)


def project(row):
    if type(row) is not dict or type(row.get("ecuId")) is not str or not IDENTITY.fullmatch(row["ecuId"]):
        raise ValueError("invalid ECU identity")
    result = {}
    for key in FIELDS:
        if key in row:
            value = row[key]
            if value is not None and (type(value) is not str or len(value) > 128):
                raise ValueError("invalid ECU field")
            result[key] = value
    route = PAIR.fullmatch(row.get("ecuDiaId", "") or "")
    transport = row.get("ecuReprogramType")
    if not route:
        raise ValueError("missing or invalid route")
    left, right = (int(x, 16) for x in route.groups())
    if transport in ("can", "canfd"):
        # This inspected firmware path packs two-byte identifiers. Do not
        # silently interpret 29-bit IDs or derive a response using +8.
        result["route"] = {"kind": "can", "requestId": left, "responseId": right,
                           "physicalBus": None}
    elif transport in ("ethernet", "doip"):
        logical = row.get("logicAddress")
        if logical and not re.fullmatch(r"[0-9A-Fa-f]{1,4}", logical):
            raise ValueError("invalid logical address")
        result["route"] = {"kind": "ethernet", "pairTarget": left, "pairClient": right,
                           "target": int(logical, 16) if logical else left}
    else:
        raise ValueError("unsupported transport")
    result["evidence"] = "server_candidate_only"
    result["eligibleForProductAllowlist"] = False
    return result


def extract_prefix(body):
    match = PREFIX.match(body)
    if not match:
        return None
    offset = match.end()
    rows = []
    need_row = False
    while True:
        offset += len(body[offset:]) - len(body[offset:].lstrip())
        if offset == len(body):
            return rows, "incomplete_tail"
        if body[offset] == "]":
            if need_row:
                return rows, "invalid_tail"
            # A closed array is not proof that the rest of the response exists.
            try:
                complete = json.loads(body, object_pairs_hook=pairs)
                if type(complete) is not dict or complete.get("status") != "100":
                    raise ValueError()
            except ValueError:
                return rows, "array_complete_response_incomplete"
            return rows, "complete_response"
        if body[offset] != "{":
            return rows, "invalid_tail"
        try:
            row, end = DECODER.raw_decode(body, offset)
        except json.JSONDecodeError:
            return rows, "incomplete_or_invalid_object"
        except ValueError:
            return rows, "invalid_object"
        try:
            projected = project(row)
        except (ValueError, TypeError):
            return rows, "invalid_object"
        # Require a delimiter. A truncated property after a valid-looking
        # object must not turn it into an accepted array member.
        tail = end + len(body[end:]) - len(body[end:].lstrip())
        if tail == len(body):
            return rows, "incomplete_tail"
        if body[tail] not in ",]":
            return rows, "invalid_tail"
        rows.append(projected)
        offset = tail + 1 if body[tail] == "," else tail
        need_row = body[tail] == ","


def extract(raw, source_name):
    responses = []
    for number, line in enumerate(raw.decode("utf-8", "strict").splitlines(), 1):
        match = LINE.fullmatch(line)
        if not match:
            continue
        parsed = extract_prefix(match["body"])
        if parsed is None:
            continue
        rows, state = parsed
        responses.append({"line": number, "time": match["time"],
                          "pid": int(match["pid"]), "tid": int(match["tid"]),
                          "responseState": state, "completeObjects": len(rows), "ecus": rows})
    return {"schemaVersion": 1, "source": source_name,
            "sourceSha256": hashlib.sha256(raw).hexdigest(), "profile": None,
            "applicability": "unverified", "eligibleForProductAllowlist": False,
            "responses": responses}


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("input", type=Path)
    ap.add_argument("--output", type=Path, required=True)
    args = ap.parse_args()
    raw = args.input.read_bytes()
    if len(raw) > 16 * 1024 * 1024:
        raise SystemExit("Saved log exceeds 16 MiB bound")
    report = extract(raw, str(args.input))
    with args.output.open("x") as stream:
        json.dump(report, stream, indent=2)
        stream.write("\n")
    print(json.dumps({"responses": len(report["responses"]),
                      "completeObjects": sum(r["completeObjects"] for r in report["responses"])}))


if __name__ == "__main__":
    main()
