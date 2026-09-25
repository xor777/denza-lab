#!/usr/bin/env python3
"""Bounded offline check of stock startup after Binder publication failure.

Uses synthetic objects and Binder stubs, with no Android, syscalls, network or
vehicle access. Executes the original factory path and main control flow;
the object constructor and downstream initialization are not executed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_PC, UC_ARM64_REG_LR
from verify_identity_native import IdentityMachine, EXPECTED, OBJ, HELPER, OUTPUT, STOP

MANAGER, VTABLE = OUTPUT + 0x1000, OUTPUT + 0x2000
ADD_SERVICE, CHECK_SERVICE = STOP + 0x100, STOP + 0x200


class StartupMachine(IdentityMachine):
    def __init__(self, source, publish_status, existing_service):
        self.startup = False
        super().__init__(source)
        self.startup = True
        self.publish_status = publish_status
        self.existing_service = existing_service
        self.events = []
        self.factory_result = None
        # Preconstructed synthetic native object: do not run its worker threads.
        singleton = self.read64(0x8ecb0)
        self.write64(singleton, OBJ)
        self.write64(OBJ, HELPER + 0x4020)
        self.write64(OBJ + 8, HELPER + 0x4120)
        self.write64(MANAGER, VTABLE)
        self.write64(VTABLE + 0x30, ADD_SERVICE)
        self.write64(VTABLE + 0x28, CHECK_SERVICE)
        self.write64(self.read64(0x8ecc8), 1)  # SRE static guard already initialized.

    def read64(self, at):
        return struct.unpack('<Q', self.u.mem_read(at, 8))[0]

    def write64(self, at, value):
        self.u.mem_write(at, struct.pack('<Q', value))

    def finish_stub(self, result=0):
        self.u.reg_write(UC_ARM64_REG_X0, result & 0xffffffffffffffff)
        self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))

    def hook(self, engine, address, size, data):
        if not self.startup:
            return super().hook(engine, address, size, data)
        self.instructions += 1
        if address == ADD_SERVICE:
            assert self.reg(0) == MANAGER
            self.events.append('publish_service')
            return self.finish_stub(self.publish_status)
        if address == CHECK_SERVICE:
            assert self.reg(0) == MANAGER
            self.write64(self.reg(8), OBJ + 8 if self.existing_service else 0)
            self.events.append('check_service')
            return self.finish_stub()
        if address == 0x88d10:  # defaultServiceManager, hidden structure return
            self.write64(self.reg(8), MANAGER)
            return self.finish_stub()
        if address == 0x88eb0:  # ProcessState::self
            self.write64(self.reg(8), 0)
            return self.finish_stub()
        if address == 0x88c90:  # String16 construction
            assert bytes(self.u.mem_read(self.reg(1), 13)) == b'cloudmanager\0'
            return self.finish_stub()
        if address == 0x5b744:
            assert self.reg(0) == OBJ
            self.events.append('stock_initialize_requested')
            return self.finish_stub()
        if address == 0x668c0:
            self.events.append('publication_failure_logged')
        if address == 0x67180:
            value = self.reg(0) & 0xffffffff
            self.factory_result = value if value < 2**31 else value - 2**32
        if address in (0x66a74, 0x7e460):
            self.events.append('logger_setup_stubbed' if address == 0x66a74 else 'sre_setup_stubbed')
            return self.finish_stub()
        if address == 0x88ed0:
            self.events.append('binder_thread_pool_requested')
            return self.finish_stub()
        if address in (0x883c0, 0x88410, 0x883f0, 0x88950, 0x88ea0, 0x88ec0, 0x88c00):
            return self.finish_stub()
        if not (0x666e4 <= address < 0x668f8 or 0x67114 <= address < 0x67214):
            raise ValueError('Unexpected native target ' + hex(address))
        word = struct.unpack('<I', self.u.mem_read(address, 4))[0]
        if word & 0xffe0001f == 0xd4000001:
            raise ValueError('Syscall forbidden')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('firmware', type=Path)
    args = parser.parse_args()
    if hashlib.sha256(args.firmware.read_bytes()).hexdigest() != EXPECTED:
        raise ValueError('Unreviewed firmware')
    cases = []
    for name, status, existing in (
        ('publication_success', 0, True),
        ('publication_denied_existing_service_found', -13, True),
        ('publication_denied_no_service_found', -13, False),
    ):
        m = StartupMachine(args.firmware, status, existing)
        assert m.call(0x67114) == 0
        assert m.factory_result == status
        assert m.events.count('stock_initialize_requested') == 1
        assert m.events.count('binder_thread_pool_requested') == 1
        assert ('publication_failure_logged' in m.events) == (not existing)
        cases.append(dict(name=name, factory_return=status, main_return=0,
                          events=m.events, instructions=m.instructions))
    print(json.dumps(dict(synthetic_only=True, firmware_sha256=EXPECTED,
                         scope=__doc__, all_passed=True, cases=cases), indent=2))


if __name__ == '__main__':
    main()
