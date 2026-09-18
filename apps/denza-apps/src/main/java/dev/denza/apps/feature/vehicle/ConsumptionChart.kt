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
 * ### A point every hundred metres, and every point is the last kilometre
 *
 * The axis is the odometer's own grid of closed [PITCH_KM] buckets, and the point standing at grid
 * step `k` is `Σ kWh / Σ km × 100` over the buckets whose road lies in the kilometre ending there.
 * A trailing mean rather than a centred one, so the newest point is a figure with a meaning of its
 * own - what the last kilometre cost - instead of a shape that ends half a kilometre behind the car.
 *
 * Twenty steps of five hundred metres came before this and the owner read them off the car as
 * «огромные ступеньки»: the car's own journal of 2026-09-18 has neighbouring 500 m steps differing
 * by 20 kWh/100 km at the median, which is a comb however it is drawn. Over a kilometre the median
 * jump is 9, which is a curve.
 *
 * ### The grid is the axis, and a bucket is filed by the road it covers
 *
 * `floor(odometer / PITCH_KM)`, not "group the tail from the oldest". Grouping from the oldest
 * re-phases every point the moment a bucket closes, so the same road never comes back the same
 * shape. And a bucket that closed at 100.5 km covers `[100.4, 100.5)`, which is the step that ends
 * at 100.5 and not the one that starts there - so each bucket's road is walked and each step is
 * given its overlap; **the energy and the known road go with it pro rata by road**, which is the
 * only division a bucket supports. It holds one integral over one stretch and no record of where
 * inside it anything happened.
 *
 * ### A hole is a point, and it is a drawing rule
 *
 * A point with under half a kilometre of known road behind it is `NaN` ([ConsumptionSample.isKnown]
 * asked of the kilometre, not of whatever road happened to be recorded - a point right after a
 * hole is the mean of a hundred metres, which is the spike this chart exists to be rid of, so the
 * line resumes half a kilometre after a hole rather than at once): drawn as nothing, the line
 * breaks there, and the road under it is still on the axis. A grid step no bucket covers at all is `NaN` too, for the same reason and more bluntly
 * - the points stand on the odometer's grid, so a stretch the log has no record of is a stretch of
 * holes and never a compression of the axis. There are no partial widths anywhere: the figure's own
 * exclusion is §2.6's and is a different question.
 *
 * ### It is handed a kilometre more road than it draws
 *
 * The first point of a full window is a mean over road *older* than the window, so [of] takes
 * [TAIL_KM] of tail ([ConsumptionLog.chartTail]) and emits [POINTS] points over the newest
 * [ConsumptionWindow.KM] of it. A log with less shows fewer points, right-anchored where new road
 * arrives; the figure beside the chart keeps its own ten-kilometre window and is unaffected.
 *
 * Built once per sweep in [VehicleTelemetryHub] and carried in [VehicleTelemetry] as a primitive
 * array: this is read in every frame of a sixty-frame panel and built in none of them.
 */
internal object ConsumptionChart {

    /**
     * One point per closed bucket of road - the odometer's own hundred metres.
     *
     * The pitch is the log's bucket rather than a number of this object's own: a grid finer than
     * the record would interpolate and a coarser one would re-phase.
     */
    const val PITCH_KM = ConsumptionLog.DEFAULT_BUCKET_KM

    /** And what each point is the mean of: the kilometre of road ending at it. */
    const val SMOOTH_KM = 1.0

    /** The road [of] needs behind the window, so its first point is a whole kilometre too. */
    const val TAIL_KM = ConsumptionWindow.KM + SMOOTH_KM

    /** A hundred, which is the window over the pitch rather than a second statement of either. */
    val POINTS: Int = (ConsumptionWindow.KM / PITCH_KM).roundToInt()

    /** And how many grid steps one trailing kilometre covers. */
    val SMOOTH_STEPS: Int = (SMOOTH_KM / PITCH_KM).roundToInt()

    /** Which grid step a metre of road belongs to. */
    fun stepOf(odometerKm: Double): Long = floor(odometerKm / PITCH_KM).toLong()

    /**
     * The tail's road as the points the box draws, oldest first.
     *
     * @param tail the last [TAIL_KM] of road, which is [ConsumptionLog.chartTail]
     */
    fun of(tail: List<ConsumptionSample>): ConsumptionChartSnapshot {
        if (tail.isEmpty()) return ConsumptionChartSnapshot.EMPTY
        // The newest point is the step the newest bucket's *last* metre is in. A bucket closing
        // exactly on a step's edge covers the road behind that edge, and opening an empty step in
        // front of it right-anchored the chart against road nothing had driven yet.
        val newest = stepOf(tail[tail.size - 1].odometerKm - OdometerGate.KM_EPSILON)
        val base = newest - POINTS + 1
        // The accumulation reaches a kilometre further back than the first point does, because
        // that point is a mean over the kilometre ending at it.
        val slotBase = base - (SMOOTH_STEPS - 1)
        val slots = POINTS + SMOOTH_STEPS - 1

        val kwh = DoubleArray(slots)
        val km = DoubleArray(slots)
        val known = DoubleArray(slots)
        for (index in tail.indices) {
            val bucket = tail[index]
            if (bucket.km <= 0.0) continue
            val end = bucket.odometerKm
            val start = end - bucket.km
            // Walked over the **steps** the road covers rather than by advancing along the road
            // itself. The road walk read the next edge back as `(step + 1) * PITCH_KM`, which at a
            // pitch of a tenth can round to a value that is not past the metre it was asked about,
            // and then the walk stands still for ever. The half-kilometre bins got away with it
            // because a half is exact in binary and a tenth is not. Termination is the loop's own
            // bound now rather than a property of the arithmetic inside it.
            //
            // **Both ends are taken a millimetre inside the bucket**, which is the same epsilon the
            // odometer is read with. `end - km` lands a hair *below* a grid edge for two thirds of
            // the edges this car closes a bucket on, so a hundred-metre bucket claimed a sliver of
            // the step before it - one extra point, holding a hundred-thousandth of a micron of
            // road, drawn at the left edge of the box.
            val from = maxOf(stepOf(start + OdometerGate.KM_EPSILON), slotBase)
            val to = minOf(stepOf(end - OdometerGate.KM_EPSILON), slotBase + slots - 1)
            for (step in from..to) {
                val overlap = minOf(end, (step + 1) * PITCH_KM) - maxOf(start, step * PITCH_KM)
                if (overlap <= 0.0) continue
                val slot = (step - slotBase).toInt()
                val share = overlap / bucket.km
                kwh[slot] += bucket.kwh * share
                km[slot] += overlap
                known[slot] += bucket.knownKm * share
            }
        }

        // Where the run starts: the oldest grid step of the window the log has any road in. Older
        // steps are *absent* rather than `NaN` - the log simply does not reach them - which is what
        // anchors a filling chart at the right edge. A step with no road *inside* the run is a hole.
        var first = 0
        while (first < POINTS && km[first + SMOOTH_STEPS - 1] <= 0.0) first++
        if (first >= POINTS) return ConsumptionChartSnapshot.EMPTY

        val count = POINTS - first
        val values = FloatArray(count)
        for (index in 0 until count) {
            val at = first + index + SMOOTH_STEPS - 1
            if (km[at] <= 0.0) {
                // No bucket covers this hundred metres at all. The line breaks and the axis keeps
                // its place; a mean over the kilometre behind it would draw across a gap.
                values[index] = Float.NaN
                continue
            }
            var trailingKwh = 0.0
            var trailingKm = 0.0
            var trailingKnown = 0.0
            for (slot in at - SMOOTH_STEPS + 1..at) {
                trailingKwh += kwh[slot]
                trailingKm += km[slot]
                trailingKnown += known[slot]
            }
            // The half-known rule is asked of the whole kilometre, not of the road recorded in it:
            // after a hole the first point is drawn when half a kilometre is known, not after the
            // first hundred metres.
            values[index] =
                ConsumptionSample.valueOf(trailingKwh, SMOOTH_KM, trailingKnown).toFloat()
        }
        return ConsumptionChartSnapshot(values)
    }
}

/**
 * What a renderer is handed: one value per point, oldest first, `NaN` where the line breaks.
 *
 * An array of primitives rather than a list of boxed floats because this is read in every frame and
 * built in none of them. There are no widths: every point is one grid step wide, and the run is
 * right-anchored by [span].
 */
internal class ConsumptionChartSnapshot(val values: FloatArray) {

    val isEmpty: Boolean get() = values.isEmpty()

    /**
     * How many points the chart has, which is what right-anchors it in its box.
     *
     * At most [ConsumptionChart.POINTS]; fewer while the log is still filling, because the grid
     * steps before its first bucket are absent rather than empty.
     */
    val span: Int get() = values.size

    companion object {
        val EMPTY = ConsumptionChartSnapshot(FloatArray(0))
    }
}
