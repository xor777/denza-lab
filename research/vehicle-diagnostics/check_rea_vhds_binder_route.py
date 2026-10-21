#!/usr/bin/env python3
"""Replay the REA Binder-route finding offline against the original ELF bytes."""
import hashlib
import io
import json
import lzma
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile

ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name("rea-vhds-binder-route-20261021.json")


def require(value, message):
    if not value:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def va_bytes(elf, address, size):
    for section in elf.iter_sections():
        if section["sh_type"] != "SHT_NOBITS" and section["sh_addr"] <= address and address + size <= section["sh_addr"] + section["sh_size"]:
            offset = address - section["sh_addr"]
            return section.data()[offset:offset + size]
    raise ValueError(f"Unmapped VA {address:#x}+{size}")


def main():
    r = json.loads(REPORT.read_text())
    require(r["schema"] == "denza-lab.rea-vhds-binder-route.v1", "schema")
    require(r["vehicleOperations"] == r["firmwareExecutions"] == 0, "offline scope")
    require(r["newEcuAddresses"] == r["newDtcDefinitions"] == 0, "no data promotion")
    for source in r["sources"]:
        raw = (ROOT / source["path"]).read_bytes()
        require(len(raw) == source["bytes"] and digest(raw) == source["sha256"], "source " + source["path"])
    base = ROOT / r["captureBase"]
    elfs = {name: ELFFile(io.BytesIO((ROOT / path).read_bytes())) for name, path in r["binaries"].items()}
    load = json.loads((base / "load-image.json").read_text())["normalized_result"]
    require(load["status"] == "unsupported", "independent ELF attestation limit")
    obs = load["observations"]
    require(obs["image_base"] == "0x100000", "measured base")
    mapping = next(m for m in obs["mappings"] if m["block"] == ".text")
    section = elfs["vhds"].get_section_by_name(".text")
    require(int(mapping["start"], 16) - section["sh_addr"] == 0x100000, "code address delta")
    require(mapping["sha256"] == digest(section.data()), "loaded text bytes")
    evidence = json.loads((base / "byte-corroboration.json").read_text())
    decoded = {}
    require(len(evidence["ranges"]) == 6, "bounded range inventory")
    for row in evidence["ranges"]:
        raw = va_bytes(elfs[row["binary"]], int(row["va"], 16), row["bytes"])
        require(digest(raw) == row["sha256"], "native extent")
        instructions = [{"va": hex(i.address), "bytes": i.bytes.hex(), "mnemonic": i.mnemonic, "operands": i.op_str}
                        for i in Cs(CS_ARCH_ARM64, CS_MODE_ARM).disasm(raw, int(row["va"], 16))]
        require(instructions == row["instructions"] and len(instructions) * 4 == len(raw), "independent decode")
        decoded.update({(row["binary"], int(i["va"], 16)): (i["mnemonic"], i["operands"]) for i in instructions})
    anchors = {
        ("vhds", 0x141F0): ("mov", "x19, x0"),
        ("vhds", 0x14220): ("add", "x0, x19, #8"),
        ("vhds", 0x1426C): ("adrp", "x8, #0x49000"),
        ("vhds", 0x14270): ("add", "x8, x8, #0x80"),
        ("vhds", 0x14278): ("add", "x10, x8, #0x60"),
        ("vhds", 0x1427C): ("add", "x11, x8, #0xd0"),
        ("vhds", 0x14288): ("stp", "x8, x10, [x19]"),
        ("vhds", 0x1428C): ("str", "x11, [x19, #0x10]"),
        ("vhds", 0x1FD40): ("sub", "x0, x0, #8"),
        ("vhds", 0x1FD44): ("b", "#0x1b7f0"),
        ("libvhds", 0x4DCC): ("mov", "w1, w21"),
        ("libvhds", 0x4DD0): ("mov", "x2, x23"),
        ("libvhds", 0x4DD4): ("mov", "w3, w22"),
        ("libvhds", 0x4DD8): ("ldr", "x8, [x8, #0x40]"),
        ("libvhds", 0x4DDC): ("blr", "x8"),
        ("libvhds", 0x4F20): ("sub", "x0, x0, #8"),
        ("libvhds", 0x4F24): ("b", "#0x5950"),
        ("libvhds", 0x5950): ("adrp", "x16, #0x7000"),
        ("libvhds", 0x5954): ("ldr", "x17, [x16, #0xc40]"),
        ("libvhds", 0x5958): ("add", "x16, x16, #0xc40"),
        ("libvhds", 0x595C): ("br", "x17"),
    }
    require(all(decoded[key] == value for key, value in anchors.items()), "vptr/thunk/argument anchors")
    slot = 0x49000 + 0x80 + 0x60 + 0x40
    relocations = [rel for rel in elfs["vhds"].get_section_by_name(".rela.dyn").iter_relocations() if rel["r_offset"] == slot]
    require(len(relocations) == 1 and relocations[0]["r_info_type"] == 1027 and relocations[0]["r_addend"] == 0x1FD40, "virtual slot binding")
    relsec = elfs["vhds"].get_section_by_name(".rela.dyn")
    imported = next(rel for rel in relsec.iter_relocations() if rel["r_offset"] == 0x491D0)
    import_name = "_ZThn8_N7android5BnVDS10onTransactEjRKNS_6ParcelEPS1_j"
    require(imported["r_info_type"] == 257 and elfs["vhds"].get_section(relsec["sh_link"]).get_symbol(imported["r_info_sym"]).name == import_name, "BBinder onTransact relocation")
    require(any(s.name == import_name and s["st_value"] == 0x4F20 for s in elfs["libvhds"].get_section_by_name(".dynsym").iter_symbols()), "import resolution")
    plt = elfs["libvhds"].get_section_by_name(".rela.plt")
    binding = next(rel for rel in plt.iter_relocations() if rel["r_offset"] == 0x7C40)
    symbol = elfs["libvhds"].get_section(plt["sh_link"]).get_symbol(binding["r_info_sym"])
    require(binding["r_info_type"] == 1026 and symbol.name == "_ZN7android5BnVDS10onTransactEjRKNS_6ParcelEPS1_j" and symbol["st_value"] == 0x4C90, "PLT symbol binding, not observed runtime resolution")
    debug = ELFFile(io.BytesIO(lzma.decompress(elfs["vhds"].get_section_by_name(".gnu_debugdata").data())))
    symbols = {(s["st_value"], s.name) for s in debug.get_section_by_name(".symtab").iter_symbols()}
    require((0x1B7F0, "_ZN4VHDS11remoteOrderEiPKvi") in symbols and
            (0x1FD40, "_ZThn8_N4VHDS11remoteOrderEiPKvi") in symbols, "named method/thunk")
    require(va_bytes(elfs["vhds"], 0x9CF8, 5) == b"vhds\0", "published service string")
    for name, address, symbol in (("constructor", "0x1141d0", "_ZN4VHDSC1Ev"), ("publish", "0x12acd0", "_ZN4VHDS14publishServiceEv")):
        n = json.loads((base / (name + ".json")).read_text())["normalized_result"]
        require(n["procedure"]["address"] == address and n["procedure"]["name"] == symbol, "resolved procedure identity")
    require(r["cleanup"]["ownedTemporaryRootRemoved"] is True and r["cleanup"]["inputHashesUnchanged"] is True, "recorded cleanup")
    print(f"PASS {len(r['sources'])} pinned sources; 6 ranges/579 instructions; 21 anchors; Binder5->remoteOrder; no live claims")


if __name__ == "__main__":
    main()
