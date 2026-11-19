package dev.denza.apps.feature.split.pill

import dev.denza.apps.feature.split.SplitPair
import dev.denza.apps.feature.split.SplitPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the handle, the pill and its window stand at both of the divider's detents, and the
 * touchable region the window is given (decisions 2 and 10, README "The handle", "The pill").
 */
class SplitPillGeometryTest {

    private val detents = listOf(SplitPillSpec.Stage.DETENT_NARROW_LEFT, SplitPillSpec.Stage.DETENT_WIDE_LEFT)

    @Test
    fun theHandleIsADiscOverThePanesJoint() {
        detents.forEach { div ->
            val disc = PillGeometry.handle(div)
            assertEquals(div, disc.cx, 1e-9)
            assertEquals(SplitPillSpec.Handle.W, disc.w, 1e-9)
            assertEquals(disc.w, disc.h, 1e-9)
            assertEquals(SplitPillSpec.Handle.BOTTOM, disc.b, 1e-9)
            val hit = PillGeometry.handleHit(div)
            assertEquals(SplitPillSpec.Handle.HIT_W, hit.w, 1e-9)
            assertEquals(SplitPillSpec.Handle.HIT_H, hit.h, 1e-9)
            assertEquals(disc.cx, hit.cx, 1e-9)
            assertEquals(disc.cy, hit.cy, 1e-9)
        }
    }

    @Test
    fun theOpenPillStandsOnTheDividerAndTheAppsRowsMarkOnIt() {
        detents.forEach { div ->
            val pairs = PillGeometry.layout(listOf(chip(), PillItem.Separator, PillItem.Plus))
            val open = PillGeometry.open(div, pairs)
            assertEquals(div, open.cx, 1e-9)
            assertEquals(SplitPillSpec.Pill.OPEN_BOTTOM, open.b, 1e-9)
            assertEquals(SplitPillSpec.Pill.OPEN_H, open.h, 1e-9)
            // Two apps on the left, three on the right: the pill stands off, the mark stays.
            val apps = PillGeometry.layout(
                listOf(single(SplitPane.PRIMARY, "a"), single(SplitPane.PRIMARY, "b"), PillItem.DividerMark) +
                    listOf("c", "d", "e").map { single(SplitPane.SECONDARY, it) },
            )
            val pill = PillGeometry.open(div, apps)
            val mark = apps.indexOf(PillItem.DividerMark.key)
            assertTrue("the pill stands off the divider", kotlin.math.abs(pill.cx - div) > 1)
            assertEquals(div, pill.cx + apps.x[mark] + apps.widthOf(mark) / 2, 1e-9)
        }
    }

    @Test
    fun theRowIsSymmetricAndPadded() {
        val l = PillGeometry.layout(listOf(chip(), chip("x", "y"), PillItem.Separator, PillItem.Plus))
        assertEquals(-l.w / 2 + SplitPillSpec.Row.PAD, l.x[0], 1e-9)
        assertEquals(l.w / 2 - SplitPillSpec.Row.PAD, l.x[3] + l.widthOf(3), 1e-9)
        assertEquals(SplitPillSpec.Row.GAP_CHIP, l.x[1] - (l.x[0] + l.widthOf(0)), 1e-9)
        assertEquals(SplitPillSpec.Row.GAP_SEP, l.x[2] - (l.x[1] + l.widthOf(1)), 1e-9)
    }

    @Test
    fun theFixedWindowHoldsEveryShapeAtEitherDetent() {
        val w = PillGeometry.window
        assertEquals(0.0, w.l, 0.0)
        assertEquals(SplitPillSpec.Stage.W, w.r, 0.0)
        val rows = listOf(
            listOf(chip(), chip("x", "y"), chip("p", "q"), PillItem.Separator, PillItem.Plus),
            listOf("a", "b", "c").map { single(SplitPane.PRIMARY, it) } + PillItem.DividerMark +
                listOf("d", "e", "f").map { single(SplitPane.SECONDARY, it) },
        )
        val m = PillGeometry.margins
        detents.forEach { div ->
            val hit = PillGeometry.handleHit(div)
            assertTrue(w.contains(hit.l, hit.t) && w.contains(hit.r, hit.b))
            rows.forEach { row ->
                val open = PillGeometry.open(div, PillGeometry.layout(row))
                val reach = open.grow(0.0, m.t, 0.0, 0.0)
                assertTrue("open pill with its band and shadow at $div", w.contains(reach.l, reach.t) && w.contains(reach.r, reach.b))
            }
        }
        assertTrue("never past the screen", w.t >= 0 && w.b <= SplitPillSpec.Stage.H)
    }

    @Test
    fun theRegionAtRestIsTheHandlesTarget() {
        val scale = 2.0
        val div = SplitPillSpec.Stage.DETENT_NARROW_LEFT
        val bands = PillGeometry.touchBands(emptyList(), PillGeometry.handleHit(div), scale)
        val origin = PillGeometry.toPx(PillGeometry.window, scale)
        val hit = PillGeometry.toPx(PillGeometry.handleHit(div), scale)
        assertEquals(1, bands.size)
        assertEquals(listOf(hit[0] - origin[0], hit[1] - origin[1], hit[2] - origin[0], hit[3] - origin[1]), bands[0].toList())
        assertEquals(192, bands[0][2] - bands[0][0])
        assertEquals(96, bands[0][3] - bands[0][1])
    }

    @Test
    fun theBandsCoverTheCapsuleAndStrayOnlyAtItsEnds() {
        // A wide capsule, a tall narrow one (the apps row with nothing but its mark) and a disc.
        listOf(
            doubleArrayOf(100.0, 50.0, 700.0, 226.0),
            doubleArrayOf(400.0, 20.0, 476.0, 196.0),
            doubleArrayOf(10.0, 10.0, 74.0, 74.0),
        ).forEach { c ->
            val bands = PillGeometry.capsuleBands(c, PillGeometry.BAND_PX)
            val cap = PillRect(c[0], c[1], c[2], c[3])
            var y = c[1] - 6.5
            while (y < c[3] + 6) {
                var x = c[0] - 6.5
                while (x < c[2] + 6) {
                    val covered = bands.any { b -> x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3] }
                    if (PillGeometry.inCapsule(cap, x, y)) assertTrue("($x, $y) inside $cap uncovered", covered)
                    // Past the curve by more than a band's height is never the pill's: a rectangle
                    // over the capsule's box would take its corners.
                    if (outside(cap, x, y) > PillGeometry.BAND_PX) assertFalse("($x, $y) outside $cap taken", covered)
                    x += 1
                }
                y += 1
            }
        }
        assertFalse(
            "the box's corner",
            PillGeometry.capsuleBands(doubleArrayOf(100.0, 50.0, 700.0, 226.0), PillGeometry.BAND_PX)
                .any { b -> 100.5 >= b[0] && 100.5 <= b[2] && 50.5 >= b[1] && 50.5 <= b[3] },
        )
    }

    @Test
    fun theOpenPillAtRestTakesExactlyItsGlass() {
        val scale = 2.0
        val div = SplitPillSpec.Stage.DETENT_NARROW_LEFT
        val layout = PillGeometry.layout(listOf(chip(), chip("x", "y"), PillItem.Separator, PillItem.Plus))
        val (shapes, hit) = PillGeometry.region(true, true, false, div, layout, layout.w, layout.dx)
        val open = PillGeometry.open(div, layout)
        assertEquals(listOf(open), shapes)
        assertNull("no handle target over an open pill at rest", hit)
        val bands = PillGeometry.touchBands(shapes, hit, scale)
        val origin = PillGeometry.toPx(PillGeometry.window, scale)
        fun taken(x: Double, y: Double): Boolean {
            val px = x * scale - origin[0]
            val py = y * scale - origin[1]
            return bands.any { b -> px >= b[0] && px <= b[2] && py >= b[1] && py <= b[3] }
        }
        assertTrue(taken(open.cx, open.cy))
        assertTrue(taken(open.cx, open.t + 1))
        assertFalse("just above the glass is the app's", taken(open.cx, open.t - 3))
        assertFalse("just below the glass is the app's", taken(open.cx, open.b + 3))
        assertFalse("beside the round end is the app's", taken(open.l + 2, open.t + 2))
        // Hiding: nothing at all, at once.
        val (gone, goneHit) = PillGeometry.region(false, true, false, div, layout, layout.w, layout.dx)
        assertTrue(gone.isEmpty() && goneHit == null)
        // Moving: the envelope and the handle's target.
        val (moving, movingHit) = PillGeometry.region(true, true, true, div, layout, layout.w, layout.dx)
        assertTrue(moving.single().t < open.t && moving.single().w > open.w)
        assertEquals(PillGeometry.handleHit(div), movingHit)
    }

    /** How far (x, y) lies outside the drawn capsule [c], dp; 0 inside. */
    private fun outside(c: PillRect, x: Double, y: Double): Double {
        val rad = minOf(c.w, c.h) / 2
        val dx = x - x.coerceIn(c.l + rad, maxOf(c.l + rad, c.r - rad))
        val dy = y - y.coerceIn(c.t + rad, maxOf(c.t + rad, c.b - rad))
        return maxOf(0.0, kotlin.math.sqrt(dx * dx + dy * dy) - rad)
    }

    @Test
    fun anItemIsTakenInItsOwnMeasure() {
        // The view maps a touch through an item's drawn place and scale; these are the shapes it tests.
        val chip = chip()
        assertTrue(PillGeometry.hitsItem(chip, SplitPillSpec.Chip.STACKED / 2, 0.0))
        assertFalse(PillGeometry.hitsItem(chip, -SplitPillSpec.Chip.HIT - 1, 0.0))
        assertFalse(PillGeometry.hitsItem(PillItem.Separator, 0.0, 0.0))
        assertFalse(PillGeometry.hitsItem(PillItem.DividerMark, 0.0, 0.0))
        val r = SplitPillSpec.Btn.SIZE / 2
        assertTrue(PillGeometry.hitsItem(PillItem.Plus, r, r - 1))
        assertFalse("«+» is a disc", PillGeometry.hitsItem(PillItem.Plus, 1.0, -r + 1))
        val cx = SplitPillSpec.Badge.X + SplitPillSpec.Badge.SIZE / 2
        val cy = -SplitPillSpec.Row.H_CHIP / 2 + SplitPillSpec.Badge.Y + SplitPillSpec.Badge.SIZE / 2
        assertTrue(PillGeometry.hitsBadge(cx, cy))
        assertFalse(PillGeometry.hitsBadge(SplitPillSpec.Chip.STACKED + SplitPillSpec.Row.GAP_CHIP / 2 + 1, cy))
    }

    @Test
    fun theBadgeTakesItsDiscButNeverTheNextChip() {
        val l = PillGeometry.layout(listOf(chip(), chip("x", "y"), PillItem.Separator, PillItem.Plus))
        val disc = PillGeometry.badgeDisc(l, 0)
        assertTrue(PillGeometry.inBadge(l, 0, disc.cx, disc.cy))
        val next = l.x[1]
        assertFalse(PillGeometry.inBadge(l, 0, next + 1, disc.cy))
        assertEquals(0, PillGeometry.itemAt(l, l.x[0] + l.widthOf(0) / 2, 0.0))
        assertEquals(1, PillGeometry.itemAt(l, l.x[1] + 2, 0.0))
        assertEquals("the separator takes no touch", -1, PillGeometry.itemAt(l, l.x[2], 0.0))
        assertEquals(3, PillGeometry.itemAt(l, l.x[3] + l.widthOf(3) / 2, 0.0))
    }

    @Test
    fun theDividerIsReadFromTheWindowList() {
        val h = 1600
        val divider = PillDivider.Box(system = true, application = false, title = "multi-divider", l = 856, t = 112, r = 880, b = 1472)
        val left = PillDivider.Box(system = false, application = true, title = "a", l = 0, t = 112, r = 860, b = 1472)
        val right = PillDivider.Box(system = false, application = true, title = "b", l = 876, t = 112, r = 2560, b = 1472)
        assertEquals(868.0, PillDivider.centrePx(listOf(left, divider, right), h)!!, 0.0)
        assertEquals(868.0, PillDivider.centrePx(listOf(left, right), h)!!, 0.0)
        // The window typed as the divider is the whole screen: it says nothing of where.
        val typed = PillDivider.Box(system = false, application = false, title = null, l = 0, t = 0, r = 2560, b = 1600)
        assertNull(PillDivider.centrePx(listOf(typed), h))
        assertNull("one full-screen app", PillDivider.centrePx(listOf(left.copy(r = 2560)), h))
        assertNull("a short window is no pane", PillDivider.centrePx(listOf(left, right.copy(t = 1300)), h))
        // Two tall windows side by side but one above the other share no band: no divider there.
        val upper = PillDivider.Box(system = false, application = true, title = "a", l = 0, t = 0, r = 600, b = 700)
        val lower = PillDivider.Box(system = false, application = true, title = "b", l = 620, t = 850, r = 2560, b = 1600)
        assertNull(PillDivider.centrePx(listOf(upper, lower), h))
    }

    private fun chip(a: String = "nav", b: String = "music") =
        PillItem.Chip(SplitPair(a, b), a, b, missing = null, live = false, badge = PillBadge.OUTLINE)

    private fun single(pane: SplitPane, app: String) = PillItem.Single(pane, app)
}
