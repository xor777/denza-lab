package dev.denza.tools;

import android.os.IBinder;
import android.os.Parcel;
import java.io.*;
import java.math.BigInteger;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import java.security.interfaces.*;
import java.security.spec.AlgorithmParameterSpec;
import java.util.*;
import javax.crypto.*;
import javax.net.ssl.*;

/** Android TLS with an opaque factory key. No vehicle packet schema. */
public final class OncarTls {
    public static final class FactoryIdentityMismatch extends GeneralSecurityException {
        public FactoryIdentityMismatch(){super("factory identity does not match reviewed car");}
    }
    interface Signer { byte[] sign(byte[] block) throws Exception; }
    // Re-initialization affects future sockets only. An opaque key is bound to
    // exactly one handshake; neither peer trust nor its signing budget is global.
    static volatile Identity identity;
    private static final ThreadLocal<Attempt> lastAttempt=new ThreadLocal<>();
    static final class Identity {
        final X509Certificate root,issuer,leaf;
        final RSAPublicKey publicKey;
        final Signer signer;
        Identity(X509Certificate r,X509Certificate i,X509Certificate l,Signer s){
            root=r;issuer=i;leaf=l;publicKey=(RSAPublicKey)l.getPublicKey();signer=s;
        }
        synchronized byte[] sign(byte[] block)throws Exception{return signer.sign(block);}
    }
    static final class Attempt {
        final Identity identity;
        private SSLSocket socket;
        private boolean peerVerified,closed;
        private int signatures;
        Attempt(Identity identity){this.identity=Objects.requireNonNull(identity,"TLS identity missing");}
        synchronized void bind(SSLSocket socket){need(this.socket==null&&!closed,"TLS socket already bound");this.socket=socket;}
        synchronized void verified(Socket socket){need(this.socket!=null&&this.socket==socket&&!closed,"TLS peer belongs to another socket");peerVerified=true;}
        synchronized void reserve(){need(peerVerified&&!closed&&signatures==0,"peer or signature budget");signatures++;}
        synchronized int signatures(){return signatures;}
        synchronized boolean mutual(){return peerVerified&&!closed&&signatures==1;}
        synchronized void finish(){closed=true;}
    }
    // Diagnostic for the caller's most recent connection, never an authorization source.
    public static int signatureCount(){Attempt attempt=lastAttempt.get();return attempt==null?0:attempt.signatures();}
    static void need(boolean b,String why) { if(!b) throw new IllegalStateException(why); }
    static String hex(byte[] b) { StringBuilder s=new StringBuilder();for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s.toString(); }
    static byte[] unhex(String s) { need(s.length()%2==0&&s.length()<=4096,"hex bound");byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++){int a=Character.digit(s.charAt(i*2),16),c=Character.digit(s.charAt(i*2+1),16);need(a>=0&&c>=0,"hex");b[i]=(byte)(a*16+c);}return b; }
    static IBinder service(String name)throws Exception {return (IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);}
    static byte[] chip(int selector)throws Exception {
        IBinder b=service("safekeyservice");Parcel q=Parcel.obtain(),r=Parcel.obtain();
        try{q.writeInterfaceToken(b.getInterfaceDescriptor());q.writeInt(10102);q.writeInt(selector);need(b.transact(14,q,r,0),"cert transaction");need(r.readInt()==0,"certificate status");String value=r.readString();r.readInt();return android.util.Base64.decode(value,android.util.Base64.DEFAULT);}finally{q.recycle();r.recycle();}
    }
    static byte[] factorySign(byte[] block)throws Exception {
        IBinder b=service("safekeyservice");Parcel q=Parcel.obtain(),r=Parcel.obtain();
        try{q.writeInterfaceToken(b.getInterfaceDescriptor());q.writeInt(10106);q.writeInt(0x01003100);q.writeString(hex(block));q.writeInt(256);need(b.transact(6,q,r,0),"signature transaction");need(r.readInt()==0,"signature status");String value=r.readString();r.readInt();need(value!=null&&value.length()==512,"signature length");return unhex(value);}finally{q.recycle();r.recycle();}
    }
    static X509Certificate cert(byte[] b)throws Exception {return (X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(b));}
    public static final class OpaqueKey implements RSAPrivateKey {
        private final Attempt attempt;
        OpaqueKey(Attempt attempt){this.attempt=attempt;}
        public BigInteger getModulus(){return attempt.identity.publicKey.getModulus();}
        public BigInteger getPrivateExponent(){throw new UnsupportedOperationException("factory key opaque");}
        public String getAlgorithm(){return "RSA";} public String getFormat(){return null;} public byte[] getEncoded(){return null;}
    }
    public static final class FactoryProvider extends Provider {
        public FactoryProvider(){super("DenzaFactoryTest",1.0,"Bounded opaque TLS key");put("Cipher.RSA/ECB/PKCS1Padding",FactoryCipher.class.getName());put("Cipher.RSA/ECB/NoPadding",RawFactoryCipher.class.getName());}
    }
    public static class FactoryCipher extends CipherSpi {
        final ByteArrayOutputStream input=new ByteArrayOutputStream();boolean raw;Attempt attempt;
        protected void engineSetMode(String mode)throws NoSuchAlgorithmException {if(!mode.equals("ECB"))throw new NoSuchAlgorithmException();}
        protected void engineSetPadding(String padding)throws NoSuchPaddingException {if(!padding.equals(raw?"NoPadding":"PKCS1Padding"))throw new NoSuchPaddingException();}
        protected int engineGetBlockSize(){return 256;} protected int engineGetOutputSize(int n){return 256;} protected byte[] engineGetIV(){return null;} protected AlgorithmParameters engineGetParameters(){return null;}
        protected void engineInit(int op,Key key,SecureRandom random)throws InvalidKeyException {attempt=null;input.reset();if(op!=Cipher.ENCRYPT_MODE||!(key instanceof OpaqueKey))throw new InvalidKeyException("not factory TLS signing");attempt=((OpaqueKey)key).attempt;}
        protected void engineInit(int op,Key key,AlgorithmParameterSpec p,SecureRandom r)throws InvalidKeyException {engineInit(op,key,r);}
        protected void engineInit(int op,Key key,AlgorithmParameters p,SecureRandom r)throws InvalidKeyException {engineInit(op,key,r);}
        protected byte[] engineUpdate(byte[] b,int o,int n){need(input.size()+n<=256,"signer input bound");input.write(b,o,n);return new byte[0];}
        protected int engineUpdate(byte[] b,int o,int n,byte[] dst,int off){engineUpdate(b,o,n);return 0;}
        protected byte[] engineDoFinal(byte[] b,int o,int n)throws BadPaddingException {
            try {if(n>0)engineUpdate(b,o,n);byte[] message=input.toByteArray(),block;
                if(raw){need(message.length==256,"raw TLS block");block=message;}
                else{need(message.length==51&&hex(Arrays.copyOf(message,19)).equals("3031300d060960864801650304020105000420"),"TLS digest algorithm");block=new byte[256];block[1]=1;Arrays.fill(block,2,204,(byte)255);System.arraycopy(message,0,block,205,51);}
                need(block[0]==0&&block[1]==1&&block[204]==0&&hex(Arrays.copyOfRange(block,205,224)).equals("3031300d060960864801650304020105000420"),"TLS raw digest algorithm");
                for(int j=2;j<204;j++)need(block[j]==(byte)255,"TLS raw padding");
                need(attempt!=null,"TLS cipher not initialized");attempt.reserve();
                byte[] result=attempt.identity.sign(block);need(result!=null&&result.length==256,"signature bytes");
                Cipher verify=verificationCipher();verify.init(Cipher.DECRYPT_MODE,attempt.identity.publicKey);need(MessageDigest.isEqual(block,verify.doFinal(result)),"signature verification");return result;
            }catch(Exception e){throw new BadPaddingException("factory TLS signature failed: "+e.getClass().getSimpleName());}
        }
        protected int engineDoFinal(byte[] b,int o,int n,byte[] dst,int off)throws BadPaddingException,ShortBufferException {if(off<0||off>dst.length-256)throw new ShortBufferException();byte[] result=engineDoFinal(b,o,n);System.arraycopy(result,0,dst,off,result.length);return result.length;}
    }
    public static final class RawFactoryCipher extends FactoryCipher {public RawFactoryCipher(){raw=true;}}
    static Cipher verificationCipher()throws GeneralSecurityException {
        Provider android=Security.getProvider("AndroidOpenSSL");
        return android!=null?Cipher.getInstance("RSA/ECB/NoPadding",android):Cipher.getInstance("RSA/ECB/NoPadding");
    }
    static synchronized void initialize(X509Certificate root,X509Certificate issuer,X509Certificate leaf,Signer signer)throws Exception {
        identity=null;
        need(leaf.getPublicKey() instanceof RSAPublicKey&&((RSAPublicKey)leaf.getPublicKey()).getModulus().bitLength()==2048,"RSA dimensions");
        root.checkValidity();issuer.checkValidity();leaf.checkValidity();root.verify(root.getPublicKey());issuer.verify(root.getPublicKey());leaf.verify(issuer.getPublicKey());need(root.getBasicConstraints()>=0&&issuer.getBasicConstraints()>=0,"CA constraints");
        if(Security.getProvider("DenzaFactoryTest")==null)Security.insertProviderAt(new FactoryProvider(),1);
        identity=new Identity(root,issuer,leaf,Objects.requireNonNull(signer));
    }
    static SSLContext context(Attempt attempt)throws Exception {
        final Identity identity=attempt.identity;
        final X509Certificate root=identity.root,issuer=identity.issuer,leaf=identity.leaf;
        KeyStore store=KeyStore.getInstance(KeyStore.getDefaultType());store.load(null);store.setCertificateEntry("root",root);
        TrustManagerFactory tmf=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tmf.init(store);X509ExtendedTrustManager trusted=(X509ExtendedTrustManager)tmf.getTrustManagers()[0];
        X509ExtendedTrustManager trust=new X509ExtendedTrustManager(){
            X509Certificate[] complete(X509Certificate[] c){if(c.length==1&&!c[0].equals(issuer))return new X509Certificate[]{c[0],issuer};return c;}
            public void checkServerTrusted(X509Certificate[] c,String a,Socket socket)throws CertificateException {trusted.checkServerTrusted(complete(c),a,socket);attempt.verified(socket);}
            public void checkServerTrusted(X509Certificate[] c,String a,SSLEngine engine)throws CertificateException {throw new CertificateException("socket required");}
            public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException {throw new CertificateException("socket verification required");}
            public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException();}
            public void checkClientTrusted(X509Certificate[] c,String a,Socket s)throws CertificateException{throw new CertificateException();}
            public void checkClientTrusted(X509Certificate[] c,String a,SSLEngine e)throws CertificateException{throw new CertificateException();}
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[]{root};}
        };
        X509ExtendedKeyManager km=new X509ExtendedKeyManager(){
            public String chooseClientAlias(String[] t,Principal[] i,Socket socket){for(String x:t)if(x.equals("RSA"))return "factory";return null;}
            public String chooseServerAlias(String t,Principal[] i,Socket s){return null;}
            public String[] getClientAliases(String t,Principal[] i){return new String[]{"factory"};}
            public String[] getServerAliases(String t,Principal[] i){return null;}
            public X509Certificate[] getCertificateChain(String a){return new X509Certificate[]{leaf,issuer};}
            public PrivateKey getPrivateKey(String a){return new OpaqueKey(attempt);}
        };
        SSLContext ctx=SSLContext.getInstance("TLSv1.2");
        ctx.init(new KeyManager[]{km},new TrustManager[]{trust},new SecureRandom());
        return ctx;
    }
    public interface SocketOwner { void own(Socket socket) throws Exception; }
    public static SSLSocket connect(String host,int port)throws Exception {
        return connect(host,port,socket->{});
    }
    /** Publish sockets before connect/handshake so lifecycle cancellation can close them. */
    public static SSLSocket connect(String host,int port,SocketOwner owner)throws Exception {
        return connect(host,null,port,owner);
    }
    /** Selected DNS address affects routing only; TLS identity remains the canonical host. */
    public static SSLSocket connect(String host,InetAddress selected,int port,SocketOwner owner)throws Exception {
        // This bounded experiment requires a new verified peer and one chip signature per socket.
        // A shared context can resume an earlier TLS session without either callback.
        lastAttempt.remove();
        Attempt attempt=new Attempt(identity);lastAttempt.set(attempt);
        SSLContext ctx=context(attempt);Socket raw=new Socket();SSLSocket socket=null;
        try{owner.own(raw);raw.connect(selected==null?new InetSocketAddress(host,port):new InetSocketAddress(selected,port),8000);socket=(SSLSocket)ctx.getSocketFactory().createSocket(raw,host,port,true);owner.own(socket);
            attempt.bind(socket);socket.setSoTimeout(10000);socket.setEnabledProtocols(new String[]{"TLSv1.2"});socket.setEnabledCipherSuites(new String[]{"TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"});SSLParameters p=socket.getSSLParameters();p.setEndpointIdentificationAlgorithm("HTTPS");socket.setSSLParameters(p);socket.startHandshake();need(attempt.mutual(),"mutual TLS not established");return socket;
        }catch(Exception e){try{raw.close();}catch(Exception closeFailure){e.addSuppressed(closeFailure);}
            try{if(socket!=null)socket.close();}catch(Exception closeFailure){e.addSuppressed(closeFailure);}throw e;
        }finally{attempt.finish();}
    }
    public static synchronized void factoryIdentity()throws Exception {
        identity=null;
        X509Certificate r=cert(chip(5)),i=cert(chip(6)),l=cert(chip(7));
        // The reviewed BYD root is the trust anchor. Each car may have its own
        // issuer/leaf, which must form a valid chain and match the chip key.
        MessageDigest sha=MessageDigest.getInstance("SHA-256");
        if(!hex(sha.digest(r.getEncoded())).equals("6eb7d1ba485904c09d39619f2b09bb60ded2acc0bfd5c662e6769915a51ff5be"))
            throw new FactoryIdentityMismatch();
        try{initialize(r,i,l,OncarTls::factorySign);}
        catch(GeneralSecurityException|IllegalStateException mismatch){throw new FactoryIdentityMismatch();}
        try{hardwareProof(l,OncarTls::factorySign);}
        catch(Exception failure){identity=null;throw failure;}
    }
    /** One local challenge proves the presented leaf actually belongs to this chip. */
    static void hardwareProof(X509Certificate leaf,Signer signer)throws Exception{
        byte[] challenge=new byte[32];new SecureRandom().nextBytes(challenge);
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(challenge);
        byte[] prefix=unhex("3031300d060960864801650304020105000420");
        byte[] block=new byte[256];block[1]=1;Arrays.fill(block,2,204,(byte)255);
        System.arraycopy(prefix,0,block,205,prefix.length);
        System.arraycopy(digest,0,block,224,digest.length);
        byte[] signature=signer.sign(block);
        if(signature==null||signature.length!=256)throw new FactoryIdentityMismatch();
        try{
            Cipher verifier=verificationCipher();
            verifier.init(Cipher.DECRYPT_MODE,leaf.getPublicKey());
            if(!MessageDigest.isEqual(block,verifier.doFinal(signature)))
                throw new FactoryIdentityMismatch();
        }catch(GeneralSecurityException invalid){throw new FactoryIdentityMismatch();}
    }
}
