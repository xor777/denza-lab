#!/usr/bin/env python3
"""Decode ObdAddressReadProbe output (probe.txt) into requests, replies and standard OBD values.

python3 research/vehicle-diagnostics/decode_obd_probe.py captures/diagnostics-obd-20261020/run2/probe.txt

Only the single-frame replies these runs produced are decoded; a multi-frame reply is
reported as such. OBD formulas are SAE J1979 Mode 01; nothing BYD-specific is assumed.
"""
import json
import re
import sys

RESULT = {1: "ok", 2: "negative", 3: "response timeout", 4: "diagnostic timeout", 5: "busy"}
NRC = {0x10: "general reject", 0x11: "service not supported", 0x12: "subfunction not supported",
       0x13: "incorrect length or format", 0x22: "conditions not correct", 0x31: "request out of range"}


def pid_value(pid, d):
    if pid in (0x00, 0x20, 0x40, 0x60):
        base = pid
        bits = int.from_bytes(bytes(d[:4]), "big")
        return [f"{base + i + 1:02X}" for i in range(32) if bits & (1 << (31 - i))]
    if pid == 0x01:
        return dict(mil=bool(d[0] & 0x80), dtc_count=d[0] & 0x7F, compression=bool(d[1] & 0x08),
                    raw=" ".join(f"{x:02X}" for x in d[:4]))
    one = {0x04: lambda a: round(a * 100 / 255, 1), 0x05: lambda a: a - 40, 0x06: lambda a: round((a - 128) * 100 / 128, 1),
           0x07: lambda a: round((a - 128) * 100 / 128, 1), 0x0B: lambda a: a, 0x0D: lambda a: a,
           0x0E: lambda a: a / 2 - 64, 0x0F: lambda a: a - 40, 0x11: lambda a: round(a * 100 / 255, 1),
           0x13: lambda a: f"{a:08b}", 0x1C: lambda a: a, 0x03: lambda a: a}
    if pid == 0x0C:
        return (d[0] * 256 + d[1]) / 4
    if pid == 0x1F:
        return d[0] * 256 + d[1]
    if pid == 0x15:
        return dict(volts=d[0] / 200, trim=None if d[1] == 0xFF else round((d[1] - 128) * 100 / 128, 1))
    return one[pid](d[0]) if pid in one else " ".join(f"{x:02X}" for x in d)


def dtc(b):
    return "PCBU"[b[0] >> 6] + str((b[0] >> 4) & 3) + f"{b[0] & 15:X}{b[1]:02X}-{b[2]:02X}"


def decode(path):
    lines = open(path).read().splitlines()
    requests = {int(m.group(1)): m.group(2) for m in (re.match(r"REQUEST step=(\d+) (\w+)", l) for l in lines) if m}
    frames = {}
    for line in lines:
        m = re.match(r"FRAME step=(\d+) id=(\w+) total=(\d+) index=(\d+) raw=(\w+)", line)
        if m:
            frames.setdefault(int(m.group(1)), []).append((int(m.group(3)), int(m.group(4)), bytes.fromhex(m.group(5))))
    out = []
    for step, req in sorted(requests.items()):
        r = bytes.fromhex(req)
        row = dict(step=step, request=f"{r[1]:02X}{r[2]:02X}", uds=r[4:].hex().upper())
        got = frames.get(step)
        if not got:
            row["reply"] = "none in 4 s"
        else:
            got = sorted(got, key=lambda f: f[1])
            if [f[1] for f in got] != list(range(1, got[0][0] + 1)):
                row["reply"] = "incomplete multi-frame"
                out.append(row)
                continue
            b = got[0][2]
            row["result"] = RESULT.get(b[6], b[6])
            # First frame: payload from byte 7; a continuation's from byte 6, each to its declared length.
            u = b[7:7 + b[3]] + b"".join(f[2][6:6 + f[2][3]] for f in got[1:])
            if len(got) > 1:
                row["frames"] = len(got)
            row["payload"] = u.hex().upper()
            if b[6] == 2 and len(u) >= 3:
                row["nrc"] = NRC.get(u[2], f"{u[2]:02X}")
            elif u and u[0] == 0x41:
                row["pid"] = f"{u[1]:02X}"
                row["value"] = pid_value(u[1], list(u[2:]))
            elif u and u[0] in (0x43, 0x47, 0x4A):
                row["codes"] = u[1]
            elif u[:2] == b"\x59\x01":
                row["mask"], row["format"], row["count"] = f"{u[2]:02X}", u[3], u[4] * 256 + u[5]
            elif u[:2] == b"\x59\x02":
                row["records"] = [dict(code=dtc(u[i:i + 3]), status=f"{u[i + 3]:02X}") for i in range(3, len(u) - 3, 4)]
            elif u[:1] == b"\x62":
                text = u[3:].decode("ascii", "replace")
                row["did"] = f"{u[1]:02X}{u[2]:02X}"
                row["value"] = text.strip() if text.isprintable() else u[3:].hex().upper()
        out.append(row)
    return out


if __name__ == "__main__":
    print(json.dumps(decode(sys.argv[1]), indent=1, ensure_ascii=False))
