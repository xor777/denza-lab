#!/usr/bin/env python3
"""Replay retained diagnostic-cache receipts offline; never contact the car.

Raw preferences were deliberately discarded. This verifies their recorded
projection and the exact reader/lifecycle sources, not the omitted XML bytes.
"""
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
REPORT = ROOT / 'research/vehicle-diagnostics/diagnostic-cache-access-20261021.json'
checks = 0


def check(condition, label):
    global checks
    if not condition:
        raise AssertionError(label)
    checks += 1


def verified(item):
    raw = (ROOT / item['path']).read_bytes()
    check(len(raw) == item['bytes'], item['path'] + ' size')
    check(hashlib.sha256(raw).hexdigest() == item['sha256'], item['path'] + ' hash')
    return raw


def main():
    report = json.loads(REPORT.read_text())
    evidence = {key: verified(item) for key, item in report['evidence'].items()}
    prior = {item['path']: verified(item) for item in report['priorEvidence']}
    metadata = json.loads(evidence['metadata'])['records']
    check(len(metadata) == 15, 'bounded metadata record count')
    denied = [row for row in metadata if row['status'] == 'permission_denied']
    check(len(denied) == 9 and all(row['exists'] is None for row in denied), 'shell denial is not absence')
    check(sum(row['status'] == 'not_found_by_shell' for row in metadata) == 2, 'external directory result count')
    check(metadata[0]['metadata'] == '170' and metadata[1]['metadata'] == 'CANFD', 'vehicle profile')
    packages = json.loads(evidence['installedPackages'])
    check(packages['currentUser'] == '0', 'user zero')
    manual = next(p for p in packages['packages'] if p['package'] == 'com.byd.auto_emanual')
    check('userId=10079' in manual['metadataLines'], 'manual separate UID')
    check('dataDir=/data/user/0/com.byd.auto_emanual' in manual['metadataLines'], 'manual data directory')
    ring = json.loads(evidence['manualRing'])
    check(ring['bytes'] == ring['lines'] == ring['actualTagLines'] == 0, 'empty filtered ring')
    check(ring['rawSha256'] == hashlib.sha256(b'').hexdigest(), 'empty ring hash')
    check(all(value == 0 for value in ring['markers'].values()), 'no retained manual markers')

    manual_sources = {}
    for key, item in report['manualAccessBoundary']['sources'].items():
        raw = verified(item)
        manual_sources[key] = raw.decode()
        lines = raw.decode().splitlines(keepends=True)
        for span in item['ranges']:
            selected = ''.join(lines[span['start']-1:span['end']]).encode()
            check(hashlib.sha256(selected).hexdigest() == span['sha256'], key + ' source range')
    android = '{http://schemas.android.com/apk/res/android}'
    providers = ET.fromstring(manual_sources['manifest']).findall('application/provider')
    check(len(providers) == 2 and all(p.get(android + 'exported') == 'false' for p in providers), 'declared private providers')
    roots = list(ET.fromstring(manual_sources['providerPaths']))
    check(len(roots) == 2 and all(p.tag == 'external-path' for p in roots), 'external paths only')
    check('FileProvider.getUriForFile(context, "com.byd.auto_emanual.fileProvider", file)' in manual_sources['installer'], 'APK share caller')
    check('intent.setDataAndType(uriFromFile, "application/vnd.android.package-archive")' in manual_sources['installer'], 'APK-only inspected share call')
    check('this.indexId = intent.getData().getQueryParameter(Parameters.ID_INDEX)' in manual_sources['entry'], 'UI index dispatch')
    check('SharedPrefUtil.clearParametersForOther()' in manual_sources['entry'], 'entry has state effect')
    check(report['manualAccessBoundary']['newQualifiedReadback'] is False, 'no promoted manual read API')

    plan = json.loads(evidence['plan'])
    check(len(plan['sourceSha256']) == 7, 'seven source modules pinned')
    for path, expected in plan['sourceSha256'].items():
        check(hashlib.sha256((ROOT / path).read_bytes()).hexdigest() == expected, 'approved source: ' + path)
    spot = '/data/user_de/0/com.byd.spotinspection/shared_prefs/com.byd.spotinspection.xml'
    ota = '/data/user/0/com.byd.otaupdate/shared_prefs/share_data_prefs.xml'
    check(plan['files'] == [spot, ota], 'exact two paths')
    check(plan['keys'] == ['RUNNING_TASK', 'afterRunRes', 'otaUpdateDownloadFileInfo'], 'exact three keys')
    check(plan['directoryListings'] == plan['maximumDownloads'] == 2 and plan['retries'] == 0, 'bounded one-shot plan')
    check(plan['ecuRequests'] == plan['factoryRequests'] == 0, 'no ECU or server calls')
    check(plan['projectionAfterConfirmedClosureOnly'] and not plan['rawPreferencesSaved'], 'projection boundary')
    check(evidence['consumedPlan'] == b'one session consumed\n', 'permission consumed')
    live_hashes = {item['path']: item['sha256'] for item in json.loads(evidence['apkHashes'])['apks']}
    for path, digest in plan['targetApks'].values():
        check(live_hashes[path] == digest, 'installed source build identity')

    session = json.loads(evidence['approvedSession'])
    check(session['plan'] == plan and session['status'] == 'complete', 'completed approved plan')
    for key in ('exportClosed', 'watchdogTerminal', 'leaseReleased', 'forwardRemoved', 'postApkIdentityVerified'):
        check(session[key] is True, key)
    trace = session['listenerTrace']
    check(trace[0] == {'phase': 'preflight', 'elapsedMs': 0, 'ports': []}, 'closed initial ports')
    check(trace[1]['phase'] == 'start' and trace[1]['ports'] == [46320, 46321, 46322], 'observed startup ports')
    check(len(trace) == 4 and all(t['phase'] == 'close' and t['ports'] == [] for t in trace[2:]), 'two closure samples')
    check([t['elapsedMs'] for t in trace] == sorted(t['elapsedMs'] for t in trace), 'ordered trace')
    projection = session['result']
    check(projection['files'] == report['projectedFiles'], 'retained projection')
    expected_rows = [
        (spot, 65, '3325d2a819fdd8062c2cdc48a09b995c9b012915bcdf88b1cf9742a7f057c793', ['RUNNING_TASK', 'afterRunRes']),
        (ota, 705, 'd3d4eced92d1c21ce7fe63e211449f2ff2d9face92376505f35e8329ce87c2ca', ['otaUpdateDownloadFileInfo']),
    ]
    check(len(projection['files']) == 2, 'two projected files')
    for row, (path, size, digest, keys) in zip(projection['files'], expected_rows):
        check(row == {'source': path, 'status': 'projected', 'rawSizeBytes': size, 'rawSha256': digest,
                      'selected': {key: {'status': 'key_missing'} for key in keys}}, 'exact whitelist result')
    check(projection['qualifiedDtcDefinitions'] == projection['qualifiedChildRoles'] == 0, 'no promoted labels or roles')
    post = json.loads(evidence['postflight'])
    check(post['exitCode'] == 0 and post['observedOutput'] == {'status': 'ready', 'exportStarted': False}, 'independent read-only postflight')
    check(report['actions']['bydLogStarts'] == 1 and report['actions']['protectedContentsRead'] == 2, 'single export accounted')
    check(report['actions']['ecuRequests'] == report['actions']['factoryServerRequests'] == report['actions']['otaActions'] == 0, 'no vehicle/factory operations')

    alternatives = json.loads(prior['research/vehicle-diagnostics/ecu-persistence-alternatives-20261021.json'])
    anchors = json.loads(verified(alternatives['evidence']['sourceAnchors']))
    check(len(anchors) == 12, 'twelve alternate-persistence sources')
    alternative_sources = {}
    for key, item in anchors.items():
        raw = verified(item['source'])
        check((ROOT / item['originalPath']).read_bytes() == raw, 'frozen source matches corpus: ' + key)
        alternative_sources[key] = raw.decode()
        lines = raw.decode().splitlines(keepends=True)
        for span in item['ranges']:
            selected = ''.join(lines[span['start']-1:span['end']])
            check(selected == span['text'], key + ' source anchor')
            check(hashlib.sha256(selected.encode()).hexdigest() == span['sha256'], key + ' range hash')
    for item in alternatives['evidence']['priorReportsAndLatestReceipt']:
        verified(item)
    findings = {item['id']: item for item in alternatives['newSourceFindings']}
    fields = re.findall(r'public String (\w+);', alternative_sources['downloadModel'])
    check(fields == findings['transport_download_preferences']['declaredFields'], 'exact download model fields')
    check(not set(fields) & {'ecuId', 'ecuDescription', 'ecuSoftCode', 'childDID'}, 'generic cache does not carry ECU semantics')
    check('DOWNLOAD_FILE_INFO_' in alternative_sources['downloadHelper'], 'URL-keyed transport cache')
    check('ecuDescription' not in alternative_sources['reportModel'], 'failed-report model drops description')
    check('noCache().noStore()' in alternative_sources['httpCachePolicy'], 'bounded HTTP cache policy')
    check(all(value == 0 for value in alternatives['actions'].values()), 'alternative audit remained offline')
    check(alternatives['result']['newNamedRoles'] == alternatives['result']['newQualifiedDtcDefinitions'] == 0, 'alternatives do not promote roles/meanings')

    # The UI selection is a historical body-controller sample, not a new live DTC.
    old = json.loads(prior['research/vehicle-diagnostics/dtc-20261018.json'])
    entry = next(e for e in old['entries'] if e['dtc_raw'] == 'C22A87')
    check(old['format_identifier'] == 0 and old['status_availability_mask'] == 'FF', 'old format and availability')
    check(entry['status_raw'] == '28' and entry['description'] is None, 'old raw status, no description')
    later = json.loads(prior['captures/diagnostics-scanner-20261020/last-scan-1931.json'])
    def find_route(value):
        if isinstance(value, dict):
            if value.get('request') == '720' and value.get('abbreviation') == 'LA_BCM':
                return value
            for child in value.values():
                found = find_route(child)
                if found is not None:
                    return found
        elif isinstance(value, list):
            for child in value:
                found = find_route(child)
                if found is not None:
                    return found
        return None
    route = find_route(later)
    check(route is not None and route['status'] == 'HISTORY' and route['active'] == 0, 'later body route')
    replies = {e['uds']: bytes.fromhex(e['answer']) for e in route['exchanges']}
    check(replies['190109'] == bytes.fromhex('590109000008'), 'later availability09 and format00')
    payload = replies['190209']
    check(payload[:3] == bytes.fromhex('590209'), 'later list availability')
    check(bytes.fromhex('C22A8708') in [payload[i:i+4] for i in range(3, len(payload), 4)], 'later raw status08')
    raw = bytes.fromhex('C22A87')
    display = 'PCBU'[raw[0] >> 6] + f'{(raw[0] >> 4) & 3:X}{raw[0] & 15:X}{raw[1]:02X}-{raw[2]:02X}'
    check(display == report['selectedPrototypeCode']['displayFormat00'] == 'U022A-87', 'format00 notation')
    check(0x09 & 0x20 == 0, 'later availability cannot establish bit5 clearance')
    check(report['result']['newQualifiedDtcDefinitions'] == report['result']['newQualifiedChildRoles'] == report['result']['newWarningDtcLinks'] == 0, 'gaps stay explicit')
    print(f'PASS: {checks} offline evidence checks; no device access; raw XML deliberately unavailable')


if __name__ == '__main__':
    main()
