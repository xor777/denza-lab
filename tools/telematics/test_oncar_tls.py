#!/usr/bin/env python3
"""Android opaque-key TLS tests against an independent local server, synthetic keys."""
import json,subprocess,tempfile,ssl,socket,threading
from pathlib import Path
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
from cryptography import x509
from cryptography.x509.oid import NameOID
from tls_identity_probe import make_cert,pem

TEST_JAR = '/data/local/tmp/denza-oncar-tls.jar'
FIXTURE_DIRECTORY = '/data/local/tmp/denza-tls-fixture'

def run():
 results=[];keys=[rsa.generate_private_key(public_exponent=65537,key_size=2048) for _ in range(4)]
 name=lambda x:x509.Name([x509.NameAttribute(NameOID.COMMON_NAME,x)])
 root=make_cert(keys[0],keys[0],name('root'),name('root'),ca=True)
 issuer=make_cert(keys[1],keys[0],root.subject,name('issuer'),ca=True)
 client=make_cert(keys[2],keys[1],issuer.subject,name('client'))
 server=make_cert(keys[3],keys[1],issuer.subject,name('localhost'),server=True)
 remote=FIXTURE_DIRECTORY
 with tempfile.TemporaryDirectory(prefix='denza-android-tls-') as tmp:
  p=Path(tmp)
  for n,c in [('root',root),('issuer',issuer),('leaf',client)]: (p/(n+'.der')).write_bytes(c.public_bytes(serialization.Encoding.DER))
  (p/'key.der').write_bytes(keys[2].private_bytes(serialization.Encoding.DER,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
  (p/'server.pem').write_bytes(pem(server)) # server deliberately omits intermediate
  (p/'root.pem').write_bytes(pem(root));(p/'server-key.pem').write_bytes(keys[3].private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()))
  subprocess.run(['adb','-s','emulator-5580','shell','mkdir','-p',remote],check=True,capture_output=True)
  for f in p.glob('*.der'):subprocess.run(['adb','-s','emulator-5580','push',str(f),remote+'/'+f.name],check=True,capture_output=True)
  try:
   for case,host in [('mutual_tls','localhost'),('repeated_mutual_tls','localhost'),('selected_ip','localhost'),('selected_ip_wrong_hostname','wrong.invalid'),('wrong_hostname','127.0.0.1'),('wrong_server_ca','localhost'),('bad_signature','localhost')]:
    if case=='wrong_server_ca':
     other=rsa.generate_private_key(public_exponent=65537,key_size=2048)
     (p/'server.pem').write_bytes(pem(make_cert(keys[3],other,name('untrusted'),name('localhost'),server=True)))
    else:(p/'server.pem').write_bytes(pem(server))
    ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER);ctx.minimum_version=ctx.maximum_version=ssl.TLSVersion.TLSv1_2;ctx.set_ciphers('ECDHE-RSA-AES128-GCM-SHA256');ctx.load_cert_chain(str(p/'server.pem'),str(p/'server-key.pem'));ctx.load_verify_locations(str(p/'root.pem'));ctx.verify_mode=ssl.CERT_REQUIRED
    listen=socket.socket();listen.bind(('127.0.0.1',0));port=listen.getsockname()[1];listen.listen(1);listen.settimeout(15)
    subprocess.run(['adb','-s','emulator-5580','reverse',f'tcp:{port}',f'tcp:{port}'],check=True,capture_output=True)
    seen={}
    def serve():
     try:
      for _ in range(2 if case=='repeated_mutual_tls' else 1):
       conn,_=listen.accept();conn.settimeout(10)
       with ctx.wrap_socket(conn,server_side=True) as secure:
        seen['client_verified']=bool(secure.getpeercert());seen['received']=list(secure.recv(3));secure.sendall(b'\4')
        seen.setdefault('resumed',[]).append(secure.session_reused)
     except Exception as e:seen['error']=type(e).__name__
    worker=threading.Thread(target=serve,daemon=True);worker.start()
    extra=' --bad-signature' if case=='bad_signature' else (' --twice' if case=='repeated_mutual_tls' else (' --selected-ip' if case.startswith('selected_ip') else ''))
    result=subprocess.run(['adb','-s','emulator-5580','shell',f'CLASSPATH={TEST_JAR} timeout 20s app_process /system/bin dev.denza.tools.OncarTlsTest {remote} {host} {port}{extra}'],capture_output=True,text=True,timeout=25)
    worker.join(2);listen.close();subprocess.run(['adb','-s','emulator-5580','reverse','--remove',f'tcp:{port}'],capture_output=True)
    passed=(result.returncode==0 and seen.get('received')==[1,2,3] and
            result.stdout.count('PASS TLS signatures=1')==(2 if case=='repeated_mutual_tls' else 1)) if case in ('mutual_tls','repeated_mutual_tls','selected_ip') else ('FAIL TLS signatures='+('1' if case=='bad_signature' else '0')) in result.stdout
    results.append({'case':case,'passed':passed,'stdout':result.stdout,'stderr':result.stderr[-6000:],'server':seen})
  finally:subprocess.run(['adb','-s','emulator-5580','shell','rm','-rf',remote],capture_output=True)
 return {'all_passed':all(x['passed'] for x in results),'cases':results,'factory_calls':0,'cloud_contacted':False}
if __name__=='__main__':print(json.dumps(run(),indent=2))
