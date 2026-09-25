package dev.denza.tools;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import javax.crypto.Cipher;
import javax.net.ssl.SSLSocket;

/** Emulator-only synthetic-key fixture; excluded from the runtime candidate. */
public final class OncarTlsTest {
    public static void main(String[] args)throws Exception {
        String dir=args[0];
        PrivateKey key=KeyFactory.getInstance("RSA").generatePrivate(
            new PKCS8EncodedKeySpec(Files.readAllBytes(Paths.get(dir,"key.der"))));
        OncarTls.initialize(
            OncarTls.cert(Files.readAllBytes(Paths.get(dir,"root.der"))),
            OncarTls.cert(Files.readAllBytes(Paths.get(dir,"issuer.der"))),
            OncarTls.cert(Files.readAllBytes(Paths.get(dir,"leaf.der"))),block->{
                if(args.length==4&&args[3].equals("--bad-signature"))return new byte[256];
                Cipher c=Cipher.getInstance("RSA/ECB/NoPadding","AndroidOpenSSL");
                c.init(Cipher.ENCRYPT_MODE,key);return c.doFinal(block);
            });
        try{
            for(int j=0;j<(args.length==4&&args[3].equals("--twice")?2:1);j++){
                boolean selected=args.length==4&&args[3].equals("--selected-ip");
                try(SSLSocket socket=selected
                    ? OncarTls.connect(args[1],java.net.InetAddress.getByAddress(new byte[]{127,0,0,1}),Integer.parseInt(args[2]),s->{})
                    : OncarTls.connect(args[1],Integer.parseInt(args[2]))){
                    socket.getOutputStream().write(new byte[]{1,2,3});
                    OncarTls.need(socket.getInputStream().read()==4,"echo");
                    System.out.println("PASS TLS signatures="+OncarTls.signatureCount());
                }
            }
        }catch(Exception e){
            System.out.println("FAIL TLS signatures="+OncarTls.signatureCount()+" type="+e.getClass().getSimpleName());
            throw e;
        }
    }
}
