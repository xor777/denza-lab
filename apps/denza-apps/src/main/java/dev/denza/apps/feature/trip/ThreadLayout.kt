package dev.denza.apps.feature.trip

import kotlin.math.max
import kotlin.math.min

/**
 * The two layout rules of the journey thread, kept Android-free so they can be
 * unit-tested on the JVM (the renderer itself is Canvas-bound).
 */
object ThreadLayout {

    /**
     * Pixels per travelled metre.
     *
     * The thread grows from the left edge at a FIXED scale — [fullWidthMeters]
     * spans the panel — so early progress is visible as movement rather than as
     * a rescale. Only once the trip would overflow does the scale shrink, so the
     * whole journey stays on screen.
     */
    fun horizontalScale(travelledMeters: Float, usableWidth: Float, fullWidthMeters: Float): Float {
        val fixed = usableWidth / fullWidthMeters
        if (travelledMeters <= 1f) return fixed
        return min(fixed, usableWidth / travelledMeters)
    }

    /**
     * Height of the elevation band the thread is normalised against.
     *
     * Never narrower than [minSpanMeters]: without that floor, a flat road's GPS
     * noise gets stretched across the full band and the thread reads as an
     * oscilloscope trace rather than terrain.
     */
    fun bandSpan(minAltitude: Float, maxAltitude: Float, minSpanMeters: Float): Float =
        max(minSpanMeters, maxAltitude - minAltitude)

    /**
     * Index of the last thread point recorded at or before [seconds] of trip time,
     * over the ascending [elapsed] series.
     *
     * Events are placed by trip time rather than by a fraction of it, because the
     * thread's axis is distance: an 18-minute stop consumes time but no width, so
     * a proportional guess would slide every later label to the right.
     */
    fun anchorIndex(elapsed: FloatArray, count: Int, seconds: Double): Int {
        if (count <= 0) return 0
        var lo = 0
        var hi = count - 1
        var found = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (elapsed[mid] <= seconds) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}
