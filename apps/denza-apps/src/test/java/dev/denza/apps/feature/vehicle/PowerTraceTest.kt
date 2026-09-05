package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two minutes of pack power the strip's second page draws, and the ladder it is drawn on.
 *
 * Both halves of this are decisions rather than drawings: which sample lands in which step, and
 * which rung a window is scaled to. The shape on the glass is what those two produce.
 */
class PowerTraceTest {

    @Test
    fun aStepIsTheMeanOfWhatArrivedInIt() {
        val trace = PowerTrace()
        // Five seconds, one sample a second, all in one step.
        listOf(10.0, 20.0, 30.0, 40.0, 50.0).forEachIndexed { index, kw ->
            trace.sample(index * 1_000L, kw)
        }
        val steps = trace.snapshot().steps
        assertEquals("one step so far", 1, steps.size)
        assertEquals("its mean", 30f, steps[0], 1e-3f)
    }

    @Test
    fun severalReadingsInOneSecondCollapseToTheNewest() {
        val trace = PowerTrace()
        trace.sample(0L, 10.0)
        trace.sample(250L, 20.0)
        trace.sample(500L, 33.0)
        assertEquals("the last answer in the second", 33f, trace.snapshot().steps[0], 1e-3f)
    }

    /**
     * A second the poll never reached is a hole, and a hole is not interpolated across.
     *
     * The renderer breaks the shape at a `NaN` rather than drawing a line over it, which is the
     * same rule the cluster's engine box follows: a gap in a history is information.
     */
    @Test
    fun aBinNothingAnsweredInIsNotAZero() {
        val trace = PowerTrace()
        trace.sample(0L, 40.0)
        // Nothing for the next ten seconds, then one more reading.
        trace.sample(11_000L, 20.0)
        val steps = trace.snapshot().steps
        assertEquals("three steps", 3, steps.size)
        assertEquals("the first holds its reading", 40f, steps[0], 1e-3f)
        assertTrue("the middle one is a hole", steps[1].isNaN())
        assertEquals("the newest is at the right", 20f, steps[2], 1e-3f)
    }

    @Test
    fun theWindowIsTwoMinutesAndTheOldestFallsOffTheLeft() {
        val trace = PowerTrace()
        for (second in 0..200) trace.sample(second * 1_000L, second.toDouble())
        val snapshot = trace.snapshot()
        assertEquals("steps", PowerTrace.SLOTS / PowerTrace.BIN_SECONDS, snapshot.steps.size)
        assertEquals("its own span", 120, snapshot.seconds)
        // The newest step is the one still filling, and it holds the seconds whose own number on
        // the clock falls inside it - second 200 opens a bin rather than closing the one before.
        assertEquals("the newest step", 200f, snapshot.steps.last(), 1e-3f)
        assertEquals("and the one behind it is full", 197f, snapshot.steps[snapshot.steps.size - 2], 1e-3f)
    }

    @Test
    fun aClockThatWentBackwardsIsNotAHistoryThisExtends() {
        val trace = PowerTrace()
        for (second in 0..30) trace.sample(second * 1_000L, 25.0)
        trace.sample(0L, 5.0)
        val steps = trace.snapshot().steps
        assertEquals("what is left", 1, steps.size)
        assertEquals("and it is the new reading", 5f, steps[0], 1e-3f)
    }

    @Test
    fun eachHalfTakesTheSmallestRungThatHoldsIt() {
        assertEquals("eight kilowatts", 10, PowerSpan.rung(8f))
        assertEquals("exactly a rung", 20, PowerSpan.rung(20f))
        assertEquals("one over it", 40, PowerSpan.rung(21f))
        assertEquals("nothing at all", 5, PowerSpan.rung(0f))
        assertEquals("a launch", 320, PowerSpan.rung(196f))
        assertEquals("more than the ladder has", 640, PowerSpan.rung(900f))
    }

    @Test
    fun theSpanIgnoresTheHolesAndTheOtherSide() {
        val steps = floatArrayOf(1f, Float.NaN, -8f, -9.5f, 2f)
        assertEquals("what left the pack", 5, PowerSpan.ceiling(steps))
        assertEquals("and what came back", 10, PowerSpan.floor(steps))
    }

    /**
     * And a reading past the last rung is held against the edge of the box.
     *
     * Unclamped it was drawn over the figure above the box, which is what the owner's question
     * about 200 kW would have got as an answer.
     */
    @Test
    fun aStepPastTheLadderIsHeldAgainstTheEdge() {
        val snapshot = PowerTraceSnapshot(floatArrayOf(0f), PowerTrace.BIN_SECONDS)
        assertEquals("out of the pack", 320f, snapshot.clamp(900f, 320, 160), 1e-3f)
        assertEquals("and back into it", -160f, snapshot.clamp(-400f, 320, 160), 1e-3f)
        assertEquals("an ordinary reading is untouched", 42f, snapshot.clamp(42f, 320, 160), 1e-3f)
    }

    /**
     * A window with nothing in it still has a span, and the box still has an axis.
     *
     * The renderer draws the zero line before it looks at the data, so a page swiped to a moment
     * before the first sweep is an empty box rather than a missing one.
     */
    @Test
    fun anEmptyWindowStillHasAnAxis() {
        val snapshot = PowerTrace().snapshot()
        assertTrue("nothing to draw", snapshot.isEmpty)
        assertEquals("but a ceiling", 5, PowerSpan.ceiling(snapshot.steps))
        assertEquals("and a floor", 5, PowerSpan.floor(snapshot.steps))
    }
}
