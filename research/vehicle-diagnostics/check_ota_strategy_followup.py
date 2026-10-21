#!/usr/bin/env python3
"""Reproduce the retained OTA follow-up offline. Never accesses a device/network."""
import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[2]
ARTIFACT = ROOT / 'research/vehicle-diagnostics/ota-strategy-followup-20261021.json'


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def verify_refs(value, errors):
    count = 0
    if isinstance(value, list):
        for item in value:
            count += verify_refs(item, errors)
    elif isinstance(value, dict):
        if 'path' in value and 'sha256' in value:
            path = ROOT / value['path']
            raw = path.read_bytes()
            if sha256(raw) != value['sha256']:
                errors.append(f'hash: {path}')
            count += 1
            if 'requiredTokens' in value:
                lines = raw.decode().splitlines()
                selected = '\n'.join(lines[value['lineStart']-1:value['lineEnd']])
                missing = [token for token in value['requiredTokens'] if token not in selected]
                if missing:
                    errors.append(f'anchor {path}:{value["lineStart"]}-{value["lineEnd"]}: {missing}')
        for item in value.values():
            count += verify_refs(item, errors)
    return count


def read_json(ref):
    return json.loads((ROOT / ref['path']).read_text())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive-dir', type=Path,
                        default=Path.home() / 'Dev/denza/firmware')
    args = parser.parse_args()
    data = json.loads(ARTIFACT.read_text())
    errors = []
    refs = verify_refs(data, errors)
    if errors:
        raise AssertionError('\n'.join(errors))

    source = data['cacheSources']
    cache = read_json(source['ecuVersions'])
    rows = {row['ecuId']: row for row in cache['ecuInfo']}
    assert len(rows) == len(cache['ecuInfo']) == 225
    candidate = data['finiteTupleCandidate']
    matches = [row for row in rows.values() if row['ecuConfigCode'] == 'FF' * 9]
    assert matches == [candidate['record']]
    assert len(matches) == candidate['count'] == 1
    assert int(candidate['versionBytesHex'], 16) == candidate['versionAsUnsigned16'] == 65535
    decimal = str(candidate['versionAsUnsigned16']).zfill(5)
    formatted = f'{decimal[:1]}.{decimal[1:3]}.{decimal[3:]}'
    assert formatted == matches[0]['ecuConfigVer'] == '6.55.35'
    assert matches[0]['ecuSoftVer'] == '4.00.36'
    assert not candidate['isFailureCauseProved']

    assessment = data['baselineAssessment']
    config_counts = dict.fromkeys(['zero', 'negativeMarker', 'allFF', 'otherNonzero'], 0)
    for row in rows.values():
        code = row['ecuConfigCode']
        kind = {'00' * 9: 'zero', '7F22': 'negativeMarker', 'FF' * 9: 'allFF'}.get(code, 'otherNonzero')
        config_counts[kind] += 1
    assert config_counts == assessment['configCodeClassCounts']
    factory_rows = {row['ecuId']: row for row in read_json(assessment['factoryRouteSource'])['ecus']}
    route = assessment['fZcuSourceRoute']
    assert all(factory_rows[route['ecuId']][key] == route[key] for key in ['ecuDiaId', 'ecuConfigDid'])
    assert assessment['fZcuConfigAcceptedByClientPredicate']
    assert not assessment['fZcuConfigAloneTriggersRetry']
    assert not assessment['fZcuConfigTriggersRepairSubstitution']
    assert not assessment['ownerHistory']['independentlyVerified']
    assert assessment['newDeviceOrNetworkActionsExecuted'] == []

    need = read_json(source['needCheck'])
    timing = data['cacheTimeQualification']
    assert need['timestamp'] == timing['needCheckResponseLocalTimestamp']
    assert datetime.fromtimestamp(need['timestamp']/1000, timezone.utc).isoformat() == timing['needCheckTimestampUtc']
    assert len(need['ecuList']) == timing['needCheckEcuCount'] == 0
    assert len(need['cdnUrl']) == timing['needCheckCdnCount'] == 8
    # Host mtimes are recorded observations, not stable source timestamps; never assert them.
    assert timing['ecuVersionSourceTimestamp'] is None
    assert not timing['exact14h43RequestAssociationProved']

    comparison = data['firmwareComparison']
    media = next(row for row in comparison['multimediaRows'] if '_Media_' in row['ecuId'])
    fse = next(row for row in comparison['multimediaRows'] if '_ASSY_' in row['ecuId'])
    for row in (media, fse):
        assert rows[row['ecuId']] == row
    for archive, row, prefix in zip(comparison['archives'], (media, fse), ('Di5.1_', 'Di5.1_FSE_')):
        assert archive['archiveFileName'] == prefix + row['ecuSoftVer'] + '.zip'
        with ZipFile(args.archive_dir / archive['archiveFileName']) as ota:
            raw = ota.read(archive['member'])
        assert sha256(raw) == archive['memberSha256']
        props = dict(line.split('=', 1) for line in raw.decode().splitlines() if '=' in line)
        assert props['post-outswver'] == archive['postOutswver']
        assert row['ecuSoftVer'].startswith(props['post-outswver'] + '.')
        assert props['post-build'] == archive['postBuild']
        assert props['post-carseries'] == archive['postCarseries'] == 'denza'
    assert sha256((ROOT / comparison['fseMetadata']['path']).read_bytes()) == comparison['archives'][1]['memberSha256']

    for key, code in [('iviMcuXml', 'iviMcuCode'), ('fseMcuXml', 'fseMcuCode')]:
        config = ET.fromstring((ROOT / comparison[key]['path']).read_bytes()).find('Configure')
        assert config.findtext('EcuSoftwareCode') == comparison[code]
    manifest = read_json(comparison['fseMcuDecryptionManifest'])
    assert next(entry['sha256'] for entry in manifest if entry['input'] == 'mcu_42_2_3.xcd') == comparison['fseMcuXml']['sha256']
    iviconfig = ET.fromstring((ROOT / comparison['iviConfigXml']['path']).read_bytes())
    mcufile = iviconfig.find('Data/Mcu/Target/Package').attrib['FileName']
    mcu_revision = mcufile.removeprefix('di5.1_ruisa_app_canfd_e_').removesuffix('.xcd')
    assert media['ecuSoftVer'] == comparison['archives'][0]['postOutswver'] + '.' + mcu_revision

    props = dict(line.split('=', 1) for line in (ROOT / comparison['buildProperties']['path']).read_text().splitlines()
                 if '=' in line and not line.startswith('#'))
    assert (props['ro.build.car.series'], props['ro.build.system.fission_single_os'],
            props['ro.build.multi_display_user']) == ('denza', '0', '0')
    for mapping_ref in comparison['mediaMappingSources']:
        mapping = read_json(mapping_ref)['softcode_mapping']
        for flags in comparison['matchingMediaLayoutFlags']:
            matches = [entry for entry in mapping
                       if any(module['name'] == 'ivi' and module['softcode'] == flags['series']
                              and module.get('fission', 0) == flags['fission']
                              and module.get('displayUser', 0) == flags['displayUser']
                              for module in entry['module'])
                       and any(module['name'] == 'mcu' and module['softcode'] == comparison['iviMcuCode']
                               for module in entry['module'])]
            assert len(matches) == 1
            assert matches[0]['softcode'] == media['ecuSoftCode'] == comparison['iviMappedCode']

    # Validate absence of a hidden expected-version table or release-notes download offer.
    lines = (ROOT / source['liveFinal']['path']).read_text().splitlines()
    replies = [json.loads(line.split('responseStr :', 1)[1]) for line in lines if 'responseStr :' in line]
    final = next(reply for reply in replies if reply.get('status') == '111')
    sync = next(reply for reply in replies if reply.get('status') == '104')
    assert final['upgradeDetails'] is None
    assert all(sync[key] == '' for key in ['upgradeDetailsFilePath', 'upgradeDetailsFileSecretKey',
                                          'upgradeDetailsFileEncZipSHA256', 'upgradeDetailsFileIv'])
    assert all(final[key] == [] for key in ['targetPackageInfo', 'sourcePackageInfo',
                                          'configPackageInfo', 'configSourceInfo'])
    assert not data['answer']['offendingEcuProved']
    assert not data['answer']['newApplicableUpdateObtained']
    assert data['nextMinimalStep']['newActionsExecuted'] == []
    assert not data['eligibleForProductAllowlist']
    print(f'PASS: {refs} hashed references;111 cleanup/cause chain; two archive metadata;'
          ' two MCU headers; Media mapping in three clients; F_ZCU config and route; GHY cache scope;'
          ' baseline and stock-check boundaries')


if __name__ == '__main__':
    main()
