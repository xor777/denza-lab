package dev.denza.apps.feature.vehicle

import dev.denza.apps.core.EnglishWords
import dev.denza.apps.core.figure
import dev.denza.apps.core.say
import dev.denza.apps.feature.cluster.dashboard.ContourFlow
import dev.denza.apps.feature.cluster.dashboard.ContourFrame
import dev.denza.apps.feature.cluster.dashboard.ContourPanel
import dev.denza.apps.R
import dev.denza.apps.core.AppText
import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import dev.denza.apps.feature.cluster.dashboard.ContourScene
import dev.denza.apps.feature.cluster.dashboard.UNIT_PER_100KM
import dev.denza.apps.feature.cluster.dashboard.UNIT_PER_100KM_PREFIX
import dev.denza.apps.feature.trip.StripModel
import dev.denza.apps.feature.trip.StripReadings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * One quantity, one definition, one set of words, on both screens.
 *
 * `docs/energy-display-contract.md` §7: the same list of snapshots is run through the cluster as it
 * draws - scene, followers and frame builder ([ContourPanel]) - and through the car page's own
 * model ([StripReadings]), and what the two print has to agree: the power, the volts, the engine's
 * cell, the trip, the consumption and its window, the hundred points. They were not one function
 * before, and the car page printed «В БАТАРЕЮ» over «−25 кВт».
 *
 * **Until 2026-10-08 this compared an `EnergyReadouts` with an `EnergyReadouts`**, two instances
 * of one deterministic class on the same input, which agree by construction; the comment above it
 * already said it compared what the renderers consume. Of its seventeen assertions only the two that
 * read the car page's model could fail, and the cluster's frame builder was not in it at all.
 */
open class EnergyReadoutsTest {

    private fun snapshot(
        powerKw: Double? = null,
        values: Map<VehicleSignal, Double> = emptyMap(),
        buckets: List<ConsumptionSample> = emptyList(),
        trace: EngineChargeSnapshot = EngineChargeSnapshot.EMPTY,
        trip: TripEnergy = TripEnergy(),
        volts: Double? = 549.0,
    ): VehicleTelemetry {
        val all = LinkedHashMap<VehicleSignal, Double>(values)
        if (powerKw != null) all[VehicleSignal.POWER_KW] = powerKw
        if (volts != null) all[VehicleSignal.PACK_VOLT] = volts
        return VehicleTelemetry(
            access = VehicleAccess.READY,
            values = all,
            consumption = buckets,
            chart = ConsumptionChart.of(buckets),
            engineCharge = trace,
            trip = trip,
        )
    }

    /** [n] hundred-metre buckets spending [kwh] each, ending at 110 km. */
    private fun road(n: Int, kwh: Double = 0.02) =
        List(n) { ConsumptionSample(110.0 - (n - 1 - it) * 0.1, kwh, 0.1, 0.1) }

    /** A run of [seconds] with the pack taking [chargeKw], a sweep a second, stopped [stoppedFor] seconds ago. */
    private fun trace(seconds: Int, chargeKw: Float, stoppedFor: Int = 0): EngineChargeSnapshot {
        val engine = EngineCharge()
        repeat(seconds + stoppedFor) {
            engine.sample(it * 1_000L, engineRunning = it < seconds, loadKw = -chargeKw.toDouble(), dtSeconds = 1.0)
        }
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
        // A kilometre the link was down: eighty readings with a seam in the middle of them, which
        // is eighty points and «за 8,0 км» rather than a gap in the line (contract §2.3).
        val seam = road(100).toMutableList().also { list ->
            for (index in 40 until 60) list[index] = list[index].copy(kwh = 0.0, knownKm = 0.0)
        }
        val pastCeiling = road(100).toMutableList().also { list ->
            for (index in 88 until 98) list[index] = list[index].copy(kwh = 0.3)
        }
        // A trip both screens can print: forty-two kilometres, 9,3 kWh out of the pack.
        val trip = TripEnergy(opened = true, netKwh = 9.3, kilometres = 42.0)
        val engineTrip = trip.copy(engineSeconds = 400.0)
        return listOf(
            "electric drive" to snapshot(powerKw = 34.0, buckets = electric, trip = trip),
            "return" to snapshot(powerKw = -42.0, buckets = electric),
            "engine charging" to snapshot(
                powerKw = -8.0,
                values = mapOf(
                    VehicleSignal.ENGINE_RUNNING to 3.0,
                    VehicleSignal.ENGINE_RPM to 1650.0,
                ),
                buckets = electric,
                trace = trace(60, 8f),
                trip = engineTrip,
            ),
            // Inside the neutral zone: the car page names the engine, the cluster's hero stays white.
            "engine charging lightly" to snapshot(
                powerKw = -4.0,
                values = mapOf(
                    VehicleSignal.ENGINE_RUNNING to 3.0,
                    VehicleSignal.ENGINE_RPM to 1650.0,
                ),
                buckets = electric,
                trace = trace(60, 4f),
                trip = engineTrip,
            ),
            "engine running and charging nothing" to snapshot(
                powerKw = 34.0,
                values = mapOf(
                    VehicleSignal.ENGINE_RUNNING to 3.0,
                    VehicleSignal.ENGINE_RPM to 2150.0,
                ),
                buckets = electric,
                trace = trace(60, 0f),
                trip = engineTrip,
            ),
            "engine just stopped" to snapshot(
                powerKw = 34.0,
                values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0),
                buckets = electric,
                trace = trace(60, 14f, stoppedFor = 1),
                trip = engineTrip,
            ),
            "engine stopped past the hold" to snapshot(
                powerKw = 34.0,
                values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0),
                buckets = electric,
                trace = trace(60, 14f, stoppedFor = 30),
                trip = engineTrip,
            ),
            "engine ran yesterday" to snapshot(
                powerKw = 34.0,
                values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0),
                buckets = electric,
                trip = engineTrip,
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
            // The pack's own id reading nothing while a charger gives 7 kW: one event, which was
            // 7 kW on one screen and 0 on the other before the substitution (§2.1).
            "a charge the pack's own id reads as nothing" to snapshot(
                powerKw = 0.0,
                values = mapOf(
                    VehicleSignal.GEARBOX_PARK to 1.0,
                    VehicleSignal.CHARGE_GUN to 2.0,
                    VehicleSignal.CHARGE_KW to 7.0,
                    VehicleSignal.CHARGE_MINUTES to 35.0,
                ),
                buckets = electric,
            ),
            "window filling" to snapshot(powerKw = 22.0, buckets = filling),
            "a road that gave back more" to snapshot(powerKw = -20.0, buckets = road(100, kwh = -0.005)),
            "a seam in the record" to snapshot(powerKw = 22.0, buckets = seam),
            "a kilometre past the ceiling" to snapshot(powerKw = 128.0, buckets = pastCeiling),
            "link lost" to VehicleTelemetry(access = VehicleAccess.UNAVAILABLE, message = "нет"),
            "a dropped read" to snapshot(powerKw = null, buckets = electric),
        )
    }

    /** One snapshot on both screens: the cluster's frame, [SETTLED] seconds into it, and the car page's model. */
    private fun screens(telemetry: VehicleTelemetry): Pair<ContourFrame, StripModel> =
        ContourPanel().run(telemetry, SETTLED) to StripModel().also { StripReadings().car(it, telemetry, NOW) }

    @Test
    fun bothScreensPrintTheSameThingAboutEverySnapshot() {
        cases().forEach { (name, telemetry) ->
            val (frame, model) = screens(telemetry)
            assertEquals("$name: closed", frame.unavailable, model.closed)
            if (model.closed) {
                assertEquals("$name: and why", frame.message, model.message)
                return@forEach
            }

            // The pack: the hero's settled magnitude and the strip's figure, which way it flows, the volts.
            assertEquals("$name: the power", frame.heroFigure, model.power.figure)
            // Blue is the band's on both screens - except where the car page's sentence names a
            // source, which is blue whatever its magnitude (§2.1). The cluster prints no sentence, so
            // a 2,4 kW charge is a neutral, white hero there under «● В батарею от зарядки» here.
            if (!model.power.dot) assertEquals("$name: into the pack", frame.into, model.power.blue)
            assertEquals("$name: the volts", frame.volts, model.volts.figure)

            // The unit beside the volts is the car page's.
            if (frame.volts != null) assertEquals("$name: the volts' unit", frame.voltsUnit, model.volts.unit)

            // The engine's cell: one name in two cases - «ДВС» on the cluster and on the car page,
            // ENGINE on the one and Engine on the other - and one reading with one unit beside it.
            assertEquals("$name: whether the engine has a cell", frame.iceCaption != null, model.engine.present)
            frame.iceCaption?.let { heading ->
                assertEquals("$name: the engine's name", EnergyReadouts.sentence(heading), model.engine.caption)
                assertEquals("$name: its reading", frame.iceFigure, model.engine.figure)
                if (frame.iceFigure != null) assertEquals("$name: its unit", frame.iceUnit, model.engine.unit)
            }

            // The trip, wherever the cluster has not given its seat to the engine's block: one
            // figure, and one road - the cluster's «42 км» under the figure, the car page's
            // «42 км · за поездку» over it.
            if (!frame.engineBlock) {
                assertEquals("$name: whether there is a trip", frame.tripCaption != null, model.tripCell.present)
                assertEquals("$name: the trip", frame.tripKwh, model.tripCell.figure)
                if (model.tripCell.present) {
                    val road = frame.tripKm?.let { AppText.s(R.string.energy_trip_km, it) } ?: EnergyReadouts.TRIP_ALONE_SENTENCE
                    assertEquals("$name: its road", road, model.tripCell.caption)
                    assertEquals("$name: its name", EnergyReadouts.sentence(frame.tripCaption!!), EnergyReadouts.TRIP_ALONE_SENTENCE)
                }
            }

            // The figure over the chart and the road it is over, in the seat the charge countdown
            // takes on the cluster while a charger has agreed.
            if (frame.consumptionUnit != ContourReadout.UNIT_CHARGE_LEFT) {
                assertEquals("$name: the consumption", frame.consumption, model.spendFigure)
                assertEquals("$name: its window", frame.consumptionUnit, model.spendWindow)
                // Grey while the engine runs, on the cluster alone (§2.5); otherwise blue is the minus.
                if (frame.consumptionTone != ContourFrame.Tone.GREY) {
                    assertEquals("$name: its minus", frame.consumptionTone == ContourFrame.Tone.BLUE, model.spendNegative)
                }
            }

            // And the hundred points under it.
            assertEquals("$name: how many points", model.chartCount, frame.chartCount)
            assertArrayEquals("$name: the points", model.chart, frame.chart.copyOf(frame.chartCount))
        }
    }

    /**
     * Every case is *some* case: a test that agreed about nothing would pass the one above.
     *
     * So the list has to reach every reading the two screens are compared on at least once, which is
     * what stops a snapshot table quietly decaying into fourteen ways of saying "no data".
     */
    @Test
    fun theSnapshotsBetweenThemReachEveryReadingBothScreensPrint() {
        val seen = mutableSetOf<String>()
        cases().forEach { (_, telemetry) ->
            val (frame, model) = screens(telemetry)
            if (model.closed) {
                seen += "closed"
                return@forEach
            }
            if (model.power.figure != null) seen += "power"
            if (model.power.blue && !model.power.dot) seen += "into the pack"
            if (model.volts.figure != null) seen += "volts"
            when (model.engine.unit) {
                EnergyReadouts.ENGINE_RPM_UNIT -> seen += "rpm"
                EnergyReadouts.ENGINE_MINUTES_UNIT -> seen += "minutes"
            }
            if (!model.engine.present) seen += "no cell"
            if (!frame.engineBlock && model.tripCell.present) seen += "trip"
            if (frame.consumptionUnit != ContourReadout.UNIT_CHARGE_LEFT && model.spendFigure != null) {
                seen += "consumption"
                if (distance(model.spendWindow) != "10") seen += "a filling window"
                if (model.spendNegative && frame.consumptionTone != ContourFrame.Tone.GREY) seen += "a minus"
            }
            if (model.chartCount > 0) seen += "chart"
        }
        assertEquals(
            setOf(
                "closed", "power", "into the pack", "volts", "rpm", "minutes", "no cell", "trip",
                "consumption", "a filling window", "a minus", "chart",
            ),
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
                    values = mapOf(VehicleSignal.ENGINE_RUNNING to 3.0),
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

    /**
     * The car page's case of every word above, written out: the Luminofor board prints these and
     * nothing else, and a derivation that went wrong would be agreed with by the test above.
     */
    @Test
    fun theCarPageSaysTheSameWordsInSentenceCase() {
        assertEquals(say("Батарея", "Battery"), EnergyReadouts.WORD_NEUTRAL_SENTENCE)
        assertEquals(say("Из батареи", "From battery"), EnergyReadouts.WORD_FROM_PACK_SENTENCE)
        assertEquals(say("В батарею", "To battery"), EnergyReadouts.WORD_TO_PACK_SENTENCE)
        // «ДВС» is an abbreviation and stays one in every case.
        assertEquals(say("В батарею от ДВС", "To battery from engine"), EnergyReadouts.WORD_FROM_ENGINE_SENTENCE)
        assertEquals(say("В батарею от зарядки", "To battery from charger"), EnergyReadouts.WORD_FROM_CHARGER_SENTENCE)

        val readouts = EnergyReadouts()
        fun sentence(telemetry: VehicleTelemetry): Pair<String, String> {
            readouts.read(telemetry, parked = false)
            return readouts.word to readouts.wordSentence
        }
        assertEquals(say("ИЗ БАТАРЕИ", "FROM BATTERY") to say("Из батареи", "From battery"), sentence(snapshot(powerKw = 34.0)))
        assertEquals(say("В БАТАРЕЮ", "TO BATTERY") to say("В батарею", "To battery"), sentence(snapshot(powerKw = -42.0)))
        assertEquals(say("БАТАРЕЯ", "BATTERY") to say("Батарея", "Battery"), sentence(snapshot(powerKw = null)))
        assertEquals(
            say("В БАТАРЕЮ ОТ ДВС", "TO BATTERY FROM ENGINE") to say("В батарею от ДВС", "To battery from engine"),
            sentence(
                snapshot(
                    powerKw = -8.0,
                    values = mapOf(VehicleSignal.ENGINE_RUNNING to 3.0),
                ),
            ),
        )
        assertEquals(
            say("В БАТАРЕЮ ОТ ЗАРЯДКИ", "TO BATTERY FROM CHARGER") to say("В батарею от зарядки", "To battery from charger"),
            sentence(
                snapshot(
                    powerKw = -2.4,
                    values = mapOf(VehicleSignal.CHARGE_GUN to 2.0, VehicleSignal.CHARGE_KW to 2.4),
                ),
            ),
        )

        // The engine's cell: «ДВС» over «… об/мин» on both screens, the cluster in capitals.
        readouts.read(
            snapshot(values = mapOf(VehicleSignal.ENGINE_RUNNING to 3.0, VehicleSignal.ENGINE_RPM to 1650.0)),
            parked = false,
        )
        assertEquals(say("ДВС", "ENGINE"), readouts.engineCellTitle)
        assertEquals(say("ДВС", "Engine"), readouts.engineCellCaption)
        assertEquals(say("об/мин", "rpm"), readouts.engineCellUnit)
        assertEquals("1650", readouts.engineCellFigure)
        readouts.read(
            snapshot(
                values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0),
                trace = trace(2, 8f, stoppedFor = 20),
                trip = TripEnergy(engineSeconds = 6 * 60.0),
            ),
            parked = false,
        )
        assertEquals(say("ДВС ЗА ПОЕЗДКУ", "ENGINE THIS TRIP"), readouts.engineCellTitle)
        assertEquals(say("ДВС за поездку", "Engine this trip"), readouts.engineCellCaption)
        assertEquals(say("мин", "min"), readouts.engineCellUnit)
        readouts.read(snapshot(values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0)), parked = false)
        assertEquals("no cell, no words", "" to "", readouts.engineCellCaption to readouts.engineCellUnit)
    }

    /**
     * A car that answers its pack and not its odometer: the hub is READY and its ledger has opened
     * no trip. Until 2026-11-19 both screens printed «0,0 кВт·ч» for it - the cluster's «ЗА
     * ПОЕЗДКУ» seeded by every packet, the car page's cell by the access alone. And a trip the
     * ledger restored - from this build's record or 0.7.1's - is a trip on both.
     */
    @Test
    fun aLedgerThatOpenedNoTripPrintsNoneOnEitherScreen() {
        val ledger = TripEnergyLedger()
        repeat(40) { ledger.sample(odometerKm = null, powerKw = 30.0, engineRunning = false, parked = false, dtSeconds = 0.25) }
        val (frame, model) = screens(snapshot(powerKw = 30.0, buckets = road(100), trip = ledger.trip))
        assertNull("the cluster has no trip", frame.tripCaption)
        assertNull(frame.tripKwh)
        assertFalse("nor has the car page", model.tripCell.present)

        val restored = TripEnergyLedger()
        val record = TripRecord(TripEnergy(9.3, 3.1, 1.1, 360.0, 42.0, opened = true), 1000.0, armed = true)
        assertTrue(restored.restore(record, odometerKm = 1000.0))
        val (back, page) = screens(snapshot(powerKw = 30.0, buckets = road(100), trip = restored.trip))
        assertEquals(figure("9,3"), back.tripKwh)
        assertEquals(back.tripKwh, page.tripCell.figure)
    }

    /**
     * A snapshot the hub leaves standing - a reader blocked behind a helper's start - is aged by
     * one horizon on both screens. The cluster ages it on its own clock from when it arrived; the car
     * page reads each snapshot as it comes and kept printing «80 кВт» from it after the cluster's
     * figure had gone. Arrival and the sweep's stamp are one moment here, as they are on the car.
     */
    @Test
    fun aSnapshotLeftStandingLeavesBothScreensOnOneHorizon() {
        val stamp = 50_000L
        val standing = snapshot(
            powerKw = 80.0,
            values = mapOf(VehicleSignal.PACK_TEMP_AVG to 28.0),
            buckets = road(100),
            trip = TripEnergy(netKwh = 9.3, kilometres = 42.0, opened = true),
        ).copy(hotReadAtMillis = stamp)
        for ((seconds, live) in listOf(0.5f to true, 1.9f to true, 2.1f to false, 30f to false)) {
            val panel = ContourPanel()
            panel.run(standing, 0.01f)
            val frame = panel.run(standing, seconds, quiet = true)
            val model = StripModel().also { StripReadings().car(it, standing, stamp + (seconds * 1000f).toLong()) }
            assertEquals("$seconds s: the cluster's power", live, frame.heroFigure != null)
            assertEquals("$seconds s: the car page's power", live, model.power.figure != null)
            assertEquals("$seconds s: the volts", frame.volts, model.volts.figure)
            val caption = if (live) EnergyReadouts.WORD_FROM_PACK_SENTENCE else EnergyReadouts.WORD_NEUTRAL_SENTENCE
            assertEquals("$seconds s: the caption stays", caption, model.power.caption)
        }
        assertEquals(
            "one horizon, not two",
            (ContourScene.STALE_SECONDS * 1000f).toLong(),
            VehicleTelemetry.HOT_HORIZON_MS,
        )
    }

    /**
     * The trip's cell, which the car page gained from the cluster's first seat: the same integral,
     * a tenth, under the cluster's own phrase in sentence case - and nothing at all until the car
     * has answered.
     */
    @Test
    fun theTripCellIsTheClustersFirstSeatInTheCarPagesCase() {
        val readouts = EnergyReadouts()
        readouts.read(snapshot(trip = TripEnergy(opened = true, netKwh = 9.27, kilometres = 42.3)), parked = false)
        assertEquals(ContourReadout.tenth(9.27), readouts.tripFigure)
        assertEquals(figure("9,3"), readouts.tripFigure)
        assertEquals(say("42 км · за поездку", "42 km · this trip"), readouts.tripCaption)
        assertEquals(
            "the cluster's two pieces, one case down",
            ("42 " + ContourReadout.UNIT_KM + " · " + ContourReadout.CAPTION_TRIP).lowercase(),
            readouts.tripCaption.lowercase(),
        )

        // The odometer has said nothing yet: the phrase stands alone, the way the cluster's does.
        readouts.read(snapshot(trip = TripEnergy(opened = true, netKwh = 0.4)), parked = false)
        assertEquals(figure("0,4"), readouts.tripFigure)
        assertEquals(say("За поездку", "This trip"), readouts.tripCaption)
        assertEquals(EnergyReadouts.sentence(ContourReadout.CAPTION_TRIP), readouts.tripCaption)

        // A ledger that has opened no trip has none, rather than a trip of nothing.
        readouts.read(snapshot(trip = TripEnergy()), parked = false)
        assertNull(readouts.tripFigure)

        // A car that has not answered has no trip to print, rather than a trip of nothing.
        readouts.read(VehicleTelemetry(), parked = false)
        assertNull(readouts.tripFigure)
        readouts.read(VehicleTelemetry(access = VehicleAccess.UNAVAILABLE, message = "нет"), parked = false)
        assertNull(readouts.tripFigure)
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
     * `docs/energy-display-contract.md` §2.1. The pack's id reads zero, or less than the charger
     * delivers, on a car standing on a charger, so the cluster substituted `−|CHARGE_KW|` for the band and the car
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
     * One cluster, a charge that moves across a rounding boundary and settles: 2.4 kW, then 2.6. The
     * comparison above builds a new panel per snapshot and never crosses a boundary inside one; on
     * the car the hero held 2 against the car page's 3 for the rest of the charge.
     */
    @Test
    fun aSettledChargeOnTheOtherSideOfARoundingBoundaryIsOneFigureOnBothScreens() {
        fun charge(kw: Double) = snapshot(
            powerKw = 0.0,
            values = mapOf(
                VehicleSignal.CHARGE_GUN to 2.0,
                VehicleSignal.CHARGE_KW to kw,
                VehicleSignal.CHARGE_MINUTES to 35.0,
            ),
        )
        val panel = ContourPanel()
        for (kw in listOf(2.4, 2.6, 2.4)) {
            val frame = panel.run(charge(kw), SETTLED)
            val model = StripModel().also { StripReadings().car(it, charge(kw), NOW) }
            assertEquals("$kw kW", model.power.figure, frame.heroFigure)
        }
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
        assertEquals(figure("19,8"), readouts.consumptionFigure)
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
        assertEquals(figure("0,0"), readouts.consumptionFigure)
        assertFalse(readouts.consumptionNegative)
    }

    @Test
    fun theWindowNamesTheKnownRoadAndNeverRoundsAFillingOne() {
        val readouts = EnergyReadouts()
        readouts.read(snapshot(powerKw = 22.0, buckets = road(37)), parked = false)
        assertEquals(ContourReadout.UNIT_PER_100KM_PREFIX + say("3,7 км", "3.7 km"), readouts.window)

        readouts.read(snapshot(powerKw = 22.0, buckets = road(100)), parked = false)
        assertEquals(ContourReadout.UNIT_PER_100KM, readouts.window)
    }

    @Test
    fun theEngineBlockSaysWhatThePackTookAndForHowLong() {
        val readouts = EnergyReadouts()
        readouts.read(
            snapshot(powerKw = -14.0, values = mapOf(VehicleSignal.ENGINE_RUNNING to 3.0), trace = trace(82, 14f)),
            parked = false,
        )
        // 14 kW for 82 s is 0.319 kWh.
        assertEquals(figure("0,3"), readouts.engineKwh)
        assertEquals(say("ЗА 1 мин", "IN 1 min"), readouts.engineRun)
        assertEquals(EnergyReadouts.WORD_FROM_ENGINE, readouts.word)

        // After the stop the run keeps its own figure and time, which the block holds for its ten
        // seconds; the sentence leaves with the flag.
        readouts.read(
            snapshot(powerKw = 34.0, values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0), trace = trace(82, 14f, stoppedFor = 3)),
            parked = false,
        )
        assertEquals(figure("0,3"), readouts.engineKwh)
        assertEquals(EnergyReadouts.WORD_FROM_PACK, readouts.word)

        // No run since the hub started: nothing.
        readouts.read(snapshot(powerKw = 34.0), parked = false)
        assertNull(readouts.engineKwh)
    }

    /**
     * The sentence names the engine whenever the pack takes with the engine running: the same
     * condition the block's figure counts under, so the car page's «В батарею от ДВС» and the
     * cluster's «ЗАРЯД ОТ ДВС» never disagree about whose energy it is.
     */
    @Test
    fun theEngineIsTheSourceWheneverItRunsAndThePackTakes() {
        val readouts = EnergyReadouts()
        readouts.read(snapshot(powerKw = -8.0, values = mapOf(VehicleSignal.ENGINE_RUNNING to 3.0)), parked = true)
        assertEquals("standing in P", EnergyReadouts.WORD_FROM_ENGINE, readouts.word)
        readouts.read(snapshot(powerKw = -8.0, values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0)), parked = false)
        assertEquals("a return with the engine off is a return", EnergyReadouts.WORD_TO_PACK, readouts.word)
        readouts.read(snapshot(powerKw = 34.0, values = mapOf(VehicleSignal.ENGINE_RUNNING to 3.0)), parked = false)
        assertEquals("out of the pack is out of the pack", EnergyReadouts.WORD_FROM_PACK, readouts.word)
    }

    /**
     * However small the charge: the neutral zone is about a direction nobody can name, and a running
     * engine names it. Entered from neutral, 4 kW into the pack is inside the zone's ±4.5 kW, and
     * until 2026-11-19 the car page said «Батарея» there while the cluster's block counted the same
     * kilowatts as the run's. A charge whose figure prints «0» names nothing, engine or not.
     */
    @Test
    fun aSmallChargeFromARunningEngineStillNamesTheEngine() {
        val running = mapOf(VehicleSignal.ENGINE_RUNNING to 3.0)
        for (kw in listOf(-4.0, -2.0, -1.0, -0.5)) {
            val readouts = EnergyReadouts()
            readouts.read(snapshot(powerKw = 0.0, values = running), parked = false)
            readouts.read(snapshot(powerKw = kw, values = running), parked = false)
            assertEquals("$kw kW from neutral", EnergyReadouts.WORD_FROM_ENGINE, readouts.word)
            assertTrue("$kw kW: the mark leads it", readouts.mark)
            assertEquals("$kw kW: and it is blue", ContourFlow.BACK, readouts.flow)
        }
        for (kw in listOf(0.0, -0.3, 2.0)) {
            val readouts = EnergyReadouts()
            readouts.read(snapshot(powerKw = kw, values = running), parked = false)
            assertEquals("$kw kW is no charge to name", EnergyReadouts.WORD_NEUTRAL, readouts.word)
            assertFalse(readouts.mark)
        }
        // And the same small charge with the engine off is still a pack with no direction.
        val off = EnergyReadouts()
        off.read(snapshot(powerKw = -4.0, values = mapOf(VehicleSignal.ENGINE_RUNNING to 0.0)), parked = false)
        assertEquals(EnergyReadouts.WORD_NEUTRAL, off.word)
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
                    trace = if (warm) trace(2, 8f, stoppedFor = 20) else EngineChargeSnapshot.EMPTY,
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

        assertEquals(
            EnergyReadouts.EngineCell.MINUTES to "14",
            cell(running = 0.0, trip = TripEnergy(engineSeconds = 14 * 60.0), warm = true),
        )
        assertEquals(ContourReadout.TITLE_ENGINE_MINUTES, readouts.engineCellTitle)

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

    /**
     * The caption and the chart say one thing: the road the unit names is the run's own width.
     *
     * `docs/energy-display-contract.md` §2.2 and §2.3. The unit says «за 3,7 км» off
     * [ConsumptionWindow.coveredKm] and the chart draws thirty-seven points, each one hundred metres
     * of recorded road, so «за 8,6 км» is 86 % of the box and a reader can believe both at once.
     * Before the axis was recorded road the two were different quantities: the unit counted the road
     * the figure was the mean of and the chart stood on the odometer's grid, and a drive with a gap
     * in it printed «за 8,6 км» under a hundred points of which fourteen were drawn as nothing.
     */
    @Test
    fun theRoadTheUnitNamesIsTheWidthOfTheChartAboveIt() {
        val readouts = EnergyReadouts()
        // Every filling width from the fifth reading to the full window, and a seam in the record.
        val widths = (ConsumptionChart.MIN_STEPS..100).toList() + listOf(137, 300)
        widths.forEach { n ->
            readouts.read(snapshot(powerKw = 22.0, buckets = road(n)), parked = false)
            assertEquals(
                "$n readings: the window names the chart's own road",
                kilometres(readouts.chart.span),
                distance(readouts.window),
            )
        }
        val seam = road(50).toMutableList().also { list ->
            for (index in 20 until 30) list[index] = list[index].copy(kwh = 0.0, knownKm = 0.0)
        }
        readouts.read(snapshot(powerKw = 22.0, buckets = seam), parked = false)
        assertEquals("forty readings", 40, readouts.chart.span)
        assertEquals("and the window says four kilometres, not five", figure("4,0"), distance(readouts.window))
    }

    /** A heading's words without its separator: what the two layouts of it must both say. */
    private fun words(text: String): List<String> =
        text.split(' ').filter { it.isNotEmpty() && it != "·" }

    /**
     * The road under [points] of chart, as a window prints it, worked out here rather than asked of
     * the code: a point is a hundred metres, and a full window is a whole «10».
     */
    private fun kilometres(points: Int): String =
        if (points >= ConsumptionChart.POINTS) "10" else figure("${points / 10},${points % 10}")

    /** The distance a window names: the last number in it, since «кВт·ч/100 км» carries one too. */
    private fun distance(window: String): String =
        Regex("""\d+([,.]\d+)?""").findAll(window).lastOrNull()?.value ?: window

    private fun assertArrayEquals(what: String, expected: FloatArray, actual: FloatArray) {
        assertEquals("$what: length", expected.size, actual.size)
        for (index in expected.indices) {
            val a = expected[index]
            val b = actual[index]
            if (a.isNaN() && b.isNaN()) continue
            assertEquals("$what at $index", a, b, 1e-6f)
        }
    }

    private companion object {
        /**
         * Long enough for the cluster to have heard every reading, for its followers to have
         * arrived where the snapshot is, and for a charger to have been agreed with.
         */
        const val SETTLED = 3f
    }
}

/** The same snapshots on the same two screens, in `values-en/`. */
class EnergyReadoutsEnglishTest : EnergyReadoutsTest() {
    @get:Rule
    val english = EnglishWords()
}

/** A frame's clock for snapshots that carry no stamp of their own: nothing in them ages. */
private const val NOW = 0L
