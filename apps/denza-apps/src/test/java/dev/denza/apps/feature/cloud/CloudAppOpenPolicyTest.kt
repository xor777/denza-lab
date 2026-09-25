package dev.denza.apps.feature.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudAppOpenPolicyTest {
    private fun action(request: CloudLinkRequest = CloudLinkRequest(enabled = true),
                       mode: CloudSimMode = CloudSimMode.CUSTOM, serviceAlive: Boolean = false,
                       pilot: Boolean = true, identityValid: Boolean = true,
                       terminal: String? = null, resumePending: Boolean = false,
                       startPending: Boolean = false) =
        CloudAppOpenPolicy.action(mode, request, serviceAlive, pilot, identityValid,
            terminal, resumePending, startPending)

    @Test fun savedOnNeedsOneSerializedRestartAfterExplicitOpen() {
        assertEquals(CloudAppOpenPolicy.Action.RESTART, action())
        for (code in listOf("lease_expired", "power_lost", "power_unavailable",
                "owner_changed", "service_changed", "session_failed", "worker_stalled", "config_changed")) {
            assertEquals(code, CloudAppOpenPolicy.Action.RESTART, action(terminal = code))
        }
        assertEquals(CloudAppOpenPolicy.Action.RESTART,
            action(request = CloudLinkRequest(enabled = true, pendingDisable = true)))
    }

    @Test fun savedOffAndLiveServiceNeverCreateAnotherOwner() {
        assertEquals(CloudAppOpenPolicy.Action.NONE, action(request = CloudLinkRequest()))
        assertEquals(CloudAppOpenPolicy.Action.CLEANUP,
            action(request = CloudLinkRequest(pendingDisable = true)))
        assertEquals(CloudAppOpenPolicy.Action.NONE, action(serviceAlive = true))
        assertEquals(CloudAppOpenPolicy.Action.NONE, action(startPending = true))
        assertEquals(CloudAppOpenPolicy.Action.NONE, action(mode = CloudSimMode.FACTORY))
    }

    @Test fun crashDuringCleanupKeepsExplicitOpenResumeIntentUntilConfirmedStop() {
        val pendingStop = CloudLinkRequest(enabled = true).request(false)
        assertFalse(pendingStop.enabled)
        assertTrue(pendingStop.pendingDisable)
        assertEquals(CloudAppOpenPolicy.Action.RESTART,
            action(request = pendingStop, resumePending = true))
        assertEquals(CloudAppOpenPolicy.Action.RESTART,
            action(request = pendingStop, serviceAlive = true, resumePending = true))
        assertFalse(CloudAppOpenPolicy.readyToStart(pendingStop, serviceAlive = false,
            ownerPresent = false, resumePending = true))
        assertFalse(CloudAppOpenPolicy.readyToStart(
            pendingStop.copy(pendingDisable = false), serviceAlive = true,
            ownerPresent = false, resumePending = true))
        assertFalse(CloudAppOpenPolicy.readyToStart(
            pendingStop.copy(pendingDisable = false), serviceAlive = false,
            ownerPresent = true, resumePending = true))
        assertTrue(CloudAppOpenPolicy.readyToStart(
            pendingStop.copy(pendingDisable = false), serviceAlive = false,
            ownerPresent = false, resumePending = true))
        assertEquals(CloudAppOpenPolicy.Action.RESTART,
            action(request = pendingStop.copy(pendingDisable = false), resumePending = true))
        assertEquals(CloudAppOpenPolicy.Action.RESTART,
            action(request = CloudLinkRequest(enabled = true, pendingDisable = true),
                serviceAlive = true))
        // An explicit mode selection cancels the intent and leaves saved OFF.
        assertEquals(CloudAppOpenPolicy.Action.NONE,
            action(request = CloudLinkRequest(), resumePending = false))
    }

    @Test fun permanentFailureOrMissingPrerequisiteDoesNotLoopOnForeground() {
        for (code in listOf("unsupported_firmware", "unsupported_identity",
                "registration_rejected", "login_rejected", "operation_rejected", "native_unavailable")) {
            assertEquals(code, CloudAppOpenPolicy.Action.NONE, action(terminal = code))
        }
        assertEquals(CloudAppOpenPolicy.Action.NONE, action(pilot = false))
        assertEquals(CloudAppOpenPolicy.Action.NONE, action(identityValid = false))
    }

    @Test fun lateOffAfterRestartStillRequiresConfirmedCleanup() {
        val stopped = CloudLinkRequest(enabled = true).request(false)
        assertFalse(stopped.enabled)
        assertTrue(stopped.pendingDisable)
        assertEquals(CloudAppOpenPolicy.Action.CLEANUP,
            action(request = stopped, resumePending = false))
        assertFalse(CloudAppOpenPolicy.readyToStart(stopped, serviceAlive = false,
            ownerPresent = false, resumePending = false))
    }

    @Test fun configurationRecreationDoesNotCountAsAnExplicitLaunch() {
        assertTrue(CloudAppOpenPolicy.handleCreate(false, false))
        assertTrue(CloudAppOpenPolicy.handleCreate(true, false)) // Cold process with restored state.
        assertTrue(CloudAppOpenPolicy.handleCreate(false, true)) // New Activity after old config teardown.
        assertFalse(CloudAppOpenPolicy.handleCreate(true, true))
    }
}
