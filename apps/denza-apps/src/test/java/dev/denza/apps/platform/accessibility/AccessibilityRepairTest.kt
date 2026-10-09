package dev.denza.apps.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * The service as the repair reads it on a fake clock: listed, created by the system at
     * [createdAtMs] and connecting at [connectsAtMs] (never, when null).
     */
    private class Binding(
        private val enabled: Boolean = true,
        private val createdAtMs: Long? = 0,
        private val connectsAtMs: Long? = null,
    ) {
        var nowMs = 0L
        var reads = 0

        fun read(): AccessibilityHealth {
            reads++
            val connected = connectsAtMs != null && nowMs >= connectsAtMs
            return AccessibilityHealth(
                enabled = enabled,
                connected = connected,
                bindingForMs = createdAtMs?.takeUnless { connected }?.let { nowMs - it },
            )
        }

        fun sleep(ms: Long) {
            nowMs += ms
        }
    }

    private fun await(binding: Binding, stillWanted: () -> Boolean = { true }): Boolean =
        AccessibilityRepair.awaitBind(binding::read, binding::sleep, stillWanted)

    /** After an update or a boot the system binds the service itself: the repair waits, and rewrites nothing. */
    @Test
    fun `a bind under way that lands within the grace leaves nothing to repair`() {
        val binding = Binding(createdAtMs = 0, connectsAtMs = 650)

        assertTrue(await(binding))
        assertEquals(700L, binding.nowMs)
    }

    @Test
    fun `a bind that does not land within the grace is repaired after it`() {
        val binding = Binding(createdAtMs = 0, connectsAtMs = null)

        assertFalse(await(binding))
        assertEquals(AccessibilityHealth.BIND_GRACE_MS, binding.nowMs)
    }

    /** The common start, a wake after a sleep: the firmware left the service crashed, nothing is on its way. */
    @Test
    fun `a crashed service is repaired at once`() {
        val binding = Binding(createdAtMs = null)

        assertFalse(await(binding))
        assertEquals(0L, binding.nowMs)
        assertEquals(1, binding.reads)
    }

    /** A repair asked for on purpose over a working service still rewrites the setting, as it did. */
    @Test
    fun `a bound service or one switched off is not waited for`() {
        assertFalse(await(Binding(createdAtMs = null, connectsAtMs = 0)))
        val off = Binding(enabled = false, createdAtMs = 0)
        assertFalse(await(off))
        assertEquals(0L, off.nowMs)
    }

    @Test
    fun `the wait ends when nobody wants the repair any more`() {
        val binding = Binding(createdAtMs = 0, connectsAtMs = null)
        var wanted = 3

        assertFalse(await(binding) { wanted-- > 0 })
        assertEquals(300L, binding.nowMs)
    }
}
