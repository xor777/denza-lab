#!/usr/bin/env python3
"""Offline exact-source, status and cleanup verification for the single catalog read."""
import json
from pathlib import Path
import sys

from check_ota_core_fields import ROOT, references, read, pids
sys.path.insert(0, str(ROOT/'tools/vehicle-diagnostics'))
import ota_http_status_catalog as exact

ARTIFACT = ROOT/'research/vehicle-diagnostics/ota-http-status-catalog-20261021.json'


def health(evidence):
    before, after = read(evidence['preflight']), read(evidence['postflight'])
    for value in [before, after]:
        assert value['state'] == 'read_complete'
        assert all(value[k] for k in ['guardPass','firmwareHashMatch','crashEmpty','stockPresent','exportIdle'])
        assert not value['activeResearchProcesses']
    assert before['appSha256'] == after['appSha256']
    assert pids(evidence['preProcesses']) == pids(evidence['postProcesses'])
    assert read(evidence['healthComparison'])['stable']


def cleanup(session):
    assert all(session[k] for k in ['exportClosed','watchdogTerminal','leaseReleased','postApkIdentityVerified'])
    assert not session.get('helperCleanupUnverified') and not session.get('cleanupError')


def main():
    evidence = json.loads(ARTIFACT.read_text()); count = len(list(references(evidence)))
    build = read(evidence['sources']['build']); base = (ROOT/evidence['sources']['build']['path']).parent
    assert exact.catalog.qualified.sha(Path(exact.__file__)) == build['statusReaderSha256']
    assert exact.catalog.qualified.sha(base/'dex/classes.dex') == build['artifactSha256']
    for row in build['sources']: assert exact.catalog.qualified.sha(base/row['path']) == row['sha256']
    for name, digest in exact.catalog.PINNED.items():
        path = exact.catalog.SOURCE/name; assert exact.catalog.qualified.sha(path) == digest
        text = path.read_text()
        if name in ['EcuMapMetadataProbe.java','run_ecu_map_metadata.py']:
            anchor = '"logicAddress", "ecuDId", "ecuHardDid", "ecuConfigDid", "seq"'
            text = exact.transform(text, anchor, anchor+', "ecuIp"')
        if name == 'EcuMapMetadataProbe.java': text = exact.transform(text, exact.JAVA_OLD, exact.JAVA_NEW)
        if name == 'run_ecu_map_metadata.py': text = exact.transform(text, exact.HOST_OLD, exact.HOST_NEW)
        assert (base/'source'/name).read_text() == text
    session = read(evidence['session'])
    assert session['status'] == 'refused' and session['goSent'] and 'result' not in session
    assert session['error'] == 'helper_http_401'
    assert session['helperError'] == {'stage':'https','code':'http_401'}
    assert session['artifactSha256'] == build['artifactSha256']
    assert session['helperSourceSha256'] == exact.catalog.qualified.sha(base/'source/EcuMapMetadataProbe.java')
    assert session['orchestratorSha256'] == exact.catalog.qualified.sha(base/'source/run_ecu_map_metadata.py')
    assert build['cloudRequestLimit'] == 1 and build['ecuCalls'] == 0 and build['loginOrRegistration'] is False
    cleanup(session); health(evidence)
    assert evidence['summary']['httpStatus'] == 401
    print(json.dumps({'passed':True,'referencesVerified':count,'httpStatus':401,
                      'exactDerivedSources':True,'cleanupVerified':True,'vehicleOrNetworkAccess':False}))


if __name__ == '__main__': main()
