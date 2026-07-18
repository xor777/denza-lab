package dev.denza.disharebridge;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

public final class LocalAdbClientTest {
    @Test
    public void authorizationPendingStopsHostFallback() {
        assertTrue(LocalAdbClient.isAuthorizationPending(
                new IOException("ADB authorization pending; confirm the debugging prompt")));
        assertFalse(LocalAdbClient.isAuthorizationPending(
                new IOException("Connection refused")));
    }

    @Test
    public void promptGateSuppressesDuplicateRequestsDuringCooldown() {
        LocalAdbClient.AuthorizationPromptGate gate =
                new LocalAdbClient.AuthorizationPromptGate(10L);

        assertTrue(gate.tryAcquire(100L));
        assertFalse(gate.tryAcquire(109L));
        assertTrue(gate.tryAcquire(110L));
    }
}
