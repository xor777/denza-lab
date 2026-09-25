package dev.denza.apps.feature.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCustomOwnershipTest {
    private fun status(stage: String, code: String, live: Boolean = false) = CloudCustomStatus(
        pid = 1, sessionLive = live, stage = stage, code = code,
        updatedElapsedMs = 100, connectedElapsedMs = 0, lastRxElapsedMs = 0,
        lastTxElapsedMs = 0, lastReportElapsedMs = 0, nextRetryElapsedMs = 0,
        attempts = 0, reportsSent = 0, statusReplies = 0, commandsForwarded = 0,
        commandsCompleted = 0, reconnects = 0, callbackAgeMs = -1, events = emptyList(),
    )

    @Test fun restartAndAmbiguousStopKeepOwnerUntilExactProof() {
        assertFalse(CloudCustomOwnershipPolicy.stopped(null))
        assertFalse(CloudCustomOwnershipPolicy.absent(null))
        assertFalse(CloudCustomOwnershipPolicy.absent(status("failed", "owner_present")))
        assertFalse(CloudCustomOwnershipPolicy.absent(status("stopped", "maybe_absent")))
        assertTrue(CloudCustomOwnershipPolicy.absent(status("stopped", "owner_absent_confirmed")))
        assertFalse(CloudCustomOwnershipPolicy.stopped(status("stopped", "stop_wait", live = true)))
        assertTrue(CloudCustomOwnershipPolicy.stopped(status("stopped", "stopped")))
    }

    @Test fun rapidOffOnKeepsTeardownAndLocksIdentityEditing() {
        val queued = CloudLinkRequest(enabled = true).request(false).request(true)
        assertTrue(queued.enabled)
        assertTrue(queued.pendingDisable)
        assertTrue(queued.needsService)
        assertFalse(CloudLinkSettings.canConfigure(enabled = false, pendingDisable = true, busy = false))
        assertFalse(CloudLinkSettings.canConfigure(enabled = false, pendingDisable = false, busy = false, customOwner = true))
    }

}
