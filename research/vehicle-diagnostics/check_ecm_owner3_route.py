#!/usr/bin/env python3
"""Replay source-bounded owner03 evidence. No car I/O or firmware execution."""
import hashlib
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name("ecm-owner3-route-20261021.json")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def elf_bytes(raw, address, size):
    require(raw[:6] == b"\x7fELF\x02\x01", "ELF64 little endian")
    offset = struct.unpack_from("<Q", raw, 0x28)[0]
    step, count = struct.unpack_from("<HH", raw, 0x3A)
    for index in range(count):
        _, kind, _, start, file_offset, length, *_ = struct.unpack_from("<IIQQQQIIQQ", raw, offset + index * step)
        if kind != 8 and start <= address and address + size <= start + length:
            return raw[file_offset + address - start:file_offset + address - start + size]
    raise AssertionError("unmapped ELF range")


def relocations(raw):
    offset = struct.unpack_from("<Q", raw, 0x28)[0]
    step, count = struct.unpack_from("<HH", raw, 0x3A)
    result = []
    for index in range(count):
        _, kind, _, _, file_offset, length, *_ = struct.unpack_from("<IIQQQQIIQQ", raw, offset + index * step)
        if kind == 4:
            for entry in range(file_offset, file_offset + length, 24):
                address, info, addend = struct.unpack_from("<QQq", raw, entry)
                result.append((address, info & 0xFFFFFFFF, addend))
    return result


def check():
    d = json.loads(REPORT.read_text())
    require(d["offlineOnly"] and d["vehicleOperations"] == d["firmwareExecutions"] == 0, "offline scope")
    require(d["eligibleForLive"] is False and d["liveRequestRecommendation"] is None, "candidate boundary")
    for source in d["sources"]:
        raw = (ROOT / source["path"]).read_bytes()
        require(len(raw) == source["bytes"] and hashlib.sha256(raw).hexdigest() == source["sha256"],
                "source drift: " + source["path"])
    images = {name: (ROOT / entry["path"]).read_bytes() for name, entry in d["images"].items()}
    for name, raw in images.items():
        require(hashlib.sha256(raw).hexdigest() == d["images"][name]["sha256"], "image identity")
    all_rows = {"mcu": {}, "vhds": {}}
    for region in d["ranges"]:
        name, start, end = region["image"], int(region["startVA"], 0), int(region["endExclusiveVA"], 0)
        image = images[name]
        code = image[start - 0x40000:end - 0x40000] if name == "mcu" else elf_bytes(image, start, end - start)
        require(hashlib.sha256(code).hexdigest() == region["sha256"], "native range " + region["name"])
        rows = json.loads((ROOT / region["decodedInstructions"]).read_text())
        cursor = start
        for row in rows:
            address, raw = int(row["va"], 0), bytes.fromhex(row["bytes"])
            require(address == cursor and code[address - start:address - start + len(raw)] == raw,
                    "decoded bytes/continuity")
            require(address not in all_rows[name], "range overlap")
            all_rows[name][address] = row
            cursor += len(raw)
        require(cursor == end and len(rows) == region["instructions"], "range boundary/count")
    for entry in d["anchors"]:
        row = all_rows[entry["image"]][int(entry["va"], 0)]
        require([row["mnemonic"], row["operands"]] == entry["instruction"], "anchor " + entry["va"])
    require((0x493A0, 1027, 0x2A9B0) in relocations(images["vhds"]), "observer vtable callback relocation")
    require(struct.unpack("<I", elf_bytes(images["vhds"], 0x4C1E0, 4))[0] == 0x9900010A,
            "enabled receive FID")
    require(struct.unpack_from("<III", images["mcu"], 0xCD9D8 - 0x40000) ==
            (0xAA00010A, 0x16C840, 0), "MCU input FID dispatch")
    require(struct.unpack_from("<III", images["mcu"], 0xA6488 - 0x40000) ==
            (0x9900010A, 240, 0xFEEFBE31), "shared output descriptor")
    accepted = {3: [], 10: []}
    for length in range(4096):
        for owner in accepted:
            expected = length - (3 if owner == 3 else 1) if length >= (3 if owner == 3 else 1) else (3 if owner == 3 else 1)
            # ADDI(-920) sets unsigned carry precisely for expected >=920;
            # BNC selects the acceptance block after the independent lower gate.
            carry = expected + ((1 << 32) - 920) >= 1 << 32
            allow = expected > (3 if owner == 3 else 6) and not carry
            require(allow == (7 <= length <= 922 if owner == 3 else 8 <= length <= 920), "FF size interval")
            if allow:
                accepted[owner].append(length)
    require(d["firstFrame"]["owner03"]["acceptedTotalUdsInterval"] == [min(accepted[3]), max(accepted[3])], "owner03 bounds")
    require(d["firstFrame"]["otherOwners"]["acceptedTotalUdsInterval"] == [min(accepted[10]), max(accepted[10])], "other bounds")
    max_records = {owner: max(n for n in range(1024) if 3 + 4 * n in accepted[owner]) for owner in accepted}
    require(max_records == {3: 229, 10: 229} and d["sizeDiscriminator"]["maximum19ListRecordsBoth"] == 229,
            "no DTC registry capacity increase")
    require(3 + 4 * 258 == 1035 and 1035 not in accepted[3] and 1035 not in accepted[10], "1035-byte hypothesis rejected by both")
    require(d["sizeDiscriminator"]["bothReject1035"] is True and
            d["sizeDiscriminator"]["owner03ExpectedBodyAt1035"] == 1032 and
            d["sizeDiscriminator"]["otherExpectedBodyAt1035"] == 1034,
            "reported1035-byte projection")
    require(923 not in accepted[3] and 921 in accepted[3] and 922 in accepted[3], "two-byte-only difference")
    for sequence in range(32):
        for received in range(16):
            require((received == (sequence & 15)) == (received == sequence % 16), "CF low-nibble equality")
    for flags in range(16):
        active = bool(flags & 1)
        states = [bool(flags & (1 << index)) for index in (1, 2, 3)]
        require((active or any(states)) == (flags != 0), "shared sequential busy decision")
    require(d["callback"]["independentLane"] is False and d["sizeDiscriminator"]["ecmCauseEstablished"] is False,
            "no isolation or root-cause promotion")
    print(f"PASS {len(d['sources'])} sources; {len(d['ranges'])} ranges/"
          f"{sum(len(rows) for rows in all_rows.values())} instructions; {len(d['anchors'])} anchors; "
          "4096 FF lengths; both19-list limits229; owner03 stock callback/shared arbiter; no live route qualified")


if __name__ == "__main__":
    check()
