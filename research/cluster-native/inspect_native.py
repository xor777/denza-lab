#!/usr/bin/env python3
"""Offline AArch64 ELF corpus index and annotated function disassembly.

Never loads or executes firmware. Requires pyelftools, capstone, and c++filt.
Direct branch edges are exact instruction targets, not a complete call graph:
virtual calls, callbacks, GOT/data relocations and runtime registration need
separate analysis. DWARF paths/lines describe the build, not available sources.
"""

import argparse
import bisect
import csv
import gzip
import hashlib
import json
import re
import struct
import subprocess
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from elftools.elf.elffile import ELFFile


def text(value):
    return value.decode("utf-8", "replace") if isinstance(value, bytes) else str(value)


def demangle(names):
    names = sorted(set(names))
    result = subprocess.run(["c++filt", "-n"], input="\n".join(names) + "\n",
                            text=True, capture_output=True, check=True)
    lines = result.stdout.splitlines()
    if len(lines) != len(names):
        raise ValueError("c++filt returned a different number of lines")
    return dict(zip(names, lines))


def writer(path, fields):
    stream = path.open("w", newline="", encoding="utf-8")
    output = csv.writer(stream, delimiter="\t", lineterminator="\n")
    output.writerow(fields)
    return stream, output


class Native:
    def __init__(self, path):
        self.path = Path(path)
        self.stream = self.path.open("rb")
        self.elf = ELFFile(self.stream)
        if self.elf["e_machine"] != "EM_AARCH64" or not self.elf.little_endian:
            raise ValueError("Only little-endian AArch64 ELF is supported")
        self.sha256 = hashlib.sha256(self.path.read_bytes()).hexdigest()
        self.symbols = {}
        for section_name in (".dynsym", ".symtab"):
            section = self.elf.get_section_by_name(section_name)
            if section is None:
                continue
            for symbol in section.iter_symbols():
                if not symbol.name:
                    continue
                key = (symbol["st_value"], symbol.name, symbol["st_info"]["type"])
                self.symbols[key] = {
                    "address": symbol["st_value"], "size": symbol["st_size"],
                    "type": symbol["st_info"]["type"], "bind": symbol["st_info"]["bind"],
                    "section": str(symbol["st_shndx"]), "name": symbol.name,
                }
        self.names = demangle(s["name"] for s in self.symbols.values())
        self.functions = {}
        for symbol in self.symbols.values():
            if symbol["type"] != "STT_FUNC" or symbol["section"] == "SHN_UNDEF":
                continue
            address = symbol["address"]
            previous = self.functions.get(address)
            if previous is None or symbol["size"] > previous["size"]:
                self.functions[address] = dict(symbol, source="symbol")
        # Stripped executables may still have unwind function extents.
        try:
            for entry in self.elf.get_dwarf_info().EH_CFI_entries():
                if "initial_location" not in entry.header:
                    continue
                address, size = entry["initial_location"], entry["address_range"]
                self.functions.setdefault(address, {
                    "address": address, "size": size, "name": f"sub_{address:x}",
                    "source": ".eh_frame", "type": "STT_FUNC", "bind": "inferred",
                    "section": "unwind",
                })
        except (KeyError, AttributeError):
            pass
        self.starts = sorted(self.functions)
        self.targets = {a: self.label(f["name"]) for a, f in self.functions.items()}
        self.plt = {}
        plt = self.elf.get_section_by_name(".plt")
        rel = self.elf.get_section_by_name(".rela.plt")
        dyn = self.elf.get_section_by_name(".dynsym")
        if plt is not None and rel is not None and dyn is not None:
            # Check the conventional AArch64 32-byte header / 16-byte stubs.
            entries = list(rel.iter_relocations())
            data = plt.data()
            if len(data) == 32 + 16 * len(entries):
                for i, relocation in enumerate(entries):
                    stub = data[32 + i * 16:48 + i * 16]
                    if struct.unpack_from("<I", stub, 12)[0] != 0xD61F0220:
                        raise ValueError("Unrecognized PLT stub (expected br x17)")
                    address = plt["sh_addr"] + 32 + 16 * i
                    name = dyn.get_symbol(relocation["r_info_sym"]).name
                    self.plt[address] = name
                    self.targets[address] = self.label(name) + "@plt"

    def label(self, name):
        return self.names.get(name, name)

    def owner(self, address):
        index = bisect.bisect_right(self.starts, address) - 1
        if index >= 0:
            item = self.functions[self.starts[index]]
            if item["address"] <= address < item["address"] + item["size"]:
                return item
        return None

    def bytes_at(self, address, length):
        for section in self.elf.iter_sections():
            if section["sh_type"] == "SHT_NOBITS":
                continue
            start = section["sh_addr"]
            if start <= address and address + length <= start + section["sh_size"]:
                offset = address - start
                return section.data()[offset:offset + length]
        raise ValueError(f"Unmapped extent {address:#x}+{length:#x}")

    def close(self):
        self.stream.close()


def export_dwarf(native, output):
    counts = {"compilation_units": 0, "line_rows": 0, "dwarf_functions": 0,
              "layouts": 0, "enums": 0}
    if native.elf.get_section_by_name(".debug_info") is None:
        return counts
    dwarf = native.elf.get_dwarf_info()
    units_file, units = writer(output / "compilation-units.tsv",
                              ["offset", "version", "name", "directory", "producer"])
    lines_file, lines = writer(output / "source-lines.tsv",
                              ["address", "file", "line", "column", "end_sequence"])
    funcs_file, funcs = writer(output / "dwarf-functions.tsv",
                              ["die_offset", "low_pc", "high_pc", "ranges_offset",
                               "name", "linkage_name", "file", "line", "declaration"])
    layouts = (output / "dwarf-layouts.jsonl").open("w")
    enums = (output / "dwarf-enums.jsonl").open("w")

    def inherited(die, key, depth=0):
        if die is None or depth > 8:
            return None, die
        if key in die.attributes:
            return die.attributes[key], die
        for ref in ("DW_AT_abstract_origin", "DW_AT_specification"):
            if ref in die.attributes:
                result, owner = inherited(die.get_DIE_from_attribute(ref), key, depth + 1)
                if result is not None:
                    return result, owner
        return None, die

    def attr(die, key, default=None):
        value, _ = inherited(die, key)
        return value.value if value is not None else default

    def type_ref(die):
        if "DW_AT_type" not in die.attributes:
            return None
        target = die.get_DIE_from_attribute("DW_AT_type")
        return {"die_offset": hex(target.offset), "tag": target.tag,
                "name": text(attr(target, "DW_AT_name", ""))}

    def file_name(cu, file_index):
        program = dwarf.line_program_for_CU(cu)
        if program is None or file_index is None:
            return ""
        # DWARF 5 file/directory indices are zero-based; v2-v4 are one-based.
        version = program.header["version"]
        file_offset = file_index if version >= 5 else file_index - 1
        entries = program.header["file_entry"]
        if not 0 <= file_offset < len(entries):
            return f"<file-index:{file_index}>"
        entry = entries[file_offset]
        name = text(entry.name)
        if name.startswith("/"):
            return name
        index = entry.dir_index
        directories = program.header["include_directory"]
        if version >= 5:
            directory = text(directories[index]) if index < len(directories) else ""
        else:
            directory = (text(directories[index - 1]) if index else
                         text(attr(cu.get_top_DIE(), "DW_AT_comp_dir", "")))
        return str(Path(directory) / name)

    for cu in dwarf.iter_CUs():
        top = cu.get_top_DIE()
        counts["compilation_units"] += 1
        units.writerow([hex(cu.cu_offset), cu["version"], text(attr(top, "DW_AT_name", "")),
                        text(attr(top, "DW_AT_comp_dir", "")), text(attr(top, "DW_AT_producer", ""))])
        program = dwarf.line_program_for_CU(cu)
        if program is not None:
            for entry in program.get_entries():
                state = entry.state
                if state is not None:
                    lines.writerow([hex(state.address), file_name(cu, state.file), state.line,
                                    state.column, int(state.end_sequence)])
                    counts["line_rows"] += 1
        for die in cu.iter_DIEs():
            if die.tag == "DW_TAG_subprogram":
                low = attr(die, "DW_AT_low_pc")
                high_attr, _ = inherited(die, "DW_AT_high_pc")
                high = None if high_attr is None else high_attr.value
                if high is not None and high_attr.form != "DW_FORM_addr" and low is not None:
                    high += low
                file_attr, file_owner = inherited(die, "DW_AT_decl_file")
                funcs.writerow([
                    hex(die.offset), hex(low) if low is not None else "",
                    hex(high) if high is not None else "", attr(die, "DW_AT_ranges", ""),
                    text(attr(die, "DW_AT_name", "")), text(attr(die, "DW_AT_linkage_name", "")),
                    file_name(file_owner.cu, file_attr.value) if file_attr is not None else "",
                    attr(die, "DW_AT_decl_line", ""), int(bool(attr(die, "DW_AT_declaration", False))),
                ])
                counts["dwarf_functions"] += 1
            elif die.tag in ("DW_TAG_structure_type", "DW_TAG_class_type", "DW_TAG_union_type"):
                if "DW_AT_byte_size" not in die.attributes:
                    continue
                item = {"die_offset": hex(die.offset), "tag": die.tag,
                        "name": text(attr(die, "DW_AT_name", "")),
                        "size": attr(die, "DW_AT_byte_size"), "members": []}
                for child in die.iter_children():
                    if child.tag not in ("DW_TAG_member", "DW_TAG_inheritance"):
                        continue
                    item["members"].append({
                        "tag": child.tag, "name": text(attr(child, "DW_AT_name", "")),
                        "type": type_ref(child),
                        "location": attr(child, "DW_AT_data_member_location"),
                        "bit_size": attr(child, "DW_AT_bit_size"),
                        "data_bit_offset": attr(child, "DW_AT_data_bit_offset"),
                    })
                layouts.write(json.dumps(item, ensure_ascii=False) + "\n")
                counts["layouts"] += 1
            elif die.tag == "DW_TAG_enumeration_type":
                values = [{"name": text(attr(c, "DW_AT_name", "")),
                           "value": attr(c, "DW_AT_const_value")}
                          for c in die.iter_children() if c.tag == "DW_TAG_enumerator"]
                enums.write(json.dumps({"die_offset": hex(die.offset),
                                        "name": text(attr(die, "DW_AT_name", "")),
                                        "size": attr(die, "DW_AT_byte_size"),
                                        "values": values}, ensure_ascii=False) + "\n")
                counts["enums"] += 1
        if counts["compilation_units"] % 20 == 0:
            print(native.path.name, "DWARF units", counts["compilation_units"], flush=True)
    for stream in (units_file, lines_file, funcs_file, layouts, enums):
        stream.close()
    return counts


def index(path, output, assembly):
    native = Native(path)
    output.mkdir(parents=True, exist_ok=True)
    metadata_path = output / "metadata.json"
    if metadata_path.exists() and json.loads(metadata_path.read_text())["sha256"] != native.sha256:
        raise ValueError("Output directory belongs to a different ELF hash")
    dynamic = native.elf.get_section_by_name(".dynamic")
    metadata = {
        "input": str(path.resolve()), "sha256": native.sha256,
        "size": path.stat().st_size, "machine": native.elf["e_machine"],
        "needed": [t.needed for t in dynamic.iter_tags("DT_NEEDED")] if dynamic else [],
        "has_debug_info": native.elf.get_section_by_name(".debug_info") is not None,
        "sections": [{"name": s.name, "address": hex(s["sh_addr"]),
                      "size": s["sh_size"], "file_offset": s["sh_offset"]}
                     for s in native.elf.iter_sections()],
    }
    stream, rows = writer(output / "symbols.tsv",
                          ["address", "size", "type", "bind", "section", "name", "demangled"])
    for item in sorted(native.symbols.values(), key=lambda s: (s["address"], s["name"])):
        rows.writerow([hex(item["address"]), item["size"], item["type"], item["bind"],
                       item["section"], item["name"], native.label(item["name"])])
    stream.close()
    stream, rows = writer(output / "functions.tsv", ["address", "size", "source", "name", "demangled"])
    for address, item in sorted(native.functions.items()):
        rows.writerow([hex(address), item["size"], item["source"], item["name"], native.label(item["name"])])
    stream.close()
    stream, rows = writer(output / "strings.tsv", ["address", "file_offset", "section", "text"])
    string_count = 0
    for section in native.elf.iter_sections():
        if section.name not in (".rodata", ".data", ".data.rel.ro"):
            continue
        for match in re.finditer(rb"[^\x00]{4,}", section.data()):
            try:
                value = match.group().decode("utf-8")
            except UnicodeDecodeError:
                continue
            if not all(c.isprintable() or c in "\n\r\t" for c in value):
                continue
            rows.writerow([hex(section["sh_addr"] + match.start()),
                           section["sh_offset"] + match.start(), section.name, value])
            string_count += 1
    stream.close()
    stream, rows = writer(output / "branches.tsv",
                          ["instruction", "kind", "caller", "caller_name", "target", "target_name"])
    section = native.elf.get_section_by_name(".text")
    counts = {"symbols": len(native.symbols), "functions": len(native.functions),
              "strings": string_count, "direct_calls": 0, "direct_tail_branches": 0,
              "indirect_calls": 0, "indirect_branches": 0}
    for i, (word,) in enumerate(struct.iter_unpack("<I", section.data())):
        address = section["sh_addr"] + 4 * i
        kind, target, target_name = None, "", ""
        owner = native.owner(address)
        if word & 0x7C000000 == 0x14000000:
            immediate = word & 0x3FFFFFF
            immediate -= (1 << 26) if immediate & (1 << 25) else 0
            target_address = address + 4 * immediate
            if word & 0x80000000:
                kind = "direct_calls"
            elif target_address in native.targets and (owner is None or target_address != owner["address"]):
                kind = "direct_tail_branches"
            target, target_name = hex(target_address), native.targets.get(target_address, "")
        elif word & 0xFFFFFC1F in (0xD63F0000, 0xD61F0000):
            kind = "indirect_calls" if word & 0xFFFFFC1F == 0xD63F0000 else "indirect_branches"
            target = f"x{(word >> 5) & 31}"
        if kind:
            counts[kind] += 1
            rows.writerow([hex(address), kind, hex(owner["address"]) if owner else "",
                           native.label(owner["name"]) if owner else "", target, target_name])
    stream.close()
    if assembly:
        print(path.name, "writing disassembly", flush=True)
        decoded_bytes = 0
        with gzip.open(output / "disassembly.txt.gz", "wt") as stream:
            for address, size, mnemonic, operands in Cs(CS_ARCH_ARM64, CS_MODE_ARM).disasm_lite(
                    section.data(), section["sh_addr"]):
                decoded_bytes += size
                if address in native.functions:
                    stream.write(f"\n{native.targets[address]}:\n")
                suffix = ""
                if mnemonic in ("bl", "b") and operands.startswith("#"):
                    target = int(operands[1:], 0)
                    if target in native.targets:
                        suffix = " ; " + native.targets[target]
                stream.write(f"{address:08x}  {mnemonic:8s} {operands}{suffix}\n")
        if decoded_bytes != section["sh_size"]:
            raise ValueError(f"Incomplete .text disassembly: {decoded_bytes}/{section['sh_size']} bytes")
        metadata["disassembly"] = {"section": ".text", "decoded_bytes": decoded_bytes,
                                   "complete": True}
    counts.update(export_dwarf(native, output))
    metadata["counts"] = counts
    metadata["limitations"] = [
        "No reconstructed original source files; DWARF paths, types and lines only.",
        "Direct call and tail-branch edges only; indirect targets remain unresolved.",
        "Presence in this ELF does not establish runtime use on this vehicle.",
        "No source-to-source or high-level decompiler has been run.",
    ]
    metadata_path.write_text(json.dumps(metadata, indent=2) + "\n")
    print(path.name, json.dumps(counts), flush=True)
    native.close()


def function(path, pattern, maximum):
    native = Native(path)
    matches = [item for address, item in sorted(native.functions.items())
               if pattern == hex(address) or re.search(pattern, native.label(item["name"]))]
    if len(matches) > maximum:
        raise ValueError(f"{len(matches)} matches; narrow pattern or raise --max-matches")
    for item in matches:
        print(f"\nFUNCTION {item['address']:#x} size={item['size']:#x} {native.label(item['name'])}")
        decoded_bytes = 0
        for address, size, mnemonic, operands in Cs(CS_ARCH_ARM64, CS_MODE_ARM).disasm_lite(
                native.bytes_at(item["address"], item["size"]), item["address"]):
            decoded_bytes += size
            suffix = ""
            if mnemonic in ("bl", "b") and operands.startswith("#"):
                target = int(operands[1:], 0)
                suffix = " ; " + native.targets.get(target, "unknown")
            print(f"{address:08x}  {mnemonic:8s} {operands}{suffix}")
        if decoded_bytes != item["size"]:
            raise ValueError(f"Incomplete function disassembly at {item['address']:#x}")
    native.close()
    if not matches:
        raise ValueError("No matching function")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    ix = sub.add_parser("index")
    ix.add_argument("elf", type=Path)
    ix.add_argument("--out", type=Path, required=True)
    ix.add_argument("--assembly", action="store_true")
    fn = sub.add_parser("function")
    fn.add_argument("elf", type=Path)
    fn.add_argument("pattern")
    fn.add_argument("--max-matches", type=int, default=8)
    args = parser.parse_args()
    if args.command == "index":
        index(args.elf, args.out, args.assembly)
    else:
        function(args.elf, args.pattern, args.max_matches)


if __name__ == "__main__":
    main()
