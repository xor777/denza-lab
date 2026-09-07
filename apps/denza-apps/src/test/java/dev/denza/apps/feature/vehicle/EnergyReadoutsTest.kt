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
 *
 * **And the comparison is of what the two renderers actually consume.** It used to compare an
 * `EnergyReadouts` with an `EnergyReadouts`, which agree by construction over any input: what the
 * cluster and the car page read out of this object - the power figure with its charging
 * substitution, the engine's cell, the chart, the window, the consumption - is what is listed here,
 * so a field one screen stopped reading is a field this test stops covering, visibly.
 */
class EnergyReadoutsTest {

    private fun snapshot(
        powerKw: Double? = null,
        values: Map<VehicleSignal, Double> = emptyMap(),
        buckets: List<ConsumptionSample> = emptyList(),
        trace: EngineTraceSnapshot = EngineTraceSnapshot.EMPTY,
        trip: TripEnergy = TripEnergy(),
    ): VehicleTelemetry {
        val all = LinkedHashMap<VehicleSignal, Double>(values)
        if (powerKw != null) all[VehicleSignal.POWER_KW] = powerKw
        return VehicleTelemetry(
            access = VehicleAccess.READY,
            values = all,
            consumption = buckets,
            chart = ConsumptionChart.of(buckets),
            engineTrace = trace,
            trip = trip,
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
                trip = TripEnergy(engineSeconds = 400.0),
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
                trip = TripEnergy(engineSeconds = 400.0),
            ),
            "engine just stopped" to snapshot(
                powerKw = 34.0,
                values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0),
                buckets = electric,
                trace = trace(60, 14f),
                trip = TripEnergy(engineSeconds = 400.0),
            ),
            "engine ran yesterday" to snapshot(
                powerKw = 34.0,
                values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0),
                buckets = electric,
                trip = TripEnergy(engineSeconds = 400.0),
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

            // What the cluster's renderer reads: the band's colour and the hero's magnitude, the
            // engine's corner, the petal's figure, its unit and its twenty bins.
            assertEquals("$name: direction", cluster.flow, strip.flow)
            assertEquals("$name: word", cluster.word, strip.word)
            assertEquals("$name: mark", cluster.mark, strip.mark)
            assertEquals("$name: the power figure", cluster.powerFigure, strip.powerFigure)
            assertEquals("$name: the consumption", cluster.consumptionFigure, strip.consumptionFigure)
            assertEquals("$name: its sign", cluster.consumptionNegative, strip.consumptionNegative)
            assertEquals("$name: the engine's figure", cluster.engineFigure, strip.engineFigure)
            // And what the car page's reads, which is the same list in its own case.
            assertEquals("$name: the engine's cell", cluster.engineCell, strip.engineCell)
            assertEquals("$name: its reading", cluster.engineCellFigure, strip.engineCellFigure)
            assertEquals(
                "$name: and its heading, which is one word in two cases",
                cluster.engineCellTitle.uppercase(),
                strip.engineCellTitleCaps,
            )
            assertEquals("$name: the volts", cluster.voltsFigure, strip.voltsFigure)
            // The window is one distance printed in three lines: «за 3,7 км», «ЗА 3,7 КМ», and the
            // car page's whole foot unit.
            assertEquals("$name: the window's distance", distance(cluster.window), distance(strip.windowCaps))
            assertEquals("$name: and the foot line's", distance(cluster.window), distance(strip.windowFoot))
            assertEquals("$name: whether there is a chart at all", cluster.chart.isEmpty, strip.chart.isEmpty)
            assertArrayEquals("$name: the bins", cluster.chart.values, strip.chart.values)
            assertArrayEquals("$name: their widths", cluster.chart.widths, strip.chart.widths)
        }
    }

    /**
     * Every case is *some* case: a test that agreed about nothing would pass the one above.
     *
     * So the list has to reach every figure at least once, which is what stops a snapshot table
     * quietly decaying into thirteen ways of saying "no data".
     */
    @Test
    fun theSnapshotsBetweenThemReachEveryFigureBothScreensDraw() {
        val readouts = EnergyReadouts()
        val seen = mutableSetOf<String>()
        cases().forEach { (_, telemetry) ->
            readouts.read(telemetry, telemetry.parked == true)
            if (readouts.powerFigure != null) seen += "power"
            if (readouts.consumptionFigure != null) seen += "consumption"
            if (readouts.engineFigure != null) seen += "engine"
            if (readouts.engineCell == EnergyReadouts.EngineCell.RPM) seen += "rpm"
            if (readouts.engineCell == EnergyReadouts.EngineCell.MINUTES) seen += "minutes"
            if (readouts.engineCell == EnergyReadouts.EngineCell.NONE) seen += "no cell"
            if (!readouts.chart.isEmpty) seen += "chart"
            if (readouts.mark) seen += "mark"
            if (readouts.flow == ContourFlow.BACK) seen += "back"
            if (readouts.flow == ContourFlow.OUT) seen += "out"
        }
        assertEquals(
            setOf("power", "consumption", "engine", "rpm", "minutes", "no cell", "chart", "mark", "back", "out"),
            seen,
        )
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

    /**
     * While the charger has agreed, `P` is the charger's own kilowatts on both screens.
     *
     * `docs/energy-display-contract.md` §2.1. The pack's id reads zero or a small load on a car
     * standing on a charger, so the cluster substituted `−|CHARGE_KW|` for the band and the car
     * page printed the raw id: 7 kW on one screen and 0 on the other, for one event.
     */
    @Test
    fun aChargeIsTheChargersOwnKilowattsOnBothScreens() {
        val readouts = EnergyReadouts()
        val charging = snapshot(
            powerKw = 0.0,
            values = mapOf(
                VehicleSignal.CHARGE_GUN to 2.0,
                VehicleSignal.CHARGE_KW to 7.0,
                VehicleSignal.CHARGE_MINUTES to 35.0,
            ),
        )
        assertEquals(-7.0, EnergyReadouts.packKilowatts(charging)!!, 1e-9)
        readouts.read(charging, parked = true)
        assertEquals("7", readouts.powerFigure)
        assertEquals(EnergyReadouts.WORD_FROM_CHARGER, readouts.word)
    }

    /**
     * And a sentence that names a source is blue, whatever the magnitude behind it is.
     *
     * A wall charge is two kilowatts, which is inside the neutral zone, so «В БАТАРЕЮ ОТ ЗАРЯДКИ»
     * was printed in grey: the word said one thing and the colour another, which is the exact
     * defect this class exists to make impossible. The neutral zone is about a direction nobody can
     * name, and these two sentences have named it.
     */
    @Test
    fun aSentenceThatNamesASourceIsBlueEvenInsideTheNeutralZone() {
        val readouts = EnergyReadouts()
        readouts.read(snapshot(powerKw = 34.0), parked = false)
        readouts.read(snapshot(powerKw = 0.5), parked = false)
        assertEquals("a neutral history", ContourFlow.NEUTRAL, readouts.flow)
        readouts.read(
            snapshot(
                powerKw = -2.4,
                values = mapOf(
                    VehicleSignal.GEARBOX_PARK to 1.0,
                    VehicleSignal.CHARGE_GUN to 2.0,
                    VehicleSignal.CHARGE_KW to 2.4,
                ),
            ),
            parked = true,
        )
        assertEquals(EnergyReadouts.WORD_FROM_CHARGER, readouts.word)
        assertEquals("and the colour agrees with it", ContourFlow.BACK, readouts.flow)
    }

    /**
     * A read that did not land does not get to move the hysteresis.
     *
     * A dropped sample used to reset the held colour to neutral, so the next good one - inside the
     * hysteresis band, where the whole point is that it keeps the colour it had - came back grey.
     * The band flickered on a signal the panel had never lost.
     */
    @Test
    fun aDroppedReadLeavesTheColourItFoundWhereItWas() {
        val readouts = EnergyReadouts()
        readouts.read(snapshot(powerKw = -8.0), parked = false)
        assertEquals(ContourFlow.BACK, readouts.flow)
        readouts.read(snapshot(powerKw = null), parked = false)
        assertEquals("nothing to colour", ContourFlow.NEUTRAL, readouts.flow)
        readouts.read(snapshot(powerKw = -3.5), parked = false)
        assertEquals("still coming back", ContourFlow.BACK, readouts.flow)
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

    /** And a figure that rounded its magnitude away is not the exception the blue is for. */
    @Test
    fun aConsumptionThatPrintsAsZeroIsNotPrintedAsNegative() {
        val readouts = EnergyReadouts()
        readouts.read(snapshot(powerKw = -1.0, buckets = road(100, kwh = -0.0004)), parked = false)
        assertEquals("0", readouts.consumptionFigure)
        assertFalse("the minus is not there, so neither is the blue", readouts.consumptionNegative)
        readouts.read(snapshot(powerKw = -1.0, buckets = road(100, kwh = -0.00004)), parked = true)
        assertEquals("0,0", readouts.consumptionFigure)
        assertFalse(readouts.consumptionNegative)
    }

    @Test
    fun theWindowNamesTheKnownRoadInBothCasesAndNeverRoundsAFillingOne() {
        val readouts = EnergyReadouts()
        readouts.read(snapshot(powerKw = 22.0, buckets = road(37)), parked = false)
        assertEquals(ContourReadout.UNIT_PER_100KM_PREFIX + "3,7 км", readouts.window)
        assertEquals("ЗА 3,7 КМ", readouts.windowCaps)
        assertEquals("кВт·ч/100 км · ЗА 3,7 КМ", readouts.windowFoot)

        readouts.read(snapshot(powerKw = 22.0, buckets = road(100)), parked = false)
        assertEquals(ContourReadout.UNIT_PER_100KM, readouts.window)
        assertEquals("ЗА 10 КМ", readouts.windowCaps)

        // A pane drops the word and nothing else.
        readouts.read(snapshot(powerKw = 22.0, buckets = road(100)), parked = false, narrow = true)
        assertEquals("10 КМ", readouts.windowCaps)
        assertEquals("кВт·ч/100 км · 10 КМ", readouts.windowFoot)
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

        // Nothing about the engine while it is not turning - not even a generation reading that
        // is still on the wire. The flag is what says the engine is there to give anything.
        readouts.read(
            snapshot(
                powerKw = 34.0,
                values = mapOf(
                    VehicleSignal.ENGINE_RUNNING to 0.0,
                    VehicleSignal.GENERATION_KW to 14.0,
                ),
                trace = trace(60, 14f),
            ),
            parked = false,
        )
        assertNull(readouts.engineFigure)
        readouts.read(snapshot(powerKw = 34.0, trace = trace(60, 14f)), parked = false)
        assertNull(readouts.engineFigure)
    }

    /**
     * And «ДВС ДАЁТ 0 кВт» is the zero this panel does not draw.
     *
     * It is not a corner case either: the two drives so far both recorded the engine running with
     * `GENERATION_KW` flat, so a figure printed at zero is the *ordinary* picture of an engine that
     * is turning and giving the pack nothing.
     */
    @Test
    fun theEngineFigureIsAbsentWhileTheEngineGivesNothing() {
        val readouts = EnergyReadouts()
        fun figure(generationKw: Double): String? {
            readouts.read(
                snapshot(
                    powerKw = 34.0,
                    values = mapOf(
                        VehicleSignal.ENGINE_RUNNING to 3.0,
                        VehicleSignal.GENERATION_STATE to 1.0,
                        VehicleSignal.GENERATION_KW to generationKw,
                    ),
                    trace = trace(60, generationKw.toFloat()),
                ),
                parked = false,
            )
            return readouts.engineFigure
        }
        assertNull("flat", figure(0.0))
        assertNull("rounding, not the engine working", figure(VehicleTelemetry.GENERATION_FLOOR_KW))
        assertEquals("14", figure(14.0))
    }

    /**
     * The engine's own cell, decided once - the cluster's corner and the car page's cell.
     *
     * The cluster read it off the trip and the car page off the trace, so the owner's own case -
     * getting into the car with yesterday's drive still on the ledger - had a corner on one screen
     * and nothing on the other. «Just stopped» is the trace: a hundred and twenty seconds with no
     * timer of its own.
     */
    @Test
    fun theEngineCellIsRevolutionsThenMinutesThenNothingAtAll() {
        val readouts = EnergyReadouts()
        fun cell(
            rpm: Double? = null,
            running: Double? = null,
            trip: TripEnergy = TripEnergy(),
            warm: Boolean = false,
        ): Pair<EnergyReadouts.EngineCell, String?> {
            val values = LinkedHashMap<VehicleSignal, Double>()
            rpm?.let { values[VehicleSignal.ENGINE_RPM] = it }
            running?.let { values[VehicleSignal.ENGINE_RUNNING] = it }
            readouts.read(
                snapshot(
                    values = values,
                    trace = if (warm) trace(2, 8f) else EngineTraceSnapshot.EMPTY,
                    trip = trip,
                ),
                parked = false,
            )
            return readouts.engineCell to readouts.engineCellFigure
        }

        assertEquals(
            EnergyReadouts.EngineCell.RPM to "1321",
            cell(rpm = 1321.0, running = 1.0),
        )
        assertEquals(ContourReadout.TITLE_ENGINE_RPM, readouts.engineCellTitle)
        assertEquals("ДВС · ОБ/МИН", readouts.engineCellTitleCaps)

        assertEquals(
            EnergyReadouts.EngineCell.MINUTES to "14",
            cell(running = 0.0, trip = TripEnergy(engineSeconds = 14 * 60.0), warm = true),
        )
        assertEquals(ContourReadout.TITLE_ENGINE_MINUTES, readouts.engineCellTitle)
        assertEquals("ДВС · МИН ЗА ПОЕЗДКУ", readouts.engineCellTitleCaps)

        // The owner's own case: he got into the car, had not started the engine, and the cell said
        // «3 мин за поездку» - true by the ledger, and read as *this* drive.
        assertEquals(
            "engine ran yesterday, cold now",
            EnergyReadouts.EngineCell.NONE to null,
            cell(running = 0.0, trip = TripEnergy(engineSeconds = 3 * 60.0)),
        )
        assertEquals(
            "ran for twenty seconds, which is a zero with a unit on it",
            EnergyReadouts.EngineCell.NONE to null,
            cell(running = 0.0, trip = TripEnergy(engineSeconds = 20.0), warm = true),
        )
        assertEquals("never started", EnergyReadouts.EngineCell.NONE to null, cell(running = 0.0))
        assertEquals(
            "running, but the revolutions did not answer",
            EnergyReadouts.EngineCell.NONE to null,
            cell(running = 1.0),
        )
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
