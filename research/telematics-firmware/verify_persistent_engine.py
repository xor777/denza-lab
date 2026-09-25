#!/usr/bin/env python3
"""Offline Unicorn execution of the isolated Linux ARM64 engine.

Only process syscalls are modeled. Firmware callbacks, codecs and dispatchers
remain machine instructions in the generated ELF. No socket, car or APK.
"""
import argparse
import json
import struct
from pathlib import Path

from elftools.elf.elffile import ELFFile
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_INTR, UC_HOOK_MEM_INVALID, UC_HOOK_CODE
from unicorn.arm64_const import UC_ARM64_REG_PC, UC_ARM64_REG_SP, UC_ARM64_REG_LR, UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2, UC_ARM64_REG_X3, UC_ARM64_REG_X4, UC_ARM64_REG_X5, UC_ARM64_REG_X8, UC_ARM64_REG_X19

# Test-session property namespace only. These keys are never asserted to be
# writable on a car; the production Java bridge owns the actual policy.
FIXTURE_PRIVATE_ONLY_PROPERTIES = {
    'persist.sys.cloud.unlock_type', 'sys.cloud.unlock_uuid', 'sys.cloud_532_reply',
    'sys.tcp_reg_errcode', 'persist.sys.cloud.user_id', 'sys.cloud.201_send_status',
    'persist.sys.cloud_412_data', 'persist.sys.505_req_status',
}
FIXTURE_LOCAL_WRITES = FIXTURE_PRIVATE_ONLY_PROPERTIES | {
    'persist.sys.sentrymode_record', 'persist.sys.smart_charge_stage_record',
    'persist.sys.record_610_upload', 'persist.sys.system_info',
    'persist.sys.cloud.app_reg_status', 'persist.sys.cloud_fid_uploaded',
    'persist.sys.record_421_notify', 'persist.sys.record_499_upload',
    'persist.sys.316_req_status', 'persist.sys.mcu_func_record',
    'persist.sys.vehicle_sales_record', 'sys.cloud.unlock_index',
    'sys.vin_valid_record_time', 'sys.tcp_step', 'sys.tcp_connect_status',
    'sys.virtual.vin', 'sys.cloud_domin_ip',
}


class Process:
    def __init__(self, binary: Path, script: bytes, auto_replies=False,
                 fail_call_kind=None, mcu_getter=1, acc_getter=2,
                 wake_event_on_wait=False, auto_vin_fixture=None,
                 auto_vin19_fixture=None, secondary_write_count_override=None,
                 observed_getters=None, observed_properties=None, property_status_fixture=-1,
                 observed_buffers=None):
        self.u = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        self.input = bytearray(script)
        self.output = bytearray()
        self.exited = None
        self.steps = 0
        self.sleep_calls = []
        self.boundary_visits = {}
        self.path_hits = {}
        self.queue_at_drain = []
        self.wake_dispatch = []
        self.body_commands = []
        self.thread_create_calls = []
        self.buffer_getter_calls = []
        self.guard_calls = []
        self.dns_requests = []
        self.timer_settime_requests = []
        self.secondary_calls = []
        self.secondary_attempts = []
        self.sender_signals = []
        self.sender_callers = []
        self.random_requests = 0
        self.string_copy_calls = []
        self.property_set_calls = []
        self.auto_replies = auto_replies
        # Offline fixture: acknowledge each native NET only after the outer
        # OP completes, mirroring a host that fully wrote the opaque frame.
        # The actual Java bridge sends SENT after its TLS write instead.
        self.fixture_network_sends = []
        self.fixture_sent_sequence = 0
        self.sdk_getter_requests = set()
        self.property_get_requests = set()
        self.fixture_private_properties = {}
        self.fixture_ack_writes = []
        self.fail_call_kind = fail_call_kind
        self.mcu_getter = mcu_getter
        self.acc_getter = acc_getter
        self.wake_event_on_wait = wake_event_on_wait
        self.auto_vin_fixture = auto_vin_fixture
        self.auto_vin19_fixture = auto_vin19_fixture
        self.secondary_write_count_override = secondary_write_count_override
        self.observed_getters = observed_getters or {}
        self.observed_properties = observed_properties or {}
        self.property_status_fixture = property_status_fixture
        self.observed_buffers = observed_buffers or {}
        self.wait_count = 0
        self.handled_output = 0
        with binary.open('rb') as source:
            elf = ELFFile(source)
            self.symbols = {symbol.name: symbol['st_value'] for symbol in
                            elf.get_section_by_name('.symtab').iter_symbols()}
            for segment in elf.iter_segments():
                if segment['p_type'] != 'PT_LOAD':
                    continue
                addr, size = segment['p_vaddr'], segment['p_memsz']
                lo, hi = addr & ~4095, (addr + size + 4095) & ~4095
                self.u.mem_map(lo, hi - lo)
                self.u.mem_write(addr, segment.data())
            self.entry = elf.header['e_entry']
        self.u.mem_map(0x40000000, 0x200000)
        self.u.reg_write(UC_ARM64_REG_SP, 0x401ff000)
        self.u.reg_write(UC_ARM64_REG_PC, self.entry)
        self.u.hook_add(UC_HOOK_INTR, self.interrupt)
        self.u.hook_add(UC_HOOK_MEM_INVALID, self.invalid)
        self.u.hook_add(UC_HOOK_CODE, self.count)

    def count(self, engine, address, size, data):
        self.steps += 1
        if address == 0x50088a40:
            self.thread_create_calls.append(tuple(self.u.reg_read(r) for r in
                (UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2, UC_ARM64_REG_X3)))
        if address == 0x50088b60:
            self.buffer_getter_calls.append(tuple(self.u.reg_read(r) for r in
                (UC_ARM64_REG_X1,UC_ARM64_REG_X2)))
        if address == 0x50088430:
            self.guard_calls.append(self.u.reg_read(UC_ARM64_REG_X0))
        if address == 0x50088c60:
            host = self.u.reg_read(UC_ARM64_REG_X0)
            self.dns_requests.append((self.u.reg_read(UC_ARM64_REG_LR),
                                      bytes(self.u.mem_read(host, 128)).split(b'\0', 1)[0]))
        if address == 0x50088fb0:
            spec = self.u.reg_read(UC_ARM64_REG_X2)
            self.timer_settime_requests.append((self.u.reg_read(UC_ARM64_REG_LR),
                self.u.reg_read(UC_ARM64_REG_X0), self.u.reg_read(UC_ARM64_REG_X1),
                struct.unpack('<4Q', self.u.mem_read(spec, 32))))
        if address == 0x50080c50:
            host_ptr = self.u.reg_read(UC_ARM64_REG_X1)
            self.secondary_attempts.append((
                bytes(self.u.mem_read(host_ptr, 128)).split(b'\0', 1)[0],
                self.u.reg_read(UC_ARM64_REG_X2),
                self.u.reg_read(UC_ARM64_REG_X3),
                bytes(self.u.mem_read(self.symbols['domain'], 128)).split(b'\0', 1)[0]))
        if address == 0x500888f0:
            source = self.u.reg_read(UC_ARM64_REG_X1)
            self.string_copy_calls.append(bytes(self.u.mem_read(source, 24)).hex())
        if address == 0x500885c0:
            name = self.u.reg_read(UC_ARM64_REG_X0)
            value = self.u.reg_read(UC_ARM64_REG_X1)
            self.property_set_calls.append((
                bytes(self.u.mem_read(name, 128)).split(b'\0', 1)[0],
                bytes(self.u.mem_read(value, 128)).split(b'\0', 1)[0] if value else None))
        if address == 0x5006e3b0:
            self.body_commands.append(self.u.reg_read(UC_ARM64_REG_X3))
        if address == 0x500739a0:
            self.sender_signals.append(self.u.reg_read(UC_ARM64_REG_X1))
            self.sender_callers.append((self.u.reg_read(UC_ARM64_REG_X1),
                                        self.u.reg_read(UC_ARM64_REG_LR)))
        if address == 0x5005de60:
            image_base = struct.unpack('<Q', self.u.mem_read(self.symbols['base'], 8))[0]
            self.queue_at_drain.append(bytes(self.u.mem_read(
                image_base + 0x910f8, 24)).hex())
        if address in (0x50058670, 0x500586ec):
            x19 = self.u.reg_read(UC_ARM64_REG_X19)
            self.wake_dispatch.append((hex(address-0x50000000),
                bytes(self.u.mem_read(x19+0x498,4)).hex(),
                bytes(self.u.mem_read(self.symbols['aux']+0x400+0xf2,16)).hex()))
        if address - 0x50000000 in (0x54e20, 0x69630, 0x739a0, 0x74abc,
                                    0x45ca4, 0x5da6c, 0x5dac4, 0x546f8, 0x451f0,
                                    0x480c4, 0x53818, 0x5c940, 0x5de60,
                                    0x5ef80, 0x69868, 0x54cc0, 0x55b30,
                                    0x565ac, 0x510d4, 0x4ca68, 0x4b4fc,
                                    0x74b20, 0x74b24,
                                    0x74b28, 0x74b2c, 0x74c94, 0x74cc0, 0x74c48,
                                    0x57c30, 0x57c84, 0x75b70, 0x75bdc,
                                    0x4b694, 0x4b9e4, 0x49f80, 0x4a0c8,
                                    0x5fe04, 0x5ffdc, 0x5ffe4, 0x5fff8,
                                    0x60020, 0x6015c, 0x73228,
                                    0x75b0c, 0x383a8, 0x38680, 0x3896c,
                                    0x38a24, 0x539d8, 0x36edc, 0x53668,
                                    0x38eb4, 0x39178, 0x39420, 0x3955c, 0x39698,
                                    0x71e7c, 0x725b4):
            at = hex(address - 0x50000000)
            self.path_hits[at] = self.path_hits.get(at, 0) + 1
        if 0x50000000 <= address < 0x50098000 and address - 0x50000000 in (
            0x883c0, 0x883f0, 0x6ecf8, 0x54320, 0x888b0, 0x88dd0,
            0x3f7f4, 0x7e934, 0x7e97c, 0x88990, 0x7c920, 0x88410,
            0x39288, 0x48018, 0x480c4, 0x4b3e0, 0x5d238, 0x55b30,
            0x48280, 0x75904, 0x54964, 0x72718):
            at = hex(address - 0x50000000)
            self.boundary_visits[at] = self.boundary_visits.get(at, 0) + 1
        if self.steps > 10000000:
            raise RuntimeError(f'instruction budget pc={address:#x}')

    def invalid(self, engine, access, address, size, value, data):
        self.fault = f'memory access {access} at {address:#x} pc={self.u.reg_read(UC_ARM64_REG_PC):#x} lr={self.u.reg_read(UC_ARM64_REG_LR):#x}'
        return False

    def interrupt(self, engine, number, data):
        regs = [self.u.reg_read(x) for x in (UC_ARM64_REG_X0, UC_ARM64_REG_X1,
                UC_ARM64_REG_X2, UC_ARM64_REG_X3, UC_ARM64_REG_X4, UC_ARM64_REG_X5)]
        op = self.u.reg_read(UC_ARM64_REG_X8)
        if op == 222:  # mmap anonymous private image
            self.u.mem_map(0x50000000, 0x99000)
            result = 0x50000000
        elif op == 226:  # Honor the generated worker's actual page permissions.
            self.u.mem_protect(regs[0], regs[1], regs[2])
            result = 0
        elif op == 261:  # prlimit64
            result = 0
        elif op == 167:  # prctl setup
            result = 0
        elif op == 173:  # getppid; stable process lease fixture
            result = 4242
        elif op in (198, 29, 56, 220):  # isolation self-checks
            result = (1 << 64) - 1
        elif op == 63:  # read
            count = min(regs[2], len(self.input))
            if count:
                self.u.mem_write(regs[1], bytes(self.input[:count]))
                del self.input[:count]
            result = count
        elif op == 64:  # write
            self.output.extend(self.u.mem_read(regs[1], regs[2]))
            if self.auto_replies:
                while True:
                    end = self.output.find(b'\n', self.handled_output)
                    if end < 0:
                        break
                    line = self.output[self.handled_output:end].decode('ascii')
                    self.handled_output = end + 1
                    if line.startswith('NET '):
                        fields = line.split()
                        if len(fields) != 5:
                            raise RuntimeError(f'malformed native NET {line}')
                        self.fixture_network_sends.append((fields[2], fields[3]))
                    if line.startswith('DONE ') and self.fixture_network_sends:
                        pending = self.fixture_network_sends
                        self.fixture_network_sends = []
                        suffix = bytearray()
                        for epoch, command in pending:
                            self.fixture_sent_sequence += 1
                            suffix.extend(f'OP {3000000+self.fixture_sent_sequence} {epoch} SENT {command}\n'.encode())
                        self.input[:0] = suffix
                    if line.startswith('CALL '):
                        fields = line.split()
                        _, ident, epoch, kind, *args = fields
                        if kind.startswith('SECONDARY_'):
                            self.secondary_calls.append((kind, args))
                        if kind == self.fail_call_kind and (kind != 'LINK_STATE' or args == ['0']):
                            value = 'ERR'
                        elif kind == 'SECONDARY_CONNECT':
                            assert args == [b'test.denzacloud.com'.hex(),
                                            b'203.0.113.7'.hex(), '6003']
                            value = 'CONNECTED 1'  # Explicit offline mTLS success fixture.
                        elif kind == 'SECONDARY_WRITE':
                            assert len(args) == 1 and 0 < len(bytes.fromhex(args[0])) <= 1024
                            count = len(bytes.fromhex(args[0]))
                            if self.secondary_write_count_override is not None:
                                count = self.secondary_write_count_override
                            value = 'WRITTEN ' + str(count)
                        elif kind == 'LINK_STATE':
                            assert args in (['0'], ['1'])
                            value = 'OK'  # Owner-local notification fixture only.
                        elif kind == 'SECONDARY_CLOSE':
                            assert not args
                            value = 'OK'
                        elif kind in ('GET_INT', 'GET_FLOAT'):
                            device, fid = map(int, args)
                            self.sdk_getter_requests.add((kind,device,fid))
                            values = {(1014, 0x14400008): 0,
                                      (1023, 0x2f4000fa): 1,
                                      (1001, 0x12d0002a): self.acc_getter,
                                      (1001, 0x40d00010): 0,
                                      (1005, 0x99000003): self.mcu_getter,
                                      (1014, 0x4a505038): 0x429e0000}
                            values.update(self.observed_getters)
                            if (device, fid) not in values:
                                self.unqualified_sdk_getters = getattr(self, 'unqualified_sdk_getters', set())
                                self.unqualified_sdk_getters.add((kind, device, fid))
                            value = 'VALUE ' + str(values.get((device, fid), 0))
                        elif kind == 'PROPERTY_GET':
                            key = bytes.fromhex(args[0]).decode('ascii')
                            self.property_get_requests.add(key)
                            values = {'persist.sys.vehicle_40d_code': '0',
                                      'persist.sys.record_610_upload': '0',
                                      'persist.sys.cloud_enable': '',
                                      'sys.cloud.unlock_index': '0'}
                            values.update(self.observed_properties)
                            if key in self.fixture_private_properties:
                                data = self.fixture_private_properties[key]
                                value = ('VALUE ' + data.encode().hex()) if data else 'VALUE -'
                            elif key in FIXTURE_PRIVATE_ONLY_PROPERTIES:
                                value = 'VALUE -'  # Unpublished private value is absent, not stock state.
                            elif key in values:
                                value = ('VALUE ' + values[key].encode().hex()) if values[key] else 'VALUE -'
                            elif (key == 'persist.sys.cloud.last_vin'
                                  and self.auto_vin19_fixture is not None):
                                value = 'VALUE ' + self.auto_vin19_fixture.hex()
                            elif key in ('persist.sys.gpsinfo',
                                         'persist.sys.cloud.token_flag',
                                         'persist.sys.system_info',
                                         'apps.setting.product.outswver',
                                         'mcu_version',
                                         'persist.sys.version',
                                         'persist.sys.mcu_version',
                                         'ro.vehicle.type.value',
                                         'persist.byd.telephony.networkType'):
                                value = 'VALUE -'
                            else:
                                self.unqualified_properties = getattr(self, 'unqualified_properties', set())
                                self.unqualified_properties.add(key)
                                value = 'VALUE -'  # Explicit missing-property exploration fixture.
                        elif kind == 'FILE_OPEN':
                            if (len(args)!=2 or bytes.fromhex(args[0])!=b'/data/cloudservice/div15_msg_info_542_vector.dat'
                                    or bytes.fromhex(args[1]) not in (b'r',b'rb')):
                                raise RuntimeError('unsupported fixture file open')
                            value='VALUE -2'  # Explicit absent-cache fixture, no file contents invented.
                        elif kind == 'MATH_POW':
                            import math
                            a,b=(struct.unpack('<d',struct.pack('<Q',int(x)))[0] for x in args)
                            value='VALUE '+str(struct.unpack('<Q',struct.pack('<d',math.pow(a,b)))[0])
                        elif kind == 'C_ATOF':
                            import ctypes
                            if len(args)!=1:
                                raise RuntimeError('invalid atof fixture shape')
                            source=b'' if args[0]=='-' else bytes.fromhex(args[0])
                            if len(source)>128 or any(b==0 or b>127 for b in source):
                                raise RuntimeError('invalid atof fixture input')
                            atof=ctypes.CDLL(None).atof
                            atof.argtypes=[ctypes.c_char_p];atof.restype=ctypes.c_double
                            value='VALUE '+str(struct.unpack('<Q',struct.pack('<d',atof(source)))[0])
                        elif kind == 'PROPERTY_SET_STATUS':
                            if (len(args)!=2 or bytes.fromhex(args[0])!=b'persist.sys.edge.enable.sre'
                                    or bytes.fromhex(args[1]) not in (b'0',b'1')):
                                raise RuntimeError('unsupported fixture property status')
                            self.fixture_ack_writes.append((kind,args))
                            value='VALUE '+str(self.property_status_fixture)
                        elif kind == 'PROPERTY_SET_RESULT':
                            if len(args)!=2:
                                raise RuntimeError('invalid property-result fixture shape')
                            key = bytes.fromhex(args[0]).decode('ascii')
                            data = '' if args[1]=='-' else bytes.fromhex(args[1]).decode('ascii')
                            self.fixture_ack_writes.append((kind,args))
                            if key in FIXTURE_LOCAL_WRITES:
                                self.fixture_private_properties[key] = data
                                value = 'VALUE 0'  # Isolated offline namespace, not a stock setter result.
                            else:
                                value = 'VALUE -1'  # No shared property write in this fixture.
                        elif kind in ('PROPERTY_SET', 'PROPERTY_SET_NULL'):
                            self.fixture_ack_writes.append((kind,args))
                            if kind == 'PROPERTY_SET_NULL':
                                if len(args)!=1:
                                    raise RuntimeError('invalid null-property fixture shape')
                                key = bytes.fromhex(args[0]).decode('ascii')
                                if key not in ('persist.sys.cloud.user_id', 'sys.cloud.unlock_uuid',
                                               'persist.sys.cloud_412_data'):
                                    raise RuntimeError('unsupported null-property fixture')
                                self.fixture_private_properties[key] = ''
                            value = 'OK'
                        elif kind == 'RANDOM_BYTES':
                            if args != ['16']:
                                raise RuntimeError(f'unexpected native random request {line}')
                            self.random_requests += 1
                            # Deterministic, distinct offline entropy fixture.
                            # Product must obtain fresh host entropy per CALL.
                            value = 'BYTES ' + bytes((0x20 + self.random_requests + i) & 255
                                                     for i in range(16)).hex()
                        elif kind == 'LOCALTIME':
                            import datetime
                            seconds = int(args[0])
                            tm = datetime.datetime.fromtimestamp(seconds,
                                datetime.timezone.utc).timetuple()
                            value = ('TM ' + ' '.join(str(v) for v in
                                (tm.tm_sec, tm.tm_min, tm.tm_hour, tm.tm_mday,
                                 tm.tm_mon - 1, tm.tm_year - 1900,
                                 (tm.tm_wday + 1) % 7, tm.tm_yday - 1, 0)))
                        elif kind == 'MKTIME':
                            import datetime
                            fields = list(map(int, args))
                            if len(fields) != 9:
                                raise RuntimeError(f'malformed native MKTIME {line}')
                            instant = datetime.datetime(fields[5] + 1900,
                                fields[4] + 1, fields[3], fields[2], fields[1],
                                fields[0], tzinfo=datetime.timezone.utc)
                            value = 'VALUE ' + str(int(instant.timestamp()))
                        elif kind == 'SET_INT':
                            if list(map(int, args)) not in ([1005, 0xaa00004a, 1],
                                                             [1027, 0xaa000026, 0]):
                                raise RuntimeError(f'unexpected native setter {line}')
                            value = 'OK'
                        elif kind == 'GET_BUFFER':
                            if list(map(int, args)) not in ([1027, 0x99000002],
                                                             [1027, 0x9900021a],
                                                             [1027, 0x99000035],
                                                             [1027, 0x99000402]):
                                raise RuntimeError(f'unexpected native buffer getter {line}')
                            self.get_buffer_calls = getattr(self, 'get_buffer_calls', 0) + 1
                            buffer_key=tuple(map(int,args))
                            if buffer_key in self.observed_buffers:
                                status,content=self.observed_buffers[buffer_key]
                                assert len(content)<=512 and (status==0 or not content)
                                value=f'BUFFER {status} '+(content.hex() if content else '-')
                            elif (list(map(int,args)) == [1027, 0x9900021a]
                                    and self.auto_vin_fixture is not None):
                                assert len(self.auto_vin_fixture) == 17
                                value = 'BUFFER 0 ' + self.auto_vin_fixture.hex()
                            elif (list(map(int,args)) == [1027, 0x99000035]
                                  and self.auto_vin19_fixture is not None):
                                assert len(self.auto_vin19_fixture) == 19
                                value = 'BUFFER 0 ' + self.auto_vin19_fixture.hex()
                            else:
                                # Explicit SDK-error branch fixture; it is not
                                # a captured buffer and never qualifies success.
                                value = 'BUFFER -1 -'
                        elif kind == 'DNS_LOOKUP':
                            fixtures = {
                                b'test.denzacloud.com': 'cb007107',
                                b'dilinkreg-cn.denzacloud.com': 'cb007108',
                                b'dilinkaddr-cn.denzacloud.com': 'cb007109',
                            }
                            hostname = bytes.fromhex(args[0]) if len(args) == 1 else b''
                            if hostname not in fixtures:
                                raise RuntimeError(f'unexpected native DNS hostname {line}')
                            # Explicit documentation-range resolver fixture,
                            # never a claim about the live service address.
                            value = 'IPV4 ' + fixtures[hostname]
                        elif kind == 'WAIT':
                            deadline_ns = int(args[0])
                            self.wait_count += 1
                            if self.wake_event_on_wait and self.wait_count == 1:
                                self.mcu_getter = 1
                                fresh_e = struct.pack('<9I', 0, 1, 0, 1,
                                    0x429e0000, 0, 1, 1, 255)
                                fresh_x = struct.pack('<4I', 0, 0, 0, 7)
                                wake_ms = deadline_ns // 1000000 - 900
                                value = (f'EVENT 1005 {0x99000003} 1 '
                                         f'{wake_ms} {wake_ms} {1700000000000 + wake_ms} '
                                         f'{fresh_e.hex()} {fresh_x.hex()}')
                            else:
                                wait_ms = (deadline_ns + 999999) // 1000000
                                value = (f'TIMEOUT {wait_ms} {wait_ms} '
                                         f'{1700000000000 + wait_ms}')
                        else:
                            raise RuntimeError(f'unexpected native CALL {line}')
                        self.input[:0] = f'RET {ident} {epoch} {value}\n'.encode()
            result = regs[2]
        elif op == 101:  # nanosleep; record exact external wait in emulator
            delay = struct.unpack('<QQ', self.u.mem_read(regs[0], 16))
            if delay not in ((0, 5000000), (0, 50000000), (0, 100000000)):
                raise RuntimeError(f'unexpected native wait {delay}')
            self.sleep_calls.append(delay)
            result = 0
        elif op in (93, 94):
            self.exited = regs[0]
            self.u.emu_stop()
            result = 0
        else:
            raise RuntimeError(f'unhandled interrupt {number} syscall {op} pc={self.u.reg_read(UC_ARM64_REG_PC):#x} lr={self.u.reg_read(UC_ARM64_REG_LR):#x} args={regs}')
        self.u.reg_write(UC_ARM64_REG_X0, result)

    def run(self):
        try:
            self.u.emu_start(self.entry, 0, count=10000000)
        except Exception as error:
            raise RuntimeError(getattr(self, 'fault', str(error)) + '\nOUTPUT: ' + self.output.decode('ascii', errors='replace')) from error
        return self.exited, self.output.decode('ascii', errors='replace')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('binary', type=Path)
    p.add_argument('script', type=Path, nargs='?')
    p.add_argument('--self-test', type=Path, metavar='PINNED_FIRMWARE')
    args = p.parse_args()
    if args.self_test:
        print(json.dumps(self_test(args.binary, args.self_test), indent=2))
        return
    if args.script is None:
        p.error('script is required without --self-test')
    code, output = Process(args.binary, args.script.read_bytes()).run()
    print(output, end='')
    print(f'EXIT {code}')


def self_test(binary: Path, firmware: Path):
    from verify_native_roundtrip import VIN_VALUE, KEY, UUID, HELPER, PACKET, WIRE
    from verify_native_roundtrip import Roundtrip
    from verify_opaque_native import INPUT, read_buffers
    from native_session import NativeSession
    from verify_generic_control_closure import GenericControl
    from verify_native_control import NativeControl

    peer = NativeSession(firmware, VIN_VALUE, KEY, UUID,
                         b'89010000000000000001', b'001010123456789', b'', 1700000000)
    peer.u.mem_write(INPUT, b'\x01')
    peer.call(0x6e3b0, HELPER, 0, PACKET, 211, 1, INPUT, 1, 0)
    peer.call(0x6e858, HELPER, PACKET, WIRE, 1)
    registration = peer.frames[-1]
    peer.u.mem_write(INPUT, bytes(16))
    peer.call(0x6e3b0, HELPER, 0, PACKET, 220, 1, INPUT, 16, 0)
    peer.call(0x6e858, HELPER, PACKET, WIRE, 16)
    login = peer.frames[-1]
    endpoint_host = b'test.denzacloud.com'
    endpoint_body = (6003).to_bytes(2, 'big') + bytes(20) + bytes([len(endpoint_host)]) + endpoint_host
    peer.u.mem_write(INPUT, endpoint_body)
    peer.call(0x6e3b0, HELPER, 0, PACKET, 200, 1, INPUT, len(endpoint_body), 0)
    peer.call(0x6e858, HELPER, PACKET, WIRE, len(endpoint_body))
    discovery = peer.frames[-1]
    snapshot = struct.pack('<9I', 2, 1, 0, 1,
                           struct.unpack('<I', struct.pack('<f', 79.0))[0],
                           0, 1, 1, 255)
    identity = [('V', VIN_VALUE), ('K', KEY), ('U', UUID),
                ('C', b'89010000000000000001'), ('M', b'001010123456789'),
                ('S', b''), ('A', b'double_apn'),
                ('T', struct.pack('<I', 1700000000)), ('N', bytes(range(32, 48)))]
    identity_prefix = ''.join(f'OP {i} 1 {name} {value.hex()}\n'
                              for i, (name, value) in enumerate(identity, 1))
    state_snapshot = struct.pack('<4I', 0, 0, 0, 7)
    prefix = identity_prefix + (
        'OP 10 1 START\nOP 11 1 TICK 0 0 1700000000000\n'
        'OP 12 1 NETSTATE 4\n'
        f'OP 19 1 R211 {registration.hex()}\n'
        f'OP 21 1 R200 {discovery.hex()}\n'
        f'OP 200 1 E {snapshot.hex()}\nOP 201 1 X {state_snapshot.hex()}\n'
        'OP 17 1 TICK 0 0 1700000000000\n'
        f'OP 18 1 R220 {login.hex()}\n'
        f'OP 202 1 INT 1005 {0x99000003} 1\n')
    cases = []
    discovery_probe = identity_prefix + f'OP 10 1 START\nOP 19 1 R200 {discovery.hex()}\nQUIT\n'
    discovery_process = Process(binary, discovery_probe.encode(), auto_replies=True)
    code, discovery_output = discovery_process.run()
    if code != 1 or '"stage":"discovery_sender_absent"' not in discovery_output or \
       'STOCK_LIFECYCLE_BRIDGE=0' not in discovery_output or \
       'POSIX_TIMERS=0' not in discovery_output or \
       ' DNS_LOOKUP ' in discovery_output:
        raise AssertionError(f'original R200 resolver gate: {discovery_output}')
    cases.append({'original_R200_resolver_gate':
                  'R200 before NETSTATE cannot use an uninitialized original sender',
                  'native_dns_calls': 0})
    cases.append({'stock_lifecycle_capability': 'STOCK_LIFECYCLE_BRIDGE=0 in native CAPS; no detached system_server status callback'})
    cases.append({'posix_timer_capability': 'POSIX_TIMERS=0 in native CAPS; offline scheduler replay does not qualify Android suspend/wall semantics'})
    network_probe = identity_prefix + ('OP 10 1 START\n'
                                       'OP 11 1 TICK 0 0 1700000000000\n'
                                       'OP 20 1 NETSTATE 4\nQUIT\n')
    network_process = Process(binary, network_probe.encode(), auto_replies=True)
    code, network_output = network_process.run()
    network_gate = bytes(network_process.u.mem_read(
        network_process.symbols['object'] + 0x35d, 2)).hex()
    if code != 0 or 'DONE 20 1 NETSTATE' not in network_output or \
       network_gate != '0101' or \
       'CALL 20 1 GET_BUFFER 1027 2566914586' not in network_output or \
       'CALL 20 1 SET_INT 1027 2852126758 0' not in network_output or \
       network_process.path_hits.get('0x4b9e4') != 1:
        raise AssertionError(f'original public network callback frontier gate={network_gate}: {network_output}')
    cases.append({'original_virtual_public_network_callback':
                  '0x4b694 sets OBJ+0x35d/35e via original state4 branch; explicit getBuffer error and setter ACK fixtures',
                  'native_gate_after_callback': network_gate,
                  'original_hits': {key: network_process.path_hits.get(key, 0)
                                    for key in ('0x4b694','0x4b9e4','0x49f80','0x4a0c8')},
                  'sdk_getBuffer_1027_9900021a': 'explicit error fixture'})
    for sub, duration in ((3, 16000), (17, 32000), (39, 10000)):
        frame, _ = GenericControl(firmware).request(sub)
        script = (prefix + f'OP 13 1 E {snapshot.hex()}\n'
                  f'OP 14 1 RX {frame.hex()}\nOP 15 1 TICK {duration} {duration} {1700000000000 + duration}\nQUIT\n')
        process = Process(binary, script.encode(), auto_replies=True)
        code, output = process.run()
        lines = output.splitlines()
        arm = next((x for x in lines if x.startswith('ARM 14 1 ')), None)
        if code != 0 or not arm or not arm.endswith(f' {duration}') or \
           not any(x.startswith('CANCEL 15 1 ') for x in lines) or \
           not any(x.startswith('FIRED 15 1 ') for x in lines):
            raise AssertionError(f'timer {sub}: {code} {output}')
        auto = [x for x in lines if x.startswith('AUTO 14 1 ')]
        net_request = [x for x in lines if x.startswith(('NET 14 1 ', 'CALL 14 1 SECONDARY_WRITE '))]
        net_expiry = [x for x in lines if x.startswith(('NET 15 1 ', 'CALL 15 1 SECONDARY_WRITE '))]
        if sub == 39:
            if len(auto) != 5 or process.sleep_calls.count((0, 100000000)) != 6 or \
               len(net_request) != 2 or len(net_expiry) != 1:
                raise AssertionError(f'sub39 effects: auto={len(auto)} waits100={process.sleep_calls.count((0, 100000000))} net_request={len(net_request)} net_expiry={len(net_expiry)} waits={process.sleep_calls} {output}')
        elif len(auto) != 1 or len(net_request) != 1 or net_expiry:
            raise AssertionError(f'generic timeout effects: {output}')
        cases.append({'subcommand': sub, 'deadline_ms': duration,
                      'original_expiry_net': len(net_expiry),
                      'opaque_auto_writes': len(auto),
                      'external_100ms_waits': process.sleep_calls.count((0, 100000000)),
                      'postlogin_100ms_waits_in_prefix': 1})

    frame, _ = GenericControl(firmware).request(3)
    native = GenericControl(firmware)
    native.receive(frame)
    terminal = bytearray(native.vehicle_writes[-1])
    terminal[4] = 1  # MCU flag on a frame made by original firmware.
    for late in (False, True):
        script = prefix + f'OP 13 1 E {snapshot.hex()}\nOP 14 1 RX {frame.hex()}\n'
        if late:
            script += 'OP 15 1 TICK 16000 16000 1700000016000\n'
        script += f'OP 16 1 E {snapshot.hex()}\nOP 17 1 MCU {terminal.hex()}\n'
        if not late:
            script += 'OP 18 1 TICK 16000 16000 1700000016000\n'
        script += 'QUIT\n'
        process = Process(binary, script.encode(), auto_replies=True)
        code, output = process.run()
        if code != 0 or 'DONE 17 1 532 1 1' not in output or \
           'CALL 17 1 PROPERTY_SET_RESULT 7379732e636c6f75645f3533325f7265706c79 ' not in output or \
           f'CALL 17 1 LOCALTIME {1700000016 if late else 1700000000}' not in output or \
           'CALL 17 1 MKTIME ' not in output or \
           process.path_hits.get('0x54e20') != 1 or process.path_hits.get('0x69630') != 1:
            raise AssertionError(f'terminal late={late}: {output}')
        if late:
            if 'FIRED 15 1 ' not in output or 'CANCEL 17 1 ' in output:
                raise AssertionError(f'late terminal semantics: {output}')
        elif 'CANCEL 17 1 ' not in output or 'FIRED ' in output:
            raise AssertionError(f'terminal cancellation: {output}')
        cases.append({'terminal_after_expiry': late,
                      'native_terminal_reply': True,
                      'timer_canceled_before_firing': not late,
                      'original_result_journal': '0x54e20 and native local date helper'})
    script = (prefix + f'OP 13 1 E {snapshot.hex()}\n'
              f'OP 14 1 RX {frame.hex()}\n')
    code, output = Process(binary, script.encode(), auto_replies=True,
                           fail_call_kind='GET_INT').run()
    if code != 1 or '"stage":"call_reply_failed"' not in output:
        raise AssertionError(f'missing getter did not fail closed: {output}')
    cases.append({'missing_host_getter': 'fails closed'})

    sub5_frame, _ = GenericControl(firmware).request(5)
    sub5_native = GenericControl(firmware)
    sub5_native.receive(sub5_frame)
    sub5_reply = bytearray(sub5_native.vehicle_writes[-1]);sub5_reply[4] = 1
    sub5_script = (prefix + f'OP 260 1 E {snapshot.hex()}\n'
                   f'OP 261 1 RX {sub5_frame.hex()}\n'
                   f'OP 262 1 E {snapshot.hex()}\n'
                   f'OP 263 1 MCU {sub5_reply.hex()}\n'
                   'OP 264 1 TICK 5000 5000 1700000005000\nQUIT\n')
    sub5_process = Process(binary, sub5_script.encode(), auto_replies=True)
    code, output = sub5_process.run()
    if code != 0 or 'ARM 263 1 ' not in output or \
       'FIRED 264 1 ' not in output or 'DONE 263 1 532 1 1' not in output:
        raise AssertionError(f'sub5 timer original closure: {output}')
    cases.append({'sub5_timer_original': 'terminal1 schedules 5s; original 0x45ca4 fired',
                  'original_sender_hits': sub5_process.path_hits.get('0x739a0', 0),
        'network_bridge_hits': sub5_process.path_hits.get('0x74abc', 0),
                  'endpoint_setup_hits': {key: sub5_process.path_hits.get(key, 0)
                                          for key in ('0x57c30', '0x57c84', '0x75b70')},
                  'network_bridge_branch_hits': {key: value for key, value in
                      sub5_process.path_hits.items() if key in
                      ('0x74b20','0x74b24','0x74b28','0x74b2c','0x74c94','0x74cc0','0x74c48')},
                  'NET_on_terminal': output.count('NET 263 1 '),
                  'NET_on_expiry': output.count('NET 264 1 ')})

    script = (prefix + f'OP 13 1 E {snapshot.hex()}\n'
              f'OP 14 1 RX {frame.hex()}\n'
              f'OP 16 1 E {snapshot.hex()}\n'
              f'OP 17 1 MCU {terminal.hex()}\n')
    code, output = Process(binary, script.encode(), auto_replies=True,
                           fail_call_kind='PROPERTY_SET_RESULT').run()
    if code != 1 or '"stage":"call_reply_failed"' not in output:
        raise AssertionError(f'host property failure was accepted: {output}')
    cases.append({'failed_host_property_write': 'fails closed'})

    sleep_index = struct.pack('<4I', 0, 0, 0, 7)
    awake_index = struct.pack('<4I', 0, 0, 1, 7)
    script = (prefix + f'OP 203 1 E {snapshot.hex()}\n'
              f'OP 204 1 X {sleep_index.hex()}\n'
              f'OP 205 1 INT 1005 {0x99000003} 0\n'
              ''
              f'OP 206 1 E {snapshot.hex()}\n'
              f'OP 207 1 X {awake_index.hex()}\n'
              f'OP 208 1 INT 1005 {0x99000003} 1\n'
              f'OP 209 1 E {snapshot.hex()}\n'
              f'OP 210 1 X {awake_index.hex()}\n'
              f'OP 211 1 INT 1005 {0x99000003} 1\nQUIT\n')
    code, output = Process(binary, script.encode(), auto_replies=True).run()
    if code != 0 or output.count('CALL 205 1 PROPERTY_SET_RESULT ') != 2 or \
       output.count('NET 205 1 ') != 1 or output.count('NET 208 1 ') != 1 or \
       'NOTIFY 208 1 2' not in output or 'NET 211 1 ' in output or \
       'NOTIFY 211 1 ' in output:
        raise AssertionError(f'original MCU observer transition: {output}')
    cases.append({'original_integer_observer': 'awake-sleep-awake',
                  'native_502_count': 3,
                  'sleep_property_writes_confirmed': 2,
                  'duplicate_awake_effects': 0})

    callback = read_buffers(firmware.parents[5] /
                            'telematics-20260924/native-session-status/live-1/callbacks.log')[0]
    status_request = Roundtrip(firmware).request()
    status_script = (prefix + f'OP 240 1 I {callback.hex()}\n'
                     f'OP 241 1 R511 {status_request.hex()}\nQUIT\n')
    status_process = Process(binary, status_script.encode(), auto_replies=True)
    code, output = status_process.run()
    status = next((line for line in output.splitlines()
                   if line.startswith('RESULT 241 1 STATUS ')), '')
    fields = status.split()
    if code != 0 or len(fields) != 5 or len(bytes.fromhex(fields[4])) < 50:
        raise AssertionError(f'opaque native STATUS result: {output}')
    cases.append({'opaque_STATUS_IPC': 'RESULT id epoch STATUS framehex; no SOC field'})
    postlogin_paths = ('0x55b30','0x565ac','0x510d4','0x4ca68','0x4b4fc')
    postlogin_hits = {key: status_process.path_hits.get(key,0) for key in postlogin_paths}
    postlogin_net = [line for line in output.splitlines() if line.startswith('NET 18 1 ')]
    if any(value!=1 for value in postlogin_hits.values()) or len(postlogin_net)!=8:
        raise AssertionError(f'original post-login path/effects: {postlogin_hits} net={len(postlogin_net)} {output}')
    cases.append({'original_post_login_fixture':postlogin_hits,
                  'native_opaque_frames':len(postlogin_net),
                  'original_body_command_sequence':status_process.body_commands[:9],
                  'fixture_limits':'unknown SDK getters returned zero, unknown properties returned empty, GET_BUFFER returned explicit SDK error; property writes received fixture ACK, not live write proof',
                  'sdk_getter_requests':[(kind,device,hex(fid)) for kind,device,fid in sorted(status_process.sdk_getter_requests)],
                  'unqualified_sdk_zero_returns':[(kind,device,hex(fid)) for kind,device,fid in sorted(getattr(status_process,'unqualified_sdk_getters',set()))],
                  'property_get_requests':sorted(status_process.property_get_requests),
                  'unqualified_empty_property_returns':sorted(getattr(status_process,'unqualified_properties',set())),
                  'fixture_acked_property_writes':status_process.fixture_ack_writes,
                  'remaining_noop_visits':{key:value for key,value in
                      status_process.boundary_visits.items() if key in
                      ('0x48280','0x75904','0x54964','0x72718')}})

    wake_peer = NativeControl(firmware)
    wake_peer.u.mem_write(INPUT, bytes(16))
    wake_peer.call(0x6e3b0, HELPER, 0, PACKET, 536, 254, INPUT, 0, 0)
    wake_peer.call(0x6e858, HELPER, PACKET, WIRE, 0)
    sleeping_env = struct.pack('<9I', 0, 0, 0, 1, 0x429e0000,
                               0, 1, 1, 255)
    sleeping = prefix + (f'OP 217 1 E {sleeping_env.hex()}\n'
                         f'OP 218 1 X {sleep_index.hex()}\n'
                         f'OP 219 1 INT 1005 {0x99000003} 0\n'
                         f'OP 220 1 E {sleeping_env.hex()}\n'
                         f'OP 221 1 X {sleep_index.hex()}\n'
                         'OP 222 1 TICK 0 0 1700000000000\n'
                         f'OP 223 1 E {sleeping_env.hex()}\n'
                         f'OP 224 1 X {sleep_index.hex()}\n'
                         f'OP 225 1 RX {wake_peer.frames[-1].hex()}\n'
                         f'OP 226 1 E {sleeping_env.hex()}\n'
                         f'OP 227 1 X {sleep_index.hex()}\n'
                         f'OP 228 1 INT 1005 {0x99000003} 1\n'
                         'OP 229 1 TICK 8000 8000 1700000008000\nQUIT\n')
    wake_process = Process(binary, sleeping.encode(), auto_replies=True,
                           mcu_getter=0, acc_getter=0)
    code, output = wake_process.run()
    if code != 0 or 'ARM 225 1 ' not in output or \
       'CANCEL 228 1 ' not in output or output.count(' SET_INT ') != 6 or \
       output.count(' WAIT ') != 6 or 'FIRED 229 1 ' in output:
        image_base = struct.unpack('<Q', wake_process.u.mem_read(
            wake_process.symbols['base'], 8))[0]
        vector = bytes(wake_process.u.mem_read(image_base + 0x910f8, 24)).hex()
        raise AssertionError(f'sleeping 536 native request vector={vector} at_drain={wake_process.queue_at_drain} dispatch={wake_process.wake_dispatch} paths={wake_process.path_hits}: {output}')
    cases.append({'sleeping_536_request': 'original post-login wait and timer path',
                  'wake_set_calls': output.count(' SET_INT '),
                  'condition_wait_calls': output.count(' WAIT '),
                  'timer_arms': output.count('ARM 225 1 '),
                  'wake_callback_opaque_writes': output.count('AUTO 228 1 '),
                  'timer_cancelled_on_actual_observer': True,
                  'native_queue_at_drain': wake_process.queue_at_drain,
                  'native_queue_path_hits': {key: wake_process.path_hits.get(key, 0)
                         for key in ('0x5fe04','0x5ffdc','0x5ffe4','0x5fff8','0x60020','0x6015c')}})

    expiring = sleeping.split('OP 226 1 E ')[0] + 'OP 229 1 TICK 8000 8000 1700000008000\nQUIT\n'
    expiry_process = Process(binary, expiring.encode(), auto_replies=True,
                             mcu_getter=0, acc_getter=0)
    code, output = expiry_process.run()
    expiry_image = struct.unpack('<Q', expiry_process.u.mem_read(
        expiry_process.symbols['base'], 8))[0]
    expiry_queue = struct.unpack('<QQQ', expiry_process.u.mem_read(
        expiry_image + 0x910f8, 24))
    expiry_arm = next((line.split() for line in output.splitlines()
                       if line.startswith('ARM 225 1 ')), None)
    expiry_handle = expiry_arm[3] if expiry_arm else None
    if code != 0 or expiry_handle is None or \
       f'FIRED 229 1 {expiry_handle}' not in output or \
       not any(line.startswith('ARM 229 1 ') and line.endswith(' 13000')
               for line in output.splitlines()) or \
       'NET 229 1 ' in output or 'AUTO 229 1 ' in output or \
       expiry_process.path_hits.get('0x54cc0') != 1:
        raise AssertionError(f'536 native expiry/rearm boundary: {output}')
    cases.append({'536_expiry_original':
                  '0x5e7e8 -> 0x611b8 -> 0x54cc0; native queue remains present, timer rearms 5s without response in fixture',
                  'native_queue_nonempty_after_expiry': expiry_queue[0] != expiry_queue[1],
                  'native_5s_rearm': True,
                  'expiry_network_effects': 0})

    waking = sleeping.split('OP 226 1 E ')[0] + 'QUIT\n'
    event_process = Process(binary, waking.encode(), auto_replies=True,
                            mcu_getter=0, acc_getter=0,
                            wake_event_on_wait=True)
    code, output = event_process.run()
    if code != 0 or 'NOTIFY 225 1 ' not in output or \
       'NET 225 1 ' not in output or 'DONE 225 1 536 ' not in output or \
       event_process.wait_count != 1:
        raise AssertionError(f'cooperative original wake event: {output}')
    cases.append({'cooperative_WAIT_event':
                  'actual INT observer inside original 0x4a96c; no nested DONE',
                  'wait_calls': event_process.wait_count})

    acc_event = (prefix + f'OP 230 1 E {snapshot.hex()}\n'
                 f'OP 231 1 X {sleep_index.hex()}\n'
                 f'OP 232 1 INT 1001 {0x12d0002a} 1\nQUIT\n')
    code, output = Process(binary, acc_event.encode(), auto_replies=True).run()
    if code != 1 or '"stage":"event_publisher_unqualified"' not in output:
        raise AssertionError(f'ACC observer escaped publisher boundary: {output}')
    cases.append({'original_ACC_observer_frontier':
                  '0x6822c -> original 0x5d2e0 -> external EventPublisher.publish, fail closed'})

    charge_event = (prefix + f'OP 233 1 E {snapshot.hex()}\n'
                    f'OP 234 1 X {sleep_index.hex()}\n'
                    f'OP 235 1 INT 1009 {0x34400018} 1\nQUIT\n')
    try:
        code, output = Process(binary, charge_event.encode(),
                               auto_replies=True).run()
        cases.append({'original_charge_observer': 'completed' if code == 0 else
                      'failed closed', 'output_tail': output.splitlines()[-2:]})
    except RuntimeError as error:
        cases.append({'original_charge_observer_frontier': str(error).splitlines()[0]})

    script = prefix
    for i in range(20):
        sender = GenericControl(firmware)
        opaque = bytes((32 + i + j) & 255 for j in range(16)) + bytes((3, 0, 0, 0))
        sender.u.mem_write(INPUT, opaque)
        sender.call(0x6e3b0, HELPER, 0, PACKET, 532, 254, INPUT, len(opaque), 0)
        sender.call(0x6e858, HELPER, PACKET, WIRE, len(opaque))
        request = sender.frames[-1]
        op = 13 + i * 3
        script += (f'OP {op} 1 E {snapshot.hex()}\n'
                   f'OP {op + 1} 1 RX {request.hex()}\n'
                   f'OP {op + 2} 1 TICK {(i + 1) * 16000} {(i + 1) * 16000} {1700000000000 + (i + 1) * 16000}\n')
    process = Process(binary, (script + 'QUIT\n').encode(), auto_replies=True)
    code, output = process.run()
    if code != 0 or output.count('ARM ') != 20 or \
       output.count('FIRED ') != 20 or output.count('CANCEL ') != 20:
        raise AssertionError(f'persistent reuse: {output}')
    cases.append({'sequential_original_controls': 20,
                  'distinct_timer_generations': 20,
                  'bounded_engine_exit': 0})
    return {'passed': True, 'qualification': 'offline research only',
            'firmware': firmware.name, 'cases': cases,
            'remaining_boundaries': [
                'original post-login returns for one absent-property/getBuffer-error fixture; alternative property/SDK branches and heartbeat scheduling remain unqualified',
                'virtual public network callback sets native gate, then continues into unclosed SDK readiness/setter chain before R200/DNS',
                'original 0x54e20 executes; shared result-property ownership and all result classes remain unqualified',
                'sub5 TimerManager and callback execute; original 0x52ea0 R200 resolver is gated by absent listener state OBJ+0x35e, so sender has no qualified endpoint and emits no NET',
                'sleeping 536 queue path and original expiry need a qualified nonempty post-login fixture and actual SDK wake result',
                'shared property writes remote_controling=1, 505_req_status=1 and tcp_connect_status=1 require exact ownership/crash cleanup',
                'ACC 0x5d2e0 reaches unqualified EventPublisher.publish; charge fixture completion does not establish all continuations',
                'nonempty native listener/alarm delivery remains unqualified']}


if __name__ == '__main__':
    main()
