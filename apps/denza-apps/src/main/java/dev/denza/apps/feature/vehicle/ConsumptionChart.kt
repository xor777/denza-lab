package dev.denza.apps.feature.vehicle

import kotlin.math.roundToInt

/**
 * The last ten kilometres of **recorded** road as the shape both screens draw. One chart, drawn
 * twice.
 *
 * `docs/energy-display-contract.md` §2.3. The cluster's petal and the head unit's car page used to
 * carry two different histories of two different quantities on two different axes, which is why
 * they could never be compared; they are one object now and the pixel height is all that differs.
 *
 * ### The axis is recorded road, so there are no holes
 *
 * A point per **reading** bucket ([ConsumptionSample.isKnown]) - a hundred metres of road the log
 * knows the energy over - in the order they closed. Road the log did not record does not exist on
 * this axis: the line is continuous from its first point to its last, a gap in the record is a seam
 * nothing marks, and the chart's width says the same thing as the unit beside it - «за 8,6 км» is
 * 86 % of the box.
 *
 * The board before this one stood its points on the odometer's own grid and drew the road nobody
 * recorded as `NaN`; the first thing it drew on the car was the 4.7 km the hub had slept through
 * that afternoon, which is a chart that is mostly the absence of a chart. The owner's rule,
 * 2026-09-18: «есть данные - график доливается, нет данных - не доливается». The grid, the pro-rata
 * filing, the `NaN` points and the half-known-kilometre rule went with the holes; a bucket that is
 * not a reading is skipped, and the points either side of it are neighbours.
 *
 * What the odometer's grid bought - a shape that does not re-phase - this has for free. A point's
 * value is decided by the readings that closed at or before it, so a point settles the moment its
 * ten readings exist, and a new bucket appends one point and moves the rest one pitch left.
 *
 * ### A point is a kilometre of recorded road
 *
 * `Σ kWh / Σ knownKm × 100` over the last [SMOOTH_STEPS] reading buckets ending at the point. A
 * trailing mean rather than a centred one, so the newest point is a figure with a meaning of its
 * own - what the last kilometre cost - instead of a shape that ends half a kilometre behind the car.
 *
 * Twenty steps of five hundred metres came before this and the owner read them off the car as
 * «огромные ступеньки»: the car's own journal of 2026-09-18 has neighbouring 500 m steps differing
 * by 20 kWh/100 km at the median, which is a comb however it is drawn. Over a kilometre the median
 * jump is 9, which is a curve.
 *
 * ### Filling
 *
 * While fewer than [SMOOTH_STEPS] readings stand behind a point the mean is over what there is, and
 * never over fewer than [MIN_STEPS]: a log with four readings in it draws nothing at all, and the
 * chart appears at the fifth. Nothing is ever drawn as an invented zero and nothing is drawn from
 * fewer than half a kilometre of readings, which is why the earliest points of a fresh log share
 * that first half kilometre rather than each being the spike of its own hundred metres.
 *
 * Built once per sweep in [VehicleTelemetryHub] from the log's own closed buckets - the retained
 * road, so the oldest drawn point still has a kilometre of readings behind it - and carried in
 * [VehicleTelemetry] as a primitive array: this is read in every frame of a sixty-frame panel and
 * built in none of them.
 */
internal object ConsumptionChart {

    /**
     * The road one point stands for - the odometer's own hundred metres, which is the log's bucket.
     *
     * Not a grid any more: it is the pitch the box is divided by and the number that turns a count
     * of points into the road under them, which is what the unit beside the chart names.
     */
    const val PITCH_KM = ConsumptionLog.DEFAULT_BUCKET_KM

    /** A hundred, which is the window over the pitch rather than a second statement of either. */
    val POINTS: Int = (ConsumptionWindow.KM / PITCH_KM).roundToInt()

    /** How many reading buckets one point is the mean of: a kilometre of recorded road. */
    const val SMOOTH_STEPS = 10

    /**
     * And the fewest it is ever taken over.
     *
     * A mean over one hundred-metre bucket is the comb this chart exists to be rid of, drawn as a
     * single point. Half a kilometre is where the owner's own journal stops swinging by more than
     * the ladder's own resolution between neighbours.
     */
    const val MIN_STEPS = 5

    /** The same smoothing said in road, which is what the boards and the prose call it. */
    val SMOOTH_KM: Double = SMOOTH_STEPS * PITCH_KM

    /**
     * The recorded road as the points the box draws, oldest first.
     *
     * @param buckets the log's closed buckets, oldest first ([ConsumptionLog.buckets]). The whole
     *   retention rather than the window, so the oldest drawn point has its own kilometre of
     *   readings behind it; the newest [POINTS] of them are what comes back.
     */
    fun of(buckets: List<ConsumptionSample>): ConsumptionChartSnapshot {
        if (buckets.isEmpty()) return ConsumptionChartSnapshot.EMPTY

        // Where the readings are, once. A bucket that is not one is not a hole and not a zero: it
        // is off this axis, and the readings either side of it are neighbours.
        val readings = IntArray(buckets.size)
        var found = 0
        for (index in buckets.indices) {
            val bucket = buckets[index]
            if (ConsumptionSample.isKnown(bucket.km, bucket.knownKm)) readings[found++] = index
        }
        if (found < MIN_STEPS) return ConsumptionChartSnapshot.EMPTY

        val first = maxOf(0, found - POINTS)
        val values = FloatArray(found - first)
        for (at in first until found) {
            var from = at - SMOOTH_STEPS + 1
            if (from < 0) from = 0
            var to = at
            // Fewer than half a kilometre stands behind this one, so the mean reaches forward into
            // the log's first half kilometre instead of narrowing. Only the first four points of a
            // fresh log can be here, and they share one mean rather than each being a spike.
            if (to - from + 1 < MIN_STEPS) to = from + MIN_STEPS - 1
            var kwh = 0.0
            var knownKm = 0.0
            for (step in from..to) {
                val bucket = buckets[readings[step]]
                kwh += bucket.kwh
                knownKm += bucket.knownKm
            }
            // A reading has known road by definition, so this is never a division by zero and the
            // array never carries a `NaN`.
            values[at - first] = (kwh / knownKm * 100.0).toFloat()
        }
        return ConsumptionChartSnapshot(values)
    }
}

/**
 * What a renderer is handed: one value per point, oldest first, and never a `NaN`.
 *
 * An array of primitives rather than a list of boxed floats because this is read in every frame and
 * built in none of them. There are no widths and no holes: every point is one [ConsumptionChart.
 * PITCH_KM] of recorded road wide, and the run is right-anchored by [span].
 */
internal class ConsumptionChartSnapshot(val values: FloatArray) {

    val isEmpty: Boolean get() = values.isEmpty()

    /**
     * How many points the chart has, which is what right-anchors it in its box.
     *
     * At most [ConsumptionChart.POINTS]; fewer while the log is still filling, and `span × PITCH_KM`
     * is the recorded road under it - the same road the unit beside the figure names.
     */
    val span: Int get() = values.size

    companion object {
        val EMPTY = ConsumptionChartSnapshot(FloatArray(0))
    }
}
