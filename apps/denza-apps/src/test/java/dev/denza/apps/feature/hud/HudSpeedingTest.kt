package dev.denza.apps.feature.hud

import dev.denza.hudlink.HudRadarWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** HUD Speed's warning of a camera at [limit] in [unit] (1 km/h, 2 mph), sampled at [at]. */
    private fun camera(limit: Int, unit: Int = 1, at: Long = 0L) =
        HudRadarWarning(at, true, HudRadarWarning.GPS_FIX, true, 1, 1, "Камера", 300, limit, unit, false, -1, "", "")

    /** The host's tick: [kmh] from the wheels held from [from] to [to] past [camera]; the red at the end. */
    private fun follow(camera: HudRadarWarning?, kmh: Double?, from: Long, to: Long): Float {
        var red = 0f
        var t = from
        while (t <= to) {
            red = speeding.update(camera, kmh, t)
            t += 50
        }
        return red
    }

    @Test
    fun aKmhCameraWithAnUnknownWheelSpeedIsNoRed() {
        assertEquals(0f, follow(camera(60), null, 0, 2_000), 0f)
        assertTrue(speeding.settled())
    }

    @Test
    fun anMphCameraWithAnUnknownWheelSpeedIsNoRed() {
        assertNull(HudSpeeding.inUnit(null, HudSpeeding.MPH))
        assertEquals(0f, follow(camera(40, unit = 2), null, 0, 2_000), 0f)
        assertTrue(speeding.settled())
    }

    @Test
    fun aCameraWithoutALimitOrNoCameraIsNoRed() {
        assertEquals(0f, follow(camera(0), 200.0, 0, 2_000), 0f)
        assertEquals(0f, follow(null, 200.0, 2_050, 4_000), 0f)
        assertEquals(0f, follow(null, null, 4_050, 5_000), 0f)
        assertTrue(speeding.settled())
    }

    @Test
    fun aSpeedThatArrivesLaterTurnsTheRedOn() {
        assertEquals(0f, follow(camera(60), null, 0, 1_000), 0f)
        assertEquals(1f, follow(camera(60), 80.0, 1_050, 4_000), 0f)
        assertEquals(0f, follow(camera(60), null, 4_050, 8_000), 0f)
    }

    @Test
    fun anMphCameraCountsInMiles() {
        // 40 mph + 10 = 50 mph = 80.47 km/h: 80 km/h is no fine, 82 km/h is.
        assertEquals(0f, follow(camera(40, unit = 2), 80.0, 0, 3_000), 0f)
        assertEquals(1f, follow(camera(40, unit = 2), 82.0, 3_050, 6_000), 0f)
        assertEquals(80.0 / 1.609344, HudSpeeding.inUnit(80.0, HudSpeeding.MPH)!!, 1e-9)
        assertEquals(80.0, HudSpeeding.inUnit(80.0, 1)!!, 0.0)
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
