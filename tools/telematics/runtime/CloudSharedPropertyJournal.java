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

/** Exact shared-property debt. It never invents a value to restore. */
final class CloudSharedPropertyJournal {
    private static final String KEY="sys.cloud.remote_controling";
    private final Path file;
    CloudSharedPropertyJournal(Path file){this.file=file;}
    boolean pending(){return Files.exists(file,LinkOption.NOFOLLOW_LINKS);}
    void beforeWrite(String previous)throws IOException{
        // This exact native effect writes 0. A preimage of 1 or empty could
        // belong to stock; the worker has no authority to clear it. Admit
        // only an idempotent write and never attempt a guessed restoration.
        if(!"0".equals(previous))throw new IOException("shared_property_original_unqualified");
        if(pending()){
            // The first preimage is the only one valid for crash cleanup.
            if(!baseline().equals(previous))throw new IOException("shared_property_preimage_changed");
            return;
        }
        byte[] data=(KEY+"=0\n")
            .getBytes(StandardCharsets.US_ASCII);
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
            ByteBuffer block=ByteBuffer.wrap(data);while(block.hasRemaining())channel.write(block);
            channel.force(true);
        }
        syncDirectory();
    }
    String baseline()throws IOException{
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>64)
            throw new IOException("shared_property_journal_invalid");
        String raw=new String(Files.readAllBytes(file),StandardCharsets.US_ASCII);
        if(raw.equals(KEY+"=0\n"))return "0";
        throw new IOException("shared_property_journal_invalid");
    }
    void resolveAfterExit(String observed)throws IOException{
        if(!pending())return;
        baseline();
        // The admitted custom write is 0 over 0. A later 1 belongs to an
        // external writer; local cleanup must leave it intact and may drop
        // only our journal. Unknown values still retain the debt.
        if(!"0".equals(observed)&&!"1".equals(observed))
            throw new IOException("shared_property_cleanup_uncertain");
        Files.delete(file);syncDirectory();
    }
    private void syncDirectory()throws IOException{
        try(FileChannel directory=FileChannel.open(file.toAbsolutePath().getParent(),StandardOpenOption.READ)){
            directory.force(true);
        }
    }
}
