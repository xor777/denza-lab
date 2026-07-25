package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadLayoutTest {

    private val width = 1786f
    private val full = 30_000f

    @Test
    fun growsAtAFixedScaleUntilItFillsThePanel() {
        val fixed = width / full
        assertEquals(fixed, ThreadLayout.horizontalScale(0f, width, full), 1e-9f)
        assertEquals(fixed, ThreadLayout.horizontalScale(2_000f, width, full), 1e-9f)
        assertEquals(fixed, ThreadLayout.horizontalScale(full, width, full), 1e-9f)
    }

    @Test
    fun aShortTripOccupiesOnlyItsShareOfTheWidth() {
        val scale = ThreadLayout.horizontalScale(3_000f, width, full)
        val drawn = 3_000f * scale
        assertEquals(width / 10f, drawn, 0.5f)
    }

    @Test
    fun compressesOnceTheTripOutgrowsThePanel() {
        val travelled = 160_000f
        val scale = ThreadLayout.horizontalScale(travelled, width, full)
        assertTrue(scale < width / full)
        // Whatever the distance, the whole trip still lands inside the panel.
        assertEquals(width, travelled * scale, 0.5f)
    }

    @Test
    fun flatTerrainKeepsTheMinimumBand() {
        // A parked/flat trip whose altitude only wanders a few metres must not be
        // stretched across the whole band.
        assertEquals(60f, ThreadLayout.bandSpan(430f, 438f, 60f), 1e-9f)
    }

    @Test
    fun realHillsUseTheirOwnBand() {
        assertEquals(240f, ThreadLayout.bandSpan(300f, 540f, 60f), 1e-9f)
    }

    @Test
    fun anchorsAnEventToThePointItHappenedAt() {
        val elapsed = floatArrayOf(0f, 10f, 20f, 30f, 40f)
        assertEquals(0, ThreadLayout.anchorIndex(elapsed, 5, 0.0))
        assertEquals(2, ThreadLayout.anchorIndex(elapsed, 5, 25.0))
        assertEquals(3, ThreadLayout.anchorIndex(elapsed, 5, 30.0))
        assertEquals(4, ThreadLayout.anchorIndex(elapsed, 5, 999.0))
    }

    @Test
    fun aLongStopDoesNotSlideLaterLabels() {
        // 0-60 s driving, then an 18-minute stop, then driving again. The label
        // for the event at 70 s must stay on the pre-stop point, even though it
        // sits early in trip TIME but late in trip DISTANCE.
        val elapsed = floatArrayOf(0f, 30f, 60f, 70f, 1150f, 1180f)
        assertEquals(3, ThreadLayout.anchorIndex(elapsed, 6, 70.0))
        // A proportional guess would have landed at index 0 (70/1180 of the way).
        assertEquals(4, ThreadLayout.anchorIndex(elapsed, 6, 1160.0))
    }
}
