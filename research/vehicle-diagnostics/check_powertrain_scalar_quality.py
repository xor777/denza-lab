#!/usr/bin/env python3
"""Offline byte/record/arithmetic checks; never runs firmware or contacts a car."""
from pathlib import Path
import hashlib,json,math,struct,sys
ROOT=Path(__file__).resolve().parents[2]
RECORD=Path(__file__).with_name('powertrain-scalar-quality-20261021.json')
def require(ok,why):
 if not ok:raise ValueError(why)
def f32(v):return struct.unpack('<f',struct.pack('<f',v))[0]
def signed32(v):return (v&0xffffffff)-0x100000000 if v&0x80000000 else v&0xffffffff
def elfbytes(data,va,n):
 require(data[:6]==b'\x7fELF\x02\x01','ELF64LE required')
 phoff=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54)
 for k in range(count):
  typ,flags,off,address,physical,filesz,memsz,align=struct.unpack_from('<IIQQQQQQ',data,phoff+k*size)
  if typ==1 and address<=va and va+n<=address+filesz:return data[off+va-address:off+va-address+n]
 raise ValueError(('unmapped ELF address',hex(va),n))
def front(wire):
 if (wire&0xffffffff)>20000:return -10014,None
 return 0,f32(f32(signed32(wire)*f32(.1))-1000.0)
def rear(wire):return f32(signed32(wire)*f32(.1))
def gbcurrent(api):return math.trunc(f32(f32(api*10.0)+10000.0))&0xffff
def gbtemp(api):return ((api&0xff)+40)&0xff
def main():
 d=json.loads(RECORD.read_text());require(d['newEngineeringUnitsProved']==0,'unit boundary')
 blobs={}
 for s in d['sources']:
  p=ROOT/s['path'];b=p.read_bytes();require(hashlib.sha256(b).hexdigest()==s['sha256'],'source hash: '+s['path']);require(len(b)==s['bytes'],'source length');blobs[s['path']]=b
 for a in d['instructionAnchors']:
  require(elfbytes(blobs[a['image']],int(a['va'],16),len(bytes.fromhex(a['bytes']))).hex()==a['bytes'],'instruction bytes '+a['va'])
 m=blobs['captures/firmware/ivi-34.1.33.2605218.1/mcu/app.bin'];sweep=blobs['captures/diagnostics-sweep-20261020/fids.tsv'].decode().splitlines();desc_count=0;obs_count=0
 for r in d['records']:
  require(r['engineeringUnit'] is None and r['invalidRawDomainFullyQualified'] is False,'unproved field contract')
  for x in r['mcu']:
   a=int(x['descriptorVA'],16)-0x40000;p=int(x['propertyRecordVA'],16)-0x40000
   require(m[a:a+12].hex()==x['descriptorBytes'] and m[p:p+12].hex()==x['propertyRecordBytes'],'MCU record bytes')
   require(int.from_bytes(m[p:p+4],'little')==int(r['fid'],16),'MCU exact property')
   require(int.from_bytes(m[int(x['tableVA'],16)-0x40000:int(x['tableVA'],16)-0x40000+4],'little')==int(x['lookupKey'],16),'MCU source table key')
   desc_count+=1
  for o in r['retainedScalarObservations']:
   parts=sweep[o['sourceLine']-1].split('\t');require(parts[0]=='FID' and int(parts[1])==o['pass'] and int(parts[3])==r['device'] and int(parts[4],16)==int(r['fid'],16) and parts[5]==o['int_word'],'retained scalar replay')
   if 'float_word' in o:require(parts[6]==o['float_word'],'retained float bits')
   obs_count+=1
 require(desc_count==12 and obs_count==26,'bounded replay completeness')
 h=blobs['captures/firmware/ivi-34.1.33.2605218.1/system/system/lib64/hw/auto.default.so']
 require(elfbytes(h,0x42750,8).hex()=='01000000ffff0000','integer initial tag/value')
 require(struct.unpack('<f',bytes.fromhex('00ff7f47'))[0]==65535.0,'float initial value')
 # Exhaust the producer-width domains, including boundaries that are not live data.
 statuses={};front_values=[]
 for raw in range(65536):
  status,api=front(raw);statuses[status]=statuses.get(status,0)+1
  if raw<=20000:
   require(status==0 and api is not None and -1000<=api<=1000,'front accepted domain');front_values.append(api)
  else:require(status==-10014 and api is None,'front unsigned rejection')
 for raw in [-1,-2147483648,20001,0x7fffffff,0xffffffff]:require(front(raw)==(-10014,None),'front signed/unsigned boundary')
 require(statuses=={0:20001,-10014:45535},'front full u16 counts')
 require(front(9990)==(0,-1.0) and front(10000)==(0,0.0),'float value collision and zero')
 for raw in range(65536):
  api=rear(raw);require(0<=api<=6553.5,'rear unsigned producer domain')
  require(gbcurrent(api)==((raw+10000)&65535),'rear API-to-GB modular packing')
 for raw in range(256):require(gbtemp(raw-40)==raw,'temperature exact byte restoration')
 require(gbtemp(65535)==39 and gbcurrent(65535.0)==9990 and gbcurrent(rear(65535))==9999,'placeholder/word distinctions')
 prior=0.0;status,new=front(65535);after=prior if status else new
 require(status==-10014 and after==0.0,'rejected update preserves prior value model')
 result={'offlineOnly':True,'vehicleOperations':0,'networkOperations':0,'sourceHashChecks':len(d['sources']),'instructionByteAnchors':len(d['instructionAnchors']),'mcuDescriptorRecordPairs':desc_count,'retainedObservationsReplayed':obs_count,'frontUnsigned16InputsChecked':65536,'frontAccepted':statuses[0],'frontRejected':statuses[-10014],'rearUnsigned16PackingInputsChecked':65536,'temperatureByteInputsChecked':256,'minusOneCollisionVerified':True,'initialPlaceholdersNarrowToCurrent9990AndTemp39':True,'newEngineeringUnitsProved':0,'scope':'Independent bounded arithmetic model plus pinned bytes/records; no MCU/HAL emulation or new live acceptance.'}
 print(json.dumps(result,indent=2))
if __name__=='__main__':main()
