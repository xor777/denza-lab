package dev.denza.dipilotkey.probe;

import static org.junit.Assert.*;
import org.junit.Test;

public class AdbStateTest {
    @Test public void acceptsBothDumpFormatsAndNeverPrintsKeyBodies() throws Exception {
        DenzaPublicKey key = DenzaPublicKey.read(DenzaPublicKeyTest.fixture());
        for (String dump : new String[]{
                "connected_to_adb=true\nlast_key_received=" + key.fingerprint + "\nuser_keys=" + key.payload
                        + "\nsystem_keys=\nkeystore=\n",
                "USB Debugging State:\n  Connected to adbd: true\n  Last key received: " + key.fingerprint
                        + "\n  User keys:\n" + key.payload + "\n  System keys:\n\n"}) {
            AdbState state = AdbState.read(dump);
            assertEquals(key.fingerprint, state.fingerprint);
            assertEquals(Boolean.TRUE, state.threadPresent);
            assertEquals(Boolean.TRUE, state.stored(key));
            assertTrue(state.matchingKey("").startsWith(key.blob));
            assertFalse(state.describe(key).contains(key.blob));
        }
    }

    @Test public void xmlMetadataCanIdentifyAQueueKeyButDoesNotProveStoredTrust() throws Exception {
        DenzaPublicKey key = DenzaPublicKey.read(DenzaPublicKeyTest.fixture());
        AdbState state = AdbState.read("last_key_received=" + key.fingerprint + "\nuser_keys=\nsystem_keys=\n"
                + "keystore=<adbKey key=\"" + key.payload + "\"/>\n");
        assertEquals(Boolean.FALSE, state.stored(key));
        assertTrue(state.matchingKey("").startsWith(key.blob));
    }

    @Test public void knownDifferentFingerprintCannotTurnAnyKeyIntoTheCurrentCandidate() throws Exception {
        DenzaPublicKey key = DenzaPublicKey.read(DenzaPublicKeyTest.fixture());
        AdbState state = AdbState.read("last_key_received=" + "00:".repeat(15) + "00\nuser_keys="
                + key.payload + "\nsystem_keys=\nkeystore=\n");
        assertNull(state.matchingKey("Logging key " + key.payload + ", state = 1"));
    }

    @Test public void nullFingerprintAndMissingOrDeniedDumpStayUnknown() throws Exception {
        DenzaPublicKey key = DenzaPublicKey.read(DenzaPublicKeyTest.fixture());
        assertNull(AdbState.read("Last key received: null\nConnected to adbd: false\n").fingerprint);
        assertEquals(Boolean.FALSE, AdbState.read("Connected to adbd: false\n").threadPresent);
        assertNull(AdbState.read("Can't find service: adb").stored(key));
        assertNull(AdbState.read("Permission Denial").stored(key));
    }
}
