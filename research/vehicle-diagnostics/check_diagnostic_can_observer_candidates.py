#!/usr/bin/env python3
"""Check retained observer-source evidence and owner-Z9 passive windows offline.

Only reads local files. No vehicle, ADB, IPC, firmware execution or network calls.
The ten-byte raw envelope below applies only to the six retained GB/bigdata/Yun
windows. It is deliberately not applied to SecretOBD callback 99000241.
"""
import collections
import hashlib
import json
from pathlib import Path
import re
import struct


ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name('diagnostic-can-observer-candidates-20261021.json')
PREFIX = '[RawCanTurnProbe] '
START = re.compile(r'START uid=(\d+) duration_s=(\d+) queue_capacity=(\d+) stream=(\w+)')
READY = re.compile(r'READY callback=0x([0-9A-Fa-f]+) t_ns=(\d+)')
BUFFER = re.compile(r'BUFFER seq=(\d+) t_ns=(\d+) stream=(\w+) len=(\d+) raw=([0-9A-Fa-f]+)')
FRAME = re.compile(r'FRAME seq=(\d+) t_ns=(\d+) id=0x([0-9A-Fa-f]+) sub=0x([0-9A-Fa-f]+) ch=(\d+) cnt=(\d+) len=(\d+) payload=([0-9A-Fa-f]*)')
DONE = re.compile(r'DONE received=(\d+) dropped=(\d+) callback_errors=(\d+) queued=(\d+)')
LEGAL_LENGTHS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 16, 20, 24, 32, 48, 64}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def file_row(path):
    data = (ROOT/path).read_bytes()
    return dict(path=str(path), bytes=len(data), sha256=sha(data))


def inspect_stream(data, stream, callback):
    """Require a complete normalized journal before drawing absence conclusions."""
    require(data.endswith(b'\n'), 'partial journal: missing final newline')
    lines = data.decode('utf-8').splitlines()
    require(bool(lines) and all(x.startswith(PREFIX) for x in lines), 'unknown log prefix')
    body = [x[len(PREFIX):] for x in lines]
    start = START.fullmatch(body[0])
    require(start is not None and start.groups() == ('2000', '20', '4096', stream), 'fixed owner-window START')
    require(len([x for x in body if START.fullmatch(x)]) == 1, 'repeated START')
    readies = [READY.fullmatch(x) for x in body if READY.fullmatch(x)]
    require(len(readies) == 1 and int(readies[0][1], 16) == callback, 'READY callback')
    ready_at = int(readies[0][2])
    done = DONE.fullmatch(body[-1])
    require(done is not None and done.groups()[1:] == ('0', '0', '0'), 'incomplete/dropped/error/queued capture')
    require(len([x for x in body if DONE.fullmatch(x)]) == 1, 'repeated DONE')
    require(body.count('UNREGISTERED') == 1, 'listener cleanup marker')
    records = []
    for number, line in enumerate(body, 1):
        if START.fullmatch(line) or READY.fullmatch(line) or DONE.fullmatch(line) or line == 'UNREGISTERED':
            continue
        frame, buffer = FRAME.fullmatch(line), BUFFER.fullmatch(line)
        if frame:
            require(stream == 'bigdata', 'FRAME stream mismatch')
            seq, at, cid, sub, channel, counter, length, payload = frame.groups()
            seq, at, cid, sub, channel, counter, length = (
                int(seq), int(at), int(cid, 16), int(sub, 16), int(channel), int(counter), int(length))
            payload = bytes.fromhex(payload)
            require(len(payload) == length, 'FRAME payload length')
        elif buffer:
            require(stream in ('gb', 'yun'), 'BUFFER stream mismatch')
            seq, at, label, length, raw = buffer.groups()
            seq, at, length, raw = int(seq), int(at), int(length), bytes.fromhex(raw)
            require(label == stream and len(raw) == length and length >= 10, 'BUFFER declared length/envelope')
            cid, sub, channel = int.from_bytes(raw[:4], 'big'), raw[4], raw[5]
            counter, payload = int.from_bytes(raw[6:10], 'big'), raw[10:]
            length = len(payload)
        else:
            raise ValueError('unrecognized or failed record at line '+str(number))
        require(0 <= cid <= 0x7FF and 0 <= sub <= 255 and channel in (0, 1, 2), 'unqualified CAN tuple')
        require(length in LEGAL_LENGTHS and 0 <= counter <= 0xFFFFFFFF, 'payload/counter domain')
        records.append(dict(line=number, seq=seq, at=at, can=cid, sub=sub, channel=channel,
                            counter=counter, length=length, payload=payload.hex().upper()))
    require([r['seq'] for r in records] == list(range(1, int(done[1])+1)), 'noncontiguous sequence/count')
    require(all(a['at'] <= b['at'] for a, b in zip(records, records[1:])), 'arrival-clock backstep')
    tuples = collections.Counter((r['can'], r['sub'], r['channel'], r['length']) for r in records)
    diagnostic = [r for r in records if 0x700 <= r['can'] <= 0x7FF]
    return dict(
        records=len(records), tupleCount=len(tuples),
        readyNanos=ready_at, firstNanos=records[0]['at'] if records else None,
        lastNanos=records[-1]['at'] if records else None,
        preReadyRecords=sum(r['at'] < ready_at for r in records),
        completed=True, dropped=0, callbackErrors=0, queued=0,
        selectedReplyCounts={f'{cid:03X}': sum(r['can'] == cid for r in records) for cid in (0x7E8, 0x7EE)},
        diagnosticRangeCount=len(diagnostic),
        diagnosticTuples=[dict(canIdHex=f'{cid:03X}', subId=sub, channel=channel, payloadBytes=length, count=count)
            for (cid, sub, channel, length), count in sorted(collections.Counter(
                (r['can'], r['sub'], r['channel'], r['length']) for r in diagnostic).items())],
        diagnosticRecords=[dict(line=r['line'], seq=r['seq'], receivedNanos=r['at'],
            canIdHex=f"{r['can']:03X}", subId=r['sub'], channel=r['channel'],
            counterRawU32=r['counter'], payloadHex=r['payload']) for r in diagnostic])


def elf_range(data, va, size):
    require(data[:6] == b'\x7fELF\x02\x01', 'ELF64 little endian image required')
    phoff = struct.unpack_from('<Q', data, 32)[0]
    entsize, count = struct.unpack_from('<HH', data, 54)
    for i in range(count):
        kind, _, offset, start, _, filesz, _, _ = struct.unpack_from('<IIQQQQQQ', data, phoff+i*entsize)
        if kind == 1 and start <= va and va+size <= start+filesz:
            return data[offset+va-start:offset+va-start+size]
    raise ValueError('native range is not in one file-backed load segment')


def check():
    report = json.loads(REPORT.read_text())
    require(report['offlineOnly'] and report['vehicleOperations'] == report['networkOperations'] == 0, 'offline scope')
    for source in report['sources']:
        require(file_row(Path(source['path'])) == source, 'source hash/size drift: '+source['path'])
    hal = (ROOT/report['native']['image']).read_bytes()
    for row in report['native']['ranges']:
        data = elf_range(hal, int(row['startVA'], 16), row['bytes'])
        require(sha(data) == row['sha256'], 'native byte range drift: '+row['name'])
    for row in report['sourceAssertions']:
        text = (ROOT/row['path']).read_text()
        require(all(anchor in text for anchor in row['contains']), 'source anchor changed: '+row['path'])
    total = 0
    for window in report['retainedOwnerWindows']:
        raw = (ROOT/window['path']).read_bytes()
        require(sha(raw) == window['sha256'] and len(raw) == window['bytes'], 'capture hash/size drift')
        inventory = inspect_stream(raw, window['stream'], int(window['callbackHex'], 16))
        require(inventory == window['inventory'], 'window inventory drift: '+window['path'])
        total += inventory['records']
        # A damaged/incomplete stream must not qualify absence.
        for damaged in (raw[:-1], raw.replace(b'dropped=0', b'dropped=1'),
                        raw.replace(b'seq=1 ', b'seq=2 ', 1)):
            try:
                inspect_stream(damaged, window['stream'], int(window['callbackHex'], 16))
            except ValueError:
                continue
            raise ValueError('damaged journal accepted by inventory checker')
    require(total == report['aggregate']['records'], 'aggregate record count')
    require(sum(w['inventory']['diagnosticRangeCount'] for w in report['retainedOwnerWindows'])
            == report['aggregate']['diagnosticRangeCount'], 'diagnostic-range aggregate')
    print(f"PASS: {len(report['sources'])} source hashes, {len(report['native']['ranges'])} native ranges, "
          f"{len(report['retainedOwnerWindows'])} owner-Z9 windows/{total} records; "
          f"700..7FF count={report['aggregate']['diagnosticRangeCount']}; no vehicle operations")


if __name__ == '__main__':
    check()
