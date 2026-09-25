#!/usr/bin/env python3
"""Offline original 532 checks and opaque MCU forwarding; no live I/O.

Synthetic packet/getter fixtures only. Native instructions decide whether to
forward and construct both cloud replies and the MCU envelope. Every side effect
is captured. This is not a command executor or a product runtime.
"""
import argparse
import json
import struct
from pathlib import Path

from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_PC, UC_ARM64_REG_LR
from verify_opaque_native import OBJ, AUX, INPUT, EXPECTED, need
from verify_native_roundtrip import Roundtrip, HELPER, PACKET, WIRE, CORRELATION

# Dispatcher plus the original control reply builders and opaque forwarding.
# All outbound Binder/network/timer boundaries below are capture-only stubs.
CONTROL_RANGES = [(0x573ec, 0x5ac98), (0x6fa08, 0x6fb98), (0x5fe04, 0x60268),
                  (0x6f3a4, 0x6f408), (0x6039c, 0x60b20), (0x713b0, 0x714bc),
                  (0x4a96c, 0x4ab90), (0x5ad9c, 0x5aef4), (0x6fc54, 0x6fd18)]


class NativeControl(Roundtrip):
    def __init__(self, source, *, speed=0, soc=79.0, acc=2, busy=False):
        self.control_bodies = []
        self.vehicle_writes = []
        self.can_writes = []
        self.timers = []
        self.results = []
        self.result_sends = 0
        self.getters = []
        self.speed, self.soc, self.acc = speed, soc, acc
        super().__init__(source)
        self.put64(0x8edf0, AUX + 0x7000)
        self.put64(0x8edd8, AUX + 0x8000)
        self.put64(0x8ef28, AUX + 0x9000)
        self.put64(0x8ef30, AUX + 0xa000)
        self.put64(0x8ebf8, AUX + 0xb000)
        self.put64(OBJ + 0x2b0, AUX + 0xc000)
        self.put64(0x8ef40, AUX + 0xd000)
        self.u.mem_write(OBJ + 0x284, struct.pack("<I", 1))
        self.u.mem_write(OBJ + 0x28c, struct.pack('<I', acc))
        self.u.mem_write(OBJ + 0x290, struct.pack('<I', 1))
        self.u.mem_write(OBJ + 0x254, bytes([int(busy)]))

    def hook(self, engine, at, size, data):
        u = self.u
        result = 0
        if at == 0x57560:
            return super().hook(engine, at, size, data)
        if at == 0x6e3b0 and self.reg(3) in (532, 536):
            need(0 <= self.reg(6) <= 128, 'control body bound')
            self.control_bodies.append((self.reg(4), bytes(u.mem_read(self.reg(5), self.reg(6)))))
            return
        if at == 0x88890:
            pass  # Single-thread isolated native mutex.
        elif at == 0x4ad30:
            need(self.string(self.reg(2)) == b'%d' and self.reg(3) == 0, 'format boundary')
            need(self.reg(1) >= 2, 'format bound')
            u.mem_write(self.reg(0), b'0\0')
            result = 1
        elif at == 0x885c0:
            need(self.string(self.reg(0)) == b'sys.cloud.remote_controling' and
                 self.string(self.reg(1)) == b'0', 'captured property boundary')
        elif at == 0x739a0:
            need(self.reg(0) == AUX + 0xc000 and self.reg(1) == 1, 'result sender boundary')
            self.result_sends += 1
        elif at == 0x54e20:
            self.results.append((self.reg(1), self.reg(2), self.reg(3)))
        elif at == 0x39288:
            pass  # RefBase scope destructor in isolated context.
        elif at == 0x71888:
            need(self.reg(2) == 8, "time diagnostic boundary")
        elif at == 0x48698:
            need(self.string(self.reg(1)) == b'persist.sys.repair_mode.enable', 'property boundary')
            result = 0  # Explicit normal-mode fixture, not a live default.
        elif at == 0x88880:
            key = (self.reg(1), self.reg(2))
            values = {(1014, 0x14400008): self.speed, (1023, 0x2f4000fa): 1,
                      (1001, 0x12d0002a): self.acc}
            need(key in values, 'unexpected integer getter ' + str(key))
            self.getters.append(key)
            u.mem_write(self.reg(3), struct.pack('<I', values[key]))
        elif at == 0x88cc0:
            need((self.reg(1), self.reg(2)) == (1014, 0x4a505038), 'SOC getter boundary')
            self.getters.append((1014, 0x4a505038))
            u.mem_write(self.reg(3), struct.pack('<f', self.soc))
        elif at == 0x4a41c:
            need(self.reg(0) == OBJ and self.reg(1) == OBJ + 0x5d8, 'timer boundary')
            self.timers.append({'seconds': self.reg(3), 'callback': hex(self.reg(4))})
        elif at == 0x4ab90 and self.reg(2) == 0xaa00001e:
            need(self.reg(0) == OBJ and self.reg(1) == 1034 and self.reg(4) == 14, 'native CAN boundary')
            self.can_writes.append(bytes(u.mem_read(self.reg(3), self.reg(4))))
        elif at == 0x4ab90 and self.reg(2) == 0xaa000004:
            need(self.reg(0) == OBJ and self.reg(1) == 1034 and 0 < self.reg(4) <= 256,
                 'opaque vehicle write boundary')
            self.vehicle_writes.append(bytes(u.mem_read(self.reg(3), self.reg(4))))
        elif any(lo <= at < hi for lo, hi in CONTROL_RANGES):
            need(int.from_bytes(u.mem_read(at, 4), 'little') & 0xffe0001f != 0xd4000001,
                 'native syscall')
            return
        else:
            return super().hook(engine, at, size, data)
        u.reg_write(UC_ARM64_REG_X0, result)
        u.reg_write(UC_ARM64_REG_PC, u.reg_read(UC_ARM64_REG_LR))

    def request(self, subcommand=3):
        # Synthetic offline fixture only, deliberately not a usable climate setting.
        need(0 <= subcommand <= 255, "synthetic subcommand range")
        opaque = CORRELATION + bytes([subcommand, 0, 0, 0])
        self.u.mem_write(INPUT, opaque)
        self.call(0x6e3b0, HELPER, 0, PACKET, 532, 254, INPUT, len(opaque), 0)
        self.call(0x6e858, HELPER, PACKET, WIRE, len(opaque))
        return self.frames[-1], opaque


def verify(source):
    frame, opaque = NativeControl(source).request()
    cases = []
    for name, kwargs, expected_reason in (
        ('stationary_acc_on_forwarded', {}, None),
        ('moving_rejected', {'speed': 2}, 10),
        ('low_soc_rejected', {'soc': 9.0}, 14),
        ('busy_rejected', {'busy': True}, 12),
    ):
        m = NativeControl(source, **kwargs)
        m.receive(frame)
        if expected_reason is None:
            need(len(m.vehicle_writes) == 1, 'native forwarding missing')
            need(m.vehicle_writes[0][6:] == opaque, 'opaque payload changed')
            need(m.timers and m.timers[0]['seconds'] == 16, 'native timeout missing')
        else:
            need(not m.vehicle_writes, 'native refusal forwarded')
            need(m.control_bodies[-1][0] == 2 and m.control_bodies[-1][1][19] == expected_reason,
                 'native refusal reason mismatch')
        cases.append({'case': name, 'vehicle_writes_captured': len(m.vehicle_writes),
                      'native_reason': expected_reason, 'getters': m.getters,
                      'cloud_reply_flags': [f for f, _ in m.control_bodies]})
    # A native-generated envelope reused solely as a synthetic MCU reply fixture.
    # Intermediate reply3 retains busy; cloud continuation4 is correlated; final1 clears it.
    m = NativeControl(source)
    m.receive(frame)
    intermediate = bytearray(m.vehicle_writes[-1])
    intermediate[4] = 3
    m.u.mem_write(INPUT, bytes(intermediate))
    m.call(0x6039c, OBJ, 0x99000004, INPUT, len(intermediate))
    need(m.u.mem_read(OBJ + 0x254, 1) == b'\1' and m.result_sends == 1,
         'intermediate reply lost busy or send')
    need(m.control_bodies[-1] == (3, opaque), 'intermediate body changed')
    peer = NativeControl(source)
    peer.u.mem_write(INPUT, opaque)
    peer.call(0x6e3b0, HELPER, 0, PACKET, 532, 4, INPUT, len(opaque), 0)
    peer.call(0x6e858, HELPER, PACKET, WIRE, len(opaque))
    m.receive(peer.frames[-1])
    need(len(m.vehicle_writes) == 2 and m.vehicle_writes[-1][4] == 4 and
         m.vehicle_writes[-1][6:] == opaque, 'cloud continuation not forwarded')
    terminal = bytearray(intermediate)
    terminal[4] = 1
    m.u.mem_write(INPUT, bytes(terminal))
    m.call(0x6039c, OBJ, 0x99000004, INPUT, len(terminal))
    need(m.u.mem_read(OBJ + 0x254, 1) == b'\0' and m.result_sends == 2 and
         m.results[-1] == (3, 1, 0), 'terminal reply did not complete')
    need(m.control_bodies[-1] == (1, opaque), 'terminal body changed')
    cases.append({'case': 'native_intermediate_cloud_continuation_terminal',
                  'vehicle_writes_captured': len(m.vehicle_writes), 'result_sends': m.result_sends})
    peer = NativeControl(source)
    peer.u.mem_write(INPUT, b'\1')
    peer.call(0x6e3b0, HELPER, 0, PACKET, 536, 254, INPUT, 1, 0)
    peer.call(0x6e858, HELPER, PACKET, WIRE, 1)
    m = NativeControl(source)
    m.receive(peer.frames[-1])
    need(m.control_bodies == [(1, b'\1')] and len(m.can_writes) == 1,
         'native already-awake response missing')
    cases.append({'case': 'native_536_already_awake', 'native_can_writes_captured': len(m.can_writes)})
    peer.call(0x6e3b0, HELPER, 0, PACKET, 536, 254, INPUT, 0, 0)
    peer.call(0x6e858, HELPER, PACKET, WIRE, 0)
    m = NativeControl(source)
    m.receive(peer.frames[-1])
    need(m.control_bodies == [(1, b'\1')] and len(m.can_writes) == 1 and
         len(m.vehicle_writes) == 1 and len(m.vehicle_writes[0]) == 7,
         'empty536 did not use native wake forwarding')
    wake_reply = bytearray(m.vehicle_writes[0])
    wake_reply[4:7] = bytes([1, 1, 1])
    m.u.mem_write(INPUT, bytes(wake_reply))
    m.call(0x6039c, OBJ, 0x99000004, INPUT, len(wake_reply))
    need(m.result_sends == 1 and not m.results, '536 callback treated as climate terminal')
    cases.append({'case': 'native_536_empty_request_and_mcu_reply', 'native_result_sends': m.result_sends})
    return {'passed': True, 'firmware_sha256': EXPECTED, 'cases': cases,
            'scope': 'Offline synthetic inputs; all getters, writes, timers and network are stubs. '
                     'Original result callbacks and cloud continuation use synthetic fixtures. No sleeping MCU or real command tested.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('firmware', type=Path)
    print(json.dumps(verify(parser.parse_args().firmware), indent=2))
