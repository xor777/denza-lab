package dev.denza.apps.feature.split

import dev.denza.apps.feature.split.RecordingCrewPen.Stroke
import dev.denza.apps.feature.split.SplitCrewScene.Companion.CRANE_CYCLE
import dev.denza.apps.feature.split.SplitCrewScene.Companion.FLOOR
import dev.denza.apps.feature.split.SplitCrewScene.Companion.H
import dev.denza.apps.feature.split.SplitCrewScene.Companion.NARROW_LEFT
import dev.denza.apps.feature.split.SplitCrewScene.Companion.RAIL_B
import dev.denza.apps.feature.split.SplitCrewScene.Companion.STILL_T
import dev.denza.apps.feature.split.SplitCrewScene.Companion.T_MOVE
import dev.denza.apps.feature.split.SplitCrewScene.Companion.U
import dev.denza.apps.feature.split.SplitCrewScene.Companion.W
import dev.denza.apps.feature.split.SplitCrewScene.Companion.crane
import dev.denza.apps.feature.split.SplitCrewScene.Companion.dividerAt
import dev.denza.apps.feature.split.SplitCrewScene.Companion.hitAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * «Бригада» as geometry: a frame recorded by [RecordingCrewPen], part by part.
 *
 * The approved page was laid out so that the crew works without anyone walking through anyone:
 * the signalman stands clear of the crane's blocks, the pushers clear of both, the right crew keeps
 * to its frame. Nothing in the code enforces that - it is a property of the numbers - so this
 * sweeps a whole crane round of three blocks, which is more than three rounds of the divider and
 * of the frame, and holds it every five milliseconds.
 */
class SplitCrewGeometryTest {

    /** One frame, part by part, in the order `drawShield` draws them. */
    private fun parts(t: Double): Map<String, List<Stroke>> {
        val pen = RecordingCrewPen()
        val scene = SplitCrewScene(pen, CAPTION)
        val xd = dividerAt(t)
        pen.actor = "gantry"; scene.drawGantry()
        pen.actor = "crane"; val k = scene.drawCrane(t)
        pen.actor = "signalman"; scene.drawSignalman(t, k)
        pen.actor = "right crew"; scene.drawRightCrew(t)
        pen.actor = "divider"; scene.drawDivider(xd)
        pen.actor = "pushers"; scene.drawPushers(t, xd)
        return pen.strokes.groupBy { it.actor }
    }

    private fun sweep(block: (Double) -> Unit) {
        var t = 0.0
        while (t <= 3 * CRANE_CYCLE + T_MOVE) {
            block(t)
            t += 5.0
        }
    }

    @Test
    fun theShieldIsLaidOnBlackBeforeAnythingElseAndCaptionedLast() {
        for (t in listOf(0.0, 300.0, 700.0, STILL_T, 5200.0)) {
            val pen = RecordingCrewPen()
            SplitCrewScene(pen, CAPTION).drawShield(t)
            assertEquals("the ground comes first at $t", "ground", pen.ops.first())
            assertTrue("then the haze", pen.ops[1].startsWith("haze"))
            assertEquals("then the site, clipped while it opens", "save", pen.ops[2])
            assertTrue("the caption is the last thing drawn", pen.ops.last().startsWith("caption $CAPTION 640.0 748.0 18.0"))
        }
    }

    @Test
    fun theShieldIsItsPartsInThePagesOrder() {
        for (t in listOf(700.0, STILL_T, 3300.0, 9000.0)) {
            val pen = RecordingCrewPen()
            SplitCrewScene(pen, CAPTION).drawShield(t)
            val whole = pen.strokes.map { it.alpha to it.polylines }
            val byParts = parts(t).values.flatten().map { it.alpha to it.polylines }
            assertEquals("drawShield at $t", byParts, whole)
        }
    }

    @Test
    fun theStillFrameIsABlowWithTheDividerOnTheLeftThird() {
        // 50 ms after the hold ends: the divider has barely left the firmware's narrow left pane.
        assertEquals(NARROW_LEFT, dividerAt(STILL_T), 1.0)
        assertEquals(hitAt(4), STILL_T, 0.0)
    }

    @Test
    fun everythingStaysOnTheScreen() {
        sweep { t ->
            for ((actor, strokes) in parts(t)) {
                strokes.forEachIndexed { i, s ->
                    if (s.points.isEmpty()) return@forEachIndexed
                    val b = s.box
                    // The ground and its hatching run off both edges of the page, as a drawing's do.
                    val bleed = if (actor == "gantry" && i == GROUND) 8.5 else 0.0
                    if (b.left < -bleed || b.right > W + bleed || b.top < 0 || b.bottom > H) {
                        fail("$actor stroke $i leaves the screen at $t ms: $b")
                    }
                }
            }
        }
    }

    @Test
    fun thePushersHandsAreOnTheDividersFaces() {
        sweep { t ->
            val xd = dividerAt(t)
            val parts = parts(t)
            val pushers = parts.getValue("pushers")
            // Each pusher is a body, a head and a hat; the body's subpaths are two legs, the torso,
            // and the two arms, each from the shoulder through the elbow to the hand.
            for ((body, face) in listOf(pushers[0] to xd - 6, pushers[3] to xd + 6)) {
                val hands = listOf(body.polylines[3].last(), body.polylines[4].last())
                assertEquals("upper hand x at $t", face, hands[0].x, 1e-9)
                assertEquals("upper hand y at $t", FLOOR - 0.6 * U, hands[0].y, 1e-9)
                assertEquals("lower hand x at $t", face, hands[1].x, 1e-9)
                assertEquals("lower hand y at $t", FLOOR - 0.6 * U + 8, hands[1].y, 1e-9)
            }
            // And those faces are the divider's: its girder's two chords.
            val girder = parts.getValue("divider")[1].polylines.first()
            assertEquals(xd - 6, girder.minOf { it.x }, 1e-9)
            assertEquals(xd + 6, girder.maxOf { it.x }, 1e-9)
            assertTrue(FLOOR - 0.6 * U in (RAIL_B + 10)..(FLOOR - 6))
        }
    }

    @Test
    fun theCrewNeverRunIntoEachOther() {
        val crews = listOf("crane", "signalman", "pushers", "right crew")
        var closest = Double.MAX_VALUE
        var where = ""
        sweep { t ->
            val parts = parts(t)
            for (i in crews.indices) for (j in i + 1 until crews.size) {
                for (a in parts.getValue(crews[i])) for (b in parts.getValue(crews[j])) {
                    if (a.points.isEmpty() || b.points.isEmpty()) continue
                    val reach = (a.width + b.width) / 2
                    val ab = a.box
                    val bb = b.box
                    if (!RecordingCrewPen.Box(ab.left - 40, ab.top - 40, ab.right + 40, ab.bottom + 40).intersects(bb)) continue
                    for (sa in a.segments) for (sb in b.segments) {
                        val gap = RecordingCrewPen.distance(sa, sb) - reach
                        if (gap < closest) {
                            closest = gap
                            where = "${crews[i]} / ${crews[j]} at $t ms"
                        }
                        if (gap <= 0) fail("${crews[i]} and ${crews[j]} touch at $t ms: $sa, $sb")
                    }
                }
            }
        }
        println("closest approach between two crews: ${"%.1f".format(closest)} dp ($where)")
    }

    @Test
    fun theStackIsDrawnAsItStandsAndTheOldOneFades() {
        fun blocks(t: Double): List<Stroke> = parts(t).getValue("crane").drop(CRANE_STRUCTURE)
        // Round 3 (the fourth block) begins over a full stack of three, fading in 600 ms.
        val round = 3
        val start = round * CRANE_CYCLE - SplitCrewScene.CRANE_LEAD
        assertEquals(0, crane(start).onStack)
        val fading = blocks(start + 300).single { it.polylines.size == 3 * 3 }
        assertEquals("half gone at 300 ms", 0.95 * 0.5, fading.alpha, 1e-9)
        assertTrue("gone by 600 ms", blocks(start + 600).none { it.polylines.size == 3 * 3 })
        // At the end of the round the placed block is the stack's third.
        val set = blocks(start + 3000 + CRANE_CYCLE * 2).first()
        assertEquals("three blocks, each a box and two braces", 3 * 3, set.polylines.size)
    }

    private companion object {
        const val CAPTION = "Запускаем разделение экрана…"

        /** The gantry's fourth stroke is the ground and its hatching. */
        const val GROUND = 3

        /** The crane's own strokes before the load: mast, jib, ties and weights, trolley and hook. */
        const val CRANE_STRUCTURE = 4
    }
}
