#!/usr/bin/env python3
"""Verify the finite brake/VCU pass from retained files, without vehicle I/O."""
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / 'research/vehicle-diagnostics/brake-vcu-obd-completion-20261021.json'


def check(ok, why):
    if not ok:
        raise ValueError(why)


def replay(text, plan):
    lines = text.splitlines()
    m = re.fullmatch(r'(DENZA_SERVE_[0-9a-f]{24}:)READY', lines[0])
    check(m is not None and len(lines) == 1 + 4 * len(plan), 'lifecycle/length')
    out = []
    for i, (target, request) in enumerate(plan, 1):
        host, begin, answer, end = lines[1 + (i - 1) * 4:1 + i * 4]
        check(host == f'HOST x {i} {target} {request} 4000', 'request order/target')
        check(begin == m[1] + 'BEGIN' and end == m[1] + 'END ok', 'boundary/nonce')
        a = re.fullmatch(r'a (\d+) (\d+) (\d+) (\d+) ([0-9A-F]+)', answer)
        check(a is not None and int(a[1]) == i and int(a[2]) == 1, 'answer metadata')
        check(0 < int(a[3]) < 4000, 'latency')
        raw = bytes.fromhex(a[5])
        check(raw[0] == int(request[:2], 16) + 0x40, 'response SID')
        if request.startswith('22'):
            check(raw[1:3].hex().upper() == request[2:], 'echo DID')
        else:
            check(raw[1] == int(request[2:], 16), 'echo PID')
        out.append(dict(seq=i, targetHex=target, requestHex=request, responseHex=a[5],
                        frames=int(a[2]), latencyMs=int(a[3]), receivedNanos=int(a[4])))
    return out


def main():
    d = json.loads(REPORT.read_text())
    for s in d['sources']:
        check(hashlib.sha256((ROOT / s['path']).read_bytes()).hexdigest() == s['sha256'], s['path'])
    base = ROOT / d['captureBase']
    plan = json.loads((base / 'plan.json').read_text())['plan']
    text = (base / 'live-01/protocol.txt').read_text()
    rows = replay(text, plan)
    check(rows == d['wireResponses'] and len(rows) == 23, 'wire projection')
    by = {(r['targetHex'], r['requestHex']): r for r in rows}
    check(by['7E0', '010C']['responseHex'] == '410C0000', 'engine stopped')
    check(by['7E0', '22F194']['responseHex'] == '62F19432440011E004000000', 'ECM identity')
    check(by['7E2', '22F194']['responseHex'] == '62F19432440011E07E007841', 'VCU identity')
    check(by['7E5', '0904']['responseHex'] == '49040134303030383235303432323533313533', 'BSCM CALID')
    check(by['7E5', '090A']['responseHex'] == '490A014253434D2D4272616B6553797374656D00000000', 'BSCM name')
    old = json.loads((ROOT / 'research/vehicle-diagnostics/obd-20261020.json').read_text())
    for target in ['7E2', '7E5']:
        supported = []
        for pid in [0, 32, 64]:
            raw = by[target, f'01{pid:02X}']['responseHex']
            check(len(raw) == 12, 'bitmap shape')
            bits = int(raw[4:], 16)
            actual = [f'{pid + i + 1:02X}' for i in range(32) if bits & (1 << (31 - i))]
            earlier = next(r['value'] for r in old['run1'] + old['run2_detail']
                           if r.get('request') == '0' + target and r.get('pid') == f'{pid:02X}')
            check(actual == earlier, 'bitmap changed')
            supported += [p for p in actual if int(p, 16) % 32]
        answered = {r['pid'] for r in old['run1'] + old['run2_detail']
                    if r.get('request') == '0' + target and r.get('result') == 'ok' and 'pid' in r}
        answered |= {r['requestHex'][2:] for r in rows if r['targetHex'] == target and r['requestHex'].startswith('01')}
        check(set(supported) <= answered, 'incomplete coverage')
        check(d['advertisedNonBitmapCoverage'][target]['pids'] == supported, 'coverage projection')
        check(d['advertisedNonBitmapCoverage'][target]['count'] == len(supported), 'coverage count')
    expected_new = {('7E5', q) for q in ['0130', '0142', '0149']}
    expected_new |= {('7E2', q) for q in ['0121', '0141', '0142', '0149', '014A']}
    check(len(d['newParameters']) == 8 and
          {(p['target'], p['request']) for p in d['newParameters']} == expected_new, 'new parameter coverage')
    for p in d['newParameters']:
        raw = by[p['target'], p['request']]['responseHex']
        check(raw == p['response'], 'new parameter wire')
        pid = int(p['request'][2:], 16)
        data = bytes.fromhex(raw[4:])
        size = {0x21: 2, 0x30: 1, 0x41: 4, 0x42: 2, 0x49: 1, 0x4A: 1}[pid]
        check(len(data) == size, 'new parameter shape')
        value = (raw[4:] if pid == 0x41 else int.from_bytes(data, 'big') / 1000 if pid == 0x42
                 else data[0] * 100 / 255 if pid in [0x49, 0x4A] else int.from_bytes(data, 'big'))
        check(value == p['value'], 'new parameter arithmetic')
    coolant = d['coolantComparison']
    check([item['target'] for item in coolant['values']] == ['7E0', '7E2', '7E5'], 'coolant target coverage')
    for item in coolant['values']:
        r = by[item['target'], '0105']
        check(r['responseHex'] == item['response'] and len(r['responseHex']) == 6, 'coolant wire')
        check(int(item['response'][4:], 16) - 40 == item['nominalC'], 'coolant arithmetic')
        check(r['receivedNanos'] == item['receivedNanos'], 'coolant timestamp')
    measured_window = by['7E5', '0105']['receivedNanos'] - by['7E0', '0105']['receivedNanos']
    check(coolant['windowNanos'] == measured_window == 1032273846, 'comparison window')
    status = [json.loads((base / p / 'status.json').read_text()) for p in ['preflight', 'postflight']]
    for s in status:
        check(all(s[k] for k in ['guardPass', 'firmwareHashMatch', 'appHashMatch', 'crashEmpty']), 'health gate')
    check(status[0]['processes'] == status[1]['processes'], 'stock PIDs')
    for p in ['preflight', 'postflight']:
        check((base / p / 'app-hash.txt').read_text().split()[0] == d['health']['installedApkSha256'], 'APK changed')
    result = json.loads((base / 'live-01/result.json').read_text())
    check(result['state'] == 'complete' and result['exitCode'] == 0 and result['jarRemoved'] and result['processAbsent'], 'cleanup')
    damaged = [text.replace('HOST x 1 7E0', 'HOST x 1 7E5'), text.replace(':END ok', ':END err', 1),
               text.replace('62F194', '62F195', 1), text.replace('a 1 1 ', 'a 2 1 ', 1),
               text.replace('410532', '420532', 1), '\n'.join(text.splitlines()[:-1]) + '\n']
    for t in damaged:
        try:
            replay(t, plan)
        except ValueError:
            pass
        else:
            raise ValueError('accepted damaged transcript')
    print(json.dumps({'status': 'pass', 'sources': len(d['sources']), 'wireReplies': len(rows),
                      'newParameters': len(d['newParameters']), 'BSCMLeafPids': 8, 'VCULeafPids': 9,
                      'damagedTranscriptsRejected': len(damaged), 'vehicleOperations': 0}))


if __name__ == '__main__':
    main()
