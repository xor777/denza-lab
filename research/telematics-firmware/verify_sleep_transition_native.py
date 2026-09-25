#!/usr/bin/env python3
"""Bounded offline original-code MCU 1->0->1 transition investigation.

Native writes and callbacks remain captured external boundaries. This does not
simulate a live SDK event source, timer delivery, or cloud session.
"""
import argparse
import json
from pathlib import Path

from unicorn.arm64_const import UC_ARM64_REG_LR, UC_ARM64_REG_PC, UC_ARM64_REG_X0

from verify_wake_callback_native import WakeCallback
from verify_opaque_native import OBJ, STACK, EXPECTED, need

SECONDARY = 0x911e8


class SleepTransition(WakeCallback):
    def __init__(self, source):
        self.sleep_dependencies = []
        self.unlock_index = 0
        super().__init__(source, initial_mcu=1)
        need(self.get64(0x8ec40) == SECONDARY, 'secondary BSS relocation')
        self.call(0x4404c, SECONDARY)
        self.put64(OBJ + 0x30, SECONDARY)
        self.put64(OBJ, self.get64(0x8eca8) + 0x18)
        self.put64(0x91190, OBJ)
        need(self.get64(OBJ + 0x2e8) == 0 and
             self.get64(0x91128) == self.get64(0x91130) == 0,
             'explicitly empty listener and scheduled-work vector fixtures')

    def hook(self, engine, at, size, data):
        if at == 0x885c0 and self.string(self.reg(0)) == b'persist.sys.cloud.unlock_type':
            need(self.string(self.reg(1)) == b'0', 'sleep unlock type reset boundary')
            self.property_writes.append(('persist.sys.cloud.unlock_type', '0'))
            self.u.reg_write(UC_ARM64_REG_X0, 0)
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if at == 0x885c0 and self.string(self.reg(0)) == b'sys.cloud.unlock_index':
            need(self.string(self.reg(1)) == str(self.unlock_index + 1).encode() and
                 self.unlock_index < 10, 'sleep unlock index progression')
            self.unlock_index += 1
            self.property_writes.append(('sys.cloud.unlock_index', str(self.unlock_index)))
            self.u.reg_write(UC_ARM64_REG_X0, 0)
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if at == 0x48698 and self.string(self.reg(1)) == b'sys.cloud.unlock_index':
            need(self.reg(2) == 0, 'sleep unlock index default')
            self.property_reads.append('sys.cloud.unlock_index')
            self.u.reg_write(UC_ARM64_REG_X0, self.unlock_index)
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if at == 0x66980:
            need(self.get64(0x91190) == OBJ, 'singleton fixture absent')
            slot = self.u.reg_read(UC_ARM64_REG_X0 + 8)
            need(STACK <= slot < STACK + 0x10000, 'singleton destination')
            self.put64(slot, OBJ)
            self.sleep_dependencies.append('singleton_selector')
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if at == 0x887b0:
            need(self.reg(0) == SECONDARY + 0x90 and self.string(self.reg(1)) == b'',
                 'secondary empty string constructor')
            self.u.mem_write(self.reg(0), bytes(24))
            self.u.reg_write(UC_ARM64_REG_X0, self.reg(0))
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if any(lo <= at < hi for lo, hi in (
                (0x42448, 0x42490), (0x4404c, 0x440c0),
                (0x4689c, 0x468d0), (0x480c4, 0x48240),
                (0x546f8, 0x548cc), (0x451f0, 0x45380),
                (0x48018, 0x480c0))):
            if at in (0x480c4, 0x546f8, 0x451f0):
                self.sleep_dependencies.append(hex(at))
            return
        return super().hook(engine, at, size, data)

    def mcu_event(self, value):
        need(value in (0, 1), 'external MCU state fixture')
        self.mcu = value
        self.call(0x5d87c, OBJ, value)


def verify(source):
    machine = SleepTransition(source)
    need(machine.u.mem_read(OBJ + 0x284, 4) == b'\1\0\0\0',
         'initial awake MCU state')
    machine.mcu_event(0)
    need(machine.u.mem_read(OBJ + 0x284, 4) == b'\0\0\0\0' and
         len(machine.native_502) == 1 and machine.sends == [502] and
         machine.sleep_dependencies == ['0x480c4', '0x546f8', '0x451f0',
                                        'singleton_selector', 'singleton_selector',
                                        'singleton_selector'] and
         0x5da6c in machine.internal_visits and 0x5dac4 in machine.internal_visits and
         machine.property_writes == [
             ('persist.sys.cloud.unlock_type', '0'),
             ('sys.cloud.unlock_index', '1')] and
         machine.property_reads.count('sys.cloud.unlock_index') == 1 and
         not machine.wake_notifies and not machine.vehicle_writes,
         'original sleep callback effects')
    sleeping = {'mcu_state': 0, 'native_502_body_hex': machine.native_502[0][1].hex(),
                'native_502_send_count': len(machine.sends),
                'native_function_visits': machine.sleep_dependencies[:],
                'property_reads': machine.property_reads[:],
                'property_writes': machine.property_writes[:],
                'condition_notifies': len(machine.wake_notifies),
                'vehicle_writes': len(machine.vehicle_writes)}
    machine.mcu_event(1)
    need(machine.u.mem_read(OBJ + 0x284, 4) == b'\1\0\0\0' and
         len(machine.native_502) == 2 and machine.sends == [502, 502] and
         machine.wake_notifies == ['notify_all'] and
         0x53818 in machine.internal_visits and 0x5c940 in machine.internal_visits and
         0x5de60 in machine.internal_visits and
         machine.property_writes == sleeping['property_writes'] and
         not machine.vehicle_writes and not machine.queue_writes,
         'original awake return effects')
    awake = {'mcu_state': 1, 'native_502_body_hex': machine.native_502[1][1].hex(),
             'native_502_send_count': len(machine.sends),
             'condition_notifies': len(machine.wake_notifies),
             'lifecycle_0x53818': True, 'lifecycle_getter_0x5c940': True,
             'drain_0x5de60': True,
             'vehicle_writes': len(machine.vehicle_writes)}
    return {'passed': True, 'qualified_for_product': False,
            'firmware_sha256': EXPECTED,
            'sleep': sleeping, 'awake_after_sleep': awake,
            'native_502_bodies_are_original': True,
            'explicit_external_fixtures': [
                'MCU callback delivery and matching SDK getter state 1->0->1',
                'synthetic logged-in session and partial primary OBJ',
                'secondary control object constructed by original 0x4404c',
                'primary listeners OBJ+0x2e0/0x2e8 empty',
                'scheduled-work vector 0x91128/0x91130 empty',
                'singleton selector 0x66980 points to partial OBJ',
                'sys.cloud.unlock_index starts at 0 and captures native write to 1',
                'all property/SDK/network/timer outward effects capture-only'],
            'remaining_boundary': ('Real SDK event/listener delivery, nonempty listener and '
                                   'scheduled-work vectors, timer scheduling, Binder property '
                                   'effects and cloud delivery remain unproved.')}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('firmware', type=Path)
    print(json.dumps(verify(parser.parse_args().firmware), indent=2))
