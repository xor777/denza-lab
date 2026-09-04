package dev.denza.apps.feature.cluster.dashboard

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The four moving parts of the panel, and the three rules that stop them twitching.
 *
 * Nothing here reads a clock, so every case is a frame time and an answer.
 */
class ContourMotionTest {

    private val frame = 1f / 30f

    private fun run(motion: ContourMotion, kw: Float?, seconds: Float, rpm: Float? = null) {
        var left = seconds
        while (left > 0f) {
            motion.step(kw, rpm, minOf(frame, left))
            left -= frame
        }
    }

    // ---- the follower itself

    @Test
    fun aStepIsNineTenthsCoveredInTheTimeTheConstantNames() {
        val follower = ContourFollower(riseSeconds = 0.120f, fallSeconds = 0.300f)
        var left = 0.120f
        while (left > 0f) {
            follower.step(100f, minOf(1f / 240f, left))
            left -= 1f / 240f
        }
        assertEquals(90f, follower.value, 1.5f)
    }

    @Test
    fun theFollowerNeverOvershootsBecauseItIsCriticallyDamped() {
        val follower = ContourFollower(0.120f, 0.300f)
        repeat(200) {
            follower.step(100f, frame)
            assertTrue("overshot to ${follower.value}", follower.value <= 100.0001f)
        }
    }

    @Test
    fun aFrameLongerThanTheTimeConstantStillConverges() {
        // The rise is 120 ms and a frame is 33 ms, so explicit Euler would diverge here. On a parked
        // car the view drops to 5 fps and the frame is 200 ms, which is where it would blow up.
        val follower = ContourFollower(0.120f, 0.300f)
        repeat(50) { follower.step(80f, 0.2f) }
        assertEquals(80f, follower.value, 0.01f)
    }

    @Test
    fun aHitIsFasterThanARelease() {
        val rising = ContourFollower(0.120f, 0.300f)
        val falling = ContourFollower(0.120f, 0.300f)
        falling.settle(100f)

        repeat(4) {
            rising.step(100f, frame)
            falling.step(0f, frame)
        }
        // Same distance, same number of frames: the one going up is further along.
        assertTrue(
            "rise ${rising.value} against fall ${100f - falling.value}",
            rising.value > 100f - falling.value,
        )
    }

    @Test
    fun theAsymmetryIsAboutMagnitudeSoASwingThroughZeroIsAReleaseThenAHit() {
        val follower = ContourFollower(0.120f, 0.300f)
        follower.settle(40f)
        // Toward -40: the first half of that journey is the band letting go of 40 kW out.
        follower.step(-40f, frame)
        val slow = 40f - follower.value

        val hit = ContourFollower(0.120f, 0.300f)
        hit.step(40f, frame)
        assertTrue("the release must be softer than the hit", slow < hit.value)
    }

    // ---- the dead band and the neutral zone

    @Test
    fun aParkedCarsNoiseDoesNotMoveTheBand() {
        val motion = ContourMotion()
        run(motion, 0.3f, 2f)
        assertEquals(0f, motion.powerKw, 1e-6f)
    }

    @Test
    fun insideTheNeutralZoneThePanelCarriesNoColour() {
        val motion = ContourMotion()
        run(motion, 2f, 2f)
        assertEquals(ContourFlow.NEUTRAL, motion.flow)
    }

    @Test
    fun aCoastSwingingEitherSideOfZeroNeverChangesColour() {
        val motion = ContourMotion()
        run(motion, 2f, 1f)
        // The swing CRITIQUE M12 describes: two kilowatts out, two back, over and over.
        repeat(20) {
            run(motion, 2f, 0.4f)
            assertEquals(ContourFlow.NEUTRAL, motion.flow)
            run(motion, -2f, 0.4f)
            assertEquals(ContourFlow.NEUTRAL, motion.flow)
        }
    }

    @Test
    fun colourIsTakenAboveTheBandAndGivenUpBelowIt() {
        var flow = ContourFlow.NEUTRAL
        // Three and a half kilowatts is inside the hysteresis and buys nothing yet.
        flow = ContourMotion.flowOf(3.5f, flow)
        assertEquals(ContourFlow.NEUTRAL, flow)
        flow = ContourMotion.flowOf(5f, flow)
        assertEquals(ContourFlow.OUT, flow)
        // Coming back it keeps the colour through the same band it had to cross to get it.
        flow = ContourMotion.flowOf(2.5f, flow)
        assertEquals(ContourFlow.OUT, flow)
        flow = ContourMotion.flowOf(1f, flow)
        assertEquals(ContourFlow.NEUTRAL, flow)
        flow = ContourMotion.flowOf(-6f, flow)
        assertEquals(ContourFlow.BACK, flow)
    }

    @Test
    fun aHardTransitionStillChangesColourBecauseItIsRealDriving() {
        // The hysteresis is there to hold a coast still, not to slow the panel down. Braking hard
        // and then pulling hard is a change, and it is drawn as one.
        val motion = ContourMotion()
        run(motion, -40f, 2f)
        assertEquals(ContourFlow.BACK, motion.flow)
        run(motion, 40f, 1f)
        assertEquals(ContourFlow.OUT, motion.flow)
    }

    // ---- the hero's glyph

    @Test
    fun theHeroIsRewrittenAtTwoHertzAtMost() {
        val motion = ContourMotion()
        motion.step(34f, null, frame)
        assertEquals("the first reading is placed at once", 34, motion.figure)

        // A jump, and four tenths of a second: the band is already there and the glyph is not.
        motion.step(80f, null, 0.4f)
        assertTrue("the band arrived", motion.powerKw > 75f)
        assertEquals("the glyph waited for its half-second", 34, motion.figure)

        motion.step(80f, null, 0.2f)
        assertEquals(80, motion.figure)
    }

    @Test
    fun theGlyphDoesNotAlternateOnItsOwnRoundingBoundary() {
        val motion = ContourMotion()
        run(motion, 33f, 2f)
        assertEquals(33, motion.figure)

        // 33.6 rounds to 34 and is only 0.6 from what is printed, which is inside the hysteresis.
        run(motion, 33.6f, 4f)
        assertEquals(33, motion.figure)
        // 34.2 is more than a whole kilowatt away, and the glyph turns over.
        run(motion, 34.2f, 4f)
        assertEquals(34, motion.figure)
    }

    @Test
    fun theHeroPrintsMagnitudeBecauseTheColourCarriesTheDirection() {
        val motion = ContourMotion()
        run(motion, -42f, 2f)
        assertEquals(42, motion.figure)
        assertEquals(ContourFlow.BACK, motion.flow)
    }

    @Test
    fun thereIsNoFigureBeforeTheFirstReading() {
        val motion = ContourMotion()
        assertNull(motion.figure)
        motion.step(null, null, frame)
        assertNull(motion.figure)
    }

    // ---- the peak

    @Test
    fun thePeakHoldsForThreeSecondsAndThenComesBackToTheTip() {
        val motion = ContourMotion()
        run(motion, 128f, 1f)
        run(motion, 20f, 0.5f)
        val held = motion.peakKw!!
        assertTrue("the peak stayed out at $held", held > 100f)

        // A second later it is still where it landed.
        run(motion, 20f, 1f)
        assertEquals("still held", held, motion.peakKw!!, 1f)

        // And then sixty kilowatts a second brings it home to the tip.
        run(motion, 20f, 3f)
        assertEquals(20f, motion.peakKw!!, 1.5f)
    }

    @Test
    fun aPeakOnTheOtherSideOfZeroIsANewEventRatherThanASlideThroughIt() {
        val motion = ContourMotion()
        run(motion, 120f, 1f)
        assertTrue(motion.peakKw!! > 0f)
        run(motion, -58f, 1f)
        assertTrue("the mark moved to the braking side", motion.peakKw!! < 0f)
    }

    @Test
    fun thereIsNoPeakWorthDrawingInsideTheNeutralZone() {
        val motion = ContourMotion()
        run(motion, 1.5f, 3f)
        assertNull(motion.peakKw)
    }

    @Test
    fun aRisingReadingKeepsThePeakInFrontOfTheTip() {
        val motion = ContourMotion()
        run(motion, 60f, 2f)
        assertNotNull(motion.peakKw)
        assertTrue(
            "peak ${motion.peakKw} against tip ${motion.powerKw}",
            motion.peakKw!! >= motion.powerKw - 0.001f,
        )
    }

    // ---- what a null does

    @Test
    fun aValueThatLeftTakesItsMotionWithItRatherThanSweepingBackIn() {
        val motion = ContourMotion()
        run(motion, 128f, 2f)
        motion.step(null, null, frame)

        assertEquals(0f, motion.powerKw, 1e-6f)
        assertNull(motion.figure)
        assertNull(motion.peakKw)
        assertEquals(ContourFlow.NEUTRAL, motion.flow)

        // And a reading arriving from nothing is placed rather than travelled to: the band would
        // otherwise sweep the whole panel every time the bus hiccupped.
        motion.step(34f, null, frame)
        assertEquals(34f, motion.powerKw, 1e-6f)
        assertEquals(34, motion.figure)
    }

    @Test
    fun theGlowIsASecondAndAHalfBehindTheBand() {
        val motion = ContourMotion()
        run(motion, 0f, 0.5f)
        run(motion, 100f, 0.4f)

        assertTrue("band at ${motion.powerKw}", motion.powerKw > 90f)
        assertTrue("glow at ${motion.glowKw}", motion.glowKw < 60f)
        run(motion, 100f, 3f)
        assertTrue("and it does arrive", abs(motion.glowKw - 100f) < 1f)
    }

    @Test
    fun revolutionsFollowTheirOwnPairOfConstants() {
        val motion = ContourMotion()
        motion.step(0f, 1780f, frame)
        assertEquals("a first reading is placed, not travelled to", 1780f, motion.rpm, 1e-3f)
        assertTrue(motion.rpmReady)

        run(motion, 0f, 1f, rpm = null)
        assertTrue("a stopped engine is not a reading of zero rpm", !motion.rpmReady)
    }
}
