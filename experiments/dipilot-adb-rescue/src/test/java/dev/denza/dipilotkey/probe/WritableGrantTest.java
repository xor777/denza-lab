package dev.denza.dipilotkey.probe;

import static org.junit.Assert.*;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public class WritableGrantTest {
    @Test public void appendsOnlyThePublicKeyPreservingExistingBytesAndModeAndDoesNotDuplicateIt() throws Exception {
        DenzaPublicKey key = DenzaPublicKey.read(DenzaPublicKeyTest.fixture());
        Path file = Files.createTempFile("denza-trust-test", ".txt");
        try {
            Files.writeString(file, "existing-public-key other-app\n");
            var mode = Files.getPosixFilePermissions(file);
            assertTrue(run(file, key).contains("TARGET_KEY_ADDED"));
            byte[] after = Files.readAllBytes(file);
            assertTrue(new String(after, StandardCharsets.US_ASCII).startsWith("existing-public-key other-app\n"));
            assertEquals(mode, Files.getPosixFilePermissions(file));
            assertTrue(run(file, key).contains("TARGET_KEY_ALREADY"));
            assertTrue(java.util.Arrays.equals(after, Files.readAllBytes(file)));
        } finally { Files.deleteIfExists(file); }
    }

    @Test public void missingFileIsNotCreated() throws Exception {
        Path dir = Files.createTempDirectory("denza-no-trust-test");
        Path file = dir.resolve("absent");
        try {
            assertTrue(run(file, DenzaPublicKey.read(DenzaPublicKeyTest.fixture())).contains("NOT_WRITABLE"));
            assertFalse(Files.exists(file));
        } finally { Files.deleteIfExists(dir); }
    }

    private static String run(Path file, DenzaPublicKey key) throws Exception {
        String script = AndroidRescueDevice.writableGrantCommand(key)
                .replace("/data/misc/adb/adb_keys", file.toString());
        Process process = new ProcessBuilder("/bin/sh", "-c", script).redirectErrorStream(true).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Grant timed out"); }
        assertEquals(0, process.exitValue());
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);
    }
}
