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
 * `floor(odometer / BIN_KM)`, not "group the tail by fives". Grouping from the oldest re-phases
 * every bin the moment a bucket closes, so all twenty heights change every hundred metres and the
 * same road never comes back the same shape. Anchored, a bin's membership is fixed the moment it is
 * closed and the window steps by whole bins.
 *
 * **And a bucket is filed by the road it covers rather than by where it stopped.** A bucket that
 * closed at 100.5 km covers `[100.4, 100.5)`, which is the bin that ends at 100.5 and not the one
 * that starts there - filing it by its own odometer put every bin's membership one bucket out of
 * phase, and a bucket carrying two kilometres of a pause vanished into a single bin, taking 1.9 km
 * of road off the axis with it. So each bucket's road is walked and each bin is given its overlap;
 * **the energy and the known road go with it pro rata by road**, which is the only division a
 * bucket supports - it holds one integral over one stretch and no record of where inside it
 * anything happened.
 *
 * ### A hole is not a zero, and it is a drawing rule
 *
 * A bin whose known road is under half its road is `NaN`: **drawn as nothing, and its road still
 * counted for the axis.** That is all it says. The figure beside the chart is over the *buckets*
 * that are readings (§2.6), which is a different set - a known bucket inside a hole bin is in the
 * figure, and a hole bucket inside a drawn bin is not.
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

    /** Which anchored bin a metre of road belongs to. */
    fun binOf(odometerKm: Double): Long = floor(odometerKm / BIN_KM).toLong()

    /**
     * The window's buckets as the bins the box paints, oldest first.
     *
     * @param window the tail [ConsumptionWindow.raw] hands out - the last ten kilometres of road
     */
    fun of(window: List<ConsumptionSample>): ConsumptionChartSnapshot {
        if (window.isEmpty()) return ConsumptionChartSnapshot.EMPTY
        // The newest bin is the one the newest bucket's *last* metre is in. A bucket closing
        // exactly on a bin edge covers the road behind that edge, and opening an empty bin in
        // front of it right-anchored the chart against a step nothing had driven yet.
        val newest = binOf(window[window.size - 1].odometerKm - OdometerGate.KM_EPSILON)
        val base = newest - BINS + 1

        val kwh = DoubleArray(BINS)
        val km = DoubleArray(BINS)
        val known = DoubleArray(BINS)
        for (index in window.indices) {
            val bucket = window[index]
            if (bucket.km <= 0.0) continue
            val end = bucket.odometerKm
            var at = end - bucket.km
            while (at < end - OdometerGate.KM_EPSILON) {
                val bin = binOf(at)
                val to = minOf(end, (bin + 1) * BIN_KM)
                val overlap = to - at
                at = to
                // Anything older than the box is wide has already left it at the left edge.
                val slot = (bin - base).toInt()
                if (slot < 0 || slot >= BINS) continue
                val share = overlap / bucket.km
                kwh[slot] += bucket.kwh * share
                km[slot] += overlap
                known[slot] += bucket.knownKm * share
            }
        }

        var first = 0
        while (first < BINS && km[first] <= 0.0) first++
        if (first >= BINS) return ConsumptionChartSnapshot.EMPTY

        val count = BINS - first
        val values = FloatArray(count)
        val widths = FloatArray(count)
        for (index in 0 until count) {
            val at = first + index
            // The coercion is a geometric guard, not arithmetic: a bin cannot be drawn wider than
            // itself whatever a re-anchor's rounding says its overlap was.
            widths[index] = (km[at] / BIN_KM).toFloat().coerceIn(0f, 1f)
            values[index] = ConsumptionSample.valueOf(kwh[at], km[at], known[at]).toFloat()
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

    /**
     * How wide the whole chart is, in bins - which is what right-anchors it in its box.
     *
     * Summed here rather than in a draw: both renderers need it in every frame and it cannot
     * change between them, so it is counted once per sweep like everything else in this object.
     */
    val span: Float = run {
        var total = 0f
        for (index in widths.indices) total += widths[index]
        total
    }

    companion object {
        val EMPTY = ConsumptionChartSnapshot(FloatArray(0), FloatArray(0))
    }
}
