package dev.denza.apps.feature.trip

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
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

    override fun draw(
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
                )
                return
            }
            TripPanelLayout.WIDE -> Unit
        }
        setSize(w, h, WIDE_VIRTUAL_W, WIDE_VIRTUAL_H)
        spectrumRenderer.draw(
            canvas, spectrum, nowPlaying, frameTimeSec, dtSec,
            left = vx(SPECTRUM_LEFT), right = vx(SPECTRUM_RIGHT),
            top = vy(SPECTRUM_TOP), bottom = vy(SPECTRUM_BOTTOM), unit = vs(1f),
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
    ) {
        val across = layout == TripPanelLayout.MEDIUM
        val virtualW = if (across) MEDIUM_VIRTUAL_W else NARROW_VIRTUAL_W
        val virtualH = height * virtualW / width
        setSize(width, height, virtualW, virtualH)

        val figuresH = if (across) PANE_BLOCK else PANE_ROWS
        val figuresTop = virtualH - figuresH
        val analyserBottom = (figuresTop - PANE_GROUP).coerceAtLeast(PANE_MIN_ANALYSER)

        spectrumRenderer.draw(
            canvas, spectrum, nowPlaying, frameTimeSec, dtSec,
            left = vx(0f), right = vx(virtualW),
            top = vy(0f), bottom = vy(analyserBottom), unit = vs(1f),
        )

        if (across) {
            drawPaneBlocks(canvas, engine, virtualW, figuresTop, showLocationHint)
        } else {
            drawPaneRows(canvas, engine, virtualW, figuresTop, showLocationHint)
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
            paneBlock(
                canvas, pitch, top, "ВЫСОТА",
                "${engine.smoothedAltitude().roundToInt()}", "м",
            )
            variometer(
                canvas, vx(pitch + cell), vy(top + PANE_BLOCK_FIGURE), engine.variometer(),
                PANE_RATE, ARROW_SIZE,
            )
        } else if (showLocationHint) {
            label(
                canvas, LOCATION_HINT, vx(pitch), vy(top + PANE_BLOCK_LABEL), PANE_LABEL,
                TripPalette.alpha(TripPalette.MUTED, 0.85f),
            )
        }

        val sun = engine.sunInfo()
        if (sun.nextEventLabel.isNotEmpty()) {
            capsPaint.textSize = vs(PANE_LABEL)
            capsPaint.color = TripPalette.AMBER
            canvas.drawText(
                if (sun.nextIsSunset) "ЗАКАТ" else "РАССВЕТ",
                vx(2 * pitch), vy(top + PANE_BLOCK_LABEL), capsPaint,
            )
            figure(
                canvas, sun.nextEventLabel, vx(2 * pitch), vy(top + PANE_BLOCK_FIGURE),
                FIGURE_SIZE, TripPalette.INK,
            )
        }
    }

    /** A tracked capital over a large light figure, with its unit against it. */
    private fun paneBlock(
        canvas: Canvas,
        x: Float,
        top: Float,
        name: String,
        value: String,
        unit: String,
    ) {
        caps(canvas, name, vx(x), vy(top + PANE_BLOCK_LABEL))
        val width = figure(
            canvas, value, vx(x), vy(top + PANE_BLOCK_FIGURE), FIGURE_SIZE, TripPalette.INK,
        )
        if (unit.isNotEmpty()) {
            unit(canvas, unit, vx(x) + width + vs(UNIT_GAP), vy(top + PANE_BLOCK_FIGURE), UNIT_SIZE)
        }
    }

    /**
     * Three readings as rows, where there is not.
     *
     * The second and third exist only when the car has told us where it is, so on a cold start
     * this is one row - and the "no location" hint goes where the second would have been, which is
     * the same place the wide layout puts it and for the same reason: it collides with nothing
     * because nothing is there.
     */
    private fun drawPaneRows(
        canvas: Canvas,
        engine: TripEngine,
        width: Float,
        top: Float,
        showLocationHint: Boolean,
    ) {
        val x = vx(0f)
        val rightEdge = vx(width)

        val guidance = engine.guidance()
        if (guidance != null) {
            val parts = buildList {
                guidance.distanceMeters?.let { add(distanceLabel(it.toDouble())) }
                guidance.timeSeconds?.let { add(clockHm(it.toLong())) }
            }
            paneRow(
                canvas, x, rightEdge, rowBaseline(top, 0),
                "ОСТАЛОСЬ", parts.joinToString(" · ").ifBlank { "—" }, TripPalette.MUTED,
            )
        } else {
            val elapsed = engine.elapsedSeconds
            val timePart = if (elapsed >= 3600) clockHm(elapsed.toLong()) else clockMs(elapsed)
            paneRow(
                canvas, x, rightEdge, rowBaseline(top, 0),
                "В ПУТИ", "$timePart · ${distanceLabel(engine.distanceMeters())}", TripPalette.MUTED,
            )
        }

        if (engine.hasAltitude()) {
            val baseline = rowBaseline(top, 1)
            caps(canvas, "ВЫСОТА", x, baseline)
            val rate = variometer(
                canvas, rightEdge, baseline, engine.variometer(), PANE_RATE, PANE_ARROW,
            )
            figurePaint.textAlign = Paint.Align.RIGHT
            figure(
                canvas, "${engine.smoothedAltitude().roundToInt()} м",
                rightEdge - rate - vs(PANE_GAP), baseline, PANE_VALUE, TripPalette.INK,
            )
            figurePaint.textAlign = Paint.Align.LEFT
        } else if (showLocationHint) {
            label(
                canvas, LOCATION_HINT, x, rowBaseline(top, 1), PANE_LABEL,
                TripPalette.alpha(TripPalette.MUTED, 0.85f),
            )
        }

        val sun = engine.sunInfo()
        if (sun.nextEventLabel.isNotEmpty()) {
            paneRow(
                canvas, x, rightEdge, rowBaseline(top, 2),
                if (sun.nextIsSunset) "ЗАКАТ" else "РАССВЕТ", sun.nextEventLabel, TripPalette.AMBER,
            )
        }
    }

    /** A tracked capital at the left edge, its reading at the right, both on one baseline. */
    private fun paneRow(
        canvas: Canvas,
        left: Float,
        right: Float,
        baseline: Float,
        label: String,
        value: String,
        labelColour: Int,
    ) {
        capsPaint.textSize = vs(PANE_LABEL)
        capsPaint.color = labelColour
        canvas.drawText(label, left, baseline, capsPaint)
        figurePaint.textAlign = Paint.Align.RIGHT
        figure(canvas, value, right, baseline, PANE_VALUE, TripPalette.INK)
        figurePaint.textAlign = Paint.Align.LEFT
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
            block(
                canvas, x, BLOCK_TOP_2 + RULE_GAP, "ВЫСОТА",
                "${engine.smoothedAltitude().roundToInt()}", "м",
            )
            variometer(
                canvas,
                vx(COLUMN_RIGHT),
                vy(BLOCK_TOP_2 + RULE_GAP + FIGURE_BASELINE),
                engine.variometer(),
            )
        }

        val sun = engine.sunInfo()
        if (sun.nextEventLabel.isNotEmpty()) {
            hairline(canvas, x, vy(BLOCK_TOP_3), vx(COLUMN_RIGHT), vy(BLOCK_TOP_3))
            drawSun(canvas, x, sun.nextIsSunset)
            val textX = x + vs(SUN_ICON + SUN_GAP)
            val width = figure(
                canvas, sun.nextEventLabel, textX, vy(SUN_BASELINE), SUN_TIME, TripPalette.INK,
            )
            unit(
                canvas,
                if (sun.nextIsSunset) "закат" else "рассвет",
                textX + width + vs(SUN_GAP),
                vy(SUN_BASELINE),
                UNIT_SIZE_SMALL,
            )
        }
    }

    /** A tracked capital label with a large light figure and its unit beside it. */
    private fun block(canvas: Canvas, x: Float, top: Float, name: String, figure: String, unit: String) {
        caps(canvas, name, x, vy(top + LABEL_BASELINE))
        val width = figure(canvas, figure, x, vy(top + FIGURE_BASELINE), FIGURE_SIZE, TripPalette.INK)
        if (unit.isNotEmpty()) {
            unit(canvas, unit, x + width + vs(UNIT_GAP), vy(top + FIGURE_BASELINE), UNIT_SIZE)
        }
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
        val ax = right - textWidth - vs(SUN_GAP) - half
        stroke.color = colour
        stroke.strokeWidth = vs(ARROW_WEIGHT)
        val tip = if (up) baseline - half * 2f else baseline
        val tail = if (up) baseline else baseline - half * 2f
        canvas.drawLine(ax, tail, ax, tip, stroke)
        val wing = if (up) tip + half else tip - half
        canvas.drawLine(ax - half * 0.6f, wing, ax, tip, stroke)
        canvas.drawLine(ax + half * 0.6f, wing, ax, tip, stroke)
        return textWidth + vs(SUN_GAP) + vs(arrowSize)
    }

    /** The board's sun: a disc with five rays and the horizon under it. */
    private fun drawSun(canvas: Canvas, x: Float, sunset: Boolean) {
        val cx = x + vs(SUN_ICON) / 2f
        val cy = vy(BLOCK_TOP_3 + RULE_GAP + SUN_ICON / 2f)
        val r = vs(SUN_ICON) * 0.17f
        stroke.color = if (sunset) TripPalette.AMBER else TripPalette.LIVE
        stroke.strokeWidth = vs(SUN_WEIGHT)
        canvas.drawCircle(cx, cy, r, stroke)
        val ray = vs(SUN_ICON) * 0.09f
        canvas.drawLine(cx, cy - r - ray * 1.6f, cx, cy - r - ray * 0.4f, stroke)
        val d = (r + ray) * 0.72f
        canvas.drawLine(cx - d - ray * 0.4f, cy - d - ray * 0.4f, cx - d, cy - d, stroke)
        canvas.drawLine(cx + d + ray * 0.4f, cy - d - ray * 0.4f, cx + d, cy - d, stroke)
        canvas.drawLine(x, cy + r + ray * 1.4f, x + vs(SUN_ICON), cy + r + ray * 1.4f, stroke)
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

        // The analyser takes the left, the figures the right, with the board's 30 between them.
        const val SPECTRUM_LEFT = 0f
        const val SPECTRUM_RIGHT = 834f
        const val SPECTRUM_TOP = 6f
        const val SPECTRUM_BOTTOM = 296f
        const val COLUMN_X = 864f
        const val COLUMN_RIGHT = 1184f

        // Three blocks hung apart down the full height, as `justify-content: space-between`.
        const val BLOCK_TOP_1 = 6f
        const val BLOCK_TOP_2 = 116f
        const val BLOCK_TOP_3 = 241f
        const val RULE_GAP = 14f

        /** The board's `letter-spacing:1.6px` at 15 px, as the em value a paint takes. */
        const val CAPS_TRACKING = 0.107f

        const val LABEL_SIZE = 15f
        const val LABEL_BASELINE = 12f
        const val FIGURE_SIZE = 46f
        const val FIGURE_BASELINE = 58f
        const val UNIT_SIZE = 24f
        const val UNIT_SIZE_SMALL = 19f
        const val UNIT_GAP = 14f

        const val ARROW_SIZE = 20f
        const val ARROW_WEIGHT = 2.4f
        const val ARROW_TEXT = 19f

        const val SUN_ICON = 26f
        const val SUN_WEIGHT = 1.846f
        const val SUN_GAP = 12f
        const val SUN_TIME = 34f
        const val SUN_BASELINE = 281f

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

        // Three figures side by side: a 15 label over a 46 figure, in a band 76 tall.
        const val PANE_BLOCK = 76f
        const val PANE_BLOCK_LABEL = 14f
        const val PANE_BLOCK_FIGURE = 73f

        // Three figures as rows: a 15 label and a 24 reading on one baseline, three of them a
        // neighbour's gap apart. 23 is where a 24-dp face sits in a 30-dp line.
        const val PANE_ROW = 30f
        const val PANE_ROW_GAP = 12f
        const val PANE_ROWS = PANE_ROW * 3f + PANE_ROW_GAP * 2f
        const val PANE_BASELINE = 23f
        const val PANE_LABEL = 15f
        const val PANE_VALUE = 24f
        const val PANE_RATE = 19f
        const val PANE_ARROW = 14f
        const val PANE_GAP = 8f
    }
}
