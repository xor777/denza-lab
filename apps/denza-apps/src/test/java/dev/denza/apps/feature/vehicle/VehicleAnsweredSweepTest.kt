package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What one answered sweep makes of the car: the step every energy figure on both screens comes
 * through, held here on the inputs a sweep really has - parsed ids, a clock and the three records.
 *
 * The expectations are worked out from what the test feeds in, never by asking the chart, the log
 * or the ledger what they would say.
 */
class VehicleAnsweredSweepTest {

    private val log = ConsumptionLog()
    private val ledger = TripEnergyLedger()
    private val trace = EngineTrace()
    private var odometerKm = 100.0
    private var atMillis = 0L

    /** One sweep [SWEEP_SECONDS] after the last, the odometer [km] further on. */
    private fun sweep(
        powerKw: Double,
        km: Double = 0.1,
        extra: Map<VehicleSignal, Double> = emptyMap(),
        cold: Map<VehicleSignal, Double> = emptyMap(),
    ): VehicleTelemetry {
        odometerKm += km
        atMillis += (SWEEP_SECONDS * 1000).toLong()
        val parsed = mapOf(
            VehicleSignal.ODOMETER_KM to odometerKm,
            VehicleSignal.POWER_KW to powerKw,
        ) + extra
        val values = VehicleAnsweredSweep.feed(parsed, cold, atMillis, SWEEP_SECONDS, log, ledger, trace)
        return VehicleAnsweredSweep.snapshot(values, log, ledger, trace)
    }

    /**
     * The chart is the log's whole retention and the figure is the window's.
     *
     * Thirty expensive hundred-metre buckets and then a hundred cheap ones: the window is the last ten
     * kilometres, all cheap, while the chart's oldest point still averages the ten readings behind it,
     * nine of them expensive. A chart built from the window - one line away - has no readings behind
     * that point and draws the road the driver just covered as if it had been cheap all along.
     */
    @Test
    fun theChartReadsTheWholeRecordAndTheFigureTheWindow() {
        sweep(powerKw = 0.0, km = 0.0) // the odometer's first reading only seeds it
        repeat(30) { sweep(powerKw = EXPENSIVE_KW) }
        var t = VehicleTelemetry()
        repeat(100) { t = sweep(powerKw = CHEAP_KW) }

        assertEquals("the window is ten kilometres of the cheap road", 100, t.consumption.size)
        assertEquals(kwhPer100(CHEAP_KW), t.consumptionMean!!, 0.01)
        assertEquals("a hundred points", 100, t.chart.values.size)
        assertEquals(
            "the oldest point is nine expensive readings and one cheap one",
            (9 * kwhPer100(EXPENSIVE_KW) + kwhPer100(CHEAP_KW)) / 10,
            t.chart.values.first().toDouble(),
            0.05,
        )
        assertEquals(kwhPer100(CHEAP_KW), t.chart.values.last().toDouble(), 0.05)
    }

    /** P closes the trip, and the first movement after it starts the next one (contract §2.4). */
    @Test
    fun theParkFlagTheTripIsToldIsTheGearbox() {
        repeat(51) { sweep(powerKw = CHEAP_KW) }
        sweep(powerKw = 0.0, km = 0.0, extra = mapOf(VehicleSignal.GEARBOX_PARK to 1.0))
        var t = VehicleTelemetry()
        repeat(10) { t = sweep(powerKw = CHEAP_KW, extra = mapOf(VehicleSignal.GEARBOX_PARK to 0.0)) }

        assertEquals("the five kilometres before P are the last trip, not this one", 1.0, t.trip.kilometres, 1e-6)
    }

    /** A hot value is this sweep's or absent; a cold one is whatever the last cold sweep left. */
    @Test
    fun hotValuesNeverCarryAndColdOnesComeFromTheColdSweepAlone() {
        val cold = mapOf(VehicleSignal.PACK_TEMP_AVG to 28.0)
        val first = sweep(powerKw = 30.0, cold = cold)
        assertEquals(30.0, first[VehicleSignal.POWER_KW]!!, 0.0)
        assertEquals(28.0, first[VehicleSignal.PACK_TEMP_AVG]!!, 0.0)

        // The next sweep did not answer the power: the figure goes, the temperature stays.
        odometerKm += 0.1
        atMillis += 1_000L
        val values = VehicleAnsweredSweep.feed(
            parsed = mapOf(VehicleSignal.ODOMETER_KM to odometerKm, VehicleSignal.PACK_TEMP_AVG to 99.0),
            cold = cold,
            atMillis = atMillis,
            dtSeconds = 1.0,
            log = log,
            ledger = ledger,
            trace = trace,
        )
        assertFalse("a power figure does not outlive its sweep", VehicleSignal.POWER_KW in values)
        assertEquals("and a cold id in a hot batch is not the cold sweep's", 28.0, values[VehicleSignal.PACK_TEMP_AVG]!!, 0.0)
    }

    /** Nothing answered is nothing to show: the hub turns an empty sweep into a failed read. */
    @Test
    fun aSweepWithNothingInItIsEmpty() {
        val values = VehicleAnsweredSweep.feed(emptyMap(), emptyMap(), 1_000L, 1.0, log, ledger, trace)
        assertTrue(values.isEmpty())
    }

    /** The engine flag reads 1 or 3 when it turns and the trace takes either as running. */
    @Test
    fun theEngineFlagFromOneUpIsTheEngineRunning() {
        repeat(5) {
            sweep(
                powerKw = -8.0,
                extra = mapOf(VehicleSignal.ENGINE_RUNNING to 1.0, VehicleSignal.GENERATION_KW to 8.0),
            )
        }
        val t = sweep(powerKw = -8.0, extra = mapOf(VehicleSignal.ENGINE_RUNNING to 1.0, VehicleSignal.GENERATION_KW to 8.0))
        assertTrue("the trace has the engine giving", t.engineTrace.gives)
        assertTrue("and the trip has its minutes", t.trip.engineSeconds > 0.0)
    }

    /** kWh per 100 km of a hundred-metre bucket driven at [kw] for one sweep. */
    private fun kwhPer100(kw: Double): Double = kw * SWEEP_SECONDS / 3600.0 / 0.1 * 100.0

    private companion object {
        /** Three seconds for a hundred metres is 120 km/h, inside the odometer's plausibility. */
        const val SWEEP_SECONDS = 3.0

        /** 0.05 kWh in a bucket, 50 kWh/100 km. */
        const val EXPENSIVE_KW = 60.0

        /** 0.017 kWh in a bucket, 17 kWh/100 km. */
        const val CHEAP_KW = 20.4
    }
}
