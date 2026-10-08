package dev.denza.apps.ui

import androidx.compose.ui.unit.dp
import dev.denza.apps.DenzaUiState
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.feature.trip.StripGeometry
import dev.denza.apps.feature.trip.TripPanelLayout
import dev.denza.apps.ui.dashboard.DashboardTiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The policy's own decisions. Which mode each of Luminofor's three windows resolves to is
 * `LuminoforScreenContractTest.theThreeWindowsAreTheSpecsThree`, read off `spec.json`.
 */
class DashboardLayoutPolicyTest {
    @Test
    fun `thresholds have no ambiguous width`() {
        assertEquals(
            DashboardLayoutMode.NARROW,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.NARROW_MAX_WIDTH_DP),
        )
        assertEquals(
            DashboardLayoutMode.MEDIUM,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.NARROW_MAX_WIDTH_DP + 1),
        )
        assertEquals(
            DashboardLayoutMode.MEDIUM,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.MEDIUM_MAX_WIDTH_DP),
        )
        assertEquals(
            DashboardLayoutMode.WIDE,
            DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.MEDIUM_MAX_WIDTH_DP + 1),
        )
    }

    @Test
    fun `each width gets its own margin, its own row and its own kind of feature`() {
        // One table, because these answers are one decision: the margin buys the width, the width
        // decides whether a feature can afford its name, and what is left over is the strip. The
        // numbers are Luminofor's - main-sound, two-sound and one-sound - and they are three rungs
        // of the spacing ladder, which is why this compares them with the ladder.
        assertEquals(DenzaMetrics.Space.XXL, DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.WIDE))
        assertEquals(DenzaMetrics.Space.L, DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.MEDIUM))
        assertEquals(DenzaMetrics.Space.M, DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.NARROW))

        assertEquals(6, DashboardLayoutPolicy.columns(DashboardLayoutMode.WIDE, FEATURES))
        assertEquals(12, DashboardLayoutPolicy.columns(DashboardLayoutMode.MEDIUM, FEATURES))
        assertEquals(6, DashboardLayoutPolicy.columns(DashboardLayoutMode.NARROW, FEATURES))

        // A feature is written out on the full screen and compressed to a chip in a pane.
        assertEquals(false, DashboardLayoutPolicy.chips(DashboardLayoutMode.WIDE))
        assertEquals(true, DashboardLayoutPolicy.chips(DashboardLayoutMode.MEDIUM))
        assertEquals(true, DashboardLayoutPolicy.chips(DashboardLayoutMode.NARROW))

        assertEquals(TripPanelLayout.WIDE, DashboardLayoutPolicy.panel(DashboardLayoutMode.WIDE))
        assertEquals(TripPanelLayout.MEDIUM, DashboardLayoutPolicy.panel(DashboardLayoutMode.MEDIUM))
        assertEquals(TripPanelLayout.NARROW, DashboardLayoutPolicy.panel(DashboardLayoutMode.NARROW))
    }

    @Test
    fun `an eleventh feature costs the chip seven dp and the analyser nothing`() {
        // The question this answers: what happens when a feature is added. A band of icons is a
        // toolbar and a toolbar fits rather than wraps, so the chip is its share of the row and an
        // eleventh makes every chip smaller instead of taking a whole row of 80 dp off the
        // analyser. `ChipDensity.dc.html` draws ten through thirteen. The gap is the one the
        // Luminofor boards spread eleven chips with - 12.03 and 12.04 - so the eleventh lands on
        // the spec's 60.7 and 55.3 to the hundredth and the other counts move by the same rule.
        assertEquals(10, DashboardLayoutPolicy.columns(DashboardLayoutMode.MEDIUM, 10))
        assertEquals(11, DashboardLayoutPolicy.columns(DashboardLayoutMode.MEDIUM, 11))
        assertEquals(5, DashboardLayoutPolicy.columns(DashboardLayoutMode.NARROW, 10))
        assertEquals(6, DashboardLayoutPolicy.columns(DashboardLayoutMode.NARROW, 11))
        assertEquals(6, DashboardLayoutPolicy.columns(DashboardLayoutMode.NARROW, 12))

        assertEquals(68.0f, DashboardLayoutPolicy.chipWidth(DashboardLayoutMode.MEDIUM, 10).value, 0.05f)
        assertEquals(60.7f, DashboardLayoutPolicy.chipWidth(DashboardLayoutMode.MEDIUM, 11).value, 0.05f)
        assertEquals(68.8f, DashboardLayoutPolicy.chipWidth(DashboardLayoutMode.NARROW, 10).value, 0.05f)
        assertEquals(55.3f, DashboardLayoutPolicy.chipWidth(DashboardLayoutMode.NARROW, 11).value, 0.05f)
        // The twelfth - the cloud link - is the same rule once more: six dp off every chip in the
        // two-thirds row, and nothing at all in the narrow pane, where it fills the second row.
        assertEquals(54.64f, DashboardLayoutPolicy.chipWidth(DashboardLayoutMode.MEDIUM, 12).value, 0.05f)
        assertEquals(55.3f, DashboardLayoutPolicy.chipWidth(DashboardLayoutMode.NARROW, 12).value, 0.05f)
    }

    @Test
    fun `the dashboard this app ships still leaves a chip worth pressing`() {
        // The guard, and it is deliberately the thing that fails rather than the screen. Twelve
        // features fit both panes - 54.7 and 55.3 dp - and a thirteenth is 49.5 and 45.7, under a
        // floor set by a glyph that has to read at arm's length and a target a finger has to hit.
        // Adding one past that is a design decision, so it should stop somebody here and not turn
        // up as chips that quietly got smaller.
        val floor = DenzaMetrics.Component.CHIP_MIN.value
        for (mode in listOf(DashboardLayoutMode.MEDIUM, DashboardLayoutMode.NARROW)) {
            val chip = DashboardLayoutPolicy.chipWidth(mode, FEATURES).value
            assertTrue(
                "$FEATURES features put the $mode chip at $chip dp, under the $floor floor - " +
                    "the panes need a row added or a feature dropped, not a smaller chip",
                chip >= floor,
            )
            assertTrue(
                "twelve should still fit $mode",
                DashboardLayoutPolicy.chipWidth(mode, 12).value >= floor,
            )
            assertTrue(
                "and thirteen should not, or this floor means nothing",
                DashboardLayoutPolicy.chipWidth(mode, 13).value < floor,
            )
        }
    }

    @Test
    fun `the three windows are one record and the thresholds fall between them`() {
        // There used to be two records of the same three sizes - thresholds at 599 and 1099, and
        // 1280/828/416 typed again inside chipWidth - so moving one of them moved nothing. The
        // thresholds are now derived, and what is worth asserting is that they still sit between
        // the windows they separate rather than any particular value they came out at.
        assertTrue(
            "the narrow threshold is not between the two windows it separates",
            DashboardLayoutPolicy.NARROW_MAX_WIDTH_DP in
                DashboardLayoutPolicy.NARROW_WIDTH_DP until DashboardLayoutPolicy.MEDIUM_WIDTH_DP,
        )
        assertTrue(
            "the medium threshold is not between the two windows it separates",
            DashboardLayoutPolicy.MEDIUM_MAX_WIDTH_DP in
                DashboardLayoutPolicy.MEDIUM_WIDTH_DP until DashboardLayoutPolicy.WIDE_WIDTH_DP,
        )
        for (mode in DashboardLayoutMode.entries) {
            assertEquals(
                "$mode should resolve to itself at the width it was measured in",
                mode,
                DashboardLayoutPolicy.resolve(DashboardLayoutPolicy.windowWidth(mode)),
            )
        }
    }

    @Test
    fun `the strip keeps a floor and the page scrolls rather than overflowing`() {
        // The failure this replaces: the full screen added up to exactly 680 - 20 + 340 + 12 + 296
        // + 12 - with no scroll in any of the three widths, so any inset at all drew the foot of
        // the analyser past the bottom edge in silence. And the pane's floor was a comment: it was
        // written as `heightIn(min =)` under a `weight(1f)`, which a Column measures as an exact
        // height, so it could never have held anything up.
        val wideContent = 1_280f - DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.WIDE).value * 2
        val narrowContent = 416f - DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.NARROW).value * 2

        // The full screen as the board draws it: 20 over two rows of tiles, 12, the strip's own
        // 1184x296 and 12 under it, adding up to the window with nothing over.
        val wide = DashboardLayoutPolicy.page(
            DashboardLayoutMode.WIDE, FEATURES, wideContent, WINDOW_DP.dp,
        )
        assertEquals(296f, wide.panelHeight.value, 1e-3f)
        assertEquals(false, wide.scrolls)

        // The same screen with a caption bar over it - which is what a pane always has, and what
        // any future window that keeps more of itself would give the full screen too.
        val squeezed = DashboardLayoutPolicy.page(
            DashboardLayoutMode.WIDE, FEATURES, wideContent, (WINDOW_DP - CAPTION_DP).dp,
        )
        assertEquals("the strip keeps the board's shape", 296f, squeezed.panelHeight.value, 1e-3f)
        assertEquals("and the page that no longer holds it scrolls", true, squeezed.scrolls)

        // A pane has room to spare, so its floor never binds and the caller hands the strip a
        // weight instead of this number - which comes out at the one-third board's strip box,
        // 182.6 to 668.
        val pane = DashboardLayoutPolicy.page(
            DashboardLayoutMode.NARROW, FEATURES, narrowContent, (WINDOW_DP - CAPTION_DP).dp,
        )
        assertEquals(485.4f, pane.panelHeight.value, 0.05f)
        assertEquals(false, pane.scrolls)

        // And a window too short for it holds the floor rather than shrinking the analyser.
        val shortPane = DashboardLayoutPolicy.page(
            DashboardLayoutMode.NARROW, FEATURES, narrowContent, 400.dp,
        )
        assertEquals(
            StripGeometry.minimumHeight(TripPanelLayout.NARROW),
            shortPane.panelHeight.value,
            1e-3f,
        )
        assertEquals(true, shortPane.scrolls)
    }

    /**
     * A pane's strip is never handed less than both of its pages fit.
     *
     * The floor was a constant of 300 dp while the strip's own pages need 444.4 (one third) and
     * 462.3 (two thirds): the analyser's floor and the page dots rise with the box's foot, and
     * under that height the dots land on the car page's chart and the analyser's field shrinks
     * below the 80 dp it is read at. This test used to hold "a 400 dp window gives the strip 300"
     * as correct, and any pane more than 41 dp (one third) or 81 dp (two thirds) shorter than
     * today's would have drawn it.
     */
    @Test
    fun `a pane strip never gets less than both of its pages fit`() {
        for (mode in listOf(DashboardLayoutMode.NARROW, DashboardLayoutMode.MEDIUM)) {
            val content = DashboardLayoutPolicy.windowWidth(mode) -
                DashboardLayoutPolicy.sideMargin(mode).value * 2
            val least = StripGeometry.minimumHeight(DashboardLayoutPolicy.panel(mode))
            // What the rest of the page costs, read off a window with room to spare.
            val window = WINDOW_DP - CAPTION_DP
            val roomy = DashboardLayoutPolicy.page(mode, FEATURES, content, window.dp)
            assertEquals("$mode: today's window has room", false, roomy.scrolls)
            val rest = window - roomy.panelHeight.value
            fun at(room: Float) = DashboardLayoutPolicy.page(mode, FEATURES, content, (room + rest).dp)

            // Between the old constant and the pages' own least: the case the constant let through.
            val between = at((300f + least) / 2)
            assertEquals("$mode: the strip keeps its least box", least, between.panelHeight.value, 1e-3f)
            assertEquals("$mode: and the page scrolls to show it", true, between.scrolls)

            // Exactly that height fits, and a little more goes to the strip.
            assertEquals("$mode at its least", false, at(least).scrolls)
            assertEquals(least + 10f, at(least + 10f).panelHeight.value, 1e-3f)
        }
    }

    @Test
    fun `only the full screen takes its strip height from its width`() {
        // The wide panel is a fixed shape - 1184 by 296 - so a caller asks for a box of that shape
        // and the drawing arrives unstretched. A pane's strip is handed the remainder and lays
        // itself out in whatever it gets, which is the one arrangement that could not have had the
        // bug the first cut of these panes shipped with: the height was worked out by hand against
        // 680, the car keeps 24 of that for its caption bar, and the foot of the strip was drawn
        // past the bottom edge of the window.
        assertEquals(296f, DashboardLayoutPolicy.wholeScreenPanelHeight(1_184f).value, 1e-3f)
        assertEquals(148f, DashboardLayoutPolicy.wholeScreenPanelHeight(592f).value, 1e-3f)
    }

    private companion object {
        /**
         * What the dashboard carries, counted off the dashboard; DashboardTilesTest owns the list.
         * It was the number 12 typed here until 2026-10-08, so a thirteenth tile went past the
         * chip's floor without this file noticing.
         */
        val FEATURES = DashboardTiles.of(DenzaUiState()).size

        /** The app's window: what the car leaves it, measured. The page lays itself out inside. */
        const val WINDOW_DP = 680f

        /** What BYD's freeform windowing keeps at the top of a pane. Measured on the car. */
        const val CAPTION_DP = 24f
    }
}
