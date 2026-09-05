package dev.denza.apps.feature.trip

import dev.denza.apps.feature.cluster.dashboard.ContourReadout
import dev.denza.apps.feature.vehicle.ConsumptionWindow
import dev.denza.apps.feature.vehicle.VehicleTelemetry

/**
 * What the car's page says, and when it says nothing.
 *
 * These four decisions are the page's whole argument and none of them is a statement about pixels,
 * so they are here rather than inside a `Canvas` call where nothing could read them back. The
 * Contour keeps `ContourReadout` for the same reason and after the same lesson: the sentence, not
 * the drawing, is what a reader was getting wrong.
 *
 *  - **one quantity, one sentence.** The headline says what is happening in words and the figure
 *    under it says how much. A minus in front of a number is not a direction anybody reads at a
 *    glance;
 *  - **a zero is never drawn, and a quantity that did not happen has no cell.** The engine's cell
 *    is absent until the engine has run, the consumption is absent while the car is standing, and
 *    neither leaves a hole that has to be filled with `0`;
 *  - **a figure names the window it is true over.** Two minutes, or the run the box actually has.
 */
internal object VehiclePageWords {

    /**
     * What is happening, in words, and whether it earns the mark that means «into the pack».
     *
     * The gun outranks the engine and the engine outranks a plain recovery, because that is the
     * order in which the answer stops being obvious: a driver can see the road giving energy back,
     * cannot see the engine deciding to, and cannot see the charger at all.
     *
     * Null when there is nothing to say. A pack that has not answered is not "out of the battery"
     * - the sentence *is* the claim here, and a claim with no reading behind it is the one thing
     * this page must not print.
     */
    fun headline(telemetry: VehicleTelemetry): Headline? {
        if (telemetry.charging) return Headline(TITLE_FROM_CHARGER, mark = true)
        val load = telemetry.loadKw ?: return null
        return when {
            load >= 0.0 -> Headline(TITLE_FROM_PACK, mark = false)
            telemetry.generating -> Headline(TITLE_FROM_ENGINE, mark = true)
            else -> Headline(TITLE_TO_PACK, mark = true)
        }
    }

    /**
     * The engine's one cell, and the three things it can say.
     *
     * Turning, it is the revolutions. Stopped after a run, it is how long that run was - which is
     * [ContourReadout.TITLE_ENGINE_MINUTES], the cluster's own words, in this screen's own case.
     * Never started, it is nothing at all: a zero here would be an accountant's way of saying the
     * engine did not run, which is the sentence the Contour's sixth pass exists to have deleted.
     *
     * A run under a minute is nothing at all too, for the same reason - «0 мин за поездку» is a
     * zero with a unit on it.
     */
    fun engineCell(telemetry: VehicleTelemetry): Pair<String, String>? {
        val rpm = telemetry.engineRpm
        if (telemetry.engineRunning == true && rpm != null && rpm > 0.0) {
            return TITLE_RPM to ContourReadout.whole(rpm)
        }
        val minutes = telemetry.trip.engineMinutes
        if (!telemetry.trip.engineRan || minutes < 1.0) return null
        return TITLE_ENGINE_MINUTES to ContourReadout.whole(minutes)
    }

    /**
     * How far back the shape reaches, which is what its caption may claim.
     *
     * Two minutes is the window's own length and the figure a full box is allowed to print; a box
     * still filling says the run it has. In a pane the words shorten before anything else on that
     * line goes - the Contour's own rule for the same line, where the span may not go at all.
     */
    fun window(seconds: Int, narrow: Boolean): String = when {
        seconds >= WINDOW_SECONDS -> if (narrow) TITLE_WINDOW_SHORT else TITLE_WINDOW
        else -> "$TITLE_WINDOW_PREFIX ${BaseTripRenderer.clockMs(seconds.toDouble())}"
    }

    /**
     * What the last few kilometres cost, over the distance they actually were.
     *
     * Absent while the car stands: kWh per 100 km has no value at zero speed, which is why
     * [ConsumptionWindow.mean] returns null rather than a crawling average, and an absent figure
     * is drawn as no cell rather than as a dash - nothing is missing, there is simply no
     * consumption to report yet.
     */
    fun spend(telemetry: VehicleTelemetry): String? {
        val mean = telemetry.consumptionMean ?: return null
        val km = ConsumptionWindow.coveredKm(telemetry.consumption)
        if (km <= 0.0) return null
        return "${ContourReadout.tenth(mean)} $UNIT_PER_100 $TITLE_OVER " +
            "${ContourReadout.whole(km)} $UNIT_KM"
    }

    class Headline(val text: String, val mark: Boolean)

    /** Two minutes, in the seconds the trace counts. */
    const val WINDOW_SECONDS = 120

    const val TITLE_FROM_PACK = "ИЗ БАТАРЕИ"
    const val TITLE_TO_PACK = "В БАТАРЕЮ"
    const val TITLE_FROM_ENGINE = "В БАТАРЕЮ ОТ ДВС"
    const val TITLE_FROM_CHARGER = "В БАТАРЕЮ ОТ ЗАРЯДКИ"
    const val TITLE_RPM = "ДВС · ОБ/МИН"
    const val TITLE_WINDOW = "ПОСЛЕДНИЕ 2 МИНУТЫ"
    const val TITLE_WINDOW_SHORT = "2 МИН"
    const val TITLE_WINDOW_PREFIX = "ПОСЛЕДНИЕ"
    const val TITLE_OVER = "ЗА"
    const val UNIT_PER_100 = "кВт·ч/100"
    const val UNIT_KM = "КМ"

    /** The cluster's own words for the same reading, in this screen's own case. */
    val TITLE_ENGINE_MINUTES: String = ContourReadout.TITLE_ENGINE_MINUTES.uppercase()
}
