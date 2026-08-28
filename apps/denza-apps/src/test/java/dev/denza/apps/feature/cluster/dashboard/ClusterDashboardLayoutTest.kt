package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.design.instrument.EnergyGauge
import dev.denza.apps.design.instrument.InstrumentDensity
import dev.denza.apps.feature.cluster.ClusterMapPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The verified instrument display: 2560x720, from docs/instrument-display-findings.md. */
private fun full() = ClusterDashboardLayout(2560, 720, ClusterMapPlacement.FULL)

private fun right() = ClusterDashboardLayout(2560, 720, ClusterMapPlacement.RIGHT)

class ClusterDashboardLayoutTest {

    @Test
    fun theDashboardOnlyOffersThePlacementsThatHaveRenderedLive() {
        assertTrue(full().supported)
        assertTrue(right().supported)
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
    fun theRightThirdIsClearAllTheWayThroughBecauseItsProtectionIsTheCrop() {
        val layout = right()
        assertEquals(0f, layout.stockTop, 1e-4f)
        assertEquals(1f, layout.stockBottom, 1e-4f)
        assertTrue(layout.isClear(0.5f, 0f))
        assertTrue(layout.isClear(0.5f, 1f))
        // A crop, not a shade, so there are no reveals to place corner blocks in.
        assertNull(layout.temperatureBlock)
        assertNull(layout.lampBlock)
    }

    @Test
    fun theRightThirdKeepsTheGeometryTheCarWasCapturedWith() {
        val layout = right()
        assertEquals(1537, layout.bounds.left)
        assertEquals(95, layout.bounds.top)
        assertEquals(2560, layout.bounds.right)
        assertEquals(619, layout.bounds.bottom)
        assertEquals(1023, layout.width)
        assertEquals(524, layout.height)
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
    fun everyBlockThisDesignPlacesStaysOffTheStockGraphics() {
        val layout = full()
        assertTrue(layout.isClear(layout.electricBlock))
        assertTrue(layout.isClear(layout.engineBlock))
        assertTrue(layout.isClear(requireBox(layout.temperatureBlock)))
        assertTrue(layout.isClear(requireBox(layout.lampBlock)))
    }

    @Test
    fun theTwoInstrumentsDoNotReachIntoTheGauge() {
        val layout = full()
        // The gauge radius is a share of height; on the panel it has to be compared in width.
        val radiusInWidth = layout.gaugeRadius * layout.height / layout.width
        assertTrue(layout.electricBlock.right < layout.gaugeCentreX - radiusInWidth)
        assertTrue(layout.engineBlock.left > layout.gaugeCentreX + radiusInWidth)
    }

    @Test
    fun theGaugeItselfStaysInsideTheClearGround() {
        val layout = full()
        val radiusInWidth = layout.gaugeRadius * layout.height / layout.width
        // The top of the arc, and the two ends, which hang below the band into the reveal.
        assertTrue(layout.isClear(layout.gaugeCentreX, layout.gaugeCentreY - layout.gaugeRadius))
        val endY = layout.gaugeCentreY + layout.gaugeRadius * 0.342f
        assertTrue(layout.isClear(layout.gaugeCentreX - radiusInWidth * 0.94f, endY))
        assertTrue(layout.isClear(layout.gaugeCentreX + radiusInWidth * 0.94f, endY))
    }

    @Test
    fun bothPlacementsLandOnThePanelAtTheSameScaleWhichIsWhyOneRampServesBoth() {
        assertEquals(1.70f, full().height / full().virtualHeight, 0.01f)
        assertEquals(1.70f, right().height / right().virtualHeight, 0.01f)
    }

    @Test
    fun theGaugesMarksClearTheStockEdgeAndNotJustTheArc() {
        val layout = full()
        val density = InstrumentDensity.WIDE
        val reach = EnergyGauge.topReach(density) / layout.virtualHeight
        val crown = layout.gaugeCentreY - layout.gaugeRadius - reach
        assertTrue(
            "the zero mark reaches $crown, above the stock edge at ${layout.stockTop}",
            crown > layout.stockTop,
        )
        assertTrue(layout.isClear(layout.gaugeCentreX, crown))
    }

    @Test
    fun theDialsOwnNumbersStayOutOfTheColumnBesideIt() {
        listOf(full() to InstrumentDensity.WIDE, right() to InstrumentDensity.COMPACT)
            .forEach { (layout, density) ->
                val unit = layout.height / layout.virtualHeight
                val radius = layout.gaugeRadius * layout.height
                val degrees = EnergyGauge.outermostMarkDegrees(density)
                val label = EnergyGauge.widestMark()
                val out = radius + EnergyGauge.markReach(density, label, degrees) * unit
                val x = layout.gaugeCentreX * layout.width +
                    out * kotlin.math.cos(Math.toRadians(degrees.toDouble())).toFloat()
                // The reach now carries the number's own half-extent along the radius it sits on,
                // so there is nothing left to stand in for and the column's edge is the edge.
                val edge = layout.engineBlock.left * layout.width
                assertTrue("the $degrees deg mark reaches $x against a column at $edge", x < edge)
            }
    }

    @Test
    fun theWidestReadingTheDialCanShowStillFitsInsideIt() {
        listOf(full() to InstrumentDensity.WIDE, right() to InstrumentDensity.COMPACT)
            .forEach { (layout, density) ->
                val radius = layout.gaugeRadius * layout.virtualHeight
                val chord = EnergyGauge.chordAtReading(radius, density)
                val half = EnergyGauge.widestReading(density) / 2f
                // Three digits is the ceiling: the scale clamps at 300 kW one way and 100 the
                // other, and the reading is a magnitude, so a sign never takes a fourth character.
                assertTrue(
                    "a full-scale reading is $half wide against $chord of arc",
                    half < chord - density.body,
                )
            }
    }

    @Test
    fun aRevealBlockIsSolvedForItsLowerEdgeSoItsWidestPartCannotEscape() {
        val layout = full()
        val temperatures = requireBox(layout.temperatureBlock)
        val lamps = requireBox(layout.lampBlock)
        // The right reveal is the narrower of the two, so its block must be narrower as well.
        assertTrue(lamps.right - lamps.left < temperatures.right - temperatures.left)
        // Both sit above the stock edge, which is what makes them reveal blocks at all.
        assertTrue(temperatures.bottom < layout.stockTop)
        assertTrue(lamps.bottom < layout.stockTop)
    }
}

private fun requireBox(box: DashboardBox?): DashboardBox {
    assertNotNull("this placement should offer the block", box)
    return box!!
}
