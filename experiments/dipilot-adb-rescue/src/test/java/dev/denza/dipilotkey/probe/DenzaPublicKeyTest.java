package dev.denza.dipilotkey.probe;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

public class DenzaPublicKeyTest {
    static String fixture() throws Exception {
        return DenzaPublicKey.PREFIX + Base64.getEncoder().encodeToString(
                DipilotIdentity.keyPair().getPublic().getEncoded());
    }

    @Test public void androidEncodingMatchesKnownIdentityAndFingerprint() throws Exception {
        DenzaPublicKey key = DenzaPublicKey.read(fixture());
        assertEquals(DipilotIdentity.fingerprint(), key.fingerprint);
        assertTrue(key.blob.equals(DipilotIdentity.androidPublicKeyBase64()));
        assertTrue(key.payload.endsWith(" denza-apps@denza"));
    }

    @Test public void shellScriptExtractsPublicMemberAndDoesNotModifyOrExportPrivateMember() throws Exception {
        Path root = Files.createTempDirectory("denza-public-test");
        Path dir = Files.createDirectory(root.resolve("no_backup"));
        Path file = dir.resolve("adb_auth_key_v1");
        try {
            byte[] secret = "PRIVATE_MEMBER_MUST_NOT_LEAK".repeat(40).getBytes(StandardCharsets.US_ASCII);
            byte[] publicDer = DipilotIdentity.keyPair().getPublic().getEncoded();
            try (DataOutputStream data = new DataOutputStream(Files.newOutputStream(file))) {
                data.writeInt(0x44414b31);
                data.writeInt(secret.length);
                data.write(secret);
                data.writeInt(publicDer.length);
                data.write(publicDer);
            }
            byte[] before = Files.readAllBytes(file);
            String output = runScript(root);
            assertTrue(output.trim().equals(fixture()));
            assertFalse(output.contains("PRIVATE_MEMBER"));
            assertArrayEquals(before, Files.readAllBytes(file));
        } finally {
            Files.deleteIfExists(file); Files.deleteIfExists(dir); Files.deleteIfExists(root);
        }
    }

    @Test public void legacyPrefsExtractOnlyTheNamedPublicValue() throws Exception {
        Path root = Files.createTempDirectory("denza-prefs-test");
        Path dir = Files.createDirectory(root.resolve("shared_prefs"));
        Path file = dir.resolve("adb_auth.xml");
        try {
            String der = fixture().substring(DenzaPublicKey.PREFIX.length());
            Files.writeString(file, "<map><string name=\"private_pkcs8\">PRIVATE_MUST_NOT_LEAK</string>"
                    + "<string name=\"public_x509\">" + der + "</string></map>");
            String output = runScript(root);
            assertTrue(output.trim().equals(fixture()));
            assertFalse(output.contains("PRIVATE"));
        } finally {
            Files.deleteIfExists(file); Files.deleteIfExists(dir); Files.deleteIfExists(root);
        }
    }

    private static String runScript(Path dir) throws Exception {
        Process process = new ProcessBuilder("/bin/sh", "-c", DenzaPublicKey.READ_SCRIPT)
                .directory(dir.toFile()).redirectErrorStream(true).start();
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Public extraction script timed out");
        }
        assertEquals(0, process.exitValue());
        return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test public void privateValueOrInvalidOrDifferentRsaSizeCannotBecomeAnAdbKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        String small = DenzaPublicKey.PREFIX + Base64.getEncoder().encodeToString(
                generator.generateKeyPair().getPublic().getEncoded());
        for (String value : new String[]{null, "run-as: package not debuggable", "DENZA_PUBLIC_KEY=AAAA",
                "private_pkcs8=" + fixture().substring(DenzaPublicKey.PREFIX.length()), small}) {
            try { DenzaPublicKey.read(value); fail("Should reject invalid public key"); }
            catch (Exception expected) { }
        }
    }

    @Test public void lastFingerprintIsAnObservationAndXmlMetadataAloneIsNotTrust() throws Exception {
        DenzaPublicKey key = DenzaPublicKey.read(fixture());
        assertEquals(key.fingerprint, DenzaPublicKey.lastFingerprint(
                "  last_key_received=" + key.fingerprint.toLowerCase(java.util.Locale.ROOT) + "\n"));
        assertNull(DenzaPublicKey.lastFingerprint("user_keys=" + key.payload));
        assertNull(key.stored("keystore=<adbKey key=\"" + key.payload + "\"/>"));
        assertEquals(Boolean.FALSE, key.stored("user_keys=\nsystem_keys=\nkeystore=<adbKey key=\""
                + key.payload + "\"/>"));
        assertEquals(Boolean.TRUE, key.stored("user_keys=" + key.blob + " different-comment\n"
                + "system_keys=\nkeystore=\n"));
        assertEquals(Boolean.TRUE, key.stored("user_keys=\nsystem_keys=" + key.blob + "\nkeystore=\n"));
        assertNull(key.stored("Permission Denial: dumpsys adb"));
        assertNull(key.stored("user_keys=truncated"));
    }
}
