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

    /**
     * A bucket belongs to the bins its **road** is in, not to the one its odometer stopped on.
     *
     * Five hundred-metre buckets closing at 100.1…100.5 cover `[100.0, 100.5)`, which is one bin
     * exactly. Filing each by its own close put four of them in that bin and opened a second one on
     * its far edge for the fifth - every bin one bucket out of phase with the road under it.
     */
    @Test
    fun aBinIsEnergyOverKnownRoadOverTheRoadItCovers() {
        val buckets = listOf(
            bucket(100.1, 0.010),
            bucket(100.2, 0.020),
            bucket(100.3, 0.030),
            bucket(100.4, 0.010),
            bucket(100.5, 0.020),
        )
        val chart = ConsumptionChart.of(buckets)
        assertEquals(1, chart.values.size)
        assertEquals((0.010 + 0.020 + 0.030 + 0.010 + 0.020) / 0.5 * 100.0, chart.values[0].toDouble(), 1e-5)
        assertEquals("a full bin", 1f, chart.widths[0], 1e-6f)
    }

    /** And the road a hundred metres past that edge opens the next bin, and only that. */
    @Test
    fun theBucketAfterAnEdgeIsTheFirstOfTheNextBin() {
        val chart = ConsumptionChart.of(listOf(bucket(10.5, 0.02), bucket(10.6, 0.03)))
        assertEquals(2, chart.values.size)
        assertEquals("10.4→10.5 is bin 20", 20L, ConsumptionChart.binOf(10.4))
        assertEquals("and 10.5→10.6 is bin 21", 21L, ConsumptionChart.binOf(10.5))
        assertEquals(0.02 / 0.1 * 100.0, chart.values[0].toDouble(), 1e-5)
        assertEquals(0.03 / 0.1 * 100.0, chart.values[1].toDouble(), 1e-5)
    }

    @Test
    fun theNewestBinIsAsWideAsTheRoadItHas() {
        val chart = ConsumptionChart.of(listOf(bucket(100.1, 0.02), bucket(100.2, 0.02)))
        assertEquals(1, chart.values.size)
        assertEquals("two of five buckets", 0.4f, chart.widths[0], 1e-6f)
        assertEquals(0.4f, chart.span, 1e-6f)

        val full = ConsumptionChart.of(road(5, toKm = 100.5))
        // 100.0…100.5 is one bin, and the five buckets fill it.
        assertEquals(1, full.values.size)
        assertEquals(1f, full.widths[0], 1e-6f)

        val over = ConsumptionChart.of(road(6, toKm = 100.6))
        assertEquals(2, over.values.size)
        assertEquals(1f, over.widths[0], 1e-6f)
        assertEquals(0.2f, over.widths[1], 1e-6f)
    }

    /**
     * A bucket that carries a pause keeps its whole road on the axis.
     *
     * Two kilometres closed inside one record - the panel was away, the odometer moved - is four
     * bins of road with no energy behind it. Filed by its close it was one bin, and 1.9 km of road
     * simply left the chart: the shape stepped over a gap it should have drawn as a gap.
     */
    @Test
    fun aBucketCarryingAPauseIsAsManyHoleBinsAsItHasRoad() {
        val chart = ConsumptionChart.of(listOf(bucket(102.0, 0.0, km = 2.0, knownKm = 0.0)))
        assertEquals("four half kilometres", 4, chart.values.size)
        chart.values.forEach { assertTrue("nothing is known here", it.isNaN()) }
        chart.widths.forEach { assertEquals(1f, it, 1e-6f) }
        assertEquals(4f, chart.span, 1e-6f)
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
    fun aStepLongerThanOneBucketIsSpreadOverTheRoadItCovers() {
        // 0.3 km closed inside one sample, all of it inside one bin: `[100.0, 100.3)`.
        val chart = ConsumptionChart.of(listOf(bucket(100.3, 0.06, km = 0.3)))
        assertEquals(1, chart.values.size)
        assertEquals(0.06 / 0.3 * 100.0, chart.values[0].toDouble(), 1e-5)
        assertEquals(0.6f, chart.widths[0], 1e-6f)

        // And across an edge it goes to both, energy and known road pro rata by road: `[100.4,
        // 100.7)` is 0.1 in bin 200 and 0.2 in bin 201, at one rate.
        val across = ConsumptionChart.of(listOf(bucket(100.7, 0.06, km = 0.3)))
        assertEquals(2, across.values.size)
        assertEquals(0.2f, across.widths[0], 1e-6f)
        assertEquals(0.4f, across.widths[1], 1e-6f)
        assertEquals(0.06 / 0.3 * 100.0, across.values[0].toDouble(), 1e-5)
        assertEquals(0.06 / 0.3 * 100.0, across.values[1].toDouble(), 1e-5)

        // Pro rata is only visible where the bins are mixtures: the 0.4 km of free-wheeling in
        // bin 200 takes a third of that bucket's energy, not all of it.
        val mixed = ConsumptionChart.of(
            listOf(bucket(100.4, 0.0, km = 0.4), bucket(100.7, 0.06, km = 0.3)),
        )
        assertEquals(2, mixed.values.size)
        assertEquals("a third of 0.06 kWh over half a kilometre", 4.0, mixed.values[0].toDouble(), 1e-5)
        assertEquals(0.04 / 0.2 * 100.0, mixed.values[1].toDouble(), 1e-5)
    }

    @Test
    fun aBinNeverDrawsWiderThanItself() {
        // A whole kilometre in one record - a re-anchor's own step - covers two bins and neither
        // of them is drawn wider than a bin.
        val chart = ConsumptionChart.of(listOf(bucket(100.9, 0.2, km = 0.9)))
        chart.widths.forEach { assertTrue("a bin is at most itself: $it", it <= 1f) }
        assertEquals(1f, chart.widths[0], 1e-6f)
        assertEquals(0.8f, chart.widths[1], 1e-6f)
    }

    @Test
    fun theChartIsTheNewestTwentyBinsAndNothingOlder() {
        // Fifteen kilometres of buckets: only the newest ten kilometres of anchored bins are drawn,
        // and the oldest of those holds only the road that reaches into it.
        val chart = ConsumptionChart.of(road(149, toKm = 114.9))
        assertEquals(ConsumptionChart.BINS, chart.values.size)
        // Bins 210…229 span [105.0, 115.0) and the road ends at 114.9.
        assertEquals(19.8f, chart.span, 1e-4f)
        assertEquals("the newest is the road it has", 0.8f, chart.widths[ConsumptionChart.BINS - 1], 1e-4f)
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
