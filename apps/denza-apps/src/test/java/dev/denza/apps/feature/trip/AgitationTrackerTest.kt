package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgitationTrackerTest {

    @Test
    fun smoothRideScoresHigh() {
        val tracker = AgitationTracker()
        repeat(400) { tracker.update(agitation = 0.1, verticalAbs = 0.1, dt = 0.1) }
        assertTrue("score=${tracker.smoothnessScore}", tracker.smoothnessScore >= 90)
    }

    @Test
    fun smoothCityDrivingScoresEightyToNinety() {
        val tracker = AgitationTracker()
        // ~1 m/s^2 smoothed agitation is a normal city mix of gentle corners,
        // braking, and pavement texture with the physics channels.
        repeat(400) { tracker.update(agitation = 1.0, verticalAbs = 0.3, dt = 0.1) }
        assertTrue("score=${tracker.smoothnessScore}", tracker.smoothnessScore in 80..90)
    }

    @Test
    fun aggressiveDrivingScoresFiftyToSeventy() {
        val tracker = AgitationTracker()
        // ~3 m/s^2 smoothed = sustained hard corners/braking (comfortable corner
        // is 2-3 m/s^2 peak, so 3 sustained is genuinely aggressive).
        repeat(400) { tracker.update(agitation = 3.0, verticalAbs = 0.8, dt = 0.1) }
        assertTrue("score=${tracker.smoothnessScore}", tracker.smoothnessScore in 50..70)
    }

    @Test
    fun calmTimerSurvivesNormalDrivingAndResetsOnHardImpulse() {
        val tracker = AgitationTracker()
        repeat(50) { tracker.update(agitation = 0.3, verticalAbs = 0.1, dt = 0.1) }
        assertTrue(tracker.calmSeconds > 4.0)
        // A comfortable corner (~3 m/s^2, below the 3.5 impulse threshold) must
        // NOT reset "Без всплесков".
        tracker.update(agitation = 3.0, verticalAbs = 0.5, dt = 0.1)
        assertTrue(tracker.calmSeconds > 4.0)
        // A hard impulse above the threshold resets it.
        tracker.update(agitation = 4.0, verticalAbs = 2.0, dt = 0.1)
        assertEquals(0.0, tracker.calmSeconds, 1e-9)
    }
}
