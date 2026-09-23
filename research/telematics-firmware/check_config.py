from pathlib import Path
import hashlib,json,zipfile,os,xml.etree.ElementTree as ET
from cryptography.hazmat.primitives.ciphers import Cipher,algorithms,modes
from cryptography.hazmat.primitives.padding import PKCS7
# Required explicit paths keep firmware inputs separate from generated evidence.
BASE=Path(os.environ['DENZA_FIRMWARE_OUTPUT']).expanduser().resolve()
ARCHIVE=Path(os.environ['DENZA_FIRMWARE_ARCHIVE']).expanduser().resolve()
BASE.mkdir(parents=True,exist_ok=True)
def derive(seed):
 assert len(seed)==16
 h=hashlib.sha256(seed).digest()
 key=bytearray((h[i]+h[31-i])&255 for i in range(16))
 total=sum(key);key[(total>>4)&15]=total&255
 iv=bytes((b<<3)&255 for b in hashlib.md5(seed).digest())
 return bytes(key),iv

def decrypt(data,seed):
 key,iv=derive(seed);d=Cipher(algorithms.AES(key),modes.CBC(iv)).decryptor()
 padded=d.update(data)+d.finalize();p=PKCS7(128).unpadder()
 return p.update(padded)+p.finalize()
if __name__=='__main__':
 with zipfile.ZipFile(ARCHIVE) as z:
  data=z.read('Config.xml');metadata=z.read('metadata')
 report={'input':'Config.xml','input_size':len(data),'metadata_size':len(metadata),'seed_source':'MD5 of complete metadata member','cipher':'AES-128-CBC with PKCS7','recovery_sha256':'5ec7ae12ea06ec17e89c874630d83cbd4598e269321df3ca9021750fc2df2bac'}
 try:
  plain=decrypt(data,hashlib.md5(metadata).digest());root=ET.fromstring(plain)
  (BASE/'Config-readable.xml').write_bytes(plain)
  report.update({'valid_pkcs7':True,'valid_xml':True,'plaintext_size':len(plain),'root_tag':root.tag,'tags':sorted(set(e.tag for e in root.iter())),'plaintext_sha256':hashlib.sha256(plain).hexdigest()})
 except Exception as e:report.update({'error_type':type(e).__name__,'error':str(e)})
 (BASE/'config-check.json').write_text(json.dumps(report,indent=2))
 print(json.dumps(report,indent=2))
