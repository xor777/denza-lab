#!/usr/bin/env python3
"""Replay original GPS consumption using synthetic data; no SDK or network."""
import argparse
import hashlib
import json
from pathlib import Path

from verify_persistent_engine import Process


def run(binary, fixture_path):
    fixture = json.loads(fixture_path.read_text())
    operations = fixture['script']
    identity = {op.split(' ', 1)[0]: bytes.fromhex(op.split(' ', 1)[1])
                for op in operations if op.split(' ', 1)[0] in ('V', 'K', 'U', 'C', 'M')}
    script = (''.join(f'OP {i} 1 {op}\n' for i, op in enumerate(operations, 1)) + 'QUIT\n').encode()
    getters = {(int(k.split('/')[0]), int(k.split('/')[1], 16)): v
               for k, v in fixture['integers'].items()}
    cases = {
        'empty': ('', False),
        'partial': ('0', False),
        'nonnumeric': ('abc', False),
        'positive': ('11.25_22.5_1_1_11_111.5_.1_111.11_1_1', True),
        'negative': ('-11.25_-22.5_1_1_11_111.5_.1_111.11_1_1', True),
        'exponent': ('1.125e1_2.25e1_1_1_11_111.5_.1_111.11_1_1', True),
    }
    results = {}
    for name, (gps, conversions) in cases.items():
        process = Process(binary, script, auto_replies=True,
                          auto_vin_fixture=identity['V'], auto_vin19_fixture=identity['V'] + b'00',
                          observed_getters=getters,
                          observed_properties={**fixture['properties'], 'persist.sys.gpsinfo': gps,
                                               'persist.gnss.pga': '', 'sys.vin_valid_record_time': '0'})
        code, output = process.run()
        assert code == 0 and 'LOGIN 1\n' in output, (name, output[-1000:])
        assert not getattr(process, 'unqualified_properties', set())
        converted = process.path_hits.get('0x725b4', 0)
        if conversions:
            assert converted == 2, (name, converted)
            assert 'persist.gnss.pga' in process.property_get_requests
        results[name] = {'exit': code, 'original_coordinate_conversions': converted}
    return {'binary_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(),
            'fixture_sha256': hashlib.sha256(fixture_path.read_bytes()).hexdigest(),
            'live_qualified': False, 'cases': results}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('binary', type=Path)
    parser.add_argument('fixture', type=Path)
    parser.add_argument('--result', type=Path)
    args = parser.parse_args()
    result = json.dumps(run(args.binary, args.fixture), indent=2) + '\n'
    if args.result:
        args.result.write_text(result)
    print(result)
