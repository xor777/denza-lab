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

    /** «ДВС ДАЁТ» - what the engine gives, not where it goes. */
    val enginePrefix: String = ContourReadout.LEGEND_PREFIX

    /** What it is giving now, in whole kilowatts, or null when it is not giving. */
    var engineFigure: String? = null
        private set

    /** How far back its box reaches: «· ПОСЛЕДНИЕ 1:22», or «· 1:22» where the face crowds it. */
    var engineWindow: String = ""
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
        val load = telemetry.loadKw
        flow = if (load == null) ContourFlow.NEUTRAL else ContourMotion.flowOf(load.toFloat(), held)
        held = flow
        // Unavailable is not zero: no figure, and the words stay.
        powerFigure = load?.let { figures.whole(ContourFigures.Slot.POWER, abs(it)) }

        word = when {
            telemetry.charging -> WORD_FROM_CHARGER
            load == null || flow == ContourFlow.NEUTRAL -> WORD_NEUTRAL
            flow == ContourFlow.OUT -> WORD_FROM_PACK
            telemetry.generating -> WORD_FROM_ENGINE
            else -> WORD_TO_PACK
        }
        // The mark leads the two sentences that name where the energy is coming from, and nothing
        // else: it means «into the pack», and «В БАТАРЕЮ» already says that in words.
        mark = word == WORD_FROM_ENGINE || word == WORD_FROM_CHARGER

        val mean = telemetry.consumptionMean
        consumptionFigure = mean?.let { figures.consumption(it, parked) }
        consumptionNegative = mean != null && mean < 0.0

        val covered = telemetry.consumptionKm
        window = figures.perHundredKm(covered)
        windowCaps = figures.windowCaps(covered, narrow)

        val trace = telemetry.engineTrace
        engineWindow = figures.intoPack(trace.spanSeconds, shortLegend)
        val generation = telemetry.generationKw
        engineFigure = if (telemetry.engineRunning == true && generation != null) {
            figures.whole(ContourFigures.Slot.GENERATION, generation)
        } else {
            null
        }

        chart = telemetry.chart
    }

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
    }
}
