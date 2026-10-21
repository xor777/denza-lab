#!/usr/bin/env python3
"""Offline verification of projection changes, closed attempts and truncated log."""
import json
from pathlib import Path
import sys

from check_ota_core_fields import ROOT, references, read
from check_ota_http_status_catalog import cleanup, health
sys.path.insert(0, str(ROOT/'tools/vehicle-diagnostics'))
import ota_catalog_versions as reader

ARTIFACT = ROOT/'research/vehicle-diagnostics/ota-catalog-versions-20261021.json'


def main():
    evidence = json.loads(ARTIFACT.read_text())
    count = len(list(references(evidence)))
    manifest = read(evidence['sources']['build'])
    base = (ROOT/evidence['sources']['build']['path']).parent
    assert reader.catalog.qualified.sha(reader.TEMPLATE/'build.json') == reader.TEMPLATE_SHA
    parent = json.loads((reader.TEMPLATE/'build.json').read_text())
    reader.verify(reader.TEMPLATE, parent)
    reader.verify(base, manifest)
    assert manifest['versionsReaderSha256'] == reader.catalog.qualified.sha(Path(reader.__file__))
    for row in parent['sources']:
        old = reader.TEMPLATE/row['path']
        new = base/row['path']
        if old.name in ['EcuMapMetadataProbe.java', 'run_ecu_map_metadata.py']:
            assert reader.derived(old.read_text()) == new.read_text()
        elif old.name == 'test_run_ecu_map_metadata.py':
            assert reader.login.exact.transform(old.read_text(), reader.TEST_OLD, reader.TEST_NEW) == new.read_text()
        else:
            assert old.read_bytes() == new.read_bytes(), row['path']
    assert manifest['cloudRequestLimit'] == 2 and manifest['ecuCalls'] == 0
    assert not any(manifest[k] for k in ['apkInstalls', 'downloadOrInstall', 'tokenPersistence', 'nativeLoginManager'])
    for run in evidence['runs']:
        session = read(run['session'])
        cleanup(session); health(run)
        assert session['status'] == 'refused' and session['goSent'] is False
        assert session['error'] == 'listeners_not_ready' and 'result' not in session
        assert session['artifactSha256'] == manifest['artifactSha256']
        assert session['helperSourceSha256'] == reader.catalog.qualified.sha(base/'source/EcuMapMetadataProbe.java')
        assert session['orchestratorSha256'] == reader.catalog.qualified.sha(base/'source/run_ecu_map_metadata.py')
    partial = evidence['partialStockLog']
    line = (ROOT/evidence['sources']['partialStockLog']['path']).read_text().splitlines()[partial['line']-1]
    assert '解析之前获取服务器端数据' in line
    text = line.split('responseStr :', 1)[1]
    assert text.startswith('{"status":"100","ecuList":[')
    pos = text.index('"ecuList":[')+len('"ecuList":[')
    decoder = json.JSONDecoder(); rows = []
    while True:
        try:
            row, end = decoder.raw_decode(text, pos)
        except json.JSONDecodeError:
            break
        assert isinstance(row, dict) and 'ecuId' in row
        rows.append(row); pos = end
        if pos >= len(text) or text[pos] != ',':
            break
        pos += 1
    assert len(rows) == partial['completeEcuRows'] == 8
    assert text[pos:].startswith('{') and len(text[pos:]) > 0
    assert [sum(field in row for row in rows) for field in partial['fields']] == partial['presenceCounts'] == [0]*6
    reset = (ROOT/evidence['sources']['stockReset']['path']).read_text()
    for field, default in [('ecuSoftCode','DEFAULT_ECU_SOFT_CODE_VALUE'), ('ecuSoftVer','DEFAULT_ECU_SOFT_VERSION_VALUE'),
                           ('ecuHardVer','DEFAULT_ECU_HARD_VERSION_VALUE'), ('ecuConfigCode','DEFAULT_ECU_SOFT_CODE_VALUE'),
                           ('ecuConfigVer','DEFAULT_ECU_SOFT_VERSION_VALUE')]:
        assert f'e.{field} = VehicleOTAUtil.{default};' in reset
    print(json.dumps({'passed': True, 'referencesVerified': count, 'sessionsClosed': 2,
                      'cloudRequests': 0, 'partialStockRows': 8, 'fullCatalogVersionPresenceUnresolved': True,
                      'vehicleOrNetworkAccess': False}))


if __name__ == '__main__':
    main()
