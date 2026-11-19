package dev.denza.disharebridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;

import org.junit.Test;

public final class AdbKeyStoreTest {
    private static KeyPair pair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** The two copies on a disk the test describes: what each holds, and what was written. */
    private static final class Disk implements AdbKeyStore.Copies {
        KeyPair canonical;
        Exception canonicalFails;
        KeyPair legacy;
        GeneralSecurityException legacyFails;
        boolean writeFails;
        KeyPair written;
        int generated;

        @Override
        public KeyPair canonical() throws IOException, GeneralSecurityException {
            if (canonicalFails instanceof IOException) {
                throw (IOException) canonicalFails;
            }
            if (canonicalFails instanceof GeneralSecurityException) {
                throw (GeneralSecurityException) canonicalFails;
            }
            return canonical;
        }

        @Override
        public KeyPair legacy() throws GeneralSecurityException {
            if (legacyFails != null) {
                throw legacyFails;
            }
            return legacy;
        }

        @Override
        public void writeCanonical(KeyPair keyPair) throws GeneralSecurityException {
            if (writeFails) {
                throw new GeneralSecurityException("disk full");
            }
            written = keyPair;
            canonical = keyPair;
            canonicalFails = null;
        }

        @Override
        public KeyPair generate() throws GeneralSecurityException {
            generated++;
            return pair();
        }
    }

    /**
     * Review finding 12: a canonical key file cut short by a crash or a full disk made every load
     * fail, for good, with the same identity intact in the legacy copy beside it. It falls back to
     * that copy and writes the file again from it; nothing makes a new key.
     */
    @Test
    public void aDamagedKeyFileFallsBackToTheLegacyCopyAndIsRepaired() throws Exception {
        Disk disk = new Disk();
        disk.legacy = pair();
        disk.canonicalFails = new EOFException("cut short");

        assertSame(disk.legacy, AdbKeyStore.load(disk));
        assertSame("the file written again from the copy", disk.legacy, disk.written);
        assertEquals(0, disk.generated);
        // The next load reads the repaired file.
        disk.legacy = null;
        assertSame(disk.written, AdbKeyStore.load(disk));
    }

    @Test
    public void aKeyFileWhoseHalvesDoNotBelongTogetherIsDamaged() throws Exception {
        Disk disk = new Disk();
        disk.legacy = pair();
        disk.canonical = new KeyPair(pair().getPublic(), disk.legacy.getPrivate());

        assertSame(disk.legacy, AdbKeyStore.load(disk));
        assertSame(disk.legacy, disk.written);
    }

    /** A copy that cannot be read may still be the identity the car trusts: no new key replaces it. */
    @Test
    public void noKeyIsMadeWhileACopyIsThere() throws Exception {
        Disk damagedAlone = new Disk();
        damagedAlone.canonicalFails = new GeneralSecurityException("Unrecognized ADB key file");
        Disk bothDamaged = new Disk();
        bothDamaged.canonicalFails = new EOFException("cut short");
        KeyPair legacy = pair();
        bothDamaged.legacy = new KeyPair(pair().getPublic(), legacy.getPrivate());
        Disk legacyIncomplete = new Disk();
        legacyIncomplete.legacyFails = new GeneralSecurityException("Incomplete persisted ADB key pair");

        for (Disk disk : new Disk[] {damagedAlone, bothDamaged, legacyIncomplete}) {
            try {
                AdbKeyStore.load(disk);
                fail();
            } catch (GeneralSecurityException expected) {
                // Every ADB call fails until a copy is readable; none speaks for a new identity.
            }
            assertEquals(0, disk.generated);
            assertNull(disk.written);
        }
    }

    @Test
    public void theFirstStartMakesOneKeyAndALegacyCopyMovesIntoTheFile() throws Exception {
        Disk empty = new Disk();
        AdbKeyStore.load(empty);
        assertEquals(1, empty.generated);

        Disk legacyOnly = new Disk();
        legacyOnly.legacy = pair();
        assertSame(legacyOnly.legacy, AdbKeyStore.load(legacyOnly));
        assertSame(legacyOnly.legacy, legacyOnly.written);
        assertEquals(0, legacyOnly.generated);
    }

    /** A repair the disk refuses still loads the identity; the next load tries the write again. */
    @Test
    public void aRepairThatCannotBeWrittenStillLoadsTheIdentity() throws Exception {
        Disk disk = new Disk();
        disk.legacy = pair();
        disk.canonicalFails = new EOFException("cut short");
        disk.writeFails = true;

        assertSame(disk.legacy, AdbKeyStore.load(disk));
        assertEquals(0, disk.generated);
    }

    @Test
    public void anIntactKeyFileIsAllThatIsRead() throws Exception {
        Disk disk = new Disk();
        disk.canonical = pair();
        disk.legacyFails = new GeneralSecurityException("not to be read");

        assertSame(disk.canonical, AdbKeyStore.load(disk));
        assertNull(disk.written);
    }

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
