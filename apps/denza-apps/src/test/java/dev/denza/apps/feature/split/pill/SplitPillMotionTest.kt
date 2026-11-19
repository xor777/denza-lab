package dev.denza.apps.feature.split.pill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tanh

/**
 * The page's motion, number for number (README "Motion", "Pull"): the springs, the tweens, the
 * pull's mapping and the release's arithmetic.
 */
class SplitPillMotionTest {

    @Test
    fun aSpringSettlesInItsTimeAndOvershootsAsItsDampingSays() {
        listOf(SplitPillSpec.Spring.OPEN, SplitPillSpec.Spring.CLOSE, SplitPillSpec.Spring.SLIDE, SplitPillSpec.Spring.RELEASE).forEach { spec ->
            val spring = PillSpring.of(spec)
            val v = PillValue(0.0, 1e-4).spring(100.0, spring, 0.0, 0.0)
            var peak = 0.0
            var t = 0.0
            while (t < spec.ms) {
                t += 1000.0 / 60
                v.step(1.0 / 60, t)
                peak = max(peak, v.v)
            }
            // ω = 6.6 / s leaves a critically damped spring 1 % short at its time; Euler a little more.
            assertEquals("$spec settled within 2 % by its time", 100.0, v.v, 2.0)
            // The page's integrator (semi-implicit Euler at `rate`) trims the analytic peak a little.
            assertEquals("$spec overshoot", 100 * spring.overshoot(), peak - 100, 2.0)
        }
    }

    @Test
    fun aTweenLeavesFromWhereTheValueIsAndArrivesOnTime() {
        val tween = SplitPillSpec.Motion.GLASS_IN
        val v = PillValue(0.3, 1e-4).tween(1.0, tween, 0.0)
        v.step(0.0, 0.0)
        assertEquals(0.3, v.v, 1e-12)
        v.step(tween.ms / 2000, tween.ms / 2)
        assertTrue(v.v > 0.3 && v.v < 1.0)
        val mid = v.v
        // Retargeted half way: it goes back from where it is, no jump.
        v.tween(0.0, SplitPillSpec.Motion.GLASS_OUT, tween.ms / 2)
        v.step(0.0, tween.ms / 2)
        assertEquals(mid, v.v, 1e-12)
        var t = tween.ms / 2
        repeat(200) {
            t += 1000.0 / 60
            v.step(1.0 / 60, t)
        }
        assertEquals(0.0, v.v, 0.0)
        assertFalse(v.active)
    }

    @Test
    fun aDelayedSpringHoldsStillUntilItsTime() {
        val v = PillValue(10.0, 1e-4).spring(0.0, PillSpring.of(SplitPillSpec.Spring.CLOSE), SplitPillSpec.Motion.SHRINK_DELAY, 0.0)
        v.step(1.0 / 60, SplitPillSpec.Motion.SHRINK_DELAY - 1)
        assertEquals(10.0, v.v, 0.0)
        v.step(1.0 / 60, SplitPillSpec.Motion.SHRINK_DELAY + 16)
        assertTrue(v.v < 10.0)
    }

    @Test
    fun thePullMapsTheFingerToThePill() {
        // The primitives the view's pull calls (PillPull.progress, heightOf, widthOf, rawOfHeight),
        // held to values worked out here from the board's numbers, not to each other.
        val h0 = SplitPillSpec.Handle.H
        val h1 = SplitPillSpec.Pill.OPEN_H
        val w0 = SplitPillSpec.Handle.W
        val travel = SplitPillSpec.Pull.TRAVEL
        listOf(0.25, 0.5, 0.75).forEach { q ->
            assertEquals("height at $q", h0 + (h1 - h0) * q, PillPull.heightOf(PillPull.progress(q, 0.0, false)), 1e-9)
            assertEquals("width at $q toward 400", w0 + (400 - w0) * q, PillPull.widthOf(q, 400.0), 1e-9)
        }
        assertEquals(60.0, PillPull.heightOf(0.5), 1e-9)
        assertEquals(216.0, PillPull.widthOf(0.5, 400.0), 1e-9)
        assertEquals(0.5, PillPull.rawOfHeight(60.0), 1e-12)
        // Below the handle nothing; past the open pill the band, never more than its cap.
        assertEquals(h0, PillPull.heightOf(PillPull.progress(-0.3, 0.0, false)), 0.0)
        val cap = SplitPillSpec.Pull.BAND_CAP
        val banded = h1 + cap * tanh(SplitPillSpec.Pull.BAND_FACTOR * 10 / cap)
        assertEquals(banded, PillPull.heightOf(PillPull.progress(1 + 10 / travel, 0.0, false)), 1e-9)
        assertEquals(1 + 10 / travel, PillPull.rawOfHeight(banded), 1e-9)
        assertTrue(PillPull.heightOf(PillPull.progress(1 + 1000 / travel, 0.0, false)) <= h1 + cap + 1e-9)
        // Over an empty pane the floor holds, or gives by the band when the pull may spring back.
        assertEquals(0.6, PillPull.progress(0.3, 0.6, false), 0.0)
        val give = 0.6 - cap * tanh(SplitPillSpec.Pull.BAND_FACTOR * 0.3 * travel / cap) / travel
        assertEquals(give, PillPull.progress(0.3, 0.6, true), 1e-12)
        // After a catch at 40 % with the pill 100 dp wide, the width line bends through that point.
        assertEquals(w0 + (100 - w0) * 0.2 / 0.4, PillPull.widthOf(0.2, 400.0, 0.4, 100.0), 1e-9)
        assertEquals(100 + (400 - 100) * 0.3 / 0.6, PillPull.widthOf(0.7, 400.0, 0.4, 100.0), 1e-9)
        assertEquals(400.0, PillPull.widthOf(1.4, 400.0, 0.4, 100.0), 1e-9)
    }

    @Test
    fun aTweenGivenToAMovingValueCarriesItsSpeedOn() {
        val v = PillValue(0.0, 1e-4).spring(100.0, PillSpring.of(SplitPillSpec.Spring.OPEN), 0.0, 0.0)
        var t = 0.0
        repeat(6) {
            t += 1000.0 / 240
            v.step(1.0 / 240, t)
        }
        val speed = v.vel
        assertTrue(speed > 0)
        val at = v.v
        // An immediate tween (no delay of its own) inherits the speed; a delayed one holds still first.
        v.tween(0.0, SplitPillSpec.Motion.GLASS_IN, t)
        v.step(0.0, t)
        assertEquals("no jump", at, v.v, 1e-12)
        val dt = 1.0 / 1000
        v.step(dt, t + 1)
        // It leaves with the speed it had, plus the tween's own start ("out" starts at 3 x its average).
        val own = (0.0 - at) * 3 / (SplitPillSpec.Motion.GLASS_IN.ms / 1000)
        assertEquals("no kink: the speed it had is carried on", speed + own, (v.v - at) / dt, abs(speed + own) * 0.03 + 1)
        repeat(400) {
            t += 1000.0 / 60
            v.step(1.0 / 60, t + 1)
        }
        assertEquals(0.0, v.v, 0.0)
    }

    @Test
    fun theContourBreathesOnceACycle() {
        val cycle = SplitPillSpec.Motion.BREATHE.ms
        assertEquals(2400.0, cycle, 0.0)
        assertEquals(0.0, breathePhase(0.0, cycle), 1e-12)
        assertEquals(0.5, breathePhase(cycle / 4, cycle), 1e-12)
        assertEquals("brightest half way", 1.0, breathePhase(cycle / 2, cycle), 1e-12)
        assertEquals(0.5, breathePhase(cycle * 3 / 4, cycle), 1e-12)
        assertEquals("quiet again after one cycle", 0.0, breathePhase(cycle, cycle), 1e-12)
    }

    @Test
    fun theItemsArriveInTurnOverTheTravel() {
        val n = 5
        assertEquals(0.0, PillPull.revealOf(0, n, SplitPillSpec.Pull.ITEMS_FROM), 1e-12)
        assertEquals(1.0, PillPull.revealOf(0, n, SplitPillSpec.Pull.ITEMS_FROM + SplitPillSpec.Pull.ITEMS_EACH), 1e-12)
        assertEquals(1.0, PillPull.revealOf(n - 1, n, SplitPillSpec.Pull.ITEMS_TO), 1e-12)
        assertTrue(PillPull.revealOf(n - 1, n, SplitPillSpec.Pull.ITEMS_TO - 0.01) < 1.0)
        assertEquals(0.0, PillPull.revealOf(n - 1, n, SplitPillSpec.Pull.ITEMS_TO - SplitPillSpec.Pull.ITEMS_EACH), 1e-12)
        assertTrue(PillPull.revealOf(1, n, SplitPillSpec.Pull.ITEMS_FROM + SplitPillSpec.Pull.ITEMS_EACH / 2) > 0)
    }

    @Test
    fun aReleaseOpensPastHalfOrWhenItsSpeedWouldCarryItThere() {
        val half = SplitPillSpec.Pull.HALF
        assertTrue(PillPull.opens(half, 0.0))
        assertFalse(PillPull.opens(half - 0.01, 0.0))
        val needed = (half - 0.2) * SplitPillSpec.Pull.TRAVEL / (SplitPillSpec.Pull.PROJECT / 1000)
        assertTrue(PillPull.opens(0.2, needed + 1))
        assertFalse(PillPull.opens(0.2, needed - 1))
        assertFalse("a flick down puts it away", PillPull.opens(0.9, -2000.0))
    }

    @Test
    fun theFingersSpeedIsItsSlopeAtTheLastSample() {
        // Steady 500 dp/s upwards (y falls), sampled at 60 Hz.
        val t = DoubleArray(12) { it * 16.0 }
        val y = DoubleArray(12) { 700 - it * 8.0 }
        assertEquals(500.0, PillPull.fingerSpeed(t, y, 12), 1e-6)
        // Two samples: the line between them.
        assertEquals(500.0, PillPull.fingerSpeed(t, y, 2), 1e-6)
        // Accelerating: the parabola reads the speed at the end, above the average.
        val ya = DoubleArray(12) { i -> 700 - 0.05 * (i * 16.0) * (i * 16.0) / 16 }
        val v = PillPull.fingerSpeed(t, ya, 12)
        val average = (ya[0] - ya[11]) / (t[11] / 1000)
        assertTrue(v > average)
        assertEquals(0.0, PillPull.fingerSpeed(t, y, 1), 0.0)
    }

    @Test
    fun aHandedSpeedNeverCarriesTheSpringPastItsBound() {
        val spring = PillSpring.of(SplitPillSpec.Spring.OPEN_H)
        val target = SplitPillSpec.Pill.OPEN_H
        val edge = target + SplitPillSpec.Pull.BOUND_HEIGHT
        val v = PillPull.boundVel(5000.0, 60.0, target, spring, edge)
        assertTrue(v > 0 && v < 5000)
        val value = PillValue(60.0, 1e-4).spring(target, spring, 0.0, 0.0, v)
        var top = 60.0
        var t = 0.0
        repeat(120) {
            t += 1000.0 / 240
            value.step(1.0 / 240, t)
            top = max(top, value.v)
        }
        assertTrue("peak $top past $edge", top <= edge + 0.05)
        assertEquals("a small speed is handed whole", 1.0, PillPull.boundVel(1.0, 60.0, target, spring, edge), 0.0)
        assertEquals("a speed away from the bound is handed whole", -500.0, PillPull.boundVel(-500.0, 60.0, target, spring, edge), 0.0)
    }

    @Test
    fun theEasesAreThePages() {
        assertEquals(0.875, PillEase.named("out").at(0.5), 1e-12)
        assertEquals(0.75, PillEase.named("outQ").at(0.5), 1e-12)
        assertEquals(0.5, PillEase.named("inOut").at(0.5), 1e-12)
        val settle = PillEase.named(SplitPillSpec.Ease.SETTLE)
        assertEquals(0.0, settle.at(0.0), 1e-9)
        assertEquals(1.0, settle.at(1.0), 1e-9)
        assertTrue(abs(PillEase.named("ease-out").at(0.5) - PillEase.bezier(0.0, 0.0, 0.58, 1.0).at(0.5)) < 1e-12)
        assertTrue("pop overshoots", (1..99).any { PillEase.named(SplitPillSpec.Ease.POP).at(it / 100.0) > 1 })
    }
}
