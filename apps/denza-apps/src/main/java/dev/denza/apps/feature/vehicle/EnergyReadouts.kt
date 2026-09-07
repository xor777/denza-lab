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

    /** The cluster's unit: «кВт·ч/100 км · за 10 км», or the road the window actually has. */
    var window: String = ""
        private set

    /** And the car page's own case of the same window: «ЗА 10 КМ» / «10 КМ» in a pane. */
    var windowCaps: String = ""
        private set

    /** The car page's whole foot unit, «кВт·ч/100 км · ЗА 10 КМ», which that page measures. */
    var windowFoot: String = ""
        private set

    /** «ДВС ДАЁТ» - what the engine gives, not where it goes. */
    val enginePrefix: String = ContourReadout.LEGEND_PREFIX

    /** What it is giving now, in whole kilowatts, or null when it is not giving. */
    var engineFigure: String? = null
        private set

    /** How far back its box reaches: «· ПОСЛЕДНИЕ 1:22», or «· 1:22» where the face crowds it. */
    var engineWindow: String = ""
        private set

    /** Which of the engine's three cells this snapshot earns, and [EngineCell.NONE] is one of them. */
    var engineCell: EngineCell = EngineCell.NONE
        private set

    /** Its heading in the cluster's case, and empty where there is no cell. */
    var engineCellTitle: String = ""
        private set

    /** And in the car page's, which is the same words shouted. */
    var engineCellTitleCaps: String = ""
        private set

    /** The reading inside it - revolutions or minutes - or null where there is no cell. */
    var engineCellFigure: String? = null
        private set

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
     * @param narrow whether the car page is in a pane, where the window drops its «ЗА»
     * @param shortLegend whether the face in use crowds «ПОСЛЕДНИЕ» out of the engine's sentence
     */
    fun read(
        telemetry: VehicleTelemetry,
        parked: Boolean,
        narrow: Boolean = false,
        shortLegend: Boolean = false,
    ) {
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

        val covered = telemetry.consumptionKm
        window = figures.perHundredKm(covered)
        windowCaps = figures.windowCaps(covered, narrow)
        windowFoot = figures.windowFoot(covered, narrow)

        val trace = telemetry.engineTrace
        engineWindow = figures.intoPack(trace.spanSeconds, shortLegend)
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

        chart = telemetry.chart
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
            engineCellTitleCaps = ContourReadout.TITLE_ENGINE_RPM_CAPS
            engineCellFigure = figures.whole(ContourFigures.Slot.RPM, rpm)
            return
        }
        val minutes = t.trip.engineMinutes
        if (!t.engineTrace.isEmpty && t.trip.engineRan && minutes >= 1.0) {
            engineCell = EngineCell.MINUTES
            engineCellTitle = ContourReadout.TITLE_ENGINE_MINUTES
            engineCellTitleCaps = ContourReadout.TITLE_ENGINE_MINUTES_CAPS
            engineCellFigure = figures.whole(ContourFigures.Slot.ENGINE_MINUTES, minutes)
            return
        }
        engineCell = EngineCell.NONE
        engineCellTitle = ""
        engineCellTitleCaps = ""
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
