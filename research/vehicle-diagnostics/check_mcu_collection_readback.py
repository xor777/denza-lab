#!/usr/bin/env python3
"""Replay bounded MCU collection-table evidence without a car or decoder runtime."""
import hashlib
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name("mcu-collection-readback-20261021.json")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def relative_candidates(image, targets):
    """Aligned relative JR/JARL encodings, not a whole-image code classification."""
    result = {hex(target): [] for target in targets}
    for offset in range(0, len(image) - 3, 2):
        first, second = struct.unpack_from("<HH", image, offset)
        if first & 0x07C0 != 0x0780 or second & 1:
            continue
        displacement = ((first & 0x3F) << 16) | second
        if displacement & 0x200000:
            displacement -= 0x400000
        target = offset + 0x40000 + displacement
        if target in targets:
            result[hex(target)].append({"va": hex(offset + 0x40000),
                                        "linkRegister": first >> 11})
    return result


def literal_hits(image, value):
    needle, start, result = struct.pack("<I", value), 0, []
    while True:
        offset = image.find(needle, start)
        if offset < 0:
            return result
        result.append(hex(offset + 0x40000))
        start = offset + 1


def check():
    report = json.loads(REPORT.read_text())
    require(report["offlineOnly"] and report["vehicleOperations"] == 0,
            "offline scope")
    require(report["status"] == "no_read_only_mcu_table_readback_qualified_in_traced_paths",
            "bounded conclusion")
    for source in report["sources"]:
        data = (ROOT / source["path"]).read_bytes()
        require(len(data) == source["bytes"] and hashlib.sha256(data).hexdigest() == source["sha256"],
                "source drift: " + source["path"])
    image = (ROOT / report["image"]["path"]).read_bytes()
    require(len(image) == 1933312 and hashlib.sha256(image).hexdigest() == report["image"]["sha256"],
            "MCU image identity")
    instructions = {}
    for region in report["ranges"]:
        start, end = int(region["startVA"], 0), int(region["endExclusiveVA"], 0)
        require(hashlib.sha256(image[start - 0x40000:end - 0x40000]).hexdigest() == region["sha256"],
                "native range " + region["name"])
        rows = json.loads((ROOT / region["decodedInstructions"]).read_text())
        cursor, assembly = start, []
        for row in rows:
            address, raw = int(row["va"], 0), bytes.fromhex(row["bytes"])
            require(address == cursor and image[address - 0x40000:address - 0x40000 + len(raw)] == raw,
                    "instruction continuity/bytes")
            require(address not in instructions, "overlapping evidence ranges")
            instructions[address] = row
            cursor += len(raw)
            assembly.append(f"{address:08x}: {row['bytes']:<12} {row['mnemonic']:10s} {row['operands']}")
        require(cursor == end and len(rows) == region["instructions"], "bounded range end/count")
        require((ROOT / region["assembly"]).read_text() == "\n".join(assembly) + "\n", "assembly replay")
    for anchor in report["anchors"]:
        row = instructions[int(anchor["va"], 0)]
        require([row["mnemonic"], row["operands"]] == anchor["instruction"], "anchor " + anchor["va"])
    targets = [int(value, 0) for value in report["relativeBranchCandidates"]]
    require(relative_candidates(image, targets) == report["relativeBranchCandidates"],
            "relative call/tail-branch candidates")
    for value, hits in report["literalCandidates"].items():
        require(literal_hits(image, int(value, 0)) == hits, "literal candidates " + value)
    for entry in report["inputDispatch"]:
        require(struct.unpack_from("<III", image, int(entry["rowVA"], 0) - 0x40000) ==
                (int(entry["fid"], 0), int(entry["callbackVA"], 0), 0), "input FID dispatch")
    for entry in report["outputDescriptors"]:
        require(struct.unpack_from("<III", image, int(entry["va"], 0) - 0x40000) ==
                (int(entry["fid"], 0), entry["bytes"], int(entry["bufferRAM"], 0)), "output descriptor")
    for entry in report["romStrings"]:
        offset = int(entry["va"], 0) - 0x40000
        expected = entry["text"].encode("ascii") + b"\0"
        require(image[offset:offset + len(expected)] == expected, "ROM string " + entry["va"])
    # The primitive returns an existing record/default and has no calls, stores or publication.
    primitive = [row for address, row in instructions.items() if 0x16961C <= address < 0x16964A]
    require(not any(row["mnemonic"].startswith(("st", "sst")) or row["mnemonic"] == "jarl"
                    for row in primitive), "lookup has no store/call")
    require(report["table"]["bucketBytes"] == 953 * 4 == 0xEE4, "bucket allocation")
    require(0xFEBDDA24 + 251 * 0x50 == 0xFEBE2894 and
            0xFEBE2894 + 251 * 0x10 == 0xFEBE3844, "adjacent record/node pools")
    for selector in range(3):
        for can_id in (0x7E8, 0x7EE):
            for sub_id in (0, 0x10, 0x14):
                key = (selector << 24) | ((can_id & 0x7FF) << 8) | sub_id
                require((key >> 24, (key >> 8) & 0x7FF, key & 0xFF) ==
                        (selector, can_id, sub_id), "diagnostic key projection")
    require(report["conclusion"]["qualifiedReadOnlyRequest"] is None and
            report["conclusion"]["current7E8Membership"] == "unknown" and
            report["conclusion"]["current7EEMembership"] == "unknown", "no invented runtime readback")
    require(report["query158Boundary"]["semanticPairWithAA000158Established"] is False and
            report["query158Boundary"]["otherFIDPublicationExcluded"] is False and
            report["query158Boundary"]["inboundQueryResponsePathTraced"] is False,
            "do not conflate scalar setter and query or exclude untraced publications")
    print(f"PASS: {len(report['sources'])} pinned sources; {len(report['ranges'])} bounded ranges / "
          f"{len(instructions)} instructions; {len(report['anchors'])} anchors; branch/literal scans; "
          "known mutators identified; query158 untraced; no readback/absence claim")


if __name__ == "__main__":
    check()
