#!/usr/bin/env python3
"""Verify the retained Z9GT chassis stream catalog offline.

Run: python3 research/vehicle-diagnostics/check_stream_chassis.py
Requires the owner's local firmware/capture corpus; no vehicle or network calls.
Checks native/property namespaces, all input hashes, original HAL/MCU bytes,
all retained scalar rows, all-ones arithmetic and exact rear-angle raw replay.
"""
from collections import Counter
from pathlib import Path
import hashlib
import json
import math
import struct
import sys

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / 'research/vehicle-diagnostics/stream-chassis-20261021.json'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def f32(value):
    return struct.unpack('<f', struct.pack('<f', value))[0]


def signed(value):
    return value - 2**32 if value >= 2**31 else value


def check(path=CATALOG):
    d = json.loads(path.read_text())
    files = {}
    for source in d['sources']:
        raw = (ROOT / source['path']).read_bytes()
        require(len(raw) == source['bytes'], 'source length: ' + source['path'])
        require(hashlib.sha256(raw).hexdigest() == source['sha256'],
                'source hash: ' + source['path'])
        files[source['path']] = raw
    hal = files['captures/firmware/ivi-34.1.33.2605218.1/system/system/lib64/hw/auto.default.so']
    mcu = files['captures/firmware/ivi-34.1.33.2605218.1/mcu/app.bin']
    dc = json.loads(files['research/vehicle-diagnostics/dicar-readable-properties-20261021.json'])
    sweep_name = 'captures/diagnostics-sweep-20261020/fids.tsv'
    lines = files[sweep_name].decode().splitlines()
    native_by_name = {}
    for line in lines:
        cells = line.split('\t')
        if cells[0] == 'CAT':
            for symbol in cells[3].split('|'):
                native_by_name[(int(cells[1]), symbol.split('.')[-1])] = cells[2]
    for va, cv in d['nativeConverters'].items():
        start = int(cv['fileOffset'], 16)
        expected = bytes.fromhex(cv['bytesHex'])
        require(hal[start:start+len(expected)] == expected, 'HAL converter bytes: '+va)

    descriptor_count = scalar_count = 0
    for r in d['records']:
        original = dc['rows'][r['catalogRowIndex']]
        require((r['propertyKey'], r['declaredAccess'], r['nativeDevice'], r['name'], r['declaredJavaType']) ==
                (original[0], original[1], original[4], original[5], original[7]),
                'DiCar row/namespace: ' + r['name'])
        native = original[8] or native_by_name.get((r['nativeDevice'], r['name']))
        require(r['nativeFid'] == native, 'native resolution: '+r['name'])
        if native:
            require(r['nativeFidSigned'] == signed(int(native, 16)), 'signed native FID')
        require(bool(r['labelRu']) and bool(r['readinessRu']) and bool(r['freshness']),
                'missing human-readable contract')
        require(r['qualification']['physicalEcuBound'] is False and
                r['physicalEcuBinding'] is None and
                r['qualification']['sdkContinuousStreamObserved'] is False,
                'unsupported physical ECU/typed stream claim')
        for o in r['historicalScalarRows']:
            cells = lines[o['line']-1].split('\t')
            require((cells[0],int(cells[1]),int(cells[2]),int(cells[3]),cells[4],cells[5]) ==
                    ('FID',o['passIndex'],o['hostObservationNs'],r['nativeDevice'],native,o['intWordHex']),
                    'scalar source row: '+r['name'])
            fw = cells[6] if len(cells)>6 and cells[6] else None
            iv = signed(int(cells[5],16))
            fv = struct.unpack('<f',struct.pack('<I',int(fw,16)))[0] if fw else None
            available = iv not in (-10011,-10013) if fw is None else math.isfinite(fv) and fv not in (-10011.,-10013.,-1.)
            require((o['int32'],o['floatWordHex'],o['float32'],o['scalarResponse']) ==
                    (iv,fw,fv,available), 'scalar type/classification: '+r['name'])
            scalar_count += 1
        q = r['qualification']
        require(q['scalarResponseObserved'] == any(o['scalarResponse'] for o in r['historicalScalarRows']), 'scalar availability aggregation')
        require(q['valueChangedBetweenTwoRetainedPasses'] == (len({(o['intWordHex'],o['floatWordHex']) for o in r['historicalScalarRows']})>1), 'changed-cache count')
        for field in r['mcuDescriptors']:
            a = int(field['descriptorVA'],16)-0x40000
            p = int(field['propertyRecordVA'],16)-0x40000
            require(mcu[a:a+12].hex() == field['descriptorBytesHex'], 'MCU descriptor bytes')
            require(mcu[p:p+12].hex() == field['propertyRecordBytesHex'], 'MCU property bytes')
            require(list(mcu[a:a+4]) == field['coordinatesOneBasedByteLsb0'], 'MCU coordinates')
            require(struct.unpack_from('<II',mcu,a+4) == (int(field['handlerVA'],16),p+0x40000), 'MCU descriptor pointers')
            key = int(field['lookupKey'],16)
            table = int(field['tableVA'],16)
            require(struct.unpack_from('<I',mcu,int(field['hashPointerVA'],16)-0x40000)[0] == table, 'MCU hash pointer')
            require(struct.unpack_from('<I',mcu,table-0x40000)[0] == key, 'MCU lookup key')
            require(field['rawSource']['channel'] == (key>>24)-0xe0 and
                    int(field['rawSource']['canId'],16) == (key>>8)&0xffff,
                    'CAN channel/ID binding')
            descriptor_count += 1
        for counterexample in r.get('allOnesWireCounterexamples',[]):
            cv = d['nativeConverters'][counterexample['converterRef']]
            raw = (1<<counterexample['wireFieldBits'])-1
            value = (f32(f32(raw*cv['coefficientFloat32'])+cv['offset'])
                     if cv['kind']=='affineFloat' else raw+cv['offset'])
            require(value == counterexample['nativeApiValue'], 'all-ones conversion')
            require(counterexample['historicalMatchingLines'] == [o['line'] for o in r['historicalScalarRows'] if (o['float32'] if o['floatWordHex'] else o['int32'])==value], 'all-ones row join')

    # Replay only exact tuples. The existing strict parser checks sequence and
    # count integrity; no cross-channel reuse of a descriptor is allowed here.
    sys.path.insert(0, str(ROOT/'research/vehicle-diagnostics'))
    from decode_battery_pages import parse_log
    actual = []
    for source in d['rawSourceStreams']:
        rows = parse_log(ROOT/source['source'])
        require(len(rows)==source['totalRecords'], 'raw total count')
        for row in rows:
            if (row.can_id,row.sub,row.channel,len(row.payload)) != (0xfe,0,1,32):
                continue
            bits = int.from_bytes(row.payload,'little')
            left,right,validity = (bits>>176)&4095,(bits>>188)&4095,(bits>>175)&1
            actual.append(dict(source=source['source'],seq=row.seq,hostReceivedNs=row.t_ns,mcuCounter=row.counter,payloadHex=row.payload.hex(),leftRaw12=left,rightRaw12=right,validityRaw1=validity,leftApiCandidate=f32(f32(left*f32(.1))-20.),rightApiCandidate=f32(f32(right*f32(.1))-20.)))
    require(actual == d['rearSteeringRawProjection']['records'], 'rear steering raw projection')
    require(len(actual)==40, 'rear raw coverage')
    summary = dict(records=len(d['records']),families=dict(Counter(r['family'] for r in d['records'])),nativeResolved=sum(r['nativeFid'] is not None for r in d['records']),withHistoricalScalarRows=sum(bool(r['historicalScalarRows']) for r in d['records']),scalarResponseObserved=sum(r['qualification']['scalarResponseObserved'] for r in d['records']),changedBetweenTwoPasses=sum(r['qualification']['valueChangedBetweenTwoRetainedPasses'] for r in d['records']),withHalGetPattern=sum(bool(r['halGetPatterns']) for r in d['records']),withExactMcuDescriptor=sum(bool(r['mcuDescriptors']) for r in d['records']),allOnesArithmeticMatches=sum(bool(r.get('allOnesWireCounterexamples')) for r in d['records']),usableEngineeringSemantics=sum(r['qualification']['usableEngineeringSemantic'] for r in d['records']),sdkContinuousStreamsQualified=0)
    require(summary==d['summary'], 'summary count mismatch')
    return dict(valid=True,records=len(d['records']),sources=len(d['sources']),scalarRows=scalar_count,mcuDescriptorBindings=descriptor_count,nativeConverters=len(d['nativeConverters']),rearRawRows=len(actual))


if __name__ == '__main__':
    try:
        print(json.dumps(check(), ensure_ascii=False))
    except (AssertionError,KeyError,ValueError,IndexError) as exc:
        raise SystemExit('FAIL: '+str(exc))
