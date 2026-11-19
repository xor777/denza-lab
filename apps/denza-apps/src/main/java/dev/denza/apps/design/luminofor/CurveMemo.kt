package dev.denza.apps.design.luminofor

/**
 * What a [Silhouette] last laid its paths from: the points and the geometry they were placed on.
 *
 * The cluster draws sixty frames a second and its two charts change four times a second at most,
 * so the curve - a hundred tangents and as many cubics on the line and again on its field - was
 * rebuilt about fifteen times for every new point. [same] answers whether this frame's input is the
 * last one's, and records it when it is not; the silhouette lays its paths only then.
 *
 * **The points are compared by value**, not by array: the cluster's frame reuses one array and
 * writes it in place every frame, so the array is the same object whether or not anything in it
 * moved, and the car page hands a new array with each snapshot even when its points are the same.
 * A hundred and twenty floats compared is nothing beside the curve they stand for. Compared as raw
 * bits, so a `NaN` is the same as itself.
 *
 * The geometry is everything the paths' pixels depend on: the window's placement and ladder in
 * units, and the pen's origin and scale, which is how a pane resized or a density changed is a new
 * curve.
 */
internal class CurveMemo(capacity: Int) {

    private var held = FloatArray(capacity)
    private var count = -1
    private val geometry = FloatArray(GEOMETRY)

    /**
     * Whether [count] points of [points] from [first], on the geometry after them, are what was laid
     * last time. When they are not, they are now: the caller lays its paths and draws.
     *
     * @param originX the pen's pixel at unit zero across, which with [originY] and [scale] is the
     *   pen's whole transform
     */
    @Suppress("LongParameterList")
    fun same(
        points: FloatArray,
        first: Int,
        count: Int,
        right: Float,
        pitch: Float,
        zero: Float,
        top: Float,
        bottom: Float,
        upTo: Float,
        downTo: Float,
        originX: Float,
        originY: Float,
        scale: Float,
    ): Boolean {
        var same = count == this.count
        if (same) {
            for (i in 0 until count) {
                if (points[first + i].toRawBits() != held[i].toRawBits()) {
                    same = false
                    break
                }
            }
        }
        same = keep(0, right, same)
        same = keep(1, pitch, same)
        same = keep(2, zero, same)
        same = keep(3, top, same)
        same = keep(4, bottom, same)
        same = keep(5, upTo, same)
        same = keep(6, downTo, same)
        same = keep(7, originX, same)
        same = keep(8, originY, same)
        same = keep(9, scale, same)
        if (same) return true
        if (held.size < count) held = FloatArray(count)
        points.copyInto(held, 0, first, first + count)
        this.count = count
        return false
    }

    /** Record one geometry value, and whether everything so far still matches. */
    private fun keep(index: Int, value: Float, same: Boolean): Boolean {
        val was = geometry[index]
        geometry[index] = value
        return same && was.toRawBits() == value.toRawBits()
    }

    private companion object {
        const val GEOMETRY = 10
    }
}
