package dev.denza.apps.feature.vehicle

import dev.denza.apps.feature.cluster.dashboard.ContourFlow
import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One quantity, one definition, one set of words, on both screens.
 *
 * `docs/energy-display-contract.md` §7: a cluster-side instance and a strip-side one are driven
 * from the same list of snapshots and have to agree - the same figure, the same window distance,
 * the same direction, and bin arrays that are equal element for element. They were not one
 * function before, and the car page printed «В БАТАРЕЮ» over «−25 кВт».
 */
class EnergyReadoutsTest {

    private fun snapshot(
        powerKw: Double? = null,
        values: Map<VehicleSignal, Double> = emptyMap(),
        buckets: List<ConsumptionSample> = emptyList(),
        trace: EngineTraceSnapshot = EngineTraceSnapshot.EMPTY,
    ): VehicleTelemetry {
        val all = LinkedHashMap<VehicleSignal, Double>(values)
        if (powerKw != null) all[VehicleSignal.POWER_KW] = powerKw
        return VehicleTelemetry(
            access = VehicleAccess.READY,
            values = all,
            consumption = buckets,
            chart = ConsumptionChart.of(buckets),
            engineTrace = trace,
        )
    }

    /** [n] hundred-metre buckets spending [kwh] each, ending at 110 km. */
    private fun road(n: Int, kwh: Double = 0.02) =
        List(n) { ConsumptionSample(110.0 - (n - 1 - it) * 0.1, kwh, 0.1, 0.1) }

    private fun trace(seconds: Int, generationKw: Float): EngineTraceSnapshot {
        val engine = EngineTrace()
        repeat(seconds) { engine.sample(it * 1_000L, engineRunning = true, generationKw = generationKw.toDouble()) }
        return engine.snapshot()
    }

    /**
     * The list of snapshots both screens are held to, one per case the contract names.
     *
     * @return the case's name and its snapshot, so a failure says which case failed.
     */
    private fun cases(): List<Pair<String, VehicleTelemetry>> {
        val electric = road(100)
        val filling = road(37)
        val hole = road(100).toMutableList().also { list ->
            for (index in 40 until 60) list[index] = list[index].copy(kwh = 0.0, knownKm = 0.0)
        }
        val past40 = road(100).toMutableList().also { list ->
            for (index in 90 until 95) list[index] = list[index].copy(kwh = 0.3)
        }
        return listOf(
            "electric drive" to snapshot(powerKw = 34.0, buckets = electric),
            "return" to snapshot(powerKw = -42.0, buckets = electric),
            "engine giving" to snapshot(
                powerKw = -8.0,
                values = mapOf(
                    VehicleSignal.ENGINE_RUNNING to 3.0,
                    VehicleSignal.ENGINE_RPM to 1650.0,
                    VehicleSignal.GENERATION_KW to 8.0,
                ),
                buckets = electric,
                trace = trace(60, 8f),
            ),
            "engine running and giving nothing" to snapshot(
                powerKw = 34.0,
                values = mapOf(
                    VehicleSignal.ENGINE_RUNNING to 3.0,
                    VehicleSignal.ENGINE_RPM to 2150.0,
                    VehicleSignal.GENERATION_KW to 0.0,
                ),
                buckets = electric,
                trace = trace(60, 0f),
            ),
            "engine just stopped" to snapshot(
                powerKw = 34.0,
                values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0),
                buckets = electric,
                trace = trace(60, 14f),
            ),
            "standing on P" to snapshot(
                powerKw = 1.4,
                values = mapOf(VehicleSignal.GEARBOX_PARK to 1.0),
                buckets = electric,
            ),
            "charging" to snapshot(
                powerKw = -2.4,
                values = mapOf(
                    VehicleSignal.GEARBOX_PARK to 1.0,
                    VehicleSignal.CHARGE_GUN to 2.0,
                    VehicleSignal.CHARGE_KW to 2.4,
                    VehicleSignal.CHARGE_MINUTES to 35.0,
                ),
                buckets = electric,
            ),
            "window filling" to snapshot(powerKw = 22.0, buckets = filling),
            "a hole" to snapshot(powerKw = 22.0, buckets = hole),
            "a bin past 40" to snapshot(powerKw = 128.0, buckets = past40),
            "link lost" to VehicleTelemetry(access = VehicleAccess.UNAVAILABLE, message = "нет"),
            "a dropped read" to snapshot(powerKw = null, buckets = electric),
        )
    }

    @Test
    fun bothScreensSayTheSameThingAboutEverySnapshot() {
        val cluster = EnergyReadouts()
        val strip = EnergyReadouts()
        cases().forEach { (name, telemetry) ->
            val parked = telemetry.parked == true
            cluster.read(telemetry, parked, narrow = false, shortLegend = false)
            strip.read(telemetry, parked, narrow = false)

            assertEquals("$name: direction", cluster.flow, strip.flow)
            assertEquals("$name: word", cluster.word, strip.word)
            assertEquals("$name: mark", cluster.mark, strip.mark)
            assertEquals("$name: the power figure", cluster.powerFigure, strip.powerFigure)
            assertEquals("$name: the consumption", cluster.consumptionFigure, strip.consumptionFigure)
            assertEquals("$name: its sign", cluster.consumptionNegative, strip.consumptionNegative)
            assertEquals("$name: the engine's figure", cluster.engineFigure, strip.engineFigure)
            // The window is one distance printed in two cases: «за 3,7 км» and «ЗА 3,7 КМ».
            assertEquals("$name: the window's distance", distance(cluster.window), distance(strip.windowCaps))
            assertArrayEquals("$name: the bins", cluster.chart.values, strip.chart.values)
            assertArrayEquals("$name: their widths", cluster.chart.widths, strip.chart.widths)
        }
    }

    @Test
    fun theWordIsTheContractsTableAndTheMarkIsOnTheTwoThatNameASource() {
        val readouts = EnergyReadouts()
        fun word(telemetry: VehicleTelemetry): String {
            readouts.read(telemetry, parked = false)
            return readouts.word
        }
        assertEquals(EnergyReadouts.WORD_FROM_PACK, word(snapshot(powerKw = 34.0)))
        assertEquals(EnergyReadouts.WORD_TO_PACK, word(snapshot(powerKw = -42.0)))
        assertEquals(EnergyReadouts.WORD_NEUTRAL, word(snapshot(powerKw = 1.4)))
        // Unavailable is not zero, and it is not a claim either: the noun stands with no figure.
        assertEquals(EnergyReadouts.WORD_NEUTRAL, word(snapshot(powerKw = null)))
        assertNull(readouts.powerFigure)

        assertEquals(
            EnergyReadouts.WORD_FROM_ENGINE,
            word(
                snapshot(
                    powerKw = -8.0,
                    values = mapOf(
                        VehicleSignal.ENGINE_RUNNING to 3.0,
                        VehicleSignal.GENERATION_KW to 8.0,
                    ),
                ),
            ),
        )
        assertTrue("the engine's sentence names a source", readouts.mark)
        assertEquals(
            EnergyReadouts.WORD_FROM_CHARGER,
            word(
                snapshot(
                    powerKw = -2.4,
                    values = mapOf(
                        VehicleSignal.CHARGE_GUN to 2.0,
                        VehicleSignal.CHARGE_KW to 2.4,
                    ),
                ),
            ),
        )
        assertTrue(readouts.mark)
        word(snapshot(powerKw = -42.0))
        assertFalse("a plain return names no source", readouts.mark)
    }

    @Test
    fun theMinusIsNeverPrintedForPower() {
        val readouts = EnergyReadouts()
        readouts.read(snapshot(powerKw = -25.0), parked = false)
        assertEquals("25", readouts.powerFigure)
        assertEquals(ContourFlow.BACK, readouts.flow)
        readouts.read(snapshot(powerKw = 25.0), parked = false)
        assertEquals("25", readouts.powerFigure)
        assertEquals(ContourFlow.OUT, readouts.flow)
    }

    @Test
    fun theConsumptionIsWholeOnTheMoveAndATenthOnPark() {
        val readouts = EnergyReadouts()
        val buckets = road(100, kwh = 0.0198)
        readouts.read(snapshot(powerKw = 34.0, buckets = buckets), parked = false)
        assertEquals("20", readouts.consumptionFigure)
        readouts.read(snapshot(powerKw = 1.4, buckets = buckets), parked = true)
        assertEquals("19,8", readouts.consumptionFigure)
    }

    @Test
    fun aWindowThatOnlyGaveBackIsTheOneSignedFigure() {
        val readouts = EnergyReadouts()
        readouts.read(snapshot(powerKw = -42.0, buckets = road(100, kwh = -0.015)), parked = false)
        assertEquals("-15", readouts.consumptionFigure)
        assertTrue(readouts.consumptionNegative)
    }

    @Test
    fun theWindowNamesTheKnownRoadInBothCasesAndNeverRoundsAFillingOne() {
        val readouts = EnergyReadouts()
        readouts.read(snapshot(powerKw = 22.0, buckets = road(37)), parked = false)
        assertEquals(ContourReadout.UNIT_PER_100KM_PREFIX + "3,7 км", readouts.window)
        assertEquals("ЗА 3,7 КМ", readouts.windowCaps)

        readouts.read(snapshot(powerKw = 22.0, buckets = road(100)), parked = false)
        assertEquals(ContourReadout.UNIT_PER_100KM, readouts.window)
        assertEquals("ЗА 10 КМ", readouts.windowCaps)

        // A pane drops the word and nothing else.
        readouts.read(snapshot(powerKw = 22.0, buckets = road(100)), parked = false, narrow = true)
        assertEquals("10 КМ", readouts.windowCaps)
        readouts.read(snapshot(powerKw = 22.0, buckets = road(37)), parked = false, narrow = true)
        assertEquals("3,7 КМ", readouts.windowCaps)
    }

    @Test
    fun theEngineSentenceSaysWhatItGivesAndHowFarBackTheBoxReaches() {
        val readouts = EnergyReadouts()
        readouts.read(
            snapshot(
                powerKw = -8.0,
                values = mapOf(
                    VehicleSignal.ENGINE_RUNNING to 3.0,
                    VehicleSignal.GENERATION_KW to 14.0,
                ),
                trace = trace(82, 14f),
            ),
            parked = false,
        )
        assertEquals("ДВС ДАЁТ", readouts.enginePrefix)
        assertEquals("14", readouts.engineFigure)
        assertEquals("· ПОСЛЕДНИЕ 1:22", readouts.engineWindow)

        readouts.read(
            snapshot(
                powerKw = -8.0,
                values = mapOf(
                    VehicleSignal.ENGINE_RUNNING to 3.0,
                    VehicleSignal.GENERATION_KW to 14.0,
                ),
                trace = trace(82, 14f),
            ),
            parked = false,
            shortLegend = true,
        )
        assertEquals("· 1:22", readouts.engineWindow)

        // Nothing about the engine while it is not turning.
        readouts.read(snapshot(powerKw = 34.0, trace = trace(60, 14f)), parked = false)
        assertNull(readouts.engineFigure)
    }

    @Test
    fun theNeutralZoneKeepsItsColourUntilTheReadingLeavesTheBand() {
        // Three kilowatts of hysteresis around a three-kilowatt zone, so a coast cannot flicker.
        // It is per screen, which is why the readouts are an object rather than a function.
        val readouts = EnergyReadouts()
        readouts.read(snapshot(powerKw = 9.0), parked = false)
        assertEquals(ContourFlow.OUT, readouts.flow)
        readouts.read(snapshot(powerKw = 2.0), parked = false)
        assertEquals("still ink inside the band", ContourFlow.OUT, readouts.flow)
        readouts.read(snapshot(powerKw = 1.0), parked = false)
        assertEquals(ContourFlow.NEUTRAL, readouts.flow)
    }

    @Test
    fun aChartIsWhatTheSnapshotCarriesRatherThanSomethingBuiltHere() {
        val buckets = road(100)
        val telemetry = snapshot(powerKw = 34.0, buckets = buckets)
        val readouts = EnergyReadouts()
        readouts.read(telemetry, parked = false)
        assertNotNull(readouts.chart)
        assertEquals(ConsumptionChart.BINS, readouts.chart.values.size)
        assertArrayEquals("the hub's own array", telemetry.chart.values, readouts.chart.values)
    }

    /** The distance a window names, whichever case it is printed in. */
    private fun distance(window: String): String =
        Regex("""\d+(,\d+)?""").findAll(window).lastOrNull()?.value ?: window

    private fun assertArrayEquals(what: String, expected: FloatArray, actual: FloatArray) {
        assertEquals("$what: length", expected.size, actual.size)
        for (index in expected.indices) {
            val a = expected[index]
            val b = actual[index]
            if (a.isNaN() && b.isNaN()) continue
            assertEquals("$what at $index", a, b, 1e-6f)
        }
    }
}
