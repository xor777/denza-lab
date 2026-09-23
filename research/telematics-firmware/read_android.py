import io,os,struct,zipfile,hashlib,json,xml.etree.ElementTree as ET
from pathlib import Path
from cryptography.hazmat.primitives.ciphers import Cipher,algorithms,modes
from check_config import ARCHIVE,BASE,derive

def package_seed(text):
 assert len(text)==38
 raw=bytearray.fromhex(text);assert len(raw)==19 and raw[16]<16
 raw[raw[16]]=raw[17]
 factor=text[36:38].encode('ascii');h=hashlib.sha256(factor).digest()
 key=bytes((h[2*i]+h[31-2*i])&255 for i in range(16));iv=hashlib.md5(factor).digest()
 d=Cipher(algorithms.AES(key),modes.CBC(iv)).decryptor()
 return d.update(bytes(raw[:16]))+d.finalize()

class AndroidReader(io.RawIOBase):
 def __init__(self):
  super().__init__();self.f=ARCHIVE.open('rb');self.pos=0;self.read_bytes=0
  with zipfile.ZipFile(ARCHIVE) as z: info=z.getinfo('Android/Target/android.zip')
  assert info.compress_type==0
  h=os.pread(self.f.fileno(),30,info.header_offset);assert h[:4]==b'PK\x03\x04'
  n,x=struct.unpack_from('<HH',h,26);self.offset=info.header_offset+30+n+x;self.cipher_size=info.file_size
  r=ET.parse(BASE/'Config-readable.xml').getroot()
  text=next(e.text for e in r.iter('Package') if e.get('ModuleName')=='Android')
  self.key,self.iv=derive(package_seed(text))
  self.size=self.cipher_size;last=self._range(self.size-16,16)
  pad=last[-1];assert 1<=pad<=16 and last[-pad:]==bytes([pad])*pad
  self.size-=pad;self.padding=pad
 def readable(self):return True
 def seekable(self):return True
 def tell(self):return self.pos
 def seek(self,offset,whence=0):
  self.pos=offset if whence==0 else self.pos+offset if whence==1 else self.size+offset
  if not 0<=self.pos<=self.size:raise ValueError('out of bounds')
  return self.pos
 def _range(self,start,n):
  a=start//16*16;b=(start+n+15)//16*16
  iv=self.iv if a==0 else os.pread(self.f.fileno(),16,self.offset+a-16)
  data=os.pread(self.f.fileno(),b-a,self.offset+a);assert len(data)==b-a
  self.read_bytes+=len(data)+(16 if a else 0)
  d=Cipher(algorithms.AES(self.key),modes.CBC(iv)).decryptor();p=d.update(data)+d.finalize()
  return p[start-a:start-a+n]
 def read(self,n=-1):
  if n<0:n=self.size-self.pos
  n=min(n,self.size-self.pos)
  if n>16*1024*1024:raise ValueError('single read cap 16 MiB')
  if n==0:return b''
  p=self._range(self.pos,n);self.pos+=len(p);return p
 def close(self):
  if hasattr(self,'f'):self.f.close()
  super().close()

if __name__=='__main__':
 with AndroidReader() as f:
  head=f.read(64)
  with zipfile.ZipFile(f) as z:
   entries=[{'name':i.filename,'size':i.file_size,'compressed_size':i.compress_size,'method':i.compress_type,'header_offset':i.header_offset,'crc32':f'{i.CRC:08x}'} for i in z.infolist()]
   for i in z.infolist():
    if i.file_size<20000 and 'metadata' in i.filename:
     data=z.read(i);(BASE/('android-'+i.filename.rsplit('/',1)[-1])).write_bytes(data)
  report={'plaintext_size':f.size,'pkcs7_padding':f.padding,'first_magic':head[:4].hex(),'zip_directory_parsed':True,'entries':entries,'cipher_bytes_read':f.read_bytes,'full_archive_crc_checked':False}
  (BASE/'android-index.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
