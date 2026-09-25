package dev.denza.tools.runtime;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** One-request Unix socket transport; challenge authenticates the app's shell bridge. */
final class CloudLocalControl {
    static final String SOCKET="denza.cloud.native.v2";
    static final Path STATE=java.nio.file.Paths.get("/data/local/tmp/denza-cloud-native-state");
    static final Path SECRET=STATE.resolve("auth.secret");
    private CloudLocalControl(){}
    static byte[] secret(boolean create)throws Exception{
        if(create&&!Files.exists(SECRET,LinkOption.NOFOLLOW_LINKS)){
            byte[] generated=new byte[32];new SecureRandom().nextBytes(generated);
            try(FileChannel channel=FileChannel.open(SECRET,StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                Files.setPosixFilePermissions(SECRET,PosixFilePermissions.fromString("rw-------"));
                ByteBuffer block=ByteBuffer.wrap(generated);
                while(block.hasRemaining())channel.write(block);
                channel.force(true);
            }catch(java.nio.file.FileAlreadyExistsException competing){}
            try(FileChannel dir=FileChannel.open(STATE,StandardOpenOption.READ)){dir.force(true);}
        }
        if(!Files.isRegularFile(SECRET,LinkOption.NOFOLLOW_LINKS)||Files.size(SECRET)!=32||
           !Files.getPosixFilePermissions(SECRET,LinkOption.NOFOLLOW_LINKS)
               .equals(PosixFilePermissions.fromString("rw-------")))
            throw new IOException("control_secret_invalid");
        try(FileChannel channel=FileChannel.open(SECRET,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer out=ByteBuffer.allocate(32);while(out.hasRemaining()&&channel.read(out)>0){}
            if(out.hasRemaining())throw new IOException("control_secret_short");return out.array();
        }
    }
    static String nonce(){byte[] random=new byte[16];new SecureRandom().nextBytes(random);return hex(random);}
    static String mac(byte[] secret,String challenge,String installId,long generation)throws Exception{
        Mac hmac=Mac.getInstance("HmacSHA256");hmac.init(new SecretKeySpec(secret,"HmacSHA256"));
        return hex(hmac.doFinal((challenge+":"+installId+":"+generation)
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    }
    static boolean matches(String expected,String actual){
        return actual!=null&&actual.matches("[0-9a-f]{64}")&&
            MessageDigest.isEqual(expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                                  actual.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    static LocalSocket connect()throws IOException{
        LocalSocket socket=new LocalSocket();
        // Android's LocalSocket.connect(address, timeout) is an unsupported API
        // even though it is present in android.jar. The abstract Unix endpoint
        // is local; use its supported connect overload, then bound protocol I/O.
        try{socket.connect(new LocalSocketAddress(SOCKET,LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(12000);return socket;}
        catch(IOException failure){try{socket.close();}catch(IOException ignored){}throw failure;}
    }
    static String readLine(InputStream input,int max)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        for(;;){int c=input.read();if(c<0){if(bytes.size()==0)return null;throw new IOException("control_eof");}
            if(c=='\n')break;
            if(c<32||c>126||bytes.size()>=max)throw new IOException("control_line_bound");
            bytes.write(c);
        }
        return bytes.toString("US-ASCII");
    }
    static void writeLine(OutputStream output,String text)throws IOException{
        if(text==null||text.length()>32768||!text.matches("[ -~]+"))throw new IOException("control_output_bound");
        output.write((text+"\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));output.flush();
    }
    private static String hex(byte[] bytes){
        char[] out=new char[bytes.length*2];String digits="0123456789abcdef";
        for(int i=0;i<bytes.length;i++){int b=bytes[i]&255;out[i*2]=digits.charAt(b>>>4);out[i*2+1]=digits.charAt(b&15);}
        return new String(out);
    }
}
