import struct,json,bz2,lzma,hashlib,bisect
from collections import Counter,OrderedDict
from read_android import AndroidReader,BASE
import zipfile

def varint(b,p):
 v=0
 for shift in range(0,70,7):
  x=b[p];p+=1;v|=(x&127)<<shift
  if x<128:return v,p
 raise ValueError('varint overflow')
def fields(b):
 out={};p=0
 while p<len(b):
  tag,p=varint(b,p);n,w=tag>>3,tag&7
  if w==0:v,p=varint(b,p)
  elif w==2:
   size,p=varint(b,p);v=b[p:p+size];p+=size
  elif w in (1,5):
   size=8 if w==1 else 4;v=b[p:p+size];p+=size
  else:raise ValueError(w)
  assert p<=len(b);out.setdefault(n,[]).append(v)
 return out
def one(f,n,d=None):return f.get(n,[d])[0]
class Payload:
 def __init__(self):
  self.reader=AndroidReader()
  with zipfile.ZipFile(self.reader) as z:i=z.getinfo('payload.bin')
  assert i.compress_type==0;self.reader.seek(i.header_offset);h=self.reader.read(30)
  n,x=struct.unpack_from('<HH',h,26);self.offset=i.header_offset+30+n+x
  self.reader.seek(self.offset);h=self.reader.read(24)
  magic,version,length,sig=struct.unpack('>4sQQI',h);assert magic==b'CrAU' and version==2
  data=self.reader.read(length);self.manifest=fields(data);self.blob=self.offset+24+length+sig
  self.block=one(self.manifest,3,4096);self.parts={}
  for p in self.manifest[13]:
   p=fields(p);self.parts[one(p,1).decode()]=p
 def read(self,offset,length):
  self.reader.seek(self.blob+offset);return self.reader.read(length)
 def summary(self):
  return {'block_size':self.block,'minor_version':one(self.manifest,12,0),'partitions':[{'name':n,'size':one(fields(one(p,7)),1),'sha256':one(fields(one(p,7)),2).hex(),'operation_types':dict(Counter(one(fields(o),1) for o in p[8]))} for n,p in self.parts.items()]}
class Partition:
 def __init__(self,payload,name):
  self.p=payload;self.name=name;self.meta=payload.parts[name];self.ops=[fields(o) for o in self.meta[8]]
  self.size=one(fields(one(self.meta,7)),1);self.ext=[];self.cache=OrderedDict();self.verified=set();self.compressed_bytes=0
  for i,o in enumerate(self.ops):
   source=0
   for e in o[6]:
    e=fields(e);start=one(e,1)*self.p.block;length=one(e,2)*self.p.block
    self.ext.append((start,start+length,i,source));source+=length
  self.ext.sort();self.starts=[e[0] for e in self.ext]
 def decode(self,i):
  if i in self.cache:self.cache.move_to_end(i);return self.cache[i]
  o=self.ops[i];kind=one(o,1);length=sum(one(fields(e),2)*self.p.block for e in o[6]);assert length<=16*1024*1024
  if kind==6:data=bytes(length)
  else:
   assert kind in (0,1,8),('unsupported non-replacement operation',kind)
   data=self.p.read(one(o,2,0),one(o,3));self.compressed_bytes+=len(data)
   assert hashlib.sha256(data).digest()==one(o,8);self.verified.add(i)
   data=data if kind==0 else bz2.decompress(data) if kind==1 else lzma.decompress(data)
   assert len(data)==length
  self.cache[i]=data
  while len(self.cache)>24:self.cache.popitem(last=False)
  return data
 def read(self,offset,length):
  assert 0<=offset<=offset+length<=self.size and length<=16*1024*1024
  out=bytearray()
  while length:
   i=bisect.bisect_right(self.starts,offset)-1;assert i>=0
   start,end,op,source=self.ext[i];assert start<=offset<end
   n=min(length,end-offset);out.extend(self.decode(op)[source+offset-start:source+offset-start+n]);offset+=n;length-=n
  return bytes(out)
if __name__=='__main__':
 p=Payload();report=p.summary();(BASE/'current-partition-index.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
 for name in ['system','vendor','system_ext','product']:
  if name not in p.parts:continue
  part=Partition(p,name);h=part.read(0,2048)
  print(name,'EXT4',h[1080:1082].hex(),'EROFS',h[1024:1028].hex(),'compressed_bytes',part.compressed_bytes)
