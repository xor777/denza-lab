package dev.denza.apps.feature.vehicle

import dev.denza.apps.feature.cluster.dashboard.ContourFigures
import dev.denza.apps.feature.cluster.dashboard.ContourFlow
import dev.denza.apps.feature.cluster.dashboard.ContourMotion
import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import kotlin.math.abs

/**
 * Every energy string and every energy shape both screens draw, decided once.
 *
 * `docs/energy-display-contract.md` §1 and §7: **one quantity, one definition, one set of words, on
 * both screens.** The car page printed «В БАТАРЕЮ» over «−25 кВт» because the word and the sign
 * were decided in two places; there is one place now, and `EnergyReadoutsTest` feeds the same
 * snapshots to a cluster-side instance and a strip-side one and asserts they agree.
 *
 * The two renderers own geometry and nothing else. Neither formats a number.
 *
 * ### Why this is an object per screen rather than a function
 *
 * Two things make it stateful, and both are per screen. The neutral zone's hysteresis is a function
 * of where that screen already was (`ContourMotion.flowOf`), so two screens looking at the same
 * kilowatts through different histories may legitimately differ for one frame. And the strings are
 * memoised through [ContourFigures], because the cluster asks sixty times a second about a snapshot
 * that arrives four - re-formatting each of them would be a thousand strings a second of garbage
 * on a view drawn over the vehicle's own instruments.
 *
 * ### What it does not decide
 *
 * Staleness, which is `ContourScene`'s one rule, and smoothing, which is `ContourMotion`'s. The
 * cluster's hero prints the *followed* magnitude at 4 Hz with its own rounding hysteresis and takes
 * its colour from the same [ContourMotion.flowOf] this does; that is one rule read at two speeds
 * rather than two rules.
 *
 * Pure Kotlin: no Android imports, so a test states the case it means instead of assembling a car.
 */
internal class EnergyReadouts {

    private val figures = ContourFigures()

    /** Where this screen's colour already was, which is what the neutral zone's hysteresis needs. */
    private var held = ContourFlow.NEUTRAL

    // ---- what a read leaves behind

    /** The colour role: [ContourFlow.NEUTRAL] grey, [ContourFlow.OUT] ink, [ContourFlow.BACK] blue. */
    var flow: ContourFlow = ContourFlow.NEUTRAL
        private set

    /** The direction, in words. Never a sign. */
    var word: String = WORD_NEUTRAL
        private set

    /**
     * The same words in the car page's sentence case: «Из батареи», «В батарею от ДВС».
     *
     * The Luminofor strip writes its captions the way the head unit writes everything else - a
     * capital, then lower case - where the cluster keeps its tracked capitals. It is one decision
     * printed in two cases, and the case is derived here from the capitals ([sentence]) rather than
     * typed a second time, so the two screens cannot drift into two sentences.
     */
    var wordSentence: String = WORD_NEUTRAL_SENTENCE
        private set

    /** Whether the blue mark leads the word, which it does only where the sentence names a source. */
    var mark: Boolean = false
        private set

    /** `|P|` in whole kilowatts, or null while the pack has not answered. */
    var powerFigure: String? = null
        private set

    /** The window's consumption: whole on the move, a tenth on P, with its minus where it has one. */
    var consumptionFigure: String? = null
        private set

    /** Which is the one signed figure on either screen, and it is signed because it is an exception. */
    var consumptionNegative: Boolean = false
        private set

    /**
     * The consumption's unit and the road it is over: «кВт·ч/100 км · за 10 км», or the road the
     * window actually has. One string on both screens - the cluster prints it after the petal's
     * figure, the car page after «Расход» and the same figure over its chart.
     */
    var window: String = ""
        private set

    /** «ДВС ДАЁТ» - what the engine gives, not where it goes. */
    val enginePrefix: String = ContourReadout.LEGEND_PREFIX

    /** What it is giving now, in whole kilowatts, or null when it is not giving. */
    var engineFigure: String? = null
        private set

    /** How far back its box reaches: «ПОСЛЕДНИЕ 1:22», on the line under the box. */
    var engineWindow: String = ""
        private set

    /** Which of the engine's three cells this snapshot earns, and [EngineCell.NONE] is one of them. */
    var engineCell: EngineCell = EngineCell.NONE
        private set

    /** Its heading in the cluster's case, and empty where there is no cell. */
    var engineCellTitle: String = ""
        private set

    /** The reading inside it - revolutions or minutes - or null where there is no cell. */
    var engineCellFigure: String? = null
        private set

    /**
     * And the car page's own case of that heading, split where the strip splits a reading: the
     * caption over the figure, the unit small beside it - «ДВС» over «1650 об/мин», «ДВС за
     * поездку» over «6 мин». Empty where there is no cell. See [ENGINE_RPM_CAPTION].
     */
    var engineCellCaption: String = ""
        private set

    var engineCellUnit: String = ""
        private set

    /**
     * What this trip has cost the pack, as the car page prints it: «9,3» over «кВт·ч», or null
     * where there is no answer to print.
     *
     * The same integral the cluster's first seat draws ([TripEnergy.netKwh], a tenth), through the
     * same memo slot a seat uses, so the two screens print one number. Null until the car has
     * answered: a trip that has not been read is not a trip of nothing.
     */
    var tripFigure: String? = null
        private set

    /**
     * Its caption, which names the road it is over: «42 км · за поездку», and «За поездку» alone
     * while the odometer has nothing to say - the cluster's own two phrases in sentence case.
     */
    var tripCaption: String = TRIP_ALONE_SENTENCE
        private set

    /** The odometer's figure [tripCaption] was last built from, so the phrase is built once a km. */
    private var tripKm: String? = null

    /** The pack's volts, whole, or null while the read has not landed. */
    var voltsFigure: String? = null
        private set

    /** The twenty bins, which are the same twenty bins on both screens. */
    var chart: ConsumptionChartSnapshot = ConsumptionChartSnapshot.EMPTY
        private set

    /**
     * One snapshot.
     *
     * @param parked whether the selector is in P, which is what buys the consumption its tenth
     */
    fun read(telemetry: VehicleTelemetry, parked: Boolean) {
        val load = packKilowatts(telemetry)
        // A read that did not land is not a reading of zero, so it does not get to move the
        // hysteresis: one dropped sample used to reset the band's colour to grey, and the next
        // good one came back inside the neutral zone and stayed grey with it.
        val band = if (load == null) ContourFlow.NEUTRAL else ContourMotion.flowOf(load.toFloat(), held)
        if (load != null) held = band
        // Unavailable is not zero: no figure, and the words stay.
        powerFigure = load?.let { figures.whole(ContourFigures.Slot.POWER, abs(it)) }

        word = when {
            telemetry.charging -> WORD_FROM_CHARGER
            load == null || band == ContourFlow.NEUTRAL -> WORD_NEUTRAL
            band == ContourFlow.OUT -> WORD_FROM_PACK
            telemetry.generating -> WORD_FROM_ENGINE
            else -> WORD_TO_PACK
        }
        wordSentence = when (word) {
            WORD_FROM_CHARGER -> WORD_FROM_CHARGER_SENTENCE
            WORD_FROM_PACK -> WORD_FROM_PACK_SENTENCE
            WORD_FROM_ENGINE -> WORD_FROM_ENGINE_SENTENCE
            WORD_TO_PACK -> WORD_TO_PACK_SENTENCE
            else -> WORD_NEUTRAL_SENTENCE
        }
        // The mark leads the two sentences that name where the energy is coming from, and nothing
        // else: it means «into the pack», and «В БАТАРЕЮ» already says that in words.
        mark = word == WORD_FROM_ENGINE || word == WORD_FROM_CHARGER
        // And a sentence that names a source is blue whatever the magnitude is. «В БАТАРЕЮ ОТ
        // ЗАРЯДКИ» in grey over a 2 kW wall charge was a word and a colour saying different
        // things, which is the defect this class exists for - the neutral zone is about a
        // *direction* nobody can name, and these two sentences have named it.
        flow = if (mark) ContourFlow.BACK else band

        val mean = telemetry.consumptionMean
        consumptionFigure = mean?.let { figures.consumption(it, parked) }
        // Read off the printed figure rather than off the mean: −0,04 rounds to «0,0» and a minus
        // the reader cannot see is not the exception the blue is for.
        consumptionNegative = consumptionFigure?.startsWith('-') == true

        window = figures.perHundredKm(telemetry.consumptionKm)

        val trace = telemetry.engineTrace
        engineWindow = figures.intoPack(trace.spanSeconds)
        val generation = telemetry.generationKw
        // A figure of «0 кВт» inside «ДВС ДАЁТ … » is the zero this panel does not draw, and it is
        // the state the two drives so far recorded: the engine running with the id flat.
        engineFigure =
            if (telemetry.engineRunning == true &&
                generation != null &&
                generation > VehicleTelemetry.GENERATION_FLOOR_KW
            ) {
                figures.whole(ContourFigures.Slot.GENERATION, generation)
            } else {
                null
            }

        readEngineCell(telemetry)
        voltsFigure = telemetry[VehicleSignal.PACK_VOLT]?.let {
            figures.whole(ContourFigures.Slot.VOLTS, it)
        }
        readTrip(telemetry)

        chart = telemetry.chart
    }

    /**
     * The trip's own cell on the car page: the pack's net over the trip, named by its road.
     *
     * The cluster decides the same cell from `ContourScene`'s ages; the car page has no scene and
     * reads each snapshot as it comes, so the rule here is the snapshot's own: a car that has
     * answered carries its trip ([VehicleTelemetry.trip] is integrated by the hub), and one that has
     * not - still starting, or closed to us - has no trip to print.
     */
    private fun readTrip(t: VehicleTelemetry) {
        if (t.access != VehicleAccess.READY) {
            tripFigure = null
            tripCaption = TRIP_ALONE_SENTENCE
            tripKm = null
            return
        }
        tripFigure = figures.seat(TRIP_SEAT, t.trip.netKwh)
        val km = t.trip.kilometres
        if (km <= 0.0) {
            tripCaption = TRIP_ALONE_SENTENCE
            tripKm = null
            return
        }
        val figure = figures.whole(ContourFigures.Slot.ODOMETER, km)
        // The slot hands back the very string it printed last time for the same kilometre, so an
        // identity check is what keeps the phrase from being rebuilt thirty times a second.
        if (figure !== tripKm) {
            tripKm = figure
            tripCaption = figure + TRIP_KM_SENTENCE
        }
    }

    /**
     * The engine's one cell, and the three things it can say - on both screens.
     *
     * Turning, it is the revolutions. Just stopped, it is how long it ran this trip. Otherwise
     * nothing at all: a zero here would be an accountant's way of saying the engine did not run,
     * and a run under a minute is a zero with a unit on it.
     *
     * **«Just stopped» is the trace, not the trip**, and that is the owner's own finding from the
     * first drive: he got into the car, had not started the engine, and the cell said «3 мин за
     * поездку». It was not lying - a trip runs from the first movement after P, so yesterday's
     * drive was still the trip - but a cell that says «за поездку» beside a cold engine is read as
     * *this* drive, and being technically right is not an answer. So the cell lives as long as the
     * trace holds a slot the engine was alive in, which is a hundred and twenty seconds with no
     * timer of its own.
     *
     * The cluster used to decide this from the trip alone and the car page from the trace, so the
     * same cold engine had a corner on one screen and none on the other.
     */
    private fun readEngineCell(t: VehicleTelemetry) {
        val rpm = t.engineRpm
        if (t.engineRunning == true && rpm != null && rpm > 0.0) {
            engineCell = EngineCell.RPM
            engineCellTitle = ContourReadout.TITLE_ENGINE_RPM
            engineCellCaption = ENGINE_RPM_CAPTION
            engineCellUnit = ENGINE_RPM_UNIT
            engineCellFigure = figures.whole(ContourFigures.Slot.RPM, rpm)
            return
        }
        val minutes = t.trip.engineMinutes
        if (!t.engineTrace.isEmpty && t.trip.engineRan && minutes >= 1.0) {
            engineCell = EngineCell.MINUTES
            engineCellTitle = ContourReadout.TITLE_ENGINE_MINUTES
            engineCellCaption = ENGINE_MINUTES_CAPTION
            engineCellUnit = ENGINE_MINUTES_UNIT
            engineCellFigure = figures.whole(ContourFigures.Slot.ENGINE_MINUTES, minutes)
            return
        }
        engineCell = EngineCell.NONE
        engineCellTitle = ""
        engineCellCaption = ""
        engineCellUnit = ""
        engineCellFigure = null
    }

    /** What the engine's own cell is saying, and «nothing» is one of the answers. */
    enum class EngineCell { NONE, RPM, MINUTES }

    companion object {
        /** Inside the neutral zone there is no direction to name, so the noun stands alone. */
        const val WORD_NEUTRAL = "БАТАРЕЯ"
        const val WORD_FROM_PACK = "ИЗ БАТАРЕИ"
        const val WORD_TO_PACK = "В БАТАРЕЮ"

        /**
         * And the two that name where it is coming from, which is what earns the blue mark.
         *
         * The gun outranks the engine because that is the order in which the answer stops being
         * obvious: a driver can see the road giving energy back, cannot see the engine deciding
         * to, and cannot see the charger at all.
         */
        const val WORD_FROM_ENGINE = "В БАТАРЕЮ ОТ ДВС"
        const val WORD_FROM_CHARGER = "В БАТАРЕЮ ОТ ЗАРЯДКИ"

        /** The abbreviation that stays capital in every case: an engine is a «ДВС», never a «двс». */
        const val ENGINE_ABBREVIATION = "ДВС"

        /**
         * Capitals into the car page's sentence case: a capital, lower case after it, and «ДВС»
         * left as it is written.
         *
         * A derivation rather than five more literals: one word in two cases is one record, and a
         * second record is a second place for it to change.
         */
        fun sentence(caps: String): String =
            caps.lowercase()
                .replaceFirstChar { it.uppercaseChar() }
                .replace(ENGINE_ABBREVIATION.lowercase(), ENGINE_ABBREVIATION)

        val WORD_NEUTRAL_SENTENCE: String = sentence(WORD_NEUTRAL)
        val WORD_FROM_PACK_SENTENCE: String = sentence(WORD_FROM_PACK)
        val WORD_TO_PACK_SENTENCE: String = sentence(WORD_TO_PACK)
        val WORD_FROM_ENGINE_SENTENCE: String = sentence(WORD_FROM_ENGINE)
        val WORD_FROM_CHARGER_SENTENCE: String = sentence(WORD_FROM_CHARGER)

        /**
         * The engine's two headings as the car page lays a reading out: the words over the figure
         * and the unit beside it.
         *
         * The cluster writes one line, «ДВС · об/мин» and «ДВС · мин за поездку», because it has a
         * caption and a figure and no unit slot; the strip has the slot, so the unit moves into it
         * and the rest of the heading stays up - «ДВС» over «1650 об/мин», «ДВС за поездку» over
         * «6 мин». Split off the cluster's own titles by one rule (the unit is the first word after
         * the dot), so neither screen can rename the engine without the other.
         */
        val ENGINE_RPM_CAPTION: String = engineCaption(ContourReadout.TITLE_ENGINE_RPM)
        val ENGINE_RPM_UNIT: String = engineUnit(ContourReadout.TITLE_ENGINE_RPM)
        val ENGINE_MINUTES_CAPTION: String = engineCaption(ContourReadout.TITLE_ENGINE_MINUTES)
        val ENGINE_MINUTES_UNIT: String = engineUnit(ContourReadout.TITLE_ENGINE_MINUTES)

        private fun engineTail(title: String): String = title.substringAfter(ContourReadout.SEPARATOR)

        private fun engineUnit(title: String): String = engineTail(title).substringBefore(' ')

        private fun engineCaption(title: String): String {
            val head = title.substringBefore(ContourReadout.SEPARATOR)
            val rest = engineTail(title).substringAfter(' ', "")
            return if (rest.isEmpty()) head else "$head $rest"
        }

        /** The trip's phrase after its kilometres, «· за поездку», and the phrase alone. */
        val TRIP_KM_SENTENCE: String =
            " " + ContourReadout.UNIT_KM + " " + ContourReadout.CAPTION_TRIP.lowercase()
        val TRIP_ALONE_SENTENCE: String = sentence(ContourReadout.CAPTION_TRIP_ALONE)

        /** The cluster's first seat, which is the trip's net: the slot the figure is memoised in. */
        private const val TRIP_SEAT = 0

        /**
         * Pack power as both screens read it, which is the one place the charger's substitution is.
         *
         * `docs/energy-display-contract.md` §2.1: while the charger has agreed, `P` is
         * `−|CHARGE_KW|`. The pack's own id reads zero or a small load on a car standing on a
         * charger - the board electronics - and a band that drew *that* while «В БАТАРЕЮ ОТ
         * ЗАРЯДКИ» stood over it was two readings of one event. The cluster substituted and the
         * car page did not, so the same charge was 7 kW on one screen and 0 on the other.
         */
        fun packKilowatts(t: VehicleTelemetry): Double? {
            if (t.charging) {
                val charge = t.chargeKw
                if (charge != null) return -abs(charge)
            }
            return t.loadKw
        }
    }
}
