#!/usr/bin/env python3
"""Replay the production VHDS configuration route against retained ELF bytes.

Offline only. Requires the local firmware corpus and .venv-firmware packages.
No factory code is executed and no vehicle or server is contacted.
"""
import gzip
import hashlib
import io
import json
import lzma
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name("vhds-production-route-20261021.json")


def need(value, label):
    if not value:
        raise ValueError(label)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def reply(path):
    result = json.loads(gzip.decompress(path.read_bytes()))["result"]
    return result.get("structuredContent") or json.loads(result["content"][0]["text"])


def native_bytes(elf, address, length):
    for section in elf.iter_sections():
        start, size = section["sh_addr"], section["sh_size"]
        if section["sh_type"] != "SHT_NOBITS" and start <= address and address + length <= start + size:
            return section.data()[address - start:address - start + length]
    raise ValueError(f"Unmapped VA {address:#x}+{length}")


def main():
    report = json.loads(REPORT.read_text())
    need(report["schema"] == "denza-lab.vhds-production-route.v1", "schema")
    need(report["vehicleOperations"] == report["networkRequests"] == report["firmwareExecutions"] == 0,
         "offline boundary")
    need(report["newEcuAddresses"] == report["newDtcDefinitions"] == 0, "no invented map/dictionary")
    for source in report["sources"]:
        data = (ROOT / source["path"]).read_bytes()
        need(len(data) == source["bytes"] and sha(data) == source["sha256"], source["path"])
    base = ROOT / report["captureBase"]
    elf = ELFFile(io.BytesIO((ROOT / report["binary"]).read_bytes()))
    debug = ELFFile(io.BytesIO(lzma.decompress(elf.get_section_by_name(".gnu_debugdata").data())))
    symbols = {s.name: (s["st_value"], s["st_size"]) for s in debug.get_section_by_name(".symtab").iter_symbols()}
    load = reply(base / "rea/load-image.json.gz")["normalized_result"]
    need(load["status"] == "unsupported", "ELF attestation remains unsupported")
    need(load["observations"]["image_base"] == "0x100000", "measured REA base")
    text = next(x for x in load["observations"]["mappings"] if x["block"] == ".text")
    section = elf.get_section_by_name(".text")
    need(int(text["start"], 16) - section["sh_addr"] == 0x100000, "address delta")
    need(text["sha256"] == sha(section.data()), "loaded code matches original bytes")
    ranges = json.loads((base / "byte-corroboration.json").read_text())["ranges"]
    need(len(ranges) == 13, "bounded native ranges")
    decoded = {}
    for row in ranges:
        address, length = int(row["va"], 16), row["bytes"]
        need(symbols[row["symbol"]] == (address, length), "symbol extent")
        raw = native_bytes(elf, address, length)
        need(sha(raw) == row["sha256"], "range hash")
        instructions = [{"va": hex(i.address), "bytes": i.bytes.hex(), "mnemonic": i.mnemonic,
                         "operands": i.op_str} for i in Cs(CS_ARCH_ARM64, CS_MODE_ARM).disasm(raw, address)]
        need(instructions == row["instructions"] and len(instructions) * 4 == length, "independent decode")
        decoded.update({int(x["va"], 16): (x["mnemonic"], x["operands"]) for x in instructions})
    anchors = {
        0x18688: ("add", "x9, x8, #0x670"),
        0x1868C: ("adrp", "x8, #0x1c000"),
        0x18690: ("add", "x8, x8, #0x620"),
        0x18698: ("mov", "w1, #4"),
        0x1869C: ("stp", "xzr, x20, [sp, #0x90]"),
        0x186A4: ("stp", "x9, x8, [sp, #0x80]"),
        0x186A8: ("bl", "#0x210e0"),
        0x18A14: ("adrp", "x9, #0x1e000"),
        0x18A1C: ("add", "x9, x9, #0xd20"),
        0x18A28: ("mov", "w1, #2"),
        0x18A50: ("bl", "#0x210e0"),
        0x22164: ("mov", "w27, #4"),
        0x22170: ("mov", "w2, #4"),
        0x22174: ("bl", "#0x179e0"),
        0x22178: ("cbz", "w25, #0x222d8"),
        0x222D8: ("ldr", "x0, [x20, #0x20]"),
        0x222DC: ("strb", "w27, [sp, #0x5c]"),
        0x222F0: ("ldr", "x8, [x8, #0x30]"),
        0x222F4: ("blr", "x8"),
        0x2D4F0: ("ldp", "x9, x8, [x0, #0x10]"),
        0x2D4F4: ("ldr", "x3, [x0, #8]"),
        0x2D4F8: ("add", "x0, x8, x9, asr #1"),
        0x2D508: ("ldrb", "w2, [x2]"),
        0x2D50C: ("br", "x3"),
        0x18498: ("add", "x8, x8, #0x550"),
        0x184A4: ("stp", "xzr, x20, [x0, #0x10]"),
        0x184A8: ("add", "x2, x2, #0xce0"),
        0x184B0: ("stp", "x23, x8, [x0]"),
        0x2CD04: ("ldp", "x10, x9, [x19, #0x10]"),
        0x2CD08: ("ldr", "x8, [x19, #8]"),
        0x2CD1C: ("blr", "x8"),
    }
    for address, expected in anchors.items():
        need(decoded.get(address) == expected, f"anchor {address:#x}")
    relocations = {r["r_offset"]: r.entry for r in elf.get_section_by_name(".rela.dyn").iter_relocations()}
    callback = relocations[0x4A670 + 0x30]
    need(callback["r_info_type"] == 1027 and callback["r_addend"] == 0x2D4F0, "callback vtable slot")
    pseudo = {}
    for name in ["config-entry", "config-production", "config-model", "callback-and-threads"]:
        data = reply(base / ("rea/" + name + ".json.gz"))["normalized_result"]
        need(data["failed"] == 0 and data["total"] == data["succeeded"], "REA operation status")
        pseudo.update({x["address"]: x["pseudocode"] for x in data["items"]})
    for address, needles in report["pseudocodeAnchors"].items():
        for needle in needles:
            need(needle in pseudo[address], "pseudocode context " + address + ": " + needle)
    # A full frozen negative search is bounded to these 42 extracted/indexed ELFs.
    inventory = json.loads((base / "native-client-inventory.json").read_text())["inventory"]
    need(len(inventory) == 42, "inventory extent")
    for item in inventory:
        raw = (ROOT / item["path"]).read_bytes()
        need(len(raw) == item["bytes"] and sha(raw) == item["sha256"], "inventory source")
    hits = [x["path"] for x in inventory if x["symbolMatches"] or x["literalOffsets"]]
    need(sorted(hits) == sorted(report["inventoryHits"]), "bounded client search")
    receipt = json.loads(gzip.decompress((base / "rea/receipt.json.gz").read_bytes()))
    need(receipt["sha256Before"] == receipt["sha256After"] == sha((ROOT / report["binary"]).read_bytes()), "unchanged input")
    need(receipt["serverExitCode"] == 0 and receipt["scratchEntries"] == [], "owned session closed")
    need(report["cache"]["ecuListPersistedByInspectedBranch"] is False, "do not conflate caches")
    need(report["cache"]["signalResponseWrittenBeforeResultCodeGate"] is True, "cache is not success proof")
    print(f"PASS: {len(report['sources'])} pinned sources; {len(ranges)} ranges/{len(decoded)} instructions; "
          f"{len(anchors)} anchors; callback relocation; 42-ELF inventory; offline-only")


if __name__ == "__main__":
    main()
