#!/usr/bin/env python3
"""Replay the bounded ISG report, frozen build and health offline; no car access."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re

from decode_isg_gateway_trace import decode_capture

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
BASE = ROOT/'captures/diagnostics-isg-followup-20261021'
OUTPUT = HERE/'isg-failure-report-20261021.json'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def derive():
    build, run = BASE/'build-02', BASE/'isg-trace'
    manifest_path, state_path = build/'manifest.json', run/'run.json'
    manifest, state = json.loads(manifest_path.read_text()), json.loads(state_path.read_text())
    plan_path, probe_path = build/'plan.json', run/'probe.txt'
    fixture = HERE/'fixtures/isg-failure-report-20261021.txt'
    assert fixture.read_bytes() == probe_path.read_bytes(), 'fixture differs from live evidence'
    value = decode_capture(probe_path.read_text(), json.loads(plan_path.read_text()))
    assert state['state'] == 'process_exited' and state['exitCode'] == 0
    assert state['plan'] == 'isg-trace' and not state['apkInstalled']
    assert state['manifestSha256'] == sha(manifest_path)
    assert state['jarSha256'] == manifest['jarSha256'] == sha(build/manifest['jar'])
    assert sha(run/'manifest.json') == sha(manifest_path)
    assert sha(run/manifest['jar']) == manifest['jarSha256']
    assert manifest['tests'] == 'PASS assertions=246 synthetic_only=true'
    sources = {Path(__file__), HERE/'decode_isg_gateway_trace.py', HERE/'decode_supported_dtc.py',
               HERE/'test_decode_isg_gateway_trace.py', fixture, manifest_path, state_path,
               plan_path, probe_path, BASE/'experiment-plan.json'}
    for row in manifest['sources']:
        frozen = build/'sources'/row['path']
        assert sha(frozen) == row['sha256'], str(frozen)
        sources.add(frozen)
    host_path = run/'host-source.py'
    assert sha(host_path) == state['hostSourceSha256']
    sources.add(host_path)
    # Only the frozen pure health parser is invoked; main/transport are not run.
    spec = importlib.util.spec_from_file_location('frozen_isg_health', host_path)
    host = importlib.util.module_from_spec(spec);spec.loader.exec_module(host)
    health = []
    for stage, key in [('preflight', 'before'), ('postflight', 'after')]:
        directory = run/stage
        raw = {p.stem:p.read_text() for p in directory.glob('*.txt')}
        parsed = host.parse_health(raw);parsed['state'] = 'read_complete'
        assert host.acceptable(parsed), stage
        saved = json.loads((directory/'status.json').read_text())
        assert all(saved[k] == v for k, v in parsed.items())
        assert state[key] == saved
        assert all(item['exitCode'] == 0 for item in saved['commands'].values())
        health.append(parsed)
        sources.update(directory.glob('*.txt'));sources.add(directory/'status.json')
    assert health[0]['appSha256'] == health[1]['appSha256']
    assert health[0]['stockPids'] == health[1]['stockPids']
    assert all(state[key] for key in ['jarRemoved', 'processAbsent', 'healthStable', 'lifecycleVerified'])
    for command in ['verify-jar-absent', 'verify-process-absent']:
        assert state['hostCommands'][command]['exitCode'] == 1
        for suffix in ['.stdout', '.stderr']:
            path = run/(command+suffix)
            assert not path.read_bytes().strip();sources.add(path)

    old_path = HERE/'isg-gateway-trace-20261021.json'
    old = json.loads(old_path.read_text());sources.add(old_path)
    image_path = ROOT/old['sources'][1]['path'];image = image_path.read_bytes()
    assert sha(image_path) == old['sources'][1]['sha256'];sources.add(image_path)
    for region in old['native']['ranges']:
        start, end = int(region['startVA'], 16)-0x40000, int(region['endExclusiveVA'], 16)-0x40000
        assert hashlib.sha256(image[start:end]).hexdigest() == region['sha256']
    asm_paths = [ROOT/'captures/diagnostics-supported-20261021/isg-trace'/name
                 for name in ['native-ranges.asm', 'native-new-ranges.asm']]
    sources.update(asm_paths)
    instructions = {}
    for path in asm_paths:
        for line in path.read_text().splitlines():
            match = re.match(r'^([0-9a-f]{8}):\s+([0-9a-f]+)\s+(.*)$', line)
            if match:
                address, encoded, description = match.groups()
                at = int(address, 16)
                assert image[at-0x40000:at-0x40000+len(encoded)//2] == bytes.fromhex(encoded)
                instructions[at] = dict(va=f'0x{at:x}', bytesHex=encoded, assembly=description)
    anchors = [instructions[at] for at in [0x15160E, 0x151614, 0x151616, 0x15161A,
                                           0x15161C, 0x152E66, 0x152EF2, 0x152F0E]]
    assert '0x56f' in instructions[0x151616]['assembly']
    assert 'blt' in instructions[0x15161A]['assembly']
    assert '0x152ef2' in instructions[0x152E66]['assembly']
    assert '0x152fdc' in instructions[0x152EF2]['assembly']
    assert '0x1515a0' in instructions[0x152F0E]['assembly']
    max_records = max(n for n in range(200) if 13*n < 0x56F)
    assert max_records == 106 and 13*max_records == 1378
    can = value['report']['can']
    assert can['nativeRecordCount'] == 20 < max_records
    assert (can['recoveredUdsBytes'], can['declaredUdsBytes'], can['completeRawRecordCount']) == (125, 231, 30)
    assert can['partialDtcTailHex'] == '183F' and can['unrecoveredByteCount'] == 106
    assert can['sequenceWrapObserved'] and not can['acceptedSuccessfulRegistry']
    assert all(r['statusHex'] == '00' for r in can['records'])
    old_records = [(r['rawDtc'], r['statusHex']) for r in old['reconstruction']['records']]
    assert old_records == [(r['raw24Hex'], r['statusHex']) for r in can['records'][:26]]
    review_paths = [BASE/'review'/name for name in ['prelive-build02-audit.json',
                    'live-artifact-audit.json', 'decoder-final-audit.json']]
    reviews = [json.loads(p.read_text()) for p in review_paths]
    assert reviews[0]['jarSha256'] == manifest['jarSha256']
    assert reviews[1]['runArtifactSha256'] == sha(probe_path)
    assert reviews[2]['independentMutations']['allInvalidCasesRejected']
    for row in reviews[2]['sources']:
        assert sha(ROOT/row['path']) == row['sha256'], 'reviewed source drift'
    sources.update(review_paths)
    return dict(schema='denza-lab.isg-failure-report-evidence.v1', date='2026-10-21',
        status='complete_published_failure_report_incomplete_uds_registry',
        historicalVehicleRequests=3, replayVehicleOperations=0,
        run=dict(plan='isg-trace', jarSha256=manifest['jarSha256'], tests=manifest['tests'],
                 exitCode=0, jarRemoved=True, processAbsent=True, lifecycleVerified=True,
                 healthStable=True, appSha256=health[0]['appSha256'], stockPids=health[0]['stockPids'],
                 crashEmpty=True, firmwareHashesMatch=True, exportIdle=True, apkInstalled=False),
        decoded=value,
        native=dict(imageApplicability=old['native']['imageApplicability'],
                    ranges=old['native']['ranges'], instructionAnchors=anchors,
                    recorderBound=dict(recordBytes=13, strictUpperByteBound=1391,
                        maxRecordsFromZero=max_records, maxRecordedBytes=1378,
                        observedRecords=20, recordCapacityReached=False),
                    mismatchedSequenceFramesOmitted=True,
                    mismatchObserved=False,
                    rootCauseQualified=False),
        priorPrefixComparison=dict(completeTuplesCompared=26, equal=True,
                                   oldPublishedReportWasComplete=False),
        independentReview=dict(verdicts=[r['verdict'] for r in reviews],
                               invalidDecoderMutationCases=12, allRejected=True,
                               reviewerVehicleOperations=0),
        nextDiscriminator='A separately qualified passive CAN-wire source is needed to distinguish missing, mismatched or unrecorded frames; do not infer the cause from this filtered MCU report.',
        qualifiedFactoryDefinitions=[], qualifiedWarningDtcLinks=[],
        sources=[dict(path=str(p.relative_to(ROOT)), bytes=p.stat().st_size, sha256=sha(p)) for p in sorted(sources)])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args();value = derive()
    if args.write:
        OUTPUT.write_text(json.dumps(value, ensure_ascii=False, indent=2)+'\n')
    else:
        assert json.loads(OUTPUT.read_text()) == value, 'Evidence differs from replay'
    print(f"PASS sources={len(value['sources'])} fragments=2 native_records=20 uds=125/231 raw_dtc_tuples=30 complete_registry=false health_and_cleanup=true vehicle_operations=0")


if __name__ == '__main__':
    main()
