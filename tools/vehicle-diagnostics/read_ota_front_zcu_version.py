#!/usr/bin/env python3
"""Two fixed ZCU reads using the retained isolated bus; no OTA/cloud lifecycle.

Default is an offline plan. Run uses the existing authorized ADB transport,
current pre/postflight guards and a nonce-owned jar removed by the retained
runner. Neither ECU configuration nor the product whitelist is changed.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
RETAINED = ROOT / 'captures/diagnostics-followup-20261021/front-zcu'
PLAN = [['722', '22F194'], ['722', '22F195']]
PINS = {
    'run.py': 'ef68be380aaabf5725167a4587f37e3c6db9993a6cf15027d9e399c990bbcfe7',
    'build/build.json': '526f77af6799492681d7057dd199748d4aad1680b80c1289d344361768c2730a',
    'build/front-zcu-config-probe.jar': 'fdd027bffc025f37d92f9f78a7d7a2a6db47feda6a81c87a443b62b90b40904f',
}
HEALTH = ROOT / 'tools/vehicle-diagnostics/run_supported_dtc_probe.py'
HEALTH_SHA = 'c086250984f3fc124ef6c62ad77dd972acd608215ffb35437741948bbe11b741'
STOCK = ('autoservice', 'gbacqservice', 'gbdataservice', 'com.byd.avc',
         'com.byd.otaupdate', 'com.byd.otaupdate2', 'com.byd.productlineota', 'com.byd.bydlogtool')


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def answer(line, seq, target, request):
    if not 1 <= seq <= 2 or [target, request] != PLAN[seq - 1]:
        raise ValueError('Outside two-read plan/order')
    match = re.fullmatch(r'a (\d+) (\d+) (\d+) (\d+) ([0-9A-F]+)', line)
    if not match or int(match[1]) != seq or not 1 <= int(match[2]) <= 16:
        raise ValueError('Incomplete response metadata')
    data = bytes.fromhex(match[5])
    if data[:3] != bytes.fromhex('62' + request[2:]):
        raise ValueError('Wrong response SID/echoDID')
    if not 0 <= int(match[3]) < 4000 or int(match[4]) <= 0:
        raise ValueError('Invalid response timing')
    if seq == 1 and match[5] != '62F1944B310011E001540100':
        raise ValueError('Front ZCU identity differs from retained cache')
    # Preserve the entire declared UDS response. The stock version parser
    # consumes its first two data bytes; extra bytes are not silently discarded.
    if seq == 2 and not 5 <= len(data) <= 4095:
        raise ValueError('Version response too short or exceeds assembler cap')
    result = {'seq': seq, 'targetHex': target, 'requestHex': request,
              'responseHex': match[5], 'frames': int(match[2]),
              'latencyMs': int(match[3]), 'receivedNanos': int(match[4])}
    if seq == 2:
        value = int.from_bytes(data[3:5], 'big')
        version = f'{value // 10000}.{value % 10000 // 100:02d}.{value % 100:02d}'
        result.update(stockParsedVersion=version, cachedVersion='4.00.36',
                      cacheMatch=version == '4.00.36',
                      additionalDataHex=data[5:].hex().upper())
    return result


def self_test():
    answer('a 1 1 9 999 62F1944B310011E001540100', 1, '722', '22F194')
    short = answer('a 2 1 9 999 62F1959C64', 2, '722', '22F195')
    extended = answer('a 2 1 9 999 62F1959C6401020304', 2, '722', '22F195')
    assert short['cacheMatch'] and extended['additionalDataHex'] == '01020304'
    for line, seq, target, request in [
        ('a 1 1 9 999 62F194000000000000000000', 1, '722', '22F194'),
        ('a 2 1 9 999 7F2231', 2, '722', '22F195'),
        ('a 2 1 9 999 62F1959C', 2, '722', '22F195'),
        ('a 2 1 9 999 62F1949C64', 2, '722', '22F195'),
        ('a 2 1 4000 999 62F1959C64', 2, '722', '22F195'),
        ('a 2 1 9 999 62F1959C64', 2, '723', '22F195'),
        ('a 2 1 9 999 62F1959C64', 2, '722', '22FF95'),
    ]:
        try:
            answer(line, seq, target, request)
        except ValueError:
            continue
        raise AssertionError('Accepted invalid or out-of-plan reply')


def selected_pids(directory):
    pairs = [line.split() for line in (directory / 'processes.txt').read_text().splitlines()]
    found = {name: pid for row in pairs if len(row) == 2 for pid, name in [row] if name in STOCK}
    if set(found) != set(STOCK):
        raise ValueError('Incomplete stock process inventory')
    if any('denza_front_zcu_' in row[-1] for row in pairs if row):
        raise ValueError('Existing ZCU reader process')
    return found


def run(output):
    for relative, expected in PINS.items():
        if sha(RETAINED / relative) != expected:
            raise ValueError('Retained artifact changed: ' + relative)
    build = json.loads((RETAINED / 'build/build.json').read_text())
    for entry in build['sources']:
        if sha(ROOT / entry['path']) != entry['sha256']:
            raise ValueError('Retained source changed')
    if sha(HEALTH) != HEALTH_SHA:
        raise ValueError('Health implementation changed; review before use')
    health = load('ota_zcu_health', HEALTH)
    retained = load('ota_zcu_runner', RETAINED / 'run.py')
    output.mkdir(parents=True, exist_ok=False, mode=0o700)
    (output / 'runner-source.py').write_bytes(Path(__file__).read_bytes())
    (output / 'plan.json').write_text(json.dumps({'plan': PLAN, 'pins': PINS}, indent=2) + '\n')
    state = {'state': 'preflight', 'plan': PLAN, 'cloudRequests': 0, 'apkInstalled': False}
    try:
        before = health.health('127.0.0.1:5555', output / 'preflight')
        if not health.acceptable(before):
            raise ValueError('Preflight refused')
        pids = selected_pids(output / 'preflight')
        (output / 'app-baseline.json').write_text(json.dumps({
            'installedApk': before['installedApk'], 'newAppSha256': before['appSha256']}) + '\n')
        retained.BASE = output
        retained.ARTIFACTS = RETAINED / 'build'
        retained.PLAN = PLAN
        def validate_plan():
            if retained.PLAN != [['722', '22F194'], ['722', '22F195']]:
                raise ValueError('Two-read plan changed')
        retained.validate_plan = validate_plan
        retained.answer = answer
        try:
            retained.run()
        finally:
            after = health.health('127.0.0.1:5555', output / 'postflight')
            state['healthStable'] = (health.acceptable(after)
                and before['appSha256'] == after['appSha256']
                and pids == selected_pids(output / 'postflight'))
        result = json.loads((output / 'live-01/result.json').read_text())
        if not state['healthStable'] or len(result['responses']) != 2:
            raise ValueError('Incomplete lifecycle or responses')
        state.update(state='complete', version=result['responses'][1])
    except (Exception, SystemExit) as error:
        state.update(state='stopped', error=f'{type(error).__name__}: {error}')
    finally:
        (output / 'session.json').write_text(json.dumps(state, indent=2) + '\n')
    print(json.dumps(state, indent=2))
    if state['state'] != 'complete':
        raise SystemExit(2)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'self-test', 'run'], default='plan', nargs='?')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    self_test()
    if args.action == 'run':
        if not args.output:
            parser.error('run requires a fresh --output directory')
        run(args.output.resolve())
    else:
        print(json.dumps({'plan': PLAN, 'offlineChecksPassed': True, 'vehicleOperations': 0}))
