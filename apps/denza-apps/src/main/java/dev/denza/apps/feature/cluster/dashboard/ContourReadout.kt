package dev.denza.apps.feature.cluster.dashboard

import java.util.Locale

/**
 * Every decision the Contour makes about a number or a word before it becomes a shape.
 *
 * The renderer draws; this decides. Keeping the two apart is what makes the panel testable at all -
 * the module has no Robolectric and no screenshot harness, so a `Canvas` call is unverifiable by
 * construction while a threshold, a format and a string are not.
 *
 * ### The strings are the board's strings
 *
 * Every caption here is written exactly as `tools/design-canvas/gen_contour.py` draws it, because
 * the cell widths on both shelves are measured from these words and `ContourBoardContractTest`
 * compares the two records. Changing a caption is a design change and moves the panel.
 *
 * Two rules the words follow. **Units are case-sensitive**: «БАТАРЕЯ · В», «ДВС · об/мин»,
 * «кВт·ч», «км». A tracked capital is a heading, a unit is not one, and a tracked heading does not
 * get to rewrite ГОСТ 8.417. And **a figure names the window it is true over** - «за 3 км» on the
 * petal, «за поездку» on the engine's minutes - because a number integrated over an interval that
 * does not say which interval is read against the interval the reader brought.
 *
 * Everything takes plain numbers rather than a telemetry snapshot, so a test states the case it
 * means instead of assembling a car around it.
 */
internal object ContourReadout {

    // ---- the words

    const val TITLE_PACK = "БАТАРЕЯ · В"
    const val TITLE_ENGINE_RPM = "ДВС · об/мин"

    /**
     * The sleeping engine's heading, and the window is in it.
     *
     * «ДВС · мин» alone was six minutes of *something*: this stop, this hour, this trip, the
     * odometer. The aperture leaves 250.1 units at this baseline and the words take 224.9.
     */
    const val TITLE_ENGINE_MINUTES = "ДВС · мин за поездку"

    const val CAPTION_PACK = "БАТАРЕЯ"
    const val CAPTION_MOTORS = "МОТОРЫ"
    const val CAPTION_INVERTER = "ИНВЕРТОР"

    /** The spread of *what* is exactly the question that started the sixth pass. */
    const val CAPTION_SPREAD = "РАЗБРОС ЯЧЕЕК"

    /** Written after the odometer's own figure and its unit, which lead the phrase. */
    const val CAPTION_TRIP = "· ЗА ПОЕЗДКУ"

    /**
     * And what is left of it when the odometer has gone stale.
     *
     * The separator belongs to the figure in front of it, so it leaves with it: a «·» standing at
     * the head of a cell is a phrase missing its first half rather than a caption.
     */
    const val CAPTION_TRIP_ALONE = "ЗА ПОЕЗДКУ"

    /** Drawn after a blue marker dot, which is what says the same thing «ВЕРНУЛА» would have. */
    const val CAPTION_REGEN = "РЕКУПЕРАЦИЯ"

    /** A verb, and it fits: 94.4 units against a payload of 116.1. */
    const val CAPTION_ENGINE_GAVE = "ДАЛ ДВС"

    /**
     * The whole of what stands under the engine's box: a sentence, not a legend.
     *
     * The seventh pass wrote `ОБОРОТЫ · ● ГЕНЕРАЦИЯ 14 кВт` under two runs, and the owner's verdict
     * on the built panel was that it was not understandable - which is the game lost, because a key
     * is what a display read at 90 km/h does not get to need. One run left, one sentence: what the
     * shape is, what it is worth now, and how far back it goes, in that order. The words «ГЕНЕРАЦИЯ»
     * and «ОБОРОТЫ» are on no part of this panel any more.
     *
     * The window is the trace's own two minutes, which is why it is written into the string rather
     * than formatted from a duration: a caption is a coordinate here, and this one is measured.
     */
    const val LEGEND_INTO_PACK = "В БАТАРЕЮ · ПОСЛЕДНИЕ 2 МИН"

    /**
     * And what is left of it if the face in use crowds the phrase against its own box.
     *
     * Only the window can go. The figure is the reading, the unit is what makes it one, and
     * «В БАТАРЕЮ» is the half of the sentence that says which direction the energy went.
     */
    const val LEGEND_INTO_PACK_SHORT = "В БАТАРЕЮ · 2 МИН"

    const val UNIT_KW = "кВт"
    const val UNIT_KWH = "кВт·ч"
    const val UNIT_KM = "км"
    const val UNIT_MILLIVOLT = "мВ"
    const val DEGREE = "°"

    /** The petal's unit, and the window is in it: the figure is never the trip average. */
    const val UNIT_PER_100KM = "кВт·ч/100 км · за 3 км"

    /** What replaces it while a gun is in, over a figure that is a duration rather than a rate. */
    const val UNIT_CHARGE_LEFT = "до полной"

    // ---- the thresholds, unchanged from the panel this replaces

    /** Cell spread the pack is expected to hold, and where it stops being ordinary. */
    const val SPREAD_WATCH_MV = 25.0
    const val SPREAD_ALERT_MV = 40.0

    /** The only thermal reading the generation path has; there is no coolant temperature here. */
    const val INVERTER_WATCH_C = 70.0

    /**
     * What the pack is comfortable at, and how far past it counts as hot rather than warm.
     *
     * The low end is gone with the panel this replaces. It stood at 15 °C, which a pack is under on
     * most winter mornings in this city, so the exception colour would have been on the shelf more
     * often than off it - and a shelf whose rule is "colour means look at this" cannot spend orange
     * on the ordinary. Nothing is lost that the driver could act on: a cold pack limits regeneration
     * and says so through the band, which is where they would see it.
     */
    const val PACK_BAND_HIGH_C = 40.0
    const val DRIVE_BAND_HIGH_C = 70.0
    const val HOT_MARGIN_C = 15.0

    /**
     * Generation's ceiling in the engine box: 30 kW, linear, clamped.
     *
     * The concept named a square root to 100 kW and the owner read the result as flat twice. At the
     * 14 kW this car ordinarily generates a root over 100 fills a third of a 50-unit box; linear
     * over 30 fills a half, and 30 is what this generator does rather than a span borrowed from the
     * band. What a root buys is resolution near zero, and near zero this quantity is *off*.
     */
    const val GENERATION_FULL_KW = 30.0

    // ---- the numbers

    /** A whole number, and never a dash: a value that is not there is not drawn at all. */
    fun whole(value: Double): String = String.format(Locale.US, "%.0f", value)

    /** A number with a comma, the way every other panel in this app writes one. */
    fun tenth(value: Double): String =
        String.format(Locale.US, "%.1f", value).replace('.', ',')

    /**
     * The petal's figure: a whole number on the move, a tenth on P.
     *
     * At 100 km/h a tenth of a kilowatt-hour per hundred kilometres changes three times a second,
     * and a figure that flickers is a figure nobody reads (m5). Standing still it is worth the
     * resolution, and the denominator does not change underneath it either way - the window is
     * always the last three kilometres, and since the seventh pass the unit says so.
     */
    fun consumption(perHundredKm: Double, parked: Boolean): String =
        if (parked) tenth(perHundredKm) else whole(perHundredKm)

    /**
     * What is left of a charge, as the petal prints it: «2:15».
     *
     * A colon rather than a decimal, because this is a clock and not a quantity - and the reason
     * the figures are Roboto with `tnum` rather than Roboto Mono is that a monospaced face gives
     * that colon a full digit cell and breaks «2:15» into three groups.
     */
    fun chargeLeft(minutes: Int): String {
        val bounded = minutes.coerceAtLeast(0)
        return String.format(Locale.US, "%d:%02d", bounded / 60, bounded % 60)
    }

    /**
     * The mean of a run of consumption buckets, over the spending ones only.
     *
     * Averaging the recovery in would answer a question nobody asks - what the car spent net of
     * what a hill gave back - and would read lower than any part of the history beside it.
     */
    fun averageConsumption(buckets: List<Double>): Double? {
        val spending = buckets.filter { it >= 0.0 }
        if (spending.isEmpty()) return null
        return spending.average()
    }

    // ---- the exceptions, which are the only colour on the shelves

    /** Where a reading sits against the band it is expected to stay in. */
    enum class Level { NORMAL, WATCH, ALERT }

    /** Whether a temperature has left its band, and by enough to stop being merely warm. */
    fun thermalState(celsius: Double, bandHigh: Double): Level = when {
        celsius > bandHigh + HOT_MARGIN_C -> Level.ALERT
        celsius > bandHigh -> Level.WATCH
        else -> Level.NORMAL
    }

    /** Whether a cell spread is ordinary, worth watching, or worth stopping for. */
    fun spreadState(millivolts: Double): Level = when {
        millivolts >= SPREAD_ALERT_MV -> Level.ALERT
        millivolts >= SPREAD_WATCH_MV -> Level.WATCH
        else -> Level.NORMAL
    }

    /**
     * Whether the spread gets a cell at all.
     *
     * The fourth cell on the temperature shelf is an exception rather than a row: a pack holding
     * its cells together is the ordinary case and says nothing worth 167 units of shelf.
     */
    fun spreadIsWorthACell(level: Level): Boolean = level != Level.NORMAL

    /**
     * Where generation falls in the engine box: linear on [GENERATION_FULL_KW], clamped.
     *
     * Clamped rather than autoscaled for the reason the petal's ladder is fixed - a bin that changes
     * height because a *different* bin changed value never draws the same two minutes twice - and
     * clamped rather than open-topped because a generation above this span is the same fact as this
     * span: the engine is giving everything it has.
     */
    fun generationFraction(kilowatts: Double): Float =
        (kilowatts / GENERATION_FULL_KW).coerceIn(0.0, 1.0).toFloat()
}
