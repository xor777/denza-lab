package dev.denza.disharebridge;

import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AdbKeyStoreTest {
    @Test
    public void canonicalKeyFileRoundTripsTheSameIdentity() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair original = generator.generateKeyPair();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        AdbKeyStore.writeKeyPair(output, original);
        KeyPair restored = AdbKeyStore.readKeyPair(
                new ByteArrayInputStream(output.toByteArray()));

        assertArrayEquals(original.getPrivate().getEncoded(), restored.getPrivate().getEncoded());
        assertArrayEquals(original.getPublic().getEncoded(), restored.getPublic().getEncoded());
    }

    /**
     * The fingerprint must be spelled the way the vehicle's prompt spells it.
     *
     * <p>Its only job is to be compared, by eye, against the string in the "Allow USB debugging?"
     * dialog while a person is standing at the car. A different case or separator makes that
     * comparison fail on a key that actually matches.
     */
    @Test
    public void fingerprintIsFormattedLikeTheSystemPrompt() {
        byte[] digest = new byte[] {
                (byte) 0x0a, (byte) 0xbc, (byte) 0xde, (byte) 0xf0,
                (byte) 0x12, (byte) 0x34, (byte) 0x56, (byte) 0x78,
                (byte) 0x9a, (byte) 0xbc, (byte) 0xde, (byte) 0xf0,
                (byte) 0x11, (byte) 0x22, (byte) 0x33, (byte) 0xff,
        };
        assertEquals(
                "0A:BC:DE:F0:12:34:56:78:9A:BC:DE:F0:11:22:33:FF",
                AdbKeyStore.formatFingerprint(digest));
    }
}
