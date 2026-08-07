package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgitationTrackerTest {

    @Test
    fun seedsFromTheFirstSample() {
        val tracker = AgitationTracker()
        tracker.update(agitation = 2.0, verticalAbs = 1.0, dt = 0.033)
        assertEquals(2.0, tracker.smoothedAgitation, 1e-9)
        assertEquals(1.0, tracker.verticalEnergy, 1e-9)
    }

    @Test
    fun convergesOnTheFedAgitation() {
        val tracker = AgitationTracker()
        repeat(400) { tracker.update(agitation = 1.0, verticalAbs = 0.3, dt = 0.1) }
        assertEquals(1.0, tracker.smoothedAgitation, 0.01)
        assertEquals(0.3, tracker.verticalEnergy, 0.01)
    }

    @Test
    fun verticalEnergyReactsFasterThanTheAgitationEma() {
        val tracker = AgitationTracker()
        repeat(400) { tracker.update(agitation = 0.2, verticalAbs = 0.2, dt = 0.1) }
        // One second of hard bumps: the thread-head halo (vertical energy)
        // lights up well before the slow agitation EMA has moved.
        repeat(10) { tracker.update(agitation = 3.0, verticalAbs = 3.0, dt = 0.1) }
        assertTrue("vertical=${tracker.verticalEnergy}", tracker.verticalEnergy > 2.0)
        assertTrue("smoothed=${tracker.smoothedAgitation}", tracker.smoothedAgitation < 1.5)
    }
}
