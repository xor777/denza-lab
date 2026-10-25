"""Bounded serializer glue: original output remains independent and immutable."""
import copy
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from . import phase1_native as native


def varint(n):
    result=bytearray()
    while n>127:result.append((n&127)|128);n>>=7
    result.append(n);return bytes(result)


def reference(path):
    # Literal reference fixture; never constructed by NativeRouteSerializer.
    message='{"code":200,"data":['+path+']}'
    detail=json.dumps(dict(currentIndex=0,length=1,pathCount=1,payload=message,time=123)).encode()
    body=b'\x18\x01\x22'+varint(len(detail))+detail
    return dict(kind='fire',topic=native.TOPIC,seq=8,payloadHex=(b'\x0a'+varint(len(body))+body).hex())


class Phase1NativeTests(unittest.TestCase):
    def test_exact_original_path_bytes_not_reference_clock_or_legacy_output(self):
        inputs=[{},dict(event='route',seq=3,data={'path':{'pathId':'77'}})]
        original='{"pathID":77,"links":[{"laneNum":4}]}'
        refs=[{},reference(original)]
        computed=[dict(input_seq=3,path_json=original)]
        result=native.compare_paths(inputs,refs,computed)
        self.assertEqual(result['path_serialization_verdict'],'EQUAL')
        self.assertFalse(result['equivalenceEstablished'])
        self.assertEqual(result['full_sender'],'UNSUPPORTED_PHASE1_MISSING_CONSUMED_READS')
        changed=copy.deepcopy(computed);changed[0]['path_json']=original.replace('4','0')
        report=native.compare_paths(inputs,refs,changed)
        self.assertEqual(report['path_serialization_verdict'],'DIFFERENT')
        self.assertEqual(report['comparisons'][0]['field_differences'],[dict(path='$.links[0].laneNum',original=4,computed=0)])
        # Equal objects with changed field order are still different bytes.
        changed[0]['path_json']='{"links":[{"laneNum":4}],"pathID":77}'
        self.assertEqual(native.compare_paths(inputs,refs,changed)['path_serialization_verdict'],'DIFFERENT')
        self.assertEqual(refs[1],reference(original))

    def test_missing_alignment_and_multipart_reference_are_rejected(self):
        with self.assertRaisesRegex(ValueError,'counts differ'):
            native.compare_paths([{},dict(event='route',seq=3,data={'path':{'pathId':'77'}})],[{}],[])
        packet=reference('{"pathID":77}')
        packet['payloadHex']=packet['payloadHex']+'00'
        with self.assertRaisesRegex(ValueError,'wrapper'):native.route_path_bytes(packet)

    def test_real_native_serializer_consumes_snapshot_and_never_defaults_missing_getters(self):
        link=dict(topoId='5121370766698348571',roadClass=0,roadName='actual native road',length=10,linkType=0,formway=1,
            hasTrafficLight=False,laneNum=4,hasMultiOut=False,mainAction=0,assistantAction=0,adcode='110102',ownership=0,
            hasParallel=False,direction=1,hasMixFork=False,urid=1,lon=[3600000],lat=[7200000])
        path=dict(pathId='77',groups=[],viaRoadInfo=[],trafficLights=[],segments=[dict(segmentIndex=0,length=10,
            travelTime=2,mainAction=0,assistantAction=0,trafficLightNum=0,links=[link])])
        prepared=dict(routes=[dict(seq=3,path=path)],strings={name:name for name in native.RESOURCE_NAMES})
        sources=sorted(p for p in (native.ROOT/'libraries/adas-link/src/main/java').rglob('*.java') if 'import android.'not in p.read_text())
        runtime=native.bench.jdk()
        with tempfile.TemporaryDirectory()as directory:
            root=Path(directory);data=root/'input.json'
            compile=subprocess.run([str(runtime/'javac'),'--release','8','-nowarn','-d',directory,*map(str,sources),str(native.JAVA)],capture_output=True,text=True,timeout=60)
            self.assertEqual(compile.returncode,0,compile.stderr)
            def execute():
                data.write_text(json.dumps(prepared))
                return subprocess.run([str(runtime/'java'),'-cp',directory,'dev.denza.adaslink.stocknative.Phase1NativeSerializerReplay',str(data)],capture_output=True,text=True,timeout=10)
            result=execute();self.assertEqual(result.returncode,0,result.stderr)
            actual=json.loads(json.loads(result.stdout)['path_json'])
            self.assertEqual(actual['pathID'],77)
            self.assertEqual(actual['links'][0]['linkID'],5121370766698348571)
            self.assertEqual(actual['links'][0]['laneNum'],4)
            del link['laneNum']
            result=execute();self.assertNotEqual(result.returncode,0)
            self.assertIn('missing phase1 getter laneNum',result.stderr)


if __name__=='__main__':unittest.main()
