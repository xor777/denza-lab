#!/usr/bin/env python3
"""Replay the bounded washer REA pass against original bytes; no car/tool calls.

--record is a one-time evidence freeze (requires the existing firmware venv for
Capstone). Default replay uses only Python's standard library.
"""
import gzip
import hashlib
import json
from pathlib import Path
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / "captures/washer-rea-20261021-V8THNt"
CORPUS = ROOT / "captures/cluster-native-20261018"
SOURCE = ROOT / "captures/firmware/pulled-from-car-20261018/native/libCellsClusterDs.so"
REPORT = Path(__file__).with_name("washer-rea-native-20261021.json")
BATCHES = ("ui-warning", "sync-dow", "receiver-transport", "cache-ingress", "dispatch-power")
SOURCE_SHA = "124d6b567c0c6205a59579a3d4df06cc2e0dba5a89bead73cb4151852298b3f6"
ANCHORS = {
    0x118B50: ("29314079", "ldrh", "w9, [x9, #0x18]"),
    0x118B68: ("29354079", "ldrh", "w9, [x9, #0x1a]"),
    0x118BE8: ("08414039", "ldrb", "w8, [x8, #0x10]"),
    0x118BEC: ("68040035", "cbnz", "w8, #0x118c78"),
    0x11ACA4: ("291540b9", "ldr", "w9, [x9, #0x14]"),
    0x11ACBC: ("2925cb1a", "lsr", "w9, w9, w11"),
    0x11ACD8: ("08454039", "ldrb", "w8, [x8, #0x11]"),
    0x11ACDC: ("c80b0034", "cbz", "w8, #0x11ae54"),
    0x11AD10: ("01110091", "add", "x1, x8, #4"),
    0x18301C: ("08cd4439", "ldrb", "w8, [x8, #0x133]"),
    0x18305C: ("08fd0471", "subs", "w8, w8, #0x13f"),
    0x183060: ("e1000054", "b.ne", "#0x18307c"),
    0x18306C: ("558d0694", "bl", "#0x3265c0"),
    0x182D10: ("88008052", "mov", "w8, #4"),
    0x182D28: ("a6830694", "bl", "#0x323bc0"),
    0x182D34: ("08150011", "add", "w8, w8, #5"),
    0x182D64: ("a3840694", "bl", "#0x323ff0"),
    0x10D19C: ("a55a0894", "bl", "#0x323c30"),
}


def require(ok, label):
    if not ok:
        raise AssertionError(label)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def read(path):
    data = path.read_bytes()
    return json.loads(gzip.decompress(data) if path.suffix == ".gz" else data)


def result(label):
    reply = read(BASE / "session" / (label + ".json.gz"))
    require(not reply.get("error") and not reply["result"].get("isError"), label)
    return reply["result"]["structuredContent"]


def elf_slice(image, va, size):
    require(image[:6] == b"\x7fELF\x02\x01", "ELF64 LE")
    require(struct.unpack_from("<H", image, 18)[0] == 183, "AArch64")
    phoff = struct.unpack_from("<Q", image, 32)[0]
    entry_size, count = struct.unpack_from("<HH", image, 54)
    require(entry_size == 56 and count < 100, "ELF program headers")
    for i in range(count):
        kind, _, offset, start, _, length, _, _ = struct.unpack_from(
            "<IIQQQQQQ", image, phoff + i * entry_size)
        if kind == 1 and start <= va and va + size <= start + length:
            off = offset + va - start
            return off, image[off:off + size]
    raise AssertionError("unmapped original range " + hex(va))


def identity(path):
    raw = path.read_bytes()
    return {"path": str(path.relative_to(ROOT)), "bytes": len(raw), "sha256": digest(raw)}


def all_items():
    items = []
    for name in BATCHES:
        envelope = result(name + "-batch")
        require(envelope["subject"]["digest"]["sha256"] == SOURCE_SHA, "batch source")
        batch = envelope["normalized_result"]
        require(batch["failed"] == 0 and batch["succeeded"] == len(batch["items"]), "batch counts")
        for item in batch["items"]:
            require(item["status"] == "ok" and item["procedure"]["status"] == "resolved",
                    "resolved procedure")
            require(item["address"] == item["procedure"]["address"] and item["pseudocode"], "identity")
            items.append(item)
    require(len(items) == 23 and len({i["address"] for i in items}) == 23, "23 unique functions")
    return items


def record():
    from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
    require(not REPORT.exists(), "refuse replacing report")
    image = SOURCE.read_bytes()
    require(digest(image) == SOURCE_SHA, "original source")
    functions = {}
    for line in (CORPUS / "libCellsClusterDs/functions.tsv").read_text().splitlines()[1:]:
        fields = line.split("\t")
        functions[int(fields[0], 16)] = (int(fields[1]), fields[-1])
    decoder = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
    ranges = []
    summary = []
    for item in all_items():
        va = int(item["address"], 16) - 0x100000
        size, name = functions[va]
        offset, raw = elf_slice(image, va, size)
        instructions = [{"va": hex(i.address), "hex": i.bytes.hex(),
                         "mnemonic": i.mnemonic, "operands": i.op_str}
                        for i in decoder.disasm(raw, va)]
        entry = {"va": hex(va), "analysisAddress": item["address"], "bytes": size,
                 "fileOffset": offset, "name": name, "sha256": digest(raw)}
        summary.append(entry)
        ranges.append(dict(entry, instructions=instructions))
    # The exact descriptor is data, not assumed to be loader-relocation-free.
    offset, row = elf_slice(image, 0x33C890, 56)
    row_record = {"va": "0x33c890", "fileOffset": offset, "hex": row.hex(), "sha256": digest(row)}
    byte_path = BASE / "original-byte-ranges.json.gz"
    with byte_path.open("xb") as stream:
        stream.write(gzip.compress(json.dumps({"ranges": ranges, "warningDescriptor": row_record},
                                             indent=2).encode(), mtime=0))
    sources = [SOURCE, CORPUS / "libCellsClusterDs/functions.tsv",
               CORPUS / "libCellsClusterDs/dwarf-layouts.jsonl", CORPUS / "tables/can-signals.tsv"]
    artifacts = sorted(p for p in BASE.rglob("*") if p.is_file())
    report = {
        "schema": "denza-lab.washer-rea-native.v1", "date": "2026-10-21",
        "scope": {"vehicleOperations": 0, "firmwareExecutions": 0, "productEdits": 0},
        "objective": "Find a reliable binary low-washer-fluid/refill-needed signal. 1DD is one lead, not a required path or acceptance criterion.",
        "sourceSHA256": SOURCE_SHA, "captureBase": str(BASE.relative_to(ROOT)),
        "addressMapping": {"observedImageBase": "0x100000", "textDelta": "0x100000",
                           "originalTextDigestMatches": True, "independentELFAttestation": False},
        "result": {
            "producerIdentified": False, "washerDiagnosticReadFound": False,
            "boundedConclusion": "The examined native receiver accepts 1DD, requests synchronization of its FID, and has no 1DD-to-13F substitution in the inspected body/UI path. It receives an enum, not reservoir state.",
            "newDetails": [
                "26100008 descriptor: UINT16, min0/max1023, repeated1, sync1, noNeedPlatform0.",
                "UI-init233E restarts synchronization; raw power3 can schedule missing-field query after5000ms. Requests contain BE FID plus zero payload length, not a washer sensor read.",
                "Sync completion only accounts for received nonempty records, independently of semantic validity; it is not freshness or sensor proof.",
                "Body handler returns eligibility requiring already-selected13F and updates door/belt data items. It neither composes13F from open body elements nor overwrites26100008.",
                "Presentation has generic hide/DOW gates and100/500ms timer branches. One DOW reason is ADAS418 fields20/22 value2, not ordinary door-open state.",
            ],
        },
        "limitations": [
            "Static imported Cells binary; actual N9 renderer/applicability is not proved.",
            "Native SPI consumer is separate from Android HAL/BigData; not an explanation of saved raw13F.",
            "No ECU sender, reservoir-input owner, priority policy, low-fluid trigger, or current runtime state established.",
            "REA can infer imperfect arguments/locals. Original-byte anchors and DWARF corroborate only the stated bounded paths.",
            "100ms lambda captures a local by reference; its timer/lifetime semantics were not traced and no runtime defect is asserted.",
            "Reference queries report Ghidra body/entry references, including PLT thunks, not an exhaustive cross-module producer graph.",
        ],
        "functions": summary, "sources": [identity(p) for p in sources],
        "artifacts": [identity(p) for p in artifacts],
        "anchors": [{"va": hex(va), "hex": value[0], "mnemonic": value[1], "operands": value[2]}
                    for va, value in ANCHORS.items()],
    }
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


def check():
    report = read(REPORT)
    require(report["schema"] == "denza-lab.washer-rea-native.v1", "schema")
    for item in report["sources"] + report["artifacts"]:
        require(identity(ROOT / item["path"]) == item, item["path"])
    image = SOURCE.read_bytes()
    require(digest(image) == SOURCE_SHA, "original source hash")
    for envelope in (result("load-image"), read(BASE / "cells-load-image.json.gz")):
        observed = envelope["normalized_result"]["observations"]
        require(observed["image_base"] == "0x100000", "measured base")
        mapping = next(m for m in observed["mappings"] if m["block"] == ".text")
        off, data = elf_slice(image, 0x10C000, 2193784)
        require(mapping["file_offset"] == off and mapping["length"] == len(data), "mapping range")
        require(mapping["start"] == "0x20c000" and digest(data) == mapping["sha256"], "text bytes")
    items = all_items()
    evidence = read(BASE / "original-byte-ranges.json.gz")
    instructions = {}
    require(len(evidence["ranges"]) == len(items), "function byte range count")
    require([{k: v for k, v in r.items() if k != "instructions"}
             for r in evidence["ranges"]] == report["functions"], "report function summaries")
    for region, item in zip(evidence["ranges"], items):
        va = int(region["va"], 16)
        require(int(item["address"], 16) == va + 0x100000, "function address")
        off, data = elf_slice(image, va, region["bytes"])
        require(off == region["fileOffset"] and digest(data) == region["sha256"], "function bytes")
        cursor = va
        for instruction in region["instructions"]:
            raw = bytes.fromhex(instruction["hex"])
            require(int(instruction["va"], 16) == cursor and len(raw) == 4, "instruction continuity")
            require(elf_slice(image, cursor, 4)[1] == raw, "original instruction")
            instructions[cursor] = instruction
            cursor += 4
        require(cursor == va + region["bytes"], "complete symbol-sized range")
    for va, expected in ANCHORS.items():
        row = instructions[va]
        require((row["hex"], row["mnemonic"], row["operands"]) == expected, hex(va))
    descriptor = evidence["warningDescriptor"]
    row = elf_slice(image, 0x33C890, 56)[1]
    require(row.hex() == descriptor["hex"] and digest(row) == descriptor["sha256"], "table bytes")
    require(struct.unpack_from("<II", row) == (0x26100008, 0x26100008), "original/real FID")
    require(row[8] == 2 and struct.unpack_from("<I", row, 12)[0] == 2, "UINT16 two-byte field")
    require(row[16:18] == b"\x01\x01" and row[20:24] == bytes(4), "repeat/sync/platform flags")
    require(struct.unpack_from("<HH", row, 24) == (0, 1023), "accepted warning range")
    receipt = read(BASE / "session/receipt.json.gz")
    require(receipt["sha256Before"] == receipt["sha256After"] == SOURCE_SHA, "immutable input")
    require(receipt["serverExitCode"] == 0 and not receipt["scratchEntries"], "owned cleanup")
    require(all(v == 0 for v in report["scope"].values()), "offline-only scope")
    require(not report["result"]["producerIdentified"], "unresolved producer preserved")
    print(f"PASS: 23 resolved functions; {len(instructions)} original instructions; "
          "18 byte anchors; descriptor flags/range; two text mappings; cleanup")


if __name__ == "__main__":
    if sys.argv[1:] == ["--record"]:
        record()
    else:
        require(not sys.argv[1:], "usage: check_washer_rea_native.py [--record]")
    check()
