#!/usr/bin/env python3
"""Replay retained supported-DTC captures and their health; never contacts a car."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

from decode_supported_dtc import decode
from decode_snapshot_index import decode as decode_snapshot
from decode_snapshot_record import decode as decode_record

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT/'captures/diagnostics-supported-20261021'
HERE = Path(__file__).resolve().parent
OUTPUT = HERE/'supported-dtc-20261021.json'
INDEX = HERE/'oem-dtc-public-index-20261020.json'
RUSSIAN = HERE/'oem-dtc-public-index-ru-20261020.json'
RUNS = [('ecm-bms','build-03',False), ('drives','build-03',True),
        ('rear-drives','build-04',False), ('isg-observe','build-05',True),
        ('snapshot-ecm-bms','build-05',False), ('snapshot-drives','build-05',False),
        ('bms-snapshot-record','build-06',True)]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def derive():
    source_paths = {INDEX, RUSSIAN, HERE/'decode_supported_dtc.py',
                    HERE/'decode_snapshot_index.py', HERE/'decode_snapshot_record.py', Path(__file__)}
    index = json.loads(INDEX.read_text())
    ru = json.loads(RUSSIAN.read_text())
    assert ru['sourceSha256'] == sha(INDEX)
    translations = {tuple(r[:3]):r[3] for r in ru['records']}
    hostpath=ROOT/'tools/vehicle-diagnostics/run_supported_dtc_probe.py'
    spec=importlib.util.spec_from_file_location('supported_health',hostpath)
    host=importlib.util.module_from_spec(spec);spec.loader.exec_module(host)
    source_paths.add(hostpath)
    runs=[];modules=[];snapshot_modules=[];snapshot_record_modules=[]
    for name,buildname,partial in RUNS:
        directory=BASE/name;build=BASE/buildname
        runpath=directory/'run.json';probepath=directory/'probe.txt';planpath=build/'plan.json'
        state=json.loads(runpath.read_text())
        is_snapshot=name.startswith('snapshot-')
        is_record=name=='bms-snapshot-record'
        if is_record:
            decoded=decode_record(probepath.read_text(),json.loads(planpath.read_text()),allow_partial=partial)
        elif is_snapshot:
            decoded=decode_snapshot(probepath.read_text(),json.loads(planpath.read_text()),allow_partial=partial)
        else:
            decoded=decode(probepath.read_text(),json.loads(planpath.read_text()),index,allow_partial=partial)
        source_paths.update([runpath,probepath,planpath,build/'manifest.json'])
        manifest=json.loads((build/'manifest.json').read_text())
        assert state['manifestSha256']==sha(build/'manifest.json')
        assert state['jarSha256']==manifest['jarSha256']==sha(build/manifest['jar'])
        for row in manifest['sources']:
            frozen=build/'sources'/row['path']
            assert sha(frozen)==row['sha256'],str(frozen)
            source_paths.add(frozen)
        host_source=directory/'host-source.py'
        if not host_source.exists():host_source=BASE/'host-source-initial/run_supported_dtc_probe.py'
        assert sha(host_source)==state['hostSourceSha256']
        source_paths.add(host_source)
        health=[]
        for stage in ('preflight','postflight'):
            hdir=directory/stage;raw={p.stem:p.read_text() for p in hdir.glob('*.txt')}
            parsed=host.parse_health(raw)
            parsed['state']='read_complete'
            assert host.acceptable(parsed),stage
            saved=json.loads((hdir/'status.json').read_text())
            assert all(saved[k]==v for k,v in parsed.items())
            assert state['before' if stage=='preflight' else 'after']==saved
            source_paths.update(hdir.glob('*.txt'));source_paths.add(hdir/'status.json')
            health.append(parsed)
        assert health[0]['stockPids']==health[1]['stockPids']
        assert health[0]['appSha256']==health[1]['appSha256']
        assert state['jarRemoved'] and state['processAbsent'] and state['healthStable']
        for command in ('verify-jar-absent','verify-process-absent'):
            assert state['hostCommands'][command]['exitCode']==1
            for suffix in ('.stdout','.stderr'):
                p=directory/(command+suffix);assert not p.read_bytes().strip();source_paths.add(p)
        assert decoded['completion']['complete'] != partial
        assert state['lifecycleVerified'] != partial
        runs.append(dict(name=name,requests=decoded['wireRequestCount'],
            completion=decoded['completion'],probeEnd=decoded['probeEnd'],
            rejectedFrames=decoded['rejectedFrames'],
            exitCode=state['exitCode'],lifecycleVerified=state['lifecycleVerified'],
            jarRemoved=True,processAbsent=True,healthStable=True,
            appSha256=health[0]['appSha256'],stockPids=health[0]['stockPids']))
        for module in decoded['modules']:
            if is_record:
                snapshot_record_modules.append(dict(run=name,**{k:v for k,v in module.items() if k!='wireEvents'}))
                continue
            if is_snapshot:
                snapshot_modules.append(dict(run=name,**{k:v for k,v in module.items() if k!='wireEvents'}))
                continue
            registry=module['supportedRegistry'];comparison=module['catalogComparison']
            record_rows=[]
            matches={r['raw24Hex']:r for r in comparison.get('headingMatches') or []}
            for record in registry['records'] if registry else []:
                match=matches.get(record['raw24Hex']);headings=[]
                for heading in match['headings'] if match else []:
                    key=tuple(heading[k] for k in ('subsystem','displayCode','outlineId'))
                    headings.append(dict(subsystem=key[0],outlineId=key[2],
                        candidateTitleRu=translations[key],factoryMeaningQualified=False))
                record_rows.append(dict(raw24Hex=record['raw24Hex'],statusHex=record['statusHex'],
                    displayCode=None if match is None else match['standardDisplay'],
                    candidateHeadings=headings))
            modules.append(dict(run=name,name=module['name'],requestIdHex=module['requestIdHex'],
                softwareCode=module['observedSoftwareCode'],identityMatched=module['exactSoftwareIdentityMatched'],
                targetCompleted=module['targetCompleted'],formatRead=module['formatRead'],
                terminalState=module['terminalState'],negativeResponses=module['negativeResponses'],
                supportedListRead=registry is not None,supportedCount=None if registry is None else registry['recordCount'],
                catalogComparison={k:v for k,v in comparison.items() if k not in
                    ('headingMatches','headingsNotInSupportedList')},records=record_rows))
    fmcu=next(m for m in modules if m['name']=='FMCU')
    raw_codes={r['raw24Hex'] for r in fmcu['records']}
    hypothetical=[]
    for section,code,outline,title in index['records']:
        if section!='front_drive':continue
        high=('PCBU'.index(code[0])<<14)|(int(code[1])<<12)|int(code[2:5],16)
        raw=f'{high:04X}'+code[5:]
        hypothetical.append(dict(outlineId=outline,publicDisplayCode=code,
            hypotheticalRaw24=raw,presentInObservedRegistry=raw in raw_codes))
    hypothesis=dict(state='hypothesis_only_not_qualified_format01_conversion',
        assumedEncoding='Reverse the format00 display notation of public headings; do not apply it as the format01 decoder',
        matchedRows=sum(r['presentInObservedRegistry'] for r in hypothetical),
        headingRows=len(hypothetical),rawWithoutHypotheticalHeading=sorted(raw_codes-{r['hypotheticalRaw24'] for r in hypothetical}),
        rows=hypothetical,qualifiedMapping=False,qualifiedFactoryMeanings=False)
    bms=next(m for m in modules if m['name']=='BMS')
    bms_snapshot=next(m for m in snapshot_modules if m['name']=='BMS')
    registry_codes={r['raw24Hex'] for r in bms['records']}
    snapshot_pairs=bms_snapshot['snapshotIndex']['records']
    snapshot_codes={r['raw24Hex'] for r in snapshot_pairs}
    snapshot_comparison=dict(supportedCodeCount=len(registry_codes),returnedSnapshotPairCount=len(snapshot_pairs),
        intersectingCodes=sorted(registry_codes & snapshot_codes),
        snapshotPairsOutsideSupportedRegistry=[r for r in snapshot_pairs if r['raw24Hex'] not in registry_codes],
        supportedCodesOutsideSnapshotIndex=sorted(registry_codes-snapshot_codes),
        meaning='Comparison of returned metadata; no stored-incident or engineering-value claim')
    search=BASE/'public-missing-code-search.json';source_paths.add(search)
    offline_build=BASE/'build-07'
    offline_manifest_path=offline_build/'manifest.json'
    offline_manifest=json.loads(offline_manifest_path.read_text())
    assert sha(offline_build/offline_manifest['jar'])==offline_manifest['jarSha256']
    assert offline_manifest['state']=='built_not_run'
    assert offline_manifest['tests']=='PASS assertions=212 synthetic_only=true'
    source_paths.add(offline_manifest_path)
    for row in offline_manifest['sources']:
        frozen=offline_build/'sources'/row['path']
        assert sha(frozen)==row['sha256'],str(frozen)
        source_paths.add(frozen)
    offline_revision=dict(build='build-07',state='offline_tested_not_run_on_car',
        jarSha256=offline_manifest['jarSha256'],tests=offline_manifest['tests'],
        change='Exact six-byte positive1904 DTC/status response produces SNAPSHOT_RECORD_EMPTY without invented record fields',
        historicalBuild06OutcomeUnchanged=True)
    return dict(schema='denza-lab.supported-dtc-evidence.v1',date='2026-10-21',
        status='read_only_registry_research_not_product_dictionary',
        runs=runs,modules=modules,snapshotModules=snapshot_modules,
        snapshotRecordModules=snapshot_record_modules,
        offlineReaderRevision=offline_revision,
        bmsSnapshotIndexComparison=snapshot_comparison,fmcuReverseNotationHypothesis=hypothesis,
        publicSearch=json.loads(search.read_text()),
        qualifiedFactoryDefinitions=[],qualifiedWarningDtcLinks=[],
        limitations=[
            'Supported codes are capabilities, not current faults. Keep mask09 memory count and per-code status separate.',
            'Exact raw/software membership is live evidence; matching public headings are still unqualified definitions.',
            'Format01 remains raw; no generic P-code translation is applied.',
            'The aborted drives pass does not establish ISG190A outcome: its rejected callback was not logged.',
            'The separate ISG followup retains only gateway fragment1/2 with opaque trace data; it is not an accepted supported registry.',
            'Snapshot-index identifiers do not establish stored incidents or decode record contents. Record IDs are not status bytes.',
            'The BMS1904 read returned DTC/status only, with no snapshot record. Build06 rejected that shorter response; its aborted lifecycle is not rewritten by later offline interpretation.',
            'Bytes beyond a gateway fragment declared payload length are not UDS data, even when retained in a rejected callback buffer.',
            'An incomplete plan may retain independently validated completed targets but cannot claim whole-plan success.',
        ],sources=[dict(path=str(p.relative_to(ROOT)),sha256=sha(p)) for p in sorted(source_paths)])


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true')
    args=parser.parse_args();value=derive()
    if args.write:OUTPUT.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n')
    else:assert json.loads(OUTPUT.read_text())==value,'Evidence differs from replay'
    print(json.dumps({'result':'PASS','runs':len(value['runs']),
        'requests':sum(r['requests'] for r in value['runs']),
        'supportedRegistries':{m['name']:m['supportedCount'] for m in value['modules'] if m['supportedListRead']},
        'sources':len(value['sources'])}))


if __name__=='__main__':main()
