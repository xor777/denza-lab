package dev.denza.apps.feature.vehicle

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The last ten kilometres as the shape both screens draw. One chart, drawn twice.
 *
 * `docs/energy-display-contract.md` §2.3. The cluster's petal and the head unit's car page used to
 * carry two different histories of two different quantities on two different axes, which is why
 * they could never be compared; they are one object now and the pixel height is all that differs.
 *
 * ### Bins are anchored to the odometer's own half kilometre
 *
 * `floor(odometerAtClose / BIN_KM)`, not "group the tail by fives". Grouping from the oldest
 * re-phases every bin the moment a bucket closes, so all twenty heights change every hundred
 * metres and the same road never comes back the same shape. Anchored, a bin's membership is fixed
 * the moment it is closed and the window steps by whole bins.
 *
 * ### A hole is not a zero
 *
 * A bin whose known road is under half its road is `NaN` (§2.6): drawn as nothing, its road still
 * counted for the axis, and out of the figure beside it.
 *
 * ### The newest bin is partial
 *
 * It is drawn at the width of the road it has - `road / BIN_KM` - so a chart that is filling grows
 * from its right edge rather than stretching across the box.
 *
 * Built once per sweep in [VehicleTelemetryHub] and carried in [VehicleTelemetry] as primitive
 * arrays: this is read in every frame of a sixty-frame panel and built in none of them.
 */
internal object ConsumptionChart {

    /**
     * Half a kilometre a step.
     *
     * A hundred steps of 2.3 units in the petal's 232 were the first drive's «расчёска»; twenty
     * steps of 11.6 - 2.5 mm of glass, 10.7′ from 800 mm - is a step the eye can count, and 500 m
     * averages away the spikes a hundred-metre bucket shows.
     */
    const val BIN_KM = 0.5

    /** Twenty, which is the window over the bin rather than a second statement of either. */
    val BINS: Int = (ConsumptionWindow.KM / BIN_KM).roundToInt()

    /** Which anchored bin a bucket belongs to, by the odometer it closed at. */
    fun binOf(odometerKm: Double): Long = floor(odometerKm / BIN_KM).toLong()

    /**
     * The window's buckets as the bins the box paints, oldest first.
     *
     * @param window the tail [ConsumptionWindow.raw] hands out - the last ten kilometres of road
     */
    fun of(window: List<ConsumptionSample>): ConsumptionChartSnapshot {
        if (window.isEmpty()) return ConsumptionChartSnapshot.EMPTY
        val newest = binOf(window[window.size - 1].odometerKm)
        val base = newest - BINS + 1

        val kwh = DoubleArray(BINS)
        val km = DoubleArray(BINS)
        val known = DoubleArray(BINS)
        for (index in window.indices) {
            val bucket = window[index]
            val bin = (binOf(bucket.odometerKm) - base).toInt()
            // Anything older than the box is wide has already left it at the left edge.
            if (bin < 0 || bin >= BINS) continue
            kwh[bin] += bucket.kwh
            km[bin] += bucket.km
            known[bin] += bucket.knownKm
        }

        var first = 0
        while (first < BINS && km[first] <= 0.0) first++
        if (first >= BINS) return ConsumptionChartSnapshot.EMPTY

        val count = BINS - first
        val values = FloatArray(count)
        val widths = FloatArray(count)
        for (index in 0 until count) {
            val at = first + index
            widths[index] = (km[at] / BIN_KM).toFloat().coerceIn(0f, 1f)
            values[index] = if (
                known[at] > 0.0 && known[at] * 2.0 >= km[at] - OdometerGate.KM_EPSILON
            ) {
                (kwh[at] / known[at] * 100.0).toFloat()
            } else {
                Float.NaN
            }
        }
        return ConsumptionChartSnapshot(values, widths)
    }
}

/**
 * What a renderer is handed: one value per bin, oldest first, `NaN` where the log knew no energy,
 * and how wide each bin is as a share of a full one.
 *
 * Arrays of primitives rather than lists of boxed floats because this is read in every frame and
 * built in none of them.
 */
internal class ConsumptionChartSnapshot(val values: FloatArray, val widths: FloatArray) {

    val isEmpty: Boolean get() = values.isEmpty()

    /** How wide the whole chart is, in bins - which is what right-anchors it in its box. */
    val span: Float
        get() {
            var total = 0f
            for (index in widths.indices) total += widths[index]
            return total
        }

    companion object {
        val EMPTY = ConsumptionChartSnapshot(FloatArray(0), FloatArray(0))
    }
}
