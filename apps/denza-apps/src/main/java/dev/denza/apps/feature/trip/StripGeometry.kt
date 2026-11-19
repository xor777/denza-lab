package dev.denza.apps.feature.trip

import dev.denza.apps.design.luminofor.LuminoforSpec.Head
import dev.denza.apps.design.luminofor.WideDigits
import kotlin.math.floor
import kotlin.math.max

/**
 * Where the strip puts things along a line, as arithmetic and nothing else.
 *
 * `luminofor.js` lays the strip out with two measurements - the wide figures' advance, which is
 * pure arithmetic ([WideDigits]), and Roboto's, which is not - and every horizontal position on
 * both pages follows from them: a row packed from the right edge, a row packed from the left, fixed
 * slots at equal gutters, a cell flush right. This is that arithmetic, lifted out of the drawing
 * code so the renderer and a JVM test run the same lines: the renderer hands it the pen's own
 * measurement, a test hands it a table ([Measure]), and a collision the test finds is a collision
 * the car would have drawn.
 *
 * Everything is in window dp - the spec's own positions - because the renderer draws with its
 * origin on the strip box's corner (`LightPen.begin(…, originX = box.left, originY = box.top)`).
 *
 * The vertical positions are not here: each is one number in `spec.json` and the renderer reads it
 * where it draws. Only what depends on a string's width is.
 */
internal object StripGeometry {

    /** Roboto's advance at a size, in board units. [strong] is the title's weight, 500. */
    fun interface Measure {
        fun sans(text: String, size: Float, strong: Boolean): Float
    }

    /** The board's `Math.round`, which is a half rounded up, on a float. */
    fun round(value: Float): Float = floor(value + 0.5f)

    /** A unit is 0.42 of its figure, rounded to a whole size: 46 takes 19, 56 takes 24. */
    fun unitSize(size: Float): Float = round(size * Head.Reading.UNIT_RATIO)

    /** The variometer's rate is 0.45 of the altitude it follows: 46 takes 21. */
    fun rateSize(size: Float): Float = round(size * Head.Reading.RATE_RATIO)

    /** The arrow's own room before the rate, which the board writes as `ax + 14`. */
    const val ARROW_ROOM = 14f

    /**
     * And the one-third pane's smaller arrow, `ax + 12`, with the arrow `ux + 22` past the unit and
     * the rate's unit 4 past the rate.
     */
    const val ROW_ARROW_ROOM = 12f
    const val ROW_RATE_OFFSET = 22f
    const val ROW_UNIT_GAP = 6f
    const val ROW_RATE_UNIT_GAP = 4f
    const val ROW_SMALL = 14f

    /** Where a caption starts behind the blue mark, which the board writes as `x + 14`. */
    const val MARK_INDENT = 14f

    /**
     * The widest temperature the row prints: a motor at 150°, which reaches as far as a pack at
     * −40° once its one hangs ([WideDigits.lead]). Every pitch holds it two tracking steps clear of
     * the next reading; `StripGeometryTest` holds every width to that.
     */
    const val TEMPERATURE_WIDEST = "150°"

    const val TEMPERATURES = 5

    /**
     * The board's `readingW`: the wider of the caption and the figure's run - figure, unit, arrow
     * and rate. A figure that is not there has taken its unit and its rate with it.
     *
     * **The blue mark is not counted**, exactly as the board does not count it: the caption stands
     * [MARK_INDENT] further right than this width says, and `StripGeometryTest` counts it where it
     * looks for collisions.
     */
    fun readingWidth(r: StripReading, size: Float, label: Float, m: Measure): Float =
        max(runWidth(r, size, m), m.sans(r.caption, label, false))

    /**
     * The figure, its unit and the variometer's arrow, rate and the rate's unit, without the
     * caption. A duration's second pair stands a pair's gap after the first one's unit.
     */
    fun runWidth(r: StripReading, size: Float, m: Measure): Float {
        val figure = r.figure ?: return 0f
        var w = WideDigits.extent(figure, size)
        r.unit?.let { w += size * Head.Reading.UNIT_GAP_RATIO + m.sans(it, unitSize(size), false) }
        r.figure2?.let { second ->
            w += size * Head.Reading.PAIR_GAP_RATIO + WideDigits.extent(second, size)
            r.unit2?.let { w += size * Head.Reading.UNIT_GAP_RATIO + m.sans(it, unitSize(size), false) }
        }
        r.rate?.let { w += size * Head.Reading.RATE_GAP_RATIO + ARROW_ROOM + WideDigits.extent(it, rateSize(size)) }
        r.rateUnit?.let { w += size * Head.Reading.RATE_UNIT_GAP_RATIO + m.sans(it, unitSize(size), false) }
        return w
    }

    // ------------------------------------------------------------------------------ sound page

    /**
     * The full screen's trip readings, packed from the right edge [Head.Full.Strip.READING_GAP]
     * apart: the board's `for (k = last … 0) { reading(x - w); x -= w + gap }`. Their left edges go
     * into [out]; returns the row's own left edge, or [right] with no readings at all.
     *
     * Packed from the right, so a reading that is not there takes no room and the ones that are keep
     * the edge: the sun at the page's right margin whether or not the altitude has answered.
     */
    fun fullTrip(model: StripModel, right: Float, m: Measure, out: FloatArray): Float {
        var x = right
        for (k in model.tripCount - 1 downTo 0) {
            val w = readingWidth(model.trip[k], Head.Full.Strip.VALUE_SIZE, Head.Full.Strip.LABEL_SIZE, m)
            out[k] = x - w
            x -= w + Head.Full.Strip.READING_GAP
        }
        return if (model.tripCount == 0) right else out[0]
    }

    /**
     * The title's room on the full screen: from the page margin to one reading gap short of the
     * row. The title is cut to this with an ellipsis, so a long one cannot reach the readings.
     */
    fun fullTitleRoom(left: Float, right: Float, rowLeft: Float, hasRow: Boolean): Float =
        (if (hasRow) rowLeft - Head.Full.Strip.READING_GAP else right) - left

    /**
     * The two-thirds pane's readings, in a row from the left [Head.Two.Sound.GAP] apart - closer,
     * the same for every gap, when long values would run the row past [right]: the board's
     * `Math.min(s.gap, (Wd - sum) / (n - 1))`. Returns where the last one ends.
     */
    fun twoTrip(model: StripModel, left: Float, right: Float, m: Measure, out: FloatArray): Float {
        val s = Head.Two.Sound
        val n = model.tripCount
        var sum = 0f
        for (k in 0 until n) {
            out[k] = readingWidth(model.trip[k], s.VALUE_SIZE, s.LABEL_SIZE, m)
            sum += out[k]
        }
        val gap = minOf(s.GAP, (right - left - sum) / max(1, n - 1))
        var x = left
        var end = left
        for (k in 0 until n) {
            val w = out[k]
            out[k] = x
            end = x + w
            x += w + gap
        }
        return end
    }

    // ------------------------------------------------------------------------------ the foot

    /**
     * The least field an analyser keeps between its top and its floor, headroom aside: below it a
     * column is too short to be read as a level rather than a tick.
     */
    const val MIN_FIELD = 80f

    /** And between the chart's foot and the page dots, which rise with the box's foot. */
    const val DOTS_CLEARANCE = 8f

    /**
     * The least box, in dp, both pages of a composition fit.
     *
     * Everything hangs from the box's top except the analyser's floor and the dots, which keep
     * their distance from its bottom (spec, `anchoring`). So a box shorter than the board's moves
     * those two up and nothing else, and it can move them until the dots meet the car page's chart
     * - which hangs from the top - or the analyser's floor comes within [MIN_FIELD] of its top.
     * This is that height; `StripGeometryTest` holds every composition's board box above it.
     */
    fun minimumHeight(layout: TripPanelLayout): Float {
        val box = TripPanelRenderer.box(layout)
        val chartFoot: Float
        val spectrumTop: Float
        val floor: Float
        when (layout) {
            TripPanelLayout.WIDE -> {
                chartFoot = Head.Full.Car.CHART_TOP + Head.Full.Car.CHART_HEIGHT
                spectrumTop = Head.Full.Strip.SPECTRUM_TOP
                floor = Head.Full.Strip.FLOOR
            }
            TripPanelLayout.MEDIUM -> {
                chartFoot = Head.Two.Car.CHART_TOP + Head.Two.Car.CHART_HEIGHT
                spectrumTop = Head.Two.Sound.SPECTRUM_TOP
                floor = Head.Two.Sound.FLOOR
            }
            TripPanelLayout.NARROW -> {
                chartFoot = Head.One.Car.CHART_TOP + Head.One.Car.CHART_HEIGHT
                spectrumTop = Head.One.Sound.SPECTRUM_TOP
                floor = Head.One.Sound.FLOOR
            }
        }
        val dots = TripPanelRenderer.dotsY(layout)
        val byChart = chartFoot + DOTS_CLEARANCE + TripPanelRenderer.DOT_RADIUS - dots
        val byField = spectrumTop + Head.Spectrum.HEADROOM + MIN_FIELD - floor
        return box.bottom - box.top + max(byChart, byField)
    }

    // -------------------------------------------------------------------------------- car page

    /**
     * Where the car page's cells stand; `NaN` for a cell that is not on the page. The trip ends on
     * the strip's right edge, so its caption and its run each have their own left edge:
     * [tripCell] is the caption's, [tripRun] the figure's.
     */
    class CarPlacement {
        var power = Float.NaN
        var volts = Float.NaN
        var temps = Float.NaN
        var engine = Float.NaN
        var tripCell = Float.NaN
        var tripRun = Float.NaN
    }

    /** The five temperatures' width on a row of [pitch]: four pitches and the widest reading. */
    fun temperaturesWidth(pitch: Float, size: Float): Float =
        (TEMPERATURES - 1) * pitch + WideDigits.extent(TEMPERATURE_WIDEST, size)

    /**
     * The full screen, as the board's `drawHead` lays it out: five slots ([Head.Slots]) with one
     * gutter between them - the volts on the left margin, the temperatures, the power, the engine,
     * and the trip ending on the right margin. Each cell stands at its slot's left edge, so nothing
     * moves when a reading or its sentence changes; the power's two-figure reading lands on the
     * page's centre.
     */
    fun fullCar(model: StripModel, left: Float, right: Float, m: Measure, out: CarPlacement) {
        val c = Head.Full.Car
        val slot = Head.Slots
        val temps = temperaturesWidth(c.TEMP_PITCH, c.TEMP_SIZE)
        val gutter = fullGutter(left, right)
        out.volts = left
        out.temps = left + slot.VOLTS + gutter
        out.power = out.temps + temps + gutter
        out.engine = if (model.engine.present) out.power + slot.POWER + gutter else Float.NaN
        trip(model, right, Head.Full.Strip.VALUE_SIZE, Head.Full.Strip.LABEL_SIZE, m, out)
    }

    /** The full screen's one gutter between two slots: what the five leave of the width, in four. */
    fun fullGutter(left: Float, right: Float): Float {
        val slot = Head.Slots
        val temps = temperaturesWidth(Head.Full.Car.TEMP_PITCH, Head.Full.Car.TEMP_SIZE)
        return (right - left - slot.VOLTS - temps - slot.POWER - slot.ENGINE - slot.TRIP) / 4f
    }

    /**
     * The two-thirds pane: the full screen's power, engine and trip slots across the pane - the
     * power on the left margin, the trip ending on the right, the engine a gutter from each - and
     * under them the volts on the margin and the temperatures in the engine's column.
     */
    fun twoCar(model: StripModel, left: Float, right: Float, m: Measure, out: CarPlacement) {
        val c = Head.Two.Car
        val slot = Head.Slots
        val column = left + slot.POWER + (right - left - slot.POWER - slot.ENGINE - slot.TRIP) / 2f
        out.power = left
        out.engine = if (model.engine.present) column else Float.NaN
        trip(model, right, c.VALUE_SIZE, Head.Two.Sound.LABEL_SIZE, m, out)
        out.volts = left
        out.temps = column
    }

    /** The trip's caption and its run, each ending on [right]. */
    private fun trip(model: StripModel, right: Float, size: Float, label: Float, m: Measure, out: CarPlacement) {
        if (!model.tripCell.present) {
            out.tripCell = Float.NaN
            out.tripRun = Float.NaN
            return
        }
        out.tripCell = right - m.sans(model.tripCell.caption, label, false)
        out.tripRun = right - runWidth(model.tripCell, size, m)
    }

    /**
     * The one-third pane: the power on its own line, the volts and the engine beside each other
     * under it, the five temperatures under those. There is no trip cell at this width - the board
     * has none, and the chart under the row is what the width is spent on.
     */
    fun oneCar(model: StripModel, left: Float, m: Measure, out: CarPlacement) {
        val c = Head.One.Car
        val label = Head.One.Sound.LABEL_SIZE
        out.power = left
        out.volts = left
        out.engine = if (model.engine.present) {
            left + readingWidth(model.volts, c.ROW2_SIZE, label, m) + c.ROW2_GAP
        } else {
            Float.NaN
        }
        out.temps = left
        out.tripCell = Float.NaN
    }
}
