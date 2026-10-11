package dev.denza.dipilotkey.probe;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.DataOutputStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;

public class TargetIdentityCheckTest {
    @Test public void readsExistingPairWithoutChangingTheFile() throws Exception {
        KeyPair pair = pair();
        Path dir = Files.createTempDirectory("denza-check-pair");
        Path backup = Files.createDirectory(dir.resolve("no_backup"));
        Path file = backup.resolve("adb_auth_key_v1");
        try {
            try (DataOutputStream data = new DataOutputStream(Files.newOutputStream(file))) {
                data.writeInt(0x44414b31);
                data.writeInt(pair.getPrivate().getEncoded().length); data.write(pair.getPrivate().getEncoded());
                data.writeInt(pair.getPublic().getEncoded().length); data.write(pair.getPublic().getEncoded());
            }
            byte[] before = Files.readAllBytes(file);
            KeyPair loaded = TargetIdentityCheck.readPair(dir.toFile());
            assertTrue(java.util.Arrays.equals(pair.getPublic().getEncoded(), loaded.getPublic().getEncoded()));
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file)));
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(backup); Files.deleteIfExists(dir); }
    }

    @Test public void signatureAuthenticatesTheExactChallengeAndShellIsActuallyOpened() throws Exception {
        assertEquals("TRUSTED", exchange(true));
    }

    @Test public void legacyPreferencesReadTheExistingPairWithoutMigration() throws Exception {
        KeyPair pair = pair();
        Path dir = Files.createTempDirectory("denza-check-legacy");
        Path prefs = Files.createDirectory(dir.resolve("shared_prefs"));
        Path file = prefs.resolve("adb_auth.xml");
        String xml = "<map><string name=\"private_pkcs8\">"
                + java.util.Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "</string><string name=\"public_x509\">"
                + java.util.Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "</string></map>";
        try {
            Files.writeString(file, xml);
            KeyPair loaded = TargetIdentityCheck.readPair(dir.toFile());
            assertArrayEquals(pair.getPublic().getEncoded(), loaded.getPublic().getEncoded());
            assertEquals(xml, Files.readString(file));
            assertFalse(Files.exists(dir.resolve("no_backup")));
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(prefs); Files.deleteIfExists(dir); }
    }

    @Test public void atomicBackupDefinesIdentityAndIsNotRenamedOrRemoved() throws Exception {
        KeyPair pair = pair();
        Path dir = Files.createTempDirectory("denza-check-backup");
        Path backup = Files.createDirectory(dir.resolve("no_backup"));
        Path base = backup.resolve("adb_auth_key_v1");
        Path file = backup.resolve("adb_auth_key_v1.bak");
        try {
            Files.writeString(base, "incomplete update");
            try (DataOutputStream data = new DataOutputStream(Files.newOutputStream(file))) {
                data.writeInt(0x44414b31);
                data.writeInt(pair.getPrivate().getEncoded().length); data.write(pair.getPrivate().getEncoded());
                data.writeInt(pair.getPublic().getEncoded().length); data.write(pair.getPublic().getEncoded());
            }
            byte[] before = Files.readAllBytes(file);
            assertArrayEquals(pair.getPublic().getEncoded(), TargetIdentityCheck.readPair(dir.toFile()).getPublic().getEncoded());
            assertArrayEquals(before, Files.readAllBytes(file));
            assertEquals("incomplete update", Files.readString(base));
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(base); Files.deleteIfExists(backup); Files.deleteIfExists(dir); }
    }

    @Test public void rejectedSignatureStopsWithoutSubmittingPublicKeyOrOpeningShell() throws Exception {
        assertEquals("AUTH_REQUIRED", exchange(false));
    }

    private static String exchange(boolean allow) throws Exception {
        KeyPair pair = pair(); byte[] token = new byte[20]; new java.security.SecureRandom().nextBytes(token);
        try (ServerSocket server = new ServerSocket(0)) {
            CompletableFuture<Void> daemon = CompletableFuture.runAsync(() -> {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(4000);
                    assertEquals(PublicKeyRequest.CNXN, PublicKeyRequest.read(socket).command);
                    PublicKeyRequest.write(socket.getOutputStream(), PublicKeyRequest.AUTH, 1, 0, token);
                    PublicKeyRequest.Message auth = PublicKeyRequest.read(socket);
                    assertEquals(PublicKeyRequest.AUTH, auth.command); assertEquals(2, auth.arg0);
                    Cipher verify = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    verify.init(Cipher.DECRYPT_MODE, pair.getPublic());
                    byte[] digestInfo = verify.doFinal(auth.payload);
                    assertArrayEquals(token, java.util.Arrays.copyOfRange(digestInfo, digestInfo.length - 20, digestInfo.length));
                    if (!allow) {
                        PublicKeyRequest.write(socket.getOutputStream(), PublicKeyRequest.AUTH, 1, 0, token);
                        assertEquals(-1, socket.getInputStream().read());
                        return;
                    }
                    PublicKeyRequest.write(socket.getOutputStream(), PublicKeyRequest.CNXN, 0x01000000, 4096, new byte[0]);
                    PublicKeyRequest.Message open = PublicKeyRequest.read(socket);
                    assertEquals(0x4e45504f, open.command);
                    assertEquals("shell:printf " + TargetIdentityCheck.MARKER + '\0', new String(open.payload, StandardCharsets.US_ASCII));
                    PublicKeyRequest.write(socket.getOutputStream(), 0x59414b4f, 7, 1, new byte[0]);
                    PublicKeyRequest.write(socket.getOutputStream(), 0x45545257, 7, 1, TargetIdentityCheck.MARKER.getBytes(StandardCharsets.US_ASCII));
                    assertEquals(0x59414b4f, PublicKeyRequest.read(socket).command);
                    PublicKeyRequest.write(socket.getOutputStream(), 0x45534c43, 7, 1, new byte[0]);
                    assertEquals(0x45534c43, PublicKeyRequest.read(socket).command);
                } catch (Exception failed) { throw new RuntimeException(failed); }
            });
            String result;
            try (Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
                result = TargetIdentityCheck.exchange(socket, (RSAPrivateKey) pair.getPrivate());
            }
            daemon.get(5, TimeUnit.SECONDS);
            return result;
        }
    }

    private static KeyPair pair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
