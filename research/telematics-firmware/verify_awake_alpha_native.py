#!/usr/bin/env python3
"""Focused offline awake-alpha replay; never accesses the car or network.

Synthetic DNS/documentation IPv4, VIN buffers, absent properties, SDK getter
defaults, and fixture write/TLS ACKs are unqualified for live use. Every
generated wire and MCU frame comes from the pinned original firmware codecs.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path

from native_session import NativeSession
from verify_generic_control_closure import GenericControl
from verify_native_control import NativeControl
from verify_native_roundtrip import VIN_VALUE, KEY, UUID, HELPER, PACKET, WIRE
from verify_opaque_native import INPUT
from verify_persistent_engine import Process


def run(binary: Path, firmware: Path, observed_path=None):
    observed = {}
    if observed_path is not None:
        raw = observed_path.read_bytes()
        snapshot = json.loads(raw)
        getters = {}
        for key, entry in snapshot['getters'].items():
            device, fid = key.split('/')
            assert entry['status'] == 0
            getters[(int(device), int(fid, 16))] = entry['value']
        observed = {'observed_getters': getters,
                    'observed_properties': {key: entry['value'] for key, entry in
                                            snapshot['properties'].items()}}
    peer = NativeSession(firmware, VIN_VALUE, KEY, UUID,
                         b'89010000000000000001', b'001010123456789', b'',
                         1700000000)

    def fixture(command, flag, body):
        peer.u.mem_write(INPUT, body)
        peer.call(0x6e3b0, HELPER, 0, PACKET, command, flag, INPUT,
                  len(body), 0)
        peer.call(0x6e858, HELPER, PACKET, WIRE, len(body))
        return peer.frames[-1]

    host = b'test.denzacloud.com'
    discovery = fixture(200, 1, (6003).to_bytes(2, 'big') + bytes(20) +
                        bytes([len(host)]) + host)
    registration_reply = fixture(211, 1, b'\x01')
    login = fixture(220, 1, bytes(16))
    sender = GenericControl(firmware)
    request, _ = sender.request(5)
    recipient = GenericControl(firmware)
    recipient.receive(request)
    terminal = bytearray(recipient.vehicle_writes[-1])
    terminal[4] = 1
    auto_vin19 = VIN_VALUE + b'00'
    identity = [('V', VIN_VALUE), ('K', KEY), ('U', UUID),
                ('C', b'89010000000000000001'), ('M', b'001010123456789'),
                ('S', b''), ('A', b'double_apn'),
                ('T', struct.pack('<I', 1700000000)),
                ('N', bytes(range(32, 48)))]
    script = ''.join(f'OP {i} 1 {name} {value.hex()}\n'
                     for i, (name, value) in enumerate(identity, 1))
    identity_script = script
    # Mirror the owner order: establish all clock domains before the original
    # network-state callback can arm its first native relative timer.
    script += ('OP 10 1 START\nOP 11 1 TICK 0 0 1700000000000\n'
               'OP 12 1 NETSTATE 4\n' +
               f'OP 13 1 R211 {registration_reply.hex()}\n' +
               f'OP 21 1 R200 {discovery.hex()}\n')
    ready = Process(binary, (script + 'QUIT\n').encode(), auto_replies=True,
                    auto_vin_fixture=VIN_VALUE,
                    auto_vin19_fixture=auto_vin19, **observed)
    ready_code, ready_output = ready.run()
    assert ready_code == 0 and 'DONE 12 1 NETSTATE' in ready_output, (ready_output,ready.buffer_getter_calls,ready.guard_calls,ready.dns_requests,ready.timer_settime_requests,ready.string_copy_calls,ready.sender_signals)
    assert 'DONE 11 1 TICK' in ready_output
    assert 'CALL 21 1 DNS_LOOKUP ' in ready_output
    assert 'RESULT 21 1 ENDPOINT test.denzacloud.com 6003' in ready_output
    assert ready.path_hits.get('0x75b70') == 1
    result = {'qualified_for_live': False,
              'native_network_and_discovery': 'original NETSTATE4 -> 0x52ea0 DNS -> 0x75b70 sender endpoint',
              'original_send_gate_after_network': {
                  'network_byte_35d': int.from_bytes(ready.u.mem_read(ready.symbols['object']+0x35d, 1), 'little'),
                  'connection_word_594': int.from_bytes(ready.u.mem_read(ready.symbols['object']+0x594, 4), 'little'),
                  'login_byte_2d0': int.from_bytes(ready.u.mem_read(ready.symbols['object']+0x2d0, 1), 'little'),
              },
              'netstate_dns_requests': [host.decode('ascii') for _, host in ready.dns_requests],
              'netstate_timer_arms': [line for line in ready_output.splitlines()
                                     if line.startswith('ARM 12 1 ')],
              'explicit_fixture': {'dns_ipv4': '203.0.113.7/.8/.9 documentation addresses',
                                   'getBuffer_1027_9900021a': 'synthetic VIN17 success',
                                   'getBuffer_1027_99000035': 'synthetic AUTO_VIN19 success',
                                   'persist.sys.cloud.last_vin': 'same synthetic AUTO_VIN19',
                                   'other_unknown_getters': 'zero fixture, not live proof',
                                   'unknown_properties': 'empty fixture, not live proof',
                                   'property_writes': 'fixture ACK, not live proof',
                                   'secondary_mutual_tls': 'CONNECT/WRITE fixture ACK, not live proof'}}
    if observed_path is not None:
        result['observed_read_only_snapshot_sha256'] = hashlib.sha256(raw).hexdigest()

    registration_script = (identity_script + 'OP 10 1 START\n'
                           'OP 11 1 TICK 0 0 1700000000000\n'
                           'OP 12 1 NETSTATE 4\n'
                           f'OP 13 1 R211 {registration_reply.hex()}\nQUIT\n')
    registration = Process(binary, registration_script.encode(), auto_replies=True,
                           auto_vin_fixture=VIN_VALUE,
                           auto_vin19_fixture=auto_vin19, **observed)
    try:
        registration_code, registration_output = registration.run()
        registration_frontier = None
    except RuntimeError as error:
        registration_code = None
        registration_output = registration.output.decode('ascii', errors='replace')
        registration_frontier = str(error).splitlines()[0]
    registration_complete = (registration_code == 0 and
                             'RESULT 13 1 REG 1' in registration_output and
                             'DONE 13 1 RX' in registration_output and
                             registration_output.count('NET 13 1 ') == 1 and
                             registration.body_commands.count(201) == 1 and
                             registration.body_commands.count(502) == 1)
    result['registration_continuation'] = {
        'completed': registration_complete,
        'frontier': registration_frontier,
        'native_random_calls': registration.random_requests,
        'native_201_frames': registration.body_commands.count(201),
        'native_502_frames': registration.body_commands.count(502),
        'native_dns_requests': [host.decode('ascii') for _, host in registration.dns_requests],
        'original_listener_dispatch_visits': registration.boundary_visits.get('0x48280', 0),
        'remaining_noop_visits': {key: value for key, value in registration.boundary_visits.items()
                                  if key in ('0x75904', '0x54320', '0x72718')},
        'effect_verbs': [line.split()[3] if line.startswith('CALL ') else line.split()[0]
                         for line in registration_output.splitlines()
                         if line.startswith(('CALL ', 'NET ', 'AUTO ', 'ARM ', 'CANCEL '))],
        'output_tail': registration_output.splitlines()[-8:],
    }
    assert registration_complete, result['registration_continuation']
    rejected_reply = fixture(211, 1, b'\x00')
    rejected_script = registration_script.replace(registration_reply.hex(),
                                                   rejected_reply.hex())
    rejected = Process(binary, rejected_script.encode(), auto_replies=True,
                       auto_vin_fixture=VIN_VALUE,
                       auto_vin19_fixture=auto_vin19, **observed)
    try:
        rejected_code, rejected_output = rejected.run()
        rejected_frontier = None
    except RuntimeError as error:
        rejected_code = None
        rejected_output = rejected.output.decode('ascii', errors='replace')
        rejected_frontier = str(error).splitlines()[0]
    result['registration_rejection'] = {
        'completed': rejected_code == 0 and 'RESULT 13 1 REG 0' in rejected_output,
        'frontier': rejected_frontier,
        'original_listener_visits': rejected.boundary_visits.get('0x48280', 0),
        'original_destroy_visits': rejected.boundary_visits.get('0x75904', 0),
        'output_tail': rejected_output.splitlines()[-8:],
    }

    environment = struct.pack('<9I', 2, 1, 0, 1,
                              struct.unpack('<I', struct.pack('<f', 79.0))[0],
                              0, 1, 1, 255)
    wake = struct.pack('<4I', 0, 0, 0, 7)
    wake_peer = NativeControl(firmware)
    wake_peer.u.mem_write(INPUT, bytes(16))
    wake_peer.call(0x6e3b0, HELPER, 0, PACKET, 536, 254, INPUT, 0, 0)
    wake_peer.call(0x6e858, HELPER, PACKET, WIRE, 0)
    awake_script = (script + f'OP 220 1 E {environment.hex()}\n'
                    f'OP 221 1 X {wake.hex()}\n'
                    'OP 222 1 TICK 0 0 1700000000000\n'
                    f'OP 22 1 R220 {login.hex()}\n'
                    f'OP 23 1 E {environment.hex()}\n'
                    f'OP 24 1 X {wake.hex()}\n'
                    f'OP 25 1 INT 1005 {0x99000003} 1\n'
                    f'OP 26 1 E {environment.hex()}\n'
                    f'OP 27 1 X {wake.hex()}\n'
                    'OP 28 1 TICK 0 0 1700000000000\n'
                    f'OP 29 1 RX {wake_peer.frames[-1].hex()}\nQUIT\n')
    awake = Process(binary, awake_script.encode(), auto_replies=True,
                    auto_vin_fixture=VIN_VALUE,
                    auto_vin19_fixture=auto_vin19, **observed)
    awake_code, awake_output = awake.run()
    assert awake_code == 0 and 'DONE 29 1 536 ' in awake_output, (awake_code,awake_output[-5000:],awake.sender_signals)
    result['awake_536'] = {'completed': True,
                           'native_wake_set_calls': awake_output.count('CALL 29 1 SET_INT 1005 '),
                           'opaque_auto_writes': awake_output.count('AUTO 29 1 '),
                           'network_effects': awake_output.count('NET 29 1 '),
                           'original_post_login_hits': awake.path_hits.get('0x55b30', 0),
                           'qualification': 'fixture only: real SDK getters and shared post-login write unproven'}
    script += (f'OP 220 1 E {environment.hex()}\n'
               f'OP 221 1 X {wake.hex()}\n'
               'OP 222 1 TICK 0 0 1700000000000\n'
               f'OP 22 1 R220 {login.hex()}\n'
               f'OP 23 1 E {environment.hex()}\n'
               f'OP 24 1 X {wake.hex()}\n'
               f'OP 25 1 INT 1005 {0x99000003} 1\n'
               'OP 26 1 TICK 0 0 1700000000000\n'
               f'OP 27 1 E {environment.hex()}\n'
               f'OP 28 1 RX {request.hex()}\n'
               f'OP 29 1 E {environment.hex()}\n'
               f'OP 30 1 MCU {terminal.hex()}\n'
               'OP 31 1 TICK 5000 5000 1700000005000\n'
               'OP 32 1 TICK 10000 10000 1700000010000\nQUIT\n')
    control = Process(binary, script.encode(), auto_replies=True,
                      auto_vin_fixture=VIN_VALUE,
                      auto_vin19_fixture=auto_vin19, **observed)
    try:
        control_code, control_output = control.run()
        frontier = None
    except RuntimeError as error:
        control_code = None
        control_output = control.output.decode('ascii', errors='replace')
        frontier = str(error).splitlines()[0]
    assert 'RESULT 22 1 LOGIN 1' in control_output
    assert 'DONE 28 1 532 0 0' in control_output, (control_code,frontier,control_output[-5000:],control.sender_callers)
    assert control.path_hits.get('0x739a0', 0) >= 1
    assert control_code == 0 and 'DONE 30 1 532 1 1' in control_output
    assert control.random_requests >= 1 and ' RANDOM_BYTES 16' in control_output
    assert control.path_hits.get('0x73228', 0) >= 2, control_output
    assert 'CALL 30 1 SECONDARY_WRITE ' in control_output
    assert 'CALL 31 1 SECONDARY_WRITE ' in control_output
    assert 'CALL 32 1 SECONDARY_WRITE ' not in control_output
    partial = Process(binary, script.encode(), auto_replies=True,
                      auto_vin_fixture=VIN_VALUE,
                      auto_vin19_fixture=auto_vin19,
                      secondary_write_count_override=0, **observed)
    partial_code, partial_output = partial.run()
    assert partial_code != 0 and '"stage":"secondary_partial_write"' in partial_output
    assert partial.path_hits.get('0x73228', 0) == 0
    assert 'DONE 30 1 532' not in partial_output
    refused = Process(binary, script.encode(), auto_replies=True,
                      auto_vin_fixture=VIN_VALUE,
                      auto_vin19_fixture=auto_vin19,
                      fail_call_kind='SECONDARY_CONNECT', **observed)
    refused_code, refused_output = refused.run()
    assert refused_code != 0 and '"stage":"call_reply_failed"' in refused_output
    assert refused.path_hits.get('0x73228', 0) == 0
    assert 'SECONDARY_WRITE ' not in refused_output
    result['sub5'] = {'completed': control_code == 0,
                      'frontier': frontier,
                      'secondary_attempts': [(a.decode('ascii','replace'),b,c,d.decode('ascii','replace'))
                                             for a,b,c,d in control.secondary_attempts],
                      'original_sender_hits': control.path_hits.get('0x739a0', 0),
                      'original_secondary_constructor_hits': control.path_hits.get('0x75bdc', 0),
                      'original_write_completion_callbacks': control.path_hits.get('0x73228', 0),
                      'secondary_write_on_terminal': control_output.count('CALL 30 1 SECONDARY_WRITE '),
                      'secondary_write_on_expiry': control_output.count('CALL 31 1 SECONDARY_WRITE '),
                      'secondary_write_after_late_tick': control_output.count('CALL 32 1 SECONDARY_WRITE '),
                      'failed_transport_boundary': {'partial_write': 'process abort before original completion',
                                                    'connect_refusal': 'process abort before first write'},
                      'last_lines': control_output.splitlines()[-7:]}
    result['fresh_random_calls'] = control.random_requests
    result['fixture_dependency_inventory'] = {
        'sdk_getters_zero_only': sorted((kind,device,hex(fid)) for kind,device,fid in
                                   getattr(control,'unqualified_sdk_getters',set())),
        'properties_empty_only': sorted(getattr(control,'unqualified_properties',set())),
        'property_writes_ack_only': sorted((kind,bytes.fromhex(args[0]).decode('ascii'),
                                            bytes.fromhex(args[1]).decode('ascii') if len(args)>1 else None)
                                           for kind,args in control.fixture_ack_writes),
    }
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('binary', type=Path)
    parser.add_argument('firmware', type=Path)
    parser.add_argument('--observed-getters', type=Path)
    args = parser.parse_args()
    print(json.dumps(run(args.binary, args.firmware, args.observed_getters), indent=2))


if __name__ == '__main__':
    main()
