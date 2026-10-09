package dev.denza.apps.feature.simulcast

import dev.denza.apps.platform.accessibility.AccessibilityHost
import dev.denza.apps.platform.accessibility.RiderHost
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimulcastOverlayRiderTest {
    private var switchReads = 0

    private fun looks(idle: Boolean, on: Boolean): Boolean =
        SimulcastOverlayRider.looksAtWindows(idle) { switchReads++; on }

    /** The overlay as the rider sees it: idle or not, and how often it was told to look. */
    private class FakeOverlay(var idle: Boolean) : DialogOverlay {
        var refreshes = 0

        override fun isIdle(): Boolean = idle

        override fun scheduleRefresh() {
            refreshes++
        }

        override fun detach() = Unit
    }

    private val overlay = FakeOverlay(idle = true)
    private var switchedOn = false
    private val rider = SimulcastOverlayRider().also { it.attach(overlay) { switchedOn } }

    @After
    fun unbind() {
        AccessibilityHost.unbind(host)
    }

    /** Switched off with nothing of ours on screen, an event costs no walk of every window. */
    @Test
    fun `an idle overlay with the projection off does not look`() {
        assertFalse(looks(idle = true, on = false))

        rider.onWindowEvent()
        assertEquals(0, overlay.refreshes)
    }

    @Test
    fun `switched on, every event looks, as it always did`() {
        assertTrue(looks(idle = true, on = true))
        assertTrue(looks(idle = false, on = true))

        switchedOn = true
        rider.onWindowEvent()
        overlay.idle = false
        rider.onWindowEvent()
        assertEquals(2, overlay.refreshes)
    }

    /**
     * Switched off over an open dialog, mid-drag or with a window left to remove, the next event
     * still looks: that look takes the row down and gives the exit back. The rider asks the overlay
     * whether it is idle, not only the switch.
     */
    @Test
    fun `an overlay with something to finish looks even with the projection off, without asking the switch`() {
        assertTrue(looks(idle = false, on = false))
        assertEquals(0, switchReads)

        overlay.idle = false
        rider.onWindowEvent()
        assertEquals(1, overlay.refreshes)
    }

    /** The switch moving does not wait for an event: the bound service's overlay looks at once. */
    @Test
    fun `the switch moving makes the bound overlay look, on the service's main thread`() {
        AccessibilityHost.bind(host)

        SimulcastOverlayRider.requestRefresh()
        assertEquals("not in place", 0, overlay.refreshes)
        host.drain()

        assertEquals(1, overlay.refreshes)
    }

    @Test
    fun `a dialog seen open holds the exit control until it is seen closed`() {
        val tracker = SimulcastDialogVisibilityTracker()
        assertFalse(tracker.isOpen())

        tracker.observe(SimulcastDialogVisibilityTracker.Observation.OPEN)
        assertTrue(tracker.isOpen())
        tracker.observe(SimulcastDialogVisibilityTracker.Observation.UNKNOWN)
        assertTrue("the close grace keeps it", tracker.isOpen())
        tracker.observe(SimulcastDialogVisibilityTracker.Observation.CLOSED_CONFIRMED)
        assertFalse(tracker.isOpen())
    }

    private val host = object : RiderHost {
        private val main = ArrayDeque<Runnable>()

        override fun <R : Any> rider(type: Class<R>): R? = if (type.isInstance(rider)) type.cast(rider) else null

        override fun post(call: Runnable) {
            main.add(call)
        }

        fun drain() {
            while (main.isNotEmpty()) main.removeFirst().run()
        }
    }
}
