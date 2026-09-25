#!/usr/bin/env python3
"""Offline replay of the original MCU wake callback and opaque queue drain.

Only SDK, property, clock, lock, timer, allocation, and outward I/O boundaries
are fixtures. No ADB, vehicle, network, cloud, or native syscall is performed.
"""
import argparse
import json
import struct
from pathlib import Path

from unicorn.arm64_const import UC_ARM64_REG_LR, UC_ARM64_REG_PC, UC_ARM64_REG_X0

from verify_wake_wait_native import WakeWait
from verify_native_control import NativeControl
from verify_native_roundtrip import HELPER, PACKET, WIRE
from verify_opaque_native import INPUT, OBJ, EXPECTED, need


CALLBACK_RANGES = (
    (0x5d87c, 0x5dc90), (0x6f408, 0x6f518),
    (0x53818, 0x539b0), (0x5c940, 0x5cc6c),
    (0x5de60, 0x5df50), (0x5e88c, 0x5e94c),
    (0x5ef80, 0x5f08c), (0x6f518, 0x6f520),
    (0x6a448, 0x6a4fc), (0x69868, 0x699a4),
    (0x5ccb0, 0x5cf28),
)


class WakeCallback(WakeWait):
    def __init__(self, source, *, initial_mcu=0, vehicle_code=0,
                 recorded_upload=0):
        self.vehicle_code = vehicle_code
        self.recorded_upload = recorded_upload
        self.wake_notifies = []
        self.property_reads = []
        self.property_writes = []
        self.file_removes = []
        self.queue_writes = []
        self.queue_frees = []
        self.native_502 = []
        self.native_timers = []
        self.internal_visits = set()
        super().__init__(source, initial_mcu=initial_mcu)

    def hook(self, engine, at, size, data):
        u = self.u
        self.internal_visits.add(at)
        result = 0
        if at == 0x6e3b0 and self.reg(3) == 502:
            need((self.reg(4), self.reg(6)) == (255, 10), 'native 502 envelope')
            self.native_502.append((self.reg(4), bytes(u.mem_read(self.reg(5), 10))))
            return  # Execute original packet builder, not a reconstructed body.
        if at == 0x69868:
            need(self.reg(0) in (OBJ + 0x5b8, OBJ + 0x5c0),
                 'wake-related native timer owner')
            self.native_timers.append({'owner': self.reg(0), 'callback': self.reg(3),
                                       'context': self.reg(4), 'timer': None})
            return
        if at == 0x88d90:
            need(self.reg(0) == OBJ + 0x418, 'wake condition notification target')
            self.wake_notifies.append('notify_all')
        elif at == 0x88880 and (self.reg(1), self.reg(2)) == (1001, 0x40d00010):
            u.mem_write(self.reg(3), struct.pack('<I', self.vehicle_code))
            self.getters.append((self.reg(1), self.reg(2)))
        elif at == 0x88640:
            key = self.string(self.reg(0))
            values = {b'persist.sys.vehicle_40d_code': str(self.vehicle_code).encode(),
                      b'persist.sys.record_610_upload': str(self.recorded_upload).encode(),
                      b'persist.sys.byd.apn_type': b'double_apn'}
            need(key in values, 'unexpected property read ' + repr(key))
            value = values[key] + b'\0'
            u.mem_write(self.reg(1), value)
            self.property_reads.append(key.decode())
            result = len(value) - 1
        elif at == 0x88a00:  # libc atoi for the two recorded numeric properties.
            raw = self.string(self.reg(0), 32)
            need(raw.isascii() and raw.lstrip(b'-').isdigit(), 'numeric property fixture')
            result = int(raw) & 0xffffffff
        elif at == 0x885c0:
            key, value = self.string(self.reg(0)), self.string(self.reg(1))
            need(key in (b'sys.cloud.remote_controling',
                         b'persist.sys.vehicle_40d_code',
                         b'persist.sys.record_610_upload'), 'unexpected property write')
            self.property_writes.append((key.decode(), value.decode()))
        elif at == 0x4ad30:  # libc snprintf("%d") used for native diagnostic properties.
            need(self.string(self.reg(2)) == b'%d' and 2 <= self.reg(1) <= 32,
                 'native numeric format boundary')
            signed = struct.unpack('<i', struct.pack('<I', self.reg(3) & 0xffffffff))[0]
            value = str(signed).encode()
            need(len(value) < self.reg(1), 'numeric property format bound')
            u.mem_write(self.reg(0), value + b'\0')
            result = len(value)
        elif at == 0x887e0:
            path = self.string(self.reg(0))
            need(path == b'/data/cloudservice/div15_msg_info_542_vector.dat',
                 'unexpected native file removal')
            self.file_removes.append(path.decode())
        elif at == 0x88fa0:  # POSIX timer_create, executed via original 0x69868.
            need(self.native_timers and self.reg(0) == 0 and
                 self.reg(2) == self.native_timers[-1]['owner'] and
                 self.get64(self.reg(1)) == self.native_timers[-1]['context'] and
                 struct.unpack('<I', u.mem_read(self.reg(1) + 12, 4))[0] == 2 and
                 self.get64(self.reg(1) + 16) == self.native_timers[-1]['callback'],
                 'timer create boundary')
            timer = 0x12345000 + len(self.native_timers)
            self.put64(self.reg(2), timer)
            self.native_timers[-1]['timer'] = timer
        elif at == 0x88fb0:  # POSIX timer_settime.
            need(self.native_timers and self.reg(0) == self.native_timers[-1]['timer'] and
                 self.reg(1) == 0, 'timer set boundary')
            self.native_timers[-1]['seconds'] = self.get64(self.reg(2) + 16)
        elif at == 0x88f90:  # POSIX timer_delete.
            need(self.native_timers and self.reg(0) == self.native_timers[-1]['timer'],
                 'timer delete boundary')
            self.native_timers[-1]['deleted'] = True
        elif at == 0x4ab90 and self.reg(2) == 0xaa000004:
            need(self.reg(0) == OBJ and self.reg(1) == 1034 and
                 0 < self.reg(4) <= 256, 'opaque queued SDK write')
            payload = bytes(u.mem_read(self.reg(3), self.reg(4)))
            self.queue_writes.append((self.reg(2), payload))
            self.vehicle_writes.append(payload)
        elif at == 0x88470:  # Original vector uses C++ new at its capacity boundary.
            result = self.alloc(self.reg(0))
        elif at == 0x88460:
            self.queue_frees.append(self.reg(0))
        elif at == 0x885b0:
            self.queue_frees.append(self.reg(0))
        elif at == 0x88570:  # libc memmove used by original queue drain.
            need(self.reg(2) <= 4096, 'native queue memmove bound')
            u.mem_write(self.reg(0), bytes(u.mem_read(self.reg(1), self.reg(2))))
            result = self.reg(0)
        elif any(lo <= at < hi for lo, hi in CALLBACK_RANGES):
            need(int.from_bytes(u.mem_read(at, 4), 'little') & 0xffe0001f != 0xd4000001,
                 'native syscall')
            return
        else:
            return super().hook(engine, at, size, data)
        u.reg_write(UC_ARM64_REG_X0, result)
        u.reg_write(UC_ARM64_REG_PC, u.reg_read(UC_ARM64_REG_LR))

    def mcu_event(self, value):
        self.call(0x5d87c, OBJ, value)

    def enqueue_opaque(self, fid, body, slot):
        need(fid == 0xaa000004 and 0 < len(body) <= 256, 'queue fixture bound')
        buffer = self.alloc(len(body))
        self.u.mem_write(buffer, body)
        item = INPUT + 0x400 + slot * 0x20
        self.u.mem_write(item, struct.pack('<QII', buffer, len(body), fid))
        self.call(0x5ef80, self.get64(0x8ec98), item)
        return buffer


def verify(source):
    cases = []
    m = WakeCallback(source)
    m.mcu_event(1)
    need(struct.unpack('<I', m.u.mem_read(OBJ + 0x284, 4))[0] == 1,
         'original callback did not store awake')
    need(len(m.native_502) == 1 and m.wake_notifies == ['notify_all'] and
         m.sends == [502] and len(m.frames) == 1,
         'awake callback omitted 502 or condition notification')
    need(0x53818 in m.internal_visits and 0x5c940 in m.internal_visits and
         0x5de60 in m.internal_visits, 'callback lifecycle/drain not executed')
    cases.append({'case': 'awake_event', 'native_502': len(m.native_502),
                  'native_502_body_hex': m.native_502[0][1].hex(),
                  'condition_notifies': len(m.wake_notifies),
                  'sdk_getters': m.getters, 'property_reads': m.property_reads,
                  'property_writes': m.property_writes})

    before = (len(m.native_502), len(m.wake_notifies), len(m.queue_writes))
    m.mcu_event(1)
    need((len(m.native_502), len(m.wake_notifies), len(m.queue_writes)) == before,
         'duplicate MCU state caused duplicate work')
    cases.append({'case': 'duplicate_awake_is_noop'})

    # The input frame uses the original encoder. Its empty 536 body reaches the
    # original sleeping-MCU dispatcher, which itself allocates the queue item.
    peer = NativeControl(source)
    peer.call(0x6e3b0, HELPER, 0, PACKET, 536, 254, INPUT, 0, 0)
    peer.call(0x6e858, HELPER, PACKET, WIRE, 0)
    queued = WakeCallback(source)
    need(struct.unpack('<I', queued.u.mem_read(OBJ + 0x284, 4))[0] == 0,
         'queued 536 fixture is not sleeping')
    queued.receive(peer.frames[-1])
    vector = queued.get64(0x8ec98)
    begin, end = queued.get64(vector), queued.get64(vector + 8)
    need(end - begin == 16 and not queued.queue_writes,
         'original sleeping 536 did not queue exactly one opaque item')
    ptr, length, fid = struct.unpack('<QII', queued.u.mem_read(begin, 16))
    need(fid == 0xaa000004 and 0 < length <= 256,
         'native sleeping 536 queue item is not an opaque MCU buffer')
    native_536 = bytes(queued.u.mem_read(ptr, length))
    need(queued.native_timers and queued.native_timers[0]['seconds'] == 2 and
         queued.native_timers[0]['callback'] == 0x5e7e8,
         'native sleeping 536 timer was not armed')
    queued.mcu_event(1)
    need(queued.queue_writes == [(fid, native_536)] and
         queued.get64(vector + 8) == queued.get64(vector) and
         queued.native_timers[0].get('deleted') and ptr in queued.queue_frees,
         'wake did not drain original 536 queue or cancel its timer')
    queued.mcu_event(1)
    need(len(queued.queue_writes) == 1, 'duplicate awake resent queued 536')
    cases.append({'case': 'native_536_queued_then_callback_drained',
                  'native_buffer_hex': native_536.hex(),
                  'wake_waits_before_queue': len(queued.waits),
                  'timer_seconds': queued.native_timers[0]['seconds'],
                  'timer_cancelled': queued.native_timers[0]['deleted'],
                  'sdk_writes_after_wake': len(queued.queue_writes)})

    # A sleeping 532 follows its separate wake guard. The original code returns
    # reason 0x25 after timeouts rather than putting this command in the queue.
    climate_frame, _ = NativeControl(source).request()
    climate = WakeCallback(source)
    climate.receive(climate_frame)
    climate_vector = climate.get64(0x8ec98)
    need(len(climate.waits) == 3 and not climate.vehicle_writes and
         climate.get64(climate_vector + 8) == climate.get64(climate_vector) and
         climate.control_bodies[-1][0] == 2 and
         climate.control_bodies[-1][1][19] == 0x25,
         'sleeping 532 did not take native wake-timeout refusal')
    cases.append({'case': 'sleeping_532_timeout_not_queued',
                  'wake_waits': len(climate.waits), 'native_reason': 0x25})

    delayed = WakeCallback(source)
    opaque_a = b'\xa6\x36\x53\x00\x04\x73\x11'
    opaque_b = b'\xa6\x32\x53\x00\x09\x42\x10\x00\x01'
    delayed.enqueue_opaque(0xaa000004, opaque_a, 0)
    delayed.enqueue_opaque(0xaa000004, opaque_b, 1)
    vector = delayed.get64(0x8ec98)
    need(delayed.get64(vector + 8) - delayed.get64(vector) == 32,
         'native queue did not retain two opaque items')
    need(not delayed.queue_writes, 'queued items emitted before wake')
    delayed.mcu_event(1)
    need([body for _, body in delayed.queue_writes] == [opaque_a, opaque_b],
         'native drain changed opaque ordering or bytes')
    need(delayed.get64(vector + 8) == delayed.get64(vector),
         'native queue not drained')
    cases.append({'case': 'queued_opaque_drained_after_wake',
                  'queued': 2, 'sdk_writes': len(delayed.queue_writes),
                  'condition_notifies': len(delayed.wake_notifies)})
    return {'passed': True, 'qualified_for_product': False,
            'firmware_sha256': EXPECTED, 'cases': cases,
            'scope': 'Original callback, 502 builder, lifecycle getter, sleeping 536 dispatcher '
                     'queue insertion, timer wrapper and opaque drain; sleeping 532 timeout refusal. '
                     'Direct two-item insertion separately tests original vector FIFO.',
            'external_fixtures': ['synthetic logged-in session and empty secondary listener list',
                                  'SDK integer getters and wake setters',
                                  'system properties and numeric conversion',
                                  'clock, mutex and condition wait timeout',
                                  'POSIX timer creation/cancellation without thread callback',
                                  'memory allocation, outward Binder write and cloud send capture'],
            'remaining_boundary': 'Real MCU event source, on-device SDK values, timer-thread '
                                  'scheduling, Binder result and cloud delivery are unproved.'}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('firmware', type=Path)
    print(json.dumps(verify(p.parse_args().firmware), indent=2))
