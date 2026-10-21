#!/usr/bin/env python3
"""Reproduce a finite raw FD/extrema correlation from completed owner logs.

Offline only. Uses the stricter sequential-page decoder, never the factory's
sticky completion latch. This does not name sensors or establish physical units.
"""
import argparse
import hashlib
import json
from pathlib import Path

from decode_battery_pages import analyze, parse_log


def inspect(path):
    analysis = analyze(path)
    if analysis['metadata43A']['cellCount'] != 166:
        raise ValueError('This evidence is specific to the retained 166-cell car')
    cycles = analysis['arrays']['0x43d']['cycles']
    rows = []
    previous_fd = -1
    for frame in parse_log(path):
        if (frame.can_id, frame.sub, frame.channel, len(frame.payload)) != (0x43d, 0, 2, 8):
            continue
        if frame.payload[0] != 0xfd:
            continue
        candidates = [c for c in cycles if previous_fd < c['startNs'] <= c['endNs'] < frame.t_ns]
        if len(candidates) > 1:
            raise ValueError('Ambiguous multiple sweeps before FD')
        words = [int.from_bytes(frame.payload[i:i+2], 'little') for i in (1, 3, 5)]
        row = dict(seq=frame.seq, tNs=frame.t_ns, payload=frame.payload.hex(),
                   word1=words[0], word3=words[1], word5=words[2], byte7=frame.payload[7],
                   precedingCompleteCycle=None)
        if candidates:
            c = candidates[0]
            row['precedingCompleteCycle'] = dict(firstSeq=c['firstSeq'], lastSeq=c['lastSeq'],
                minRaw=c['minRaw'], maxRaw=c['maxRaw'], spanSeconds=c['spanSeconds'],
                gapToFdSeconds=(frame.t_ns-c['endNs'])/1e9,
                structurallyAccepted=c['structurallyAcceptedArray'],
                maxMatches=words[1] == c['maxRaw'], minMatches=words[2] == c['minRaw'])
        rows.append(row)
        previous_fd = frame.t_ns
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('manifest', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    manifest = json.loads(args.manifest.read_text())
    paired = 0
    unpaired = 0
    for item in manifest['inputs']:
        path = root / item['path']
        if hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            raise ValueError('Changed input: ' + item['path'])
        rows = inspect(path)
        if rows != item['fd']:
            raise ValueError('Derived records differ: ' + item['path'])
        for row in rows:
            cycle = row['precedingCompleteCycle']
            if cycle is None:
                unpaired += 1
            else:
                if not (cycle['structurallyAccepted'] and cycle['maxMatches'] and cycle['minMatches']):
                    raise ValueError('Finite raw-extrema correlation did not reproduce')
                paired += 1
    if (paired, unpaired) != (manifest['pairedSweeps'], manifest['unpairedFd']):
        raise ValueError('Manifest totals differ')
    if paired == 0:
        raise ValueError('No complete pair')
    print(json.dumps(dict(passed=True, pairedSweeps=paired, unpairedFd=unpaired)))


if __name__ == '__main__':
    main()
