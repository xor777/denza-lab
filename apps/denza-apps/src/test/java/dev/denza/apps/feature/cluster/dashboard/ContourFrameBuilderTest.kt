package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.feature.vehicle.ConsumptionChart
import dev.denza.apps.feature.vehicle.ConsumptionSample
import dev.denza.apps.feature.vehicle.EngineTrace
import dev.denza.apps.feature.vehicle.EngineTraceSnapshot
import dev.denza.apps.feature.vehicle.TripEnergy
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The car as the Luminofor board's fixture: what [ContourFrameBuilder] puts in a [ContourFrame].
 *
 * The renderer draws a frame and decides nothing, so every rule the panel keeps is visible here as
 * a field: a caption that stays, a figure that leaves, a seat that exists on P alone, a colour.
 * And where a case is one the board draws, the strings are compared with the board's own - read
 * out of the debug build's `fixtures.json` - so the app fed the city's car prints the city board.
 */
class ContourFrameBuilderTest {

    private val step = 1f / 30f

    /** The panel's three stateful parts and the frame they fill, stepped together as the view does. */
    private inner class Panel {
        val scene = ContourScene()
        val motion = ContourMotion()
        val builder = ContourFrameBuilder()
        val frame = ContourFrame()
        var clock = 0f

        /** [seconds] of frames with [t] arriving three times a second, or never if [quiet]. */
        fun run(t: VehicleTelemetry, seconds: Float, quiet: Boolean = false): ContourFrame {
            var elapsed = 0f
            var next = 0f
            while (elapsed < seconds) {
                val arrived = !quiet && elapsed >= next
                if (arrived) next += 1f / 3f
                scene.frame(t, arrived, step)
                motion.step(scene.held(ContourValue.POWER), scene.held(ContourValue.RPM), step)
                clock += step
                elapsed += step
            }
            return builder.build(frame, t, motion, scene, clock)
        }
    }

    /** [n] hundred-metre buckets costing [kwh] each: 0.017 is 17 kWh/100 km. */
    private fun road(n: Int, kwh: Double = 0.017) =
        List(n) { ConsumptionSample(110.0 - (n - 1 - it) * 0.1, kwh, 0.1, 0.1) }

    private fun trace(seconds: Int, running: Boolean, generationKw: Double): EngineTraceSnapshot {
        val engine = EngineTrace()
        repeat(seconds) { engine.sample(it * 1_000L, engineRunning = running, generationKw = generationKw) }
        return engine.snapshot()
    }

    /** An engine that ran a moment ago and gave nothing: the minutes' cell, and no box. */
    private fun stoppedEngine(): EngineTraceSnapshot {
        val engine = EngineTrace()
        repeat(30) { engine.sample(it * 1_000L, engineRunning = it < 5, generationKw = 0.0) }
        return engine.snapshot()
    }

    /** The city board's car: 26 kW out, 549 V, the five cells calm, six minutes of engine behind it. */
    private fun city(
        values: Map<VehicleSignal, Double> = emptyMap(),
        buckets: List<ConsumptionSample> = road(100),
        trip: TripEnergy = TripEnergy(netKwh = 9.3, kilometres = 42.0, engineSeconds = 360.0),
        trace: EngineTraceSnapshot = stoppedEngine(),
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
            engineTrace = trace,
            trip = trip,
        )
    }

    private fun board(id: String): Map<String, Any?> = ContourFixturesContractTest.fixture(id)

    // ---------------------------------------------------------------- the skeleton

    @Test
    fun theFirstSecondsAreTheSkeletonAndNothingElse() {
        val frame = Panel().run(VehicleTelemetry(), 0.5f)
        assertFalse(frame.unavailable)
        assertFalse("no beam", frame.powerFresh)
        assertFalse("no unit before the first reading", frame.heroUnit)
        assertNull(frame.heroFigure)
        assertNull(frame.batteryCaption)
        assertTrue("no glyphs", frame.temps.none { it.shown })
        assertNull(frame.iceCaption)
        assertNull(frame.tripCaption)
        assertEquals(0, frame.chartCount)
        assertNull(frame.consumption)
        assertNull(frame.consumptionUnit)
        assertFalse(ContourGeometry.flickers(frame))
    }

    // ---------------------------------------------------------------- the board's words

    @Test
    fun theCitysCarPrintsTheCityBoard() {
        val frame = Panel().run(city(), 1.5f)
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
        assertFalse(frame.engineGiving)
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
        val frame = Panel().run(city(buckets = road(37)), 1f)
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
        val parked = Panel().run(
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

        // On the move the recuperation leaves and what the engine gave stays: the seats are the
        // contract's, «ДАЛ ДВС» on both and «● РЕКУПЕРАЦИЯ» on P alone (§2.4).
        val moving = Panel().run(city(trip = trip), 1f)
        assertEquals(ContourReadout.CAPTION_ENGINE_GAVE, moving.gaveCaption)
        assertEquals("1,1", moving.gaveKwh)
        assertNull(moving.regenCaption)
        assertEquals("a whole number on the move", "17", moving.consumption)
    }

    @Test
    fun theEnginesBoxTakesTheTripsPlaceWhileItGives() {
        val running = city(
            values = mapOf(
                VehicleSignal.POWER_KW to -14.0,
                VehicleSignal.ENGINE_RUNNING to 3.0,
                VehicleSignal.ENGINE_RPM to 1650.0,
                VehicleSignal.GENERATION_KW to 14.0,
            ),
            trace = trace(120, running = true, generationKw = 14.0),
        )
        val frame = Panel().run(running, 1f)
        val board = board("cluster-engine")
        assertTrue(frame.engineGiving)
        assertEquals(board["engineCaption"], frame.engineCaption)
        assertEquals(board["engineWindow"], frame.engineWindow)
        assertEquals(board["iceCaption"], frame.iceCaption)
        assertEquals(board["iceFigure"], frame.iceFigure)
        assertEquals((board["generation"] as List<*>).size, frame.generationCount)
        assertNull("the box stands where the trip was", frame.tripCaption)
        assertNull(frame.gaveCaption)
        assertTrue("energy into the pack is blue", frame.into)
        // The consumption is the battery's alone while the engine runs, so it says nothing more
        // than grey says. The board draws it ink: see the fixture adapter.
        assertEquals(ContourFrame.Tone.GREY, frame.consumptionTone)
    }

    @Test
    fun theEnginesSentenceClosesUpWhenItsFigureStopsArriving() {
        val panel = Panel()
        val giving = city(
            values = mapOf(
                VehicleSignal.ENGINE_RUNNING to 3.0,
                VehicleSignal.ENGINE_RPM to 1650.0,
                VehicleSignal.GENERATION_KW to 14.0,
            ),
            trace = trace(60, running = true, generationKw = 14.0),
        )
        panel.run(giving, 1f)
        // The flag drops and the box holds its ten seconds; the figure goes with the flag.
        val stopped = city(
            values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0),
            trace = trace(60, running = true, generationKw = 14.0),
        )
        val frame = panel.run(stopped, 3f)
        assertTrue("the box holds", frame.engineGiving)
        assertEquals(ContourReadout.LEGEND_PREFIX, frame.engineCaption)
    }

    @Test
    fun aHotCellCarriesItsOwnLevel() {
        val frame = Panel().run(
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
        val frame = Panel().run(
            city(values = mapOf(VehicleSignal.CELL_MIN_MV to 3_300.0, VehicleSignal.CELL_MAX_MV to 3_332.0)),
            1f,
        )
        assertEquals(board["caption"], frame.spreadCaption)
        assertEquals(board["value"], frame.spreadValue)
        assertEquals(board["unit"], frame.spreadUnit)
        assertEquals(ContourReadout.Level.WATCH, frame.spreadLevel)

        val calm = Panel().run(
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
        val frame = Panel().run(charging, 3f)
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
        val frame = Panel().run(VehicleTelemetry(access = VehicleAccess.UNAVAILABLE, message = message), 0.5f)
        assertTrue(frame.unavailable)
        assertEquals(message, frame.message)
        assertFalse(ContourGeometry.flickers(frame))
    }

    // ---------------------------------------------------------------- the rules

    @Test
    fun aStaleFigureLeavesAndItsCaptionStays() {
        val panel = Panel()
        val t = city()
        panel.run(t, 1f)
        // Past the hot horizon and past the cold one: every figure has left, every caption stays.
        val frame = panel.run(t, 30f, quiet = true)
        assertFalse("the band leaves", frame.powerFresh)
        assertNull(frame.heroFigure)
        assertTrue("its unit stays", frame.heroUnit)
        assertNull(frame.volts)
        assertEquals(ContourReadout.TITLE_PACK, frame.batteryCaption)
        assertTrue(frame.temps.all { it.shown && it.value == null })
        assertNull(frame.tripKwh)
        assertEquals("the kilometres leave with the separator", ContourReadout.CAPTION_TRIP_ALONE, frame.tripCaption)
        assertNull(frame.consumption)
        assertEquals(ContourReadout.UNIT_PER_100KM, frame.consumptionUnit)
        assertEquals("closed road is still road", 100, frame.chartCount)
        assertFalse(ContourGeometry.flickers(frame))
    }

    @Test
    fun aCoastInsideTheNeutralZoneStaysInk() {
        val panel = Panel()
        panel.run(city(values = mapOf(VehicleSignal.POWER_KW to -38.0)), 1f).let {
            assertTrue("a return is blue", it.into)
            assertEquals("38", it.heroFigure)
        }
        assertFalse(
            "two kilowatts back is inside the neutral zone",
            Panel().run(city(values = mapOf(VehicleSignal.POWER_KW to -2.0)), 1f).into,
        )
    }

    @Test
    fun aRoadThatGaveBackMorePrintsItsMinusInBlue() {
        val frame = Panel().run(city(buckets = road(100, kwh = -0.02)), 1f)
        assertEquals("-20", frame.consumption)
        assertEquals(ContourFrame.Tone.BLUE, frame.consumptionTone)
    }

    @Test
    fun aSteadyPanelReprintsNothing() {
        // Thirty frames a second over the vehicle's own instruments: a frame that changed nothing
        // hands back the very strings the last one did, so nothing was built to draw it.
        val panel = Panel()
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
