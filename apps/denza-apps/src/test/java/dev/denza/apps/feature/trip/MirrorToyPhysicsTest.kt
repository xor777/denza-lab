package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

class MirrorToyPhysicsTest {

    private fun MirrorToyPhysics.run(
        seconds: Double,
        lateral: Double = 0.0,
        longitudinal: Double = 0.0,
        vertical: Double = 0.0,
    ) {
        val dt = 1.0 / 60.0
        repeat((seconds / dt).roundToInt()) { step(dt, lateral, longitudinal, vertical) }
    }

    private fun MirrorToyPhysics.assertAllFinite() {
        for (i in 0 until MirrorToyPhysics.POINT_COUNT) {
            assertTrue("point $i", x(i).isFinite() && y(i).isFinite() && z(i).isFinite())
        }
    }

    @Test
    fun hangsWithAFiniteStableSpringSag() {
        val toy = MirrorToyPhysics()
        // The constructor pre-settles: the head hangs below its rest pose
        // because the hanger is a real spring stretched by the body's weight
        // (measured equilibrium sag ~0.16 units), not a rigid constraint.
        val head = toy.y(MirrorToyPhysics.HEAD)
        assertTrue("head=$head", head > -0.70)
        assertTrue("head=$head", head < -0.50)

        // Thousands more substeps at rest: no drift, no blow-up.
        toy.run(20.0)
        toy.assertAllFinite()
        assertEquals(head, toy.y(MirrorToyPhysics.HEAD), 0.01)
        // The hook is pinned and never moves.
        assertEquals(-1.34, toy.y(MirrorToyPhysics.HOOK), 1e-9)
    }

    @Test
    fun aBumpRingsVisiblyThenDecays() {
        val toy = MirrorToyPhysics()
        val base = toy.y(MirrorToyPhysics.HEAD)
        toy.run(0.2, vertical = 4.0)

        // The spring lets the whole toy travel (a rigid hanger would only
        // twitch): the head excursion is a visible fraction of the body height.
        var peak = 0.0
        val dt = 1.0 / 60.0
        repeat((8.0 / dt).roundToInt()) {
            toy.step(dt, 0.0, 0.0, 0.0)
            peak = max(peak, abs(toy.y(MirrorToyPhysics.HEAD) - base))
        }
        assertTrue("peak=$peak", peak > 0.1)

        // Lightly damped, but by now the ringing has died back down.
        var late = 0.0
        repeat((2.0 / dt).roundToInt()) {
            toy.step(dt, 0.0, 0.0, 0.0)
            late = max(late, abs(toy.y(MirrorToyPhysics.HEAD) - base))
        }
        assertTrue("late=$late", late < 0.02)
        toy.assertAllFinite()
    }

    @Test
    fun divergenceGuardRestoresTheRestPose() {
        val toy = MirrorToyPhysics()
        toy.step(0.05, Double.NaN, 0.0, 0.0)

        // Every substep hit the guard, so the toy sits exactly at the rest pose.
        assertEquals(0.0, toy.x(MirrorToyPhysics.HEAD), 1e-9)
        assertEquals(-0.78, toy.y(MirrorToyPhysics.HEAD), 1e-9)
        assertEquals(0.62, toy.y(MirrorToyPhysics.FTR), 1e-9)
        assertEquals(0.0, toy.z(MirrorToyPhysics.HIP), 1e-9)

        // And it keeps simulating cleanly afterwards.
        toy.run(2.0)
        toy.assertAllFinite()
    }
}
