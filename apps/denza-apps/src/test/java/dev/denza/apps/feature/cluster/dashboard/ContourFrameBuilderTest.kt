package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.core.EnglishWords
import dev.denza.apps.core.figure
import dev.denza.apps.design.luminofor.SpecJson
import dev.denza.apps.feature.vehicle.ConsumptionChart
import dev.denza.apps.feature.vehicle.ConsumptionSample
import dev.denza.apps.feature.vehicle.EngineCharge
import dev.denza.apps.feature.vehicle.EngineChargeSnapshot
import dev.denza.apps.feature.vehicle.TripEnergy
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The car as the Luminofor board's fixture: what [ContourFrameBuilder] puts in a [ContourFrame].
 *
 * The renderer draws a frame and decides nothing, so every rule the panel keeps is visible here as
 * a field: a caption that stays, a figure that leaves, a seat that exists on P alone, a colour.
 * And where a case is one the board draws, the strings are compared with the board's own - read
 * out of the debug build's `fixtures.json` - so the app fed the city's car prints the city board.
 */
open class ContourFrameBuilderTest {

    /** [n] hundred-metre buckets costing [kwh] each: 0.017 is 17 kWh/100 km. */
    private fun road(n: Int, kwh: Double = 0.017) =
        List(n) { ConsumptionSample(110.0 - (n - 1 - it) * 0.1, kwh, 0.1, 0.1) }

    /**
     * A run of [seconds] at [loadKw] of pack power (negative into the pack), a sweep a second, and
     * then [stoppedFor] seconds with the flag down.
     */
    private fun run(seconds: Int, loadKw: Double, stoppedFor: Int = 0): EngineChargeSnapshot {
        val engine = EngineCharge()
        repeat(seconds + stoppedFor) {
            engine.sample(it * 1_000L, engineRunning = it < seconds, loadKw = loadKw, dtSeconds = 1.0)
        }
        return engine.snapshot()
    }

    /** An engine that ran a while ago: the minutes' cell, and no block. */
    private fun stoppedEngine(): EngineChargeSnapshot = run(5, loadKw = 26.0, stoppedFor = 25)

    /** The city board's car: 26 kW out, 549 V, the five cells calm, six minutes of engine behind it. */
    private fun city(
        values: Map<VehicleSignal, Double> = emptyMap(),
        buckets: List<ConsumptionSample> = road(100),
        trip: TripEnergy = TripEnergy(netKwh = 9.3, kilometres = 42.0, engineSeconds = 360.0),
        trace: EngineChargeSnapshot = stoppedEngine(),
        access: VehicleAccess = VehicleAccess.READY,
    ): VehicleTelemetry {
        val all = linkedMapOf(
            VehicleSignal.POWER_KW to 26.0,
            VehicleSignal.PACK_VOLT to 549.0,
            VehicleSignal.PACK_TEMP_AVG to 28.0,
            VehicleSignal.MOTOR_FRONT_C to 31.0,
            VehicleSignal.MOTOR_REAR_LEFT_C to 29.0,
            VehicleSignal.MOTOR_REAR_RIGHT_C to 31.0,
            VehicleSignal.INVERTER_C to 32.0,
            VehicleSignal.GEARBOX_PARK to 0.0,
        )
        all.putAll(values)
        return VehicleTelemetry(
            access = access,
            values = all,
            consumption = buckets,
            chart = ConsumptionChart.of(buckets),
            engineCharge = trace,
            trip = trip,
        )
    }

    private fun board(id: String): Map<String, Any?> = SpecJson.fixture(id)

    // ---------------------------------------------------------------- the skeleton

    @Test
    fun theFirstSecondsAreTheSkeletonAndNothingElse() {
        val frame = ContourPanel().run(VehicleTelemetry(), 0.5f)
        val board = board("cluster-waking")
        assertFalse(frame.unavailable)
        assertEquals("no beam", board["powerFresh"], frame.powerFresh)
        assertEquals("no unit before the first reading", board["heroUnit"], frame.heroUnit)
        assertNull(frame.heroFigure)
        assertEquals("no glyphs", (board["temps"] as List<*>).size, frame.temps.count { it.shown })
        assertEquals((board["chart"] as List<*>).size, frame.chartCount)
        // No word on either side: the board draws none of them and the frame carries none.
        mapOf(
            "batteryCaption" to frame.batteryCaption,
            "iceCaption" to frame.iceCaption,
            "tripCaption" to frame.tripCaption,
            "consumption" to frame.consumption,
            "consumptionUnit" to frame.consumptionUnit,
        ).forEach { (key, word) ->
            assertNull("the board's $key", board[key])
            assertNull("the frame's $key", word)
        }
        assertFalse(ContourGeometry.flickers(frame))
    }

    // ---------------------------------------------------------------- the board's words

    @Test
    fun theCitysCarPrintsTheCityBoard() {
        val frame = ContourPanel().run(city(), 1.5f)
        val board = board("cluster-city")

        assertTrue(frame.powerFresh)
        assertEquals(26f, frame.powerKw, 0.5f)
        assertEquals(board["power"].toString().toDouble().toInt().toString(), frame.heroFigure)
        assertTrue(frame.heroUnit)
        assertFalse("out of the pack is ink", frame.into)

        assertEquals(board["batteryCaption"], frame.batteryCaption)
        assertEquals(board["volts"], frame.volts)
        val temps = board["temps"] as List<*>
        frame.temps.forEachIndexed { index, cell ->
            val expected = temps[index] as Map<*, *>
            assertTrue(cell.shown)
            assertEquals("cell $index", expected["value"], cell.value)
            assertEquals(ContourReadout.Level.NORMAL, cell.level)
        }
        assertNull("no spread while the pack holds together", frame.spreadCaption)

        assertEquals(board["iceCaption"], frame.iceCaption)
        assertEquals(board["iceFigure"], frame.iceFigure)
        assertFalse(frame.engineBlock)
        assertEquals(board["tripCaption"], frame.tripCaption)
        assertEquals(board["tripKwh"], frame.tripKwh)
        assertEquals(board["tripUnit"], frame.tripUnit)
        assertNull("the engine gave nothing this trip", frame.gaveCaption)
        assertNull("and the recuperation is P's", frame.regenCaption)

        assertEquals(board["consumption"], frame.consumption)
        assertEquals(board["consumptionUnit"], frame.consumptionUnit)
        assertEquals(ContourFrame.Tone.INK, frame.consumptionTone)
        assertEquals("a full window is a hundred points", 100, frame.chartCount)
        assertEquals(17f, frame.chart[99], 1e-3f)
        assertTrue("and the threads flicker", ContourGeometry.flickers(frame))
    }

    @Test
    fun aWindowStillFillingNamesItsRoadAndGrowsFromTheRight() {
        val frame = ContourPanel().run(city(buckets = road(37)), 1f)
        val board = board("cluster-filling")
        assertEquals(board["consumptionUnit"], frame.consumptionUnit)
        assertEquals((board["chart"] as List<*>).size, frame.chartCount)
    }

    @Test
    fun standingOnPTheDetailLineCarriesBothSeatsAndTheTenth() {
        val trip = TripEnergy(
            netKwh = 9.3,
            kilometres = 42.0,
            engineSeconds = 360.0,
            engineKwh = 1.1,
            recoveredKwh = 3.1,
        )
        val parked = ContourPanel().run(
            city(
                values = mapOf(VehicleSignal.POWER_KW to 1.0, VehicleSignal.GEARBOX_PARK to 1.0),
                buckets = road(100, kwh = 0.0168),
                trip = trip,
            ),
            1f,
        )
        val board = board("cluster-park")
        assertEquals(board["gaveCaption"], parked.gaveCaption)
        assertEquals(board["gaveKwh"], parked.gaveKwh)
        assertEquals(board["regenCaption"], parked.regenCaption)
        assertEquals(board["regenKwh"], parked.regenKwh)
        assertEquals("a tenth on P", board["consumption"], parked.consumption)
        assertEquals(board["tripCaption"], parked.tripCaption)

        // On the move the recuperation leaves and the engine's charge stays: the seats are the
        // contract's, «ЗАРЯД ОТ ДВС» on both and «● РЕКУПЕРАЦИЯ» on P alone (§2.4).
        val moving = ContourPanel().run(city(trip = trip), 1f)
        assertEquals(ContourReadout.CAPTION_ENGINE_CHARGE, moving.gaveCaption)
        assertEquals(figure("1,1"), moving.gaveKwh)
        assertNull(moving.regenCaption)
        assertEquals("a whole number on the move", "17", moving.consumption)
    }

    @Test
    fun theEnginesBlockTakesTheTripsPlaceWhileItRuns() {
        // Forty-one seconds at 16 kW into the pack: «0,2» «ЗА 0:41», the charging board's words.
        val running = city(
            values = mapOf(
                VehicleSignal.POWER_KW to -16.0,
                VehicleSignal.ENGINE_RUNNING to 3.0,
                VehicleSignal.ENGINE_RPM to 1518.0,
            ),
            trace = run(41, loadKw = -16.0),
        )
        val frame = ContourPanel().run(running, 1f)
        val board = board("cluster-engine")
        assertTrue(frame.engineBlock)
        assertEquals(board["engineCaption"], frame.engineCaption)
        assertEquals(board["engineKwh"], frame.engineKwh)
        assertEquals(board["engineRun"], frame.engineRun)
        assertEquals(board["iceCaption"], frame.iceCaption)
        assertEquals(board["iceFigure"], frame.iceFigure)
        assertEquals("a point a second, the filling one too", 41, frame.chargeCount)
        assertEquals("the newest point is the last ten seconds' mean", 16f, frame.charge[40], 1e-3f)
        assertNull("the block stands where the trip was", frame.tripCaption)
        assertNull(frame.gaveCaption)
        assertTrue("energy into the pack is blue", frame.into)
        // The consumption is the battery's alone while the engine runs, so it says nothing more
        // than grey says. The board draws it ink: see the fixture adapter.
        assertEquals(ContourFrame.Tone.GREY, frame.consumptionTone)
    }

    @Test
    fun theBlockHoldsItsRunBesideAnEmptyCornerAndThenGivesTheShelfBack() {
        val panel = ContourPanel()
        // The recorded drive's trip at 19:25:40 (VehicleLogReplayTest): one run, its 0,4 the trip's.
        val trip = TripEnergy(netKwh = 0.45, kilometres = 2.0, engineSeconds = 172.8, engineKwh = 0.42)
        panel.run(
            city(
                values = mapOf(VehicleSignal.ENGINE_RUNNING to 3.0, VehicleSignal.ENGINE_RPM to 1650.0),
                trip = trip,
                trace = run(60, loadKw = -14.0),
            ),
            1f,
        )
        // The flag drops and the block holds its ten seconds with the run's figure and time; the
        // corner is empty rather than a second answer beside them.
        val held = panel.run(
            city(values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0), trip = trip, trace = run(60, loadKw = -14.0, stoppedFor = 1)),
            3f,
        )
        assertTrue("the block holds", held.engineBlock)
        assertEquals(ContourReadout.CAPTION_ENGINE_CHARGE, held.engineCaption)
        assertEquals(figure("0,2"), held.engineKwh)
        assertEquals(ContourReadout.engineRun(60), held.engineRun)
        assertNull("the trip is not beside it", held.gaveCaption)
        assertNull("an empty corner", held.iceCaption)
        assertNull(held.iceFigure)

        // Past the hold: the trip is back and its detail says what the engine charged this trip.
        val gone = panel.run(
            city(values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0), trip = trip, trace = run(60, loadKw = -14.0, stoppedFor = 12)),
            8f,
        )
        val board = board("cluster-engine-gone")
        assertFalse(gone.engineBlock)
        assertEquals(board["gaveCaption"], gone.gaveCaption)
        assertEquals(board["gaveKwh"], gone.gaveKwh)
        assertEquals(board["iceCaption"], gone.iceCaption)
        assertEquals(board["iceFigure"], gone.iceFigure)
        assertEquals(board["tripKwh"], gone.tripKwh)
        assertEquals(board["tripCaption"], gone.tripCaption)
    }

    @Test
    fun aHotCellCarriesItsOwnLevel() {
        val frame = ContourPanel().run(
            city(
                values = mapOf(
                    VehicleSignal.PACK_TEMP_AVG to 36.0,
                    VehicleSignal.MOTOR_FRONT_C to 88.0,
                    VehicleSignal.MOTOR_REAR_LEFT_C to 61.0,
                    VehicleSignal.MOTOR_REAR_RIGHT_C to 63.0,
                    VehicleSignal.INVERTER_C to 74.0,
                ),
            ),
            1f,
        )
        val temps = board("cluster-hot")["temps"] as List<*>
        val levels = mapOf(
            "normal" to ContourReadout.Level.NORMAL,
            "warning" to ContourReadout.Level.WATCH,
            "danger" to ContourReadout.Level.ALERT,
        )
        frame.temps.forEachIndexed { index, cell ->
            val expected = temps[index] as Map<*, *>
            assertEquals("cell $index", expected["value"], cell.value)
            assertEquals("cell $index", levels[expected["state"]], cell.level)
        }
    }

    @Test
    fun theSpreadAppearsWithTheProblemAndInItsColour() {
        val board = board("cluster-spread")["spread"] as Map<*, *>
        val frame = ContourPanel().run(
            city(values = mapOf(VehicleSignal.CELL_MIN_MV to 3_300.0, VehicleSignal.CELL_MAX_MV to 3_332.0)),
            1f,
        )
        assertEquals(board["caption"], frame.spreadCaption)
        assertEquals(board["value"], frame.spreadValue)
        assertEquals(board["unit"], frame.spreadUnit)
        assertEquals(ContourReadout.Level.WATCH, frame.spreadLevel)

        val calm = ContourPanel().run(
            city(values = mapOf(VehicleSignal.CELL_MIN_MV to 3_300.0, VehicleSignal.CELL_MAX_MV to 3_310.0)),
            1f,
        )
        assertNull("a pack holding together says nothing", calm.spreadCaption)
    }

    @Test
    fun aChargeCountsDownInTheFiguresSeat() {
        val charging = city(
            values = mapOf(
                VehicleSignal.POWER_KW to -1.0,
                VehicleSignal.GEARBOX_PARK to 1.0,
                VehicleSignal.CHARGE_GUN to 2.0,
                VehicleSignal.CHARGE_KW to 7.0,
                VehicleSignal.CHARGE_HOURS to 2.0,
                VehicleSignal.CHARGE_MINUTES to 15.0,
            ),
        )
        val frame = ContourPanel().run(charging, 3f)
        val board = board("cluster-charging")
        assertEquals(board["consumption"], frame.consumption)
        assertEquals(board["consumptionUnit"], frame.consumptionUnit)
        assertEquals("the charger's kilowatts, into the pack", -7f, frame.powerKw, 0.5f)
        assertTrue(frame.into)
        assertEquals("the chart stays", 100, frame.chartCount)
    }

    @Test
    fun aClosedShellIsTheSkeletonAndItsReason() {
        val message = board("cluster-unavailable")["message"] as String
        val frame = ContourPanel().run(VehicleTelemetry(access = VehicleAccess.UNAVAILABLE, message = message), 0.5f)
        assertTrue(frame.unavailable)
        assertEquals(message, frame.message)
        assertFalse(ContourGeometry.flickers(frame))
    }

    // ---------------------------------------------------------------- the rules

    @Test
    fun aStaleFigureLeavesAndItsCaptionStays() {
        val panel = ContourPanel()
        val t = city()
        panel.run(t, 1f)
        // Past the hot horizon and past the cold one: every figure has left, every caption stays.
        val frame = panel.run(t, 30f, quiet = true)
        val board = board("cluster-stale")
        assertEquals("the band leaves", board["powerFresh"], frame.powerFresh)
        assertNull(frame.heroFigure)
        assertTrue("its unit stays", frame.heroUnit)
        assertNull(board["volts"])
        assertNull(frame.volts)
        assertEquals(board["batteryCaption"], frame.batteryCaption)
        val temps = board["temps"] as List<*>
        assertEquals(temps.size, frame.temps.size)
        assertTrue(temps.all { (it as Map<*, *>)["value"] == null })
        assertTrue(frame.temps.all { it.shown && it.value == null })
        assertNull(board["tripKwh"])
        assertNull(frame.tripKwh)
        assertEquals("the kilometres leave with the separator", board["tripCaption"], frame.tripCaption)
        assertEquals(board["tripUnit"], frame.tripUnit)
        assertEquals(board["iceCaption"], frame.iceCaption)
        assertNull(board["consumption"])
        assertNull(frame.consumption)
        assertEquals(board["consumptionUnit"], frame.consumptionUnit)
        assertEquals("closed road is still road", (board["chart"] as List<*>).size, frame.chartCount)
        assertFalse(ContourGeometry.flickers(frame))
    }

    @Test
    fun aCoastInsideTheNeutralZoneStaysInk() {
        val panel = ContourPanel()
        panel.run(city(values = mapOf(VehicleSignal.POWER_KW to -38.0)), 1f).let {
            assertTrue("a return is blue", it.into)
            assertEquals("38", it.heroFigure)
        }
        assertFalse(
            "two kilowatts back is inside the neutral zone",
            ContourPanel().run(city(values = mapOf(VehicleSignal.POWER_KW to -2.0)), 1f).into,
        )
    }

    @Test
    fun aRoadThatGaveBackMorePrintsItsMinusInBlue() {
        val frame = ContourPanel().run(city(buckets = road(100, kwh = -0.02)), 1f)
        assertEquals("-20", frame.consumption)
        assertEquals(ContourFrame.Tone.BLUE, frame.consumptionTone)
    }

    @Test
    fun aSteadyPanelReprintsNothing() {
        // Thirty frames a second over the vehicle's own instruments: a frame that changed nothing
        // hands back the very strings the last one did, so nothing was built to draw it.
        val panel = ContourPanel()
        val t = city(
            trip = TripEnergy(netKwh = 9.3, kilometres = 42.0, engineSeconds = 360.0, engineKwh = 1.1),
        )
        val first = panel.run(t, 1f)
        val strings = listOf(
            first.heroFigure, first.volts, first.temps[0].value, first.iceFigure, first.tripCaption,
            first.tripKwh, first.gaveKwh, first.consumption, first.consumptionUnit,
        )
        val second = panel.builder.build(panel.frame, t, panel.motion, panel.scene, panel.clock)
        val again = listOf(
            second.heroFigure, second.volts, second.temps[0].value, second.iceFigure, second.tripCaption,
            second.tripKwh, second.gaveKwh, second.consumption, second.consumptionUnit,
        )
        strings.zip(again).forEach { (a, b) -> assertSame(a, b) }
    }
}

/** The car of each scene prints its board in English: `fixtures.en.json`, which `shot.py` makes from `values-en/`. */
class ContourFrameBuilderEnglishTest : ContourFrameBuilderTest() {
    @get:Rule
    val english = EnglishWords()
}
