package dev.denza.apps.feature.cluster.dashboard

import android.graphics.Canvas
import dev.denza.apps.design.instrument.InstrumentFace
import dev.denza.apps.design.instrument.InstrumentPen
import kotlin.math.PI
import kotlin.math.sin

/**
 * The surface a glyph is drawn on, stated in the panel's own units.
 *
 * It is here for the reason [ContourRuns] is here: a `Canvas` call is unverifiable by construction
 * in this module, and the three things the family promises are not statements about pixels. **The
 * block is on the axle the glyph names, every wheel is hollow, and the one part that carries the
 * reading's colour is the block** - a mutation run put the front motor's block on the rear axle,
 * filled all four wheels and painted the block in the case's colour, and the whole suite stayed
 * green, because every one of those decisions lived inside a call nothing could observe.
 *
 * [InstrumentPen] satisfies it through one adapter the family keeps, so a frame still allocates
 * nothing; a test satisfies it with a recorder and reads back what was drawn.
 */
internal interface GlyphSurface {

    /** An outlined rounded rectangle: a case, or a wheel. */
    fun frame(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        radius: Float,
        colour: Int,
        stroke: Float,
    )

    /** A filled one: a terminal, a lit cell, a motor's block. */
    fun plate(left: Float, top: Float, right: Float, bottom: Float, radius: Float, colour: Int)

    /** And the inverter's own alternating current. */
    fun polyline(xs: FloatArray, ys: FloatArray, count: Int, colour: Int, stroke: Float)
}

/**
 * The five marks the temperature row is named by, and the arithmetic behind them.
 *
 * Until the ninth pass that row was three cells under three words - `БАТАРЕЯ`, `МОТОРЫ`,
 * `ИНВЕРТОР` - and the middle one carried three figures under one caption, so **which motor was
 * which** was something a reader had to learn. Naming the three positions in Russian was tried and
 * the owner threw it out on the sound of it («по-русски не звучат»); a drawing of the car from above
 * with one axle lit says the same thing in one glance and in no language.
 *
 * Four rules carry the family, and every one of them is his:
 *
 *  - **all five or none.** «если символы, то и батарея, и инвертор, и хорошие» - half a row of
 *    pictures under half a row of words is worse than either;
 *  - **[HEIGHT] units, not the caption's 18.** At 18 they were 2.7 mm of glass for a shape with four
 *    wheels in it - «жучков не видно». At 24 they are 5.1 mm, and they stand *on* the caption
 *    baseline rather than hanging from it, so nothing else on the shelf moved;
 *  - **one outline, one component.** The outline is `MUTED`, the same as a caption; the component
 *    inside is the part that carries colour - `INK` in the ordinary case, and the figure's own
 *    `WARNING`/`DANGER` when that cell is hot, so an exception lights the cell as one object;
 *  - **the motor is the motor, not the wheel it drives** («точно мотор с колёсами не путаешь?»).
 *    Four hollow wheels stand off the body in all three car glyphs; what moves is a filled block on
 *    an axle. A wheel is never filled, and it is the one thing here drawn at [WHEEL_STROKE] rather
 *    than at the data weight, because it is furniture saying "this is a car from above".
 *
 * ### Why this is not `DenzaIcons`
 *
 * The head unit's set is one optical weight throughout, on purpose, because those are labels on
 * tiles. These are not labels: a glyph here is half of a reading, and inside one mark the case, the
 * lit component and the wheels are three different kinds of thing. So the family has two weights and
 * says which is which - and it is a `Canvas` rather than an `ImageVector` because the cluster draws
 * through [InstrumentPen] at the panel's own scale, with nothing allocated in a frame.
 *
 * ### Nothing here is typed
 *
 * [K] is the whole trick: every proportion is written in the caption's own units and multiplied up,
 * so the family grows or shrinks with [HEIGHT] alone. `tools/design-canvas/gen_contour.py` states the
 * same numbers the same way and `ContourBoardContractTest` holds the two against each other.
 */
internal class ContourGlyphs {

    /** The wave's points, in panel units, rebuilt only when the cell moves. */
    private val waveXs = FloatArray(WAVE_SAMPLES + 1)
    private val waveYs = FloatArray(WAVE_SAMPLES + 1)
    private var waveAt = Float.NaN
    private var waveTop = Float.NaN

    /** The car's own surface, kept rather than made, because a frame allocates nothing. */
    private val surface = PenSurface()

    /**
     * One glyph on the vehicle's display.
     *
     * The pen and the canvas go into the adapter and the drawing itself is the overload below, so
     * what is drawn is decided in one place whether the surface is a `Canvas` or a recorder.
     */
    fun draw(
        pen: InstrumentPen,
        canvas: Canvas,
        glyph: Glyph,
        x: Float,
        baseline: Float,
        outline: Int,
        component: Int,
    ) {
        surface.bind(pen, canvas)
        draw(surface, glyph, x, baseline, outline, component)
    }

    /**
     * One glyph, with its left edge at [x] and its foot on [baseline], both in panel units.
     *
     * [outline] is the case and the wheels; [component] is the one part that means something.
     */
    fun draw(
        surface: GlyphSurface,
        glyph: Glyph,
        x: Float,
        baseline: Float,
        outline: Int,
        component: Int,
    ) = when (glyph) {
        Glyph.PACK -> pack(surface, x, baseline, outline, component)
        Glyph.INVERTER -> inverter(surface, x, baseline, outline, component)
        else -> car(surface, glyph, x, baseline, outline, component)
    }

    /** A battery: the case, the terminal, and one cell inside it carrying the reading's colour. */
    private fun pack(
        surface: GlyphSurface,
        x: Float,
        baseline: Float,
        outline: Int,
        component: Int,
    ) {
        val top = packTop(baseline)
        surface.frame(
            x,
            top,
            x + PACK_WIDTH - PACK_NUB,
            top + PACK_HEIGHT,
            PACK_RADIUS,
            outline,
            STROKE,
        )
        surface.plate(
            x + PACK_WIDTH - PACK_NUB,
            top + PACK_NUB_INSET,
            x + PACK_WIDTH,
            top + PACK_HEIGHT - PACK_NUB_INSET,
            0f,
            outline,
        )
        surface.plate(
            x + PACK_CELL_INSET,
            top + PACK_CELL_INSET,
            x + PACK_WIDTH - PACK_CELL_TRIM + PACK_CELL_INSET,
            top + PACK_HEIGHT - PACK_CELL_INSET,
            0f,
            component,
        )
    }

    /** The car from above: a body, four hollow wheels, and one block on one axle. */
    private fun car(
        surface: GlyphSurface,
        glyph: Glyph,
        x: Float,
        baseline: Float,
        outline: Int,
        component: Int,
    ) {
        val bodyX = x + BODY_X
        val bodyTop = bodyTop(baseline)
        surface.frame(
            bodyX,
            bodyTop,
            bodyX + BODY_WIDTH,
            bodyTop + BODY_HEIGHT,
            BODY_RADIUS,
            outline,
            STROKE,
        )
        for (right in 0..1) {
            for (rear in 0..1) {
                val wheelX = wheelX(x, right == 1)
                val wheelTop = wheelTop(baseline, rear == 1)
                surface.frame(
                    wheelX,
                    wheelTop,
                    wheelX + WHEEL_WIDTH,
                    wheelTop + WHEEL_HEIGHT,
                    WHEEL_RADIUS,
                    outline,
                    WHEEL_STROKE,
                )
            }
        }
        val left = motorLeft(x, glyph)
        val top = motorTop(baseline, onRearAxle(glyph))
        surface.plate(
            left,
            top,
            left + motorWidth(glyph),
            top + MOTOR_HEIGHT,
            MOTOR_RADIUS,
            component,
        )
    }

    /** A case with one period of the alternating current it makes drawn inside it. */
    private fun inverter(
        surface: GlyphSurface,
        x: Float,
        baseline: Float,
        outline: Int,
        component: Int,
    ) {
        val top = inverterTop(baseline)
        surface.frame(
            x,
            top,
            x + INVERTER_SIZE,
            top + INVERTER_SIZE,
            INVERTER_RADIUS,
            outline,
            STROKE,
        )
        wave(x, top)
        surface.polyline(waveXs, waveYs, waveXs.size, component, STROKE)
    }

    /**
     * The sine's own points, in panel units, built once per cell.
     *
     * The cell never moves after the view is sized, so this runs on the first frame after a resize
     * and on no other. It writes into two fields rather than allocating: `onDraw` runs inside a
     * `Presentation` over the vehicle's instruments, where an allocation per frame is an allocation
     * per frame forever.
     */
    private fun wave(x: Float, top: Float) {
        if (waveAt == x && waveTop == top) return
        waveAt = x
        waveTop = top
        val from = x + WAVE_INSET
        val to = x + INVERTER_SIZE - WAVE_INSET
        val middle = top + INVERTER_SIZE / 2f
        for (index in 0..WAVE_SAMPLES) {
            val t = index.toFloat() / WAVE_SAMPLES
            waveXs[index] = from + (to - from) * t
            waveYs[index] = middle - sin(t * 2.0 * PI).toFloat() * WAVE_AMPLITUDE
        }
    }

    /**
     * [InstrumentPen] as a [GlyphSurface]: one adapter, kept, converting units on the way through.
     *
     * The pen speaks pixels for coordinates and panel units for radii and strokes, which is what
     * every caller of it already does; this is that same conversion in one place instead of at
     * fifty call sites inside the family.
     */
    private class PenSurface : GlyphSurface {

        private var pen: InstrumentPen? = null
        private var canvas: Canvas? = null

        /** The wave again, in pixels this time, so the conversion allocates nothing either. */
        private val xs = FloatArray(WAVE_SAMPLES + 1)
        private val ys = FloatArray(WAVE_SAMPLES + 1)

        fun bind(pen: InstrumentPen, canvas: Canvas) {
            this.pen = pen
            this.canvas = canvas
        }

        override fun frame(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radius: Float,
            colour: Int,
            stroke: Float,
        ) {
            val pen = pen ?: return
            val canvas = canvas ?: return
            pen.frame(
                canvas,
                pen.v(left),
                pen.v(top),
                pen.v(right),
                pen.v(bottom),
                radius,
                colour,
                stroke,
            )
        }

        override fun plate(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radius: Float,
            colour: Int,
        ) {
            val pen = pen ?: return
            val canvas = canvas ?: return
            pen.plate(canvas, pen.v(left), pen.v(top), pen.v(right), pen.v(bottom), radius, colour)
        }

        override fun polyline(
            xs: FloatArray,
            ys: FloatArray,
            count: Int,
            colour: Int,
            stroke: Float,
        ) {
            val pen = pen ?: return
            val canvas = canvas ?: return
            for (index in 0 until count) {
                this.xs[index] = pen.v(xs[index])
                this.ys[index] = pen.v(ys[index])
            }
            pen.polyline(canvas, this.xs, this.ys, count, colour, stroke)
        }
    }

    /** The row, in the order it reads and in `VehicleTelemetry.motorTemps` order within it. */
    enum class Glyph { PACK, MOTOR_FRONT, MOTOR_REAR_LEFT, MOTOR_REAR_RIGHT, INVERTER }

    companion object {

        /** 24 units: 5.1 mm of this glass, and three rhythm steps. */
        const val HEIGHT = 24f

        /** Every proportion below is in caption units, so the family scales with [HEIGHT] alone. */
        val K: Float = HEIGHT / InstrumentFace.CAPTION.size

        /** The widest a glyph is allowed to be, which is what a cell is measured against. */
        val WIDTH: Float = 17 * K

        /** And where it starts inside its own cell. */
        const val INSET = 1f

        /** A glyph carries data, so its case is not drawn thinner than data is. */
        const val STROKE = ContourPlan.DATA_LINE

        /**
         * Except the wheels, which are the one thing here that is furniture.
         *
         * At the data weight they competed with the block on the axle, which is the part that means
         * something. This is the head unit's own icon weight in a viewport of 24 - `DenzaMetrics`
         * calls it 2.0 there and it reads lighter here because nothing else in the mark is 1.6.
         */
        const val WHEEL_STROKE = 1.6f

        // ---- the pack

        val PACK_WIDTH: Float = 17 * K
        val PACK_HEIGHT: Float = 10 * K
        val PACK_RADIUS: Float = 2 * K

        /** The terminal, drawn in the outline's colour: it is part of what makes it a battery. */
        val PACK_NUB: Float = 2.5f * K
        val PACK_NUB_INSET: Float = 3 * K

        /** The lit cell's own margin inside the case, and what it gives up to clear the terminal. */
        val PACK_CELL_INSET: Float = 2.8f * K
        val PACK_CELL_TRIM: Float = 8.3f * K

        // ---- the car

        val BODY_WIDTH: Float = 9 * K
        val BODY_HEIGHT: Float = 14 * K
        val BODY_X: Float = 4.5f * K
        val BODY_TOP: Float = 2 * K
        val BODY_RADIUS: Float = 2.5f * K

        val WHEEL_WIDTH: Float = 3 * K
        val WHEEL_HEIGHT: Float = 5 * K
        val WHEEL_RADIUS: Float = K

        /** The standoff from the body, sideways and down, and it is the same number both ways. */
        val WHEEL_GAP: Float = K

        val MOTOR_HEIGHT: Float = 4 * K
        val MOTOR_RADIUS: Float = 0.8f * K

        /** How far inside the body a block starts, and half the gap between the two rear ones. */
        val MOTOR_INSET: Float = 1.2f * K
        val MOTOR_SPLIT: Float = 0.4f * K

        // ---- the inverter

        val INVERTER_SIZE: Float = 16 * K
        val INVERTER_RADIUS: Float = 3 * K
        val WAVE_INSET: Float = 3 * K
        val WAVE_AMPLITUDE: Float = 3.2f * K
        const val WAVE_SAMPLES = 20

        /** The pack's case is centred in the glyph's own box rather than standing on its foot. */
        fun packTop(baseline: Float): Float = baseline - HEIGHT + (HEIGHT - PACK_HEIGHT) / 2f

        /** The inverter's is too, and it is nearly the whole box. */
        fun inverterTop(baseline: Float): Float =
            baseline - HEIGHT + (HEIGHT - INVERTER_SIZE) / 2f

        /** The car's body hangs from the glyph's top, because the wheels need the room below. */
        fun bodyTop(baseline: Float): Float = baseline - HEIGHT + BODY_TOP

        fun wheelX(x: Float, right: Boolean): Float =
            if (right) x + BODY_X + BODY_WIDTH + WHEEL_GAP
            else x + BODY_X - WHEEL_GAP - WHEEL_WIDTH

        fun wheelTop(baseline: Float, rear: Boolean): Float =
            if (rear) bodyTop(baseline) + BODY_HEIGHT - WHEEL_HEIGHT - WHEEL_GAP
            else bodyTop(baseline) + WHEEL_GAP

        /**
         * Which axle a glyph's block sits on, and it is the only thing that tells the cars apart.
         *
         * «точно мотор с колёсами не путаешь?» - three identical pictures would answer *which motor
         * is this cell* no better than the three words the family replaced. So the front car's bar
         * crosses the front axle and the two rear ones share the rear, and this is that sentence.
         */
        fun onRearAxle(glyph: Glyph): Boolean = glyph != Glyph.MOTOR_FRONT

        /** The block sits on its axle, which is the middle of the wheels standing beside it. */
        fun motorTop(baseline: Float, rear: Boolean): Float =
            wheelTop(baseline, rear) + WHEEL_HEIGHT / 2f - MOTOR_HEIGHT / 2f

        fun motorLeft(x: Float, glyph: Glyph): Float = when (glyph) {
            Glyph.MOTOR_REAR_RIGHT -> x + BODY_X + BODY_WIDTH / 2f + MOTOR_SPLIT
            else -> x + BODY_X + MOTOR_INSET
        }

        /** A bar across the front axle, half a bar on one side of the rear one. */
        fun motorWidth(glyph: Glyph): Float =
            if (glyph == Glyph.MOTOR_FRONT) BODY_WIDTH - 2 * MOTOR_INSET
            else BODY_WIDTH / 2f - MOTOR_INSET - MOTOR_SPLIT
    }
}
