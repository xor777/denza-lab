package dev.denza.tools.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;

/** Durable ownership of an explicit stock pause, never a factory-ready instruction. */
final class CloudGateJournal {
    private final Path file;
    CloudGateJournal(Path file){this.file=file;}
    Path path(){return file;}
    static int goneValue(String profile)throws IOException{
        if("triple_apn".equals(profile))return -2;
        if("double_apn".equals(profile))return -5;
        throw new IOException("stock_profile_unsupported");
    }
    void beforePause(String profile)throws IOException{
        goneValue(profile);
        if(pending()){
            if(!profile().equals(profile))throw new IOException("stock_profile_changed");
            return;
        }
        byte[] value=("paused_profile="+profile+"\n").getBytes(StandardCharsets.US_ASCII);
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
            ByteBuffer bytes=ByteBuffer.wrap(value);
            while(bytes.hasRemaining())channel.write(bytes);
            channel.force(true);
        }
        syncDirectory();
    }
    boolean pending(){return Files.exists(file,LinkOption.NOFOLLOW_LINKS);}
    String profile()throws IOException{
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>48)
            throw new IOException("stock_gate_journal_shape");
        String value=new String(Files.readAllBytes(file),StandardCharsets.US_ASCII);
        if(value.equals("paused_profile=triple_apn\n"))return "triple_apn";
        if(value.equals("paused_profile=double_apn\n"))return "double_apn";
        throw new IOException("stock_gate_journal_shape");
    }
    void resolved()throws IOException{Files.deleteIfExists(file);syncDirectory();}
    private void syncDirectory()throws IOException{
        try(FileChannel directory=FileChannel.open(file.toAbsolutePath().getParent(),StandardOpenOption.READ)){
            directory.force(true);
        }
    }
}
