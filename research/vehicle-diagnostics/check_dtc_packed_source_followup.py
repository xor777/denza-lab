#!/usr/bin/env python3
"""Replay bounded packed-source evidence; offline/read-only, standard library."""
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
REPORT = Path(__file__).with_name('dtc-packed-source-followup-20261021.json')


def main():
    checks = 0

    def check(value, label):
        nonlocal checks
        assert value, label
        checks += 1

    def read_verified(item):
        raw = (ROOT / item['path']).read_bytes()
        check(hashlib.sha256(raw).hexdigest() == item['sha256'], item['path'] + ' hash')
        check(len(raw) == item['bytes'], item['path'] + ' bytes')
        return raw

    report = json.loads(REPORT.read_text())
    check(all(n == 0 for n in report['actions'].values()), 'offline operations')
    check(report['selectedApplicableTuple'] is None, 'tuple absent')
    for key in ['newQualifiedOemDefinitions', 'newQualifiedFormat01Mappings', 'newQualifiedWarningDtcLinks']:
        check(report[key] == 0, key)
    artifacts = {key: read_verified(item) for key, item in report['evidence'].items()}
    prior = {item['path']: read_verified(item) for item in report['priorEvidence']}
    projection = json.loads(artifacts['packedProjection'])
    anchors = json.loads(artifacts['sourceAnchors'])
    builder_path = ROOT / report['evidence']['builder']['path']
    spec = importlib.util.spec_from_file_location('dtc_packed_source_followup_builder', builder_path)
    builder = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(builder)
    check(builder.scan() == projection, 'exact repeated container/header scan')
    check(builder.source_anchors() == anchors, 'exact repeated source-range extraction')
    for item in anchors.values():
        raw = read_verified(item)
        lines = raw.decode().splitlines(keepends=True)
        for selected in item['ranges']:
            text = ''.join(lines[selected['start'] - 1:selected['end']])
            check(text == selected['text'], item['path'] + ' exact range')
            check(hashlib.sha256(text.encode()).hexdigest() == selected['sha256'], 'range hash')
    check(len(projection['packages']) == report['newCoverage']['packageCount'] == 5, 'package count')
    check(len(projection['members']) == report['newCoverage']['memberCount'] == 4014, 'member count')
    check(sum(x['uncompressedBytes'] for x in projection['packages']) == report['newCoverage']['verifiedUncompressedMemberBytes'], 'byte count')
    check(sum(x['headerBytesRead'] for x in projection['packages']) == report['newCoverage']['headerBytes'], 'header bytes')
    check(len(projection['decodedContainers']) == 2, 'two containers')
    check(sum(x['decodedBytes'] for x in projection['decodedContainers']) == report['newCoverage']['decodedContainerBytes'], 'decoded bytes')
    check(not projection['skippedMembers'], 'no members skipped')
    check(all(x['classification'] == 'OkHttp_public_domain_suffix_rules_not_DTC' and not x['targetLiteralHits'] for x in projection['decodedContainers']), 'domain lists only')
    opaque = projection['opaqueBinDataClassifications']
    check(len(opaque) == 3, 'opaque member count')
    check(opaque[0]['sha256'] == opaque[2]['sha256'], 'same DebugProbes class')
    check([x['classification'] for x in opaque] == ['JVM_class_DebugProbesKt', 'numeric_point_sequence_no_dictionary_schema', 'JVM_class_DebugProbesKt'], 'opaque structure exclusions')
    sources = {key: (ROOT / item['path']).read_text() for key, item in anchors.items()}
    check('new GzipSource' in sources['spot_suffix_loader'] and 'readInt()' in sources['spot_suffix_loader'], 'spot suffix loader')
    check('new GzipSource' in sources['ccs_suffix_loader'] and 'readInt()' in sources['ccs_suffix_loader'], 'ccs suffix loader')
    check('replaceAll("[^\\\\w]", "")' in sources['manual_cache_name'], 'extensionless cache naming')
    check('LocalPathUtil.getLocalPath(this.context, pageData.getZipInfo().getZipUrl(), this.listener)' in sources['manual_zip_loader'], 'manual package selection')
    check('zipInfo.getZipUploadTime()' in sources['manual_manifest'] and 'zipInfo.getServerFilepath()' in sources['manual_manifest'], 'retained manual manifest fields')
    supported = json.loads(prior['research/vehicle-diagnostics/supported-dtc-20261021.json'])
    bms = next(m for m in supported['modules'] if m['name'] == 'BMS')
    check(report['targets'][1]['f194'] == bms['softwareCode'] == '4A520011E00D00047B', 'exact BMS F194')
    check(report['unchangedGaps']['BMSMissingHeadingRaw24'] == [x['raw24Hex'] for x in bms['catalogComparison']['supportedCodesWithoutHeading']], 'six upstream BMS misses')
    check(report['unchangedGaps']['BMSConflictingPublicHeadingCodes'] == bms['catalogComparison']['conflictingMatchedCodes'] == ['P2BAE00'], 'BMS conflict retained')
    isg = json.loads(prior['research/vehicle-diagnostics/isg-supported-dictionary-candidates-20261021.json'])
    check(report['unchangedGaps']['ISGUnmatchedRaw24UnderUnqualifiedHypothesis'] == isg['UNPROVEN_reverseNotationHypothesis']['rawCodesWithoutHypotheticalHeading'], 'ISG misses retained')
    check(len(report['unchangedGaps']['ISGUnmatchedRaw24UnderUnqualifiedHypothesis']) == 24, '24 ISG misses')
    check(not report['unchangedGaps']['ISGFormat01Qualified'] and not report['unchangedGaps']['FMCUFormat01Qualified'], 'format01 boundary')
    print(json.dumps({'status': 'PASS', 'checks': checks, 'packages': 5,
                      'verifiedMembers': len(projection['members']),
                      'decodedContainers': 2,
                      'primaryJavaSources': len(anchors),
                      'qualifiedDefinitions': 0, 'vehicleOperations': 0,
                      'networkOperations': 0}, indent=2))


if __name__ == '__main__':
    main()
