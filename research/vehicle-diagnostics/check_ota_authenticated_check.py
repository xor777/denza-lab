#!/usr/bin/env python3
"""Offline verification of unchanged ECU request and repeated111 after fresh login."""
import json
from pathlib import Path
import sys

from check_ota_core_fields import ROOT, references, read
from check_ota_http_status_catalog import cleanup, health
sys.path.insert(0,str(ROOT/'tools/vehicle-diagnostics'))
import ota_authenticated_check as reader

ARTIFACT=ROOT/'research/vehicle-diagnostics/ota-authenticated-check-20261021.json'


def main():
    evidence=json.loads(ARTIFACT.read_text());count=len(list(references(evidence)))
    manifest=read(evidence['sources']['build']);base=(ROOT/evidence['sources']['build']['path']).parent
    assert manifest['plannerSha256']==reader.catalog.qualified.sha(Path(reader.__file__))
    for row in manifest['sources']:assert reader.catalog.qualified.sha(base/row['path'])==row['sha256']
    assert reader.catalog.qualified.sha(base/'dex/classes.dex')==manifest['artifactSha256']
    for name,digest in reader.PINNED.items():
        path=reader.catalog.SOURCE/name;assert reader.catalog.qualified.sha(path)==digest
        assert reader.derived(name,path.read_text())==(base/'source'/name).read_text()
    for name in ['OtaLoginInputs.java','OtaLoginProtocol.java','OtaLoginProtocolTest.java','OtaTelephonyBootstrap.java']:
        assert (base/'source'/name).read_bytes()==(ROOT/'captures/ota-login-catalog-20261021/build-v6/source'/name).read_bytes()
    assert manifest['cloudRequestLimit']==2 and manifest['ecuCalls']==0 and manifest['ecuFieldsChanged'] is False
    assert not any(manifest[k] for k in ['nativeOtaInvoked','registration','tokenPersistence','downloadOrInstall'])
    session=read(evidence['session']);cleanup(session);health(evidence)
    assert session['status']=='complete' and session['goSent'] and not session.get('error')
    assert session['artifactSha256']==manifest['artifactSha256']
    assert session['snapshot']==read(evidence['snapshot'])
    assert session['snapshot']['cache']['sha256']==manifest['cacheSha256']
    prior=read(evidence['sources']['priorResponse'])
    assert session['snapshot']['cache']==prior['snapshot']['cache']
    assert session['response']==prior['response']==evidence['response']
    response=session['response'];assert response['status']=='111' and response['causeKind']=='ecu_strategy_mismatch'
    assert response['sha256']=='41204bab426485f1eb02993605e23a7374d461506b570874350a3399c18fd940'
    assert response['strategy']=={'batchId':'SALE-BYDHTH-2021-BATCH-00004','sequence':[]}
    assert all(response[k]==[] for k in ['sourcePackageInfo','targetPackageInfo','configSourceInfo','configPackageInfo'])
    print(json.dumps({'passed':True,'referencesVerified':count,'freshLoginThenCheck':True,'identicalResponse111':True,
                      'unchangedCacheRecords':len(session['snapshot']['cache']['ecuInfo']),
                      'cleanupVerified':True,'vehicleOrNetworkAccess':False}))


if __name__=='__main__':main()
