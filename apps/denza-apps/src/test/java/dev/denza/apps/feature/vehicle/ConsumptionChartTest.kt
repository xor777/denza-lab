package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The twenty bins both screens draw, and the two things they refuse to invent.
 *
 * The expected values here are computed in the test from the raw buckets - `Σ kWh / Σ knownKm × 100`
 * - rather than through the production helper, which is what makes a sign flip or an off-by-one in
 * the anchoring fail instead of agreeing with itself.
 */
class ConsumptionChartTest {

    private fun bucket(
        odometerKm: Double,
        kwh: Double,
        km: Double = 0.1,
        knownKm: Double = km,
    ) = ConsumptionSample(odometerKm, kwh, km, knownKm)

    /** [n] hundred-metre buckets ending at [toKm], each spending [kwh]. */
    private fun road(n: Int, kwh: Double = 0.02, toKm: Double = 110.0) =
        List(n) { bucket(toKm - (n - 1 - it) * 0.1, kwh) }

    @Test
    fun twentyBinsOfFiveHundredMetresAreTheWindowOverTheBin() {
        assertEquals(0.5, ConsumptionChart.BIN_KM, 1e-9)
        assertEquals(20, ConsumptionChart.BINS)
        assertEquals(
            "derived from the window rather than written twice",
            (ConsumptionWindow.KM / ConsumptionChart.BIN_KM).toInt(),
            ConsumptionChart.BINS,
        )
    }

    @Test
    fun aBinIsEnergyOverKnownRoadOverItsOwnFiveBuckets() {
        // Five buckets whose odometers all floor into one half kilometre.
        val buckets = listOf(
            bucket(100.1, 0.010),
            bucket(100.2, 0.020),
            bucket(100.3, 0.030),
            bucket(100.4, 0.010),
            bucket(100.5, 0.020),
        )
        val chart = ConsumptionChart.of(buckets)
        // 100.1…100.4 floor to bin 200 (100.0…100.5); 100.5 opens bin 201.
        assertEquals(2, chart.values.size)
        assertEquals((0.010 + 0.020 + 0.030 + 0.010) / 0.4 * 100.0, chart.values[0].toDouble(), 1e-5)
        assertEquals(0.020 / 0.1 * 100.0, chart.values[1].toDouble(), 1e-5)
    }

    @Test
    fun theNewestBinIsAsWideAsTheRoadItHas() {
        val chart = ConsumptionChart.of(listOf(bucket(100.1, 0.02), bucket(100.2, 0.02)))
        assertEquals(1, chart.values.size)
        assertEquals("two of five buckets", 0.4f, chart.widths[0], 1e-6f)
        assertEquals(0.4f, chart.span, 1e-6f)

        val full = ConsumptionChart.of(road(5, toKm = 100.5))
        // 100.1…100.4 is four buckets of the first bin, 100.5 is the first of the next.
        assertEquals(2, full.values.size)
        assertEquals(0.8f, full.widths[0], 1e-6f)
        assertEquals(0.2f, full.widths[1], 1e-6f)
    }

    @Test
    fun aBinMostlyWithoutKnownEnergyIsAHoleAndItsRoadStillCounts() {
        val buckets = listOf(
            bucket(100.1, 0.02),
            bucket(100.2, 0.0, knownKm = 0.0),
            bucket(100.3, 0.0, knownKm = 0.0),
            bucket(100.4, 0.0, knownKm = 0.0),
        )
        val chart = ConsumptionChart.of(buckets)
        assertEquals(1, chart.values.size)
        assertTrue("a hole", chart.values[0].isNaN())
        assertEquals("and its road is drawn as width all the same", 0.8f, chart.widths[0], 1e-6f)
    }

    @Test
    fun exactlyHalfAKnownBinIsStillAReading() {
        val buckets = listOf(
            bucket(100.1, 0.02),
            bucket(100.2, 0.02),
            bucket(100.3, 0.0, knownKm = 0.0),
            bucket(100.4, 0.0, knownKm = 0.0),
        )
        val chart = ConsumptionChart.of(buckets)
        assertFalse(chart.values[0].isNaN())
        assertEquals(0.04 / 0.2 * 100.0, chart.values[0].toDouble(), 1e-5)
    }

    @Test
    fun binsAreAnchoredToTheOdometerAndDoNotRephaseWhenABucketCloses() {
        // The whole reason the bins are anchored: a closed bin's value must not change because a
        // *different* bucket arrived. Grouping the tail by fives re-phases every hundred metres.
        val before = ConsumptionChart.of(road(12, toKm = 101.2))
        val after = ConsumptionChart.of(road(13, toKm = 101.3))
        // The oldest complete bin is the same bin in both, at the same value.
        val settled = before.values[1]
        assertEquals(settled, after.values[1], 1e-6f)
        assertEquals(1f, before.widths[1], 1e-6f)
        assertEquals(1f, after.widths[1], 1e-6f)
    }

    @Test
    fun aStepLongerThanOneBucketLandsInTheBinItsOdometerNames() {
        // 0.3 km closed inside one sample, recorded where the odometer stood at the close.
        val chart = ConsumptionChart.of(listOf(bucket(100.3, 0.06, km = 0.3)))
        assertEquals(1, chart.values.size)
        assertEquals(0.06 / 0.3 * 100.0, chart.values[0].toDouble(), 1e-5)
        assertEquals(0.6f, chart.widths[0], 1e-6f)
    }

    @Test
    fun aBinNeverDrawsWiderThanItself() {
        // A whole kilometre in one record - a re-anchor's own step - is still one bin wide.
        val chart = ConsumptionChart.of(listOf(bucket(100.9, 0.2, km = 0.9)))
        assertEquals(1f, chart.widths[0], 1e-6f)
    }

    @Test
    fun theChartIsTheNewestTwentyBinsAndNothingOlder() {
        // Fifteen kilometres of buckets: only the newest ten kilometres of anchored bins are drawn.
        val chart = ConsumptionChart.of(road(149, toKm = 114.9))
        assertEquals(ConsumptionChart.BINS, chart.values.size)
        assertEquals(ConsumptionChart.BINS.toFloat(), chart.span, 1e-4f)
    }

    @Test
    fun nothingClosedIsNoChart() {
        assertTrue(ConsumptionChart.of(emptyList()).isEmpty)
        assertTrue(ConsumptionChartSnapshot.EMPTY.isEmpty)
    }

    @Test
    fun aReturningBinKeepsItsSign() {
        val chart = ConsumptionChart.of(List(5) { bucket(100.1 + it * 0.1, -0.01) })
        assertTrue("energy came back", chart.values[0] < 0f)
        assertEquals(-0.05 / 0.5 * 100.0, chart.values[0].toDouble(), 1e-5)
    }
}
