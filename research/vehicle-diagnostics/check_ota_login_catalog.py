#!/usr/bin/env python3
"""Offline source-transform, catalog comparison and cleanup verification."""
import json
from pathlib import Path
import sys

from check_ota_core_fields import ROOT, references, read
from check_ota_http_status_catalog import health, cleanup
sys.path.insert(0,str(ROOT/'tools/vehicle-diagnostics'))
import ota_http_status_catalog as exact

ARTIFACT=ROOT/'research/vehicle-diagnostics/ota-login-catalog-20261021.json'


def main():
    evidence=json.loads(ARTIFACT.read_text());count=len(list(references(evidence)))
    for index,run in enumerate(evidence['runs']):
        manifest=read(run['build']);base=(ROOT/run['build']['path']).parent
        for row in manifest['sources']:assert exact.catalog.qualified.sha(base/row['path'])==row['sha256']
        assert exact.catalog.qualified.sha(base/'dex/classes.dex')==manifest['artifactSha256']
        assert exact.catalog.qualified.sha(base/'catalog-reader.py')==manifest['plannerSha256']
        assert exact.catalog.qualified.sha(base/'login-reader.py')==manifest['loginReaderSha256']
        snapshot=exact.catalog.load(base/'login-reader.py','login_verifier_snapshot_'+str(index))
        for name,digest in exact.catalog.PINNED.items():
            path=exact.catalog.SOURCE/name;assert exact.catalog.qualified.sha(path)==digest
            text=path.read_text()
            if name in ['EcuMapMetadataProbe.java','run_ecu_map_metadata.py']:
                anchor='"logicAddress", "ecuDId", "ecuHardDid", "ecuConfigDid", "seq"'
                text=exact.transform(text,anchor,anchor+', "ecuIp"')
            if name=='EcuMapMetadataProbe.java':
                text=exact.transform(text,exact.JAVA_OLD,exact.JAVA_NEW)
                for old,new in snapshot.JAVA_CHANGES:text=exact.transform(text,old,new)
            if name=='run_ecu_map_metadata.py':
                text=exact.transform(text,exact.HOST_OLD,exact.HOST_NEW)
                for old,new in snapshot.HOST_CHANGES:text=exact.transform(text,old,new)
            if name=='test_run_ecu_map_metadata.py':text=exact.transform(text,snapshot.TEST_OLD,snapshot.TEST_NEW)
            assert text==(base/'source'/name).read_text(),(run['name'],name)
        owner=Path(exact.catalog.__file__).read_text().replace("'cloudRequestLimit':1","'cloudRequestLimit':2")
        assert owner==(base/'catalog-reader.py').read_text()
        assert manifest['cloudRequestLimit']==2 and manifest['ecuCalls']==0
        assert manifest['tokenPersistence'] is False and manifest['nativeLoginManager'] is False
        session=read(run['session']);cleanup(session);health(run)
        assert session['artifactSha256']==manifest['artifactSha256']
        assert session['helperSourceSha256']==exact.catalog.qualified.sha(base/'source/EcuMapMetadataProbe.java')
        assert session['orchestratorSha256']==exact.catalog.qualified.sha(base/'source/run_ecu_map_metadata.py')
        if index<3:
            assert session['status']=='refused' and session['goSent'] is False and 'result' not in session
            assert session['helperError']==run['outcome']['helperError']
        else:
            assert session['status']=='complete' and session['goSent'] and not session.get('error')
            final=session['result']['ecus']
    old=read(evidence['priorCatalog'])['ecus']
    fields=['ecuId','ecuDiaId','ecuReprogramType','ecuChannel','networkNumber','logicAddress','ecuDId','ecuHardDid','ecuConfigDid','seq']
    project=lambda rows:{row['ecuId']:{k:row.get(k) for k in fields} for row in rows}
    assert project(old)==project(final) and len(final)==225
    routes=read(evidence['ethernetRoutes'])
    assert len(routes)==8 and all(r['validIpv4'] for r in routes)
    assert {r['ecuIp'] for r in routes}=={'192.168.195.11','192.168.195.5','192.168.195.7','192.168.195.16','192.168.195.3'}
    final_by_id={r['ecuId']:r for r in final}
    for row in routes:
        for field in ['ecuIp','ecuDiaId','logicAddress','ecuDId','ecuHardDid','ecuConfigDid','route']:
            assert row[field]==final_by_id[row['ecuId']].get(field)
    print(json.dumps({'passed':True,'referencesVerified':count,'sessionsClosed':4,'finalLoginAndCatalogSucceeded':True,
                      'catalogRecords':225,'unchangedPriorFields':True,'ethernetRecords':8,'uniqueIps':5,
                      'expectedCampaignVersionsObtained':False,'vehicleOrNetworkAccess':False}))


if __name__=='__main__':main()
