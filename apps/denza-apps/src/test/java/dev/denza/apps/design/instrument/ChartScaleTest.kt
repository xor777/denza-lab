package dev.denza.apps.design.instrument

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartScaleTest {

    @Test
    fun anEmptyRunStillUsesTheFloorsSoTheAxisDoesNotJump() {
        val (positive, negative) = ChartScale.ceilings(emptyList())
        assertEquals(10f, positive, 0f)
        assertEquals(5f, negative, 0f)
    }

    @Test
    fun ceilingsRoundOutwardToARoundNumber() {
        val (positive, negative) = ChartScale.ceilings(listOf(23f, -7f))
        assertEquals(30f, positive, 0f)
        assertEquals(10f, negative, 0f)
    }

    @Test
    fun aQuietStretchIsNotMagnifiedByItsOwnSmallness() {
        // Everything under the floor keeps the floor, so 2 kWh/100 does not fill the chart.
        val ceilings = ChartScale.ceilings(listOf(1f, 2f, 1.5f))
        assertEquals(10f, ceilings.first, 0f)
        val height = 100f
        assertTrue(ChartScale.barHeight(2f, height, ceilings) < ChartScale.zeroLine(height) / 2f)
    }

    @Test
    fun theZeroLineLeavesRoomBelowItForRecovery() {
        val height = 100f
        val zero = ChartScale.zeroLine(height)
        assertEquals(74f, zero, 1e-4f)
        assertTrue(height - zero > 0f)
    }

    @Test
    fun spendingGrowsUpAndRecoveryGrowsDownWithinTheirOwnRoom() {
        val height = 100f
        val ceilings = ChartScale.ceilings(listOf(30f, -10f))

        val full = ChartScale.barHeight(30f, height, ceilings)
        assertEquals(ChartScale.zeroLine(height), full, 1e-4f)

        val recovered = ChartScale.barHeight(-10f, height, ceilings)
        assertEquals(height - ChartScale.zeroLine(height), recovered, 1e-4f)
    }

    @Test
    fun aBarNeverEscapesItsHalfOfTheChart() {
        val height = 100f
        val ceilings = ChartScale.ceilings(listOf(10f, -5f))
        assertTrue(ChartScale.barHeight(999f, height, ceilings) <= ChartScale.zeroLine(height))
        assertTrue(
            ChartScale.barHeight(-999f, height, ceilings) <= height - ChartScale.zeroLine(height),
        )
    }
}
