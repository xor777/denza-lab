package dev.denza.dipilotkey.probe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.List;

public class DipilotRescueTest {
    @Test
    public void keyIsTheDipilotBlobAndFingerprint() throws Exception {
        assertTrue(DipilotIdentity.androidPublicKeyBase64()
                .startsWith(DipilotIdentity.PUBLIC_BLOB_PREFIX));
        assertEquals(DipilotIdentity.FINGERPRINT, DipilotIdentity.fingerprint());
    }

    @Test
    public void installedFileIsTheKeyStoreLayout() throws Exception {
        File directory = Files.createTempDirectory("dipilot-key").toFile();
        try {
            AdbKeyFile.install(directory);
            File stored = new File(directory, AdbKeyFile.FILE_NAME);
            try (DataInputStream input = new DataInputStream(new FileInputStream(stored))) {
                assertEquals(AdbKeyFile.MAGIC, input.readInt());
                byte[] pkcs8 = new byte[input.readInt()];
                input.readFully(pkcs8);
                KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                int publicLength = input.readInt();
                assertTrue(publicLength > 0);
            }
        } finally {
            for (File child : directory.listFiles()) {
                child.delete();
            }
            directory.delete();
        }
    }

    @Test
    public void pendingFollowsTheLaterMarker() {
        assertFalse(QueueDrain.pending("nothing here"));
        assertTrue(QueueDrain.pending("adbd_auth: prompt currently pending, skipping"));
        assertFalse(QueueDrain.pending(
                "prompt currently pending\nadbd_auth: no prompts to send"));
        assertTrue(QueueDrain.pending(
                "no prompts to send\nprompt currently pending, skipping"));
    }

    @Test
    public void firstDispatchedPromptIsPendingEvenWithoutASecondClient() {
        assertTrue(QueueDrain.pending(
                "adbd: adbd_auth: no prompts to send\n"
                        + "adbd: adbd_auth: prompting user for adb authentication"));
    }

    @Test
    public void approvedRequestIsNotReusedFromTheHistoricalLog() {
        String key = "G".repeat(200) + " wireless@adb";
        assertNull(QueueDrain.publicKey(
                "Logging key " + key + ", state = 1, alwaysAllow = false\n"
                        + "Logging key " + key + ", state = 2, alwaysAllow = true", ""));
    }

    @Test
    public void deniedRequestIsNotReusedFromTheHistoricalLog() {
        assertNull(QueueDrain.publicKey(
                "Logging key " + "H".repeat(200) + ", state = 1, alwaysAllow = false\n"
                        + "Logging key null, state = 3, alwaysAllow = false", ""));
    }

    @Test
    public void nativeQueueMessagesAreReadFromTheAdbdTagAndAllBuffers() {
        assertTrue(QueueDrain.LOGCAT.contains("adbd:*"));
        assertTrue(QueueDrain.LOGCAT.contains("-b all"));
    }

    @Test
    public void missingOrAnsweredLogDoesNotProveAnEmptyQueue() {
        assertEquals(QueueDrain.State.UNKNOWN, QueueDrain.state(null));
        assertEquals(QueueDrain.State.UNKNOWN, QueueDrain.state(""));
        String key = "I".repeat(200);
        assertEquals(QueueDrain.State.UNKNOWN, QueueDrain.state(
                "Logging key " + key + ", state = 1\n"
                        + "Logging key " + key + ", state = 2"));
        assertEquals(QueueDrain.State.DRAINED, QueueDrain.state(
                "Logging key " + key + ", state = 1\n"
                        + "adbd_auth: no prompts to send\n"
                        + "Logging key " + key + ", state = 2"));
    }

    @Test
    public void duplicateKeysHaveDifferentRequestTokens() {
        String key = "J".repeat(200);
        String first = "10-08 12:00:00.000 Logging key " + key + ", state = 1";
        String second = "10-08 12:00:01.000 Logging key " + key + ", state = 1";
        assertEquals(first, QueueDrain.requestToken(first));
        assertEquals(second, QueueDrain.requestToken(first + "\n"
                + "Logging key " + key + ", state = 2\n" + second));
        assertEquals(key, QueueDrain.publicKey(first + "\n"
                + "Logging key " + key + ", state = 2\n" + second, ""));
    }

    @Test
    public void binderReplyMustHaveAZeroExceptionCode() {
        assertTrue(QueueDrain.binderAccepted("Result: Parcel(00000000 '....')"));
        assertTrue(QueueDrain.binderAccepted(
                "Result: Parcel(\n  0x00000000: 00000000 00000000 '........'\n)"));
        assertFalse(QueueDrain.binderAccepted(
                "Result: Parcel(\n  0x00000000: ffffffff 00000020 '........'\n)"));
        assertFalse(QueueDrain.binderAccepted(""));
        assertFalse(QueueDrain.binderAccepted(null));
    }

    @Test
    public void diagnosticLogsDoNotExposePublicKeys() {
        String key = "K".repeat(700) + " wireless@adb";
        assertEquals("Logging key [ADB key], state = 1", QueueDrain.redact(
                "Logging key " + key + ", state = 1"));
    }

    @Test
    public void drainedMarkerInvalidatesAnOlderKeyEvenWhenANewPromptHasNoKeyLine() {
        String log = "Logging key " + "L".repeat(200) + ", state = 1\n"
                + "adbd_auth: no prompts to send\n"
                + "adbd_auth: prompting user for adb authentication";
        assertEquals(QueueDrain.State.PENDING, QueueDrain.state(log));
        assertNull(QueueDrain.publicKey(log, ""));
        assertNull(QueueDrain.requestToken(log));
    }

    @Test
    public void publicKeyIsTheLatestConfirmationNotAnOlderOne() {
        String older = "B".repeat(200);
        String current = "C".repeat(220);
        String log = "Logging key " + older + " other@adb, state = 1, alwaysAllow = false\n"
                + "Logging key " + current + " wireless@adb, state = 1, alwaysAllow = false\n"
                + "Logging key " + "D".repeat(200) + " wireless@adb, state = 2, alwaysAllow = true\n";
        assertEquals(current + " wireless@adb", QueueDrain.publicKey(log, ""));
    }

    @Test
    public void connectedKeyIsNotAPromptToApprove() {
        String log = "Logging key " + "E".repeat(200) + " wireless@adb, state = 2, alwaysAllow = true\n";
        assertNull(QueueDrain.publicKey(log, ""));
    }

    @Test
    public void dumpsysKeyIsUsedWhenTheLogHasNone() {
        String body = "F".repeat(200);
        String dump = "cmp=com.android.systemui/.usb.UsbDebuggingActivity key=" + body + " wireless@adb";
        assertEquals(body + " wireless@adb", QueueDrain.publicKey("", dump));
    }

    @Test
    public void allowCommandIsAlwaysAllowOnTransactionOne() {
        String key = "A".repeat(200) + " wireless@adb";
        assertEquals(
                "service call adb 1 i32 1 s16 '" + key + "'",
                QueueDrain.allowCommand(key));
        assertNull(QueueDrain.allowCommand("short"));
        assertNull(QueueDrain.allowCommand("A".repeat(200) + " bad'quote"));
        assertEquals(5, QueueDrain.MAX_ALLOWS);
    }

    @Test
    public void russianDebuggingDialogClicksAllowAndTheAlwaysBox() {
        PromptDecision decision = PromptDecision.decide(
                "com.android.systemui",
                "Отладка по USB\nЦифровой отпечаток ключа RSA:\nAA:BB\n",
                List.of(
                        node(null, "Отладка по USB", false, false),
                        node(null, "Всегда разрешать отладку с этого компьютера", true, false),
                        node("android:id/button1", "Разрешить", false, false),
                        node("android:id/button2", "Отмена", false, false)));
        assertEquals(1, decision.checkIndex);
        assertEquals(2, decision.clickIndex);
    }

    @Test
    public void checkedAlwaysBoxIsLeftAlone() {
        PromptDecision decision = PromptDecision.decide(
                "com.android.systemui",
                "Allow USB debugging?\nThe computer's RSA key fingerprint is:\n",
                List.of(
                        node(null, "Always allow from this computer", true, true),
                        node("android:id/button1", "OK", false, false),
                        node("android:id/button2", "Cancel", false, false)));
        assertEquals(-1, decision.checkIndex);
        assertEquals(1, decision.clickIndex);
    }

    @Test
    public void negativeButtonIsNeverTheTarget() {
        PromptDecision decision = PromptDecision.decide(
                "com.android.systemui",
                "Отладка по USB",
                List.of(
                        node("android:id/button1", "Отмена", false, false),
                        node("android:id/button2", "Разрешить", false, false)));
        assertEquals(1, decision.clickIndex);
    }

    @Test
    public void otherSystemUiWindowsAreNotApproved() {
        PromptDecision decision = PromptDecision.decide(
                "com.android.systemui",
                "Громкость",
                List.of(node("android:id/button1", "OK", false, false)));
        assertEquals(-1, decision.clickIndex);
        assertEquals(-1, PromptDecision.decide(
                "com.android.settings",
                "Отладка по USB",
                List.of(node("android:id/button1", "Разрешить", false, false))).clickIndex);
    }

    @Test
    public void authorizationFailureIsNotReportedAsADeadPort() {
        assertEquals(
                PortRescue.Access.AUTHORIZATION_REQUIRED,
                PortRescue.classify(new IOException(
                        "ADB authorization pending; confirm the ADB request")));
        assertEquals(
                PortRescue.Access.UNAVAILABLE,
                PortRescue.classify(new ConnectException("Connection refused")));
    }

    private static PromptDecision.Node node(
            String viewId, String text, boolean checkable, boolean checked) {
        return new PromptDecision.Node(viewId, text, checkable, checked);
    }
}
