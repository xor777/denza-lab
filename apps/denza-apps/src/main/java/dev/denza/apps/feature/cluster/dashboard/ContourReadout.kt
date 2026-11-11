package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.R
import dev.denza.apps.core.AppDecimal
import dev.denza.apps.core.AppText
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
 * Every caption here is written exactly as the Luminofor board prints it
 * (`tools/design-canvas/luminofor/fixtures.js`, exported to the debug build's `fixtures.json`),
 * and `ContourFixturesContractTest` holds the two records together: a word on the board that the
 * app does not print, or the other way round, fails there. Changing a caption is a design change.
 *
 * Two rules the words follow. **Units are case-sensitive**: «БАТАРЕЯ · В», «ДВС · об/мин»,
 * «кВт·ч», «км». A tracked capital is a heading, a unit is not one, and a tracked heading does not
 * get to rewrite ГОСТ 8.417. And **a figure names the window it is true over** - «за 10 км» on the
 * petal, «за поездку» on the engine's minutes - because a number integrated over an interval that
 * does not say which interval is read against the interval the reader brought.
 *
 * Everything takes plain numbers rather than a telemetry snapshot, so a test states the case it
 * means instead of assembling a car around it.
 *
 * The words read `strings.xml` through [AppText] each time they are asked for, some of them on every
 * frame. That is an array read after the first time ([AppText.s]). A sentence with a figure in it
 * formats, so it is asked for only when the figure changes ([ContourFigures]).
 */
internal object ContourReadout {

    // ---- the words

    val TITLE_PACK: String get() = AppText.s(R.string.contour_title_pack)
    val TITLE_ENGINE_RPM: String get() = AppText.s(R.string.contour_title_engine_rpm)

    /**
     * The sleeping engine's heading, and the window is in it.
     *
     * «ДВС · мин» alone was six minutes of *something*: this stop, this hour, this trip, the
     * odometer. The aperture leaves 250.1 units at this baseline and the words take 224.9.
     */
    val TITLE_ENGINE_MINUTES: String get() = AppText.s(R.string.contour_title_engine_minutes)

    /**
     * The spread of *what* is exactly the question that started the sixth pass.
     *
     * And since the ninth it is the **only** word in the temperature row: `БАТАРЕЯ`, `МОТОРЫ` and
     * `ИНВЕРТОР` are gone, replaced by five glyphs - `ThermalGlyphs` since the Luminofor board.
     * Which means a word there now means the pack is misbehaving, which is worth more than the
     * three captions cost.
     */
    val CAPTION_SPREAD: String get() = AppText.s(R.string.contour_caption_spread)

    /**
     * The odometer's figure, its unit and what they are the road of, as one sentence.
     *
     * Rebuilt only when the figure's own string changes: `ContourFrameBuilder`.
     */
    fun tripKm(figure: String): String = AppText.s(R.string.contour_trip_km, figure)

    /**
     * And what is left of it when the odometer has gone stale.
     *
     * The separator belongs to the figure in front of it, so it leaves with it: a «·» standing at
     * the head of a cell is a phrase missing its first half rather than a caption.
     */
    val CAPTION_TRIP_ALONE: String get() = AppText.s(R.string.contour_trip_alone)

    /** Drawn after a blue marker dot, which is what says the same thing «ВЕРНУЛА» would have. */
    val CAPTION_REGEN: String get() = AppText.s(R.string.contour_caption_regen)

    /** A verb, and it fits: 94.4 units against a payload of 116.1. */
    val CAPTION_ENGINE_GAVE: String get() = AppText.s(R.string.contour_caption_engine_gave)

    /**
     * The whole of what stands under the engine's box: a sentence, not a legend.
     *
     * The seventh pass wrote `ОБОРОТЫ · ● ГЕНЕРАЦИЯ 14 кВт` under two runs, and the owner's verdict
     * on the built panel was that it was not understandable - which is the game lost, because a key
     * is what a display read at 90 km/h does not get to need. One run left, one sentence: what the
     * shape is, what it is worth now, and how far back it goes, in that order. The words «ГЕНЕРАЦИЯ»
     * and «ОБОРОТЫ» are on no part of this panel any more.
     *
     * **And the sentence says «даёт» rather than «В БАТАРЕЮ»** since the energy display contract
     * (§2.5). Where `GENERATION_KW` goes in motion has never been recorded - the two drives so far
     * saw the engine run with the id flat - so «В БАТАРЕЮ» was a claim about a signal nobody has
     * logged. «ДВС ДАЁТ» is true under either meaning and is the same verb the trip's «ДАЛ ДВС»
     * uses; there is no dot, because the blue mark means «into the pack» everywhere else here.
     */
    val LEGEND_PREFIX: String get() = AppText.s(R.string.contour_engine_gives_alone)

    /** The same sentence with what it is giving now, in kilowatts. */
    fun engineGives(figure: String): String = AppText.s(R.string.contour_engine_gives, figure)

    /**
     * **And the window is the box's own reach rather than a literal.**
     *
     * «ПОСЛЕДНИЕ 2 МИН» was printed from the first second of an engine run, over a box one step
     * wide: the trace grows from the right and is never front-padded, so two minutes is what it
     * holds when it is full and nothing else. The figure beside it was honest about a five-second
     * window and the words were not. It is written as «м:сс», so every value is one length.
     *
     * Since the Luminofor board it is a line of its own under the box - the caption line carries
     * «ДВС ДАЁТ 14 кВт» over the box and this sits on the detail line under it - so it no longer
     * leads with the «·» that joined it to the sentence.
     *
     * @param seconds the trace's own span, which is the box's own width in seconds
     */
    fun intoPack(seconds: Int): String = AppText.s(R.string.contour_engine_window, clock(seconds))

    val UNIT_KW: String get() = AppText.s(R.string.contour_unit_kw)
    val UNIT_KWH: String get() = AppText.s(R.string.contour_unit_kwh)
    val UNIT_MILLIVOLT: String get() = AppText.s(R.string.contour_unit_millivolt)
    const val DEGREE = "°"

    /** Both charging ids are gated to 0…99, so anything past this is a bad read, not a charge. */
    const val MAX_CHARGE_MINUTES = 99 * 60 + 59

    /** What replaces it while a gun is in, over a figure that is a duration rather than a rate. */
    val UNIT_CHARGE_LEFT: String get() = AppText.s(R.string.contour_charge_left)

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

    // ---- the numbers

    /** The odometer arrives in tenths and the differences accumulate in doubles. */
    private const val KM_EPSILON = 1e-6

    /** A whole number, and never a dash: a value that is not there is not drawn at all. */
    fun whole(value: Double): String = unsigned(String.format(Locale.US, "%.0f", value))

    /** A number with a decimal mark, the way every other panel in this app writes one. */
    fun tenth(value: Double): String =
        AppDecimal.written(unsigned(String.format(Locale.US, "%.1f", value)))

    /**
     * A minus in front of nothing but zeros is not a direction, so it does not get printed.
     *
     * `%.0f` of −0.4 is «-0» and `%.1f` of −0.04 is «-0,0», and the one signed figure on either
     * screen is the consumption - so a hundred metres of coasting downhill printed «-0» in the
     * return's blue and said the road had given something back. Rounding a magnitude away is what
     * makes it zero; the sign is what is left of a quantity that no longer has one.
     */
    private fun unsigned(printed: String): String =
        if (printed.startsWith('-') && printed.none { it in '1'..'9' }) printed.substring(1) else printed

    /**
     * The petal's figure: a whole number on the move, a tenth on P.
     *
     * At 100 km/h a tenth of a kilowatt-hour per hundred kilometres changes three times a second,
     * and a figure that flickers is a figure nobody reads (m5). Standing still it is worth the
     * resolution, and the denominator does not change underneath it either way - the window is
     * always the last ten kilometres, and since the seventh pass the unit says so.
     *
     * Signed: a long descent returns more than it costs and prints its minus, which is the one
     * signed figure on either screen (`docs/energy-display-contract.md` §2.2).
     */
    fun consumption(perHundredKm: Double, parked: Boolean): String =
        if (parked) tenth(perHundredKm) else whole(perHundredKm)

    /**
     * The petal's unit, naming the road the figure beside it is actually the mean of - and the car
     * page's, which prints the same string after «Расход» and the same figure.
     *
     * A full window is written whole, a filling one to a tenth, and **never as a whole-number
     * rounding of a filling window**: «за 4 км» over 3.7 km of road is the defect the window was
     * added to fix, one level down.
     *
     * @param coveredKm the known road the figure beside it is actually the mean of
     * @param windowKm what the window holds once it is full
     */
    fun perHundredKm(coveredKm: Double, windowKm: Double): String {
        val figure =
            if (coveredKm >= windowKm - KM_EPSILON) whole(windowKm) else tenth(coveredKm)
        return AppText.s(R.string.contour_per_100km, figure)
    }

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
     * What is left of a charge, as the petal prints it: «2:15», and «12:30» once it is hours.
     *
     * A colon rather than a decimal, because this is a clock and not a quantity.
     *
     * **Always «ч:мм», through 99:59.** Until the Luminofor board an estimate of ten hours or more
     * was «12 ч»: the seat reserved three digits and one mark, and a fifth glyph had nowhere to go.
     * The board's figure is left-aligned with its unit following it, so a fifth glyph is room the
     * petal has - and the wide figures have no «ч» to print. Anything past [MAX_CHARGE_MINUTES] is
     * a bad read rather than a charge - both ids are gated to 0…99 - and is clamped.
     */
    fun chargeLeft(minutes: Int): String {
        val bounded = minutes.coerceIn(0, MAX_CHARGE_MINUTES)
        return String.format(Locale.US, "%d:%02d", bounded / 60, bounded % 60)
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
}
