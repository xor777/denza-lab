#!/usr/bin/env python3
"""Offline original NETSTATE4 real-VIN branch at car-aged clocks; no car I/O."""
import argparse
import json
import struct
from pathlib import Path

from verify_native_roundtrip import KEY, UUID
from verify_persistent_engine import Process


# A synthetic, structurally real VIN. It has no connection to a vehicle.
VIN = b'LEST123456789ABCD'
ELAPSED_MS = 66_670_000
UPTIME_MS = 25_700_000
WALL_MS = 1_790_334_000_000


def run(binary: Path):
    assert len(VIN) == 17 and ELAPSED_MS > UPTIME_MS
    identity = [('V', VIN), ('K', KEY), ('U', UUID),
                ('C', b'89010000000000000001'), ('M', b'001010123456789'),
                ('S', b''), ('A', b'double_apn'),
                ('T', struct.pack('<I', WALL_MS // 1000)),
                ('N', bytes(range(32, 48)))]
    prefix = ''.join(f'OP {i} 1 {key} {value.hex()}\n'
                     for i, (key, value) in enumerate(identity, 1))
    script = (prefix + 'OP 10 1 START\n'
              f'OP 11 1 TICK {ELAPSED_MS} {UPTIME_MS} {WALL_MS}\n'
              'OP 12 1 NETSTATE 4\nQUIT\n')
    results = {}
    for initial in ('0', '123'):
        process = Process(binary, script.encode(), auto_replies=True,
                          auto_vin_fixture=VIN, auto_vin19_fixture=VIN + b'00',
                          observed_properties={'sys.vin_valid_record_time': initial})
        code, output = process.run()
        assert code == 0 and 'DONE 12 1 NETSTATE' in output, output[-1000:]
        assert 'sys.vin_valid_record_time' in process.property_get_requests
        writes = [value.decode('ascii') for key, value in process.property_set_calls
                  if key == b'sys.vin_valid_record_time']
        assert writes == ([str(ELAPSED_MS // 1000)] if initial == '0' else []), writes
        assert not process.fixture_network_sends
        results[initial] = {'netstate_done': True, 'vin_record_time_writes': writes,
                            'timer_clock_elapsed_ms': ELAPSED_MS,
                            'native_monotonic_uptime_ms': UPTIME_MS}
    return {'synthetic_vin': VIN.decode('ascii'), 'wall_ms': WALL_MS,
            'record_time_cases': results}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('binary', type=Path)
    print(json.dumps(run(parser.parse_args().binary), indent=2))
