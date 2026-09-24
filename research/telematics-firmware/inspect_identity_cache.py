#!/usr/bin/env python3
"""Static candidate inventory for the reviewed cloudmanager identity cache.

Requires pyelftools and Capstone. No executable loading or vehicle access.
Immediate offsets are candidates for manual review, not complete alias analysis.
"""
import argparse
import bisect
import hashlib
import json
from pathlib import Path

from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
from capstone.arm64_const import ARM64_OP_IMM, ARM64_OP_MEM
from elftools.elf.elffile import ELFFile

EXPECTED = "9e36cdbf841d54a3b1ea3631b5d5b867d5908bd191a113f53b5bb4182df82eb9"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("firmware", type=Path)
    parser.add_argument("function_ranges", type=Path)
    args = parser.parse_args()
    if hashlib.sha256(args.firmware.read_bytes()).hexdigest() != EXPECTED:
        raise ValueError("Unreviewed firmware")
    ranges = json.loads(args.function_ranges.read_text())
    starts = [start for start, size in ranges]

    def function(address):
        index = bisect.bisect_right(starts, address) - 1
        return hex(starts[index]) if index >= 0 and address < sum(ranges[index]) else None

    with args.firmware.open("rb") as stream:
        elf = ELFFile(stream)
        text = elf.get_section_by_name(".text")
        code, base = text.data(), text["sh_addr"]
        rel = list(elf.get_section_by_name(".rela.dyn").iter_relocations())
        singleton = next(r["r_addend"] for r in rel if r["r_offset"] == 0x8eeb8)
        initializer = [{"offset": hex(r["r_offset"]), "target": hex(r["r_addend"])}
                       for r in rel if r["r_addend"] == 0x73208]
        init = elf.get_section_by_name(".init_array")
        init_range = [hex(init["sh_addr"]), hex(init["sh_addr"] + init["sh_size"])]

    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.detail = True
    calls, fields, refs = [], [], []
    targets = {0x6d9f4, 0x6dac4, 0x6dcf0, 0x4ece0, 0x6e00c, 0x73208}
    for instruction in md.disasm(code, base):
        ops = instruction.operands
        row = {"address": hex(instruction.address), "function": function(instruction.address),
               "instruction": instruction.mnemonic + " " + instruction.op_str}
        if instruction.mnemonic in ("b", "bl") and ops[0].type == ARM64_OP_IMM and ops[0].imm in targets:
            calls.append(row)
        if (any(o.type == ARM64_OP_MEM and o.mem.disp in (0x64, 0x79, 0xa7) for o in ops)
                or (instruction.mnemonic == "add" and any(o.type == ARM64_OP_IMM and o.imm in (0x64, 0x79, 0xa7) for o in ops))):
            fields.append(row)
        if (any(o.type == ARM64_OP_MEM and o.mem.disp == 0xeb8 for o in ops)
                or any(o.type == ARM64_OP_IMM and o.imm == singleton for o in ops)):
            refs.append(row)
    print(json.dumps({
        "firmware_sha256": EXPECTED, "scope": __doc__, "singleton_address": hex(singleton),
        "init_array": init_range, "initializer_relocations": initializer,
        "direct_calls": calls, "candidate_field_accesses": fields,
        "candidate_singleton_refs": refs,
    }, indent=2))


if __name__ == "__main__":
    main()
