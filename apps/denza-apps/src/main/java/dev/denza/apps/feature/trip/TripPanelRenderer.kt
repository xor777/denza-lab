package dev.denza.apps.feature.trip

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The single trip panel screen, in the three widths the car gives this app.
 *
 * The analyser ([SpectrumRenderer]) and the same three readings every time - how long and how far,
 * how high and which way, and the next thing the sun does. What changes is how they are arranged
 * and, more to the point, what space they are laid out in.
 *
 * **Each width is laid out one unit to one dp.** [PanelCanvas] scales a virtual space onto the box
 * it is given, and that is exactly the wrong thing to do to type: the two-thirds pane used to take
 * the wide composition, whose space is 1184 wide, and have it squeezed into 732 - so the ladder's
 * 46, 24 and 15 arrived on the screen at 28, 15 and 9. The bottom rung of this app's type ladder is
 * 15 because eleven and twelve point captions are legible on a desk and not in a car. So a pane has
 * its own space, at its own size, and the scale factor is 1.
 *
 * The figures change shape rather than size. At 1280 there is a 320-wide column beside the analyser
 * and they hang apart down it as blocks, a tracked capital over a large light figure. In a pane
 * there is no such column, so each becomes one row: the label at the left, the reading at the
 * right. Same three readings, same two type sizes, one line each.
 */
class TripPanelRenderer : BaseTripRenderer() {

    /**
     * The column's own two faces, because the shared helpers have neither.
     *
     * [PanelCanvas.value] draws monospace, which is right for a figure that ticks every frame and
     * wrong here: the board sets these in Roboto Light, and a proportional face is what makes
     * "0:00" and "209" read as typography rather than as a readout. The label is the board's
     * medium weight with its 1.6-over-15 tracking expressed as an em value, which is what a text
     * paint can actually do - the first attempt padded the string with thin spaces.
     */
    private val figurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    }
    private val capsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = CAPS_TRACKING
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF }

    private val spectrumRenderer = SpectrumRenderer()
    private val vehicleRenderer = VehiclePageRenderer()

    internal override fun draw(
        canvas: Canvas,
        w: Float,
        h: Float,
        engine: TripEngine,
        spectrum: SpectrumSource,
        nowPlaying: NowPlayingSource,
        frameTimeSec: Double,
        dtSec: Double,
        showLocationHint: Boolean,
        layout: TripPanelLayout,
        page: StripPage,
        vehicle: VehicleTelemetry,
    ) {
        when (layout) {
            TripPanelLayout.MEDIUM, TripPanelLayout.NARROW -> {
                drawPane(
                    canvas = canvas,
                    width = w,
                    height = h,
                    layout = layout,
                    engine = engine,
                    spectrum = spectrum,
                    nowPlaying = nowPlaying,
                    frameTimeSec = frameTimeSec,
                    dtSec = dtSec,
                    showLocationHint = showLocationHint,
                    page = page,
                    vehicle = vehicle,
                )
                return
            }
            TripPanelLayout.WIDE -> Unit
        }
        setSize(w, h, WIDE_VIRTUAL_W, WIDE_VIRTUAL_H)
        drawField(
            canvas, page, spectrum, nowPlaying, vehicle, frameTimeSec, dtSec,
            left = SPECTRUM_LEFT, right = SPECTRUM_RIGHT,
            top = SPECTRUM_TOP, bottom = SPECTRUM_BOTTOM,
            narrow = false,
        )
        drawColumn(canvas, engine)
        if (showLocationHint) {
            // The column's GNSS blocks are absent without a fix, so its lower half is empty and
            // the hint has somewhere to go that collides with nothing.
            label(
                canvas, LOCATION_HINT, vx(COLUMN_X), vy(HINT_Y), HINT_SIZE,
                TripPalette.alpha(TripPalette.MUTED, 0.85f),
            )
        }
    }

    /**
     * A pane: the analyser with everything the chips left it, and three readings at its foot.
     *
     * `TwoThirds.dc.html` and `OneThird.dc.html`. One function draws both, and what differs is how
     * much room there is beside a figure: at 788 dp the three stand side by side with a rule
     * between them, at 392 they become rows.
     *
     * **The virtual height is taken from the box rather than declared.** [PanelCanvas] scales x and
     * y independently, so a fixed virtual space in a box of another shape distorts everything in
     * it - and a *uniform* scale is no better here, because it takes the type off the ladder: the
     * wide composition squeezed into a two-thirds pane put the ladder's 46, 24 and 15 on the screen
     * at 28, 15 and 9. Deriving the height from the width keeps the two scale factors equal at one
     * unit to one dp, whatever the caller had left to give - which is what lets the strip simply
     * take the remainder instead of computing it, and computing it is what put the last cut's foot
     * past the bottom of the window.
     */
    private fun drawPane(
        canvas: Canvas,
        width: Float,
        height: Float,
        layout: TripPanelLayout,
        engine: TripEngine,
        spectrum: SpectrumSource,
        nowPlaying: NowPlayingSource,
        frameTimeSec: Double,
        dtSec: Double,
        showLocationHint: Boolean,
        page: StripPage,
        vehicle: VehicleTelemetry,
    ) {
        val across = layout == TripPanelLayout.MEDIUM
        val virtualW = if (across) MEDIUM_VIRTUAL_W else NARROW_VIRTUAL_W
        val virtualH = height * virtualW / width
        setSize(width, height, virtualW, virtualH)

        val figuresH = if (across) PANE_BLOCK else PANE_ROWS
        val figuresTop = virtualH - figuresH
        val analyserBottom = (figuresTop - PANE_GROUP).coerceAtLeast(PANE_MIN_ANALYSER)

        drawField(
            canvas, page, spectrum, nowPlaying, vehicle, frameTimeSec, dtSec,
            left = 0f, right = virtualW, top = 0f, bottom = analyserBottom,
            narrow = layout == TripPanelLayout.NARROW,
        )

        if (across) {
            drawPaneBlocks(canvas, engine, virtualW, figuresTop, showLocationHint)
        } else {
            drawPaneRows(canvas, engine, virtualW, figuresTop, showLocationHint)
        }
    }

    /**
     * How much of the strip's width the field has, which is what a swipe is taken on.
     *
     * At 1280 the analyser is 832 of the 1184 the page has and the trip's three figures are the
     * rest; a pane gives the field the whole width and stacks the figures under it. The view asks
     * rather than assuming, because these two numbers have moved once already and a gesture
     * measured against a stale one is a gesture that fires over somebody else's readings.
     */
    fun fieldFraction(layout: TripPanelLayout): Float = when (layout) {
        TripPanelLayout.WIDE -> SPECTRUM_RIGHT / WIDE_VIRTUAL_W
        TripPanelLayout.MEDIUM, TripPanelLayout.NARROW -> 1f
    }

    /**
     * The field, whichever of its two pages is up, and the dots that say there are two.
     *
     * **The dots cost the field its bottom [DOTS] and nothing else costs anything.** They are at
     * the foot on both pages, so nothing moves when the page does, and the analyser is laid out in
     * what is left - [SpectrumRenderer] puts its ticker, its bars and their reflection at fractions
     * of the box it is handed, so a shorter box is the same analyser at a smaller size rather than
     * a clipped one. At 1280 that is 203 units of bars becoming 186.
     *
     * The three trip figures are not a page and are not drawn here: they are true on both.
     */
    private fun drawField(
        canvas: Canvas,
        page: StripPage,
        spectrum: SpectrumSource,
        nowPlaying: NowPlayingSource,
        vehicle: VehicleTelemetry,
        frameTimeSec: Double,
        dtSec: Double,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        narrow: Boolean,
    ) {
        val foot = bottom - DOTS
        when (page) {
            StripPage.SOUND -> spectrumRenderer.draw(
                canvas, spectrum, nowPlaying, frameTimeSec, dtSec,
                left = vx(left), right = vx(right),
                top = vy(top), bottom = vy(foot), unit = vs(1f),
            )

            StripPage.VEHICLE -> vehicleRenderer.draw(
                canvas, vehicle,
                left = vx(left), top = vy(top),
                right = vx(right), bottom = vy(foot),
                unit = vs(1f), narrow = narrow,
            )
        }
        drawDots(canvas, (left + right) / 2f, foot + DOTS / 2f, page)
    }

    /**
     * Two dots, and the whole of the affordance.
     *
     * A gesture nobody can see is a gesture nobody uses, and this strip answered no touch at all
     * until now. The pager that was here before had four pages and no indicator, which is what it
     * was deleted for; this says how many pages there are and which one this is, whether or not
     * anybody swipes.
     */
    private fun drawDots(canvas: Canvas, centreX: Float, centreY: Float, page: StripPage) {
        val radius = vs(DOT / 2f)
        val pitch = vs(DOT + DOT_GAP)
        val first = vx(centreX) - pitch / 2f
        StripPage.entries.forEachIndexed { index, entry ->
            fill.color =
                if (entry == page) TripPalette.LIVE
                else TripPalette.alpha(TripPalette.MUTED, DOT_IDLE_ALPHA)
            canvas.drawCircle(first + index * pitch, vy(centreY), radius, fill)
        }
    }

    /**
     * Three readings side by side with a rule between them, where there is width for it.
     *
     * The same block the full screen's column draws - a tracked capital over a large light figure
     * with its unit beside it - turned through ninety degrees. The archived board under this name
     * set its three numbers this way and it was the right instinct: a rule between two figures says
     * they are separate readings, where a gap alone reads as one sentence that got spaced out.
     */
    private fun drawPaneBlocks(
        canvas: Canvas,
        engine: TripEngine,
        width: Float,
        top: Float,
        showLocationHint: Boolean,
    ) {
        val cell = (width - 2f * (PANE_RULE + PANE_GROUP)) / 3f
        val pitch = cell + PANE_RULE + PANE_GROUP
        for (index in 1..2) {
            val x = vx(index * pitch - PANE_GROUP / 2f)
            hairline(canvas, x, vy(top), x, vy(top + PANE_BLOCK))
        }

        val guidance = engine.guidance()
        if (guidance != null) {
            val parts = buildList {
                guidance.distanceMeters?.let { add(distanceLabel(it.toDouble())) }
                guidance.timeSeconds?.let { add(clockHm(it.toLong())) }
            }
            paneBlock(canvas, 0f, top, "ОСТАЛОСЬ", parts.joinToString(" · ").ifBlank { "—" }, "")
        } else {
            val elapsed = engine.elapsedSeconds
            val timePart = if (elapsed >= 3600) clockHm(elapsed.toLong()) else clockMs(elapsed)
            paneBlock(
                canvas, 0f, top, "В ПУТИ", timePart, distanceLabel(engine.distanceMeters()),
            )
        }

        if (engine.hasAltitude()) {
            val run = paneBlock(
                canvas, pitch, top, "ВЫСОТА",
                "${engine.smoothedAltitude().roundToInt()}", "м",
            )
            val rate = engine.variometer()
            variometer(
                canvas,
                vx(pitch) + run + vs(UNIT_GAP) + variometerWidth(rate, PANE_RATE),
                vy(top + PANE_BLOCK_FIGURE), rate, PANE_RATE, ARROW_SIZE,
            )
        } else if (showLocationHint) {
            label(
                canvas, LOCATION_HINT, vx(pitch), vy(top + PANE_BLOCK_LABEL), PANE_LABEL,
                TripPalette.alpha(TripPalette.MUTED, 0.85f),
            )
        }

        val sun = engine.sunInfo()
        if (sun.nextEventLabel.isNotEmpty()) {
            // The same block as its two neighbours, in the same ink. See [drawColumn].
            paneBlock(
                canvas, 2 * pitch, top,
                if (sun.nextIsSunset) "ЗАКАТ" else "РАССВЕТ", sun.nextEventLabel, "",
            )
        }
    }

    /**
     * A tracked capital over a large light figure, with its unit against it; returns the width of
     * the figure and its unit.
     */
    private fun paneBlock(
        canvas: Canvas,
        x: Float,
        top: Float,
        name: String,
        value: String,
        unit: String,
    ): Float {
        caps(canvas, name, vx(x), vy(top + PANE_BLOCK_LABEL))
        val width = figure(
            canvas, value, vx(x), vy(top + PANE_BLOCK_FIGURE), FIGURE_SIZE, TripPalette.INK,
        )
        if (unit.isEmpty()) return width
        val gap = vs(UNIT_GAP)
        unitPaint.textSize = vs(UNIT_SIZE)
        unit(canvas, unit, vx(x) + width + gap, vy(top + PANE_BLOCK_FIGURE), UNIT_SIZE)
        return width + gap + unitPaint.measureText(unit)
    }

    /**
     * Three readings as rows, where there is not.
     *
     * The second and third exist only when the car has told us where it is, so on a cold start
     * this is one row - and the "no location" hint goes where the second would have been, which is
     * the same place the wide layout puts it and for the same reason: it collides with nothing
     * because nothing is there.
     *
     * **The readings share a left edge.** They used to be hung off the right of the pane, which
     * lines up the last character of a clock with the last character of a distance and puts the
     * digits that matter in three different places - three readings you cannot compare with your
     * eye, which is the one thing a stack of rows is for. The label column is [PANE_LABEL_COLUMN]
     * and is the same whichever words the row is carrying today.
     */
    private fun drawPaneRows(
        canvas: Canvas,
        engine: TripEngine,
        width: Float,
        top: Float,
        showLocationHint: Boolean,
    ) {
        val x = vx(0f)
        val valueX = x + vs(PANE_LABEL_COLUMN)

        val guidance = engine.guidance()
        if (guidance != null) {
            val parts = buildList {
                guidance.distanceMeters?.let { add(distanceLabel(it.toDouble())) }
                guidance.timeSeconds?.let { add(clockHm(it.toLong())) }
            }
            paneRow(
                canvas, x, valueX, rowBaseline(top, 0),
                "ОСТАЛОСЬ", parts.joinToString(" · ").ifBlank { "—" },
            )
        } else {
            val elapsed = engine.elapsedSeconds
            val timePart = if (elapsed >= 3600) clockHm(elapsed.toLong()) else clockMs(elapsed)
            paneRow(
                canvas, x, valueX, rowBaseline(top, 0),
                "В ПУТИ", "$timePart · ${distanceLabel(engine.distanceMeters())}",
            )
        }

        if (engine.hasAltitude()) {
            val baseline = rowBaseline(top, 1)
            val run = paneRow(
                canvas, x, valueX, baseline,
                "ВЫСОТА", "${engine.smoothedAltitude().roundToInt()} м",
            )
            val rate = engine.variometer()
            variometer(
                canvas,
                valueX + run + vs(UNIT_GAP) + variometerWidth(rate, PANE_RATE),
                baseline, rate, PANE_RATE, ARROW_SIZE,
            )
        } else if (showLocationHint) {
            label(
                canvas, LOCATION_HINT, x, rowBaseline(top, 1), PANE_LABEL,
                TripPalette.alpha(TripPalette.MUTED, 0.85f),
            )
        }

        val sun = engine.sunInfo()
        if (sun.nextEventLabel.isNotEmpty()) {
            paneRow(
                canvas, x, valueX, rowBaseline(top, 2),
                if (sun.nextIsSunset) "ЗАКАТ" else "РАССВЕТ", sun.nextEventLabel,
            )
        }
    }

    /**
     * A tracked capital in the label column and its reading beside it, both on one baseline;
     * returns how wide the reading came out.
     */
    private fun paneRow(
        canvas: Canvas,
        left: Float,
        valueX: Float,
        baseline: Float,
        label: String,
        value: String,
    ): Float {
        capsPaint.textSize = vs(PANE_LABEL)
        capsPaint.color = TripPalette.MUTED
        canvas.drawText(label, left, baseline, capsPaint)
        return figure(canvas, value, valueX, baseline, PANE_VALUE, TripPalette.INK)
    }

    private fun rowBaseline(top: Float, index: Int): Float =
        vy(top + index * (PANE_ROW + PANE_ROW_GAP) + PANE_BASELINE)

    /**
     * The figures beside the analyser, as `Main.dc.html` draws them.
     *
     * Three blocks, not four. The board hangs them apart down the full height with a hairline
     * above the second and the third, gives each a small tracked capital label and a large light
     * figure, and lets the unit sit beside the figure rather than under it. The version this
     * replaces had four blocks at four fixed heights in 16 and 27 point type - two sizes that are
     * on no ladder in this app - and packed them into the top two thirds with nothing below.
     *
     * The trip's total climb was the fourth block and is gone. The variometer beside the altitude
     * already says which way the road is going, which is the part that is worth a glance while
     * driving; a running total of metres climbed is a number for afterwards.
     */
    private fun drawColumn(canvas: Canvas, engine: TripEngine) {
        val x = vx(COLUMN_X)

        val guidance = engine.guidance()
        if (guidance != null) {
            val parts = buildList {
                guidance.distanceMeters?.let { add(distanceLabel(it.toDouble())) }
                guidance.timeSeconds?.let { add(clockHm(it.toLong())) }
            }
            block(canvas, x, BLOCK_TOP_1, "ОСТАЛОСЬ", parts.joinToString(" · "), "")
        } else {
            val elapsed = engine.elapsedSeconds
            val timePart = if (elapsed >= 3600) clockHm(elapsed.toLong()) else clockMs(elapsed)
            block(canvas, x, BLOCK_TOP_1, "В ПУТИ", timePart, distanceLabel(engine.distanceMeters()))
        }

        if (engine.hasAltitude()) {
            hairline(canvas, x, vy(BLOCK_TOP_2), vx(COLUMN_RIGHT), vy(BLOCK_TOP_2))
            val run = block(
                canvas, x, BLOCK_TOP_2 + RULE_GAP, "ВЫСОТА",
                "${engine.smoothedAltitude().roundToInt()}", "м",
            )
            // Flush against the reading, not hung off the far edge. The rate belongs to the
            // altitude beside it; pushed to the right edge of a cell it reads as a second column,
            // and in the two-thirds pane it ended up pinned against the rule between two readings.
            val rate = engine.variometer()
            variometer(
                canvas,
                x + run + vs(UNIT_GAP) + variometerWidth(rate, ARROW_TEXT),
                vy(BLOCK_TOP_2 + RULE_GAP + FIGURE_BASELINE),
                rate,
            )
        }

        val sun = engine.sunInfo()
        if (sun.nextEventLabel.isNotEmpty()) {
            hairline(canvas, x, vy(BLOCK_TOP_3), vx(COLUMN_RIGHT), vy(BLOCK_TOP_3))
            // The third block is the other two, and nothing else. It used to be a sun in the
            // vehicle's own amber, a 34 where its neighbours were 46, and its word set as a unit
            // rather than as a label - so the one caption on this screen drawn in a colour was the
            // one saying that the sun goes down at the usual time. Amber is what the car draws
            // when it wants a decision from the driver. The label already says which event it is.
            block(
                canvas, x, BLOCK_TOP_3 + RULE_GAP,
                if (sun.nextIsSunset) "ЗАКАТ" else "РАССВЕТ", sun.nextEventLabel, "",
            )
        }
    }

    /**
     * A tracked capital label with a large light figure and its unit beside it; returns how wide
     * the figure and its unit came out, so anything else on that line can sit against them.
     */
    private fun block(
        canvas: Canvas,
        x: Float,
        top: Float,
        name: String,
        figure: String,
        unit: String,
    ): Float {
        caps(canvas, name, x, vy(top + LABEL_BASELINE))
        val width = figure(canvas, figure, x, vy(top + FIGURE_BASELINE), FIGURE_SIZE, TripPalette.INK)
        if (unit.isEmpty()) return width
        val gap = vs(UNIT_GAP)
        unitPaint.textSize = vs(UNIT_SIZE)
        unit(canvas, unit, x + width + gap, vy(top + FIGURE_BASELINE), UNIT_SIZE)
        return width + gap + unitPaint.measureText(unit)
    }

    private fun caps(canvas: Canvas, text: String, x: Float, y: Float) {
        capsPaint.textSize = vs(LABEL_SIZE)
        capsPaint.color = TripPalette.MUTED
        canvas.drawText(text, x, y, capsPaint)
    }

    /** Draws a figure and returns how wide it came out, so its unit can sit against it. */
    private fun figure(canvas: Canvas, text: String, x: Float, y: Float, sizeV: Float, colour: Int): Float {
        figurePaint.textSize = vs(sizeV)
        figurePaint.color = colour
        canvas.drawText(text, x, y, figurePaint)
        return figurePaint.measureText(text)
    }

    private fun unit(canvas: Canvas, text: String, x: Float, y: Float, sizeV: Float) {
        unitPaint.textSize = vs(sizeV)
        unitPaint.color = TripPalette.MUTED
        canvas.drawText(text, x, y, unitPaint)
    }

    /**
     * Which way the road is going, hung off a right edge; returns how much width it took.
     *
     * The arrow is drawn rather than typed. "↗" is one character and would be a great deal less
     * code, and this panel has no idea what the head unit's font does with U+2197 - a glyph that
     * comes out as a box is a reading that says nothing, and there is no way to find out from
     * here. Three lines always draw.
     */
    private fun variometer(
        canvas: Canvas,
        right: Float,
        baseline: Float,
        metresPerSecond: Double,
        textSize: Float = ARROW_TEXT,
        arrowSize: Float = ARROW_SIZE,
    ): Float {
        val up = metresPerSecond >= 0
        val colour = if (up) TripPalette.LIVE else TripPalette.AMBER
        val text = fmt1(abs(metresPerSecond))
        unitPaint.textSize = vs(textSize)
        unitPaint.color = colour
        unitPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(text, right, baseline, unitPaint)
        val textWidth = unitPaint.measureText(text)
        unitPaint.textAlign = Paint.Align.LEFT
        val half = vs(arrowSize) / 2f
        val ax = right - textWidth - vs(ARROW_GAP) - half
        stroke.color = colour
        stroke.strokeWidth = vs(ARROW_WEIGHT)
        val tip = if (up) baseline - half * 2f else baseline
        val tail = if (up) baseline else baseline - half * 2f
        canvas.drawLine(ax, tail, ax, tip, stroke)
        val wing = if (up) tip + half else tip - half
        canvas.drawLine(ax - half * 0.6f, wing, ax, tip, stroke)
        canvas.drawLine(ax + half * 0.6f, wing, ax, tip, stroke)
        return textWidth + vs(ARROW_GAP) + vs(arrowSize)
    }

    /** How wide [variometer] will come out, so a caller can set it flush against a run. */
    private fun variometerWidth(metresPerSecond: Double, textSize: Float): Float {
        unitPaint.textSize = vs(textSize)
        return unitPaint.measureText(fmt1(abs(metresPerSecond))) + vs(ARROW_GAP) + vs(ARROW_SIZE)
    }

    private fun distanceLabel(meters: Double): String =
        if (meters >= 1000) "${fmt1(meters / 1000.0)} км" else "${meters.roundToInt()} м"

    private fun fmt1(v: Double): String = "%.1f".format(v).replace('.', ',')

    companion object {
        // Flush against the panel's own left edge: the view is laid out with
        // fillMaxWidth() in the same column as the feature cards, so x=0 is
        // already the card edge and any inset here reads as the analyser sitting
        // crooked against everything above it.
        /**
         * The wide panel's own space: the board's, one unit to one of its pixels.
         *
         * The base class declares 1850x360, a 5.1:1 band from when this strip was thin, and
         * [PanelCanvas] scales x and y independently - so drawing that layout into the board's
         * region stretched every stroke in it vertically. Rather than choose between a distorted
         * panel and a box the board does not draw, the panel is laid out in the board's own
         * numbers and asks for a box of the board's own shape.
         *
         * 296 rather than the 416 this first shipped at, because 416 was never available. The head
         * unit gives the app 680 dp of height, not the 800 dp of the screen - a system dock takes
         * 64 below the window and an opaque status band 56 above it, measured off the car. Two
         * rows of tiles and the page margins take 384 of that, so 296 is what is left. At 416 the
         * bottom 120 dp of this panel was drawn past the edge of the window and never seen.
         */
        const val WIDE_VIRTUAL_W = 1184f
        const val WIDE_VIRTUAL_H = 296f

        /**
         * The analyser takes the left, the figures the right, with `Space.XL` between them.
         *
         * 832, not the 834 this shipped at: the board's gap was 30, which is on no ladder, and
         * moving it to the group rung takes two units off the analyser and leaves the column
         * starting at 864 exactly where it was.
         *
         * The analyser starts at the top of the strip rather than 6 units into it. That 6 was an
         * inset with no name and no rung; the separation between the tiles and the strip is
         * `DashboardLayoutPolicy.bandGap`, and one number for one gap is the whole rule.
         */
        const val SPECTRUM_LEFT = 0f
        const val SPECTRUM_RIGHT = 832f
        const val SPECTRUM_TOP = 0f
        const val SPECTRUM_BOTTOM = 296f
        const val COLUMN_X = 864f
        const val COLUMN_RIGHT = 1184f

        // Three blocks hung apart down the full height, as `justify-content: space-between`, at
        // the anchors `Main.dc.html` measures: a 72-tall block, then two of 85 behind a hairline.
        const val BLOCK_TOP_1 = 0f
        const val BLOCK_TOP_2 = 99f
        const val BLOCK_TOP_3 = 211f
        const val RULE_GAP = 12f

        /**
         * The band under the field that the two page dots stand in, and the only thing the second
         * page charges the first.
         *
         * At the foot rather than the top because that is where a page indicator belongs and
         * because the top of the field already carries the ticker. Both pages spend the same 20,
         * so the dots do not move when the page does.
         */
        const val DOTS = 20f
        const val DOT = 8f
        const val DOT_GAP = 8f

        /** A dot that is not this page: the caption's ink, well under it. */
        const val DOT_IDLE_ALPHA = 0.45f

        /** The board's `letter-spacing:1.6px` at 15 px, as the em value a paint takes. */
        const val CAPS_TRACKING = 0.107f

        // A block is a 15 label on its own line and a 46 figure 8 under it, which is where the
        // board's `.fig { gap: 8px }` puts them; both baselines are measured off the board rather
        // than chosen, which is what the last pair of numbers here were.
        const val LABEL_SIZE = 15f
        const val LABEL_BASELINE = 14f
        const val FIGURE_SIZE = 46f
        const val FIGURE_BASELINE = 65f
        const val UNIT_SIZE = 24f
        const val UNIT_GAP = 12f

        /**
         * One arrow, one size, at every width.
         *
         * The narrow pane used to draw it at 14 beside the same 19 the other two widths set their
         * rate in, so one glyph came out two sizes on one screen. It is 20 everywhere now, and
         * its stroke is the ladder's optical weight at 20: `2.0 x 24 / 20`.
         */
        const val ARROW_SIZE = 20f
        const val ARROW_WEIGHT = 2.4f
        const val ARROW_TEXT = 19f
        const val ARROW_GAP = 8f

        const val HINT_Y = 142f
        const val HINT_SIZE = 15f

        /**
         * The two panes' own widths, off `TwoThirds.dc.html` and `OneThird.dc.html`.
         *
         * The width is the content width the page has after its margins, so the scale factor is
         * one and every size below is the dp it says it is. 788 is 828 less two margins of 20;
         * 392 is 416 less two of 12. There is no matching height: a pane's strip takes whatever
         * the chips above it left, and [drawPane] derives the height of its space from that.
         */
        const val MEDIUM_VIRTUAL_W = 788f
        const val NARROW_VIRTUAL_W = 392f

        /**
         * The floor under a pane's analyser.
         *
         * The strip is handed a remainder, and a remainder can in principle be small. Below this
         * the bars are shorter than the ticker over them and the thing stops being an analyser, so
         * the figures are allowed to sit past the bottom of a box this short rather than the
         * analyser being squeezed into nothing. It has never happened on this car - 828 leaves 416
         * and 416 leaves 296 - and it is here so that it cannot happen silently.
         */
        const val PANE_MIN_ANALYSER = 140f

        /** Between the analyser and the figures: two different things, so the group gap. */
        const val PANE_GROUP = 32f
        const val PANE_RULE = 1f

        // Three figures side by side, and it is the full screen's own block: a 15 label, Space.S,
        // a 46 figure - 72 tall, with the same two baselines. It used to be 76, with the label and
        // the figure pushed to the ends of the band by a `space-between` nobody had chosen, so one
        // block came out two heights on one screen.
        const val PANE_BLOCK = 72f
        const val PANE_BLOCK_LABEL = LABEL_BASELINE
        const val PANE_BLOCK_FIGURE = FIGURE_BASELINE

        // Three figures as rows: a 15 label and a 24 reading on one baseline, three of them a
        // neighbour's gap apart. 23 is where a 24-dp face sits in a 30-dp line.
        const val PANE_ROW = 30f
        const val PANE_ROW_GAP = 12f
        const val PANE_ROWS = PANE_ROW * 3f + PANE_ROW_GAP * 2f
        const val PANE_BASELINE = 23f
        const val PANE_LABEL = 15f
        const val PANE_VALUE = 24f
        const val PANE_RATE = 19f

        /**
         * Where a row's reading starts, so all three start in the same place.
         *
         * The widest capital this panel can print is "ОСТАЛОСЬ", which measures 91.4 dp at 15 with
         * the board's tracking; this is that plus `Space.L`, rounded up to the whole dp above it.
         * A column measured from the *drawn* labels would move the readings sideways the moment a
         * route started, which is the jump the captions on the tiles were rewritten to remove.
         */
        const val PANE_LABEL_COLUMN = 112f
    }
}
