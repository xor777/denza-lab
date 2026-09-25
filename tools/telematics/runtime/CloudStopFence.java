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

/** Durable STOP fence prevents a stale custom marker from restarting its generation. */
final class CloudStopFence {
    private final Path file;
    CloudStopFence(Path file){this.file=file;}
    void block(String installId,long generation)throws IOException{
        if(!installId.matches("[0-9a-f]{32}")||generation<=0)throw new IOException("stop_fence_shape");
        byte[] bytes=(installId+" "+generation+"\n").getBytes(StandardCharsets.US_ASCII);
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
            ByteBuffer body=ByteBuffer.wrap(bytes);channel.position(0);
            while(body.hasRemaining())channel.write(body);
            channel.truncate(bytes.length);channel.force(true);
        }
        try(FileChannel dir=FileChannel.open(file.getParent(),StandardOpenOption.READ)){dir.force(true);}
    }
    boolean blocked(String installId,long generation)throws IOException{
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return false;
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>64)throw new IOException("stop_fence_invalid");
        String value=new String(Files.readAllBytes(file),StandardCharsets.US_ASCII);
        if(!value.matches("[0-9a-f]{32} [1-9][0-9]{0,18}\\n"))throw new IOException("stop_fence_invalid");
        String[] fields=value.trim().split(" ");
        if(!fields[0].equals(installId))return false;
        try{return generation<=Long.parseLong(fields[1]);}
        catch(NumberFormatException failure){throw new IOException("stop_fence_invalid");}
    }
}
