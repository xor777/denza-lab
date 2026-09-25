#!/usr/bin/env python3
"""Offline regression for original post-login configuration continuation.

Uses synthetic SDK and server fixtures only. No vehicle, sockets or signatures.
The original encoder, dispatcher, parser and retry callback execute in ARM64.
"""
import argparse
import hashlib
import json
import struct
from pathlib import Path

from native_session import NativeSession
from verify_native_roundtrip import HELPER, PACKET, WIRE
from verify_opaque_native import INPUT
from verify_persistent_engine import Process


def run(binary: Path, firmware: Path, fixture_path: Path):
    fixture = json.loads(fixture_path.read_text())
    operations = fixture['script']
    identity = {op.split(' ', 1)[0]: bytes.fromhex(op.split(' ', 1)[1])
                for op in operations if op.split(' ', 1)[0] in ('V','K','U','C','M')}
    assert fixture['properties']['persist.sys.record_610_upload'] == '1'
    prefix = ''.join(f'OP {i} 1 {op}\n' for i, op in enumerate(operations, 1))
    next_id = len(operations) + 1
    environment = next(op for op in operations if op.startswith('E '))
    elapsed, uptime, wall = map(int, next(op for op in reversed(operations)
                                         if op.startswith('TICK ')).split()[1:])
    getters = {(int(k.split('/')[0]), int(k.split('/')[1],16)): v
               for k,v in fixture['integers'].items()}

    class FixturePeer(NativeSession):
        def hook(self, engine, at, size, data):
            if at == 0x6e3b0 and self.reg(3) in (201,531):
                return  # Execute the original encoder for this fixture.
            return super().hook(engine, at, size, data)

    peer = FixturePeer(firmware, identity['V'], identity['K'], identity['U'],
                       identity['C'], identity['M'], b'', wall // 1000)

    def frame(body, flag=1, command=531):
        peer.u.mem_write(INPUT, body)
        peer.call(0x6e3b0, HELPER, 0, PACKET, command, flag, INPUT, len(body), 0)
        peer.call(0x6e858, HELPER, PACKET, WIRE, len(body))
        return peer.frames[-1].hex()

    def execute(tail, status=-1):
        script = prefix + ''.join(f'OP {i} 1 {op}\n'
                                  for i,op in enumerate(tail,next_id)) + 'QUIT\n'
        process = Process(binary,script.encode(),auto_replies=True,
                          auto_vin_fixture=identity['V'],
                          auto_vin19_fixture=identity['V']+b'00',
                          observed_getters=getters,
                          observed_properties={**fixture['properties'],'sys.vin_valid_record_time':'0'},
                          property_status_fixture=status)
        try:
            code, output = process.run()
        except RuntimeError as error:
            raise AssertionError(str(error)+'\n'+process.output.decode()[-1800:]) from error
        assert code == 0, output[-1800:]
        assert process.path_hits.get('0x38eb4') == 1
        assert process.path_hits.get('0x39178') == 1
        return process, output

    def tick(offset):
        return f'TICK {elapsed+offset} {uptime+offset} {wall+offset}'

    summary = {'qualified_for_live': False,
               'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
               'fixture_sha256': hashlib.sha256(fixture_path.read_bytes()).hexdigest()}
    for status in (-1,0):
        timeout, output = execute([tick(ms) for ms in (4999,5000,10000,15000,20000,25000)],status)
        assert timeout.body_commands.count(531) == 4, timeout.body_commands
        assert timeout.path_hits.get('0x39420') == 4
        config_writes = [(kind,args) for kind,args in timeout.fixture_ack_writes
                         if kind == 'PROPERTY_SET_STATUS']
        assert config_writes == [('PROPERTY_SET_STATUS',
                                  [b'persist.sys.edge.enable.sre'.hex(), b'0'.hex()])]
        summary[f'timeout_property_status_{status}'] = {'requests':4,'callbacks':4,
                                                      'timer_stopped':True}

    interleaved,output = execute(['KEEPALIVE 1',tick(5000),environment,
                                  'RX '+frame(b'\0\0',command=201),tick(10000)])
    assert interleaved.body_commands.count(531) == 3
    assert interleaved.path_hits.get('0x38a24') == 1
    assert interleaved.path_hits.get('0x3896c') == 1
    assert interleaved.path_hits.get('0x39420') == 2
    summary['concurrent_heartbeat']={'config_callbacks':2,'heartbeat_callbacks':1,
                                     'acknowledgement_handled':True}

    payload = bytes(16) + b'\x02\x00\x00'
    cases = {'enabled': (payload+b'{"configs":[{"k":"sre","v":1}]}\0',1, b'1'),
             'disabled': (payload+b'{"configs":[{"k":"sre","v":0}]}\0',1,b'0'),
             'decimal': (payload+b'{"configs":[{"k":"sre","v":1e0}]}\0',1,b'1'),
             'missing': (payload+b'{"configs":[]}\0',1,b'0'),
             'malformed': (payload+b'{no-json}\0',1,b'0'),
             'refused': (payload+b'\0',2,b'0'),
             'short': (b'\0',1,None)}
    for length in (430,431,432):
        cases[f'long_string_{length}']=(payload+b'{"configs":[{"k":"ignored","v":"'+
            b'x'*length+b'"},{"k":"sre","v":1}]}\0',1,b'1')
    for name,(body,flag,expected) in cases.items():
        process,output = execute([environment,'RX '+frame(body,flag),tick(5000),tick(30000)])
        assert process.path_hits.get('0x3955c') == 1, output[-1800:]
        assert process.body_commands.count(531) == 1, process.body_commands
        assert not process.path_hits.get('0x39420'), output[-1800:]
        writes = [args[1] for kind,args in process.fixture_ack_writes if kind=='PROPERTY_SET_STATUS']
        assert writes == ([] if expected is None else [expected.hex()]), (name,writes)
        summary[name] = {'response_handled':True,'timer_stopped':True,
                         'native_property_value':expected.decode() if expected else None}
    return summary


if __name__ == '__main__':
    parser=argparse.ArgumentParser()
    parser.add_argument('binary',type=Path)
    parser.add_argument('firmware',type=Path)
    parser.add_argument('fixture',type=Path)
    parser.add_argument('--result',type=Path)
    args=parser.parse_args()
    result=json.dumps(run(args.binary,args.firmware,args.fixture),indent=2)+'\n'
    if args.result:args.result.write_text(result)
    print(result)
