package dev.denza.dipilotkey.probe;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.IOException;

public class DenzaKeyRescueTest {
    @Test public void previousAttemptCanDrainAKnownPrecedingKeyAndAuthorizeTargetWithoutAnotherSubmission() throws Exception {
        Fake device = new Fake(); device.remembered = true; device.blocked = true;
        java.security.KeyPairGenerator rsa = java.security.KeyPairGenerator.getInstance("RSA"); rsa.initialize(2048);
        device.preceding = DenzaPublicKey.read(DenzaPublicKey.PREFIX + java.util.Base64.getEncoder()
                .encodeToString(rsa.generateKeyPair().getPublic().getEncoded()));
        device.last = device.preceding.fingerprint;
        assertTrue(run(device).contains("Denza Apps: shell и повторное подключение: ДА"));
        assertEquals(0, device.requests); assertEquals(2, device.allows);
    }

    @Test public void unavailableFingerprintDoesNotPreventOneExactTargetApproval() throws Exception {
        Fake device = new Fake(); device.hideFingerprint = true;
        String text = run(device);
        assertTrue(text.contains("Отпечаток системы недоступен"));
        assertTrue(text.contains("Denza Apps: shell и повторное подключение: ДА"));
        assertEquals(1, device.requests); assertEquals(1, device.allows);
    }

    @Test public void writableFileFallbackIsFollowedBySignedReconnectVerification() throws Exception {
        Fake device = new Fake(); device.blocked = true; device.writable = true;
        String text = run(device);
        assertTrue(text.contains("Файл доверия: ключ добавлен"));
        assertTrue(text.contains("Denza Apps: shell и повторное подключение: ДА"));
    }

    @Test public void exhaustedCombinedApprovalBudgetDoesNotAddAnotherQueueEntry() throws Exception {
        Fake device = new Fake();
        StringBuilder report = new StringBuilder();
        new DenzaKeyRescue(device, 0).run(report, ignored -> { });
        assertEquals(0, device.requests); assertEquals(0, device.allows);
    }
    @Test public void alreadyStoredIdentityNeedsNeitherSubmissionNorApproval() throws Exception {
        Fake device = new Fake(); device.stored = true;
        String text = run(device);
        assertTrue(text.contains("Denza Apps: shell и повторное подключение: ДА"));
        assertEquals(0, device.requests); assertEquals(0, device.allows);
        assertFalse(device.remembered);
        assertFalse(text.contains(device.key.blob));
    }

    @Test public void matchingLastFingerprintCanBeApprovedWithoutAddingAnotherRequest() throws Exception {
        Fake device = new Fake(); device.last = device.key.fingerprint;
        String text = run(device);
        assertEquals(0, device.requests); assertEquals(1, device.allows);
        assertTrue(text.contains("Denza Apps: shell и повторное подключение: ДА"));
    }

    @Test public void oneSubmissionKeepsSocketOpenForApprovalAndConfirmsStoredKey() throws Exception {
        Fake device = new Fake();
        String text = run(device);
        assertEquals(1, device.requests); assertEquals(1, device.allows);
        assertTrue(device.remembered); assertTrue(device.closed);
        assertTrue(text.contains("Denza Apps: shell и повторное подключение: ДА"));
        assertFalse(text.contains(device.key.blob));
    }

    @Test public void blockedDifferentFingerprintCannotBeApprovedAndSecondRunCannotAddARequest() throws Exception {
        Fake device = new Fake(); device.blocked = true;
        assertTrue(run(device).contains("Denza Apps: доступ не восстановлен"));
        assertTrue(device.closed); assertEquals(1, device.requests); assertEquals(0, device.allows);
        assertTrue(run(device).contains("повторно очередь не пополняю"));
        assertEquals(1, device.requests); assertEquals(0, device.allows);
        assertTrue(device.clock <= 10800);
    }

    @Test public void binderSuccessWithoutStorageChangeIsNotReportedAsTrust() throws Exception {
        Fake device = new Fake(); device.persist = false;
        String text = run(device);
        assertEquals(1, device.allows);
        assertTrue(text.contains("Binder принял вызов"));
        assertTrue(text.contains("Denza Apps: доступ не восстановлен"));
        assertFalse(text.contains("Denza Apps: shell и повторное подключение: ДА"));
    }

    @Test public void nonDebuggableTargetDoesNotSubmitOrApproveAnything() throws Exception {
        Fake device = new Fake(); device.publicOutput = "run-as: package not debuggable: dev.denza.apps";
        String text = run(device);
        assertTrue(text.contains("Denza Apps: ключ недоступен"));
        assertTrue(text.contains("сборка Denza Apps не debuggable"));
        assertEquals(0, device.requests); assertEquals(0, device.allows);
    }

    @Test public void unableToPersistLatchStopsBeforeSendingAndCanTryLater() throws Exception {
        Fake device = new Fake(); device.failRemember = true;
        assertTrue(run(device).contains("Denza Apps: проверка не удалась"));
        assertEquals(0, device.requests); assertFalse(device.remembered);
    }

    @Test public void cancellationClosesSubmittedSocketWithoutAnotherRequestOrApproval() throws Exception {
        Fake device = new Fake(); device.interrupt = true;
        try { run(device); fail("Should propagate cancellation"); }
        catch (InterruptedException expected) { }
        assertTrue(device.closed); assertTrue(device.remembered);
        assertEquals(1, device.requests); assertEquals(0, device.allows);
    }

    private static String run(Fake device) throws InterruptedException {
        StringBuilder text = new StringBuilder();
        new DenzaKeyRescue(device).run(text, ignored -> { });
        return text.toString();
    }

    private static final class Fake implements DenzaKeyRescue.Device {
        final DenzaPublicKey key;
        String publicOutput;
        String last = "00:".repeat(15) + "00";
        DenzaPublicKey preceding;
        boolean hideFingerprint, writable;
        boolean stored, blocked, remembered, closed, failRemember, interrupt;
        boolean persist = true;
        int requests, allows;
        long clock;
        Fake() throws Exception {
            publicOutput = DenzaPublicKeyTest.fixture();
            key = DenzaPublicKey.read(publicOutput);
        }
        @Override public String shell(String command, int timeoutMs) {
            if (command.equals(DenzaPublicKey.READ)) return publicOutput;
            if (command.equals(QueueDrain.LOGCAT)) return "adbd: adb client authorized";
            if (command.equals(DenzaPublicKey.DUMP)) {
                return "connected_to_adb=true\nlast_key_received=" + (hideFingerprint ? "null" : last) + "\nuser_keys="
                        + (stored ? key.payload : "") + "\nsystem_keys=\nkeystore="
                        + (preceding == null ? "" : preceding.payload) + "\n";
            }
            if (preceding != null && command.equals(QueueDrain.allowCommand(preceding.payload))) {
                assertEquals(preceding.fingerprint, last); allows++; last = key.fingerprint;
                return "Result: Parcel(00000000 '....')";
            }
            assertTrue(command.equals(QueueDrain.allowCommand(key.payload)));
            assertEquals(key.fingerprint, last);
            if (requests > 0) assertFalse(closed);
            allows++;
            stored = persist;
            return "Result: Parcel(00000000 '....')";
        }
        @Override public DenzaKeyRescue.Request requestPublicKey(String payload,
                DenzaKeyRescue.BeforeSend beforeSend) throws Exception {
            assertTrue(payload.equals(key.payload));
            beforeSend.run();
            assertTrue(remembered);
            requests++;
            if (!blocked) last = key.fingerprint;
            return () -> closed = true;
        }
        @Override public boolean targetRequestAttempted(String fingerprint) { return remembered; }
        @Override public String verifyTargetKey() {
            return "DENZA_TARGET_FP=" + key.fingerprint + "\nDENZA_TARGET_FIRST="
                    + (stored ? "TRUSTED" : "AUTH_REQUIRED") + "\nDENZA_TARGET_SECOND="
                    + (stored ? "TRUSTED" : "AUTH_REQUIRED") + "\nDENZA_TARGET_CHECK="
                    + (stored ? "TRUSTED" : "AUTH_REQUIRED");
        }
        @Override public String grantWritableKey(DenzaPublicKey key) {
            if (writable) { stored = true; return "TARGET_KEY_ADDED"; }
            return "NOT_WRITABLE";
        }
        @Override public void rememberTargetRequest(String fingerprint) throws Exception {
            if (failRemember) throw new IOException("Unable to persist");
            remembered = true;
        }
        @Override public long now() { return clock; }
        @Override public void pause(long millis) throws InterruptedException {
            if (interrupt) throw new InterruptedException();
            clock += millis;
        }
    }
}
