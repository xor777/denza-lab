package dev.denza.apps.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * After an APK update the setting can list the service while the system never binds it. Every
     * feature reads that as "on its way" for [AccessibilityHealth.BIND_DEADLINE_MS], then as no
     * access - the state whose press repairs - and a bind that lands late clears it by itself.
     */
    @Test
    fun `a listed service that never binds stops being available at the deadline`() {
        val deadline = AccessibilityHealth.BIND_DEADLINE_MS
        val watch = AccessibilityBindWatch {}

        assertTrue(watch.read(enabled = true, connected = false, nowMs = 5_000L).available)
        assertTrue(watch.read(enabled = true, connected = false, nowMs = 5_000L + deadline - 1).available)
        val stalled = watch.read(enabled = true, connected = false, nowMs = 5_000L + deadline)
        assertFalse(stalled.available)
        assertFalse("a repair is still wanted", stalled.ready())

        assertTrue("a late bind clears it", watch.read(enabled = true, connected = true, nowMs = 60_000L).available)
        assertTrue(watch.read(enabled = true, connected = false, nowMs = 61_000L).available)
        assertFalse("switched off is never available", watch.read(enabled = false, connected = false, nowMs = 0L).available)
    }

    /** One re-read per stall, at the deadline; a finished repair starts the clock and the re-read again. */
    @Test
    fun `the bind clock runs while listed and unbound, and a repair restarts it`() {
        val scheduled = mutableListOf<Long>()
        val watch = AccessibilityBindWatch { scheduled += it }
        fun unboundFor(nowMs: Long, connected: Boolean = false) =
            watch.read(enabled = true, connected = connected, nowMs = nowMs).unboundForMs

        assertEquals(0L, unboundFor(1_000L))
        assertEquals(listOf(AccessibilityHealth.BIND_DEADLINE_MS), scheduled)
        assertEquals(12_000L, unboundFor(13_000L))
        assertEquals("one re-read per stall", 1, scheduled.size)

        watch.restart()
        assertEquals(0L, unboundFor(14_000L))
        assertEquals(2, scheduled.size)

        assertEquals(0L, unboundFor(30_000L, connected = true))
        assertEquals(0L, unboundFor(31_000L))
        assertEquals(500L, unboundFor(31_500L))
        assertEquals(3, scheduled.size)
    }
}
