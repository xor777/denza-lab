package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The axis is time, the length is the engine's own history, and the steps are anchored to the clock.
 *
 * The poll behind this runs about four times a second with the dashboard on screen and slower when
 * the shell is struggling, so a trace of "the last hundred and twenty answers" would silently
 * stretch and shrink with that; a trace of the last hundred and twenty seconds cannot.
 *
 * The length is the second half, and it is the Contour's engine box in one property (CRITIQUE M7):
 * the box's right edge is fixed, its width is [EngineTraceSnapshot.spanSeconds], and it is never
 * drawn empty. So the trace starts at the oldest slot the engine was alive in, grows leftward while
 * the engine runs, holds its width through the two minutes after it stops, and then leaves - 120 s
 * of hysteresis with no timer anywhere.
 *
 * The steps are the third: a bin holds the seconds whose own absolute number falls in it, so the
 * grid does not re-phase when the front of the window is evicted.
 *
 * Since the eighth pass it keeps one series. The revolutions are not stored, but they are still
 * *read*: an engine turning is an engine the box should be up for, whether or not it happens to be
 * putting anything back that second.
 */
class EngineTraceTest {

    /** One second per step, so a test can state what each second holds. */
    private fun trace() = EngineTrace(slotMillis = 1_000L, capacity = 5, binSeconds = 1)

    private fun EngineTraceSnapshot.values(): List<Double?> =
        bins.map { if (it.isNaN()) null else it.toDouble() }

    @Test
    fun theTraceIsAsLongAsTheEngineHasBeenAliveRatherThanTheWholeSpan() {
        val trace = trace()
        trace.sample(10_000L, rpm = 800.0, generationKw = 0.0)
        trace.sample(11_000L, rpm = 1200.0, generationKw = 4.0)

        val snapshot = trace.snapshot()
        // Two live seconds is a box two seconds wide, anchored at its right edge. Padded to five,
        // the box would be born full width and would need a timer to decide when to go.
        assertEquals(2, snapshot.spanSeconds)
        assertEquals(listOf(0.0, 4.0), snapshot.values())
    }

    @Test
    fun severalAnswersInOneSecondLeaveOneSlotAndTheNewestWins() {
        val trace = trace()
        // The dashboard's own cadence: three sweeps inside one second.
        trace.sample(10_000L, rpm = 800.0, generationKw = 0.0)
        trace.sample(10_300L, rpm = 900.0, generationKw = 0.0)
        trace.sample(10_600L, rpm = 1000.0, generationKw = 1.0)

        val snapshot = trace.snapshot()
        assertEquals(1, snapshot.spanSeconds)
        assertEquals(1.0, snapshot.values().last()!!, 1e-9)
    }

    @Test
    fun aStretchNobodyWatchedIsEmptyRatherThanDrawnThrough() {
        val trace = trace()
        trace.sample(10_000L, rpm = 3000.0, generationKw = 12.0)
        // The dashboard was away for three seconds. Those slots must not be filled in: an area drawn
        // straight across them would claim the engine held 12 kW through them.
        trace.sample(14_000L, rpm = 800.0, generationKw = 0.0)

        assertEquals(listOf(12.0, null, null, null, 0.0), trace.snapshot().values())
    }

    @Test
    fun aGapLongerThanTheTraceLeavesNothingOfTheOldReadings() {
        val trace = trace()
        trace.sample(10_000L, rpm = 3000.0, generationKw = 12.0)
        trace.sample(600_000L, rpm = 800.0, generationKw = 3.0)

        assertEquals(listOf(3.0), trace.snapshot().values())
    }

    @Test
    fun theOldestReadingsFallOffTheBack() {
        val trace = trace()
        repeat(8) { second ->
            trace.sample(10_000L + second * 1_000L, rpm = 1400.0, generationKw = 1.0 * second)
        }
        assertEquals(listOf(3.0, 4.0, 5.0, 6.0, 7.0), trace.snapshot().values())
    }

    @Test
    fun anAbsentReadingIsRecordedAsAbsentRatherThanAsZero() {
        val trace = trace()
        // What a sweep with the combustion set switched off looks like, and what the generation id
        // itself returns when the engine ECU is asleep: nothing at all, which is not a zero.
        trace.sample(10_000L, rpm = null, generationKw = null)
        trace.sample(11_000L, rpm = 1400.0, generationKw = 6.0)
        trace.sample(12_000L, rpm = 1400.0, generationKw = null)

        val snapshot = trace.snapshot()
        assertEquals(2, snapshot.spanSeconds)
        assertEquals(6.0, snapshot.values()[0]!!, 1e-9)
        assertNull(snapshot.values()[1])
    }

    @Test
    fun anEngineThatHasOnlyEverRestedHasNoBoxAtAll() {
        val trace = trace()
        // A resting engine answers, and it answers zero. The fourth board drew that as a box with
        // two flat lines in it, and the owner's question about the shelf is the same question:
        // an instrument for something that did not happen is furniture.
        repeat(4) { second -> trace.sample(10_000L + second * 1_000L, rpm = 0.0, generationKw = 0.0) }

        assertTrue(trace.snapshot().isEmpty)
        assertEquals(0, trace.snapshot().spanSeconds)
    }

    @Test
    fun theBoxHoldsItsWidthAfterTheEngineStopsAndThenLeavesWithoutATimer() {
        val trace = trace()
        // Two seconds of running, then zeros arriving second by second.
        trace.sample(10_000L, rpm = 1400.0, generationKw = 8.0)
        trace.sample(11_000L, rpm = 1500.0, generationKw = 9.0)
        assertEquals(2, trace.snapshot().spanSeconds)

        trace.sample(12_000L, rpm = 0.0, generationKw = 0.0)
        assertEquals("the box grows while the dead seconds arrive", 3, trace.snapshot().spanSeconds)
        trace.sample(13_000L, rpm = 0.0, generationKw = 0.0)
        trace.sample(14_000L, rpm = 0.0, generationKw = 0.0)
        assertEquals("and stops at the retained window", 5, trace.snapshot().spanSeconds)

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
        assertEquals(1, trace.snapshot().spanSeconds)
    }

    @Test
    fun anEngineTurningWithoutGeneratingStillHoldsTheBox() {
        val trace = trace()
        // The revolutions are not a series any more and they are still read for this: on a direct
        // drive the engine can turn for a minute returning nothing, and a box that left in the
        // middle of an engine run would flicker exactly the way M7 was about.
        trace.sample(10_000L, rpm = 1600.0, generationKw = 0.0)
        trace.sample(11_000L, rpm = 1600.0, generationKw = 0.0)

        assertFalse(trace.snapshot().isEmpty)
        assertEquals(listOf(0.0, 0.0), trace.snapshot().values())
    }

    @Test
    fun anEmptyTraceSaysSoRatherThanReturningARowOfZeros() {
        assertTrue(EngineTrace().snapshot().isEmpty)
        assertEquals(0, EngineTrace().snapshot().spanSeconds)
    }

    @Test
    fun aClockThatWentBackwardsStartsANewTraceInsteadOfDrawingThePastTwice() {
        val trace = trace()
        trace.sample(10_000L, rpm = 3000.0, generationKw = 12.0)
        trace.sample(11_000L, rpm = 3100.0, generationKw = 12.0)
        trace.sample(4_000L, rpm = 700.0, generationKw = 2.0)

        assertEquals(listOf(2.0), trace.snapshot().values())
    }

    @Test
    fun theDefaultTraceIsTwoMinutesOfSecondsInFiveSecondSteps() {
        assertEquals(120, EngineTrace.SLOTS)
        assertEquals(5, EngineTrace.BIN_SECONDS)
        assertEquals(5, EngineTrace().snapshot().binSeconds)
    }

    // ---- the five-second steps the box actually draws

    /** A trace at the panel's own step size, small enough for a test to fill. */
    private fun stepped(capacity: Int = 20) =
        EngineTrace(slotMillis = 1_000L, capacity = capacity, binSeconds = 5)

    private fun feed(trace: EngineTrace, fromSecond: Long, values: List<Double?>) {
        values.forEachIndexed { index, value ->
            trace.sample(
                (fromSecond + index) * 1_000L,
                rpm = 1400.0,
                generationKw = value,
            )
        }
    }

    @Test
    fun aBinIsTheMeanOfTheSecondsInsideIt() {
        val trace = stepped()
        feed(trace, 100, listOf(2.0, 4.0, 6.0, 8.0, 10.0, 1.0, 3.0, 5.0, 7.0, 9.0))
        assertEquals(listOf(6.0, 5.0), trace.snapshot().values())
    }

    @Test
    fun aBinNothingAnsweredInIsAGapRatherThanAZero() {
        val trace = stepped()
        feed(trace, 100, List(5) { 6.0 } + List(5) { null } + List(5) { 2.0 })
        val bins = trace.snapshot().values()
        assertEquals(3, bins.size)
        assertEquals(6.0, bins[0]!!, 1e-9)
        assertNull(bins[1])
        assertEquals(2.0, bins[2]!!, 1e-9)
    }

    @Test
    fun aBinWithOneAnswerInFiveIsThatAnswer() {
        // A poll the shell missed costs resolution rather than a hole.
        val trace = stepped()
        feed(trace, 100, listOf(null, null, 12.0, null, null))
        assertEquals(12.0, trace.snapshot().values()[0]!!, 1e-9)
    }

    @Test
    fun theBinsNeverOutrunTheBoxTheyAreDrawnIn() {
        val trace = stepped(capacity = 20)
        feed(trace, 100, List(40) { it.toDouble() })
        val bins = trace.snapshot().values()
        assertEquals("twenty slots of five seconds", 4, bins.size)
        // And what is kept is the newest end of the run: 35..39 is the last full step.
        assertEquals(37.0, bins.last()!!, 1e-9)
    }

    @Test
    fun aStepHoldsTheSecondsWhoseOwnNumberFallsInIt() {
        // The whole of the fix. The grouping used to run from index 0 of the *run*, so once the
        // window filled and the front started being evicted every bin's membership shifted by one
        // second every second: all twenty-four step heights were recomputed at 1 Hz and the same two
        // minutes never came back the same shape.
        val trace = stepped(capacity = 20)

        // Seconds 100..119, which is bins 20..23 exactly. Each bin is worth its own index.
        feed(trace, 100, List(20) { (it / 5).toDouble() })
        assertEquals(listOf(0.0, 1.0, 2.0, 3.0), trace.snapshot().values())

        // One more second evicts second 100 and opens bin 24. The four bins that survive keep the
        // values they had; only the newest one is new, and only it is short.
        trace.sample(120_000L, rpm = 1400.0, generationKw = 9.0)
        assertEquals(listOf(1.0, 2.0, 3.0, 9.0), trace.snapshot().values())

        trace.sample(121_000L, rpm = 1400.0, generationKw = 11.0)
        assertEquals(
            "and the newest step fills rather than the whole grid re-phasing",
            listOf(1.0, 2.0, 3.0, 10.0),
            trace.snapshot().values(),
        )
    }

    @Test
    fun theStepAtTheOldEndIsWhateverTheWindowHasLeftOfIt() {
        // A run that starts in the middle of a step: 103..109 is two seconds of bin 20 and five of
        // bin 21, and the older one is drawn from the two it has rather than from a phase of its own.
        val trace = stepped()
        feed(trace, 103, listOf(4.0, 6.0) + List(5) { 10.0 })
        assertEquals(listOf(5.0, 10.0), trace.snapshot().values())
        assertEquals("the caption's window is the seconds, not the steps", 7, trace.snapshot().spanSeconds)
    }
}
