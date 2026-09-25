package dev.denza.apps.feature.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudServiceStartPolicyTest {
    @Test fun staleFactoryRestartCannotCreateACustomLease() {
        assertFalse(CloudServiceStartPolicy.accept(custom = true, pendingDisable = false,
            alreadyStarted = false, explicitCustomStart = false))
    }

    @Test fun explicitCustomStartAndPendingCleanupRemainAllowed() {
        assertTrue(CloudServiceStartPolicy.accept(true, false, false, true))
        assertTrue(CloudServiceStartPolicy.accept(true, true, false, false))
        assertTrue(CloudServiceStartPolicy.accept(true, false, true, false))
        assertTrue(CloudServiceStartPolicy.accept(false, false, false, false))
    }

    @Test fun bootCleanupCannotTurnSavedOnPendingStopIntoANewStart() {
        val interrupted = CloudLinkRequest(enabled = true, pendingDisable = true)
        assertTrue(CloudServiceStartPolicy.deferCleanupOnlyRestart(
            custom = true, request = interrupted, serviceAlive = false,
            explicitCustomStart = false))
        val cleanupOnly = interrupted.request(false)
        assertFalse(cleanupOnly.enabled)
        assertTrue(cleanupOnly.pendingDisable)
        assertEquals(CloudAppOpenPolicy.Action.RESTART,
            CloudAppOpenPolicy.action(CloudSimMode.CUSTOM, cleanupOnly,
                serviceAlive = true, pilot = true, identityValid = true,
                terminal = null, resumePending = true, startPending = false))
        assertFalse(CloudServiceStartPolicy.deferCleanupOnlyRestart(
            true, interrupted, serviceAlive = false, explicitCustomStart = true))
        assertFalse(CloudServiceStartPolicy.deferCleanupOnlyRestart(
            true, interrupted, serviceAlive = true, explicitCustomStart = false))
        assertFalse(CloudServiceStartPolicy.deferCleanupOnlyRestart(
            false, interrupted, serviceAlive = false, explicitCustomStart = false))
    }
}
