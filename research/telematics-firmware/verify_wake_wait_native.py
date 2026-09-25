#!/usr/bin/env python3
"""Original wake wait/getter replay, synthetic SDK/clock boundaries only.

No sockets, ADB or vehicle operations. Delayed wake below is an explicitly
injected external state fixture; it does NOT qualify native event dispatch,
queued controls, vehicle wake, power retention or a persistent adapter.
"""
import argparse
import json
import struct
from pathlib import Path
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_PC, UC_ARM64_REG_LR
from verify_native_control import NativeControl
from verify_opaque_native import OBJ, EXPECTED, need


class WakeWait(NativeControl):
    def __init__(self, source, *, initial_mcu=0, acc=0, event_wait=None,
                 event_value=1, setter_result=0, wait_result=110, spurious=0):
        self.mcu = initial_mcu
        self.event_wait, self.event_value = event_wait, event_value
        self.setter_result, self.wait_result, self.spurious = setter_result, wait_result, spurious
        self.clock_ns = 1_000_000_000
        self.setters, self.waits, self.reasons = [], [], []
        self.mutex_held = False
        super().__init__(source, acc=acc)
        self.u.mem_write(OBJ + 0x284, struct.pack('<I', initial_mcu))
        # Explicit fixture: secondary wake listeners absent, not a real car default.
        self.put64(OBJ + 0x2e8, 0)

    def hook(self, engine, at, size, data):
        if at in (0x5c5f4, 0x48280):
            if at == 0x48280:
                need(self.reg(0) == OBJ and self.reg(1) == 6, 'wake listener reason boundary')
                self.reasons.append(self.reg(1))
            return
        if any(lo <= at < hi for lo, hi in ((0x5c5f4, 0x5c71c), (0x48280, 0x48468))):
            return
        result = 0
        if at == 0x88880 and (self.reg(1), self.reg(2)) == (1005, 0x99000003):
            self.u.mem_write(self.reg(3), struct.pack('<I', self.mcu))
            self.getters.append((self.reg(1), self.reg(2)))
        elif at == 0x88b70:
            need((self.reg(1), self.reg(2), self.reg(3)) == (1005, 0xaa00004a, 1),
                 'unexpected wake setter')
            self.setters.append({'device': self.reg(1), 'fid': hex(self.reg(2)), 'value': self.reg(3)})
            result = self.setter_result & 0xffffffff
        elif at == 0x88b90:
            need(self.reg(0) == OBJ + 0x3c0 and not self.mutex_held, 'wake mutex lock boundary')
            self.mutex_held = True
        elif at == 0x88bc0:
            need(self.reg(0) == OBJ + 0x3c0 and self.mutex_held, 'wake mutex unlock boundary')
            self.mutex_held = False
        elif at == 0x88ba0:
            result = self.clock_ns
        elif at == 0x88bb0:
            need((self.reg(0), self.reg(1), self.reg(2)) == (OBJ + 0x418, OBJ + 0x3c0, 1)
                 and self.mutex_held, 'wake condition boundary')
            sec, ns = struct.unpack('<qq', self.u.mem_read(self.reg(3), 16))
            deadline = sec * 1_000_000_000 + ns
            need(0 < deadline - self.clock_ns <= 1_000_000_000, 'native wake wait bound')
            self.waits.append({'deadline_ns': deadline, 'state_before': self.mcu})
            need(len(self.waits) <= 16, 'condition fixture bound')
            if len(self.waits) == self.event_wait:
                # External SDK event fixture; no synthetic successful cloud/MCU response.
                self.mcu = self.event_value
                self.u.mem_write(OBJ + 0x284, struct.pack('<I', self.mcu))
                self.clock_ns += min(100_000_000, deadline - self.clock_ns)
                result = 0
            elif self.spurious:
                self.spurious -= 1
                self.clock_ns += min(100_000_000, deadline - self.clock_ns)
                result = 0
            else:
                self.clock_ns = deadline
                result = self.wait_result
        elif at == 0x88bd0:
            raise ValueError('native condition system_error ' + str(self.reg(0)))
        else:
            return super().hook(engine, at, size, data)
        self.u.reg_write(UC_ARM64_REG_X0, result)
        self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))


def verify(source):
    cases = []
    fixtures = [
        ('already_awake', {'initial_mcu': 1}, 1, 0, 0),
        ('no_event_times_out', {}, 0, 3, 3),
        ('sdk_fixture_after_first_wait', {'event_wait': 1}, 1, 1, 1),
        ('sdk_fixture_after_second_attempt', {'event_wait': 2}, 1, 2, 2),
        ('non_awake_event_not_success', {'event_wait': 1, 'event_value': 2}, 0, 3, 4),
        ('setter_failure_does_not_invent_awake', {'setter_result': -1}, 0, 3, 3),
        ('spurious_wakeup_not_success', {'spurious': 2}, 0, 3, 5),
    ]
    for name, kwargs, expected, writes, waits in fixtures:
        machine = WakeWait(source, **kwargs)
        result = machine.call(0x4a96c, OBJ, 3)
        need(result == expected and len(machine.setters) == writes and len(machine.waits) == waits,
             'wake fixture mismatch ' + name)
        need(not machine.mutex_held, 'normal wake path retained mutex')
        need(not machine.vehicle_writes and not machine.can_writes, 'helper emitted unexpected buffer')
        cases.append({'case': name, 'result': result, 'wake_setters': machine.setters,
                      'condition_waits': machine.waits, 'listener_reasons': machine.reasons})
    machine = WakeWait(source, wait_result=22)
    try:
        machine.call(0x4a96c, OBJ, 3)
        raise AssertionError('condition error accepted')
    except ValueError as error:
        need(str(error) == 'native condition system_error 22', 'wrong exception boundary')
        cases.append({'case': 'condition_error_is_not_success', 'boundary': str(error)})
    return {'passed': True, 'firmware_sha256': EXPECTED, 'qualified_for_product': False,
            'cases': cases, 'scope': 'Original 0x4a96c and 0x5c5f4 instructions; SDK reads, '
            'wake writes, condition waits and clock explicitly synthetic. Listener list empty. '
            'Injected MCU state is NOT a verified native callback. No 536 queue closure, '
            'actual wake/power behavior or transport readiness claimed.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('firmware', type=Path)
    print(json.dumps(verify(parser.parse_args().firmware), indent=2))
