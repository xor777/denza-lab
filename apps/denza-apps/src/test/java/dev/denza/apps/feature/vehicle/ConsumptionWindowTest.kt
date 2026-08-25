package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three windows, and the folding that keeps a chart readable at all of them.
 *
 * This is the part of the feature that has to be right before anything is drawn:
 * a window that quietly loses the newest bar, or folds groups of different sizes
 * and calls them equal, is a lie the renderer cannot detect.
 */
class ConsumptionWindowTest {

    private fun ramp(n: Int) = List(n) { it.toDouble() }

    @Test
    fun theLogHoldsExactlyTheLongestWindow() {
        // Folding down is possible and folding back up is not, so the log has to
        // store the finest resolution the widest window wants.
        assertEquals(ConsumptionWindow.LONG.buckets, ConsumptionLog.DEFAULT_CAPACITY)
        assertEquals(0.1, ConsumptionLog.DEFAULT_BUCKET_KM, 1e-9)
    }

    @Test
    fun eachWindowFoldsToAboutTheSameNumberOfBars() {
        ConsumptionWindow.entries.forEach { window ->
            val bars = window.fold(ramp(window.buckets))
            assertTrue(
                "${window.label} folded to ${bars.size} bars",
                bars.size in (ConsumptionWindow.TARGET_BARS / 2)..ConsumptionWindow.TARGET_BARS,
            )
        }
    }

    @Test
    fun theThreeWindowsFoldByTheFactorsTheyShould() {
        assertEquals(1, ConsumptionWindow.SHORT.perBar)
        assertEquals(4, ConsumptionWindow.MEDIUM.perBar)
        assertEquals(10, ConsumptionWindow.LONG.perBar)
        assertEquals(0.1, ConsumptionWindow.SHORT.barKm, 1e-9)
        assertEquals(1.0, ConsumptionWindow.LONG.barKm, 1e-9)
    }

    @Test
    fun aFoldedBarIsTheMeanOfItsGroup() {
        // Ten identical buckets of 20 fold to one bar of 20, whatever the window.
        val bars = ConsumptionWindow.LONG.fold(List(10) { 20.0 })
        assertEquals(20.0, bars.single(), 1e-9)
        // And a group that is not identical averages rather than picking one.
        val mixed = ConsumptionWindow.LONG.fold(List(5) { 10.0 } + List(5) { 30.0 })
        assertEquals(20.0, mixed.single(), 1e-9)
    }

    @Test
    fun groupingRunsFromTheNewestEndSoTheNewestBarIsNeverTheRaggedOne() {
        // Twelve buckets at ten to a bar: the newest ten make one whole bar and
        // the two oldest make the short one, not the other way round.
        val bars = ConsumptionWindow.LONG.fold(ramp(12))
        assertEquals(2, bars.size)
        assertEquals((0.0 + 1.0) / 2, bars.first(), 1e-9)
        assertEquals((2..11).sumOf { it.toDouble() } / 10, bars.last(), 1e-9)
    }

    @Test
    fun aWindowOnlyEverLooksAtItsOwnTail() {
        val all = ramp(300)
        val short = ConsumptionWindow.SHORT.raw(all)
        assertEquals(30, short.size)
        assertEquals(299.0, short.last(), 1e-9)
        assertEquals(270.0, short.first(), 1e-9)
    }

    @Test
    fun aPartlyFilledWindowSaysHowMuchRoadItActuallyHas() {
        assertEquals(1.5, ConsumptionWindow.LONG.coveredKm(ramp(15)), 1e-9)
        assertEquals(3.0, ConsumptionWindow.SHORT.coveredKm(ramp(300)), 1e-9)
        assertEquals(0.0, ConsumptionWindow.SHORT.coveredKm(emptyList()), 1e-9)
    }

    @Test
    fun anEmptyLogFoldsToAnEmptyChartRatherThanToOneEmptyBar() {
        ConsumptionWindow.entries.forEach { assertTrue(it.fold(emptyList()).isEmpty()) }
    }

    @Test
    fun theCycleVisitsEveryWindowAndComesBack() {
        var window = ConsumptionWindow.DEFAULT
        val seen = mutableListOf(window)
        repeat(ConsumptionWindow.entries.size - 1) {
            window = window.next
            seen.add(window)
        }
        assertEquals(ConsumptionWindow.entries.toSet(), seen.toSet())
        assertEquals(ConsumptionWindow.DEFAULT, window.next)
    }

    @Test
    fun anUnknownSettingFallsBackRatherThanThrowing() {
        assertEquals(ConsumptionWindow.LONG, ConsumptionWindow.byName("LONG"))
        assertEquals(ConsumptionWindow.DEFAULT, ConsumptionWindow.byName(null))
        assertEquals(ConsumptionWindow.DEFAULT, ConsumptionWindow.byName("HUGE"))
    }
}
