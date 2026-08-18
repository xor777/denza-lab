package dev.denza.disharebridge;

import static org.junit.Assert.assertArrayEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

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
}
