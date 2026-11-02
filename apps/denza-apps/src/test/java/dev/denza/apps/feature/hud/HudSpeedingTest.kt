package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner's rule: more than the camera's limit plus ten is a fine, and the red comes and goes smoothly. */
class HudSpeedingTest {
    private val speeding = HudSpeeding()

    /** [speed] held from [from] to [to], in 50 ms steps; the red at the end. */
    private fun drive(limit: Int, speed: Double?, from: Long, to: Long): Float {
        var red = 0f
        var t = from
        while (t <= to) {
            red = speeding.update(limit, speed, t)
            t += 50
        }
        return red
    }

    @Test
    fun tenOverIsNotAFineAndMoreThanTenIs() {
        assertEquals(0f, drive(60, 70.0, 0, 3_000), 0f)
        assertEquals(1f, drive(60, 70.5, 3_050, 6_000), 0f)
    }

    @Test
    fun theRedComesOverAMomentNotAtOnce() {
        drive(60, 60.0, 0, 1_000)
        val first = speeding.update(60, 80.0, 1_050)
        assertTrue(first in 0.05f..0.3f)
        val third = drive(60, 80.0, 1_100, 1_400)
        assertTrue(third in 0.55f..0.8f)
        assertEquals(1f, drive(60, 80.0, 1_450, 3_500), 0f)
    }

    @Test
    fun itGoesSlowerThanItCameAndOnlyAtEightOver() {
        drive(60, 80.0, 0, 3_000)
        assertEquals(1f, drive(60, 68.5, 3_050, 6_000), 0f)
        val gone = drive(60, 68.0, 6_050, 6_400)
        assertTrue(gone in 0.4f..0.8f)
        assertEquals(0f, drive(60, 68.0, 6_450, 10_000), 0f)
        assertTrue(speeding.settled())
    }

    @Test
    fun noLimitOrNoSpeedIsNoFine() {
        assertEquals(1f, drive(60, 90.0, 0, 3_000), 0f)
        assertEquals(0f, drive(60, null, 3_050, 8_000), 0f)
        assertEquals(0f, drive(0, 200.0, 8_050, 12_000), 0f)
        assertTrue(speeding.settled())
    }

    @Test
    fun theRedMovesInStepsAPictureCanShow() {
        drive(60, 60.0, 0, 100)
        val red = speeding.update(60, 90.0, 133)
        assertEquals(0f, (red * 32f) % 1f, 0f)
    }

    @Test
    fun aResetIsNoRedAtOnce() {
        drive(60, 90.0, 0, 3_000)
        speeding.reset()
        assertEquals(0f, speeding.level(), 0f)
        assertTrue(speeding.settled())
    }
}
