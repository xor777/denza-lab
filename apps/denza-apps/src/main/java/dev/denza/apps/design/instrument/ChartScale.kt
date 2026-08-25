package dev.denza.apps.design.instrument

import kotlin.math.abs
import kotlin.math.ceil

/**
 * How a run of consumption bars is fitted into the height it is given.
 *
 * Consumption goes negative whenever the car recovers energy, so the chart needs a zero line rather
 * than a floor, and the two sides need different room: a stretch of regeneration is worth far less
 * than the spending around it, and giving it half the chart would flatten everything else.
 *
 * The ceilings round outward to a round number and never fall below a floor, so a quiet stretch of
 * road does not silently magnify small differences into dramatic-looking bars.
 */
object ChartScale {

    /** The share of the height above the zero line. The rest belongs to regeneration. */
    const val ABOVE_ZERO_SHARE: Float = 0.74f

    private const val POSITIVE_STEP = 10f
    private const val POSITIVE_FLOOR = 10f
    private const val NEGATIVE_STEP = 5f
    private const val NEGATIVE_FLOOR = 5f

    /**
     * The two ceilings a run of [values] needs, as `(spending, recovery)`, both positive.
     *
     * Empty or all-zero input still yields the floors, so an empty chart draws its zero line in the
     * same place a full one does and the axis does not jump on the first bar.
     */
    fun ceilings(values: List<Float>): Pair<Float, Float> {
        var high = 0f
        var low = 0f
        for (value in values) {
            if (value > high) high = value
            if (value < low) low = value
        }
        val positive = maxOf(POSITIVE_FLOOR, ceil(high / POSITIVE_STEP) * POSITIVE_STEP)
        val negative = maxOf(NEGATIVE_FLOOR, ceil(abs(low) / NEGATIVE_STEP) * NEGATIVE_STEP)
        return positive to negative
    }

    /** Where the zero line falls inside a chart of [height], measured from its top. */
    fun zeroLine(height: Float): Float = height * ABOVE_ZERO_SHARE

    /**
     * How tall one bar is drawn, in pixels, given the ceilings from [ceilings].
     *
     * The value is always positive; the caller decides whether it grows up from the zero line or
     * down from it by asking whether the reading was negative.
     */
    fun barHeight(
        value: Float,
        height: Float,
        ceilings: Pair<Float, Float>,
    ): Float {
        val zero = zeroLine(height)
        return if (value >= 0f) {
            val room = zero
            (value / ceilings.first).coerceIn(0f, 1f) * room
        } else {
            val room = height - zero
            (abs(value) / ceilings.second).coerceIn(0f, 1f) * room
        }
    }
}
