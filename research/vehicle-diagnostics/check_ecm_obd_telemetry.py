#!/usr/bin/env python3
"""Replay the retained 21 Oct ECM responses; local files only, no vehicle I/O."""
import hashlib
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / 'research/vehicle-diagnostics/ecm-obd-telemetry-20261021.json'
LENGTHS = {0x00:4,0x20:4,0x40:4,0x60:4,0x80:4,0xA0:4,0xA6:4,
           0x05:1,0x0C:2,0x23:2,0x2F:1,0x33:1,0x34:4,0x3C:2,0x42:2,
           0x44:2,0x46:1,0x67:3,0x6B:5,0x7A:7}

def require(ok, why):
    if not ok:
        raise ValueError(why)

def supported(base, data):
    require(len(data) == 4, 'Bitmap length')
    n = int.from_bytes(data, 'big')
    return [base+i+1 for i in range(32) if n & (1 << (31-i))]

def replay_protocol(text, plan):
    lines = text.splitlines()
    ready = re.fullmatch(r'DENZA_SERVE_([0-9a-f]{24}):READY', lines[0]) if lines else None
    require(ready is not None, 'Missing initial READY')
    prefix = 'DENZA_SERVE_' + ready[1] + ':'
    require(len(lines) == 1 + 4*len(plan), 'Incomplete or extra exchanges')
    result = []
    for i, request in enumerate(plan, 1):
        command, begin, answer, end = lines[1+4*(i-1):1+4*i]
        require(command == f'HOST x {i} 7E0 {request} 4000', 'Request differs from fixed plan')
        require(begin == prefix+'BEGIN' and end == prefix+'END ok', 'Exchange boundary/error')
        m = re.fullmatch(r'a (\d+) (\d+) (\d+) (\d+) ([0-9A-F]+)', answer)
        require(m is not None and int(m[1]) == i, 'Wrong answer sequence')
        require(1 <= int(m[2]) <= 16 and 0 <= int(m[3]) <= 4000, 'Frame/time bound')
        payload = bytes.fromhex(m[5])
        if request == '22F194':
            require(m[5] == '62F19432440011E004000000', 'ECM identity mismatch')
        else:
            require(re.fullmatch(r'01[0-9A-F]{2}', request) is not None, 'Non-read request')
            pid = int(request[2:],16)
            require(pid in LENGTHS and payload[:2] == bytes([0x41,pid]), 'PID echo mismatch')
            require(len(payload) == LENGTHS[pid]+2, 'PID payload length')
        row = {'seq':i,'requestHex':request,'responseHex':m[5],'frames':int(m[2]),
               'latencyMs':int(m[3]),'receivedNanos':int(m[4])}
        if result:
            require(row['receivedNanos'] > result[-1]['receivedNanos'], 'Non-increasing receipt time')
        result.append(row)
    return result

def check(manifest):
    for ref in manifest['sources']:
        p = ROOT/ref['path']
        require(hashlib.sha256(p.read_bytes()).hexdigest() == ref['sha256'], 'Changed source: '+str(p))
    all_rows = []
    offered = set()
    for run in manifest['runs']:
        raw = json.loads((ROOT/run['resultPath']).read_text())
        rows = replay_protocol((ROOT/run['protocolPath']).read_text(), run['fixedPlan'])
        require(raw['responses'] == rows == run['responses'], 'Saved response projection differs')
        require(raw['state']=='complete' and raw['exitCode']==0 and raw['jarRemoved']
                and raw['processAbsent'] and not raw.get('terminationRequired'), 'Incomplete lifecycle')
        for row in rows:
            if row['requestHex']=='22F194':
                continue
            pid = int(row['requestHex'][2:],16)
            require(pid == 0 or pid in offered, 'PID was not advertised before its read')
            if pid % 32 == 0:
                offered.update(supported(pid, bytes.fromhex(row['responseHex'])[2:]))
        all_rows.extend(rows)
    require(len(all_rows)==23 and len(offered)==48, 'Expected bounded coverage changed')
    require(0xA6 in offered and 0xC0 not in offered, 'Capability chain does not terminate at A0')
    require(sorted(offered)==[int(p,16) for p in manifest['supportedPidHex']], 'Capability projection differs')
    for path in manifest['healthPaths']:
        state=json.loads((ROOT/path).read_text())
        require(all(state[k] for k in ['guardPass','firmwareHashMatch','appHashMatch','crashEmpty']), 'Health failure')
        require(state['processes']==manifest['stockProcesses'], 'Stock process changed')
    scalar=json.loads((ROOT/manifest['odometerComparison']['scalarPath']).read_text())
    m=re.fullmatch(r"Result: Parcel\(00000000 ([0-9a-f]{8})\s+'[^']*'\)\s*",scalar['stdout'])
    require(scalar['exitCode']==0 and not scalar['stderr'] and m is not None, 'Scalar parcel invalid')
    require(scalar['device']==1014 and scalar['fidHex']=='4A502010' and scalar['transaction']==5, 'Scalar path differs')
    a6=next(r for r in all_rows if r['requestHex']=='01A6')
    odo=int.from_bytes(bytes.fromhex(a6['responseHex'])[2:],'big')
    require(odo==int(m[1],16)==manifest['odometerComparison']['rawDeciKm'], 'Odometer paths disagree')
    values={int(r['requestHex'][2:],16):bytes.fromhex(r['responseHex'])[2:]
            for r in all_rows if r['requestHex'].startswith('01')}
    arithmetic={'coolantC':values[5][0]-40,'rpm':int.from_bytes(values[12],'big')/4,
                'railPressureKpa':int.from_bytes(values[0x23],'big')*10,
                'catalystTemperatureC':int.from_bytes(values[0x3C],'big')/10-40,
                'ecuVoltageV':int.from_bytes(values[0x42],'big')/1000,
                'ambientC':values[0x46][0]-40,
                'coolantSensorSupportByte':values[0x67][0],
                'coolantSensorsC':[x-40 for x in values[0x67][1:]],'odometerKm':odo/10}
    require(arithmetic==manifest['decodedChecks'], 'Engineering-value projection differs')
    return {'responseCount':len(all_rows),'supportedPidCount':len(offered),
            'sourceHashes':len(manifest['sources']),'healthChecks':len(manifest['healthPaths']),
            'odometerRawMatched':odo,'state':'PASS'}

if __name__=='__main__':
    doc=json.loads((Path(sys.argv[1]) if len(sys.argv)>1 else MANIFEST).read_text())
    print(json.dumps(check(doc),ensure_ascii=False,indent=2))
