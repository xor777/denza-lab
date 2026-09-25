#!/usr/bin/env python3
"""Explicit Android target, synthetic IPC only. Worker cannot access the car/network."""
import argparse
import json
import struct
import subprocess
from pathlib import Path
from native_session import NativeSession
from verify_native_control import NativeControl
from verify_native_roundtrip import KEY, UUID, VIN_VALUE, HELPER, PACKET, WIRE, CORRELATION
from verify_opaque_native import INPUT, need

WORKER_PATH = '/data/local/tmp/denza-control-worker'

def run(serial, source):
    peer = NativeSession(source, VIN_VALUE, KEY, UUID, b'89860700000000000001',
                         b'460010000000001', b'', 1700000000)
    peer.u.mem_write(INPUT, bytes(16))
    peer.call(0x6e3b0, HELPER, 0, PACKET, 220, 1, INPUT, 16, 0)
    peer.call(0x6e858, HELPER, PACKET, WIRE, 16)
    login = peer.frames[-1]
    init = [f'{c} {v.hex()}' for c, v in [
        ('V', VIN_VALUE), ('K', KEY), ('U', UUID), ('C', b'89860700000000000001'),
        ('M', b'460010000000001'), ('S', b''), ('T', struct.pack('<I', 1700000000)),
        ('N', bytes(range(32, 48)))]] + ['START', 'R220 ' + login.hex()]
    env = struct.pack('<4If3I', 2, 1, 0, 1, 79.0, 0, 1, 1)
    init += ['E ' + env.hex()]
    command, opaque = NativeControl(source).request()
    m = NativeControl(source)
    m.receive(command)
    intermediate = bytearray(m.vehicle_writes[-1]); intermediate[4] = 3
    terminal = bytearray(intermediate); terminal[4] = 1
    sender = NativeControl(source)
    def packet(cmd, flag, body):
        sender.u.mem_write(INPUT, body)
        sender.call(0x6e3b0, HELPER, 0, PACKET, cmd, flag, INPUT, len(body), 0)
        sender.call(0x6e858, HELPER, PACKET, WIRE, len(body))
        return sender.frames[-1]
    continuation = packet(532, 4, opaque)
    wake = packet(536, 254, b'\1')
    cases = []
    def execute(name, lines, valid=True):
        result = subprocess.run(['adb', '-s', serial, 'shell', '-T',
            WORKER_PATH], input='\n'.join(lines + ['QUIT']) + '\n',
            capture_output=True, text=True, timeout=15)
        need((result.returncode == 0) == valid, name + ' exit=' + str(result.returncode) + ': ' + result.stdout[-400:] + result.stderr[-120:])
        cases.append({'case': name, 'passed': True, 'exit': result.returncode})
        return result.stdout.splitlines()
    result = execute('full_synthetic_exchange', init + ['RX ' + wake.hex(), 'RX ' + command.hex(),
        'MCU ' + intermediate.hex(), 'RX ' + continuation.hex(), 'MCU ' + terminal.hex()])
    outgoing = [bytes.fromhex(x.split()[-1]) for x in result if x.startswith('AUTO 2852126724 ')]
    need(len(outgoing) == 2 and all(x[6:] == opaque for x in outgoing), 'opaque command altered')
    need(any(x == 'CONTROL 532 2 1' for x in result), 'native terminal result missing')
    need(sum(x.startswith('NET ') for x in result) == 4, 'native cloud reply count')
    empty_wake = packet(536, 254, b'')
    m = NativeControl(source); m.receive(empty_wake)
    wake_reply = bytearray(m.vehicle_writes[0]); wake_reply[4:7] = bytes([1, 1, 1])
    result = execute('empty536_and_native_mcu_reply_before_climate', init + ['RX ' + empty_wake.hex(),
        'MCU ' + wake_reply.hex(), 'RX ' + command.hex(), 'MCU ' + intermediate.hex(),
        'RX ' + continuation.hex(), 'MCU ' + terminal.hex()])
    need(sum(x.startswith('AUTO ') for x in result) == 4, 'empty536 native forwarding count')
    need(sum(x.startswith('NET ') for x in result) == 5 and 'CONTROL 532 3 1' in result,
         'empty536 MCU reply or following climate terminal missing')
    # A completed command must not turn the next command or wake into success.
    # This deliberately reuses one worker and native context (the earlier live
    # test restarted the worker between commands and missed the sticky level).
    next_opaque = bytes([CORRELATION[0] ^ 8]) + opaque[1:]
    next_command = packet(532, 254, next_opaque)
    next_peer = NativeControl(source)
    next_peer.receive(next_command)
    next_intermediate = bytearray(next_peer.vehicle_writes[-1]); next_intermediate[4] = 3
    next_terminal = bytearray(next_intermediate); next_terminal[4] = 1
    result = execute('terminal_is_per_exchange_not_sticky', init + [
        'RX ' + command.hex(), 'MCU ' + terminal.hex(),
        'RX ' + wake.hex(), 'RX ' + next_command.hex(), 'MCU ' + next_intermediate.hex()])
    completions = [line for line in result if line.startswith('CONTROL ')]
    need(len(completions) == 5 and [int(line.split()[-1]) for line in completions] ==
         [0, 1, 0, 0, 0], 'previous success leaked into another exchange')
    result = execute('two_distinct_commands_in_one_worker', init + [
        'RX ' + command.hex(), 'MCU ' + terminal.hex(),
        'RX ' + next_command.hex(), 'MCU ' + next_terminal.hex()])
    completions = [int(line.split()[-1]) for line in result if line.startswith('CONTROL ')]
    outgoing = [bytes.fromhex(x.split()[-1]) for x in result if x.startswith('AUTO 2852126724 ')]
    need(completions == [0, 1, 0, 1] and [x[6:] for x in outgoing] == [opaque, next_opaque],
         'second command was deduplicated or inherited the first result')
    result = execute('interleaved_wake_does_not_relabel_mcu_terminal', init + [
        'RX ' + command.hex(), 'RX ' + wake.hex(), 'MCU ' + terminal.hex()])
    completions = [line.split() for line in result if line.startswith('CONTROL ')]
    need([int(line[1]) for line in completions] == [532, 536, 532] and
         completions[-1][-1] == '1', 'MCU terminal inherited last cloud message kind')
    # No actuator writes leave this test: AUTO is only captured stdout.
    execute('missing_environment', init[:-1] + ['RX ' + command.hex()], False)
    bad_env = bytearray(env); bad_env[0:4] = bytes(4)
    execute('asleep_environment_stops_test', init + ['E ' + bad_env.hex()], False)
    bad_env = bytearray(env); bad_env[20:24] = struct.pack('<I', 1)
    execute('repair_environment_stops_test', init + ['E ' + bad_env.hex()], False)
    corrupt = command[:-8] + bytes([command[-8] ^ 64]) + command[-7:]
    execute('corrupted_cloud_packet', init + ['RX ' + corrupt.hex()], False)
    wrong = bytearray(intermediate); wrong[6] ^= 1
    execute('wrong_mcu_correlation', init + ['RX ' + command.hex(), 'MCU ' + wrong.hex()], False)
    other = packet(532, 254, CORRELATION + bytes([9, 0, 0, 0]))
    execute('other_actuator_outside_test', init + ['RX ' + other.hex()], False)
    wrong_continuation = packet(532, 4, bytes([CORRELATION[0] ^ 1]) + opaque[1:])
    lines = execute('wrong_cloud_continuation_not_forwarded', init + ['RX ' + command.hex(),
        'RX ' + wrong_continuation.hex()])
    need(sum(x.startswith('AUTO ') for x in lines) == 1, 'unrelated continuation forwarded')
    return {'passed': True, 'cases': cases, 'serial': serial,
            'scope': 'Synthetic packets and getters. AUTO output captured only; no Binder, cloud or actuator access.'}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('serial'); p.add_argument('firmware', type=Path)
    args = p.parse_args()
    print(json.dumps(run(args.serial, args.firmware), indent=2))
