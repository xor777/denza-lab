package dev.denza.apps.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class AccessibilityHealthTest {
    /**
     * One rule for every rider. A service the setting lists but nobody bound is not ready: the
     * speakers asked only for the setting until 2026-10-09 and so never repaired it.
     */
    @Test
    fun `ready is switched on and bound, for every rider alike`() {
        val readiness = listOf(
            AccessibilityHealth(enabled = false, connected = false),
            AccessibilityHealth(enabled = false, connected = true),
            AccessibilityHealth(enabled = true, connected = false),
            AccessibilityHealth(enabled = true, connected = true),
        ).map { it.ready() }

        assertEquals(listOf(false, false, false, true), readiness)
    }

    /** A feature's own further need - the projection's overlay grant - is the only thing added. */
    @Test
    fun `a feature's extra requirement is added to the same check`() {
        val healthy = AccessibilityHealth(enabled = true, connected = true)

        assertEquals(true, healthy.ready(extra = true))
        assertEquals(false, healthy.ready(extra = false))
        assertEquals(false, AccessibilityHealth(enabled = true, connected = false).ready(extra = true))
    }
}
