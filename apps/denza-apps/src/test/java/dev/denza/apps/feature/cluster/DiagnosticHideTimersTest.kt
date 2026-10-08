package dev.denza.apps.feature.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The diagnostic panels' timers on the service's one handler, which also carries work that is not
 * theirs.
 *
 * The scenario behind these is a screen picked in the service panel - opaque LEFT/RIGHT panels on
 * the base layer for 2.2 seconds - and a turn signal inside that window.
 */
class DiagnosticHideTimersTest {

    @Test
    fun aCameraStartingInsideABasePreviewLeavesTheBaseLayersHideDue() {
        val handler = FakeHandler()
        val timers = handler.timers()
        var baseHidden = 0

        timers.schedule(cameraLayer = false, delayMs = 2_200L) { baseHidden++ }
        handler.advance(500L)
        // SHOW_CAMERA for the turn signal.
        timers.cancel(cameraLayer = true)
        handler.advance(1_700L)

        assertEquals("the panels over the instruments came down", 1, baseHidden)
    }

    @Test
    fun aCameraPreviewInsideABasePreviewLeavesTheBaseLayersHideDue() {
        val handler = FakeHandler()
        val timers = handler.timers()
        var baseHidden = 0
        var cameraHidden = 0

        timers.schedule(cameraLayer = false, delayMs = 2_200L) { baseHidden++ }
        handler.advance(1_000L)
        timers.schedule(cameraLayer = true, delayMs = 2_200L) { cameraHidden++ }
        handler.advance(3_000L)

        assertEquals(1, baseHidden)
        assertEquals(1, cameraHidden)
    }

    @Test
    fun theHandlersOtherWorkIsNotTheTimersToDrop() {
        val handler = FakeHandler()
        val timers = handler.timers()
        var notified = 0
        // The first-frame notification is queued on the same handler.
        handler.postDelayed(Runnable { notified++ }, 0L)

        timers.schedule(cameraLayer = true, delayMs = 1_000L) {}
        timers.cancel(cameraLayer = true)
        timers.cancel(cameraLayer = false)
        handler.advance(10L)

        assertEquals(1, notified)
    }

    @Test
    fun aNewPreviewOnALayerReplacesThatLayersTimer() {
        val handler = FakeHandler()
        val timers = handler.timers()
        val hidden = mutableListOf<String>()

        timers.schedule(cameraLayer = false, delayMs = 2_200L) { hidden += "first" }
        handler.advance(2_000L)
        timers.schedule(cameraLayer = false, delayMs = 2_200L) { hidden += "second" }
        handler.advance(300L)
        assertEquals("the first preview's hide does not cut the second one short", emptyList<String>(), hidden)

        handler.advance(2_000L)
        assertEquals(listOf("second"), hidden)
    }

    @Test
    fun aCameraTakesItsOwnLayersPendingHide() {
        val handler = FakeHandler()
        val timers = handler.timers()
        var cameraHidden = 0

        timers.schedule(cameraLayer = true, delayMs = 2_200L) { cameraHidden++ }
        timers.cancel(cameraLayer = true)
        handler.advance(5_000L)

        assertEquals(0, cameraHidden)
        assertEquals(0, handler.queued)
    }

    /** A main-thread handler with a clock the test turns. */
    private class FakeHandler {
        private var now = 0L
        private val queue = mutableListOf<Pair<Long, Runnable>>()

        val queued: Int get() = queue.size

        fun postDelayed(task: Runnable, delayMs: Long) {
            queue += (now + delayMs) to task
        }

        fun timers() = DiagnosticHideTimers(
            postDelayed = ::postDelayed,
            remove = { task -> queue.removeAll { it.second === task } },
        )

        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = queue.filter { it.first <= until }.minByOrNull { it.first } ?: break
                queue.remove(next)
                now = next.first
                next.second.run()
            }
            now = until
        }
    }
}
