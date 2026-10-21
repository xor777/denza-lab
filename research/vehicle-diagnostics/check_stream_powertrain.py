#!/usr/bin/env python3
"""Replay the bounded powertrain matrix, original bytes and retained callbacks."""
import collections
import gzip
import hashlib
import json
from pathlib import Path
import re
import struct

ROOT=Path(__file__).resolve().parents[2]
REPORT=Path(__file__).with_name('stream-powertrain-20261021.json')


def require(ok,why):
    if not ok:raise AssertionError(why)


def read(path):
    data=(ROOT/path).read_bytes()
    return json.loads(gzip.decompress(data) if str(path).endswith('.gz') else data)


def elf_bytes(data,va,n):
    require(data[:6]==b'\x7fELF\x02\x01','ELF64 LE')
    require(struct.unpack_from('<H',data,18)[0]==183,'AArch64')
    phoff=struct.unpack_from('<Q',data,32)[0]
    entsize,count=struct.unpack_from('<HH',data,54)
    for i in range(count):
        typ,_,off,addr,_,size,_,_=struct.unpack_from('<IIQQQQQQ',data,phoff+i*entsize)
        if typ==1 and addr<=va and va+n<=addr+size:
            return off+va-addr,data[off+va-addr:off+va-addr+n]
    raise AssertionError(('unmapped VA',hex(va),n))


def f32(v):return struct.unpack('<f',struct.pack('<f',v))[0]


def normalized(base,name):
    return read(base+'/'+name+'.json.gz')['result']['structuredContent']['normalized_result']


def main():
    d=json.loads(REPORT.read_text())
    require(d['schema']=='denza-lab.stream-powertrain.v1','schema')
    require(all(d['scope'][k]==0 for k in ['vehicleOperations','ADBOperations','networkOperations','firmwareExecutions']),'offline boundary')
    require(d['scope']['offlineOnly'] and not d['scope']['productChanges'] and not d['scope']['sharedDocChanges'],'scope')
    blobs={}
    for source in d['sources']:
        raw=(ROOT/source['path']).read_bytes()
        require(len(raw)==source['bytes'] and hashlib.sha256(raw).hexdigest()==source['sha256'],('source identity',source['path']))
        blobs[source['path']]=raw
    fields=d['logicalFields'];direct=d['directObdFields'];routes=d['ecuAvailability']
    require(len(fields)==303 and len(direct)==52 and len(routes)==11,'bounded projection sizes')
    require(len({(r['name'],r['device'],r['nativeFid']) for r in fields})==len(fields),'unique field identities')
    sweep=blobs['captures/diagnostics-sweep-20261020/fids.tsv'].decode().splitlines()
    count=0
    for r in fields:
        require(r['labelRu'] and r['groupRu'] and r['implementationReadinessRu'],'human readable fields')
        require(r['physicalEcu'] is None,'no invented physical ECU')
        if not r['nativeFid']:require('readCommandTemplate' not in r,'unresolved property cannot be read as native FID')
        for o in r['observations']:
            p=sweep[o['sourceLine']-1].split('\t')
            require(p[0]=='FID' and int(p[1])==o['pass'] and int(p[2])==o['arrivalNs'] and int(p[3])==r['device'] and p[4]==r['nativeFid'] and p[5]==o['intWord'] and (p[6] or None)==o['floatWord'],'retained scalar exact row')
            count+=1
        if 'readCommandTemplate' in r:
            native=int(r['nativeFid'],16);signed=native-0x100000000 if native&0x80000000 else native
            require(r['transaction'] in [5,7] and r['readCommandTemplate']==f"service call autoservice {r['transaction']} i32 {r['device']} i32 {signed}",'safe scalar command projection')

    mcu=blobs['captures/firmware/ivi-34.1.33.2605218.1/mcu/app.bin'];mcu_count=0
    for r in fields:
        for x in r.get('mcu',[]):
            descriptor=mcu[int(x['descriptorVA'],16)-0x40000:int(x['descriptorVA'],16)-0x40000+12]
            prop=mcu[int(x['propertyVA'],16)-0x40000:int(x['propertyVA'],16)-0x40000+12]
            require(descriptor.hex()==x['descriptorBytes'] and prop.hex()==x['propertyBytes'],'original MCU records')
            require(list(descriptor[:4])==x['coordinatesOneBasedByteLsb0'],'MCU coordinates')
            require(struct.unpack_from('<II',descriptor,4)==(int(x['handlerVA'],16),int(x['propertyVA'],16)),'MCU handler/property binding')
            require(x['handlerVA']=='0x18a09c','only existing generic unsigned decoder')
            require(struct.unpack_from('<I',prop)[0]==int(x['mcuProperty'],16),'exact MCU property')
            expected=int(r['nativeFid'],16)|(0x800 if r['nativeFid'].startswith('0x36D') else 0)
            require(expected==int(x['mcuProperty'],16),'only qualified36D canonicalization')
            table=int(x['tableVA'],16)-0x40000
            require(struct.unpack_from('<I',mcu,table)[0]==int(x['lookupKey'],16),'MCU source table key')
            key=int(x['lookupKey'],16)
            require(x['channel']==((key>>24)&3) and x['subIdSelector']==(key&255),'preserve channel/sub selector')
            a,b,c,e=x['coordinatesOneBasedByteLsb0'];require(x['unsignedBits']==(c-a)*8+e-b+1,'unsigned width')
            require((x['payloadByteOffset0'],x['lastPayloadByteOffset0'],x['payloadBitOffset0'])==(a-1,c-1,(a-1)*8+b),'one-based MCU coordinates to zero-based array offsets')
            mcu_count+=1
    require(mcu_count==15,'selected MCU descriptor coverage')

    native=read(d['sourceEvidence']['selectedNative']);instructions=0
    for region in native:
        raw=blobs[region['image']];pos,data=elf_bytes(raw,int(region['elfVA'],16),region['size'])
        require(pos==int(region['fileOffset'],16) and data.hex()==region['bytes'],'original native range')
        require(hashlib.sha256(raw).hexdigest()==region['sourceSHA256'],'native source identity')
        cursor=int(region['elfVA'],16)
        for i in region['instructions']:
            require(int(i['va'],16)==cursor,'contiguous instruction coverage')
            _,data=elf_bytes(raw,cursor,4);require(data.hex()==i['bytes'],'native instruction bytes')
            cursor+=4;instructions+=1
        require(cursor==int(region['elfVA'],16)+region['size'],'complete native range')
    require(len(native)==22 and instructions==490,'selected native range scope')

    # Independent finite arithmetic models do not execute firmware or assert units.
    require([2*r-250 for r in [0,125,144,146,255]]==[-250,0,38,42,260],'DC/DC integer conversion')
    require(f32(f32(138)*f32(.1))==13.800000190734863,'DC/DC float32 arithmetic')
    require([r*.5 for r in [0,1,31]]==[0,.5,15.5],'heating power unsigned5 domain')
    require([r<<5 for r in [0,4,5,255]]==[0,128,160,8160],'compressor conversion')
    by={(r['device'],r['nativeFid']):r for r in fields if r['nativeFid']}
    require(by[1006,'0x36D00030']['stockEnum']=={'0':'CLOSE','1':'BOOST','2':'STEP_DOWN'},'DC/DC named API enum')
    require(by[1006,'0x2610001F']['engineeringUnit']=='kW' and 'pack' in by[1012,'0x2ED00010']['unitQualification'],'preserve prior normative energy qualification')
    for dev,fid in [(1006,'0x36D00020'),(1006,'0x36D00028'),(1014,'0x34500008'),(1014,'0x34500018'),(1014,'0x34500028'),(1009,'0x22D0283B'),(1005,'0x3D800038')]:
        require(by[dev,fid]['engineeringUnit'] is None,'do not invent new engineering units')

    groups=read(d['retainedStreams']['artifact']);frame_count=0
    for g in groups:
        lines=blobs[g['source']].decode().splitlines();require(len(g['rows'])==g['count'],'stream count')
        require(g['rows'][-1]['arrivalNs']-g['rows'][0]['arrivalNs']==round(g['spanSeconds']*1e9),'stream span')
        for r in g['rows']:
            line=lines[r['sourceLine']-1]
            require(f"seq={r['sequence']} " in line and f"t_ns={r['arrivalNs']} " in line,'exact callback arrival')
            if ' payload=' in line:
                m=re.search(r'id=0x([0-9A-Fa-f]+) sub=0x([0-9A-Fa-f]+) ch=(\d+) cnt=(\d+) len=(\d+) payload=([0-9a-f]+)',line)
                cid,sub,ch,counter,n,payload=m.groups();require((int(cid,16),int(sub,16),int(ch),int(n),payload)==(int(g['canId'],16),g['subId'],g['channel'],g['payloadBytes'],r['payload']),'exact FRAME tuple')
                require(int(counter)==r['mcuCounterRaw'],'FRAME raw counter')
            else:
                b=bytes.fromhex(line.split(' raw=')[1]);require((int.from_bytes(b[:4],'big'),b[4],b[5],len(b)-10,b[10:].hex())==(int(g['canId'],16),g['subId'],g['channel'],g['payloadBytes'],r['payload']),'exact BUFFER tuple')
                require(int.from_bytes(b[6:10],'big')==r['mcuCounterRaw'],'BUFFER raw counter')
            p=bytes.fromhex(r['payload'])
            if 'derivedApiValues' in r:
                if g['canId']=='0x36D':
                    require(r['derivedApiValues']=={'1006/36D00020':f32(f32(p[3])*f32(.1)),'1006/36D00028':p[4]*2-250,'1006/36D00030':p[5]&3},'DC/DC stream arithmetic')
                elif g['canId']=='0x22D':
                    require(g['subId']==2 and g['channel']==2,'thermal selector2 only')
                    require(r['derivedApiValues']=={'1009/22D02813':(p[1]>>3)&1,'1009/22D02814':p[1]>>4,'1009/22D0283B':(p[6]>>3)*.5},'thermal stream arithmetic')
            frame_count+=1
    require(len(groups)==7 and frame_count==140,'finite retained stream scope')
    require(not any(g['canId'] in ['0x345','0x2D0'] and g['channel']==0 for g in groups),'no absent powertrain raw tuple promoted')

    base=d['sourceEvidence']['reaConsumer'];load=normalized(base,'load-image');batch=normalized(base,'drive-engine-consumer')
    require(load['status']=='unsupported','ELF attestation remains unsupported')
    require(load['observations']['image_base']=='0x100000','measured REA base')
    text=next(x for x in load['observations']['mappings']if x['block']=='.text')
    image=blobs['captures/firmware/ivi-34.1.33.2605218.1/system/system/bin/gbacqservice']
    require(hashlib.sha256(image[text['file_offset']:text['file_offset']+text['length']]).hexdigest()==text['sha256'],'loaded text equals original')
    require(batch['succeeded']==2 and batch['failed']==0 and all(x['procedure']['status']=='resolved' for x in batch['items']),'resolved consumer identities')
    pseudo='\n'.join(x['pseudocode']for x in batch['items'])
    for text in ['0x3ff,DAT_0013e0f4','DAT_0013e0f4 = 0x2d0001d1','DAT_0013e0f8 = 0x2d0001d5','DAT_0013e0fc = 0x2d0001d7']:
        require(text in pseudo,('consumer argument binding',text))
    for name in ['drive-call-bytes','drive-state-jump-tables']:
        r=normalized(base,name);require(r['complete'] and r['requested_bytes']==r['returned_bytes'],'complete provider bytes')
        _,raw=elf_bytes(image,int(r['address'],16)-0x100000,r['returned_bytes']);require(raw.hex()==r['bytes_hex'],'REA rebase byte match')
    # Decode the original jump tables; do not trust the decompiler's missing switch.
    _,raw=elf_bytes(image,0x8274,16)
    require(struct.unpack_from('<4H',raw)==(0x181,0,0x17e,0x180),'front state jump targets')
    require(raw[8:12]==raw[12:16]==bytes([13,0,10,12]),'rear state jump targets')
    expected={'0':3,'1':1,'2':2,'3':4,'254':254,'255':255,'other':254}
    for fid in ['0x2D0001D1','0x2D0001D5','0x2D0001D7']:
        require(by[1023,fid]['apiToGbWireMap']['producerRawToGBOutput']==expected,'encoding only map')
    for directory in ['rea-acq','rea-hal','rea-hal-4g']:
        r=read('captures/stream-powertrain-20261021/'+directory+'/receipt.json.gz')
        require(r['serverExitCode']==0 and r['sha256Before']==r['sha256After'] and not r['scratchEntries'],'owned REA cleanup/source stability')
    for r in direct:
        require(not r['streamQualified'] and r['labelRu'] and r['evidence'],'positive retained OBD evidence, not stream proof')
    print(json.dumps({'offlineOnly':True,'sourceHashes':len(blobs),'logicalFields':len(fields),
       'retainedScalarRows':count,'directObdFields':len(direct),'ecuRoutes':len(routes),
       'mcuDescriptors':mcu_count,'nativeRanges':len(native),'nativeInstructions':instructions,
       'retainedStreamGroups':len(groups),'retainedStreamFrames':frame_count,
       'resolvedReaConsumers':2,'reaOriginalTextAndSelectedBytesMatch':True,
       'priorGenerationAndPackPowerContractPreserved':True,
       'newEngineeringUnitsProved':0,'vehicleOperations':0,'networkOperations':0},indent=2))


if __name__=='__main__':main()
