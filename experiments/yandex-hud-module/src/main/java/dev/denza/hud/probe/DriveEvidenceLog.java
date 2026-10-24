package dev.denza.hud.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Exact byte bound: active file plus one predecessor. One recorder worker owns writes. */
final class DriveEvidenceLog {
    private final File file;
    private final long limit;
    DriveEvidenceLog(File file, long limit) { this.file = file; this.limit = limit; }
    void append(String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new IOException("Evidence record exceeds log limit");
        if (file.length() + bytes.length > limit) {
            File old = new File(file.getPath() + ".1");
            if (old.exists() && !old.delete()) throw new IOException("Cannot remove old evidence log");
            if (file.exists() && !file.renameTo(old)) throw new IOException("Cannot rotate evidence log");
        }
        try (FileOutputStream out = new FileOutputStream(file, true)) { out.write(bytes); }
    }
}
