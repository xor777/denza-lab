#!/usr/bin/env python3
"""Execute only the stock MQTT ICCID getter with synthetic properties.

No device, networking, authentication, native syscalls or MQTT client startup.
This checks source selection; broker authentication and caller caches are not
executed. Requires pyelftools and Unicorn 2.1.4.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import (UC_ARM64_REG_X0, UC_ARM64_REG_SP,
    UC_ARM64_REG_LR, UC_ARM64_REG_PC, UC_ARM64_REG_TPIDR_EL0, UC_ARM64_REG_CPACR_EL1)

EXPECTED = '31d3196145b7ab48e179bf2c9bdcb3032a217b9507e2f29b6cceecf3c10db4fb'
STACK, TLS, OUTPUT, STOP = 0x200000, 0x210000, 0x220000, 0x230000
A, B = '89010000000000000001', '89010000000000000002'


class GetterMachine:
    def __init__(self, source):
        self.u = u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        with source.open('rb') as stream:
            elf = ELFFile(stream)
            segments = [s for s in elf.iter_segments() if s['p_type'] == 'PT_LOAD']
            u.mem_map(0, (max(s['p_vaddr'] + s['p_memsz'] for s in segments) + 4095) & ~4095)
            for s in segments:
                u.mem_write(s['p_vaddr'], s.data())
        for address in (STACK, TLS, OUTPUT, STOP):
            u.mem_map(address, 0x10000)
        u.reg_write(UC_ARM64_REG_TPIDR_EL0, TLS)
        u.reg_write(UC_ARM64_REG_CPACR_EL1, 3 << 20)
        u.hook_add(UC_HOOK_CODE, self.hook)

    def reg(self, n):
        return self.u.reg_read(UC_ARM64_REG_X0 + n)

    def cstring(self, address):
        return bytes(self.u.mem_read(address, 92)).split(b'\0', 1)[0]

    def hook(self, engine, address, size, data):
        self.instructions += 1
        result = 0
        if address == 0x90670:
            key = self.cstring(self.reg(0)).decode('ascii')
            assert key in ('ril.csim.iccid', 'persist.radio.iccid')
            assert self.reg(2) == 0
            self.reads.append(key)
            value = self.properties[key].encode('ascii')
            assert len(value) < 92 and b'\0' not in value
            self.u.mem_write(self.reg(1), value + b'\0')
            result = len(value)
        elif address in (0x90350, 0x903c0):
            result = len(self.cstring(self.reg(0)))
        elif address == 0x90360:
            assert self.reg(2) <= 20
            self.u.mem_write(self.reg(0), bytes(self.u.mem_read(self.reg(1), self.reg(2))))
            result = self.reg(0)
        elif address == 0x8ffc0:
            pass
        else:
            if not 0x4c300 <= address < 0x4c44c:
                raise ValueError('Unexpected native target ' + hex(address))
            word = struct.unpack('<I', self.u.mem_read(address, 4))[0]
            if word & 0xffe0001f == 0xd4000001:
                raise ValueError('Syscall forbidden')
            return
        self.u.reg_write(UC_ARM64_REG_X0, result)
        self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))

    def read(self, current, persistent):
        self.properties = {'ril.csim.iccid': current, 'persist.radio.iccid': persistent}
        self.reads = []; self.instructions = 0
        self.u.mem_write(OUTPUT, b'\xcc' * 24)
        self.u.reg_write(UC_ARM64_REG_X0 + 8, OUTPUT)
        self.u.reg_write(UC_ARM64_REG_SP, STACK + 0xf000)
        self.u.reg_write(UC_ARM64_REG_LR, STOP)
        self.u.emu_start(0x4c300, STOP, timeout=1_000_000, count=10000)
        if self.u.reg_read(UC_ARM64_REG_PC) != STOP:
            raise ValueError('Execution bound reached')
        header = self.u.mem_read(OUTPUT, 1)[0]
        assert not header & 1  # The original function only returns zero or 20 bytes here.
        size = header >> 1
        assert size in (0, 20)
        return bytes(self.u.mem_read(OUTPUT + 1, size)).decode('ascii')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('firmware', type=Path)
    args = parser.parse_args()
    if hashlib.sha256(args.firmware.read_bytes()).hexdigest() != EXPECTED:
        raise ValueError('Unreviewed firmware')
    machine = GetterMachine(args.firmware)
    cases = []
    for name, current, persistent, expected in (
        ('current_takes_priority', A, B, A),
        ('missing_current_uses_persistent', '', B, B),
        ('short_current_uses_persistent', A[:19], B, B),
        ('long_current_uses_persistent', A + '1', B, B),
        ('both_invalid_return_empty', A[:19], B[:19], ''),
        ('both_missing_return_empty', '', '', ''),
        ('twenty_bytes_not_decimal_validated', A[:19] + 'F', B, A[:19] + 'F'),
        ('getter_observes_new_properties', B, A, B),
    ):
        assert machine.read(current, persistent) == expected, name
        assert machine.reads == (['ril.csim.iccid'] if len(current) == 20 else ['ril.csim.iccid', 'persist.radio.iccid'])
        cases.append(dict(name=name, selected_source=machine.reads[-1] if expected else None,
                          property_reads=machine.reads, instructions=machine.instructions))
    print(json.dumps(dict(synthetic_only=True, firmware_sha256=EXPECTED,
                         scope=__doc__, all_passed=True, cases=cases), indent=2))


if __name__ == '__main__':
    main()
