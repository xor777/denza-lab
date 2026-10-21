#!/usr/bin/env python3
"""Strict offline reconstruction of stock GB43C/43D battery page structures.
Reads completed saved logs only. No Android, network, IPC or firmware execution.
No engineering scale or physical sensor numbering is manufactured here.
"""
from pathlib import Path
from dataclasses import dataclass, asdict
import argparse
import hashlib
import json
import math
import re
ROOT = Path.cwd()

@dataclass
class Frame:
    seq: int
    t_ns: int
    stream: str
    can_id: int
    sub: int
    channel: int
    counter: int
    payload: bytes

    def public(self):
        return {**asdict(self), 'payload': self.payload.hex()}

def parse_log(path):
    text = path.read_text()
    lines = text.splitlines()
    prefix = '^\\[RawCanTurnProbe\\] '
    starts = [re.fullmatch(prefix + 'START uid=(\\d+) duration_s=(\\d+) queue_capacity=(\\d+)(?: stream=(\\w+))?', line) for line in lines]
    starts = [m for m in starts if m]
    readies = [re.fullmatch(prefix + 'READY callback=0x([0-9a-fA-F]+) t_ns=(\\d+)', line) for line in lines]
    readies = [m for m in readies if m]
    done = [(i, re.fullmatch(prefix + 'DONE received=(\\d+) dropped=(\\d+) callback_errors=(\\d+) queued=(\\d+)', line)) for (i, line) in enumerate(lines)]
    done = [(i, m) for (i, m) in done if m]
    unregistered = [i for (i, line) in enumerate(lines) if line == '[RawCanTurnProbe] UNREGISTERED']
    if not text.endswith('\n') or len(starts) != 1 or len(readies) != 1 or (len(done) != 1) or (len(unregistered) != 1):
        raise ValueError('Incomplete or ambiguous capture lifecycle')
    (done_index, completion) = done[0]
    if unregistered[0] >= done_index or any((line.strip() for line in lines[done_index + 1:])):
        raise ValueError('Invalid completion order or content after DONE')
    start_index = lines.index(starts[0].string)
    ready_index = lines.index(readies[0].string)
    data_indices = [i for i, line in enumerate(lines) if '] BUFFER ' in line or '] FRAME ' in line]
    # Registration can produce a callback before READY. Teardown drains queued
    # records after UNREGISTERED; all of them must still precede DONE.
    if not start_index < ready_index < unregistered[0] or any(i < start_index for i in data_indices):
        raise ValueError('Capture lifecycle order differs from the collector')
    if any((re.search('\\b(?:FAIL|UNREGISTER_FAIL)\\b', line) for line in lines)):
        raise ValueError('Capture reports failure')
    stream = starts[0][4] or 'bigdata'
    callbacks = {'gb': 0x9900001F, 'bigdata': 0x99000020, 'yun': 0x99000021}
    if stream not in callbacks or int(readies[0][1], 16) != callbacks[stream]:
        raise ValueError('Unqualified stream/callback pair')
    (received, dropped, errors, queued) = map(int, completion.groups())
    if dropped or errors or queued:
        raise ValueError('Capture reports loss, errors or queued data')
    out = []
    for line in lines:
        if '] BUFFER ' in line:
            m = re.search('BUFFER seq=(\\d+) t_ns=(\\d+) stream=(\\w+) len=(\\d+) raw=([0-9a-fA-F]+)$', line)
            if not m:
                raise ValueError('Malformed BUFFER record')
            b = bytes.fromhex(m[5])
            if len(b) != int(m[4]) or len(b) < 10:
                raise ValueError('Invalid envelope length')
            if stream == 'bigdata' or m[3] != stream:
                raise ValueError('BUFFER stream mismatch')
            out.append(Frame(int(m[1]), int(m[2]), m[3], int.from_bytes(b[:4], 'big'), b[4], b[5], int.from_bytes(b[6:10], 'big'), b[10:]))
        elif '] FRAME ' in line:
            m = re.search('FRAME seq=(\\d+) t_ns=(\\d+) id=0x([0-9a-fA-F]+) sub=0x([0-9a-fA-F]+) ch=(\\d+) cnt=(\\d+) len=(\\d+) payload=([0-9a-fA-F]*)$', line)
            if not m:
                raise ValueError('Malformed FRAME record')
            b = bytes.fromhex(m[8])
            if len(b) != int(m[7]):
                raise ValueError('Invalid payload length')
            if stream != 'bigdata':
                raise ValueError('FRAME outside BigData stream')
            out.append(Frame(int(m[1]), int(m[2]), stream, int(m[3], 16), int(m[4], 16), int(m[5]), int(m[6]), b))
    if len(out) != received or [f.seq for f in out] != list(range(1, received + 1)):
        raise ValueError('Count mismatch, missing, duplicate or reordered sequence')
    if any((b.t_ns < a.t_ns for (a, b) in zip(out, out[1:]))):
        raise ValueError('Device monotonic time moved backwards')
    for f in out:
        if not 0 <= f.can_id <= 0x7FF or not 0 <= f.sub <= 255 or f.channel not in (0, 1, 2) or (not 0 <= f.counter <= 0xFFFFFFFF):
            raise ValueError('Frame outside retained envelope domain')
        if len(f.payload) not in {0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 16, 20, 24, 32, 48, 64}:
            raise ValueError('Illegal CAN/CAN-FD payload length')
    return out

def reconstruct(frames, can_id, count):
    if can_id not in (0x43C, 0x43D):
        raise ValueError('Unsupported array ID')
    if not isinstance(count, int) or isinstance(count, bool) or (not 1 <= count <= 300):
        raise ValueError('Count outside supported range')
    (width, slots, invalid) = (1, 6, 0xFF) if can_id == 0x43C else (2, 3, 0xFFFF)
    page_count = math.ceil(count / slots)
    cycles = []
    special = []
    rejected = []
    pending = []
    expected = 0
    for f in frames:
        if f.stream != 'gb':
            continue
        if (f.can_id, f.sub, f.channel) != (can_id, 0, 2):
            continue
        if len(f.payload) != 8:
            rejected.append({'seq': f.seq, 'reason': 'payload_length_not8'})
            pending = []
            expected = 0
            continue
        page = f.payload[0]
        if page >= page_count:
            special.append({**f.public(), 'classification': 'FD_policy_metadata' if can_id == 0x43D and page == 0xFD else 'unknown_out_of_range_page'})
            if pending:
                rejected.append({'seq': f.seq, 'reason': 'special_page_interrupts_incomplete_cycle', 'pages': [x.payload[0] for x in pending]})
            pending = []
            expected = 0
            continue
        if page == 0:
            if pending:
                rejected.append({'seq': f.seq, 'reason': 'new_page0_before_completion', 'incomplete_pages': [x.payload[0] for x in pending]})
            pending = []
            expected = 0
        if page != expected:
            rejected.append({'seq': f.seq, 'reason': 'page_out_of_order_or_prefix', 'page': page, 'expected': expected})
            pending = []
            expected = 0
            continue
        if can_id == 0x43C and sum(f.payload) % 256 != 255:
            rejected.append({'seq': f.seq, 'reason': 'observed43C_checksum_rule_failed'})
            pending = []
            expected = 0
            continue
        pending.append(f)
        expected += 1
        if expected != page_count:
            continue
        words = [int.from_bytes(x.payload[1 + i * width:1 + (i + 1) * width], 'little') for x in pending for i in range(slots)]
        padding = words[count:]
        raw = words[:count]
        bad = [i for (i, v) in enumerate(raw) if v == invalid]
        valid_padding = all((v == invalid for v in padding))
        observed = [value for value in raw if value != invalid]
        cycles.append({
            'canId': hex(can_id),
            'firstSeq': pending[0].seq,
            'lastSeq': pending[-1].seq,
            'startNs': pending[0].t_ns,
            'endNs': pending[-1].t_ns,
            'spanSeconds': (pending[-1].t_ns - pending[0].t_ns) / 1e9,
            'pages': [frame.payload[0] for frame in pending],
            'pageRecords': [
                {'page': frame.payload[0], 'seq': frame.seq,
                 'tNs': frame.t_ns, 'embeddedCounter': frame.counter}
                for frame in pending
            ],
            'count': count,
            'rawCodes': raw,
            'valuesOrNull': [None if value == invalid else value for value in raw],
            'missingMarkerPositionsZeroBased': bad,
            'padding': padding,
            'paddingValid': valid_padding,
            'completePages': True,
            'structurallyAcceptedArray': valid_padding and not bad,
            'atomicSnapshot': False,
            'engineeringUnit': None,
            'physicalSensorOrder': 'unproved; positions follow stock page assembly',
            'meanRaw': sum(observed) / len(observed) if observed else None,
            'minRaw': min(observed, default=None),
            'maxRaw': max(observed, default=None),
        })
        pending = []
        expected = 0
    if pending:
        rejected.append({'reason': 'trailing_incomplete_cycle', 'pages': [x.payload[0] for x in pending]})
    return {'canId': hex(can_id), 'count': count, 'slotsPerPage': slots, 'wordBytes': width, 'sourceEndian': 'little', 'checksumRule': 'sum(all8bytes)%256==255, observed in retained frames; native gate not proved' if width == 1 else 'unknown; no43Cchecksum applied', 'cycles': cycles, 'specialPages': special, 'rejectedSequences': rejected}

def analyze(path):
    fs = parse_log(path)
    if not fs or any((f.stream != 'gb' for f in fs)):
        raise ValueError('Battery qualification requires the GB stream')
    meta = [f for f in fs if (f.can_id, f.sub, f.channel) == (0x43A, 0, 2) and len(f.payload) == 8]
    pairs = sorted(set(((f.payload[0], f.payload[1]) for f in meta)))
    if len(pairs) != 1:
        raise ValueError('Count changed/missing; split capture explicitly')
    (cells, probes) = pairs[0]
    if not (1 <= cells <= 300 and 1 <= probes <= 300):
        raise ValueError('Invalid counts')
    arrays = {hex(id): reconstruct(fs, id, count) for (id, count) in [(0x43C, probes), (0x43D, cells)]}
    return {'path': str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else str(path), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'frames': len(fs), 'metadata43A': {'count': len(meta), 'cellCount': cells, 'probeCount': probes, 'records': [{'seq': f.seq, 'tNs': f.t_ns, 'insulationRaw': int.from_bytes(f.payload[2:4], 'little'), 'byte5Unqualified': f.payload[4], 'payload': f.payload.hex()} for f in meta]}, 'arrays': arrays, 'scope': 'Stock decoder-backed raw cell/probe structures. FF/FFFF are observed fill/missing markers, not the complete measurement-validity domain. Structural acceptance is not sensor health or measurement validity. No unit inference or physical index binding; cycles are not atomic.'}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('logs', nargs='+', type=Path, help='Completed saved GB log(s)')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    result = {'schema': 1, 'offlineOnly': True, 'inputs': [analyze(path.resolve()) for path in args.logs]}
    data = json.dumps(result, ensure_ascii=False, indent=2) + '\n'
    if args.output:
        args.output.write_text(data)
    else:
        print(data, end='')
if __name__ == '__main__':
    main()
