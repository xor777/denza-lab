package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class MotionSicknessTest {

    private fun MotionSickness.run(
        seconds: Double,
        lateral: Double = 0.0,
        longitudinal: Double = 0.0,
        vertical: Double = 0.0,
    ) {
        val dt = 0.01
        repeat((seconds / dt).roundToInt()) { update(dt, lateral, longitudinal, vertical) }
    }

    @Test
    fun oneFirmBrakeIsExactlyOneDose() {
        val ms = MotionSickness()
        ms.run(1.0, longitudinal = -3.0)
        assertEquals(0, ms.manoeuvreCount) // still braking: nothing charged yet
        ms.run(1.0)
        assertEquals(1, ms.manoeuvreCount)
        // A 3 m/s^2 brake is firm: 0.265 * 1.4 = 0.371, minus ~2 s of recovery.
        assertTrue("nausea=${ms.nausea}", ms.nausea in 0.32..0.38)
    }

    @Test
    fun aLongCornerIsStillOneDose() {
        val ms = MotionSickness()
        ms.run(10.0, lateral = 2.5)
        ms.run(2.0)
        assertEquals(1, ms.manoeuvreCount)
    }

    @Test
    fun refractoryGapKeepsBackToBackSpikesFromDoubling() {
        val ms = MotionSickness()
        ms.run(0.5, longitudinal = -2.0)
        ms.run(0.5) // dose charged here; 1.4 s refractory starts
        ms.run(0.3, longitudinal = -2.0) // inside the refractory gap
        ms.run(0.5)
        assertEquals(1, ms.manoeuvreCount)

        // Once the gap has expired, a new manoeuvre counts again.
        ms.run(1.5)
        ms.run(0.5, longitudinal = -2.0)
        ms.run(0.5)
        assertEquals(2, ms.manoeuvreCount)
    }

    @Test
    fun threeFirmManoeuvresReachFullNausea() {
        val ms = MotionSickness()
        repeat(3) {
            ms.run(1.0, longitudinal = -3.0)
            ms.run(1.6)
        }
        assertEquals(3, ms.manoeuvreCount)
        assertTrue("nausea=${ms.nausea}", ms.nausea > 0.9)
    }

    @Test
    fun recoveryDrainsAtOneFortyFifthPerSecond() {
        val ms = MotionSickness()
        ms.run(1.0, longitudinal = -3.0)
        ms.run(0.1) // charge the dose
        val charged = ms.nausea
        assertTrue(charged > 0.3)
        ms.run(9.0)
        assertEquals(charged - 9.0 / MotionSickness.RECOVERY_SECONDS, ms.nausea, 0.01)
        ms.run(40.0)
        assertEquals(0.0, ms.nausea, 1e-9)
    }

    @Test
    fun roughRoadTricklesSlowly() {
        val ms = MotionSickness()
        ms.run(10.0, vertical = 2.0)
        // Trickle minus the ever-running recovery: a net creep, not a spike.
        val expected = 10.0 * (MotionSickness.ROUGH_RATE - 1.0 / MotionSickness.RECOVERY_SECONDS)
        assertEquals(expected, ms.nausea, 5e-3)
        assertEquals(0, ms.manoeuvreCount)

        // Below the vertical threshold nothing accumulates at all.
        val calm = MotionSickness()
        calm.run(10.0, vertical = 1.0)
        assertEquals(0.0, calm.nausea, 1e-9)
    }
}
