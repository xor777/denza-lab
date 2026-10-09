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

    /** A listed service the system is binding by itself is waited for, up to the grace. */
    @Test
    fun `a repair waits out the rest of the grace for a bind under way`() {
        val grace = AccessibilityHealth.BIND_GRACE_MS

        assertEquals(grace, AccessibilityHealth(enabled = true, connected = false, bindingForMs = 0).bindWaitMs())
        assertEquals(grace - 1_500, AccessibilityHealth(true, false, bindingForMs = 1_500).bindWaitMs())
        assertEquals(0L, AccessibilityHealth(true, false, bindingForMs = grace).bindWaitMs())
        assertEquals(0L, AccessibilityHealth(true, false, bindingForMs = grace + 5_000).bindWaitMs())
    }

    /**
     * Nothing else waits. A crashed service has no instance on its way - the firmware binds it again
     * for nobody - so its repair runs at once; so does one switched off, or one already bound.
     */
    @Test
    fun `nothing waits without a bind under way`() {
        assertEquals(0L, AccessibilityHealth(enabled = true, connected = false).bindWaitMs())
        assertEquals(0L, AccessibilityHealth(enabled = false, connected = false, bindingForMs = 0).bindWaitMs())
        assertEquals(0L, AccessibilityHealth(enabled = true, connected = true, bindingForMs = 0).bindWaitMs())
    }
}
