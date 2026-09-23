package dev.denza.apps.feature.trip

import dev.denza.apps.feature.vehicle.ConsumptionChart
import dev.denza.apps.feature.vehicle.ConsumptionSample
import dev.denza.apps.feature.vehicle.EngineTrace
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
 * The car page's model, as [StripReadings] writes it from a snapshot: the page's semantics, which
 * the Luminofor redraw was not allowed to change, stated as what ends up in the model.
 *
 * A figure that did not arrive is absent and its caption stays; the engine's cell exists only while
 * it has something to say; a closed car is its instruction and nothing else; the words are the
 * energy contract's in the strip's sentence case, and the temperatures' colours are the cluster's
 * thresholds.
 */
class StripReadingsTest {

    private fun snapshot(
        powerKw: Double? = 34.0,
        values: Map<VehicleSignal, Double> = emptyMap(),
        trip: TripEnergy = TripEnergy(netKwh = 9.27, kilometres = 42.2),
        engineSeconds: Int = 0,
    ): VehicleTelemetry {
        val all = LinkedHashMap<VehicleSignal, Double>(values)
        if (powerKw != null) all[VehicleSignal.POWER_KW] = powerKw
        val road = List(100) { ConsumptionSample(110.0 - (99 - it) * 0.1, 0.017, 0.1, 0.1) }
        val engine = EngineTrace()
        repeat(engineSeconds) { engine.sample(it * 1_000L, engineRunning = true, generationKw = 8.0) }
        return VehicleTelemetry(
            access = VehicleAccess.READY,
            values = all,
            consumption = road,
            chart = ConsumptionChart.of(road),
            engineTrace = engine.snapshot(),
            trip = trip,
        )
    }

    private fun car(telemetry: VehicleTelemetry): StripModel =
        StripModel().also { StripReadings().car(it, telemetry) }

    @Test
    fun anElectricDriveIsTheBoardsCarPage() {
        val t = snapshot(
            values = mapOf(
                VehicleSignal.PACK_VOLT to 549.0,
                VehicleSignal.PACK_TEMP_AVG to 28.0,
                VehicleSignal.MOTOR_FRONT_C to 88.0,
                VehicleSignal.MOTOR_REAR_LEFT_C to 61.0,
                VehicleSignal.MOTOR_REAR_RIGHT_C to 63.4,
                VehicleSignal.INVERTER_C to 74.0,
            ),
        )
        val model = car(t)
        assertFalse(model.closed)
        assertEquals("Из батареи", model.power.caption)
        assertEquals("34", model.power.figure)
        assertEquals("кВт", model.power.unit)
        assertFalse(model.power.dot || model.power.blue || model.power.dim)

        assertEquals("Напряжение", model.volts.caption)
        assertEquals("549", model.volts.figure)
        assertEquals("В", model.volts.unit)

        assertEquals(listOf("28°", "88°", "61°", "63°", "74°"), model.temps.map { it.figure })
        // The front motor is past its band by more than the margin, the inverter past its watch.
        assertEquals(
            listOf(StripHeat.NORMAL, StripHeat.DANGER, StripHeat.NORMAL, StripHeat.NORMAL, StripHeat.WARNING),
            model.temps.map { it.heat },
        )

        assertTrue(model.tripCell.present)
        assertEquals("42 км · за поездку", model.tripCell.caption)
        assertEquals("9,3", model.tripCell.figure)
        assertEquals("кВт·ч", model.tripCell.unit)
        assertFalse("an engine that never ran has no cell", model.engine.present)

        assertEquals("Расход", model.spendWord)
        assertEquals("17", model.spendFigure)
        assertEquals("кВт·ч/100 км · за 10 км", model.spendWindow)
        assertFalse(model.spendNegative)
        assertSame("the chart is the snapshot's own points", t.chart.values, model.chart)
        assertEquals(t.chart.values.size, model.chartCount)
    }

    @Test
    fun theEngineGivingNamesItsSourceInBlue() {
        val model = car(
            snapshot(
                powerKw = -14.0,
                values = mapOf(
                    VehicleSignal.ENGINE_RUNNING to 3.0,
                    VehicleSignal.ENGINE_RPM to 1650.0,
                    VehicleSignal.GENERATION_KW to 14.0,
                ),
                engineSeconds = 60,
            ),
        )
        assertEquals("В батарею от ДВС", model.power.caption)
        assertEquals("14", model.power.figure)
        assertTrue(model.power.dot)
        assertTrue(model.power.blue)
        assertTrue(model.engine.present)
        assertEquals("ДВС", model.engine.caption)
        assertEquals("1650", model.engine.figure)
        assertEquals("об/мин", model.engine.unit)
    }

    @Test
    fun aFigureThatDidNotArriveTakesItsUnitAndLeavesItsCaption() {
        val model = car(snapshot(powerKw = null))
        assertTrue(model.power.present)
        assertEquals("Батарея", model.power.caption)
        assertNull(model.power.figure)
        assertNull(model.power.unit)
        assertEquals("Напряжение", model.volts.caption)
        assertNull(model.volts.figure)
        assertTrue("a temperature that did not answer keeps its glyph only", model.temps.all { it.figure == null })
    }

    @Test
    fun aPackWithNoDirectionIsDrawnDim() {
        val model = car(snapshot(powerKw = 1.4))
        assertEquals("Батарея", model.power.caption)
        assertEquals("1", model.power.figure)
        assertTrue(model.power.dim)
        assertFalse(model.power.blue)
    }

    @Test
    fun aClosedCarIsItsInstructionAndNothingElse() {
        val model = car(VehicleTelemetry(access = VehicleAccess.UNAVAILABLE, message = "ADB-ключ не подтверждён"))
        assertTrue(model.closed)
        assertEquals("ADB-ключ не подтверждён", model.message)
        val back = StripModel()
        val readings = StripReadings()
        readings.car(back, VehicleTelemetry(access = VehicleAccess.UNAVAILABLE, message = "нет"))
        readings.car(back, snapshot())
        assertFalse("the page comes back when the car does", back.closed)
    }

    @Test
    fun aCarThatHasNotAnsweredHasNoTrip() {
        val model = car(VehicleTelemetry())
        assertFalse(model.tripCell.present)
        assertFalse(model.engine.present)
        assertNull(model.spendFigure)
    }

    @Test
    fun theRoadIsPrintedTheWayTheBoardPrintsIt() {
        assertEquals("640 м", StripReadings.roadLabel(640.0))
        assertEquals("12,8 км", StripReadings.roadLabel(12_840.0))
        assertEquals("128 км", StripReadings.roadLabel(128_000.0))
        assertEquals("1,2", StripReadings.tenths(12))
        assertEquals("-0,4", StripReadings.tenths(-4))
    }
}
