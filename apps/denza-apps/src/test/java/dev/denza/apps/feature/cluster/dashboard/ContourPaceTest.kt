package dev.denza.apps.feature.cluster.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's clock: no sweep is lost to a frame that was not drawn, and the slow lane sleeps.
 *
 * Both of these were defects the panel could not show a test: the decision lived inside a
 * `Choreographer` callback, where nothing on a JVM can reach it.
 */
class ContourPaceTest {

    /** A 60 Hz panel's own period, rounded up: two of them clear the fast budget, one does not. */
    private val vsync = 16_666_667L

    private fun ContourPace.at(index: Long, moving: Boolean = false): Boolean =
        frame(index * vsync, moving)

    @Test
    fun theFirstFrameIsDrawnAndAdvancesNothing() {
        val pace = ContourPace()
        assertTrue(pace.at(1))
        assertEquals("nothing has passed yet", 0f, pace.dt, 1e-6f)
        assertFalse(pace.arrived)
    }

    @Test
    fun aSweepThatLandsOnAFrameTheBudgetRefusesIsStillShown() {
        val pace = ContourPace()
        pace.at(0, moving = true)

        // 16.7 ms later: inside the 33.3 ms budget, so this vsync is not drawn - and the sweep that
        // arrived just before it must not be consumed by the frame that refused to draw it.
        pace.sweepArrived()
        assertFalse("the budget refuses this one", pace.at(1, moving = true))

        assertTrue("and the next one draws", pace.at(2, moving = true))
        assertTrue("carrying the sweep the refused frame was handed", pace.arrived)
    }

    @Test
    fun anArrivalIsConsumedOnceAndOnlyOnce() {
        val pace = ContourPace()
        pace.at(0)
        pace.sweepArrived()
        assertTrue(pace.at(4))
        assertTrue(pace.arrived)
        assertTrue(pace.at(20))
        assertFalse("the same sweep is not shown twice", pace.arrived)
    }

    @Test
    fun anArrivalIsWorthAFastFrameEvenWhileNothingMoves() {
        val pace = ContourPace()
        pace.at(0)
        pace.sweepArrived()
        // Two vsyncs is 33.4 ms: past the fast budget, nowhere near the slow one.
        assertTrue(pace.at(2))
        assertTrue(pace.arrived)
    }

    @Test
    fun aRefusedFrameSleepsTheRestOfItsBudgetRatherThanWakingOnEveryVsync() {
        val pace = ContourPace()
        pace.at(0)
        // Nothing moving, nothing arriving: the budget is the slow one.
        assertFalse(pace.at(1))
        assertEquals(
            "the rest of a fifth of a second, not the next vsync",
            ContourPace.SLOW_FRAME_NS - vsync,
            pace.sleepNs,
        )
    }

    @Test
    fun aDrawnFrameAsksAgainWhenItsOwnBudgetIsUp() {
        val pace = ContourPace()
        pace.at(0)
        assertTrue(pace.at(20))
        assertEquals(ContourPace.SLOW_FRAME_NS, pace.sleepNs)

        pace.sweepArrived()
        assertTrue(pace.at(40))
        assertEquals("and a fast frame asks again at thirty a second", ContourPace.FAST_FRAME_NS, pace.sleepNs)
    }

    @Test
    fun aStallHandsAFollowerAQuarterOfASecondAndNoMore() {
        val pace = ContourPace()
        pace.at(0)
        assertTrue(pace.frame(2_000_000_000L, false))
        assertEquals(ContourPace.MAX_STEP_S, pace.dt, 1e-6f)
    }

    @Test
    fun restartingForgetsTheFrameBeforeIt() {
        val pace = ContourPace()
        pace.at(0)
        pace.sweepArrived()
        pace.restart()
        assertTrue(pace.at(1))
        assertFalse("a sweep drawn by nobody is not held across a restart", pace.arrived)
        assertEquals(0f, pace.dt, 1e-6f)
    }
}
