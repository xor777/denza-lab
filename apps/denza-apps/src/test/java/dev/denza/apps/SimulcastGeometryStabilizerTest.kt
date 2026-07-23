package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimulcastGeometryStabilizerTest {
    private data class Geometry(val left: Int, val top: Int)

    private val stabilizer = SimulcastGeometryStabilizer<Geometry>(
        100L,
        2,
    ) { first, second, epsilon ->
        kotlin.math.abs(first.left - second.left) <= epsilon &&
            kotlin.math.abs(first.top - second.top) <= epsilon
    }

    @Test
    fun `requires two equivalent samples at least 100 ms apart`() {
        assertNull(stabilizer.offer(1_000L, Geometry(10, 20)))
        assertNull(stabilizer.offer(1_099L, Geometry(11, 19)))

        assertEquals(Geometry(12, 20), stabilizer.offer(1_100L, Geometry(12, 20)))
    }

    @Test
    fun `returns exact latest sample after jitter within epsilon`() {
        assertNull(stabilizer.offer(2_000L, Geometry(100, 200)))

        assertEquals(Geometry(102, 198), stabilizer.offer(2_100L, Geometry(102, 198)))
    }

    @Test
    fun `jitter beyond epsilon restarts stability interval`() {
        assertNull(stabilizer.offer(3_000L, Geometry(10, 20)))
        assertNull(stabilizer.offer(3_100L, Geometry(13, 20)))
        assertNull(stabilizer.offer(3_199L, Geometry(13, 20)))

        assertEquals(Geometry(13, 20), stabilizer.offer(3_200L, Geometry(13, 20)))
    }

    @Test
    fun `animated positions do not stabilize until motion stops`() {
        assertNull(stabilizer.offer(4_000L, Geometry(0, 0)))
        assertNull(stabilizer.offer(4_050L, Geometry(10, 0)))
        assertNull(stabilizer.offer(4_100L, Geometry(20, 0)))
        assertNull(stabilizer.offer(4_199L, Geometry(20, 0)))

        assertEquals(Geometry(20, 0), stabilizer.offer(4_200L, Geometry(20, 0)))
    }

    @Test
    fun `reset requires a fresh stable pair`() {
        assertNull(stabilizer.offer(5_000L, Geometry(10, 20)))
        assertEquals(Geometry(10, 20), stabilizer.offer(5_100L, Geometry(10, 20)))

        stabilizer.reset()

        assertNull(stabilizer.offer(5_200L, Geometry(10, 20)))
    }
}
