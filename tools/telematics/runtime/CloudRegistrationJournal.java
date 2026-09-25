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
import java.util.Arrays;

/** A crash marker, not a cache of identity or a promise of factory registration. */
public final class CloudRegistrationJournal implements CloudRuntimeSupervisor.Journal {
    private static final byte[] MARKER="custom_registration_possible=1\n".getBytes(StandardCharsets.US_ASCII);
    private final Path file;
    public CloudRegistrationJournal(Path file) { this.file=file; }
    @Override public void customMayRegister() throws IOException {
        // The supervisor owns the version-independent kernel lock before this call.
        // No temp-file rename: a partial marker is conservatively an unresolved
        // registration too. The next start must sync the complete marker first.
        try(FileChannel channel=FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            Files.setPosixFilePermissions(file,PosixFilePermissions.fromString("rw-------"));
            ByteBuffer bytes=ByteBuffer.wrap(MARKER);
            channel.position(0);
            while(bytes.hasRemaining()) channel.write(bytes);
            channel.truncate(MARKER.length);
            channel.force(true);
        }
        try(FileChannel directory=FileChannel.open(file.toAbsolutePath().getParent(),StandardOpenOption.READ)) {
            directory.force(true);
        }
    }
    /** Anything present is a debt; only an explicit completed registration can resolve it. */
    public boolean registrationUncertain() { return Files.exists(file,LinkOption.NOFOLLOW_LINKS); }
}
