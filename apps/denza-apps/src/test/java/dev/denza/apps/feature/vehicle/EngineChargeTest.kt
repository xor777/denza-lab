package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine block's run (`docs/energy-display-contract.md` §2.5): what the pack took while the
 * engine ran, a point a second, each the mean of the ten seconds before it.
 *
 * The expectations are worked out here from what the test feeds in.
 */
class EngineChargeTest {

    private val charge = EngineCharge()
    private var at = 0L

    /** One sweep [dt] seconds after the last; [loadKw] negative into the pack. */
    private fun sweep(running: Boolean?, loadKw: Double?, dt: Double = 1.0) {
        at += (dt * 1000).toLong()
        charge.sample(at, running, loadKw, dt)
    }

    @Test
    fun nothingRunNothingDrawn() {
        assertTrue(charge.snapshot().isEmpty)
        repeat(5) { sweep(running = false, loadKw = -20.0) }
        assertTrue("a flag down is no run, whatever the pack does", charge.snapshot().isEmpty)
    }

    @Test
    fun theFigureIsThePacksChargeOverTheRunsOwnSeconds() {
        repeat(60) { sweep(running = true, loadKw = -12.0) }
        repeat(30) { sweep(running = true, loadKw = 40.0) }
        val run = charge.snapshot()
        // Only what went in counts: an hour at 12 kW is 12 kWh, a minute is a fifth of one.
        assertEquals(60 * 12.0 / 3600.0, run.kwh, 1e-9)
        assertEquals(90.0, run.seconds, 1e-9)
        assertTrue(run.running)
        assertEquals(0L, run.stoppedSeconds)
    }

    @Test
    fun aGapLongerThanTheLedgersCountsNothing() {
        sweep(running = true, loadKw = -12.0)
        sweep(running = true, loadKw = -12.0, dt = OdometerGate.MAX_GAP_SECONDS + 1.0)
        val run = charge.snapshot()
        assertEquals(12.0 / 3600.0, run.kwh, 1e-9)
        assertEquals(1.0, run.seconds, 1e-9)
    }

    @Test
    fun aYoungRunIsAsManyPointsAsItIsSecondsOldAndStartsFromZero() {
        repeat(4) { sweep(running = true, loadKw = -10.0) }
        val points = charge.snapshot().points
        assertEquals(4, points.size)
        // The seconds before the run gave nothing: the first point is a tenth of 10 kW.
        assertEquals(1f, points[0], 1e-5f)
        assertEquals(4f, points[3], 1e-5f)
    }

    @Test
    fun aPointIsTheTrailingTenSecondsAndTheWindowTwoMinutes() {
        repeat(200) { sweep(running = true, loadKw = if (it % 2 == 0) -10.0 else -20.0) }
        val points = charge.snapshot().points
        assertEquals(EngineCharge.WINDOW_SECONDS, points.size)
        // Five tens and five twenties in every ten seconds.
        points.forEach { assertEquals(15f, it, 1e-4f) }
    }

    @Test
    fun aSecondNoSweepLandedInKeepsTheNewestReading() {
        // A poll every two seconds: the reading stands until the next one, so the mean is over time
        // and the curve does not step every other second.
        repeat(30) { sweep(running = true, loadKw = if (it < 15) -10.0 else -20.0, dt = 2.0) }
        val points = charge.snapshot().points
        assertEquals("a point a second", 59, points.size)
        assertEquals(10f, points[27], 1e-4f)
        assertEquals(20f, points.last(), 1e-4f)
        for (i in 1 until points.size) assertTrue("never backwards on a rising step", points[i] >= points[i - 1] - 1e-4f)
    }

    @Test
    fun aHoleLongerThanTheGapIsLeftOutRatherThanCountedAsZero() {
        repeat(10) { sweep(running = true, loadKw = -10.0) }
        sweep(running = true, loadKw = -10.0, dt = 12.0)
        val points = charge.snapshot().points
        assertTrue(points.all { !it.isNaN() })
        // Eight held seconds and then three nobody answered: still 10, not dragged toward zero.
        assertEquals(10f, points.last(), 1e-4f)
    }

    @Test
    fun aReadingThatDidNotAnswerIsNotAZero() {
        repeat(20) { sweep(running = true, loadKw = -10.0) }
        repeat(3) { sweep(running = true, loadKw = null) }
        assertEquals(10f, charge.snapshot().points.last(), 1e-4f)
    }

    @Test
    fun aStoppedRunKeepsItsChartAndCountsItsSilence() {
        repeat(30) { sweep(running = true, loadKw = -10.0) }
        val before = charge.snapshot()
        repeat(4) { sweep(running = false, loadKw = 30.0) }
        val after = charge.snapshot()
        assertFalse(after.running)
        assertEquals(4L, after.stoppedSeconds)
        assertEquals(before.kwh, after.kwh, 1e-12)
        assertEquals("the chart as it was at the stop", before.points.size, after.points.size)
        assertEquals(before.points.last(), after.points.last(), 1e-6f)
        assertTrue(after.stoppedRecently)
    }

    @Test
    fun aFlagThatDidNotAnswerNeitherEndsNorCountsARun() {
        repeat(10) { sweep(running = true, loadKw = -10.0) }
        sweep(running = null, loadKw = -10.0)
        val run = charge.snapshot()
        assertTrue(run.running)
        assertEquals(10.0, run.seconds, 1e-9)
    }

    /**
     * A braking sweep with the flag missing: neither the figure nor the time counts it, and the
     * chart did - the newest ten-second mean went from 10 to 15 kW on one sweep the run itself had
     * refused.
     */
    @Test
    fun aFlagThatDidNotAnswerPutsNothingOnTheChart() {
        repeat(20) { sweep(running = true, loadKw = -10.0) }
        sweep(running = null, loadKw = -60.0)
        val run = charge.snapshot()
        assertEquals(10f, run.points.last(), 1e-4f)
        assertEquals(20 * 10.0 / 3600.0, run.kwh, 1e-12)
    }

    /**
     * A process suspended for a day wakes to a running flag. The chart is what it would have been,
     * and the history behind it is no longer than the window and its smoothing: the gap is skipped,
     * not walked a second at a time. On the old code this test allocated a boxed entry for every
     * second of three years before trimming them.
     */
    @Test
    fun aLongSuspendIsSkippedRatherThanWalked() {
        repeat(20) { sweep(running = true, loadKw = -10.0) }
        val years = 3L * 365 * 24 * 3600
        sweep(running = true, loadKw = -10.0, dt = years.toDouble())
        repeat(5) { sweep(running = true, loadKw = -10.0) }

        val run = charge.snapshot()
        assertEquals(EngineCharge.WINDOW_SECONDS, run.points.size)
        assertTrue(run.points.all { !it.isNaN() })
        assertEquals("the newest points are the run after the wake", 10f, run.points.last(), 1e-4f)
        assertEquals("the suspended time is not the run's", 25.0, run.seconds, 1e-9)

    }

    @Test
    fun aRestartWithinTenSecondsIsTheSameRun() {
        repeat(30) { sweep(running = true, loadKw = -10.0) }
        repeat(5) { sweep(running = false, loadKw = 30.0) }
        repeat(10) { sweep(running = true, loadKw = -10.0) }
        val run = charge.snapshot()
        assertEquals("the figure carries on", 40 * 10.0 / 3600.0, run.kwh, 1e-9)
        assertEquals(40.0, run.seconds, 1e-9)
        assertEquals("the off seconds are seconds of the chart", 45, run.points.size)
    }

    @Test
    fun aRestartLaterIsANewRun() {
        repeat(30) { sweep(running = true, loadKw = -10.0) }
        repeat(20) { sweep(running = false, loadKw = 30.0) }
        repeat(5) { sweep(running = true, loadKw = -10.0) }
        val run = charge.snapshot()
        assertEquals(5 * 10.0 / 3600.0, run.kwh, 1e-9)
        assertEquals(5, run.points.size)
    }

    @Test
    fun aClockThatWentBackwardsStartsAgain() {
        repeat(10) { sweep(running = true, loadKw = -10.0) }
        at = 0L
        sweep(running = true, loadKw = -10.0)
        assertEquals(1, charge.snapshot().points.size)
    }

    @Test
    fun theCeilingIsFiftyOrTwentyFiveByTheWindowAndATickBeyond() {
        assertEquals(25f, EngineCharge.ceilingKw(floatArrayOf(0f, 8f, 19.99f)), 0f)
        assertEquals(50f, EngineCharge.ceilingKw(floatArrayOf(0f, 8f, 20f)), 0f)
        assertEquals(50f, EngineCharge.ceilingKw(floatArrayOf(70f)), 0f)
        assertEquals(25f, EngineCharge.ceilingKw(FloatArray(0)), 0f)

        repeat(60) { sweep(running = true, loadKw = -8.0) }
        assertEquals("the standing charge, a third of the box", 25f, charge.snapshot().ceilingKw, 0f)
        repeat(60) { sweep(running = true, loadKw = -28.0) }
        assertEquals(50f, charge.snapshot().ceilingKw, 0f)
    }
}
