#!/usr/bin/env python3
"""Offline Android APS2 RELA index. Does not apply relocations or load the ELF.

Format: AOSP bionic/linker/linker_reloc_iterators.h, blob
c6a8cf620ab2f7d3ea1c7e921f12e06743265fa2. RELR and ordinary RELA are out of scope.
"""
import argparse
import csv
import hashlib
import json
from pathlib import Path

from elftools.elf.elffile import ELFFile


def decode(data):
    if data[:4] != b"APS2":
        raise ValueError("Not Android APS2")
    position = 4

    def sleb():
        nonlocal position
        result = shift = 0
        while position < len(data) and shift < 70:
            b = data[position]
            position += 1
            result |= (b & 127) << shift
            shift += 7
            if not b & 128:
                return result - (1 << shift) if b & 64 else result
        raise ValueError("Truncated or oversized SLEB128")

    count, offset = sleb(), sleb()
    if not 0 <= count <= 1_000_000:
        raise ValueError("Invalid relocation count")
    records = []
    addend = info = 0
    while len(records) < count:
        size, flags = sleb(), sleb()
        if not 0 < size <= count - len(records) or flags & ~15:
            raise ValueError("Invalid relocation group")
        delta = sleb() if flags & 2 else None
        if flags & 1:
            info = sleb()
        if flags & 12 == 12:
            addend += sleb()
        elif flags & 12 != 8:
            addend = 0
        for _ in range(size):
            offset += delta if flags & 2 else sleb()
            if not flags & 1:
                info = sleb()
            if flags & 12 == 8:
                addend += sleb()
            if offset < 0 or info < 0:
                raise ValueError("Negative relocation offset or info")
            records.append((offset, info, addend))
    if position != len(data):
        raise ValueError("Trailing bytes in APS2 section")
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("elf", type=Path)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    rows = []
    with args.elf.open("rb") as stream:
        elf = ELFFile(stream)
        if elf.elfclass != 64 or not elf.little_endian:
            raise ValueError("Only little-endian ELF64 is supported")
        symbols = elf.get_section_by_name(".dynsym")
        for section in elf.iter_sections():
            if section["sh_type"] != 0x60000002:
                continue
            for offset, info, addend in decode(section.data()):
                rows.append({"section": section.name, "offset": hex(offset),
                             "type": info & 0xFFFFFFFF, "symbol_index": info >> 32,
                             "symbol": symbols.get_symbol(info >> 32).name,
                             "addend": addend})
    args.out.mkdir(parents=True, exist_ok=True)
    with (args.out / "android-rela.tsv").open("w", newline="") as stream:
        writer = csv.DictWriter(stream, ["section", "offset", "type", "symbol_index", "symbol", "addend"],
                                delimiter="\t", lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    proof = {"sha256": hashlib.sha256(args.elf.read_bytes()).hexdigest(),
             "relocations": len(rows), "scope": "Android packed RELA only; no relocations applied"}
    (args.out / "android-rela-manifest.json").write_text(json.dumps(proof, indent=2) + "\n")
    print(json.dumps(proof))


if __name__ == "__main__":
    main()
