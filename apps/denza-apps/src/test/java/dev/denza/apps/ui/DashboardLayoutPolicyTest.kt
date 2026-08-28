package dev.denza.apps.ui

import androidx.compose.ui.unit.dp
import dev.denza.apps.design.DenzaMetrics
import dev.denza.apps.feature.trip.TripPanelLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLayoutPolicyTest {
    @Test
    fun `measured one-third pane uses vertical layout`() {
        assertEquals(
            DashboardLayoutMode.NARROW,
            DashboardLayoutPolicy.resolve(416),
        )
    }

    @Test
    fun `measured two-thirds pane fits the dashboard into its own width`() {
        // Правка W8: холст 1280 dp в горизонтальном скролле прятал ~904 px за краем панели 828 dp.
        assertEquals(
            DashboardLayoutMode.MEDIUM,
            DashboardLayoutPolicy.resolve(828),
        )
    }

    @Test
    fun `measured fullscreen width keeps the existing layout`() {
        assertEquals(
            DashboardLayoutMode.WIDE,
            DashboardLayoutPolicy.resolve(1_280),
        )
    }

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
        // pane numbers are the boards' - TwoThirds.dc.html and OneThird.dc.html.
        assertEquals(DenzaMetrics.Space.XXL, DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.WIDE))
        assertEquals(DenzaMetrics.Space.L, DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.MEDIUM))
        assertEquals(DenzaMetrics.Space.M, DashboardLayoutPolicy.sideMargin(DashboardLayoutMode.NARROW))

        assertEquals(6, DashboardLayoutPolicy.columns(DashboardLayoutMode.WIDE, FEATURES))
        assertEquals(11, DashboardLayoutPolicy.columns(DashboardLayoutMode.MEDIUM, FEATURES))
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
        // analyser. `ChipDensity.dc.html` draws ten through thirteen.
        assertEquals(10, DashboardLayoutPolicy.columns(DashboardLayoutMode.MEDIUM, 10))
        assertEquals(11, DashboardLayoutPolicy.columns(DashboardLayoutMode.MEDIUM, 11))
        assertEquals(5, DashboardLayoutPolicy.columns(DashboardLayoutMode.NARROW, 10))
        assertEquals(6, DashboardLayoutPolicy.columns(DashboardLayoutMode.NARROW, 11))
        assertEquals(6, DashboardLayoutPolicy.columns(DashboardLayoutMode.NARROW, 12))

        assertEquals(68.0f, DashboardLayoutPolicy.chipWidth(DashboardLayoutMode.MEDIUM, 10).value, 0.05f)
        assertEquals(60.7f, DashboardLayoutPolicy.chipWidth(DashboardLayoutMode.MEDIUM, 11).value, 0.05f)
        assertEquals(68.8f, DashboardLayoutPolicy.chipWidth(DashboardLayoutMode.NARROW, 10).value, 0.05f)
        assertEquals(55.3f, DashboardLayoutPolicy.chipWidth(DashboardLayoutMode.NARROW, 11).value, 0.05f)
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
    fun `a chip row costs a pane a fraction of what a tile row costs`() {
        // The reason a pane has chips at all, as arithmetic rather than as taste. Eleven tiles at the
        // width their names need are four rows at 828 dp and five at 416 - 692 and 868 dp of a
        // window that has 656 once the car has taken its caption bar - so the page scrolled and
        // the analyser was below the fold. Eleven chips are one row and two.
        for ((window, mode) in listOf(
            828 to DashboardLayoutMode.MEDIUM,
            416 to DashboardLayoutMode.NARROW,
        )) {
            val columns = DashboardLayoutPolicy.columns(mode, FEATURES)
            val content = window - DashboardLayoutPolicy.sideMargin(mode).value * 2
            val chip = (content - (columns - 1) * DenzaMetrics.Space.M.value) / columns
            val rows = (FEATURES + columns - 1) / columns
            val band = rows * chip + (rows - 1) * DenzaMetrics.Space.M.value

            assertTrue(
                "a chip at $window dp is $chip, which is not chip-sized",
                chip in DenzaMetrics.Component.CHIP_MIN.value..76f,
            )
            assertTrue(
                "$FEATURES chips at $window dp take $band dp, which is no better than tiles",
                band < 3 * DenzaMetrics.Component.TILE_HEIGHT.value,
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

        // The full screen as the board draws it: two rows of tiles and the strip's own 1184x296,
        // adding up to the window with nothing over.
        val wide = DashboardLayoutPolicy.page(
            DashboardLayoutMode.WIDE, FEATURES, wideContent, PAGE_HEIGHT.dp,
        )
        assertEquals(296f, wide.panelHeight.value, 1e-3f)
        assertEquals(false, wide.scrolls)

        // The same screen with a caption bar over it - which is what a pane always has, and what
        // any future window that keeps more of itself would give the full screen too.
        val squeezed = DashboardLayoutPolicy.page(
            DashboardLayoutMode.WIDE, FEATURES, wideContent, (PAGE_HEIGHT - CAPTION_DP).dp,
        )
        assertEquals("the strip keeps the board's shape", 296f, squeezed.panelHeight.value, 1e-3f)
        assertEquals("and the page that no longer holds it scrolls", true, squeezed.scrolls)

        // A pane has room to spare, so its floor never binds and the caller hands the strip a
        // weight instead of this number.
        val pane = DashboardLayoutPolicy.page(
            DashboardLayoutMode.NARROW, FEATURES, narrowContent, (PAGE_HEIGHT - CAPTION_DP).dp,
        )
        assertEquals(469.3f, pane.panelHeight.value, 0.05f)
        assertEquals(false, pane.scrolls)

        // And a window too short for it holds the floor rather than shrinking the analyser.
        val shortPane = DashboardLayoutPolicy.page(
            DashboardLayoutMode.NARROW, FEATURES, narrowContent, 400.dp,
        )
        assertEquals(
            DenzaMetrics.Component.PANEL_HEIGHT_MIN.value,
            shortPane.panelHeight.value,
            1e-3f,
        )
        assertEquals(true, shortPane.scrolls)
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
        /** What the dashboard actually carries today; DashboardTilesTest owns the list itself. */
        const val FEATURES = 11

        /**
         * What is left of the app's 680 dp window once the page's own margins are off it: 20 over
         * the features and 12 under the strip.
         */
        val PAGE_HEIGHT: Float = 680f -
            DenzaMetrics.Space.L.value - DenzaMetrics.Space.M.value

        /** What BYD's freeform windowing keeps at the top of a pane. Measured on the car. */
        const val CAPTION_DP = 24f
    }
}
