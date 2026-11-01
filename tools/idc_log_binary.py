#!/usr/bin/env python3
"""Decode the envelope of binary 0x49 records in an existing IDC .log.

The 233-byte body after the fixed protocol tag is preserved, not assigned
unproved vehicle-signal names. Offline only: no ADB, network or ZIP decryption.
"""
import argparse
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import struct

import idc_log_text as container

END_MARKER = 0xFE
NORMAL_MARKER = 0x38
PROTOCOL_TAG_OFFSET = 11
PROTOCOL_TAG_END = 19


class IdcBinaryFormatError(ValueError):
    pass


def decode_binary(data, year):
    """Validate the container and return (summary, decoded binary records)."""
    container_summary, _ = container.parse_idc_log(data)
    offset = 0
    collector_time = None
    records = []

    while offset < len(data):
        if len(data) - offset >= 8:
            epoch_ms = struct.unpack_from('>Q', data, offset)[0]
            if container.MIN_EPOCH_MS <= epoch_ms <= container.MAX_EPOCH_MS:
                collector_time = container.utc_iso(epoch_ms)
                offset += 8
                continue
        header = data[offset:offset + 2]
        payload = data[offset + 2:offset + container.FRAME_SIZE]
        if header in container.BINARY_HEADERS:
            if payload[-1] != END_MARKER:
                raise IdcBinaryFormatError(f'missing 0xFE end marker at offset {offset}')
            marker = payload[1]
            try:
                controller_time = datetime(year, *payload[2:7]).isoformat(timespec='seconds')
            except ValueError as error:
                raise IdcBinaryFormatError(f'invalid controller time at offset {offset}: {error}') from error
            records.append({
                'offset': offset,
                'collectorTimestampUtc': collector_time,
                'sequence': (header[1] << 8) | payload[0],
                'marker': marker,
                'markerHex': f'0x{marker:02x}',
                'controllerTime': controller_time,
                'reservedZero': payload[7],
                'uptimeMs': int.from_bytes(payload[8:11], 'big'),
                'protocolTagHex': payload[PROTOCOL_TAG_OFFSET:PROTOCOL_TAG_END].hex(),
                'bodyHex': payload[PROTOCOL_TAG_END:-1].hex(),
                'endMarkerHex': f'0x{payload[-1]:02x}',
            })
        offset += container.FRAME_SIZE

    discontinuities = []
    for previous, current in zip(records, records[1:]):
        delta = (current['sequence'] - previous['sequence']) & 0xFFFF
        if delta != 1:
            discontinuities.append({
                'previousSequence': previous['sequence'],
                'sequence': current['sequence'],
                'deltaMod65536': delta,
                'previousControllerTime': previous['controllerTime'],
                'controllerTime': current['controllerTime'],
            })
    normal = [record for record in records if record['marker'] == NORMAL_MARKER]
    uptime_deltas = Counter(
        current['uptimeMs'] - previous['uptimeMs']
        for previous, current in zip(normal, normal[1:])
        if (current['sequence'] - previous['sequence']) & 0xFFFF == 1)
    summary = {
        'inputSha256': container_summary['sha256'],
        'binaryRecords': len(records),
        'sequenceBytes': 'outer header byte 1 is high; payload byte 0 is low',
        'markers': dict(sorted(Counter(record['markerHex'] for record in records).items())),
        'protocolTags': dict(sorted(Counter(record['protocolTagHex'] for record in records).items())),
        'bodyBytesPerRecord': container.FRAME_SIZE - 2 - PROTOCOL_TAG_END - 1,
        'reservedZeroValues': dict(sorted(Counter(record['reservedZero'] for record in records).items())),
        'endMarkers': dict(sorted(Counter(record['endMarkerHex'] for record in records).items())),
        'normalRecords': len(normal),
        'normalFirstControllerTime': normal[0]['controllerTime'] if normal else None,
        'normalLastControllerTime': normal[-1]['controllerTime'] if normal else None,
        'sequenceDiscontinuities': discontinuities,
        'normalUptimeDeltaMs': {str(key): value for key, value in uptime_deltas.most_common()},
        'bodySemantics': 'unproved; bodyHex is preserved without invented signal names',
        'controllerTimeZone': 'not encoded; calendar agrees with the UTC vehicle timeline in this capture',
    }
    return summary, records


def write_outputs(prefix, summary, records):
    prefix = Path(prefix)
    paths = {suffix: prefix.with_name(prefix.name + suffix)
             for suffix in ('.jsonl', '.summary.json')}
    existing = [str(path) for path in paths.values() if path.exists()]
    if existing:
        raise FileExistsError('refusing to overwrite: ' + ', '.join(existing))
    prefix.parent.mkdir(parents=True, exist_ok=True)
    with paths['.jsonl'].open('x', encoding='utf-8') as output:
        for record in records:
            output.write(json.dumps(record, ensure_ascii=False) + '\n')
    with paths['.summary.json'].open('x', encoding='utf-8') as output:
        json.dump(summary, output, ensure_ascii=False, indent=2)
        output.write('\n')
    return paths


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('--out-prefix', required=True, type=Path)
    parser.add_argument('--year', type=int, default=2026,
                        help='controller calendar year; the record itself omits it (default: 2026)')
    args = parser.parse_args()
    summary, records = decode_binary(args.input.read_bytes(), args.year)
    summary['input'] = str(args.input)
    paths = write_outputs(args.out_prefix, summary, records)
    print(json.dumps({'summary': str(paths['.summary.json']),
                      'binaryRecords': len(records)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
