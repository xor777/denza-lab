package dev.denza.apps.feature.hud

import dev.denza.hudlink.HudRadarWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.ln

/**
 * The rule since 2026-11-20: more than the camera's limit plus 20 is a fine (КоАП 12.9), it goes
 * at plus 19 or less, and the red comes and goes quickly.
 */
class HudSpeedingTest {
    private val speeding = HudSpeeding()

    /** The host's red tick, [HudRadarHost.RED_TICK_MS]. */
    private val tick = HudRadarHost.RED_TICK_MS

    /** [speed] held for [ticks] host ticks after [from]; the red at the end. */
    private fun ticks(limit: Int, speed: Double, from: Long, ticks: Int): Float {
        var red = 0f
        for (i in 1..ticks) red = speeding.update(limit, speed, from + i * tick)
        return red
    }

    /**
     * Host ticks until the red is all the way there with time constant [tauMs]: the level snaps to
     * its target within half a step, 1/64, so exp(-n·tick/tau) < 1/64.
     */
    private fun ticksToSettle(tauMs: Long): Int = ceil(tauMs * ln(64.0) / tick).toInt()

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
    fun theRuleIsMoreThanTwentyOverAndGoesAtNineteenOver() {
        assertEquals(20, HudSpeeding.OVER)
        assertEquals(19, HudSpeeding.RELEASE)
        assertEquals(150L, HudSpeeding.RISE_MS)
        assertEquals(250L, HudSpeeding.FALL_MS)
    }

    @Test
    fun twentyOverIsNotAFineAndMoreThanTwentyIs() {
        assertEquals(0f, drive(60, 80.0, 0, 3_000), 0f)
        assertEquals(1f, drive(60, 80.5, 3_050, 6_000), 0f)
        speeding.reset()
        assertEquals(0f, drive(60, 80.0, 0, 3_000), 0f)
        assertEquals(1f, drive(60, 81.0, 3_050, 6_000), 0f)
    }

    @Test
    fun comingDownItStaysAtNineteenAndAHalfAndGoesAtNineteen() {
        assertEquals(1f, drive(60, 81.0, 0, 3_000), 0f)
        assertEquals(1f, drive(60, 80.0, 3_050, 6_000), 0f)
        assertEquals(1f, drive(60, 79.5, 6_050, 9_000), 0f)
        assertEquals(0f, drive(60, 79.0, 9_050, 12_000), 0f)
        assertTrue(speeding.settled())
    }

    @Test
    fun theRedComesOverAMomentNotAtOnceAndIsFullyOnWithinHalfASecond() {
        drive(60, 60.0, 0, 1_000)
        val start = 1_000L
        // Not at once: the first tick is a third of the way at most.
        val first = speeding.update(60, 90.0, start + tick)
        assertTrue("first tick $first", first in 0.2f..0.5f)
        val n = ticksToSettle(HudSpeeding.RISE_MS)
        // Half a second on, the picture is at least 31/32 red.
        val halfSecond = (500 / tick).toInt()
        assertTrue(ticks(60, 90.0, start + tick, halfSecond - 1) >= 31f / 32f)
        // And it is all the way red at n ticks, not one sooner.
        speeding.reset()
        drive(60, 60.0, 0, 1_000)
        speeding.update(60, 60.0, start)
        assertTrue(ticks(60, 90.0, start, n - 1) < 1f)
        assertEquals(1f, speeding.update(60, 90.0, start + n * tick), 0f)
        assertTrue("fully on at ${n * tick} ms", n * tick <= 700)
    }

    @Test
    fun theRedIsGoneWithinASecond() {
        drive(60, 90.0, 0, 3_000)
        val start = 3_000L
        speeding.update(60, 90.0, start)
        val n = ticksToSettle(HudSpeeding.FALL_MS)
        // 0.9 s on, the picture is at most 1/32 red.
        val nineTenths = (900 / tick).toInt()
        assertTrue(ticks(60, 60.0, start, nineTenths) <= 1f / 32f)
        // And none at all at n ticks, not one sooner.
        speeding.reset()
        drive(60, 90.0, 0, 3_000)
        speeding.update(60, 90.0, start)
        assertTrue(ticks(60, 60.0, start, n - 1) > 0f)
        assertEquals(0f, speeding.update(60, 60.0, start + n * tick), 0f)
        assertTrue(speeding.settled())
        assertTrue("fully gone at ${n * tick} ms", n * tick <= 1_100)
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
        assertEquals(1f, follow(camera(60), 90.0, 1_050, 4_000), 0f)
        assertEquals(0f, follow(camera(60), null, 4_050, 8_000), 0f)
    }

    @Test
    fun anMphCameraCountsInMiles() {
        // 40 mph + 20 = 60 mph = 96.56 km/h: 96 km/h is no fine, 97 km/h is.
        assertEquals(0f, follow(camera(40, unit = 2), 96.0, 0, 3_000), 0f)
        assertEquals(1f, follow(camera(40, unit = 2), 97.0, 3_050, 6_000), 0f)
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

    @Test
    fun anotherCameraStartsPlainEvenInsideTheOldOnesBand() {
        // At 80 km/h a 50 camera is red; the next camera, 60, is over 79 and not over 80: within
        // its band but never entered. It is plain.
        assertEquals(1f, follow(camera(50), 80.0, 0, 3_000), 0f)
        val next = HudRadarWarning(3_050, true, HudRadarWarning.GPS_FIX, true, 2, 1, "Камера", 300, 60, 1, false, -1, "", "")
        assertEquals(0f, follow(next, 80.0, 3_050, 6_000), 0f)
        assertTrue(speeding.settled())
    }

    @Test
    fun theSameCameraObservedAgainKeepsItsRed() {
        assertEquals(1f, follow(camera(50, at = 0), 80.0, 0, 3_000), 0f)
        assertEquals(1f, follow(camera(50, at = 3_000), 80.0, 3_050, 4_000), 0f)
    }

    @Test
    fun aCameraWhoseLimitChangesStartsPlain() {
        assertEquals(1f, follow(camera(50), 80.0, 0, 3_000), 0f)
        assertEquals(0f, speeding.update(camera(60), 80.0, 3_050), 0f)
    }
}
