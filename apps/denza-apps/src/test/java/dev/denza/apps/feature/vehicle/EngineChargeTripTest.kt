package dev.denza.apps.feature.vehicle

import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine block over a whole trip (`docs/energy-display-contract.md` §2.5, since 2026-11-21),
 * through the hub's own sweep step: the ledger and the block are fed the same sweeps, as on the car.
 *
 * Each test holds one of the decision's outcomes:
 *
 *  1. while a trip is open the block counts the engine for the whole trip, not one run;
 *  2. a start after a pause continues the same chart, the pause itself not drawn;
 *  3. a mark stands where the engine rested, slides with the window and leaves with it; a start
 *     inside the ten-second hold is the same run and has none;
 *  4. the figure is the trip's «ЗАРЯД ОТ ДВС», the ledger's very number;
 *  5. the time is the engine's time in this trip;
 *  6. a new trip, and only a new trip, starts figure, time and chart from zero;
 *  7. with no trip open the block is the run standing in P, as before.
 *
 * A sweep a second; the expectations are worked out from what each test feeds in.
 */
class EngineChargeTripTest {

    private val log = ConsumptionLog()
    private val ledger = TripEnergyLedger()
    private var charge = EngineCharge()
    private var at = 0L
    private var odometerKm = 100.0

    /** One sweep a second after the last: the engine's flag, pack power (negative into the pack), P, road. */
    private fun sweep(engine: Boolean?, powerKw: Double, park: Boolean = false, km: Double = 0.0): VehicleTelemetry {
        at += 1_000L
        odometerKm += km
        val parsed = buildMap {
            put(VehicleSignal.ODOMETER_KM, odometerKm)
            put(VehicleSignal.POWER_KW, powerKw)
            put(VehicleSignal.GEARBOX_PARK, if (park) 1.0 else 0.0)
            engine?.let { put(VehicleSignal.ENGINE_RUNNING, if (it) 3.0 else 0.0) }
        }
        val values = VehicleAnsweredSweep.feed(parsed, emptyMap(), at, 1.0, log, ledger, charge)
        return VehicleAnsweredSweep.snapshot(values, log, ledger, charge)
    }

    private fun run(seconds: Int, chargeKw: Double, park: Boolean = false): VehicleTelemetry {
        var t = VehicleTelemetry()
        repeat(seconds) { t = sweep(engine = true, powerKw = -chargeKw, park = park) }
        return t
    }

    private fun rest(seconds: Int, park: Boolean = false): VehicleTelemetry {
        var t = VehicleTelemetry()
        repeat(seconds) { t = sweep(engine = false, powerKw = 5.0, park = park) }
        return t
    }

    /** The odometer's first reading seeds it; the next tick is the first movement, and a trip opens. */
    private fun openTrip(): VehicleTelemetry {
        sweep(engine = false, powerKw = 0.0)
        return sweep(engine = false, powerKw = 5.0, km = 0.1).also { assertTrue("the trip is open", it.trip.open) }
    }

    /** What the screens print for [t]: the block's figure and time, and the engine's cell. */
    private fun read(t: VehicleTelemetry) = EnergyReadouts().also { it.read(t, parked = t.parked == true) }

    @Test
    fun onSixtyOffFiveMinutesOnThirtyIsOneChartWithOneMarkAndTheTripsFigure() {
        openTrip()
        run(60, chargeKw = 12.0)
        rest(300)
        val t = run(30, chargeKw = 12.0)
        val block = t.engineCharge

        assertTrue("the trip's engine", block.ofTrip)
        assertEquals("one chart: the pause cut out", 90, block.points.size)
        assertArrayEquals("one mark, before the first second after the pause", intArrayOf(60), block.marks)
        // Each stretch is smoothed as a run of its own: after the mark the curve rises from zero again.
        assertEquals(1.2f, block.points[0], 1e-4f)
        assertEquals(12f, block.points[59], 1e-4f)
        assertEquals("a tenth of 12 kW: the nine seconds before it rested", 1.2f, block.points[60], 1e-4f)
        assertEquals(12f, block.points[89], 1e-4f)

        // The figure and the time are the ledger's, to the last bit, and both are the whole trip's.
        assertEquals(t.trip.engineKwh, block.kwh, 0.0)
        assertEquals(t.trip.engineSeconds, block.seconds, 0.0)
        assertEquals(90 * 12.0 / 3600.0, block.kwh, 1e-9)
        assertEquals(90.0, block.seconds, 1e-9)

        val printed = read(t)
        assertEquals(ContourReadout.engineKwh(0.3), printed.engineKwh)
        assertEquals(ContourReadout.engineRun(90), printed.engineRun)
    }

    @Test
    fun theChartStaysAsItStoppedThroughThePauseAndTheMarkComesWithTheStart() {
        openTrip()
        run(60, chargeKw = 12.0)
        val resting = rest(5)
        assertFalse(resting.engineCharge.running)
        assertEquals("the chart as it stopped", 60, resting.engineCharge.points.size)
        assertEquals("no mark while nobody knows it is a pause", 0, resting.engineCharge.marks.size)

        rest(300)
        val back = sweep(engine = true, powerKw = -12.0)
        assertEquals("the same chart and the first second after the pause", 61, back.engineCharge.points.size)
        assertArrayEquals(intArrayOf(60), back.engineCharge.marks)
        assertEquals("the figure carries on from the trip's", back.trip.engineKwh, back.engineCharge.kwh, 0.0)
        assertEquals(61.0, back.engineCharge.seconds, 1e-9)
    }

    @Test
    fun aStartInsideTheHoldIsTheSameRunAndHasNoMark() {
        openTrip()
        run(30, chargeKw = 10.0)
        rest(5)
        val t = run(10, chargeKw = 10.0)
        assertEquals("the off seconds are seconds of the chart, at zero", 45, t.engineCharge.points.size)
        assertEquals(0, t.engineCharge.marks.size)
        assertEquals("the time is the engine's alone", 40.0, t.engineCharge.seconds, 1e-9)
        assertEquals(t.trip.engineKwh, t.engineCharge.kwh, 0.0)
    }

    @Test
    fun aMarkSlidesWithTheWindowAndLeavesWithTheSecondsBeforeIt() {
        openTrip()
        run(60, chargeKw = 12.0)
        rest(60)
        var t = run(90, chargeKw = 12.0)
        assertEquals(EngineCharge.WINDOW_SECONDS, t.engineCharge.points.size)
        assertArrayEquals("150 seconds of engine: the window starts 30 in", intArrayOf(30), t.engineCharge.marks)

        t = run(29, chargeKw = 12.0)
        assertArrayEquals("one second of the first stretch left on the glass", intArrayOf(1), t.engineCharge.marks)
        t = run(1, chargeKw = 12.0)
        assertEquals("nothing before it on the glass, so no mark", 0, t.engineCharge.marks.size)
        assertEquals("and the oldest point is still the first second after the pause", 1.2f, t.engineCharge.points[0], 1e-4f)
    }

    @Test
    fun aNewTripStartsFigureTimeAndChartFromZero() {
        openTrip()
        run(40, chargeKw = 12.0)
        rest(20)
        val parked = sweep(engine = false, powerKw = 0.0, park = true)
        assertFalse(parked.trip.open)
        assertEquals("P is not a new trip: the finished trip's figure stays", 40 * 12.0 / 3600.0, parked.engineCharge.kwh, 1e-9)
        assertFalse(parked.engineCharge.isEmpty)

        sweep(engine = false, powerKw = 0.0, park = true)
        val fresh = sweep(engine = false, powerKw = 5.0, km = 0.1)
        assertTrue(fresh.trip.open)
        assertEquals("a new trip", 0.0, fresh.trip.engineKwh, 0.0)
        assertTrue("and no chart until the engine runs in it", fresh.engineCharge.isEmpty)

        val first = run(3, chargeKw = 12.0)
        assertTrue(first.engineCharge.ofTrip)
        assertEquals(3, first.engineCharge.points.size)
        assertEquals(0, first.engineCharge.marks.size)
        assertEquals(first.trip.engineKwh, first.engineCharge.kwh, 0.0)
        assertEquals(3.0, first.engineCharge.seconds, 1e-9)
    }

    /** Driving off from P with the engine running: the trip starts, and the block starts with it. */
    @Test
    fun aTripThatStartsWithTheEngineRunningStartsTheBlockFromZero() {
        // Before any trip, standing in P: the run standing there.
        sweep(engine = false, powerKw = 0.0, park = true)
        val standing = run(20, chargeKw = 8.0, park = true)
        assertFalse(standing.engineCharge.ofTrip)
        assertEquals(20 * 8.0 / 3600.0, standing.engineCharge.kwh, 1e-9)
        assertEquals(0.0, standing.trip.engineKwh, 0.0)

        val off = sweep(engine = true, powerKw = -8.0, km = 0.1)
        assertTrue(off.trip.open)
        assertTrue(off.engineCharge.ofTrip)
        assertEquals("the chart starts with the trip", 1, off.engineCharge.points.size)
        assertEquals(0.8f, off.engineCharge.points[0], 1e-4f)
        assertEquals("and the figure is the trip's first second", off.trip.engineKwh, off.engineCharge.kwh, 0.0)
        assertEquals(8.0 / 3600.0, off.engineCharge.kwh, 1e-12)
        assertEquals(1.0, off.engineCharge.seconds, 1e-9)
    }

    /** Into P with the engine still running: the trip is finished, and the block is the run in P from now. */
    @Test
    fun aTripThatEndsWithTheEngineRunningHandsTheBlockToTheRunInP() {
        openTrip()
        run(30, chargeKw = 12.0)
        val p = sweep(engine = true, powerKw = -8.0, park = true)
        assertFalse(p.trip.open)
        assertFalse(p.engineCharge.ofTrip)
        assertEquals(1, p.engineCharge.points.size)
        assertEquals("the run in P counts from the P", 8.0 / 3600.0, p.engineCharge.kwh, 1e-12)
        assertEquals("the finished trip keeps its own", 30 * 12.0 / 3600.0, p.trip.engineKwh, 1e-9)

        val later = run(10, chargeKw = 8.0, park = true)
        assertEquals(11, later.engineCharge.points.size)
        assertEquals(11 * 8.0 / 3600.0, later.engineCharge.kwh, 1e-9)
        assertEquals(30 * 12.0 / 3600.0, later.trip.engineKwh, 1e-9)
    }

    /** Into P with the engine at rest: the chart stays for the hold and the cell; a start in P is a run of its own. */
    @Test
    fun aTripThatEndsWithTheEngineAtRestKeepsItsChartAndAStartInPIsANewRun() {
        openTrip()
        run(30, chargeKw = 12.0)
        rest(2)
        val p = sweep(engine = false, powerKw = 0.0, park = true)
        assertEquals("the trip's chart as it stopped", 30, p.engineCharge.points.size)
        assertEquals(3L, p.engineCharge.stoppedSeconds)
        assertTrue(p.engineCharge.stoppedRecently)

        // Forced charge a few seconds later, inside what would be the hold: not the trip's chart.
        val forced = run(4, chargeKw = 8.0, park = true)
        assertFalse(forced.engineCharge.ofTrip)
        assertEquals(4, forced.engineCharge.points.size)
        assertEquals(0, forced.engineCharge.marks.size)
        assertEquals(4 * 8.0 / 3600.0, forced.engineCharge.kwh, 1e-9)
    }

    /** No trip open: the block is the run standing in P, as it was - a start ten seconds on is a new run. */
    @Test
    fun withNoTripTheBlockIsTheRunStandingInP() {
        sweep(engine = false, powerKw = 0.0, park = true)
        run(30, chargeKw = 8.0, park = true)
        rest(20, park = true)
        val t = run(5, chargeKw = 8.0, park = true)
        assertFalse(t.trip.opened)
        assertFalse(t.engineCharge.ofTrip)
        assertEquals("a new run, from zero", 5, t.engineCharge.points.size)
        assertEquals(0, t.engineCharge.marks.size)
        assertEquals(5 * 8.0 / 3600.0, t.engineCharge.kwh, 1e-9)
        assertEquals(5.0, t.engineCharge.seconds, 1e-9)
    }

    /** A restart in the middle of a trip: the figure and the time are the journal's, the chart refills. */
    @Test
    fun aProcessRestartMidTripKeepsTheFigureAndTimeAndRefillsTheChart() {
        val record = TripRecord(
            TripEnergy(netKwh = 3.0, engineKwh = 0.4, engineSeconds = 300.0, kilometres = 5.0, opened = true),
            odometerKm = odometerKm,
            armed = false,
        )
        assertTrue(ledger.restore(record, odometerKm))
        charge = EngineCharge()
        val t = run(2, chargeKw = 9.0)
        assertTrue(t.engineCharge.ofTrip)
        assertEquals("the chart is this process's", 2, t.engineCharge.points.size)
        assertEquals(0.4 + 2 * 9.0 / 3600.0, t.engineCharge.kwh, 1e-9)
        assertEquals(302.0, t.engineCharge.seconds, 1e-9)
        assertEquals(ContourReadout.engineRun(302), read(t).engineRun)
    }

    /** A trip carried over from 0.7.1 has no engine charge: the block prints none, as the trip's line does not. */
    @Test
    fun aTripWhoseEngineChargeIsUnknownPrintsNoFigureOnTheBlock() {
        val record = TripRecord(
            TripEnergy(netKwh = 3.0, engineKwh = 0.0, engineSeconds = 300.0, engineKwhKnown = false, opened = true),
            odometerKm = odometerKm,
            armed = false,
        )
        assertTrue(ledger.restore(record, odometerKm))
        val t = run(5, chargeKw = 9.0)
        assertFalse(t.engineCharge.kwhKnown)
        val printed = read(t)
        assertEquals(null, printed.engineKwh)
        assertEquals(ContourReadout.engineRun(305), printed.engineRun)
    }

    /**
     * The time under the block and the engine's cell after it are one quantity, so one answer: 172
     * seconds is «ЗА 2 мин» under the block and «2» minutes in the cell once it has left.
     */
    @Test
    fun theBlocksTimeAndTheEnginesCellSayTheSameMinutes() {
        openTrip()
        run(172, chargeKw = 10.0)
        val stopped = rest(EnergyReadouts.ENGINE_HOLD_SECONDS.toInt() + 2)
        val printed = read(stopped)
        assertEquals(EnergyReadouts.EngineCell.MINUTES, printed.engineCell)
        assertEquals("2", printed.engineCellFigure)
        assertEquals(ContourReadout.engineRun(172), printed.engineRun)
    }
}
