#!/usr/bin/env python3
"""Prepare bounded A/B RoadInfo replays from an owner's BYD HUD trace; never connects to a car."""
import argparse
import base64
import hashlib
import json
from pathlib import Path


def varint(value):
    out = bytearray()
    while value > 127:
        out.append((value & 127) | 128)
        value >>= 7
    out.append(value)
    return bytes(out)


def fields(data):
    offset = 0

    def read():
        nonlocal offset
        result = shift = 0
        while True:
            byte = data[offset]
            offset += 1
            result |= (byte & 127) << shift
            if byte < 128:
                return result
            shift += 7
            if shift > 63:
                raise ValueError("oversized varint")

    result = []
    while offset < len(data):
        start = offset
        tag = read()
        number, wire = tag >> 3, tag & 7
        if wire == 0:
            value = read()
        elif wire in (1, 5):
            size = 8 if wire == 1 else 4
            value = data[offset:offset + size]
            offset += size
        elif wire == 2:
            size = read()
            value = data[offset:offset + size]
            offset += size
        else:
            raise ValueError("unsupported wire type")
        if offset > len(data):
            raise ValueError("truncated field")
        result.append((number, wire, value, data[start:offset]))
    return result


def road_fields(payload):
    outer = fields(payload)
    if len(outer) != 1 or outer[0][:2] != (1, 2):
        raise ValueError("not a RoadInfo envelope")
    return fields(outer[0][2])


def counter(payload, value):
    original = road_fields(payload)
    if sum(number == 2 and wire == 0 for number, wire, _, _ in original) != 1:
        raise ValueError("expected one counter")
    inner = b"".join(b"\x10" + varint(value) if n == 2 else raw for n, w, v, raw in original)
    encoded = b"\x0a" + varint(len(inner)) + inner
    # A/B must alter only field 2, never geometry, distance, images or navigation state.
    assert [f for f in road_fields(encoded) if f[0] != 2] == [f for f in original if f[0] != 2]
    return encoded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--denza-apps-reference", action="store_true", help="Rebuild the recorded geometry using the actual Denza Apps encoder, at 350 ms")
    parser.add_argument("--start", required=True, help="Local timestamp prefix, YYYY-MM-DDTHH:MM:SS")
    parser.add_argument("--duration-ms", type=int, default=25000)
    parser.add_argument("--interval-ms", type=int, default=50)
    args = parser.parse_args()
    if args.denza_apps_reference:
        args.interval_ms = 350
    if not 1000 <= args.duration_ms <= 60000 or not 40 <= args.interval_ms <= 1000:
        parser.error("duration must be 1..60 s; interval 40..1000 ms")
    rows = [json.loads(line) for line in args.trace.read_text().splitlines()]
    rows = [r for r in rows if r.get("event") == "send" and r.get("kind") == "payload"
            and r.get("topic") == "0x0004010a00018001" and "payloadBase64" in r]
    first = next(i for i, r in enumerate(rows) if r["localTs"].startswith(args.start))
    rows = rows[first:]
    start = rows[0]["t"]
    if rows[-1]["t"] < start + args.duration_ms:
        raise ValueError("trace does not cover requested duration")
    source_counter = next(f[2] for f in road_fields(base64.b64decode(rows[0]["payloadBase64"])) if f[0] == 2)
    args.output.mkdir(parents=True, exist_ok=True)
    outputs = {"rolling": [], "stock": []}
    cursor = 0
    for index, at in enumerate(range(0, args.duration_ms + 1, args.interval_ms)):
        while cursor + 1 < len(rows) and rows[cursor + 1]["t"] <= start + at:
            cursor += 1
        payload = base64.b64decode(rows[cursor]["payloadBase64"], validate=True)
        for mode, value in (("rolling", (source_counter + index) & 255), ("stock", 2)):
            outputs[mode].append(json.dumps({"atMs": at,
                "payloadBase64": base64.b64encode(counter(payload, value)).decode()}))
    if args.denza_apps_reference:
        from denza_packet_reference import encode_recorded_packets, SOURCE
        recorded = [json.loads(line) for line in outputs["stock"]]
        packets = encode_recorded_packets([row["payloadBase64"] for row in recorded])
        outputs = {"denza-apps": [json.dumps({"atMs": row["atMs"], "payloadBase64": payload})
                   for row, payload in zip(recorded, packets)]}
    report = {"sourceSha256": hashlib.sha256(args.trace.read_bytes()).hexdigest(),
              "start": rows[0]["localTs"], "durationMs": args.duration_ms,
              "intervalMs": args.interval_ms, "frames": len(next(iter(outputs.values())))}
    if args.denza_apps_reference:
        report["referenceEncoderSha256"] = hashlib.sha256(SOURCE.read_bytes()).hexdigest()
    for mode, lines in outputs.items():
        path = args.output / (mode + ".jsonl")
        path.write_text("\n".join(lines) + "\n")
        report[mode + "Sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
    (args.output / "manifest.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
