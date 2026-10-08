package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.luminofor.LuminoforSpec.Cluster
import dev.denza.apps.feature.cluster.ClusterMapLayout
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
 * Everything here is a fact about the panel the vehicle leaves us: `ClusterMapLayout`'s own shade
 * numbers - wherever that shade blacks the map out something stock lives, and wherever it cuts a
 * reveal we may draw - and the spec's copy of them. `ContourGeometryTest` is where the composition is
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
    fun theLuminoforSpecsKeepOutsAreTheMapShadesInItsOwnUnits() {
        // spec.json states the stock zones in the cluster's 1507.56 × 424 units, and the map's
        // shade - tuned on the car - states them in pixels. One set of zones: a change to the shade
        // moves the board or fails here.
        val map = ClusterMapLayout(2560, 720, ClusterMapPlacement.FULL)
        val x = Cluster.W / 2560f
        val y = Cluster.H / 720f
        assertEquals(Cluster.STOCK_TOP, map.shadeTopRevealHeightPx * y, 1e-3f)
        assertEquals(Cluster.STOCK_BOTTOM, (720 - map.shadeBottomSolidPx - map.shadeBottomFadePx) * y, 1e-3f)
        assertEquals(Cluster.LEFT_APERTURE_RX, map.shadeTopLeftRevealRadiusPx * x, 1e-3f)
        assertEquals(Cluster.RIGHT_APERTURE_RX, map.shadeTopRightRevealRadiusPx * x, 1e-3f)
        assertEquals(Cluster.PETAL_RX, map.shadeBottomRevealRadiusPx * x, 1e-3f)
        assertEquals(
            Cluster.PETAL_RY,
            map.shadeBottomRevealRadiusPx * map.shadeBottomRevealHeightPercent / 100f * y,
            1e-3f,
        )
        assertEquals(Cluster.PETAL_CY, (720 - map.shadeBottomRevealCenterOffsetPx) * y, 1e-3f)
    }

    @Test
    fun thePanelLandsOnTheGlassAtTheScaleItsSizesWereMeasuredFor() {
        // 424 units into 720 pixels. Every size the spec gives the cluster is stated in those units,
        // and the factor is what turns a size into a number of arc minutes from the driver's seat.
        val panel = full().bounds
        assertEquals(1.70f, (panel.bottom - panel.top) / Cluster.H, 0.01f)
    }
}
