#!/usr/bin/env python3
"""Offline verification of listener evidence, user0 readiness and complete catalog."""
import json
from pathlib import Path
import sys

from check_ota_core_fields import ROOT, references, read
from check_ota_http_status_catalog import cleanup, health
sys.path.insert(0, str(ROOT/'tools/vehicle-diagnostics'))
import ota_catalog_listener_trace as current

ARTIFACT = ROOT/'research/vehicle-diagnostics/ota-catalog-version-fields-20261021.json'


def held_socket(ref):
    found = []
    for line in (ROOT/ref['path']).read_text().splitlines():
        fields = line.split()
        if len(fields) < 10 or ':' not in fields[1]:
            continue
        if int(fields[1].rsplit(':', 1)[1], 16) == 46322 and fields[3] == '01':
            found.append((int(fields[7]), int(fields[2].rsplit(':', 1)[1], 16), fields[9]))
    assert len(found) == 1
    return found[0]


def main():
    evidence = read({'path': str(ARTIFACT.relative_to(ROOT))})
    count = len(list(references(evidence)))
    assert current.catalog.qualified.sha(current.TEMPLATE/'build.json') == current.TEMPLATE_SHA
    parent = json.loads((current.TEMPLATE/'build.json').read_text())
    current.versions.verify(current.TEMPLATE, parent)
    for i, run in enumerate(evidence['runs']):
        manifest = read(run['build']); base = (ROOT/run['build']['path']).parent
        current.versions.verify(base, manifest)
        assert manifest['artifactSha256'] == parent['artifactSha256']
        assert manifest['dexChanged'] is False and manifest['extraVehicleCalls'] == 0
        snapshot = current.catalog.load(base/'listener-trace-reader.py', 'listener_snapshot_'+str(i))
        assert current.catalog.qualified.sha(base/'listener-trace-reader.py') == manifest['listenerTraceReaderSha256']
        for row in parent['sources']:
            old = current.TEMPLATE/row['path']; new = base/row['path']
            if old.name == 'run_ecu_map_metadata.py':
                expected = snapshot.derived(old.read_text(), True) if i else snapshot.derived(old.read_text())
                assert expected == new.read_text()
            else:
                assert old.read_bytes() == new.read_bytes(), row['path']
        session = read(run['session']); cleanup(session); health(run)
        assert session['artifactSha256'] == manifest['artifactSha256']
        assert session['helperSourceSha256'] == current.catalog.qualified.sha(base/'source/EcuMapMetadataProbe.java')
        assert session['orchestratorSha256'] == current.catalog.qualified.sha(base/'source/run_ecu_map_metadata.py')
        trace = session['listenerTrace']
        assert trace[0]['phase'] == 'preflight' and trace[0]['ports'] == []
        starts = [r for r in trace if r['phase'] == 'start']
        assert starts and all(r['ports'] == [46320, 46321] for r in starts)
        assert all(r['ports'] == [] for r in trace if r['phase'] == 'close')
        assert [r['elapsedMs'] for r in trace] == sorted(r['elapsedMs'] for r in trace)
        before = held_socket(run['preSockets']); after = held_socket(run['postSockets'])
        assert before == after and before[:2] == (10151, 5228)
        if i == 0:
            assert session['status'] == 'refused' and session['goSent'] is False
            assert session['error'] == 'listeners_not_ready' and 'result' not in session
            assert starts[-1]['elapsedMs']-starts[0]['elapsedMs'] >= 2000
        else:
            assert manifest['user0Readiness'] is True
            assert session['status'] == 'complete' and session['goSent'] and not session.get('error')
            rows = session['result']['ecus']
    assert rows == read(evidence['sources']['priorCatalog'])['result']['ecus'] and len(rows) == 225
    for field, counts in evidence['fieldPresence'].items():
        assert counts == dict(absent=225, null=0, empty=0, nonempty=0)
        assert all(field not in row for row in rows)
    owner = read(evidence['sources']['portOwner'])
    assert owner['uid'] == 10151 and owner['package'] == 'app.revanced.android.gms' and owner['exitCode'] == 0
    server = (ROOT/evidence['sources']['stockExportServer']['path']).read_text()
    assert 'new ServerSocket(this.port)' in server
    assert 'new FileTransferServer(ZmqConfig.getFileTransferPortUser10())' in server
    helper = (base/'source/EcuMapMetadataProbe.java').read_text()
    assert 'EXPORT_PORT = 46321' in helper and 'EXPORT_PORT)' in helper
    assert 'for(String field:FIELDS) if(row.containsKey(field))' in helper
    print(json.dumps({'passed': True, 'referencesVerified': count, 'sessionsClosed': 2,
                      'catalogRecords': len(rows), 'allSixFieldsAbsent': True,
                      'unrelatedConnectionPreserved': True, 'vehicleOrNetworkAccess': False}))


if __name__ == '__main__':
    main()
