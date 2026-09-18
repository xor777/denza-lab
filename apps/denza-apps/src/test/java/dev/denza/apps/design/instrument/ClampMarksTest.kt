package dev.denza.apps.design.instrument

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A cut is marked once, in the middle of the stretch that was cut.
 *
 * `docs/energy-display-contract.md` §2.3. A mark per point was a mark per closed bucket when a
 * bucket was what got drawn; the chart is a line through a hundred trailing kilometres now, and a
 * kilometre held along the ceiling is one cut - ten ticks over it would be the comb the whole wave
 * exists to remove. Both screens draw the mark from this one walk, so they cannot disagree about
 * how many there are or where they stand.
 */
class ClampMarksTest {

    private fun runs(values: FloatArray, ceiling: Float = 60f) =
        ClampMarks.of(values, first = 0, count = values.size) { it >= ceiling }

    @Test
    fun aStretchAlongTheCeilingIsOneRunHoweverLongItIs() {
        val values = floatArrayOf(10f, 20f, 61f, 70f, 90f, 65f, 30f, 12f)
        assertEquals(listOf(2 to 4), runs(values))
    }

    @Test
    fun theMarkStandsInTheMiddleOfTheRunAndNotAtItsStart() {
        val xs = floatArrayOf(0f, 10f, 20f, 30f, 40f, 50f, 60f, 70f)
        // The run covers x 20…50, so the mark is at 35.
        assertEquals(35f, ClampMarks.centre(xs, start = 2, length = 4), 1e-4f)
        // And a run of one point is that point.
        assertEquals(60f, ClampMarks.centre(xs, start = 6, length = 1), 1e-4f)
    }

    @Test
    fun aPointBackUnderTheCeilingEndsTheRunAndTheNextOneIsASecondCut() {
        val values = floatArrayOf(70f, 80f, 59f, 90f, 95f)
        assertEquals(listOf(0 to 2, 3 to 2), runs(values))
    }

    /** A hole ends a run: the road either side of it was cut twice, not once. */
    @Test
    fun aHoleEndsARunEvenThoughBothSidesAreCut() {
        val values = floatArrayOf(70f, 80f, Float.NaN, 90f, 95f)
        assertEquals(listOf(0 to 2, 3 to 2), runs(values))
    }

    @Test
    fun theFloorIsWalkedTheSameWayAndAgainstItsOwnCeiling() {
        val values = floatArrayOf(-25f, -30f, -4f, -22f)
        assertEquals(
            listOf(0 to 2, 3 to 1),
            ClampMarks.of(values, first = 0, count = values.size) { it <= -20f },
        )
    }

    /** Nothing cut is no mark at all, which is what every ordinary drive draws. */
    @Test
    fun anUncutHistoryIsUnmarked() {
        assertEquals(emptyList<Pair<Int, Int>>(), runs(floatArrayOf(10f, 59.9f, Float.NaN, 0f)))
    }

    /** The walk starts where the renderer's run starts, not where the array does. */
    @Test
    fun theOffsetIsTheRunsOwnAndTheIndicesAreRelativeToIt() {
        val values = floatArrayOf(99f, 99f, 10f, 70f, 80f, 20f)
        assertEquals(listOf(1 to 2), ClampMarks.of(values, first = 2, count = 4) { it >= 60f })
    }
}
