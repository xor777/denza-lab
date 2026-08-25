package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The axis is time, and everything interesting here is about defending that.
 *
 * The poll behind this runs at 300 ms with the dashboard on screen, 2.5 s without it, and slower
 * still when the shell is struggling. A trace of "the last hundred and twenty answers" would
 * silently stretch and shrink with that; a trace of the last hundred and twenty seconds cannot.
 */
class EngineTraceTest {

    private fun trace() = EngineTrace(slotMillis = 1_000L, capacity = 5)

    @Test
    fun aTraceIsAlwaysItsWholeSpanSoItCannotStretchToFill() {
        val trace = trace()
        trace.sample(10_000L, rpm = 800.0, generationKw = 0.0)
        trace.sample(11_000L, rpm = 1200.0, generationKw = 4.0)

        val snapshot = trace.snapshot()
        // Two readings, five slots: they sit at the new end and the rest is empty. Returned short,
        // a chart would draw thirty seconds of history across a two-minute axis.
        assertEquals(5, snapshot.slots)
        assertEquals(listOf(null, null, null, 800.0, 1200.0), snapshot.revolutions)
        assertEquals(listOf(null, null, null, 0.0, 4.0), snapshot.generationKw)
    }

    @Test
    fun severalAnswersInOneSecondLeaveOneSlotAndTheNewestWins() {
        val trace = trace()
        // The dashboard's own cadence: three sweeps inside one second.
        trace.sample(10_000L, rpm = 800.0, generationKw = 0.0)
        trace.sample(10_300L, rpm = 900.0, generationKw = 0.0)
        trace.sample(10_600L, rpm = 1000.0, generationKw = 1.0)

        val snapshot = trace.snapshot()
        assertEquals(listOf(null, null, null, null, 1000.0), snapshot.revolutions)
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
        assertEquals(5, snapshot.slots)
        assertEquals(800.0, snapshot.revolutions.last()!!, 1e-9)
        assertTrue(snapshot.revolutions.dropLast(1).all { it == null })
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
        trace.sample(11_000L, rpm = 0.0, generationKw = 0.0)

        val snapshot = trace.snapshot()
        assertNull(snapshot.revolutions[3])
        assertEquals(0.0, snapshot.revolutions[4]!!, 1e-9)
        // A resting engine reports zeros, and zeros are something to draw.
        assertFalse(snapshot.isEmpty)
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

        val snapshot = trace.snapshot()
        assertEquals(700.0, snapshot.revolutions.last()!!, 1e-9)
        assertTrue(snapshot.revolutions.dropLast(1).all { it == null })
    }

    @Test
    fun theDefaultTraceIsTwoMinutesOfSeconds() {
        val trace = EngineTrace()
        assertEquals(120, EngineTrace.SLOTS)
        assertEquals(120, trace.spanSeconds)
    }
}
