package dev.denza.apps.design.luminofor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * The one curve both screens and both boards draw through a chart's points
 * (`docs/energy-display-contract.md` §2.3): through every point, never past a neighbour, flat where
 * the points are flat, and on one side of the zero between two points on that side.
 */
class MonotoneCurveTest {

    /** The curve's segments, each sampled finely, as (x, y) pairs per segment. */
    private fun segments(xs: FloatArray, ys: FloatArray): List<List<Pair<Float, Float>>> {
        val n = xs.size
        val m = FloatArray(n)
        val d = FloatArray(n)
        MonotoneCurve.tangents(xs, ys, n, m, d)
        return (0 until n - 1).map { i ->
            val p0x = xs[i]
            val p0y = ys[i]
            val p1x = MonotoneCurve.c1x(xs, i)
            val p1y = MonotoneCurve.c1y(xs, ys, m, i)
            val p2x = MonotoneCurve.c2x(xs, i)
            val p2y = MonotoneCurve.c2y(xs, ys, m, i)
            val p3x = xs[i + 1]
            val p3y = ys[i + 1]
            (0..SAMPLES).map { k ->
                val t = k.toFloat() / SAMPLES
                val u = 1f - t
                val x = u * u * u * p0x + 3 * u * u * t * p1x + 3 * u * t * t * p2x + t * t * t * p3x
                val y = u * u * u * p0y + 3 * u * u * t * p1y + 3 * u * t * t * p2y + t * t * t * p3y
                x to y
            }
        }
    }

    private fun even(vararg ys: Float): Pair<FloatArray, FloatArray> =
        FloatArray(ys.size) { it * 4.5f } to ys

    @Test
    fun passesThroughEveryPoint() {
        val (xs, ys) = even(0f, 10f, 3f, 3.5f, -4f, 20f, 19f)
        segments(xs, ys).forEachIndexed { i, segment ->
            assertEquals(ys[i], segment.first().second, 1e-4f)
            assertEquals(ys[i + 1], segment.last().second, 1e-4f)
            assertEquals(xs[i], segment.first().first, 1e-4f)
            assertEquals(xs[i + 1], segment.last().first, 1e-4f)
        }
    }

    @Test
    fun neverLeavesTheBandOfItsTwoPoints() {
        val (xs, ys) = even(0f, 50f, 2f, 48f, 47f, 47.5f, 0f, -30f, 1f, 60f, 60f, 3f)
        segments(xs, ys).forEachIndexed { i, segment ->
            val lo = min(ys[i], ys[i + 1])
            val hi = max(ys[i], ys[i + 1])
            segment.forEach { (x, y) ->
                assertTrue("segment $i at x=$x: $y outside [$lo, $hi]", y >= lo - 1e-3f && y <= hi + 1e-3f)
            }
            // And x only moves forward: the curve is a function of x, no loop.
            segment.zipWithNext().forEach { (a, b) -> assertTrue("segment $i turns back", b.first >= a.first - 1e-4f) }
        }
    }

    @Test
    fun aFlatRunStaysFlat() {
        val (xs, ys) = even(5f, 12f, 12f, 12f, 12f, 3f)
        val all = segments(xs, ys)
        (1..3).forEach { i -> all[i].forEach { (_, y) -> assertEquals("segment $i", 12f, y, 1e-5f) } }
    }

    @Test
    fun betweenTwoPointsOnOneSideTheZeroIsNotCrossed() {
        // Pixel space: the zero is a line, here y = 100, and the values sit either side of it.
        val (xs, ys) = even(140f, 101f, 160f, 99f, 40f, 99.5f, 130f, 100.5f)
        segments(xs, ys).forEachIndexed { i, segment ->
            val above = ys[i] < 100f
            if (above != (ys[i + 1] < 100f)) return@forEachIndexed
            segment.forEach { (_, y) -> assertTrue("segment $i crosses the zero at $y", (y < 100f) == above) }
        }
    }

    @Test
    fun aSignChangeIsCrossedOnceAndWithoutOvershoot() {
        val (xs, ys) = even(-10f, 30f)
        val segment = segments(xs, ys).single()
        val crossings = segment.zipWithNext().count { (a, b) -> (a.second < 0f) != (b.second < 0f) }
        assertEquals(1, crossings)
    }

    @Test
    fun onePointHasNoSegmentAndTwoAreAStraightLine() {
        val m = FloatArray(1)
        MonotoneCurve.tangents(floatArrayOf(3f), floatArrayOf(7f), 1, m, FloatArray(1))
        assertEquals(0f, m[0], 0f)

        val (xs, ys) = even(2f, 11f)
        segments(xs, ys).single().forEach { (x, y) ->
            assertEquals("two points are a segment", 2f + (11f - 2f) * (x - xs[0]) / (xs[1] - xs[0]), y, 1e-4f)
        }
    }

    @Test
    fun aTurnAtAPointIsFlatThere() {
        // A peak is a turn: its tangent is level, which is what keeps it from overshooting.
        val m = FloatArray(3)
        MonotoneCurve.tangents(floatArrayOf(0f, 1f, 2f), floatArrayOf(0f, 5f, 1f), 3, m, FloatArray(3))
        assertEquals(0f, m[1], 0f)
    }

    @Test
    fun theBoardBuildsTheSameCurve() {
        val board = SpecJson.read("luminofor/luminofor.js")
        listOf(
            "m.push(d[i - 1] * d[i] <= 0 ? 0 : (d[i - 1] + d[i]) / 2)",
            "if (d[i] === 0) { m[i] = 0; m[i + 1] = 0; continue; }",
            "if (h > 9) { const t = 3 / Math.sqrt(h); m[i] = t * a * d[i]; m[i + 1] = t * b * d[i]; }",
            "const dx = (xs[i + 1] - xs[i]) / 3;",
            "path.bezierCurveTo(xs[i] + dx, ys[i] + m[i] * dx, xs[i + 1] - dx, ys[i + 1] - m[i + 1] * dx, xs[i + 1], ys[i + 1]);",
            "monotoneCurve(line, ch.map((v, i) => xOf(i)), ch.map(yOf));",
        ).forEach { line -> assertTrue("luminofor.js no longer draws «$line»", board.contains(line)) }
    }

    private companion object {
        const val SAMPLES = 64
    }
}
