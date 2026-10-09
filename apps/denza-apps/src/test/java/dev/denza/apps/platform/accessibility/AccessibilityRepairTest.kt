package dev.denza.apps.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityRepairTest {
    /**
     * The access repair's overlay grant, letter for letter: the package in single quotes, which
     * `OverlayGrant.command` leaves bare (pinned 2026-10-09, when it was still in
     * `SimulcastCoordinator`).
     */
    @Test
    fun `the repair grants the overlay with the package quoted`() {
        assertEquals(
            "cmd appops set 'dev.denza.apps' SYSTEM_ALERT_WINDOW allow",
            AccessibilityRepair.overlayGrantCommand("dev.denza.apps"),
        )
    }
}
