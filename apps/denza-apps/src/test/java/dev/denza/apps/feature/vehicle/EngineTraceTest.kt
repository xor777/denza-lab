package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The axis is time, and the length is the engine's own history. Both are defended here.
 *
 * The poll behind this runs at 300 ms with the dashboard on screen and slower when the shell is
 * struggling, so a trace of "the last hundred and twenty answers" would silently stretch and shrink
 * with that; a trace of the last hundred and twenty seconds cannot.
 *
 * The length is the other half, and it is the Contour's engine box in one property (CRITIQUE M7):
 * the box's right edge is fixed, its width is [EngineTraceSnapshot.slots], and it is never drawn
 * empty. So the trace starts at the oldest slot the engine was alive in, grows leftward while the
 * engine runs, holds its width through the two minutes after it stops, and then leaves - 120 s of
 * hysteresis with no timer anywhere.
 */
class EngineTraceTest {

    private fun trace() = EngineTrace(slotMillis = 1_000L, capacity = 5)

    @Test
    fun theTraceIsAsLongAsTheEngineHasBeenAliveRatherThanTheWholeSpan() {
        val trace = trace()
        trace.sample(10_000L, rpm = 800.0, generationKw = 0.0)
        trace.sample(11_000L, rpm = 1200.0, generationKw = 4.0)

        val snapshot = trace.snapshot()
        // Two live seconds is a box two seconds wide, anchored at its right edge. Padded to five,
        // the box would be born full width and would need a timer to decide when to go.
        assertEquals(2, snapshot.slots)
        assertEquals(listOf(800.0, 1200.0), snapshot.revolutions)
        assertEquals(listOf(0.0, 4.0), snapshot.generationKw)
    }

    @Test
    fun severalAnswersInOneSecondLeaveOneSlotAndTheNewestWins() {
        val trace = trace()
        // The dashboard's own cadence: three sweeps inside one second.
        trace.sample(10_000L, rpm = 800.0, generationKw = 0.0)
        trace.sample(10_300L, rpm = 900.0, generationKw = 0.0)
        trace.sample(10_600L, rpm = 1000.0, generationKw = 1.0)

        val snapshot = trace.snapshot()
        assertEquals(listOf(1000.0), snapshot.revolutions)
        assertEquals(1.0, snapshot.generationKw.last()!!, 1e-9)
    }

    @Test
    fun aStretchNobodyWatchedIsEmptyRatherThanDrawnThrough() {
        val trace = trace()
        trace.sample(10_000L, rpm = 3000.0, generationKw = 12.0)
        // The dashboard was away for three seconds. Those slots must not be filled in: a line drawn
        // straight across them would claim the engine held 3000 rpm through them.
        trace.sample(14_000L, rpm = 800.0, generationKw = 0.0)

        assertEquals(listOf(3000.0, null, null, null, 800.0), trace.snapshot().revolutions)
    }

    @Test
    fun aGapLongerThanTheTraceLeavesNothingOfTheOldReadings() {
        val trace = trace()
        trace.sample(10_000L, rpm = 3000.0, generationKw = 12.0)
        trace.sample(600_000L, rpm = 800.0, generationKw = 0.0)

        val snapshot = trace.snapshot()
        assertEquals(listOf(800.0), snapshot.revolutions)
    }

    @Test
    fun theOldestReadingsFallOffTheBack() {
        val trace = trace()
        repeat(8) { second ->
            trace.sample(10_000L + second * 1_000L, rpm = 100.0 * second, generationKw = null)
        }
        assertEquals(listOf(300.0, 400.0, 500.0, 600.0, 700.0), trace.snapshot().revolutions)
    }

    @Test
    fun anAbsentReadingIsRecordedAsAbsentRatherThanAsZero() {
        val trace = trace()
        // What a sweep with the combustion set switched off looks like, and what the rpm id itself
        // returns when the engine ECU is asleep: nothing at all, which is not a reading of zero.
        trace.sample(10_000L, rpm = null, generationKw = null)
        trace.sample(11_000L, rpm = 1400.0, generationKw = 0.0)
        trace.sample(12_000L, rpm = null, generationKw = null)

        val snapshot = trace.snapshot()
        assertEquals(2, snapshot.slots)
        assertEquals(1400.0, snapshot.revolutions[0]!!, 1e-9)
        assertNull(snapshot.revolutions[1])
    }

    @Test
    fun anEngineThatHasOnlyEverRestedHasNoBoxAtAll() {
        val trace = trace()
        // A resting engine answers, and it answers zero. The fourth board drew that as a box with
        // two flat lines in it, and the owner's question about the shelf is the same question:
        // an instrument for something that did not happen is furniture.
        repeat(4) { second -> trace.sample(10_000L + second * 1_000L, rpm = 0.0, generationKw = 0.0) }

        assertTrue(trace.snapshot().isEmpty)
        assertEquals(0, trace.snapshot().slots)
    }

    @Test
    fun theBoxHoldsItsWidthAfterTheEngineStopsAndThenLeavesWithoutATimer() {
        val trace = trace()
        // Two seconds of running, then zeros arriving second by second.
        trace.sample(10_000L, rpm = 1400.0, generationKw = 8.0)
        trace.sample(11_000L, rpm = 1500.0, generationKw = 9.0)
        assertEquals(2, trace.snapshot().slots)

        trace.sample(12_000L, rpm = 0.0, generationKw = 0.0)
        assertEquals("the box grows while the dead seconds arrive", 3, trace.snapshot().slots)
        trace.sample(13_000L, rpm = 0.0, generationKw = 0.0)
        trace.sample(14_000L, rpm = 0.0, generationKw = 0.0)
        assertEquals("and stops at the retained window", 5, trace.snapshot().slots)

        // The last live slot has now walked off the left edge, and the box goes with it. Nothing
        // counted the seconds: the trace's own length was the timer.
        trace.sample(15_000L, rpm = 0.0, generationKw = 0.0)
        trace.sample(16_000L, rpm = 0.0, generationKw = 0.0)
        assertTrue(trace.snapshot().isEmpty)
    }

    @Test
    fun generationAloneIsEnoughToKeepTheBox() {
        val trace = trace()
        // Whichever of the two ids answers first, the engine was alive in that second.
        trace.sample(10_000L, rpm = 0.0, generationKw = 6.0)
        assertFalse(trace.snapshot().isEmpty)
        assertEquals(1, trace.snapshot().slots)
    }

    @Test
    fun anEmptyTraceSaysSoRatherThanReturningARowOfZeros() {
        assertTrue(EngineTrace().snapshot().isEmpty)
        assertEquals(0, EngineTrace().snapshot().slots)
    }

    @Test
    fun aClockThatWentBackwardsStartsANewTraceInsteadOfDrawingThePastTwice() {
        val trace = trace()
        trace.sample(10_000L, rpm = 3000.0, generationKw = 12.0)
        trace.sample(11_000L, rpm = 3100.0, generationKw = 12.0)
        trace.sample(4_000L, rpm = 700.0, generationKw = 0.0)

        assertEquals(listOf(700.0), trace.snapshot().revolutions)
    }

    @Test
    fun theDefaultTraceIsTwoMinutesOfSeconds() {
        val trace = EngineTrace()
        assertEquals(120, EngineTrace.SLOTS)
        assertEquals(120, trace.spanSeconds)
    }
}
