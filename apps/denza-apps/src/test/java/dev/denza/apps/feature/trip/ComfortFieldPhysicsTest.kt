package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ComfortFieldPhysicsTest {

    private fun ComfortFieldPhysics.run(
        seconds: Double,
        lateral: Double = 0.0,
        longitudinal: Double = 0.0,
        vertical: Double = 0.0,
        yawRate: Double = 0.0,
    ) {
        val dt = 1.0 / 30.0
        var t = 0.0
        while (t < seconds) {
            update(dt, lateral, longitudinal, vertical, yawRate)
            t += dt
        }
    }

    @Test
    fun brakingThrowsTheMembraneForward() {
        val field = ComfortFieldPhysics()
        field.run(seconds = 1.0, longitudinal = -2.5)
        // Forward is up the field, so braking gives a negative offset.
        assertTrue("offsetY=${field.offsetY}", field.offsetY < -0.4)
        assertEquals(0.0, field.offsetX, 1e-6)
    }

    @Test
    fun acceleratingPushesItBack() {
        val field = ComfortFieldPhysics()
        field.run(seconds = 1.0, longitudinal = 2.5)
        assertTrue("offsetY=${field.offsetY}", field.offsetY > 0.4)
    }

    @Test
    fun aLeftTurnPushesTheMembraneOutward() {
        // Engine convention: positive lateral = left turn; passengers go right.
        val field = ComfortFieldPhysics()
        field.run(seconds = 1.0, lateral = 3.0)
        assertTrue("offsetX=${field.offsetX}", field.offsetX > 0.5)
    }

    @Test
    fun aSmoothRoadSettlesTheSurface() {
        val field = ComfortFieldPhysics()
        field.run(seconds = 1.0, lateral = 3.0, longitudinal = -3.0)
        field.run(seconds = 3.0)
        assertTrue("offsetX=${field.offsetX}", abs(field.offsetX) < 0.05)
        assertTrue("offsetY=${field.offsetY}", abs(field.offsetY) < 0.05)
        assertEquals(0, field.rippleCount)
    }

    @Test
    fun aBumpThrowsARippleThatTravelsAndFades() {
        val field = ComfortFieldPhysics()
        field.update(1.0 / 30.0, 0.0, 0.0, 3.0, 0.0)
        assertEquals(1, field.rippleCount)
        val born = field.rippleRadius[0]

        field.run(seconds = 0.5)
        assertTrue("radius=${field.rippleRadius[0]}", field.rippleRadius[0] > born)

        // It cannot outlive the field: everything expires on a calm road.
        field.run(seconds = 4.0)
        assertEquals(0, field.rippleCount)
    }

    @Test
    fun rippleDisplacementOnlyActsNearItsFront() {
        val field = ComfortFieldPhysics()
        field.update(1.0 / 30.0, 0.0, 0.0, 3.0, 0.0)
        val atFront = field.rippleDisplacementAt(field.rippleRadius[0])
        val farAway = field.rippleDisplacementAt(field.rippleRadius[0] + 1.0)
        assertTrue("front=$atFront", atFront > 0.0)
        assertEquals(0.0, farAway, 1e-9)
    }

    @Test
    fun gentleSteeringTiltsTheFieldButNeverSpinsIt() {
        val field = ComfortFieldPhysics()
        field.run(seconds = 1.0, yawRate = 0.12)
        assertTrue("rotation=${field.rotationRad}", field.rotationRad > 0.02)

        // A hard turn is capped: a hint of a tilt, not a carousel.
        val hard = ComfortFieldPhysics()
        hard.run(seconds = 2.0, yawRate = 3.0)
        assertTrue("rotation=${hard.rotationRad}", abs(hard.rotationRad) <= 0.12)
    }

    @Test
    fun stayingStillLeavesTheFieldFlat() {
        val field = ComfortFieldPhysics()
        field.run(seconds = 2.0)
        assertEquals(0.0, field.offsetX, 1e-9)
        assertEquals(0.0, field.offsetY, 1e-9)
        assertEquals(0.0, field.rotationRad, 1e-9)
        assertEquals(0, field.rippleCount)
    }
}
