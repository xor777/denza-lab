#!/usr/bin/env python3
"""Strictly unpack an existing IdcLogCollect .log and extract its text frames.

This is an offline reader: it does not use ADB, decrypt ZIP files, or infer the
meaning of binary telemetry. Unknown headers, truncated frames and trailing
bytes are errors rather than data to resynchronize around.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import struct

FRAME_SIZE = 255
BINARY_HEADER = b'\x49\x00'
SECONDARY_BINARY_HEADER = b'\x49\x01'
TEXT_HEADER = b'\x59\x01'
BINARY_HEADERS = {bytes((0x49, subtype)): f'0x{subtype:02x}49' for subtype in range(15)}
KNOWN_HEADERS = {**BINARY_HEADERS, TEXT_HEADER: '0x0159'}
MIN_EPOCH_MS = 946684800000   # 2000-01-01T00:00:00Z
MAX_EPOCH_MS = 4102444800000  # 2100-01-01T00:00:00Z
TEXT_START = re.compile(r'^\d+\.\d+ \d{4}/\d{2}/\d{2} ')
TEXT_LEVEL = re.compile(r' (?:Info|Warn|Error) \[')


class IdcLogFormatError(ValueError):
    pass


def utc_iso(epoch_ms):
    return datetime.fromtimestamp(epoch_ms / 1000, timezone.utc).isoformat(timespec='milliseconds')


def parse_idc_log(data):
    """Return (summary, text_records), consuming every input byte exactly once."""
    offset = 0
    timestamps = []
    frame_types = {name: 0 for name in KNOWN_HEADERS.values()}
    records = []
    non_text_payloads = 0
    last_timestamp = None

    while offset < len(data):
        if len(data) - offset >= 8:
            epoch_ms = struct.unpack_from('>Q', data, offset)[0]
            if MIN_EPOCH_MS <= epoch_ms <= MAX_EPOCH_MS:
                last_timestamp = {'offset': offset, 'epochMs': epoch_ms,
                                  'utc': utc_iso(epoch_ms)}
                timestamps.append(last_timestamp)
                offset += 8
                if offset == len(data):
                    raise IdcLogFormatError(
                        f'timestamp at offset {last_timestamp["offset"]} has no following frame')
                continue

        if len(data) - offset < FRAME_SIZE:
            raise IdcLogFormatError(f'truncated frame or trailing bytes at offset {offset}')
        header = data[offset:offset + 2]
        if header not in KNOWN_HEADERS:
            raise IdcLogFormatError(
                f'unknown frame header {header.hex()} at offset {offset}')

        frame_type = KNOWN_HEADERS[header]
        frame_types[frame_type] += 1
        if header == TEXT_HEADER:
            payload = data[offset + 2:offset + FRAME_SIZE].split(b'\0', 1)[0]
            try:
                text = payload.decode('utf-8')
            except UnicodeDecodeError:
                text = None
            if text is not None and TEXT_START.match(text) and TEXT_LEVEL.search(text):
                records.append({
                    'offset': offset,
                    'frameType': frame_type,
                    'collectorTimestampUtc': last_timestamp['utc'] if last_timestamp else None,
                    'text': text,
                })
            else:
                non_text_payloads += 1
        offset += FRAME_SIZE

    if not timestamps and not sum(frame_types.values()):
        raise IdcLogFormatError('empty log')
    summary = {
        'sha256': hashlib.sha256(data).hexdigest(),
        'inputBytes': len(data),
        'consumedBytes': offset,
        'collectorTimestamps': {
            'count': len(timestamps),
            'firstUtc': timestamps[0]['utc'] if timestamps else None,
            'lastUtc': timestamps[-1]['utc'] if timestamps else None,
        },
        'frames': {'count': sum(frame_types.values()), 'byType': frame_types},
        'textRecords': len(records),
        'binaryFrames': sum(frame_types[name] for name in BINARY_HEADERS.values()),
        'nonTextPayloadFrames': non_text_payloads,
    }
    return summary, records


def write_outputs(prefix, summary, records):
    prefix = Path(prefix)
    paths = {suffix: prefix.with_name(prefix.name + suffix)
             for suffix in ('.txt', '.jsonl', '.summary.json')}
    existing = [str(path) for path in paths.values() if path.exists()]
    if existing:
        raise FileExistsError('refusing to overwrite: ' + ', '.join(existing))
    prefix.parent.mkdir(parents=True, exist_ok=True)

    with paths['.txt'].open('x', encoding='utf-8') as output:
        for record in records:
            text = record['text'].replace('\r', '\\r').replace('\n', '\\n')
            output.write(f"{record['collectorTimestampUtc'] or '-'} "
                         f"offset={record['offset']} {text}\n")
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
    args = parser.parse_args()
    data = args.input.read_bytes()
    summary, records = parse_idc_log(data)
    summary['input'] = str(args.input)
    paths = write_outputs(args.out_prefix, summary, records)
    print(json.dumps({'summary': str(paths['.summary.json']),
                      'textRecords': len(records),
                      'consumedBytes': summary['consumedBytes']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
