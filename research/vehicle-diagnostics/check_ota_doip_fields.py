#!/usr/bin/env python3
"""Offline fixed-wire validation and replay of exact stock DoIP parsers."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile

from check_ota_core_fields import ROOT, JAVA, references, read, pids
sys.path.insert(0,str(ROOT/'tools/vehicle-diagnostics'))
import ota_doip_field_read as reader

ARTIFACT=ROOT/'research/vehicle-diagnostics/ota-doip-fields-live-20261021.json'


def method(source,signature):
    start=source.index(signature);left=source.index('{',start);depth=1;end=left+1
    while depth:
        if source[end]=='{':depth+=1
        elif source[end]=='}':depth-=1
        end+=1
    return source[start:end]


def replay(evidence,values):
    stock=(ROOT/evidence['sources']['stockDoipReader']['path']).read_text()
    utility=(ROOT/evidence['sources']['stockVersionFormatter']['path']).read_text()
    source='''import java.util.*;
class UByte { static final int MAX_VALUE=255; }
class TextUtils { static boolean isEmpty(String s){return s==null || s.isEmpty();} }
class LogUtil {static void i(String... ignored){} }
class FormatConversionUtil {static String bytesToHexString(byte[] b){StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();} }
class VehicleOTAUtil {static final String DEFAULT_ECU_SOFT_CODE_VALUE="000000000000000000";
'''+method(utility,'    public static int byteToInt(')+method(utility,'    public static String getCurrentEcuSoftVersion(')+'''}
public class DoipStockReplay {
 static final String TAG="offline";
 static class DiagnoseFailedException extends Exception {DiagnoseFailedException(String message){super(message);} }
 static void printBytesArray(byte[] ignored){}
'''+method(stock,'    private static String parseEcuVersion(')+method(stock,'    private static String parseEcuSoftCode(')+'''
 public static void main(String[] args)throws Exception {
  for(int i=0;i<args.length;i+=3){String hex=args[i+1];byte[] b=new byte[hex.length()/2];for(int j=0;j<b.length;j++)b[j]=(byte)Integer.parseInt(hex.substring(j*2,j*2+2),16);
   String actual=args[i].equals("ecuSoftCode")?parseEcuSoftCode(b):parseEcuVersion(b);
   if(!args[i+2].equals(actual))throw new AssertionError(actual);
  }
  System.out.println(args.length/3);
 }
}
'''
    with tempfile.TemporaryDirectory(prefix='ota-doip-stock-replay-') as directory:
        path=Path(directory)/'DoipStockReplay.java';path.write_text(source)
        subprocess.run([str(JAVA/'javac'),'--release','8','-d',directory,str(path)],check=True,capture_output=True,timeout=30)
        args=[item for value in values for item in [value['field'],value['observation']['uds'][6:],value['value']]]
        result=subprocess.run([str(JAVA/'java'),'-cp',directory,'DoipStockReplay',*args],check=True,capture_output=True,text=True,timeout=10)
        assert int(result.stdout)==len(values)


def main():
    evidence=json.loads(ARTIFACT.read_text());count=len(list(references(evidence)))
    manifest=read(evidence['sources']['build']);base=(ROOT/evidence['sources']['build']['path']).parent
    assert manifest['plannerSha256']==reader.qualified.sha(Path(reader.__file__))
    for row in manifest['sources']:assert reader.qualified.sha(base/row['path'])==row['sha256']
    assert reader.owner_source()==(base/'snapshot-owner.py').read_text()
    assert manifest['plans']==reader.plans(reader.qualified.baseline())
    assert manifest['cloudRequests']==0 and manifest['connectionsPerRun']==1 and manifest['requestLimitPerRun']==4
    assert manifest['globalDeadlineSeconds']==18 and manifest['hardProcessDeadlineSeconds']==20
    positive=[];negative=[];sent=0;accepted=0
    for run in evidence['runs']:
        name=run['group'];snapshot=read(run['snapshot']);value=snapshot['values'];plan=manifest['plans'][name]
        assert snapshot['exitCode']==0 and snapshot['cloudRequests']==0 and snapshot['apkInstalled'] is False
        assert all(snapshot[k] for k in ['jarRemoved','processAbsent','healthStable'])
        before,after=read(run['preflight']),read(run['postflight'])
        for h in [before,after]:
            assert all(h[k] for k in ['guardPass','firmwareHashMatch','crashEmpty','stockPresent','exportIdle'])
            assert not h['activeResearchProcesses']
        assert before['appSha256']==after['appSha256'] and pids(run['preProcesses'])==pids(run['postProcesses'])
        stdout=(ROOT/run['stdout']['path']).read_text().strip()
        assert json.loads(stdout.removeprefix('DENZA_OTA_DOIP_READ:'))==value
        reader.validate(value,plan,name);comparison=read(run['comparison'])
        assert reader.compare(value,plan)==comparison['values']
        sent+=max(0,len(value['sent'])-1);accepted+=int(value.get('routingAccepted') is True)
        for row in comparison['values']:
            if row['observation'].get('outcome')=='positive':
                assert row['stockParseValid'] and row['cacheMatch'];positive.append(dict(row,group=name))
            elif row['observation'].get('outcome')=='negative':negative.append(dict(row,group=name))
        if name=='cdc':assert value['error']=='uds_negative' and value['exchanges'][-1]['uds']=='7F2210'
        elif name=='lidar-middle':assert value['error']=='socket_timeout' and value['sent']==[] and value['exchanges']==[]
        else:assert value['outcome']=='complete'
    assert sent==9 and accepted==4 and len(positive)==8 and len(negative)==1
    assert positive==evidence['positiveComparisons'] and negative==evidence['negativeComparisons']
    replay(evidence,positive)
    print(json.dumps({'passed':True,'referencesVerified':count,'sessionsClosed':5,'udsReadRequests':9,
                      'exactStockParserMatches':8,'negativeCode':'7F2210','middleLidar':'no_UDS_sent',
                      'vehicleOrNetworkAccess':False}))


if __name__=='__main__':main()
