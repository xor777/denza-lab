#!/usr/bin/env python3
"""Offline original-code 532 lifecycle investigation against pinned cloudmanager.

This derivative harness captures external effects. It never opens a socket,
talks to a car or invents a vehicle control payload.
"""
import argparse
import json
import struct
import time
from pathlib import Path
from elftools.elf.elffile import ELFFile
from unicorn import UC_HOOK_MEM_INVALID
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_PC, UC_ARM64_REG_LR

from verify_native_control import NativeControl
from verify_opaque_native import INPUT, OBJ, AUX, STACK, EXPECTED, need
from verify_native_roundtrip import HELPER, PACKET, WIRE

SECONDARY = 0x911e8  # GOT 0x8ec40 points to this exact BSS object in the pinned ELF.


class GenericControl(NativeControl):
    def __init__(self, source, **kwargs):
        self.extra_calls = []
        self.properties = []
        self.sleeps = []
        self.random_calls = 0
        self.extra_bodies = []
        self.timer_events = []
        self.unlock_timer_lambda = None
        self.timeout_thread_events = []
        self.timeout_thread_attr = None
        self.sender_codes = []
        self.last_fault = None
        self.last_at = None
        super().__init__(source, **kwargs)
        self.u.hook_add(UC_HOOK_MEM_INVALID, self.memory_fault)
        # The dispatcher loads its control-service pointer from this slot.
        # Main constructor 0x48a1c obtains it via GOT 0x8ec40. Initialise the
        # exact BSS object with its own constructor instead of a guessed clone.
        need(self.get64(0x8ec40) == SECONDARY, 'secondary BSS relocation')
        self.call(0x4404c, SECONDARY)
        self.put64(OBJ + 0x30, SECONDARY)
        # Main object's constructor 0x489e0 installs this vtable; the shared
        # fixture had omitted it because earlier paths never dereferenced it.
        self.put64(OBJ, self.get64(0x8eca8) + 0x18)
        # The stock getter 0x66980 resolves this process singleton. The
        # pre-existing partial OBJ fixture is the object that owns the frame.
        self.put64(0x91190, OBJ)

    def memory_fault(self, engine, access, address, size, value, data):
        self.last_fault = {'access': access, 'address': hex(address),
                           'size': size, 'pc': hex(self.u.reg_read(UC_ARM64_REG_PC))}
        return False

    def hook(self, engine, at, size, data):
        self.last_at = hex(at)
        if at in (0x451f0, 0x44c94, 0x4a41c):
            self.extra_calls.append({'at': hex(at), 'args': [hex(self.reg(i)) for i in range(6)]})
        if at == 0x4a41c:
            return self.timer_hook(engine, at, size, data)
        if at == 0x739a0:
            need(self.reg(0) == AUX + 0xc000 and self.reg(1) in (1, 709),
                 'result sender code boundary')
            self.sender_codes.append(self.reg(1))
            if self.reg(1) == 1:
                self.result_sends += 1
            self.u.reg_write(UC_ARM64_REG_X0, 0)
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if at == 0x6e3b0 and self.reg(3) in (417, 709):
            need(0 < self.reg(6) <= 128, 'native extra builder boundary')
            self.extra_bodies.append(bytes(self.u.mem_read(self.reg(5), self.reg(6))))
            return
        if at in (0x88fc0, 0x88fd0, 0x88fe0):
            if at == 0x88fc0:
                result = 1_700_000_000
            elif at == 0x88fd0:
                need(self.reg(0) == 1_700_000_000, 'srand clock fixture')
                result = 0
            else:
                need(self.random_calls < 32, 'rand call bound')
                result = (0x41 + self.random_calls) & 0xff
                self.random_calls += 1
            self.u.reg_write(UC_ARM64_REG_X0, result)
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if at in (0x88430, 0x88440, 0x88450, 0x884c0, 0x88390, 0x884b0,
                  0x884d0, 0x884e0):
            return self.unlock_timer_hook(at)
        if at in (0x88a20, 0x88a30, 0x88a40, 0x88a50):
            return self.pthread_hook(at)
        if at == 0x4ad30:
            need(self.string(self.reg(2)) == b'%d', 'integer format boundary')
            value = self.reg(3) & 0xffffffff
            if value & 0x80000000:
                value -= 0x100000000
            encoded = str(value).encode() + b'\0'
            need(len(encoded) <= self.reg(1) <= 32, 'integer format destination bound')
            self.u.mem_write(self.reg(0), encoded)
            self.u.reg_write(UC_ARM64_REG_X0, len(encoded) - 1)
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if at == 0x885c0:
            try:
                key = self.string(self.reg(0))
                value = None if self.reg(1) == 0 else self.string(self.reg(1))
            except Exception as error:
                raise ValueError('property pointer boundary x0=' + hex(self.reg(0)) +
                                 ' x1=' + hex(self.reg(1))) from error
            need(key in (b'persist.sys.cloud.unlock_type', b'persist.sys.cloud.user_id',
                         b'sys.cloud.remote_controling', b'sys.cloud.unlock_index',
                         b'sys.cloud.unlock_uuid'),
                 'unknown property write ' + repr(key))
            need(value is None or len(value) <= (32 if key == b'sys.cloud.unlock_uuid' else 16),
                 'property value bound')
            if key == b'sys.cloud.unlock_uuid':
                need(value is not None and len(value) == 32 and
                     all(c in b'0123456789ABCDEFabcdef' for c in value),
                     'unlock UUID property shape ' + repr(value))
            need(value is not None or key == b'persist.sys.cloud.user_id',
                 'unexpected null property value')
            self.properties.append((key.decode(), None if value is None else value.decode()))
            self.u.reg_write(UC_ARM64_REG_X0, 0)
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if at == 0x48698:
            key = self.string(self.reg(1))
            need(key in (b'persist.sys.repair_mode.enable', b'sys.cloud.unlock_index'),
                 'property getter boundary ' + repr(key))
            self.extra_calls.append({'at': hex(at), 'property': key.decode(),
                                     'fixture_value': 0})
            self.u.reg_write(UC_ARM64_REG_X0, 0)
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if at == 0x88580:
            micros = self.reg(0)
            need(micros <= 100_000, 'usleep boundary ' + str(micros))
            self.sleeps.append(micros)
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if at == 0x88f90:  # POSIX timer_delete, captured only
            need(self.reg(0) in (AUX + 0xe800, AUX + 0xe808),
                 'timer_delete handle boundary')
            self.timer_events.append({'external': 'timer_delete', 'handle': hex(self.reg(0))})
            self.u.reg_write(UC_ARM64_REG_X0, 0)
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if at == 0x66980:
            need(self.get64(0x91190) == OBJ, 'cloudmanager singleton absent')
            pointer_slot = self.u.reg_read(UC_ARM64_REG_X0 + 8)
            need(STACK <= pointer_slot < STACK + 0x10000, 'singleton destination boundary')
            self.u.mem_write(pointer_slot, struct.pack('<Q', OBJ))
            self.extra_calls.append({'at': hex(at), 'singleton': hex(OBJ),
                                     'destination': hex(pointer_slot)})
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if at == 0x887b0:
            need(self.reg(0) == SECONDARY + 0x90 and self.string(self.reg(1)) == b'',
                 'secondary empty string constructor boundary')
            self.u.mem_write(self.reg(0), bytes(24))
            self.u.reg_write(UC_ARM64_REG_X0, self.reg(0))
            self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))
            return
        if any(lo <= at < hi for lo, hi in ((0x42448, 0x42490),
                                           (0x4404c, 0x440c0),
                                           (0x4689c, 0x468d0),
                                           (0x48018, 0x480c0),
                                           (0x48280, 0x48498),
                                           (0x45430, 0x454cc),
                                           (0x45ca4, 0x45cf4),
                                           (0x45c9c, 0x45ca0),
                                           (0x36b78, 0x36ce4),
                                           (0x54f7c, 0x54fc4),
                                           (0x4f2e4, 0x4f318),
                                           (0x4a480, 0x4a494),
                                           (0x69974, 0x699a4),
                                           (0x60fac, 0x6114c),
                                           (0x60c0c, 0x60fac),
                                           (0x5eef0, 0x5ef80),
                                           (0x61230, 0x612a8),
                                           (0x699a4, 0x699f0),
                                           (0x69b9c, 0x69c10),
                                           (0x714bc, 0x71574),
                                           (0x7113c, 0x71200),
                                           (0x451f0, 0x45380), (0x44c94, 0x45030))):
            return
        return super().hook(engine, at, size, data)

    def unlock_timer_hook(self, at):
        result = 0
        if at == 0x88430:  # __cxa_guard_acquire
            need(self.reg(0) == self.get64(0x8ea40), 'timer guard acquire pointer')
            result = 1 if self.u.mem_read(self.reg(0), 1) == b'\0' else 0
        elif at == 0x88440:  # __cxa_guard_release
            need(self.reg(0) == self.get64(0x8ea40), 'timer guard release pointer')
            self.u.mem_write(self.reg(0), b'\1')
        elif at == 0x88450:  # __cxa_guard_abort
            raise ValueError('timer guard abort')
        elif at == 0x884c0:  # TimerManager constructor
            need(self.reg(0) == self.get64(0x8ea48), 'timer manager constructor pointer')
            self.timer_events.append({'external': 'TimerManager.ctor'})
            result = self.reg(0)
        elif at == 0x88390:  # __cxa_atexit, destructor registration
            need(self.reg(1) == self.get64(0x8ea48), 'timer manager destructor context')
            self.timer_events.append({'external': '__cxa_atexit'})
        elif at == 0x884b0:  # TimerManager.create_timer, hidden shared_ptr return
            need(self.reg(0) == self.get64(0x8ea48) and self.reg(1) == 5000 and
                 self.reg(2) == 0 and self.reg(3) == 0 and self.reg(6) == 7,
                 'unlock timer constructor arguments')
            return_slot = self.u.reg_read(UC_ARM64_REG_X0 + 8)
            need(STACK <= return_slot < STACK + 0x10000, 'unlock timer return slot')
            event, counter = AUX + 0xd000, AUX + 0xd100
            callback = bytes(self.u.mem_read(self.reg(4), 16))
            need(struct.unpack('<QQ', callback) == (0x8b330, SECONDARY),
                 'unlock timer original lambda closure')
            self.unlock_timer_lambda = AUX + 0xe100
            self.u.mem_write(self.unlock_timer_lambda, callback)
            self.u.mem_write(return_slot, struct.pack('<QQ', event, counter))
            self.u.mem_write(counter + 8, struct.pack('<Q', 2))
            self.timer_events.append({'external': 'TimerManager.create_timer',
                                      'delay_ms': self.reg(1), 'event': hex(event),
                                      'lambda_vtable': hex(0x8b330),
                                      'captured_object': hex(SECONDARY)})
        elif at == 0x884d0:  # TimerEvent::stop; scheduling is an external fixture.
            need(self.reg(0) == AUX + 0xd000 and self.unlock_timer_lambda is not None,
                 'unlock timer cancel pointer')
            self.timer_events.append({'external': 'TimerEvent.stop',
                                      'event': hex(self.reg(0))})
        else:  # TimerEvent.start
            need(self.reg(0) == AUX + 0xd000, 'unlock timer start pointer')
            self.timer_events.append({'external': 'TimerEvent.start'})
        self.u.reg_write(UC_ARM64_REG_X0, result)
        self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))

    def pthread_hook(self, at):
        if at == 0x88a20:  # pthread_attr_init
            need(STACK <= self.reg(0) < STACK + 0x10000,
                 'generic timeout pthread attribute pointer')
            self.timeout_thread_attr = self.reg(0)
            self.timeout_thread_events.append({'external': 'pthread_attr_init'})
        elif at == 0x88a30:  # pthread_attr_setdetachstate
            need(self.reg(0) == self.timeout_thread_attr and self.reg(1) == 1,
                 'generic timeout detached thread attribute')
            self.timeout_thread_events.append({'external': 'pthread_attr_setdetachstate',
                                               'state': 1})
        elif at == 0x88a40:  # pthread_create; capture-only, then invoke body explicitly.
            need(STACK <= self.reg(0) < STACK + 0x10000 and
                 self.reg(1) == self.timeout_thread_attr and
                 self.reg(2) == 0x61230 and self.reg(3) == 0,
                 'generic timeout original thread start routine')
            self.timeout_thread_events.append({'external': 'pthread_create',
                                               'start_routine': hex(self.reg(2)),
                                               'argument': self.reg(3)})
        else:  # pthread_attr_destroy
            need(self.reg(0) == self.timeout_thread_attr,
                 'generic timeout pthread attribute destroy pointer')
            self.timeout_thread_events.append({'external': 'pthread_attr_destroy'})
        self.u.reg_write(UC_ARM64_REG_X0, 0)
        self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))

    def timer_hook(self, engine, at, size, data):
        slot, seconds, callback = self.reg(1), self.reg(3), self.reg(4)
        need(self.reg(0) == OBJ and self.reg(5) == OBJ, 'timer owner boundary')
        need((slot, seconds, callback) in ((OBJ + 0x5d8, 16, 0x5eef0),
                                            (OBJ + 0x5d8, 32, 0x5eef0),
                                            (OBJ + 0x5c8, 10, 0x4a480)), 'unknown timer boundary')
        self.timers.append({'slot': hex(slot - OBJ), 'seconds': seconds,
                            'callback': hex(callback)})
        handle = AUX + (0xe800 if slot == OBJ + 0x5c8 else 0xe808)
        self.put64(slot, handle)
        self.timer_events.append({'external': 'timer_create', 'handle': hex(handle),
                                  'slot': hex(slot - OBJ), 'seconds': seconds})
        self.u.reg_write(UC_ARM64_REG_X0, 0)
        self.u.reg_write(UC_ARM64_REG_PC, self.u.reg_read(UC_ARM64_REG_LR))


def mcu_reply(machine, flag):
    """Change only the MCU reply flag in an envelope made by original code."""
    need(machine.vehicle_writes, 'native MCU envelope absent')
    reply = bytearray(machine.vehicle_writes[-1])
    reply[4] = flag
    machine.u.mem_write(INPUT, bytes(reply))
    machine.call(0x6039c, OBJ, 0x99000004, INPUT, len(reply))


def continuation(sender, opaque):
    sender.u.mem_write(INPUT, opaque)
    sender.call(0x6e3b0, HELPER, 0, PACKET, 532, 4, INPUT, len(opaque), 0)
    sender.call(0x6e858, HELPER, PACKET, WIRE, len(opaque))
    return sender.frames[-1]


def probe(source, subcommand, flag, *, full=False, sender=None):
    sender = sender or GenericControl(source)
    frame, opaque = sender.request(subcommand)
    m = GenericControl(source)
    stage = 'request'
    try:
        m.receive(frame)
        if subcommand == 39:
            need(not m.vehicle_writes and len(m.can_writes) == 5, 'sub39 CAN path')
            need(len(set(m.can_writes)) == 1 and m.get64(OBJ + 0x2e8) == 0,
                 'sub39 identical native CAN retries with empty listener fixture')
            need(m.control_bodies == [(250, opaque[:16]), (1, opaque[:16] + b'\x27\0\0\0\0')],
                 'sub39 native cloud responses')
            need(m.timers == [{'slot': '0x5c8', 'seconds': 10, 'callback': '0x4a480'}],
                 'sub39 timeout timer')
            need(m.sleeps == [100_000] * 4, 'sub39 retry pacing')
            stage = 'timeout_callback'
            m.call(0x4a480)
            need(m.sends == [8532, 532, 417] and len(m.extra_bodies) == 1 and
                 len(m.extra_bodies[0]) == 26 and m.u.mem_read(OBJ + 0x5e0, 1) == b'\0',
                 'sub39 timeout completion')
            return {'subcommand': subcommand, 'path': 'special_can_and_timeout',
                    'complete': True, 'can_writes': len(m.can_writes),
                    'cloud_flags': [x for x, _ in m.control_bodies],
                    'send_codes': m.sends, 'timer_events': m.timer_events,
                    'usleep_calls': m.sleeps}
        need(len(m.vehicle_writes) == 1, 'request was not forwarded')
        need(m.vehicle_writes[0][6:] == opaque, 'opaque MCU body changed')
        need(m.timers == [{'slot': '0x5d8',
                           'seconds': 32 if subcommand == 17 else 16,
                           'callback': '0x5eef0'}], 'generic timer mismatch')
        if full:
            stage = 'intermediate'
            mcu_reply(m, 3)
            need(m.control_bodies[-1] == (3, opaque) and m.result_sends == 1 and
                 m.u.mem_read(OBJ + 0x254, 1) == b'\1', 'intermediate callback')
            stage = 'continuation'
            m.receive(continuation(sender, opaque))
            need(len(m.vehicle_writes) == 2 and m.vehicle_writes[-1][4] == 4 and
                 m.vehicle_writes[-1][6:] == opaque, 'cloud continuation')
        stage = 'terminal'
        mcu_reply(m, flag)
        need(m.results == [(subcommand, flag, 0)] and
             m.u.mem_read(OBJ + 0x254, 1) == b'\0' and
             m.get64(OBJ + 0x5d8) == 0, 'native terminal completion')
        expected_sends = (2 if full else 1) + (1 if subcommand == 5 and flag == 1 else 0)
        need(len(m.sender_codes) == expected_sends, 'sender callback count')
        if subcommand == 5 and flag == 1:
            need(len(m.extra_bodies) == 1 and len(m.extra_bodies[0]) == 42 and
                 ('sys.cloud.unlock_uuid', '202122232425262728292A2B2C2D2E2F') in m.properties and
                 709 in m.sender_codes, 'sub5 unlock follow-up')
        else:
            need(not m.extra_bodies and 709 not in m.sender_codes,
                 'unexpected unlock follow-up')
        return {'subcommand': subcommand, 'flag': flag, 'path': 'generic_mcu',
                'full_lifecycle': full, 'complete': True, 'results': m.results,
                'timer_events': m.timer_events, 'sender_codes': m.sender_codes,
                'properties': m.properties,
                'extra_body_lengths': [len(x) for x in m.extra_bodies]}
    except Exception as error:
        return {'subcommand': subcommand, 'flag': flag, 'complete': False,
                'stage': stage, 'boundary': str(error), 'timers': m.timers,
                'extra_calls': m.extra_calls, 'properties': m.properties,
                'sleeps_us': m.sleeps, 'last_fault': m.last_fault, 'last_at': m.last_at,
                'extra_bodies_hex': [x.hex() for x in m.extra_bodies],
                'timer_events': m.timer_events, 'sender_codes': m.sender_codes}


def verify_timer_semantics(source):
    """Execute the original expiry bodies; external scheduling stays a fixture."""
    began = time.monotonic()
    with source.open('rb') as firmware:
        elf = ELFFile(firmware)
        relocations = elf.get_section_by_name('.rela.dyn')
        thread_entries = [r for r in relocations.iter_relocations()
                          if r['r_offset'] == 0x8edf0]
        need(len(thread_entries) == 1 and thread_entries[0]['r_addend'] == 0x61230,
             'original generic timeout thread entry relocation')
        plt = elf.get_section_by_name('.rela.plt')
        symbols = elf.get_section(plt['sh_link'])
        expected_imports = {
            0x884b0: '_ZN10components5utils12TimerManager12create_timerEmmmNSt6__ndk18functionIFvPvEEES4_i',
            0x884d0: '_ZN10components5utils10TimerEvent4stopEv',
            0x884e0: '_ZN10components5utils10TimerEvent5startEv',
            0x88a20: 'pthread_attr_init',
            0x88a30: 'pthread_attr_setdetachstate',
            0x88a40: 'pthread_create',
            0x88a50: 'pthread_attr_destroy',
            0x88f90: 'timer_delete',
        }
        actual_imports = {hex(address): symbols.get_symbol(
            plt.get_relocation((address - 0x88380) // 16)['r_info_sym']).name
                          for address in expected_imports}
        need(actual_imports == {hex(address): name for address, name in
                                expected_imports.items()},
             'original timer and pthread PLT imports')
    frame, _ = GenericControl(source).request(5)
    m = GenericControl(source)
    m.receive(frame)
    mcu_reply(m, 1)
    need(m.results == [(5, 1, 0)] and m.sender_codes == [1, 709] and
         m.unlock_timer_lambda == AUX + 0xe100 and
         m.get64(0x8b360) == 0x45ca4 and
         m.get64(m.get64(SECONDARY) + 0x38) == 0x45430 and
         m.get64(SECONDARY + 0x318) == AUX + 0xd000 and
         m.get64(SECONDARY + 0x320) == AUX + 0xd100,
         'sub5 terminal1 completion and original unlock lambda capture')
    finished_effects = (len(m.extra_bodies), len(m.vehicle_writes),
                        len(m.control_bodies), len(m.properties), len(m.results))
    m.call(0x45ca4, m.unlock_timer_lambda)
    need(m.sender_codes == [1, 709, 709] and
         sum(e['external'] == 'TimerEvent.stop' for e in m.timer_events) == 1 and
         (len(m.extra_bodies), len(m.vehicle_writes), len(m.control_bodies),
          len(m.properties), len(m.results)) == finished_effects and
         m.get64(SECONDARY + 0x318) == AUX + 0xd000 and
         m.get64(OBJ + 0x5d8) == 0 and m.u.mem_read(OBJ + 0x254, 1) == b'\0',
         'sub5 first expiry altered completed command or failed to send 709')
    first_expiry = {'post_terminal_results': m.results[:],
                    'sender_codes': m.sender_codes[:],
                    'timer_events': m.timer_events[:],
                    'secondary_timer_pointer_retained': True,
                    'additional_cloud_bodies': 0,
                    'additional_vehicle_writes': 0}
    m.call(0x45ca4, m.unlock_timer_lambda)
    need(m.sender_codes == [1, 709, 709, 709] and
         sum(e['external'] == 'TimerEvent.stop' for e in m.timer_events) == 2 and
         (len(m.extra_bodies), len(m.vehicle_writes), len(m.control_bodies),
          len(m.properties), len(m.results)) == finished_effects,
         'sub5 duplicate callback effect changed')
    duplicate_expiry = {'sender_codes': m.sender_codes[:],
                        'stop_calls': 2, 'results': m.results[:],
                        'secondary_timer_pointer_retained':
                        m.get64(SECONDARY + 0x318) == AUX + 0xd000}

    frame, _ = GenericControl(source).request(5)
    terminal2 = GenericControl(source)
    terminal2.receive(frame)
    mcu_reply(terminal2, 2)
    need(terminal2.results == [(5, 2, 0)] and
         terminal2.unlock_timer_lambda is None and
         terminal2.get64(SECONDARY + 0x318) == 0 and
         not any(e['external'].startswith('TimerEvent.') for e in terminal2.timer_events),
         'sub5 terminal2 unexpectedly scheduled unlock timer')

    generic_timeouts = []
    for subcommand, seconds in ((3, 16), (17, 32)):
        frame, _ = GenericControl(source).request(subcommand)
        current = GenericControl(source)
        current.receive(frame)
        need(current.timers == [{'slot': '0x5d8', 'seconds': seconds,
                                 'callback': '0x5eef0'}] and
             current.get64(OBJ + 0x5d8) == AUX + 0xe808 and
             current.u.mem_read(OBJ + 0x254, 2) == b'\1\0',
             'generic timeout pending state')
        prior_effects = (current.control_bodies[:], current.sends[:],
                         current.sender_codes[:], current.results[:],
                         current.vehicle_writes[:])
        # NativeControl uses this GOT slot for a generic isolated callback
        # fixture. Restore its pinned relocation solely for this focused path.
        current.put64(0x8edf0, 0x61230)
        current.call(0x5eef0)
        need(current.timeout_thread_events == [
            {'external': 'pthread_attr_init'},
            {'external': 'pthread_attr_setdetachstate', 'state': 1},
            {'external': 'pthread_create', 'start_routine': '0x61230', 'argument': 0},
            {'external': 'pthread_attr_destroy'}] and
             current.get64(OBJ + 0x5d8) == AUX + 0xe808 and
             current.u.mem_read(OBJ + 0x254, 2) == b'\1\0',
             'generic timer callback did not register the original thread body')
        need(current.call(0x61230) == 0 and
             current.get64(OBJ + 0x5d8) == 0 and
             current.u.mem_read(OBJ + 0x254, 2) == b'\0\0' and
             sum(e['external'] == 'timer_delete' for e in current.timer_events) == 1 and
             (current.control_bodies, current.sends, current.sender_codes,
              current.results, current.vehicle_writes) == prior_effects,
             'generic timeout body effects')
        timeout_effects = {'seconds': seconds,
                           'thread_registration': current.timeout_thread_events[:],
                           'timer_events': current.timer_events[:],
                           'busy_cleared': True, 'additional_cloud_bodies': 0,
                           'additional_sender_calls': 0, 'additional_results': 0,
                           'additional_vehicle_writes': 0}
        current.call(0x61230)
        need(sum(e['external'] == 'timer_delete' for e in current.timer_events) == 1 and
             current.u.mem_read(OBJ + 0x254, 2) == b'\0\0' and
             (current.control_bodies, current.sends, current.sender_codes,
              current.results, current.vehicle_writes) == prior_effects,
             'generic timeout duplicate body effects')
        mcu_reply(current, 1)
        need(current.results == [(subcommand, 1, 0)] and
             current.sender_codes == [1] and
             [flag for flag, _ in current.control_bodies] == [250, 1] and
             sum(e['external'] == 'timer_delete' for e in current.timer_events) == 1,
             'late MCU terminal after generic timeout')
        generic_timeouts.append({'subcommand': subcommand, 'timeout': timeout_effects,
                                 'second_body_deleted_timer_again': False,
                                 'late_native_mcu_terminal_accepted': True,
                                 'late_results': current.results[:],
                                 'late_cloud_reply_flags':
                                 [flag for flag, _ in current.control_bodies]})
    return {'passed': True, 'qualified_for_product': False,
            'firmware_sha256': EXPECTED,
            'sub5_terminal1_expiry': first_expiry,
            'sub5_forced_duplicate_expiry': duplicate_expiry,
            'sub5_terminal2_schedules_unlock_timer': False,
            'generic_timeout_bodies': generic_timeouts,
            'original_imports': actual_imports,
            'elapsed_seconds': round(time.monotonic() - began, 2),
            'scope': ('Original 0x45ca4 and 0x61230 executed offline against native-generated '
                      'request/reply fixtures. TimerManager scheduling, TimerEvent::stop and '
                      'POSIX timer_delete are captured external calls; no wall-clock firing or '
                      'real TimerEvent destruction is proven. Duplicate expiry is a forced direct '
                      'callback invocation, not evidence that TimerManager can deliver twice. '
                      'Generic timer callback 0x5eef0 was executed through detached pthread '
                      'registration; pthread_create was captured and its relocated start routine '
                      '0x61230 invoked explicitly, without actual asynchronous scheduling. '
                      'No car, cloud or production runtime acceptance.')}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('firmware', type=Path)
    parser.add_argument('--all256', action='store_true',
                        help='also check direct terminal1/2 for every byte subcommand')
    parser.add_argument('--timer-semantics', action='store_true',
                        help='focused original sub5 expiry and generic timeout bodies')
    args = parser.parse_args()
    if args.timer_semantics:
        need(not args.all256, 'timer semantics and all256 modes are distinct')
        print(json.dumps(verify_timer_semantics(args.firmware), indent=2))
        return
    began = time.monotonic()
    representative = [probe(args.firmware, sub, flag, full=True)
                      for sub in (0, 3, 5, 17, 39, 255)
                      for flag in ((1,) if sub == 39 else (1, 2))]
    failures = []
    classes = {'generic_mcu_terminal1': 0, 'generic_mcu_terminal2': 0,
               'special_can_and_timeout': 0}
    if args.all256:
        sender = GenericControl(args.firmware)
        for sub in range(256):
            for flag in ((1,) if sub == 39 else (1, 2)):
                case = probe(args.firmware, sub, flag, sender=sender)
                if case['complete']:
                    key = ('special_can_and_timeout' if sub == 39 else
                           'generic_mcu_terminal' + str(flag))
                    classes[key] += 1
                else:
                    failures.append(case)
    result = {'passed': all(x['complete'] for x in representative) and not failures,
              'qualified_for_product': False, 'firmware_sha256': EXPECTED,
              'representative_full_lifecycle': representative,
              'all256_direct_terminal_counts': classes if args.all256 else None,
              'all256_failures': failures, 'elapsed_seconds': round(time.monotonic() - began, 2),
              'native_dependencies': {
                  'secondary_control_object': 'OBJ+0x30 -> original constructor 0x4404c',
                  'primary_vtable': 'constructor 0x489e0 assignment from GOT 0x8eca8+0x18',
                  'process_singleton': '0x91190 -> partial OBJ; 0x66980 selector is a fixture boundary',
                  'generic_timer': 'OBJ+0x5d8, callback 0x5eef0; 16s except sub17 32s',
                  'sub39_timer': 'OBJ+0x5c8, callback 0x4a480; 10s',
                  'sub39_listeners': 'OBJ+0x2e0/0x2e8 empty fixture; 0x48280 traverses these',
                  'getter_fixture': ('NativeControl speed=0, SOC=79, ACC=2, '
                                     'key (1023,0x2f4000fa)=1; repair_mode=0; '
                                     'sys.cloud.unlock_index=0'),
                  'sub5_unlock_timer': 'secondary+0x318, TimerManager.create_timer 5000ms',
                  'original_write_boundaries': ['0x4ab90 property 0xaa000004 MCU envelope',
                                                '0x4ab90 property 0xaa00001e CAN',
                                                '0x885c0 property_set', '0x739a0 result sender',
                                                '0x4aedc cloud send'],
              },
              'scope': ('Original firmware codec, dispatcher and callbacks on synthetic bytes. '
                        'TimerManager, property, CAN, network, libc time/randomness and vehicle '
                        'getters are capture-only fixtures. Singleton/RefBase and listener context '
                        'are partial. No actual timer scheduling, live MCU or cloud acceptance.')}
    print(json.dumps(result, indent=2))
    if not result['passed']:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
