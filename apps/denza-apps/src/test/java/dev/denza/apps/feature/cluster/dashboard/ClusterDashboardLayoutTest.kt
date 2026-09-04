package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.feature.cluster.ClusterMapPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The verified instrument display: 2560x720, from docs/instrument-display-findings.md. */
private fun full() = ClusterDashboardLayout(2560, 720, ClusterMapPlacement.FULL)

/**
 * What the window is, before anything is drawn in it.
 *
 * Everything here is a fact about the panel the vehicle leaves us, read out of `ClusterMapLayout`'s
 * own shade numbers rather than restated - wherever that shade blacks the map out something stock
 * lives, and wherever it cuts a reveal we may draw. `ContourPlanTest` is where the composition is
 * measured against these.
 */
class ClusterDashboardLayoutTest {

    @Test
    fun theDashboardIsAFullWidthCompositionAndOffersNothingElse() {
        assertTrue(full().supported)
        // A third of this panel is not a smaller version of this instrument, it is a different
        // instrument, and this product does not offer that one.
        assertFalse(ClusterDashboardLayout(2560, 720, ClusterMapPlacement.RIGHT).supported)
        assertFalse(ClusterDashboardLayout(2560, 720, ClusterMapPlacement.CENTER).supported)
        assertFalse(ClusterDashboardLayout(2560, 720, ClusterMapPlacement.LEFT).supported)
    }

    @Test
    fun theKeepOutsComeFromTheSameNumbersAsTheMapShade() {
        val layout = full()
        // The shade clears by 272 px and its footer is 90 px solid over a 60 px fade.
        assertEquals(272f / 720f, layout.stockTop, 1e-4f)
        assertEquals(1f - 150f / 720f, layout.stockBottom, 1e-4f)
    }

    @Test
    fun theApertureRadiiAreTheShadesOwnReveals() {
        val layout = full()
        assertEquals(614f / 2560f, layout.topLeftRevealX, 1e-6f)
        assertEquals(512f / 2560f, layout.topRightRevealX, 1e-6f)
        assertEquals(600f / 2560f, layout.bottomRevealX, 1e-6f)
        assertEquals(330f / 720f, layout.bottomRevealY, 1e-6f)
        assertEquals(1f - 120f / 720f, layout.bottomRevealCentreY, 1e-6f)
    }

    @Test
    fun theBandBetweenTheStockEdgesIsOursAcrossTheWholeWidth() {
        val layout = full()
        val middle = (layout.stockTop + layout.stockBottom) / 2f
        assertTrue(layout.isClear(0f, middle))
        assertTrue(layout.isClear(0.5f, middle))
        assertTrue(layout.isClear(1f, middle))
    }

    @Test
    fun theTopCentreIsRefusedAndTheTopCornersAreNot() {
        val layout = full()
        val high = layout.stockTop / 3f
        assertFalse(layout.isClear(0.5f, high))
        assertTrue(layout.isClear(0.02f, high))
        assertTrue(layout.isClear(0.98f, high))
    }

    @Test
    fun theBottomIsRefusedExceptThroughItsCentralReveal() {
        val layout = full()
        val low = layout.stockBottom + (1f - layout.stockBottom) / 2f
        assertTrue(layout.isClear(0.5f, low))
        assertFalse(layout.isClear(0.05f, low))
        assertFalse(layout.isClear(0.95f, low))
    }

    @Test
    fun thePanelLandsOnTheGlassAtTheScaleTheRampWasMeasuredFor() {
        // 424 units into 720 pixels. Every rung on the ramp is stated in those units, and it is what
        // turns a size into a number of arc minutes from the driver's seat.
        assertEquals(1.70f, full().height / full().virtualHeight, 0.01f)
    }
}
