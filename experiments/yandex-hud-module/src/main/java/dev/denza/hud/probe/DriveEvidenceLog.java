package dev.denza.hud.probe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Exact byte bound: the active file plus {@code generations - 1} predecessors ({@code .1} newest).
 * At least {@code (generations - 1) * limit} bytes of the newest records are always retained.
 * One recorder worker owns writes.
 */
final class DriveEvidenceLog {
    private final File file;
    private final long limit;
    private final int generations;
    DriveEvidenceLog(File file, long limit) { this(file, limit, 2); }
    DriveEvidenceLog(File file, long limit, int generations) {
        if (generations < 2) throw new IllegalArgumentException("generations");
        this.file = file; this.limit = limit; this.generations = generations;
    }
    void append(String line) throws IOException {
        byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new IOException("Evidence record exceeds log limit");
        if (file.length() + bytes.length > limit) rotate(file, generations);
        try (FileOutputStream out = new FileOutputStream(file, true)) { out.write(bytes); }
    }
    /** file -> .1 -> .2 ... ; the oldest of {@code generations - 1} predecessors is deleted. */
    static void rotate(File file, int generations) throws IOException {
        File oldest = new File(file.getPath() + "." + (generations - 1));
        if (oldest.exists() && !oldest.delete()) throw new IOException("Cannot remove old evidence log");
        for (int i = generations - 2; i >= 1; i--) {
            File from = new File(file.getPath() + "." + i);
            if (from.exists() && !from.renameTo(new File(file.getPath() + "." + (i + 1)))) throw new IOException("Cannot rotate evidence log");
        }
        if (file.exists() && !file.renameTo(new File(file.getPath() + ".1"))) throw new IOException("Cannot rotate evidence log");
    }
}
