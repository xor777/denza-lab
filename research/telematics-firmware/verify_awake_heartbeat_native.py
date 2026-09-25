#!/usr/bin/env python3
"""Original keepalive(1) exploration under explicit offline fixtures only."""
import argparse
import json
import struct
from pathlib import Path

from native_session import NativeSession
from verify_native_roundtrip import VIN_VALUE, KEY, UUID, HELPER, PACKET, WIRE
from verify_opaque_native import INPUT
from verify_persistent_engine import Process


def probe(binary: Path, firmware: Path, observed_path: Path):
    snapshot = json.loads(observed_path.read_text())
    getters = {(int(k.split('/')[0]), int(k.split('/')[1], 16)): v['value']
               for k, v in snapshot['getters'].items()}
    properties = {k: v['value'] for k, v in snapshot['properties'].items()}
    peer = NativeSession(firmware, VIN_VALUE, KEY, UUID,
                         b'89010000000000000001', b'001010123456789', b'', 1700000000)

    def fixture(command, body):
        peer.u.mem_write(INPUT, body)
        peer.call(0x6e3b0, HELPER, 0, PACKET, command, 1, INPUT, len(body), 0)
        peer.call(0x6e858, HELPER, PACKET, WIRE, len(body))
        return peer.frames[-1]

    host = b'test.denzacloud.com'
    registration = fixture(211, b'\x01')
    discovery = fixture(200, (6003).to_bytes(2, 'big') + bytes(20) +
                        bytes([len(host)]) + host)
    login = fixture(220, bytes(16))
    identity = [('V', VIN_VALUE), ('K', KEY), ('U', UUID),
                ('C', b'89010000000000000001'), ('M', b'001010123456789'),
                ('S', b''), ('A', b'double_apn'), ('T', struct.pack('<I', 1700000000)),
                ('N', bytes(range(32, 48)))]
    script = ''.join(f'OP {i} 1 {key} {value.hex()}\n'
                     for i, (key, value) in enumerate(identity, 1))
    environment = struct.pack('<9I', 2, 1, 0, 1,
                              struct.unpack('<I', struct.pack('<f', 79.0))[0],
                              0, 1, 1, 255)
    wake = struct.pack('<4I', 0, 0, 0, 7)
    script += ('OP 10 1 START\nOP 11 1 TICK 0 0 1700000000000\n'
               'OP 12 1 NETSTATE 4\n' +
               f'OP 13 1 R211 {registration.hex()}\n' +
               f'OP 14 1 R200 {discovery.hex()}\n' +
               f'OP 15 1 E {environment.hex()}\n' +
               f'OP 16 1 X {wake.hex()}\n' +
               'OP 17 1 TICK 0 0 1700000000000\n' +
               f'OP 18 1 R220 {login.hex()}\n' +
               'OP 19 1 KEEPALIVE 1\n'
               'OP 20 1 TICK 5000 5000 1700000005000\n'
               'OP 21 1 TICK 10000 10000 1700000010000\nQUIT\n')
    process = Process(binary, script.encode(), auto_replies=True,
                      auto_vin_fixture=VIN_VALUE,
                      auto_vin19_fixture=VIN_VALUE+b'00',
                      observed_getters=getters, observed_properties=properties)
    try:
        code, output = process.run()
        frontier = None
    except RuntimeError as error:
        code = None
        output = process.output.decode('ascii', errors='replace')
        frontier = str(error).splitlines()[0]
    result = {'qualified_for_live': False, 'exit_code': code,
              'frontier': frontier,
              'original_timer_constructor': process.path_hits.get('0x383a8', 0),
              'original_keepalive_handler': process.path_hits.get('0x539d8', 0),
              'original_send_calls': process.path_hits.get('0x739a0', 0),
              'original_retry_callback': process.path_hits.get('0x38a24', 0),
              'native_201_templates': process.body_commands.count(201),
              'keepalive_effects': [line for line in output.splitlines()
                                    if line.startswith(('NET 19 ', 'CALL 19 ', 'ARM 19 ',
                                                        'CANCEL 19 ', 'DONE 19 ',
                                                        'NET 20 ', 'ARM 20 ', 'CANCEL 20 ',
                                                        'FIRED 20 ', 'DONE 20 ',
                                                        'NET 21 ', 'ARM 21 ', 'CANCEL 21 ',
                                                        'FIRED 21 ', 'DONE 21 '))],
              'output_tail': output.splitlines()[-20:]}
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('binary', type=Path)
    parser.add_argument('firmware', type=Path)
    parser.add_argument('--observed-getters', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(probe(args.binary, args.firmware, args.observed_getters), indent=2))
