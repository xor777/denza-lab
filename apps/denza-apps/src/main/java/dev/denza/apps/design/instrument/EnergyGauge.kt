package dev.denza.apps.design.instrument

import android.graphics.Canvas
import android.graphics.Paint
import dev.denza.apps.design.DenzaPalette

/**
 * Power now, on an arc, with the road just behind drawn inside it.
 *
 * The control is one thing rather than two because the two answer one question between them: the
 * arc says what the car is spending at this instant, the bars say what that instant is worth
 * against the last few kilometres. Splitting them would make a driver compare two places.
 *
 * It lives here rather than inside the cluster because the same gauge is wanted at three more
 * sizes - the full cluster, the right third, and the two projected panel widths - and a component
 * copied four times is four components within a month.
 *
 * ### Why the marks are labelled
 *
 * The two sides of the arc carry different spans: this car can spend three hundred kilowatts and
 * recover about a hundred, so equal deflection means very different numbers left and right. An
 * unlabelled two-sided arc would quietly claim they were the same, and a large arc across the top
 * of a cluster is read as a speedometer unless something on it says otherwise. The flanking pair -
 * 60 one way, 20 the other, at identical deflection - says both things at a glance.
 *
 * Zero is the one mark left unlabelled: it is where the fill vanishes, which needs no caption, and
 * a label above it would reach past the clear band into the vehicle's own graphics.
 *
 * How many discharge marks get drawn is [InstrumentDensity.dialMarks], and a mark that cannot be
 * labelled is not drawn at all - an unlabelled mark standing among labelled ones is the same lie in
 * miniature. In the right third that leaves the flanking pair alone, because the second mark's
 * number would land inside the combustion column.
 */
class EnergyGauge(private val pen: InstrumentPen) {

    /**
     * @param kilowatts pack flow, positive out of the battery; null when nothing answered.
     * @param bars closed consumption buckets, oldest first.
     * @param average the mean the caller states in [caption], so the line and the words agree.
     * @param caption one sentence under the dial - what the bars mean, or why there are none.
     */
    fun draw(
        canvas: Canvas,
        centreX: Float,
        centreY: Float,
        radius: Float,
        density: InstrumentDensity,
        kilowatts: Double?,
        bars: List<Double>,
        average: Double?,
        caption: String,
    ) {
        pen.glow(canvas, centreX, centreY, radius * GLOW_X, radius * GLOW_Y, GLOW_INK, GLOW_STRENGTH)

        pen.arc(canvas, centreX, centreY, radius, ARC_FROM, ARC_TO, DenzaPalette.TRACK, density.arcWidth)

        EnergyScale.DISCHARGE_TICKS_KW.take(density.dialMarks).forEach { mark ->
            marked(canvas, centreX, centreY, radius, density, mark, DenzaPalette.TRACK_MARK, DenzaPalette.MUTED_DEEP)
        }
        EnergyScale.REGEN_TICKS_KW.forEach { mark ->
            marked(canvas, centreX, centreY, radius, density, -mark, DenzaPalette.returned(0.6f), DenzaPalette.returned(0.75f))
        }
        pen.tick(canvas, centreX, centreY, radius, TOP_DEGREES, density.tickLength, DenzaPalette.MUTED_DEEP)

        if (kilowatts != null) {
            val reading = kilowatts.toFloat()
            val degrees = EnergyScale.angleDegrees(reading, TOP_DEGREES, SIDE_SWEEP)
            val color = if (EnergyScale.isRegenerating(reading)) DenzaPalette.RETURN else DenzaPalette.INK
            pen.arc(canvas, centreX, centreY, radius, TOP_DEGREES, degrees, color, density.arcWidth)
            if (EnergyScale.sweepFraction(reading) > 0f) {
                val (capX, capY) = pen.onArc(centreX, centreY, radius, degrees)
                pen.dot(canvas, capX, capY, density.dotRadius, DenzaPalette.DATA_PEAK)
            }
        }

        val zeroLineY = centreY - radius * CHART_ZERO_ABOVE
        val chartHeight = radius * CHART_HEIGHT
        val chartTop = zeroLineY - chartHeight * ChartScale.ABOVE_ZERO_SHARE
        val half = radius * CHART_HALF_WIDTH
        pen.consumptionChart(
            canvas,
            centreX - half,
            centreX + half,
            chartTop,
            chartTop + chartHeight,
            bars,
            density,
            average,
            DenzaPalette.accent(0.34f),
        )

        pen.figure(
            canvas,
            text = kilowatts?.let { format(it) } ?: DASH,
            unitText = UNIT,
            x = centreX,
            baseline = centreY - radius * FIGURE_ABOVE,
            density = density,
            sizeV = density.figure,
            color = DenzaPalette.INK,
            align = Paint.Align.CENTER,
        )

        pen.label(
            canvas,
            caption,
            centreX,
            centreY + radius * CAPTION_BELOW,
            density.body,
            DenzaPalette.MUTED_DEEP,
            Paint.Align.CENTER,
        )
    }

    /** One mark and the number it stands for, both derived from the same angle. */
    private fun marked(
        canvas: Canvas,
        centreX: Float,
        centreY: Float,
        radius: Float,
        density: InstrumentDensity,
        kilowatts: Float,
        markColor: Int,
        textColor: Int,
    ) {
        val degrees = EnergyScale.angleDegrees(kilowatts, TOP_DEGREES, SIDE_SWEEP)
        pen.tick(canvas, centreX, centreY, radius, degrees, density.tickLength, markColor)
        val out = radius + pen.v(markReach(density))
        val (x, y) = pen.onArc(centreX, centreY, out, degrees)
        pen.label(
            canvas,
            format(kotlin.math.abs(kilowatts).toDouble()),
            x,
            y + pen.v(density.tick) * BASELINE_NUDGE,
            density.tick,
            textColor,
            Paint.Align.CENTER,
        )
    }

    /**
     * The reading, as a magnitude.
     *
     * No minus sign, and that is a decision rather than an oversight. Which way the energy is going
     * is already said twice and said better - the fill runs to the other side of the dial and it is
     * drawn in [DenzaPalette.RETURN] - so the sign would be a third, weaker copy of the same fact.
     * It also costs a whole character at the one place the dial is tightest: at full regeneration
     * "-100" left about eight pixels of clearance against the arc, where "100" leaves thirty.
     */
    private fun format(kilowatts: Double): String =
        String.format(java.util.Locale.US, "%.0f", kotlin.math.abs(kilowatts))

    companion object {

        /**
         * How far past the arc the control reaches straight up, in virtual units.
         *
         * Straight up is where it matters: on the cluster the clear band's top edge runs across the
         * dial's crown, and everything the gauge draws outward besides - the numbered marks - is far
         * enough off vertical to be well below that edge. So this is the mark alone, and it is only
         * the mark because zero carries no label; the label that used to sit above it reached two
         * pixels into the vehicle's own graphics, which is exactly the sort of thing a caller cannot
         * check without being told the number.
         */
        fun topReach(density: InstrumentDensity): Float = MARK_GAP + density.tickLength

        /**
         * How far past the arc a mark's *number* sits, to the centre of that number.
         *
         * The last term is half the number's own height, so what the rhythm buys is a gap between
         * the mark's tip and the number's edge rather than between the tip and the number's middle.
         * Without it the nearly-horizontal mark on the discharge side put its tip inside the first
         * digit - about four pixels of it, which is exactly the sort of thing that survives every
         * review that is not a measurement.
         *
         * Sideways is where this matters: it decides whether the dial's own scale can coexist with
         * the instrument column beside it.
         */
        fun markReach(density: InstrumentDensity): Float =
            MARK_GAP + density.tickLength + density.step + density.tick / 2f

        /**
         * How wide the widest reading this dial can ever show comes out, in virtual units.
         *
         * The scale clamps at three digits, so the worst case is knowable in advance and there is
         * no reason to find out on the car. The two ratios are estimates of what the type actually
         * measures - a monospace digit advances 0.6 em, and "кВт" set in Roboto is a little over
         * 1.7 em of its own size - which is enough for a clearance check that leaves tens of units
         * of margin, and is not used for placement: [InstrumentPen.figure] measures the real thing.
         */
        fun widestReading(density: InstrumentDensity): Float =
            WIDEST_DIGITS * MONO_ADVANCE * density.figure +
                density.rhythm(1f) +
                UNIT_ADVANCE * density.body

        /**
         * Half the chord of the arc at the height of the reading's cap line, in virtual units.
         *
         * The cap line rather than the baseline: that is where the digits are widest and the arc
         * around them is narrowest, so it is the only height at which the question has an answer.
         */
        fun chordAtReading(radius: Float, density: InstrumentDensity): Float {
            val drop = radius * FIGURE_ABOVE + CAP_HEIGHT * density.figure
            val squared = radius * radius - drop * drop
            return if (squared <= 0f) 0f else kotlin.math.sqrt(squared)
        }

        private const val WIDEST_DIGITS = 3f
        private const val MONO_ADVANCE = 0.6f
        private const val UNIT_ADVANCE = 1.72f
        private const val CAP_HEIGHT = 0.71f

        /** The angle of the outermost mark this density labels, for a caller checking clearance. */
        fun outermostMarkDegrees(density: InstrumentDensity): Float = EnergyScale.angleDegrees(
            EnergyScale.DISCHARGE_TICKS_KW.take(density.dialMarks).last(),
            TOP_DEGREES,
            SIDE_SWEEP,
        )

        /** The dial dips below the horizon on both sides, so the chart sits in an open bowl. */
        private const val ARC_FROM = 200f
        private const val ARC_TO = -20f
        private const val TOP_DEGREES = 90f

        /** Each side gets the same arc, which is exactly why the marks carry numbers. */
        private const val SIDE_SWEEP = 110f

        /** The gap [InstrumentPen.tick] leaves before a mark starts, repeated here to clear it. */
        private const val MARK_GAP = 3f

        /** Sinks a centred label onto the optical middle of its own line. */
        private const val BASELINE_NUDGE = 0.36f

        /** The chart nested inside the dial, in shares of the radius. */
        private const val CHART_HALF_WIDTH = 0.80f
        private const val CHART_ZERO_ABOVE = 0.06f
        private const val CHART_HEIGHT = 0.52f
        private const val FIGURE_ABOVE = 0.60f
        private const val CAPTION_BELOW = 0.30f

        /**
         * The pool of light the dial sits in, in shares of its radius.
         *
         * It started at the old scrim's reach, `1.28` by `1.22`, because that shape is what produced
         * the halo on the car this is a deliberate version of. On the panel that read as a tight
         * ring rather than a bloom, so it now runs well past the arc: wide enough to reach the inner
         * edge of the columns either side, and tall enough that the panel's own edge cuts it off
         * rather than the gradient ending in mid-air.
         *
         * Two numbers, two jobs. [GLOW_STRENGTH] is the alpha at the very centre and does not change
         * with the reach - a wider pool is not a brighter one - and past roughly `0.22` on a black
         * panel it stops reading as light and starts reading as a grey disc. The reach is how far
         * that light carries.
         */
        private const val GLOW_X = 2.4f
        private const val GLOW_Y = 1.85f
        private const val GLOW_STRENGTH = 0.16f
        private val GLOW_INK = DenzaPalette.INK

        private const val UNIT = "кВт"
        private const val DASH = "—"
    }
}
