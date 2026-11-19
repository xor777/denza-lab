package dev.denza.apps.design.luminofor

import kotlin.math.sqrt

/**
 * The curve every chart on both screens draws through its points: a monotone cubic Hermite spline
 * with Fritsch-Carlson tangents, `monotoneCurve()` in `luminofor.js` line for line.
 *
 * It passes through every point, never overshoots a neighbour - between two points it stays inside
 * the band their two values span - and so between two points on the same side of the zero it never
 * crosses the zero. A flat run stays flat. Two points are a straight segment.
 *
 * Pure arithmetic and no allocation: the caller owns the arrays, sized once to its point capacity,
 * because [Silhouette] runs this in every frame of a sixty-frame panel.
 */
internal object MonotoneCurve {

    /**
     * The tangent at each of the first [n] points into [m], using [d] for the secants.
     *
     * [m] and [d] must hold at least [n] entries; nothing else is touched. A single point has the
     * tangent zero, and two have the secant at both ends.
     */
    fun tangents(xs: FloatArray, ys: FloatArray, n: Int, m: FloatArray, d: FloatArray) {
        if (n <= 0) return
        if (n == 1) {
            m[0] = 0f
            return
        }
        for (i in 0 until n - 1) d[i] = (ys[i + 1] - ys[i]) / (xs[i + 1] - xs[i])
        m[0] = d[0]
        for (i in 1 until n - 1) m[i] = if (d[i - 1] * d[i] <= 0f) 0f else (d[i - 1] + d[i]) / 2f
        m[n - 1] = d[n - 2]
        for (i in 0 until n - 1) {
            if (d[i] == 0f) {
                m[i] = 0f
                m[i + 1] = 0f
                continue
            }
            val a = m[i] / d[i]
            val b = m[i + 1] / d[i]
            val h = a * a + b * b
            if (h > 9f) {
                val t = 3f / sqrt(h)
                m[i] = t * a * d[i]
                m[i + 1] = t * b * d[i]
            }
        }
    }

    /** The first control point of the segment from point [i] to point `i + 1`: x, then y. */
    fun c1x(xs: FloatArray, i: Int): Float = xs[i] + (xs[i + 1] - xs[i]) / 3f

    fun c1y(xs: FloatArray, ys: FloatArray, m: FloatArray, i: Int): Float =
        ys[i] + m[i] * (xs[i + 1] - xs[i]) / 3f

    /** And its second. */
    fun c2x(xs: FloatArray, i: Int): Float = xs[i + 1] - (xs[i + 1] - xs[i]) / 3f

    fun c2y(xs: FloatArray, ys: FloatArray, m: FloatArray, i: Int): Float =
        ys[i + 1] - m[i + 1] * (xs[i + 1] - xs[i]) / 3f
}
