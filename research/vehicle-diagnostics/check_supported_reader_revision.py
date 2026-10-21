#!/usr/bin/env python3
"""Recheck the offline reader revision without rewriting historical live outcomes."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from decode_supported_dtc import parse_supported

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
BASE = ROOT / 'captures/diagnostics-reader-policy-20261021'
BUILD = BASE / 'build-01'
LIVE = ROOT / 'captures/diagnostics-can-visibility-20261021/live-01/probe.txt'
OUT = HERE / 'supported-reader-revision-20261021.json'


def fields(text, prefix):
    rows = [s for s in text.splitlines() if s.startswith(prefix)]
    assert len(rows) == 1, prefix
    return dict(s.split('=', 1) for s in rows[0].split()[1:])


def source(path):
    raw = path.read_bytes()
    return dict(path=str(path.relative_to(ROOT)), bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())


def derive():
    live = LIVE.read_text()
    replay_path = BASE / 'isg-replay.txt'
    replay = replay_path.read_text()
    old = fields(live, 'END ')
    assert old['reason'] == 'DUPLICATE_DTC_CODE' and old['completed'] == '5'
    assert old['cleanup'] == 'false' and old['queued'] == '1'
    retained = fields(live, 'REJECTED_FRAME seq=6 ')
    raw = bytes.fromhex(retained['raw'])
    assert raw[:7] == bytes.fromhex('0A07EEE7010101') and raw[238:] == b'\0\0'
    uds = raw[7:238]
    assert fields(live, 'RESPONSE seq=6 ')['uds'] == uds.hex().upper()
    for old_seq, new_seq in [(4, 1), (5, 2), (6, 3)]:
        assert fields(live, f'RESPONSE seq={old_seq} ')['uds'] == fields(replay, f'RESPONSE seq={new_seq} ')['uds']
    assert fields(replay, 'RESPONSE seq=1 ')['uds'] == '62F19448370011E0EA000000'
    assert fields(replay, 'RESPONSE seq=2 ')['uds'] == '590109010000'
    assert fields(replay, 'FINISH ')['reason'] == 'PLAN_DONE'
    assert not any(s.startswith('REJECTED_FRAME ') for s in replay.splitlines())
    registry = parse_supported(uds)
    records = registry['records']
    codes = Counter(r['raw24Hex'] for r in records)
    assert len(uds) == 231 and len(records) == 57 and len(codes) == 56
    assert registry['integrityPass'] and registry['duplicateRawCodes'] == ['C1D487']
    assert all(r['statusHex'] == '00' for r in records)
    duplicate_ordinals = [i for i, r in enumerate(records, 1) if r['raw24Hex'] == 'C1D487']
    assert duplicate_ordinals == [28, 40]
    projected = fields(replay, 'SUPPORTED ')
    assert {k: projected[k] for k in ['supported_count', 'unique_code_count', 'duplicate_record_count', 'active_fault_count']} == {
        'supported_count': '57', 'unique_code_count': '56', 'duplicate_record_count': '1', 'active_fault_count': 'not_measured'}
    manifest_path = BUILD / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    assert manifest['state'] == 'built_not_run' and manifest['vehicleOperationsDuringBuild'] == 0
    assert manifest['tests'] == 'PASS assertions=274 synthetic_only=true'
    assert source(BUILD / manifest['jar'])['sha256'] == manifest['jarSha256']
    paths = [LIVE, replay_path, manifest_path, BUILD / 'plan.json', BUILD / 'tests.txt',
             HERE / 'decode_supported_dtc.py', HERE / 'test_decode_supported_dtc.py', Path(__file__)]
    for row in manifest['sources']:
        frozen = BUILD / 'sources' / row['path']
        assert source(frozen)['sha256'] == row['sha256'], row['path']
        paths.append(frozen)
    return dict(schema='denza-lab.supported-reader-revision.v1', date='2026-10-21',
        vehicleOperations=0, productEdits=False, state='offline_replay_passed_not_run_on_car',
        historicalLiveOutcome=old,
        readerRevision=dict(build=str(BUILD.relative_to(ROOT)), jarSha256=manifest['jarSha256'],
            syntheticTests=manifest['tests'], replay='Actual retained envelopes supplied to a fake clock and transport; Java Engine accepts all three exchanges. No real cleanup or vehicle lifecycle is simulated.'),
        identity=dict(requestIdHex='7E6', softwareCode='48370011E0EA000000', formatIdHex='01'),
        retainedResponse=dict(udsBytes=231, sha256=hashlib.sha256(uds).hexdigest(), recordCount=57,
            uniqueRawCodes=56, duplicateRawCode='C1D487', duplicateOrdinals=duplicate_ordinals,
            orderedRecords=[dict(ordinal=i, raw24Hex=r['raw24Hex'], statusHex=r['statusHex']) for i, r in enumerate(records, 1)]),
        policy=dict(preserveOrder=True, preserveEveryStatus=True, mergeDuplicateStatuses=False,
            statusBitsOutsideAvailability='reject', malformedOrIncompleteTransport='reject',
            duplicateRawCode='report separately, accept structurally valid ordered records',
            missingOrWrongDuplicateCountsInNewTranscript='reject',
            format01='raw only; this fix does not qualify a P/C/B/U display mapping'),
        qualifiedFactoryDefinitions=[], qualifiedWarningDtcLinks=[],
        limitations=['The original failed pass and its cleanup=false remain unchanged.',
            'Supported records are not current or historical faults.',
            'An offline replay does not qualify new live-car lifecycle behavior.',
            'Only the research supported-registry reader changes; product fault-memory uniqueness rules are untouched.'],
        sources=[source(p) for p in paths])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    data = derive()
    if args.write:
        OUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n')
    else:
        assert json.loads(OUT.read_text()) == data, 'report differs from evidence'
    print('PASS offline_reader_replay=3/3 records=57 unique=56 historical_live_outcome=failed vehicle_operations=0')


if __name__ == '__main__':
    main()
