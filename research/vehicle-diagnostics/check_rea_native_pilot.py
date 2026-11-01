#!/usr/bin/env python3
"""Replay retained REA results against original ELF bytes, without tools or a car."""
import gzip
import hashlib
import json
from pathlib import Path
import struct

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name("rea-native-pilot-20261021.json")


def require(ok, message):
    if not ok:
        raise AssertionError(message)


def read_json(path):
    raw = path.read_bytes()
    return json.loads(gzip.decompress(raw) if path.suffix == ".gz" else raw)


def elf_bytes(image, va, count):
    require(image[:6] == b"\x7fELF\x02\x01", "ELF64 LE")
    require(struct.unpack_from("<H", image, 18)[0] == 183, "AArch64")
    phoff = struct.unpack_from("<Q", image, 32)[0]
    size, number = struct.unpack_from("<HH", image, 54)
    require(size == 56 and number < 100, "bounded ELF headers")
    for i in range(number):
        kind, _, offset, start, _, length, _, _ = struct.unpack_from(
            "<IIQQQQQQ", image, phoff + i * size)
        if kind == 1 and start <= va and va + count <= start + length:
            return image[offset + va - start:offset + va - start + count]
    raise AssertionError("unmapped original range")


def main():
    report = read_json(REPORT)
    require(report["schema"] == "denza-lab.rea-native-pilot.v1", "schema")
    base = ROOT / report["captureBase"]
    for item in report["sources"] + report["artifacts"]:
        data = (ROOT / item["path"]).read_bytes()
        require(len(data) == item["bytes"] and
                hashlib.sha256(data).hexdigest() == item["sha256"], item["path"])

    def result(name):
        return read_json(base / (name + ".json.gz"))["result"]["structuredContent"]["normalized_result"]

    initial = result("lib-batch")
    require(initial["succeeded"] == 7 and all(x["procedure"]["status"] == "unknown"
            and x["procedure"].get("error") for x in initial["items"]),
            "initial fallback text has unresolved procedure identities")
    library = result("lib-batch-mapped")
    daemon = result("vhds-handoff-batch")
    require([x["pseudocode"] for x in initial["items"]] ==
            [x["pseudocode"] for x in library["items"]], "fallback text matches mapped functions")
    for batch, count in ((library, 7), (daemon, 4)):
        require(batch["succeeded"] == count and batch["failed"] == 0, "batch status")
        require(all(x["status"] == "ok" and x["procedure"]["status"] == "resolved"
                    and x["pseudocode"] for x in batch["items"]), "per-item results")

    for name, source in zip(("lib", "vhds"), report["sources"]):
        observations = result(name + "-load-image")["observations"]
        require(observations["image_base"] == "0x100000", "observed image base")
        text = next(x for x in observations["mappings"] if x["block"] == ".text")
        original = (ROOT / source["path"]).read_bytes()
        raw = original[text["file_offset"]:text["file_offset"] + text["length"]]
        require(hashlib.sha256(raw).hexdigest() == text["sha256"], "original text mapping")

    image = (ROOT / report["sources"][1]["path"]).read_bytes()
    ranges = read_json(base / "vhds-handoff-byte-check.json")
    require(len(ranges) == 6 and sum(x["bytes"] for x in ranges) == 680, "bounded byte scope")
    instructions = {}
    for region in ranges:
        va = int(region["va"], 16)
        data = elf_bytes(image, va, region["bytes"])
        require(hashlib.sha256(data).hexdigest() == region["sha256"], "range identity")
        cursor = va
        for row in region["instructions"]:
            code = bytes.fromhex(row["hex"])
            require(int(row["va"], 16) == cursor and len(code) == 4 and
                    elf_bytes(image, cursor, 4) == code, "instruction continuity/bytes")
            instructions[cursor] = row
            cursor += 4
        require(cursor == va + region["bytes"], "range coverage")
    anchors = {
        0x251B4: ("add", "x20, x0, #0x328"),
        0x251B8: ("add", "x21, x0, #0x2f8"),
        0x251DC: ("ldrb", "w8, [x19, #0x63a]"),
        0x251CC: ("strb", "wzr, [x19, #0x63a]"),
        0x26F48: ("add", "x9, x22, #0x328"),
        0x26F58: ("add", "x8, x22, #0x2f8"),
        0x26F5C: ("stp", "x8, x9, [sp, #0x18]"),
        0x27434: ("mov", "w8, #0x402"),
        0x27438: ("strh", "w8, [x22, #0x65e]"),
        0x2744C: ("add", "x8, x22, #0x639"),
        0x27450: ("mov", "w9, #0x101"),
        0x27458: ("strh", "w9, [x8]"),
        0x2524C: ("ldrb", "w8, [x19, #0x65f]"),
    }
    for va, expected in anchors.items():
        row = instructions[va]
        require((row["mnemonic"], row["operands"]) == expected, "anchor " + hex(va))
    for va, target in ((0x251C4, 0x25200), (0x25684, 0x25200),
                       (0x251EC, 0x48350), (0x2745C, 0x47DF0)):
        word = struct.unpack("<I", elf_bytes(image, va, 4))[0]
        require(word >> 26 == 0x25, "BL encoding")
        immediate = word & 0x3FFFFFF
        if immediate & 0x2000000:
            immediate -= 0x4000000
        require(va + immediate * 4 == target, "direct branch target")
    graph = result("vhds-thread-values")
    require(graph["truncated"] and graph["total_nodes"] == 250 and graph["unknowns"],
            "bounded graph remains incomplete")
    cleanup = read_json(base / "cleanup.json")
    require(cleanup["temporaryRootRemoved"] and cleanup["mcpServerExitCode"] == 0
            and not cleanup["ownedProcessesBeforeRemoval"], "retained cleanup receipt")
    require(report["scope"]["vehicleOperations"] == report["scope"]["firmwareExecutions"] == 0,
            "offline scope")
    print("PASS: 7+4 decompiled functions; 2 original text mappings; "
          "6 ranges/170 instructions; worker handoff byte anchors; bounded graph; cleanup receipt")


if __name__ == "__main__":
    main()
