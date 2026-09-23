package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.SplitCrewScene.Companion.BH
import dev.denza.apps.feature.split.SplitCrewScene.Companion.B_HIT
import dev.denza.apps.feature.split.SplitCrewScene.Companion.B_UP
import dev.denza.apps.feature.split.SplitCrewScene.Companion.CRANE_CYCLE
import dev.denza.apps.feature.split.SplitCrewScene.Companion.CRANE_LEAD
import dev.denza.apps.feature.split.SplitCrewScene.Companion.CX
import dev.denza.apps.feature.split.SplitCrewScene.Companion.CY
import dev.denza.apps.feature.split.SplitCrewScene.Companion.CYCLE
import dev.denza.apps.feature.split.SplitCrewScene.Companion.FLOOR
import dev.denza.apps.feature.split.SplitCrewScene.Companion.HAM_PERIOD
import dev.denza.apps.feature.split.SplitCrewScene.Companion.HOOK_TOP
import dev.denza.apps.feature.split.SplitCrewScene.Companion.NARROW_LEFT
import dev.denza.apps.feature.split.SplitCrewScene.Companion.NARROW_RIGHT
import dev.denza.apps.feature.split.SplitCrewScene.Companion.PILE_X
import dev.denza.apps.feature.split.SplitCrewScene.Companion.SLING
import dev.denza.apps.feature.split.SplitCrewScene.Companion.STACK_X
import dev.denza.apps.feature.split.SplitCrewScene.Companion.T_HAM
import dev.denza.apps.feature.split.SplitCrewScene.Companion.T_MOVE
import dev.denza.apps.feature.split.SplitCrewScene.Companion.crane
import dev.denza.apps.feature.split.SplitCrewScene.Companion.decelerate
import dev.denza.apps.feature.split.SplitCrewScene.Companion.dividerAt
import dev.denza.apps.feature.split.SplitCrewScene.Companion.hammerAngle
import dev.denza.apps.feature.split.SplitCrewScene.Companion.hitAt
import dev.denza.apps.feature.split.SplitCrewScene.Companion.standard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The clocks of «Бригада»: the divider's four-second round, the crane's 4.4 s one and its stack of
 * three, the hammer's 600 ms blow and the frame's six-blow round, the reveal. The numbers are the
 * approved page's; `SplitCrewBoardContractTest` holds the constants to the file, this holds what
 * they add up to.
 */
class SplitCrewTimelineTest {

    private fun near(expected: Double, actual: Double, what: String, eps: Double = 1e-9) =
        assertEquals(what, expected, actual, eps)

    /** The crane's time into its round for a wait time [t], and back. */
    private fun craneT(round: Int, u: Double) = round * CRANE_CYCLE + u - CRANE_LEAD

    @Test
    fun easingIsTheCssCurveSolvedByBisection() {
        near(0.0, standard(0.0), "starts at rest")
        near(1.0, standard(1.0), "ends at rest")
        near(0.0, standard(-1.0), "clamped below")
        near(1.0, standard(2.0), "clamped above")
        var prev = 0.0
        for (i in 1..100) {
            val y = standard(i / 100.0)
            assertTrue("standard rises at ${i / 100.0}", y >= prev)
            prev = y
        }
        // decelerate(0.05, 0.7, 0.1, 1) is most of the way there by a quarter; standard is not.
        assertTrue(decelerate(0.25) > 0.8)
        assertTrue(standard(0.25) < 0.5)
    }

    @Test
    fun theDividerIsBornInTheMiddleAndHeldAtTheFirmwaresNarrowPanes() {
        for (t in listOf(0.0, 500.0, 999.999)) near(CX, dividerAt(t), "the middle before $T_MOVE ms: $t")
        near(CX, dividerAt(T_MOVE), "leaving the middle")
        for (t in 1900..2100 step 20) near(NARROW_LEFT, dividerAt(t.toDouble()), "held on the left third at $t")
        for (t in 3900..4100 step 20) near(NARROW_RIGHT, dividerAt(t.toDouble()), "held on the right third at $t")
        near(CX, dividerAt(T_MOVE + CYCLE), "back in the middle after one round")
    }

    @Test
    fun theDividerGoesRoundEveryFourSecondsWithoutAJump() {
        var t = T_MOVE
        var previous = dividerAt(t)
        while (t < T_MOVE + 3 * CYCLE) {
            near(dividerAt(t), dividerAt(t + CYCLE), "period $CYCLE at $t")
            val x = dividerAt(t)
            assertTrue("no jump at $t: $previous -> $x", abs(x - previous) < 6.0)
            assertTrue("between the narrow panes at $t", x in NARROW_LEFT..NARROW_RIGHT)
            previous = x
            t += 5
        }
        // The legs: middle to left in 0.9 s, left to right in 1.8 s, right to middle in 0.9 s.
        assertTrue(dividerAt(1450.0) in NARROW_LEFT..CX)
        assertTrue(dividerAt(3000.0) in CX..NARROW_RIGHT)
        assertTrue(dividerAt(4550.0) in CX..NARROW_RIGHT)
    }

    @Test
    fun theCraneGoesDownUpAcrossDownUpAndBack() {
        val pickY = FLOOR - BH - SLING - 6
        for (round in 1..3) {
            fun at(u: Double) = crane(craneT(round, u))
            near(HOOK_TOP, at(0.0).hy, "the hook starts at the top")
            near(PILE_X, at(0.0).tx, "over the pile")
            near(pickY, at(600.0).hy, "down on the pile block by 600 ms")
            near(pickY, at(799.0).hy, "held while it is slung")
            assertFalse("not yet carrying at 699", at(699.0).carrying)
            assertTrue("carrying from 700", at(700.0).carrying)
            near(HOOK_TOP, at(1400.0).hy, "back up by 1400")
            near(STACK_X, at(2300.0).tx, "over the stack by 2300")
            val placeY = FLOOR - BH * (at(0.0).onStack + 1) - SLING - 6
            near(placeY, at(2900.0).hy, "down on the stack by 2900")
            assertTrue("carrying until 3000", at(2999.0).carrying)
            assertFalse("set down at 3000", at(3000.0).carrying)
            near(HOOK_TOP, at(3600.0).hy, "up again by 3600")
            near(PILE_X, at(CRANE_CYCLE - 1e-6).tx, "home over the pile at the end of the round", 1e-6)
        }
    }

    @Test
    fun theHookMovesWithoutAJump() {
        var t = 0.0
        var px = crane(t).tx
        var py = crane(t).hy
        while (t < 3 * CRANE_CYCLE + 1000) {
            t += 2
            val k = crane(t)
            assertTrue("hook x jumps at $t", abs(k.tx - px) < 5.0)
            assertTrue("hook y jumps at $t", abs(k.hy - py) < 5.0)
            px = k.tx
            py = k.hy
        }
    }

    @Test
    fun theLoadIsPickedFromThePileAndSetExactlyOnTheStack() {
        for (round in 0..5) {
            val lift = crane(craneT(round, 700.0))
            // The slung block's top is the hook plus the sling; it is where the pile block stood.
            near(FLOOR - BH, lift.hy + 6 + SLING, "picked off the pile in round $round")
            val land = crane(craneT(round, 2950.0))
            near(FLOOR - BH * (land.onStack + 1), land.hy + 6 + SLING, "set on the stack in round $round")
        }
    }

    @Test
    fun theStackIsThreeTallAndStartsAgain() {
        assertEquals("the wait opens with the first block in the air", 0, crane(0.0).onStack)
        assertTrue(crane(0.0).carrying)
        val onStack = (0 until 7).map { crane(craneT(it, 100.0)).onStack }
        assertEquals(listOf(0, 1, 2, 0, 1, 2, 0), onStack)
    }

    @Test
    fun aBlowLandsEvery600Ms() {
        for (m in 0..12) {
            val hit = hitAt(m)
            near(T_HAM + m * HAM_PERIOD, hit, "blow $m")
            near(B_HIT, hammerAngle(hit), "the head is down at blow $m")
            near(B_HIT, hammerAngle(hit - 1e-6), "and comes down onto it", 1e-6)
            near(B_UP, hammerAngle(hit + 0.7 * HAM_PERIOD), "raised at 0.7 of the period", 1e-9)
        }
        // Up is the slow 0.7, down the fast 0.3 - the swing only ever turns at the two ends.
        var t = hitAt(3)
        var prev = hammerAngle(t)
        var turns = 0
        var lifting = true // the angle falls towards B_UP while the hammer is raised
        while (t < hitAt(4)) {
            t += 1
            val b = hammerAngle(t)
            if ((b < prev) != lifting && b != prev) {
                turns++
                lifting = b < prev
            }
            prev = b
        }
        assertEquals("one lift and one fall per blow", 1, turns)
    }

    @Test
    fun theFrameIsBuiltOneSidePerBlowAndLetGoAfterTheSixth() {
        val pen = RecordingCrewPen()
        val scene = SplitCrewScene(pen, "")
        fun edgesAt(t: Double): RecordingCrewPen.Stroke {
            pen.strokes.clear()
            scene.drawRightCrew(t)
            // The right crew draws the ladder, the frame's foot, then - once a blow has landed - the sides.
            return pen.strokes[2]
        }
        for (round in 0..2) {
            for (k in 1..6) {
                val t = hitAt(6 * round + k) + 200.0
                val sides = edgesAt(t).polylines.size
                assertEquals("round $round, blow $k", minOf(k, 4), sides)
            }
            val sixth = hitAt(6 * round + 6)
            val flash = edgesAt(sixth)
            near(1.0, flash.alpha, "the frame flashes on the sixth blow")
            near(3.0, flash.width, "and flares")
            near(0.0, edgesAt(sixth + 400).alpha, "then is let go in 400 ms")
        }
        // A side is drawn out in 180 ms after its blow.
        val half = edgesAt(hitAt(1) + 90).polylines.single()
        val full = edgesAt(hitAt(1) + 180).polylines.single()
        val len = { p: List<RecordingCrewPen.Pt> -> abs(p.last().y - p.first().y) }
        assertTrue(len(half) > 0 && len(half) < len(full))
        near(SplitCrewScene.FRAME_H, len(full), "the first side is the frame's height")
    }

    @Test
    fun theSiteOpensAsACircleFromTheCentreIn650Ms() {
        fun open(t: Double): List<String> {
            val pen = RecordingCrewPen()
            SplitCrewScene(pen, "").drawShield(t)
            return pen.ops
        }
        assertTrue(open(0.0).contains("clip $CX $CY ${SplitCrewScene.REVEAL_FROM}"))
        val r300 = SplitCrewScene.REVEAL_FROM + SplitCrewScene.REVEAL_GROWTH * decelerate(300.0 / SplitCrewScene.REVEAL_MS)
        assertTrue(open(300.0).contains("clip $CX $CY $r300"))
        assertTrue("the circle has passed the corners by the end", SplitCrewScene.REVEAL_FROM + SplitCrewScene.REVEAL_GROWTH > SplitCrewScene.HALF_DIAG)
        assertTrue("no clip once open", open(650.0).none { it.startsWith("clip") })
        assertTrue(open(5000.0).none { it.startsWith("clip") })
    }
}
