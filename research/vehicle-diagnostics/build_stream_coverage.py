#!/usr/bin/env python3
"""Offline crosswalk of every declared read channel, retained reads and ECU routes.

This emits research evidence, never a vehicle execution allowlist. The inputs
contain command/configuration surfaces too. A scalar reply is not a validated
measurement or proof of continuous delivery. No Android/network/subprocess API.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import re
import struct

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT = HERE / 'stream-coverage-20261021.json'
PRODUCT_ORIGINS = {
    'extras': 'apps/denza-apps/src/main/java/dev/denza/apps/feature/diagnostics/DiagnosticExtras.kt',
    'hub': 'apps/denza-apps/src/main/java/dev/denza/apps/feature/vehicle/VehicleSignals.kt',
    'catalog': 'apps/denza-apps/src/main/java/dev/denza/apps/feature/diagnostics/ecu/EcuCatalog.kt',
    'session': 'apps/denza-apps/src/main/java/dev/denza/apps/feature/diagnostics/scan/DiagnosticsSession.kt',
}
INPUTS = {
    'properties': 'research/vehicle-diagnostics/dicar-readable-properties-20261021.json',
    'sweep': 'captures/diagnostics-sweep-20261020/fids.tsv',
    'inventory': 'research/vehicle-diagnostics/ecu-inventory-coverage-20261021.json',
    'support': 'research/vehicle-diagnostics/ecu-live-data-support-20261021.json',
    **{key: 'captures/stream-coverage-20261021/product/' + Path(path).name
       for key, path in PRODUCT_ORIGINS.items()},
    'sdk': 'captures/split-firmware-20260923/jadx/framework/sources/android/hardware/bydauto/AbsBYDAutoDevice.java',
    'event': 'captures/split-firmware-20260923/jadx/framework/sources/android/hardware/bydauto/BYDAutoEventValue.java',
    'rseMapper': 'captures/ambient-light-20260923/jadx/DiCarServer/sources/com/byd/feature/rse/RseMapper.java',
    'rseDevice': 'captures/split-firmware-20260923/jadx/framework/sources/android/hardware/bydauto/rse/BYDAutoRSEDevice.java',
    'deviceConstants': 'captures/split-firmware-20260923/jadx/framework/sources/android/hardware/bydauto/BYDAutoConstants.java',
}
CHANNEL_COLUMNS = ['nativeDevice', 'nativeFid', 'propertyRowIndexes', 'snapshotRows',
                   'observedScalarTypes', 'cautionFlags', 'changedAcrossPasses', 'productReaders']
SNAPSHOT_COLUMNS = ['sourceLine', 'pass', 'receivedElapsedNs', 'intWord', 'floatWord']
NATIVE_ERRORS = {-10011, -10013, -2147482648}
NATIVE_LITERAL = r'(0x[0-9A-Fa-f]+(?:\.toInt\(\))?|-?\d+)'
PRIVATE_SYMBOL = re.compile(
    r'PASSWORD|TOKEN|PRIVATE_KEY|PUBLIC_KEY|LATITUDE|LONGITUDE|PHONE_NUMBER|'
    r'IMEI|IMSI|ICCID|UUID|(?:^|_)VIN(?:_|$)|MAC_ADDRESS|SSID|(?:^|_)IP_ADDRESS(?:_|$)')
DOMAIN_LABELS = {
    'Ac': 'Климат', 'Adas': 'Ассистенты водителя', 'Audio': 'Аудиосистема',
    'Bigdata': 'Пассивный поток CAN', 'Body': 'Кузовные функции', 'Call': 'Телефонные вызовы',
    'Charging': 'Зарядка', 'Collision': 'Состояние столкновения', 'Doorlock': 'Замки и двери',
    'Dtc': 'События диагностики', 'Energy': 'Энергия', 'Engine': 'Двигатель',
    'Gb': 'Телеметрия GB: батарея и приводы', 'Gearbox': 'Трансмиссия',
    'Instrument': 'Приборы и предупреждения', 'Lights': 'Освещение', 'Location': 'Положение и движение',
    'Motor': 'Состояния электроприводов', 'Mqtt': 'Шлюз MQTT и диагностики', 'Ota': 'Состояния обновления',
    'Panorama': 'Круговой обзор', 'Phone': 'Телефон', 'Pm25': 'Качество воздуха',
    'Power': 'Электропитание и потребление', 'Radar': 'Парковочные датчики', 'Radio': 'Радиоприёмник',
    'RearViewmirror': 'Зеркала', 'Reminder': 'Напоминания', 'Rse': 'Задняя мультимедийная система',
    'Safetybelt': 'Ремни безопасности', 'Security': 'Охранные состояния', 'Sensor': 'Датчики салона и среды',
    'Setting': 'Общие состояния и настройки', 'Special': 'Специальные функции', 'Speed': 'Скорость',
    'Statistics': 'Статистика и батарея', 'Test': 'Служебные и тестовые поля', 'Time': 'Время',
    'Tyre': 'Шины', 'Vehicle': 'Общие данные автомобиля', 'Version': 'Версии компонентов',
    'Wiper': 'Стеклоочистители', 'Yun': 'Поток YUN',
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def native_number(text):
    return int(text.replace('.toInt()', ''), 0) & 0xffffffff


def signed(word):
    return struct.unpack('>i', struct.pack('>I', int(word, 16)))[0]


def float_value(word):
    return struct.unpack('>f', struct.pack('>I', int(word, 16)))[0]


def parse_sweep(text):
    catalog, observed = {}, defaultdict(list)
    for line_number, line in enumerate(text.splitlines(), 1):
        parts = line.split('\t')
        if parts[0] == 'CAT':
            require(len(parts) == 4, 'CAT shape')
            key = int(parts[1]), parts[2]
            require(key not in catalog, 'duplicate CAT key')
            catalog[key] = {'line': line_number, 'aliases': parts[3].split('|')}
        elif parts[0] == 'FID':
            require(len(parts) == 7, 'FID shape')
            key = int(parts[3]), parts[4]
            row = [line_number, int(parts[1]), int(parts[2]), parts[5], parts[6] or None]
            require(all(previous[1] != row[1] for previous in observed[key]), 'duplicate pass/key')
            signed(row[3])
            if row[4] is not None:
                float_value(row[4])
            observed[key].append(row)
    require(set(observed) <= set(catalog), 'snapshot without catalog declaration')
    require(all([row[1] for row in values] == [1, 2] for values in observed.values()), 'two ordered passes')
    return catalog, observed


def product_readers(extras, hub):
    found = defaultdict(list)
    pattern = r'^\s*(\w+)\("[^"]+",\s*(\d+),\s*' + NATIVE_LITERAL + r',\s*([57]),'
    for match in re.finditer(pattern, extras, re.M):
        name, device, fid, transaction = match.groups()
        found[(int(device), f'0x{native_number(fid):08X}')].append(['extras', name, int(transaction), extras[:match.start()].count('\n') + 1])
    pattern = r'^\s*(\w+)\(\s*(\d+),\s*' + NATIVE_LITERAL + r',\s*VehicleTransact\.(INT|FLOAT),'
    for match in re.finditer(pattern, hub, re.M):
        name, device, fid, kind = match.groups()
        found[(int(device), f'0x{native_number(fid):08X}')].append(['hub', name, 5 if kind == 'INT' else 7, hub[:match.start()].count('\n') + 1])
    require(len(found) > 100, 'product-reader parser lost declarations')
    return found


def observations(rows):
    types, flags, values = set(), set(), []
    for _, _, _, integer, floating in rows:
        value = signed(integer)
        if value in NATIVE_ERRORS:
            flags.add('native_error_word_seen')
            if floating is None:
                continue
            types.add('float32')
            value = float_value(floating)
            if not math.isfinite(value):
                flags.add('nonfinite_float')
                continue
            if floating == '0xBF800000':
                flags.add('minus_one_float_marker_requires_field_contract')
                continue
        else:
            types.add('int32')
            if int(integer, 16) in (0xff, 0xfe, 0xffff, 0xfffe, 0xffffffff, 0xfffffffe):
                flags.add('sentinel_like_integer_requires_field_contract')
        values.append(value)
    if not rows:
        flags.add('no_retained_scalar_read')
    elif not values:
        flags.add('no_nonerror_scalar_value')
    elif all(value == 0 for value in values):
        flags.add('zero_only_not_health_or_presence_proof')
    if len(types) > 1:
        flags.add('native_type_changed')
    changed = len(rows) > 1 and len({(row[3], row[4]) for row in rows}) > 1
    return sorted(types), flags, changed


def resolve_devices(declarations, raw):
    """Resolve one constant indirection, retaining the original catalog unchanged."""
    anchors = [
        ('rseMapper', 'return BYDAutoRSEDevice.getInstance(context);'),
        ('rseDevice', 'private static int mDeviceType = BYDAutoConstants.BYDAUTO_DEVICE_RSE;'),
        ('deviceConstants', 'public static final int BYDAUTO_DEVICE_RSE = 1062;'),
    ]
    proof = []
    for source, anchor in anchors:
        text = raw[source].decode()
        require(text.count(anchor) == 1, 'RSE binding anchor changed: ' + source)
        proof.append({'input': source, 'line': text[:text.index(anchor)].count('\n') + 1,
                      'anchor': anchor})
    effective, corrected = [], []
    for index, item in enumerate(declarations):
        device = item['nativeDevice']
        if item['mapper'] == 'RseMapper':
            require(device in (None, 1062), 'RSE catalog conflicts with source')
            if device is None:
                device = 1062
                corrected.append(index)
        effective.append(device)
    return effective, {'mapper': 'RseMapper', 'nativeDevice': 1062,
                       'reason': 'SDK device number uses a named constant, missed by the original literal-only resolver',
                       'propertyRowIndexes': corrected, 'proof': proof}


def export_snapshot_rows(rows, symbols):
    """Account for personal/security fields without exporting their cached values."""
    private = any(PRIVATE_SYMBOL.search(symbol or '') for symbol in symbols)
    if private:
        return [[*row[:3], None, None] for row in rows], True
    return rows, False


def build():
    raw = {key: (ROOT / path).read_bytes() for key, path in INPUTS.items()}
    properties = json.loads(raw['properties'])
    declarations = [dict(zip(properties['columns'], row)) for row in properties['rows']]
    effective_devices, rse_binding = resolve_devices(declarations, raw)
    _, observed = parse_sweep(raw['sweep'].decode())
    readers = product_readers(raw['extras'].decode(), raw['hub'].decode())
    mapped, unresolved = defaultdict(list), []
    for index, item in enumerate(declarations):
        if effective_devices[index] is None or item['nativeCanFdHex'] is None:
            unresolved.append(index)
        else:
            mapped[effective_devices[index], item['nativeCanFdHex']].append(index)
    channels = []
    domain_rows = defaultdict(list)
    for key, indexes in sorted(mapped.items()):
        rows = observed.get(key, [])
        types, flags, changed = observations(rows)
        declared_types = {declarations[i]['declaredJavaType'] for i in indexes}
        if 'byte[]' in declared_types:
            flags.add('buffer_declaration_scalar_probe_does_not_qualify_payload')
        if any(declarations[i]['nativeResolution'].startswith('canfd_table:') for i in indexes):
            flags.add('includes_static_fallback_native_id')
        if any(declarations[i]['propertyKey'] != key[1] for i in indexes):
            flags.add('property_key_differs_from_native_fid')
        if any(declarations[i]['nativeDevice'] is None for i in indexes):
            flags.add('device_resolved_by_explicit_source_binding')
        export_rows, redacted = export_snapshot_rows(rows, [declarations[i]['symbol'] for i in indexes])
        if redacted:
            flags.add('personal_or_security_values_retained_only_in_local_capture')
        channels.append([*key, indexes, export_rows, types, sorted(flags), changed, readers.get(key, [])])
    for index, item in enumerate(declarations):
        domain_rows[item['mapper']].append(index)
    domains = []
    for name, indexes in sorted(domain_rows.items()):
        require(name.removesuffix('Mapper') in DOMAIN_LABELS, 'unlabelled logical domain: ' + name)
        keys = {(effective_devices[i], declarations[i]['nativeCanFdHex']) for i in indexes
                if effective_devices[i] is not None and declarations[i]['nativeCanFdHex'] is not None}
        rows = [row for row in channels if (row[0], row[1]) in keys]
        domains.append({'mapper': name, 'labelRu': DOMAIN_LABELS[name.removesuffix('Mapper')],
                        'nativeDevices': sorted({effective_devices[i] for i in indexes if effective_devices[i] is not None}),
                        'declaredProperties': len(indexes), 'resolvedChannels': len(rows),
                        'channelsWithSnapshots': sum(bool(row[3]) for row in rows),
                        'changedAcrossPasses': sum(row[6] for row in rows),
                        'existingProductReaders': sum(bool(row[7]) for row in rows),
                        'physicalEcuBinding': 'not inferred from logical mapper/device',
                        'continuityAndEngineeringSemantics': 'per-signal proof required'})

    inventory = json.loads(raw['inventory'])
    support = json.loads(raw['support'])
    by_request = {int(row['requestHex'], 16): row for row in support['addresses']}
    endpoints = []
    for route in inventory['routes']:
        request = route['route'].get('requestId')
        match = by_request.get(request)
        endpoints.append({'key': route['key'], 'route': route['route'], 'families': route['familyTokens'],
                          'factoryRecordIndexes': route['recordIndexes'],
                          'retainedLiveLabel': match['abbreviation'] if match else None,
                          'externalScannerLiveDataFlag': match['vendorConsensus'].get('liveData') if match else None,
                          'knownDirectMeasurementProtocol': 'OBD Mode01, advertised PIDs only' if request in (0x7e0, 0x7e2, 0x7e5) else None,
                          'udsMeasurementDidDefinitionsRecovered': False,
                          'headUnitSignalOwnership': 'requires independent binding; no logical-device-to-ECU shortcut'})
    requests = {e['route'].get('requestId') for e in endpoints}
    for request, row in sorted(by_request.items()):
        if request not in requests:
            endpoints.append({'key': f'can:{request:03X}/{request + 8:03X}',
                              'route': {'kind': 'can', 'requestId': request, 'responseId': request + 8, 'physicalBus': None},
                              'families': [row['abbreviation']], 'factoryRecordIndexes': [],
                              'retainedLiveLabel': row['abbreviation'],
                              'externalScannerLiveDataFlag': row['vendorConsensus'].get('liveData'),
                              'knownDirectMeasurementProtocol': 'OBD Mode01, advertised PIDs only' if request in (0x7e0, 0x7e2, 0x7e5) else None,
                              'udsMeasurementDidDefinitionsRecovered': False,
                              'headUnitSignalOwnership': 'requires independent binding; no logical-device-to-ECU shortcut'})
    counts = {'propertiesAccounted': len(declarations), 'logicalMapperDomains': len(domains),
              'resolvedNativeChannels': len(channels), 'unresolvedPropertyRows': len(unresolved),
              'channelsWithTwoScalarSnapshots': sum(len(row[3]) == 2 for row in channels),
              'channelsWithChangedRawReplies': sum(row[6] for row in channels),
              'channelsWithProductReader': sum(bool(row[7]) for row in channels),
              'channelsWithSentinelLikeInteger': sum('sentinel_like_integer_requires_field_contract' in row[5] for row in channels),
              'channelsWithOnlyZeroNumericReplies': sum('zero_only_not_health_or_presence_proof' in row[5] for row in channels),
              'channelsWithNoNonerrorScalarValue': sum('no_nonerror_scalar_value' in row[5] for row in channels),
              'channelsWithRedactedSnapshotValues': sum('personal_or_security_values_retained_only_in_local_capture' in row[5] for row in channels),
              'logicalEcuEndpointsAccounted': len(endpoints),
              'knownDirectObdEndpoints': sum(e['knownDirectMeasurementProtocol'] is not None for e in endpoints),
              'externalSupportFlagByRetainedAddress': dict(Counter(str(a['vendorConsensus'].get('liveData')) for a in support['addresses']))}
    # Complete partition, not a heuristic subset of promising names.
    accounted = unresolved + [i for row in channels for i in row[2]]
    require(sorted(accounted) == list(range(len(declarations))), 'property partition')
    require(len({e['key'] for e in endpoints}) == len(endpoints), 'duplicate endpoint')
    return {'schema': 'denza-lab.stream-coverage.v1', 'date': '2026-10-21',
            'scope': 'Offline complete declaration/retained-snapshot crosswalk; not all sensors and not an execution allowlist.',
            'actions': {'vehicleRequests': 0, 'networkRequests': 0, 'productChanges': 0},
            'inputs': {key: {'path': path, 'bytes': len(raw[key]), 'sha256': digest(raw[key])} for key, path in INPUTS.items()},
            'productSnapshotOrigins': PRODUCT_ORIGINS,
            'counts': counts, 'domains': domains, 'ecuEndpoints': endpoints,
            'explicitDeviceBindings': [rse_binding],
            'transportContracts': {
                'scope': 'Read-side contracts in pinned SDK and retained captures, not an instruction to register every feature.',
                'nativeScalars': {'identity': 'shell; normal application UID not qualified',
                                  'int': 'autoservice transaction5, int32 device and signed int32 FID',
                                  'float': 'autoservice transaction7; interpret the payload bits as float32',
                                  'limits': 'Known error words require handling. Do not extrapolate scalar parcel layouts to buffers.'},
                'sdkTypedGet': {'input': 'sdk', 'method': 'get(int[] eventTypes, Class<?> type)',
                                'acceptedTypes': ['Integer.TYPE', 'Double.TYPE', 'int[].class', 'float[].class', 'byte[].class'],
                                'scalarFields': {'Integer.TYPE': 'intValue', 'Double.TYPE': 'doubleValue'},
                                'bufferField': 'bufferDataValue',
                                'limits': 'Float.TYPE is not supported here. Unused DTO fields retain defaults, not universal operation status.'},
                'callback': {'input': 'sdk', 'methods': ['registerListener', 'unregisterListener'],
                             'limits': 'Requires feature-specific listener and an active Looper in qualified shell probes. Declaration/registration alone proves neither initial replay nor continuing delivery.'},
                'directEcu': {'known': ['ECM7E0 Mode01', 'VCU7E2 Mode01', 'BSCM7E5 Mode01'],
                              'limits': 'Use only each source ECU\'s advertised and qualified PIDs. No recovered per-ECU UDS measurement DID dictionary for other routes.'},
                'continuousProof': 'Per-field listener or exact passive CAN/sub/channel/length records; periodic scalar GET measures cache reads, not ECU acquisition cadence.',
            },
            'propertyIndexConvention': 'zero-based index in inputs.properties rows, pinned by whole-file SHA256',
            'channelColumns': CHANNEL_COLUMNS, 'snapshotColumns': SNAPSHOT_COLUMNS,
            'unresolvedPropertyRowIndexes': unresolved,
            'limits': ['No units, sensors, installed hardware or current freshness are inferred from an integer/float response.',
                       'Snapshot time is when a cached value was read, not when the ECU acquired it. Two samples are not a continuous stream.',
                       'Read/read_write declarations include controls/configuration; this inventory does not authorize or propose probing them all.',
                       'Byte-array properties need typed SDK get/listener. Scalar probe results do not prove buffer availability.',
                       'Property key, native FID, mapper interface, logical device, CAN ID and ECU diagnostic address are separate namespaces.',
                       'Vendor live-data support flag is a lead, not the request/DID/scale or applicability to this ECU software.',
                       'Unresolved IDs and failed reads are preserved, not treated as absent hardware.',
                       'Nonerror scalar means only that the value is not one of the recognized transport errors; per-field status/marker/range checks are still mandatory.',
                       'Product reader coverage is the frozen 2026-10-21 source snapshot, not proof of what is installed or of later implementation changes.',
                       'Personal/security-named channels retain only observation references, types and quality flags; intWord/floatWord are null. Raw values remain in the ignored local sweep, not this committed catalog.',
                       'Protocol callback/parameter contracts live in the companion domain reports; do not infer units from names.',
                       'DoIP endpoint inventory has older identity coverage; later DoIP identity results do not establish measurement streaming.'],
            'channels': channels}


def encode(report):
    report = dict(report)
    rows = report.pop('channels')
    prefix = json.dumps(report, ensure_ascii=False, indent=2)
    return prefix[:-2] + ',\n  "channels": [\n' + ',\n'.join(
        '    ' + json.dumps(row, ensure_ascii=False, separators=(',', ':')) for row in rows) + '\n  ]\n}\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--find', help='case-insensitive symbol substring; offline only')
    parser.add_argument('--limit', type=int, default=20)
    args = parser.parse_args()
    if args.find:
        report = json.loads(args.output.read_text())
        source = report['inputs']['properties']
        raw = (ROOT / source['path']).read_bytes()
        require(digest(raw) == source['sha256'], 'property catalog changed')
        props = json.loads(raw)
        channels = {i: row for row in report['channels'] for i in row[2]}
        matches = []
        for i, row in enumerate(props['rows']):
            if args.find.casefold() in (row[5] or '').casefold():
                matches.append({'propertyRow': i, **dict(zip(props['columns'], row)),
                                'nativeChannel': dict(zip(report['channelColumns'], channels[i])) if i in channels else None})
        print(json.dumps({'matches': len(matches), 'shown': matches[:max(0, args.limit)]}, ensure_ascii=False, indent=2))
        return
    result = build()
    expected = encode(result)
    if args.check:
        require(args.output.read_text() == expected, 'generated inventory differs')
    else:
        args.output.write_text(expected)
    print(json.dumps({'status': 'PASS' if args.check else 'written', **result['counts']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
