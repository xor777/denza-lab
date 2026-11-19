package dev.denza.apps.design.luminofor

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The silhouette lays its paths once per input, not once per frame.
 *
 * Until 2026-11-19 every frame of the cluster - sixty a second, against charts that change four
 * times a second at most - recomputed both charts' tangents and emitted four hundred and thirty-six
 * cubics into the same paths it had drawn the frame before. [lays] counts what [Silhouette.draw]
 * would build: one lay per input that differs from the last.
 */
class CurveMemoTest {

    private val memo = CurveMemo(4)
    private var built = 0

    private data class Geometry(
        val right: Float = 790f,
        val pitch: Float = 4f,
        val zero: Float = 364f,
        val top: Float = 300f,
        val bottom: Float = 384f,
        val upTo: Float = 60f,
        val downTo: Float = 20f,
        val originX: Float = 0f,
        val originY: Float = 0f,
        val scale: Float = 1.5f,
    )

    /** One frame: lay the paths if the memo says the input moved. */
    private fun frame(points: FloatArray, first: Int = 0, count: Int = points.size - first, g: Geometry = Geometry()) {
        if (!memo.same(
                points, first, count, g.right, g.pitch, g.zero, g.top, g.bottom, g.upTo, g.downTo,
                g.originX, g.originY, g.scale,
            )
        ) {
            built++
        }
    }

    private fun lays(block: () -> Unit): Int {
        val before = built
        block()
        return built - before
    }

    @Test
    fun sixtyFramesOfOneSweepAreOneLay() {
        val points = floatArrayOf(14f, 16f, 18f, 17f, 15f, Float.NaN)
        assertEquals(1, lays { repeat(60) { frame(points) } })
    }

    /** The cluster writes its frame's array in place: the same object is new data, or not. */
    @Test
    fun theSameArrayWithNewPointsIsANewLayAndTheSamePointsInANewArrayIsNot() {
        val points = floatArrayOf(14f, 16f, 18f, 17f, 15f)
        frame(points)
        points[4] = 21f
        assertEquals("a point moved in place", 1, lays { frame(points) })
        assertEquals("the car page's next snapshot with the same points", 0, lays { frame(points.copyOf()) })
        assertEquals("a filling window grew by one", 1, lays { frame(points + 22f) })
        assertEquals("the window slid along a longer array", 1, lays { frame(points + 22f + 23f, first = 1) })
    }

    @Test
    fun anyGeometryChangeIsANewLay() {
        val points = floatArrayOf(14f, 16f, 18f, 17f, 15f)
        val base = Geometry()
        frame(points, g = base)
        listOf(
            base.copy(right = 791f),
            base.copy(pitch = 4.1f),
            base.copy(zero = 365f),
            base.copy(top = 301f),
            base.copy(bottom = 385f),
            base.copy(upTo = 25f),
            base.copy(downTo = 21f),
            base.copy(originX = 48f),
            base.copy(originY = 372f),
            base.copy(scale = 2f),
        ).forEach { moved ->
            assertEquals("$moved", 1, lays { frame(points, g = moved) })
            assertEquals("and again is the same", 0, lays { frame(points, g = moved) })
        }
    }
}
