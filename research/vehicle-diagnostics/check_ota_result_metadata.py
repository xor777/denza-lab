#!/usr/bin/env python3
"""Check retained result-key projection and cleanup; raw XML is not retained."""
import json
import sys
from pathlib import Path

from check_ota_core_fields import ROOT, references, read
from check_ota_media_fields import health
sys.path.insert(0,str(ROOT/'tools/vehicle-diagnostics'))
import read_ota_result_metadata as reader


def main():
    artifact=ROOT/'research/vehicle-diagnostics/ota-result-metadata-20261021.json'
    evidence=json.loads(artifact.read_text());refs=len(list(references(evidence)))
    manifest=read(evidence['sources']['manifest']);refs+=len(list(references(manifest)))
    assert manifest['healthStable'] and manifest['oemRequests']==manifest['ecuCalls']==manifest['apkInstalls']==0
    reader.configure();reader.test();health(evidence)
    session=read(evidence['session'])
    assert session['status']=='complete'
    assert all(session[k] for k in ['exportClosed','watchdogTerminal','leaseReleased','forwardRemoved',
                                  'postApkIdentityVerified','crashBufferUnchanged','postExportPortsAbsent'])
    assert session['preCrashSha256']==session['postCrashSha256']
    assert [r['source'] for r in session['files']]==list(reader.PREFS)
    for row in session['files'][:2]:
        assert row['status']=='read' and row['rawSizeBytes']>0
        assert row['preferences']=={key:{'status':'key_missing'} for key in reader.KEYS}
    assert session['files'][2]['status']=='not_listed'
    print(json.dumps({'passed':True,'referencesVerified':refs,'missingProjectedKeys':6,
                      'productLineFileStatus':'not_listed','cleanupVerified':True,
                      'rawXmlAbsenceReplayed':False,'deviceOrNetworkAccess':False}))


if __name__=='__main__':main()
