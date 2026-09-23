from current_payload import Payload,Partition,BASE
import struct,json,hashlib,stat

def u16(b,o):return struct.unpack_from('<H',b,o)[0]
def u32(b,o):return struct.unpack_from('<I',b,o)[0]
class Ext4:
 def __init__(self,part):
  self.p=part;s=part.read(1024,1024);assert u16(s,56)==0xef53
  self.bs=1024<<u32(s,24);self.ipg=u32(s,40);self.isize=u16(s,88);inc=u32(s,96)
  assert not inc&(0x10|0x8000),'meta_bg/inline_data unsupported'
  self.ds=u16(s,254) if inc&0x80 else 32;self.gdt=(u32(s,20)+1)*self.bs
 def inode(self,n):
  assert n>0;g,index=divmod(n-1,self.ipg);d=self.p.read(self.gdt+g*self.ds,self.ds)
  table=u32(d,8)+(u32(d,40)<<32 if self.ds>=64 else 0)
  return self.p.read(table*self.bs+index*self.isize,self.isize)
 def extents(self,data,depth=0):
  magic,n,m,d=struct.unpack_from('<HHHH',data);assert magic==0xf30a and d<=5 and depth<=5
  out=[]
  for i in range(n):
   off=12+12*i;logical=u32(data,off)
   if d:
    child=u32(data,off+4)+(u16(data,off+8)<<32)
    out+=self.extents(self.p.read(child*self.bs,self.bs),depth+1)
   else:
    length=u16(data,off+4);assert length<=32768,'uninitialized extent'
    physical=u32(data,off+8)+(u16(data,off+6)<<32);out.append((logical*self.bs,length*self.bs,physical*self.bs))
  return out
 def content(self,n):
  inode=self.inode(n);mode=u16(inode,0);size=u32(inode,4)+(u32(inode,108)<<32)
  assert size<=16*1024*1024,('file cap',size)
  if stat.S_ISLNK(mode) and size<=60:return inode[40:40+size]
  assert u32(inode,32)&0x80000,('non-extent inode',n)
  data=bytearray(size)
  for offset,length,physical in self.extents(inode[40:100]):
   length=min(length,size-offset)
   if length>0:data[offset:offset+length]=self.p.read(physical,length)
  return bytes(data)
 def entries(self,n):
  assert stat.S_ISDIR(u16(self.inode(n),0)), 'not a directory'
  data=self.content(n);result={};off=0
  while off+8<=len(data):
   ino,rec,nlen,typ=struct.unpack_from('<IHBB',data,off)
   assert rec>=8 and rec%4==0 and off+rec<=len(data)
   name=data[off+8:off+8+nlen].decode('utf-8')
   if ino and name not in ('.','..'):result[name]=(ino,typ)
   off+=rec
  return result
 def resolve(self,path):
  n=2
  for name in path.strip('/').split('/'):
   if name:n=self.entries(n)[name][0]
  return n
 def extract(self,path,target):
  n=self.resolve(path);data=self.content(n);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
  return {'path':path,'size':len(data),'sha256':hashlib.sha256(data).hexdigest(),'elf':data.startswith(b'\x7fELF')}

if __name__=='__main__':
 p=Payload();part=Partition(p,'system');fs=Ext4(part);print('root',list(fs.entries(2)))
 candidates=['system/bin']
 out=BASE/'current-files';report={'files':[]}
 for folder in candidates:
  try:entries=fs.entries(fs.resolve(folder))
  except KeyError:continue
  print(folder,'cloud matches',[n for n in entries if any(k in n.lower() for k in ('cloud','mqtt'))])
  for name in ['cloudmanager','cloudctrlserv','mqttserv','cloudmanager.sh']:
   if name in entries:
    r=fs.extract(folder+'/'+name,out/(folder+'/'+name));report['files'].append(r);print(r)
 for path in ['system/build.prop','build.prop']:
  try:report['files'].append(fs.extract(path,out/path))
  except KeyError:pass
 for name in ['libbyddns.so','libcares.so','libstateservice.so']:
  path='system/lib64/'+name
  report['files'].append(fs.extract(path,out/path))
 report.update({'verified_operations':sorted(part.verified),'compressed_bytes_read':part.compressed_bytes,'whole_partition_hash_checked':False})
 (BASE/'current-extraction.json').write_text(json.dumps(report,indent=2))
