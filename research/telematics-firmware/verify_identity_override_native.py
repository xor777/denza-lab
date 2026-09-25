#!/usr/bin/env python3
"""Offline proof of a process-local identity source for the reviewed firmware.

Synthetic inputs only. Emulates the original constructor, prepare, MD5 and
211/220 body builders. Only the two identity property reads are redirected in
the emulator. This is NOT an installed hook, permission proof or cloud test.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct

from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_PC, UC_ARM64_REG_LR
from verify_identity_native import IdentityMachine, EXPECTED, IMSI_A, IMSI_B, ICCID_A, ICCID_B


class ScopedIdentityMachine(IdentityMachine):
    def __init__(self, source, override):
        self.override = override
        self.redirected_reads = []
        super().__init__(source)

    def hook(self, engine, address, size, data):
        if address == 0x88640:
            key = bytes(self.u.mem_read(self.reg(0), 92)).split(b'\0')[0].decode('ascii')
            caller = self.u.reg_read(UC_ARM64_REG_LR)
            if key in ('ril.csim.iccid', 'ril.imsi') and self.override is not None:
                assert caller == {'ril.csim.iccid': 0x6dd90, 'ril.imsi': 0x6deb4}[key]
                old = self.properties
                self.properties = {**old, **self.override}
                self.redirected_reads.append(key)
                try:
                    return super().hook(engine, address, size, data)
                finally:
                    self.properties = old
        if address == 0x6e3b0:
            assert self.reg(3) in (211, 220)
            assert self.reg(6) == {211: 35, 220: 48}[self.reg(3)]
            self.command = self.reg(3)
            self.body = bytes(self.u.mem_read(self.reg(5), self.reg(6)))
            self.u.emu_stop()
            return
        if address == 0x699a4:  # Local synthetic nonce, no random/device dependency.
            assert self.reg(1) == 16
            self.u.mem_write(self.reg(0), bytes(range(32, 48)))
            self.u.reg_write(UC_ARM64_REG_X0, 0)
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if 0x6f01c <= address < 0x6f0f0:
            word = struct.unpack('<I', self.u.mem_read(address, 4))[0]
            if word & 0xffe0001f == 0xd4000001:
                raise ValueError('Syscall forbidden')
            self.instructions += 1
            return
        return super().hook(engine, address, size, data)

    def check(self, expected_iccid, expected_imsi):
        result = self.step(ICCID_B, IMSI_B)
        assert result['body_hex'] == (expected_imsi + expected_iccid).encode().hex()
        modem_before = dict(self.properties)
        self.call(0x6f01c)
        assert self.command == 220
        assert self.body == bytes(range(32, 48)) + bytes(16) + hashlib.md5(expected_imsi.encode()).digest()
        assert self.properties == modem_before == {'ril.csim.iccid': ICCID_B, 'ril.imsi': IMSI_B}
        return {'registration_pair_matches': True, 'login_digest_matches': True,
                'modem_properties_unchanged': True, 'redirected_reads': list(self.redirected_reads)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('firmware', type=Path)
    args = parser.parse_args()
    if hashlib.sha256(args.firmware.read_bytes()).hexdigest() != EXPECTED:
        raise ValueError('Unreviewed firmware')
    original = {'ril.csim.iccid': ICCID_A, 'ril.imsi': IMSI_A}
    machine = ScopedIdentityMachine(args.firmware, original)
    cases = [dict(name='cold_start_override', **machine.check(ICCID_A, IMSI_A)),
             dict(name='same_process_retry', **machine.check(ICCID_A, IMSI_A))]
    # Reapplying after each process start works at the selected input boundary.
    cases.append(dict(name='new_process_reapply', **ScopedIdentityMachine(args.firmware, original).check(ICCID_A, IMSI_A)))
    # Turning off interception does not rewrite an already-populated native cache.
    machine.override = None
    cases.append(dict(name='disable_in_warm_process_retains_old_cache', **machine.check(ICCID_A, IMSI_A)))
    cases.append(dict(name='new_process_without_override_uses_modem', **ScopedIdentityMachine(args.firmware, None).check(ICCID_B, IMSI_B)))
    zero = next(f'00101{i:010d}' for i in range(10000) if hashlib.md5(f'00101{i:010d}'.encode()).digest()[0] == 0)
    zero_machine = ScopedIdentityMachine(args.firmware, {**original, 'ril.imsi': zero})
    zero_machine.check(ICCID_A, zero)
    cases.append(dict(name='zero_md5_cache_reread_still_uses_override', **zero_machine.check(ICCID_A, zero)))
    print(json.dumps({'synthetic_only': True, 'firmware_sha256': EXPECTED,
                      'scope': __doc__, 'all_passed': True, 'cases': cases}, indent=2))


if __name__ == '__main__':
    main()
