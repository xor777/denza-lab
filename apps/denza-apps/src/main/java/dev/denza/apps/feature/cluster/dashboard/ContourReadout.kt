package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.R
import dev.denza.apps.core.AppDecimal
import dev.denza.apps.core.AppDuration
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
 * Three rules the words follow. **A caption is a name and nothing else** (since 2026-11-19): one
 * case, capitals, at one tracking, and the unit stands small beside its figure - «НАПРЯЖЕНИЕ» over
 * «549 В», «ДВС» over «1518 об/мин», and a line under a figure reads caption, figure, unit:
 * «ЗАРЯД ОТ ДВС 0,4 кВт·ч». Until then units rode in the captions («БАТАРЕЯ · В», «ДВС · об/мин»,
 * «42 км · ЗА ПОЕЗДКУ»). **Units are case-sensitive**: «кВт·ч», «км», «об/мин». A tracked capital
 * is a heading, a unit is not one, and a tracked heading does not get to rewrite ГОСТ 8.417. And **a
 * figure names the window it is true over** - «за 10 км» on the petal, «за поездку» on the
 * engine's minutes - because a number integrated over an interval that does not say which interval
 * is read against the interval the reader brought.
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

    /** «НАПРЯЖЕНИЕ», as the car page says «Напряжение», over the volts and their «В». */
    val TITLE_VOLTS: String get() = AppText.s(R.string.contour_title_volts)

    /** The engine's cell over its revolutions: the car page's «ДВС» in capitals. */
    val TITLE_ENGINE_RPM: String get() = AppText.s(R.string.contour_title_engine_rpm)

    /**
     * The sleeping engine's heading, and the window is in it.
     *
     * «ДВС» over «6 мин» alone was six minutes of *something*: this stop, this hour, this trip, the
     * odometer. It never stands beside the engine's block.
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
     * The trip's caption, a name alone. Its road is a reading of its own - «42 км» at the margin of
     * the detail line under the figure - and leaves alone when the odometer goes stale. Until
     * 2026-11-19 the road was in the caption, «42 км · ЗА ПОЕЗДКУ».
     */
    val CAPTION_TRIP: String get() = AppText.s(R.string.contour_trip)

    /** Drawn after a blue marker dot, which is what says the same thing «ВЕРНУЛА» would have. */
    val CAPTION_REGEN: String get() = AppText.s(R.string.contour_caption_regen)

    /**
     * What the pack took while the engine ran, said once and the same way everywhere: over the
     * engine's block while it is on the panel, and after the trip's figure on its detail line once
     * it has left (`docs/energy-display-contract.md` §2.5, §3).
     *
     * It replaced two phrases that said one thing in two ways - «ДВС ДАЁТ 8 кВт» over the old box,
     * «ДАЛ ДВС» on the trip - and that never moved on the move, because the id behind them reads
     * zero there. No dot: the block's own blue already says «into the pack».
     */
    val CAPTION_ENGINE_CHARGE: String get() = AppText.s(R.string.contour_engine_charge)

    /**
     * How long the run behind the block's figure is: «ЗА 0:40», under the figure.
     *
     * Written as «м:сс», so every value is one length, and clamped at [MAX_WINDOW_SECONDS], past
     * which it would gain a glyph.
     */
    fun engineRun(seconds: Int): String = AppText.s(R.string.contour_engine_run, clock(seconds))

    /**
     * The block's figure: a tenth, and a whole number from ten kilowatt-hours on.
     *
     * The column after the chart is the width of «9,9 кВт·ч»; «12,4 кВт·ч» would run past the
     * group's margin, and a run that long is read in whole kilowatt-hours anyway.
     */
    fun engineKwh(kwh: Double): String = if (kwh < ENGINE_KWH_WHOLE_FROM) tenth(kwh) else whole(kwh)

    /** Where the block's figure drops its tenth: 9,95 prints «10». */
    const val ENGINE_KWH_WHOLE_FROM = 9.95

    val UNIT_KW: String get() = AppText.s(R.string.contour_unit_kw)
    val UNIT_KWH: String get() = AppText.s(R.string.contour_unit_kwh)
    val UNIT_VOLT: String get() = AppText.s(R.string.contour_unit_volt)
    val UNIT_KM: String get() = AppText.s(R.string.contour_unit_km)
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
     * Clamped at [MAX_WINDOW_SECONDS]: past it the string gains a glyph. An engine that runs longer
     * than that is still «9:59» under its block, which is a run long enough to read as long.
     */
    fun clock(seconds: Int): String {
        val bounded = seconds.coerceIn(0, MAX_WINDOW_SECONDS)
        return String.format(Locale.US, "%d:%02d", bounded / 60, bounded % 60)
    }

    /**
     * What is left of a charge, as the petal prints it: «2 ч 15 мин», «45 мин».
     *
     * A duration, so it is [AppDuration]'s, the one rule every duration in the app is printed by:
     * figure-unit pairs, never a clock. Until 2026-11-19 it was «2:15», which beside the sunset's
     * «19:44» on the head unit read as a time of day. Anything past [MAX_CHARGE_MINUTES] is a bad
     * read rather than a charge - both ids are gated to 0…99 - and is clamped.
     */
    fun chargeLeft(minutes: Int): AppDuration.Parts =
        AppDuration.of(minutes.coerceIn(0, MAX_CHARGE_MINUTES) * 60L)

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
