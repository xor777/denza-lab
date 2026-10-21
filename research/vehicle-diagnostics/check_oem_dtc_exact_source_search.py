#!/usr/bin/env python3
"""Check retained sources and the UNPROVEN page shortlist; no network/vehicle I/O."""
import copy
import hashlib
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / 'research/vehicle-diagnostics/oem-dtc-exact-source-search-20261021.json'
EXPECTED = {
    '0x1E': ([], [('engine_ecm', 'outline_209_a'), ('engine_ecm', 'outline_210_a')]),
    '0x20': ([], [('engine_ecm', 'outline_490_a'), ('engine_ecm', 'outline_489_a')]),
    '0x358': ([('battery_management', 'outline_823_a'), ('battery_management', 'outline_808_a')], []),
    '0x5D': ([], [('battery_management', 'outline_792_a'), ('battery_management', 'outline_802_a')]),
    '0x47': ([('front_drive', 'outline_571_a'), ('generator', 'outline_607_a'),
              ('rear_left', 'outline_666_a'), ('rear_right', 'outline_630_a')], []),
}


def check(ok, reason):
    if not ok:
        raise ValueError(reason)


def load(path):
    return json.loads((ROOT / path).read_text())


def verify(d):
    check(d['vehicleOperations'] == d['authenticatedRequests'] == 0, 'scope')
    for key in ['qualifiedOemDefinitions', 'qualifiedSoftwareBindings', 'qualifiedWarningDtcLinks',
                'qualifiedFormat01RawDisplayMappings']:
        check(d[key] == [], 'unproved promotion: ' + key)
    paths = [s['path'] for s in d['sources']]
    check(len(paths) == len(set(paths)), 'duplicate source')
    for source in d['sources']:
        data = (ROOT / source['path']).read_bytes()
        check(len(data) == source['bytes'] and hashlib.sha256(data).hexdigest() == source['sha256'], source['path'])
    index = load('research/vehicle-diagnostics/oem-dtc-public-index-20261020.json')
    check(index['recordCount'] == len(index['records']) == 829, 'index coverage')
    check(not index['oemProvenanceVerified'] and not index['ecuSoftwareApplicabilityVerified'], 'catalog provenance')
    heads = {(r[0], r[2]): r for r in index['records']}
    popup = load('captures/firmware/pulled-from-car-20261018/popup_nads.json')
    warnings = {r['id']: r for r in popup['dictionary']}
    groups = d['UNPROVEN']['groups']
    ids = [g['warningIdHex'] for g in groups]
    check(len(ids) == len(set(ids)) == len(EXPECTED) and set(ids) == set(EXPECTED), 'warning coverage')
    count = excluded = 0
    for group in groups:
        warning = group['warningIdHex']
        check(group['warningOriginal'] == warnings[warning[2:]], 'warning source text')
        check(group['status'] == 'UNPROVEN' and group['warningOwnerECU'] is None and not group['warningDtcLinkProved'], 'warning ownership/link')
        for key, expected in zip(['semanticCandidates', 'excludedNearMatches'], EXPECTED[warning]):
            actual = [(r['subsystem'], r['outlineId']) for r in group[key]]
            check(actual == expected, 'exact candidate coverage: ' + warning + '/' + key)
            for item in group[key]:
                original = heads[item['subsystem'], item['outlineId']]
                check([item['subsystem'], item['displayCode'], item['outlineId'], item['publicTitleZh']] == original, 'exact original heading')
                check(item['status'] == 'UNPROVEN' and not item['warningLinkProved'] and not item['softwareApplicabilityVerified'], 'candidate promotion')
                check(item['factoryDefinition'] is None and item['rawCode'] is None and item['perCodeDocumentId'] is None, 'invented factory definition/raw/document ID')
                check(bool(item['whyNotEquivalent']) and bool(item['reasonToAcquirePage']), 'candidate boundary missing')
            if key == 'semanticCandidates':
                count += len(actual)
            else:
                excluded += len(actual)
    check(count == 6 and excluded == 6, 'shortlist counts')
    engine_heads = [r[3] for r in index['records'] if r[0] == 'engine_ecm']
    battery_heads = [r[3] for r in index['records'] if r[0] == 'battery_management']
    check(len(engine_heads) == 496 and len(battery_heads) == 206, 'section coverage')
    check(not any(re.search(r'\bSOC\b|电量|荷电', h, re.I) for h in battery_heads), 'SOC heading observation changed')
    check(not any(re.search(r'冷却液温度过高|水温过高|水温过热', h) for h in engine_heads), 'coolant overheat heading observation changed')
    check(not any(re.search(r'(机油|润滑油).*压力.*(不足|过低|低于)', h) and '电路' not in h for h in engine_heads), 'physical oil pressure heading observation changed')
    acquisition = load('research/vehicle-diagnostics/oem-dtc-acquisition-20261020.json')
    old_targets = {r['name']: r for r in acquisition['targets']}
    targets = d['targets']
    names = [t['name'] for t in targets]
    check(len(names) == len(set(names)) == 6 and set(names) == {'ECM', 'BMS033', 'FMCU', 'ISG', 'SMCU', 'SMCU3'}, 'target coverage')
    for target in targets:
        old = old_targets[target['name']]
        check(target['route'] == old['observedF194RequestAddress'] and target['softwareCode'] == old['softwareCode'], 'target identity')
        check(target['dtcFormatIdentifier'] == old['observedDtcFormatIdentifier'] and target['ecuCandidates'] == old['cacheCandidates'], 'target format/version')
        check(not target['applicableDictionaryRecovered'], 'dictionary promotion')
    raw = next(r['responseHex'] for r in load(d['ecmCalibration']['source'])['responses'] if r['requestHex'] == '0904')
    data = bytes.fromhex(raw)
    check(data[:3] == bytes([0x49, 4, 1]) and d['ecmCalibration']['responseHex'] == raw, 'calibration source')
    check(data[3:].split(b'\0')[0].decode('ascii') == d['ecmCalibration']['calibrationId'] == 'Z31L07AB01', 'calibration decode')
    powertrain = load('captures/diagnostics-oem-dtc-20261020/powertrain/toc-candidates-original-chinese.json')
    expected_pages = {(s['subsystem'], s['faultListDocumentId'], s['faultListOutlineId']) for s in powertrain['sections']}
    expected_pages |= {('engine_ecm', 'DTA1343324', 'outline_22_a'), ('engine_ecm', 'DTA1343330', 'outline_25_a')}
    pages = d['UNPROVEN']['missingOriginalCatalogPages']
    check(len(pages) == len(expected_pages) == 7 and {(p['subsystem'], p['documentId'], p['outlineId']) for p in pages} == expected_pages, 'missing-page locators')
    check(all(not p['bodyRetrievedInThisPass'] and not p['oemProvenanceVerified'] for p in pages), 'protected body/provenance promotion')
    check(not d['UNPROVEN']['cooccurrence']['observed'], 'invented cooccurrence')
    boundary = d['publicSourceBoundary']
    check(boundary['disclosureNumberForTarget'] is None and not boundary['vehicleIdentifierSubmitted'], 'environmental disclosure claim')
    downloads = load(d['captureBase'] + '/public-downloads.json')
    check(len(downloads) == 2 and len(boundary['modelSummaryDocuments']) == 2, 'public PDF coverage')
    for item in downloads:
        data = (ROOT / item['path']).read_bytes()
        check(item['status'] == 200 and data.startswith(b'%PDF-') and hashlib.sha256(data).hexdigest() == item['sha256'], 'public PDF integrity')
        check(item['url'].startswith('https://www.mee.gov.cn/') and item['finalUrl'] == item['url'], 'public PDF provenance')
    return dict(status='pass', sources=len(paths), warningGroups=len(groups), semanticCandidates=count,
                excludedNearMatches=excluded, missingCatalogPages=len(pages), targets=len(targets),
                qualifiedDefinitions=0, qualifiedWarningLinks=0, vehicleOperations=0)


def main():
    d = json.loads(REPORT.read_text())
    summary = verify(d)
    damaged = []
    item = copy.deepcopy(d)
    item['UNPROVEN']['groups'][2]['semanticCandidates'].pop()
    damaged.append(item)
    item = copy.deepcopy(d)
    item['UNPROVEN']['groups'][2]['warningDtcLinkProved'] = True
    damaged.append(item)
    item = copy.deepcopy(d)
    item['UNPROVEN']['groups'][1]['excludedNearMatches'][0]['publicTitleZh'] = 'low oil pressure'
    damaged.append(item)
    item = copy.deepcopy(d)
    item['UNPROVEN']['missingOriginalCatalogPages'][0]['documentId'] = 'DTA0000000'
    damaged.append(item)
    for item in damaged:
        try:
            verify(item)
        except ValueError:
            pass
        else:
            raise ValueError('accepted damaged shortlist')
    summary['damagedReportsRejected'] = len(damaged)
    print(json.dumps(summary))


if __name__ == '__main__':
    main()
