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

    /**
     * The spread of *what* is exactly the question that started the sixth pass.
     *
     * And since the ninth it is the **only** word in the temperature row: `БАТАРЕЯ`, `МОТОРЫ` and
     * `ИНВЕРТОР` are gone, replaced by [ContourGlyphs]. Which means a word there now means the pack
     * is misbehaving, which is worth more than the three captions cost.
     */
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
     * **And the window is the box's own reach rather than a literal.** «ПОСЛЕДНИЕ 2 МИН» was
     * printed from the first second of an engine run, over a box one step wide: the trace grows
     * from the right and is never front-padded, so two minutes is what it holds when it is full and
     * nothing else. The figure beside it was honest about a five-second window and the words were
     * not. It is written as «м:сс» in the panel's tabular figures, so every value is the same width
     * and no anchor in the phrase moves - which is how the caption can be a coordinate and a
     * reading at the same time. See [intoPack].
     */
    const val LEGEND_PREFIX = "В БАТАРЕЮ · ПОСЛЕДНИЕ "

    /**
     * And what is left of it if the face in use crowds the phrase against its own box.
     *
     * Only «ПОСЛЕДНИЕ» can go. The figure is the reading, the unit is what makes it one,
     * «В БАТАРЕЮ» is the half of the sentence that says which direction the energy went, and the
     * duration is the window the shape above is true over.
     */
    const val LEGEND_PREFIX_SHORT = "В БАТАРЕЮ · "

    /** What the phrase is *measured* from: every «м:сс» is four tabular glyphs and one mark. */
    const val LEGEND_INTO_PACK = LEGEND_PREFIX + "0:00"
    const val LEGEND_INTO_PACK_SHORT = LEGEND_PREFIX_SHORT + "0:00"

    const val UNIT_KW = "кВт"
    const val UNIT_KWH = "кВт·ч"
    const val UNIT_KM = "км"
    const val UNIT_MILLIVOLT = "мВ"
    const val DEGREE = "°"

    /** What a charge estimate too long for «ч:мм» is written in. See [chargeLeft]. */
    const val UNIT_HOUR = "ч"

    /** From here the estimate is hours alone: five glyphs do not fit the petal's seat. */
    const val HOURS_ALONE_FROM = 10

    /** Both charging ids are gated to 0…99, so anything past this is a bad read, not a charge. */
    const val MAX_CHARGE_MINUTES = 99 * 60 + 59

    /**
     * The petal's unit, and the window is in it: the figure is never the trip average.
     *
     * Three kilometres is what the log has *closed*, not what the window is sized for. A history
     * that has just started - a fresh install, a reset journal, the first minutes of a drive - is
     * five hundred metres of road printed under «за 3 км», which is the seventh pass's own defect
     * one level down. The drawn form is [perHundredKm]; this is the case where the window is full.
     */
    const val UNIT_PER_100KM_PREFIX = "кВт·ч/100 км · за "
    const val UNIT_PER_100KM = UNIT_PER_100KM_PREFIX + "3 км"

    /**
     * And the widest it ever is, which is any distance still filling the window.
     *
     * The figures are tabular, so «за 0,3 км» and «за 2,7 км» are one width and this template
     * measures them all. The unit is left-aligned against the figure's reserve and nothing hangs
     * off it, so its own width is allowed to change: there is nothing to its right but the petal's
     * cut-out, which it clears by 89.9 at its widest.
     */
    const val UNIT_PER_100KM_FILLING = UNIT_PER_100KM_PREFIX + "1,2 км"

    /** What replaces it while a gun is in, over a figure that is a duration rather than a rate. */
    const val UNIT_CHARGE_LEFT = "до полной"

    /** The petal's unit ends in this, whatever distance it names. */
    const val KM_SUFFIX = " км"

    /** Past this a «м:сс» gains a glyph and every anchor in front of it would move. */
    const val MAX_WINDOW_SECONDS = 9 * 60 + 59

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

    /** The odometer arrives in tenths and the differences accumulate in doubles. */
    private const val KM_EPSILON = 1e-6

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
     * The petal's unit, naming the road the figure beside it is actually the mean of.
     *
     * @param coveredKm what the closed buckets add up to
     * @param windowKm what the window holds once it is full
     */
    fun perHundredKm(coveredKm: Double, windowKm: Double): String =
        if (coveredKm >= windowKm - KM_EPSILON) {
            UNIT_PER_100KM_PREFIX + whole(windowKm) + KM_SUFFIX
        } else {
            UNIT_PER_100KM_PREFIX + tenth(coveredKm) + KM_SUFFIX
        }

    /**
     * The engine box's sentence, naming how far back the box actually reaches.
     *
     * @param seconds the trace's own span, which is the box's own width in seconds
     * @param short whether the face in use crowds «ПОСЛЕДНИЕ» out of the phrase
     */
    fun intoPack(seconds: Int, short: Boolean): String =
        (if (short) LEGEND_PREFIX_SHORT else LEGEND_PREFIX) + clock(seconds)

    /**
     * A duration as «м:сс», in tabular figures, so that its width is a constant.
     *
     * Clamped at [MAX_WINDOW_SECONDS]: past it the string gains a glyph, and the phrase in front of
     * it is laid out right to left off a fixed edge. The trace it names is two minutes long.
     */
    fun clock(seconds: Int): String {
        val bounded = seconds.coerceIn(0, MAX_WINDOW_SECONDS)
        return String.format(Locale.US, "%d:%02d", bounded / 60, bounded % 60)
    }

    /**
     * What is left of a charge, as the petal prints it: «2:15», and «12 ч» once it is hours.
     *
     * A colon rather than a decimal, because this is a clock and not a quantity - and the reason
     * the figures are Roboto with `tnum` rather than Roboto Mono is that a monospaced face gives
     * that colon a full digit cell and breaks «2:15» into three groups.
     *
     * **Above ten hours the minutes go, and that is geometry rather than taste.** The seat reserves
     * three digits and one mark, 100.27 units, which is exactly «2:15» and exactly «16,8»; «12:30»
     * is five glyphs and 129.49, and the extra 29.2 has nowhere to come from. The history box hangs
     * off the widest the field ever gets, its left edge stands 17.3 units clear of the petal's own
     * cut-out, and widening the field would put that edge 12 units *inside* the vehicle's own
     * graphics. An estimate of ten hours or more is a wall socket overnight, where the minutes are
     * noise: «12 ч» measures 99.64 against the field's 100.27 and says the same thing. Anything past
     * [MAX_CHARGE_MINUTES] is a bad read rather than a charge - both ids are gated to 0…99 - and is
     * clamped so the field can never be overrun from the other end either.
     */
    fun chargeLeft(minutes: Int): String {
        val bounded = minutes.coerceIn(0, MAX_CHARGE_MINUTES)
        val hours = bounded / 60
        if (hours >= HOURS_ALONE_FROM) return "$hours $UNIT_HOUR"
        return String.format(Locale.US, "%d:%02d", hours, bounded % 60)
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
