#!/usr/bin/env python3
"""Host regression for the actual opaque-key signer, synthetic identities only.

Compiles OncarTls.java unchanged against Android API stubs, exercises its Java
crypto provider on a JDK. No ADB, network, factory service or vehicle calls.
Android/Conscrypt TLS integration remains a separate emulator acceptance check.
"""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
from cryptography import x509
from cryptography.x509.oid import NameOID
from tls_identity_probe import make_cert

HERE = Path(__file__).resolve().parent
HARNESS = r'''
package dev.denza.tools;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.crypto.*;
import javax.net.ssl.*;

public class TlsIsolationHarness {
    static X509Certificate root,issuer,leaf,otherLeaf;
    static PrivateKey key,otherKey;
    static void need(boolean value,String why){if(!value)throw new AssertionError(why);}
    static byte[] bytes(String dir,String name)throws Exception{return Files.readAllBytes(Paths.get(dir,name+".der"));}
    static byte[] digest(){byte[] value=new byte[51];System.arraycopy(OncarTls.unhex("3031300d060960864801650304020105000420"),0,value,0,19);Arrays.fill(value,19,51,(byte)42);return value;}
    static byte[] raw(){byte[] value=new byte[256];value[1]=1;Arrays.fill(value,2,204,(byte)255);System.arraycopy(digest(),0,value,205,51);return value;}
    static OncarTls.Identity identity(X509Certificate leaf,PrivateKey key,AtomicInteger calls){
        return new OncarTls.Identity(root,issuer,leaf,block->{calls.incrementAndGet();Cipher signer=Cipher.getInstance("RSA/ECB/NoPadding","SunJCE");signer.init(Cipher.ENCRYPT_MODE,key);return signer.doFinal(block);});
    }
    static Cipher cipher(OncarTls.Attempt attempt,boolean raw)throws Exception{
        Cipher result=Cipher.getInstance("RSA/ECB/"+(raw?"NoPadding":"PKCS1Padding"),"DenzaFactoryTest");
        result.init(Cipher.ENCRYPT_MODE,new OncarTls.OpaqueKey(attempt));return result;
    }
    static void reject(Cipher cipher,byte[] input)throws Exception{
        try{cipher.doFinal(input);throw new AssertionError("unexpected signature");}catch(BadPaddingException expected){}
    }
    static void verified(OncarTls.Attempt attempt)throws Exception{
        SSLSocket socket=(SSLSocket)SSLSocketFactory.getDefault().createSocket();
        attempt.bind(socket);attempt.verified(socket);socket.close();
    }
    static void check(byte[] signed,byte[] block,X509Certificate leaf)throws Exception{
        Cipher verify=Cipher.getInstance("RSA/ECB/NoPadding","SunJCE");verify.init(Cipher.DECRYPT_MODE,leaf.getPublicKey());
        need(MessageDigest.isEqual(block,verify.doFinal(signed)),"wrong identity signed");
    }
    public static void main(String[] args)throws Exception{
        String dir=args[0],test=args[1];
        root=OncarTls.cert(bytes(dir,"root"));issuer=OncarTls.cert(bytes(dir,"issuer"));
        leaf=OncarTls.cert(bytes(dir,"leaf"));otherLeaf=OncarTls.cert(bytes(dir,"other"));
        key=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes(dir,"key")));
        otherKey=KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(bytes(dir,"other-key")));
        AtomicInteger calls=new AtomicInteger();OncarTls.Identity identity=identity(leaf,key,calls);
        OncarTls.initialize(root,issuer,leaf,identity.signer);
        OncarTls.Attempt a=new OncarTls.Attempt(OncarTls.identity);
        if(test.equals("hardware_proof")){
            OncarTls.hardwareProof(leaf,identity.signer);
            need(calls.get()==1,"hardware challenge not signed exactly once");
        }else if(test.equals("hardware_mismatch")){
            try{OncarTls.hardwareProof(otherLeaf,identity.signer);throw new AssertionError("foreign leaf accepted");}
            catch(OncarTls.FactoryIdentityMismatch expected){}
            need(calls.get()==1,"mismatch challenge budget");
        }else if(test.equals("unverified")){
            reject(cipher(a,false),digest());need(calls.get()==0&&a.signatures()==0,"unverified signer invoked");
        }else if(test.equals("peer_isolation")){
            OncarTls.Attempt b=new OncarTls.Attempt(identity);verified(a);
            reject(cipher(b,true),raw());check(cipher(a,true).doFinal(raw()),raw(),leaf);
            need(calls.get()==1&&a.signatures()==1&&b.signatures()==0,"peer/budget crossed attempts");
        }else if(test.equals("socket_isolation")){
            SSLSocket s=(SSLSocket)SSLSocketFactory.getDefault().createSocket(), other=(SSLSocket)SSLSocketFactory.getDefault().createSocket();
            try{a.bind(s);try{a.verified(other);throw new AssertionError("foreign socket accepted");}catch(IllegalStateException expected){}
                reject(cipher(a,true),raw());need(calls.get()==0,"foreign socket licensed signer");
            }finally{s.close();other.close();}
        }else if(test.equals("identity_snapshot")){
            AtomicInteger otherCalls=new AtomicInteger();OncarTls.Identity second=identity(otherLeaf,otherKey,otherCalls);
            OncarTls.initialize(root,issuer,otherLeaf,second.signer);
            OncarTls.Attempt b=new OncarTls.Attempt(OncarTls.identity);verified(a);verified(b);
            check(cipher(a,false).doFinal(digest()),raw(),leaf);check(cipher(b,false).doFinal(digest()),raw(),otherLeaf);
            need(calls.get()==1&&otherCalls.get()==1,"identity snapshot changed");
        }else if(test.equals("failed_identity_reinit")){
            try{OncarTls.initialize(root,leaf,otherLeaf,identity.signer);throw new AssertionError("invalid chain accepted");}catch(GeneralSecurityException expected){}
            need(OncarTls.identity==null,"failed identity initialization retained old identity");
            try{new OncarTls.Attempt(OncarTls.identity);throw new AssertionError("new attempt used old identity");}catch(NullPointerException expected){}
            verified(a);check(cipher(a,true).doFinal(raw()),raw(),leaf);
            need(calls.get()==1,"existing attempt lost its captured identity");
        }else if(test.equals("failed_connect_diagnostics")){
            verified(a);cipher(a,true).doFinal(raw());
            java.lang.reflect.Field field=OncarTls.class.getDeclaredField("lastAttempt");field.setAccessible(true);
            @SuppressWarnings("unchecked") ThreadLocal<OncarTls.Attempt> last=(ThreadLocal<OncarTls.Attempt>)field.get(null);
            last.set(a);need(OncarTls.signatureCount()==1,"prior diagnostic fixture");OncarTls.identity=null;
            try{OncarTls.connect("no-network.invalid",0);throw new AssertionError("missing identity accepted");}catch(NullPointerException expected){}
            need(OncarTls.signatureCount()==0,"failed connection inherited old signature count");
        }else if(test.equals("concurrent_budget")){
            verified(a);final OncarTls.Attempt concurrent=a;ExecutorService executor=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),start=new CountDownLatch(1);
            try{
                Callable<Boolean> sign=()->{Cipher cipher=cipher(concurrent,true);ready.countDown();need(start.await(2,TimeUnit.SECONDS),"start timeout");try{cipher.doFinal(raw());return true;}catch(BadPaddingException expected){return false;}};
                Future<Boolean> first=executor.submit(sign),second=executor.submit(sign);need(ready.await(2,TimeUnit.SECONDS),"workers not ready");start.countDown();
                need(first.get(3,TimeUnit.SECONDS)^second.get(3,TimeUnit.SECONDS),"budget not exclusive");need(calls.get()==1&&a.signatures()==1,"duplicate signature");
            }finally{executor.shutdownNow();}
        }else if(test.equals("finished_attempt")){
            verified(a);a.finish();reject(cipher(a,false),digest());need(calls.get()==0,"late signer invoked");
        }else if(test.equals("failed_signer_budget")){
            OncarTls.Identity broken=new OncarTls.Identity(root,issuer,leaf,block->{calls.incrementAndGet();return new byte[256];});
            a=new OncarTls.Attempt(broken);verified(a);reject(cipher(a,true),raw());reject(cipher(a,true),raw());
            need(calls.get()==1&&a.signatures()==1,"failed signer retried");
        }else if(test.equals("invalid_block")){
            verified(a);byte[] block=raw();block[20]=0;reject(cipher(a,true),block);block=raw();block[220]=0;reject(cipher(a,true),block);
            need(calls.get()==0&&a.signatures()==0,"invalid block reached signer");check(cipher(a,true).doFinal(raw()),raw(),leaf);
        }else if(test.equals("failed_reinit")){
            verified(a);OncarTls.FactoryCipher spi=new OncarTls.FactoryCipher();spi.engineInit(Cipher.ENCRYPT_MODE,new OncarTls.OpaqueKey(a),new SecureRandom());
            try{spi.engineInit(Cipher.ENCRYPT_MODE,key,new SecureRandom());throw new AssertionError("real key accepted");}catch(InvalidKeyException expected){}
            try{spi.engineDoFinal(digest(),0,51);throw new AssertionError("stale cipher key retained");}catch(BadPaddingException expected){}
            need(calls.get()==0,"stale cipher reached signer");
        }else if(test.equals("short_buffer")){
            verified(a);OncarTls.FactoryCipher spi=new OncarTls.FactoryCipher();spi.engineInit(Cipher.ENCRYPT_MODE,new OncarTls.OpaqueKey(a),new SecureRandom());
            try{spi.engineDoFinal(digest(),0,51,new byte[255],0);throw new AssertionError("short output accepted");}catch(ShortBufferException expected){}
            need(calls.get()==0&&a.signatures()==0,"short output consumed signing budget");byte[] result=new byte[256];need(spi.engineDoFinal(digest(),0,51,result,0)==256,"result length");check(result,raw(),leaf);
        }else throw new AssertionError("unknown test");
        System.out.println("PASS "+test);
    }
}
'''


class TlsIsolationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='denza-tls-isolation-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.directory = Path(cls.temp.name)
        home = Path(os.environ.get('JAVA_HOME', '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home'))
        cls.java = home / 'bin/java'
        android = Path(os.environ.get('ANDROID_HOME', '/opt/homebrew/share/android-commandlinetools')) / 'platforms/android-35/android.jar'
        if not cls.java.exists() or not android.exists():
            raise unittest.SkipTest('JDK17 and Android35 compile stubs required')
        keys = [rsa.generate_private_key(public_exponent=65537, key_size=2048) for _ in range(4)]
        name = lambda value: x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, value)])
        root = make_cert(keys[0], keys[0], name('test-root'), name('test-root'), ca=True)
        issuer = make_cert(keys[1], keys[0], root.subject, name('test-issuer'), ca=True)
        certs = {'root': root, 'issuer': issuer,
                 'leaf': make_cert(keys[2], keys[1], issuer.subject, name('test-client')),
                 'other': make_cert(keys[3], keys[1], issuer.subject, name('test-other'))}
        for stem, cert in certs.items():
            (cls.directory / (stem + '.der')).write_bytes(cert.public_bytes(serialization.Encoding.DER))
        for stem, key in [('key', keys[2]), ('other-key', keys[3])]:
            (cls.directory / (stem + '.der')).write_bytes(key.private_bytes(serialization.Encoding.DER, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        harness = cls.directory / 'TlsIsolationHarness.java'
        harness.write_text(HARNESS)
        compiled = subprocess.run([str(home / 'bin/javac'), '-cp', str(android), '-d', str(cls.directory),
                        str(HERE / 'OncarTls.java'), str(harness)], capture_output=True, text=True, timeout=25)
        if compiled.returncode:
            raise AssertionError(compiled.stdout + compiled.stderr)
        cls.classpath = str(cls.directory) + os.pathsep + str(android)

    def run_case(self, case):
        result = subprocess.run([str(self.java), '-cp', self.classpath, 'dev.denza.tools.TlsIsolationHarness',
                                 str(self.directory), case], capture_output=True, text=True, timeout=10)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn('PASS ' + case, result.stdout)


for _case in ('hardware_proof', 'hardware_mismatch', 'unverified', 'peer_isolation', 'socket_isolation', 'identity_snapshot', 'concurrent_budget',
              'failed_identity_reinit', 'failed_connect_diagnostics', 'finished_attempt', 'failed_signer_budget', 'invalid_block', 'failed_reinit', 'short_buffer'):
    setattr(TlsIsolationTest, 'test_' + _case, lambda self, case=_case: self.run_case(case))

if __name__ == '__main__':
    unittest.main()
