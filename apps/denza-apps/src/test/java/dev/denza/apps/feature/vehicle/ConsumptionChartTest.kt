package dev.denza.apps.feature.vehicle

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hundred points both screens draw, and the three things they refuse to invent.
 *
 * `docs/energy-display-contract.md` §2.3. The expected values here are computed in the test from the
 * raw buckets - the readings picked out by hand, the trailing ten of them summed, `Σ kWh / Σ knownKm
 * × 100` - rather than through the production helper, which is what makes a sign flip, a smoothing
 * window of the wrong length or an off-by-one in the anchoring fail instead of agreeing with itself.
 */
class ConsumptionChartTest {

    private fun bucket(
        odometerKm: Double,
        kwh: Double,
        km: Double = 0.1,
        knownKm: Double = km,
    ) = ConsumptionSample(odometerKm, kwh, km, knownKm)

    /** [n] hundred-metre reading buckets ending at [toKm], each spending [kwh]. */
    private fun road(n: Int, kwh: Double = 0.02, toKm: Double = 110.0) =
        List(n) { bucket(toKm - (n - 1 - it) * 0.1, kwh) }

    /** A bucket of the same road the log knows no energy over: not a reading, and not a point. */
    private fun blind(odometerKm: Double, km: Double = 0.1) = bucket(odometerKm, 0.0, km, knownKm = 0.0)

    /**
     * The independent arithmetic: what the [ConsumptionChart.SMOOTH_STEPS] readings ending at the
     * [at]-th of them cost, over the road that energy is known for.
     *
     * Written without touching [ConsumptionChart]. The readings are picked out here by the same
     * question the log's own record answers - is half of this bucket's road known - and nothing else
     * about a bucket matters: the buckets that are not readings are not in this sum and are not on
     * the axis, so the readings either side of one are neighbours.
     */
    private fun trailingMean(buckets: List<ConsumptionSample>, at: Int): Double {
        val readings = buckets.filter { it.knownKm > 0.0 && it.knownKm * 2.0 >= it.km - 1e-9 }
        var from = at - ConsumptionChart.SMOOTH_STEPS + 1
        if (from < 0) from = 0
        var to = at
        // Never over fewer than half a kilometre: the first points of a fresh log reach forward
        // into it rather than each being the spike of its own hundred metres.
        if (to - from + 1 < ConsumptionChart.MIN_STEPS) to = from + ConsumptionChart.MIN_STEPS - 1
        val over = readings.subList(from, to + 1)
        return over.sumOf { it.kwh } / over.sumOf { it.knownKm } * 100.0
    }

    /** Every point of a chart drawn from [buckets], against the arithmetic above. */
    private fun assertMatchesTrailingMeans(buckets: List<ConsumptionSample>) {
        val chart = ConsumptionChart.of(buckets)
        val readings = buckets.count { it.knownKm > 0.0 && it.knownKm * 2.0 >= it.km - 1e-9 }
        val first = maxOf(0, readings - ConsumptionChart.POINTS)
        assertEquals("a point per reading, newest hundred", readings - first, chart.values.size)
        chart.values.forEachIndexed { index, value ->
            assertFalse("point $index is a NaN", value.isNaN())
            assertEquals("point $index", trailingMean(buckets, first + index), value.toDouble(), 1e-4)
        }
    }

    @Test
    fun aHundredPointsOfARecordedHundredMetres() {
        assertEquals(
            "a point is the log's own bucket",
            ConsumptionLog.DEFAULT_BUCKET_KM,
            ConsumptionChart.PITCH_KM,
            1e-12,
        )
        assertEquals(0.1, ConsumptionChart.PITCH_KM, 1e-12)
        assertEquals(100, ConsumptionChart.POINTS)
        assertEquals(
            "derived from the window rather than written twice",
            (ConsumptionWindow.KM / ConsumptionChart.PITCH_KM).toInt(),
            ConsumptionChart.POINTS,
        )
        assertEquals("and every point is the last kilometre of it", 10, ConsumptionChart.SMOOTH_STEPS)
        assertEquals(1.0, ConsumptionChart.SMOOTH_KM, 1e-12)
        assertEquals("taken over half a kilometre at the least", 5, ConsumptionChart.MIN_STEPS)
    }

    /**
     * A point is the mean of the ten readings ending at it, and the chart is as wide as the road.
     *
     * Thirty hundred-metre readings are thirty points and three kilometres of road: the width of
     * the chart is the width of the record, which is what «за 3,0 км» beside it promises.
     */
    @Test
    fun aPointIsTheTenReadingsEndingAtItOverTheRoadTheyKnow() {
        val buckets = (1..30).map { bucket(100.0 + it * 0.1, 0.002 * it) }
        val chart = ConsumptionChart.of(buckets)
        assertEquals("three kilometres of road", 30, chart.values.size)
        assertEquals(
            "which is what the unit beside it names",
            ConsumptionWindow.coveredKm(buckets),
            chart.span * ConsumptionChart.PITCH_KM,
            1e-9,
        )
        assertMatchesTrailingMeans(buckets)
        // And spelled out once, so the arithmetic is on the page rather than only in a loop.
        val lastTen = buckets.takeLast(10)
        assertEquals(
            lastTen.sumOf { it.kwh } / lastTen.sumOf { it.knownKm } * 100.0,
            chart.values.last().toDouble(),
            1e-4,
        )
    }

    /**
     * A bucket that is not a reading is skipped, and the readings either side of it are neighbours.
     *
     * The board before this one drew that stretch as `NaN` points on the odometer's grid and broke
     * the line over it; the first thing it drew on the car was the 4.7 km the hub had slept through.
     * Road nobody recorded is not on this axis at all.
     */
    @Test
    fun aBucketThatIsNotAReadingIsSkippedRatherThanDrawnAsAHole() {
        val before = (1..10).map { bucket(100.0 + it * 0.1, 0.02) }
        val blind = (1..7).map { blind(101.0 + it * 0.1) }
        val after = (1..10).map { bucket(101.7 + it * 0.1, 0.05) }
        val chart = ConsumptionChart.of(before + blind + after)
        assertEquals("twenty readings are twenty points", 20, chart.values.size)
        assertTrue("and none of them is a hole", chart.values.none { it.isNaN() })
        assertMatchesTrailingMeans(before + blind + after)
        // The seam is between points 9 and 10, and they are neighbours: the tenth point is the ten
        // readings before the gap, the eleventh is nine of them and the first after it.
        assertEquals(0.2 / 1.0 * 100.0, chart.values[9].toDouble(), 1e-4)
        assertEquals((0.18 + 0.05) / 1.0 * 100.0, chart.values[10].toDouble(), 1e-4)
    }

    /**
     * And a seam in the odometer is invisible for the same reason.
     *
     * Two kilometres of road the car covered with nothing watching leave a re-anchor and no record.
     * The points either side of it are neighbours, because the axis is what was recorded.
     */
    @Test
    fun aSeamInTheOdometerIsInvisible() {
        val here = (1..10).map { bucket(100.0 + it * 0.1, 0.02) }
        val thereAfterASeam = (1..10).map { bucket(140.0 + it * 0.1, 0.02) }
        val seamless = (1..20).map { bucket(100.0 + it * 0.1, 0.02) }
        val across = ConsumptionChart.of(here + thereAfterASeam)
        val straight = ConsumptionChart.of(seamless)
        assertEquals("twenty readings either way", straight.values.size, across.values.size)
        for (index in across.values.indices) {
            assertEquals("point $index", straight.values[index], across.values[index], 1e-6f)
        }
    }

    /** A bucket carrying a pause is one record of road with no energy, and no point at all. */
    @Test
    fun aBucketCarryingAPauseIsNotAPoint() {
        val pause = blind(102.0, km = 2.0)
        assertTrue(ConsumptionChart.of(listOf(pause)).isEmpty)
        val buckets = (1..10).map { bucket(102.0 + it * 0.1, 0.02) }
        assertEquals(
            "ten readings, and the two kilometres in front of them are not eleven points",
            10,
            ConsumptionChart.of(listOf(pause) + buckets).values.size,
        )
    }

    /** The chart is the newest hundred points and nothing older. */
    @Test
    fun theChartIsTheNewestHundredPointsAndNothingOlder() {
        val buckets = road(300, toKm = 130.0)
        val chart = ConsumptionChart.of(buckets)
        assertEquals(ConsumptionChart.POINTS, chart.values.size)
        assertEquals(ConsumptionChart.POINTS, chart.span)
        assertMatchesTrailingMeans(buckets)
    }

    /**
     * The oldest drawn point is still a whole kilometre, because the chart reads the retention.
     *
     * The hub hands this object the log's own buckets rather than the window: cut the kilometre
     * before the window off and the oldest point becomes the mean of a hundred metres.
     */
    @Test
    fun theOldestPointIsBackedByReadingsTheWindowNoLongerReaches() {
        // Eleven kilometres, and the oldest one of them costs four times what the rest do.
        val all = (1..110).map { bucket(100.0 + it * 0.1, if (it <= 10) 0.08 else 0.02) }
        val full = ConsumptionChart.of(all)
        assertEquals(ConsumptionChart.POINTS, full.values.size)
        assertEquals(
            "the oldest point is the readings 2…11",
            trailingMean(all, 10),
            full.values[0].toDouble(),
            1e-4,
        )
        assertMatchesTrailingMeans(all)

        val starved = ConsumptionChart.of(all.drop(10))
        assertEquals("the same hundred points either way", ConsumptionChart.POINTS, starved.values.size)
        assertEquals(
            "but the oldest of them is now the log's own first half kilometre",
            trailingMean(all.drop(10), 0),
            starved.values[0].toDouble(),
            1e-4,
        )
        assertTrue(
            "which is a different figure",
            abs(starved.values[0] - full.values[0]) > 1f,
        )
    }

    /**
     * A fresh log draws nothing until the fifth reading, and never a mean of one bucket.
     *
     * Four readings are four hundred metres, and a point over one of them is the comb this chart
     * exists to be rid of drawn as a dot. From the fifth on the run is as wide as the record.
     */
    @Test
    fun nothingIsDrawnFromFewerThanFiveReadings() {
        for (n in 0 until ConsumptionChart.MIN_STEPS) {
            assertTrue("$n readings draw nothing", ConsumptionChart.of(road(n, toKm = 100.0 + n * 0.1)).isEmpty)
        }
        val five = road(5, toKm = 100.5)
        val chart = ConsumptionChart.of(five)
        assertEquals("five readings are five points", 5, chart.values.size)
        assertTrue("and every one of them is the same half kilometre", chart.values.all { it == chart.values[0] })
        assertEquals(0.1 / 0.5 * 100.0, chart.values[0].toDouble(), 1e-4)
        assertMatchesTrailingMeans(five)
    }

    /** And the run grows a point per reading, right-anchored where new road arrives. */
    @Test
    fun theRunIsRightAnchoredAndAsWideAsTheRecord() {
        for (n in ConsumptionChart.MIN_STEPS..37) {
            val buckets = road(n, toKm = 100.0 + n * 0.1)
            val chart = ConsumptionChart.of(buckets)
            assertEquals("$n readings", n, chart.span)
            assertEquals(
                "and the road under them is what the unit names",
                ConsumptionWindow.coveredKm(buckets),
                chart.span * ConsumptionChart.PITCH_KM,
                1e-9,
            )
        }
        // Thirty-seven readings are «за 3,7 км», which is the board's own filling scene.
        assertEquals(3.7, ConsumptionChart.of(road(37, toKm = 103.7)).span * ConsumptionChart.PITCH_KM, 1e-9)
    }

    /**
     * A new bucket appends one point and shifts the rest; the settled ones do not move.
     *
     * A point's value is decided by the readings that closed at or before it, so a bucket closing
     * now cannot change what a point already on the screen says. Grouping the tail from the oldest
     * re-phased every point every hundred metres, and the same road never came back the same shape.
     */
    @Test
    fun aNewBucketAppendsOnePointAndTheSettledOnesKeepTheirValues() {
        val road = (1..40).map { bucket(100.0 + it * 0.1, 0.002 * it) }
        val before = ConsumptionChart.of(road.dropLast(1))
        val after = ConsumptionChart.of(road)
        assertEquals(39, before.values.size)
        assertEquals("one more point", 40, after.values.size)
        for (index in 0 until 39) {
            assertEquals("the point at reading $index", before.values[index], after.values[index], 1e-6f)
        }
    }

    /** And past the hundredth point the run stops growing and starts shifting. */
    @Test
    fun pastAHundredPointsTheRunShiftsInstead() {
        val road = (1..130).map { bucket(100.0 + it * 0.1, 0.002 * (it % 17)) }
        val before = ConsumptionChart.of(road.dropLast(1))
        val after = ConsumptionChart.of(road)
        assertEquals(ConsumptionChart.POINTS, before.values.size)
        assertEquals(ConsumptionChart.POINTS, after.values.size)
        for (index in 1 until ConsumptionChart.POINTS) {
            assertEquals(
                "point $index of the new run is point ${index - 1} of the old",
                before.values[index],
                after.values[index - 1],
                1e-6f,
            )
        }
    }

    @Test
    fun aReturningKilometreKeepsItsSign() {
        val buckets = (1..10).map { bucket(100.0 + it * 0.1, -0.01) }
        val chart = ConsumptionChart.of(buckets)
        assertTrue("energy came back", chart.values.last() < 0f)
        assertEquals(-0.1 / 1.0 * 100.0, chart.values.last().toDouble(), 1e-4)
    }

    /** The snapshot is values and a span: no widths, no holes, no `NaN`. */
    @Test
    fun theSnapshotCarriesPointsAndNothingElse() {
        val chart = ConsumptionChart.of(road(45, toKm = 104.5))
        assertEquals(chart.values.size, chart.span)
        assertEquals(45, chart.span)
        assertTrue("never a NaN", chart.values.none { it.isNaN() })
        assertTrue(
            "no field of the snapshot is a width",
            ConsumptionChartSnapshot::class.java.declaredFields.none {
                it.name.contains("width", ignoreCase = true)
            },
        )
    }

    @Test
    fun nothingClosedIsNoChart() {
        assertTrue(ConsumptionChart.of(emptyList()).isEmpty)
        assertTrue("and nothing recorded is no chart either", ConsumptionChart.of(List(9) { blind(100.0 + it * 0.1) }).isEmpty)
        assertTrue(ConsumptionChartSnapshot.EMPTY.isEmpty)
        assertEquals(0, ConsumptionChartSnapshot.EMPTY.span)
    }

    /**
     * And the shape a kilometre makes of the owner's own road is the shape the boards draw.
     *
     * A hundred 100 m readings whose trailing kilometres are the car's journal of 2026-09-18: the
     * chart's biggest neighbour-to-neighbour jump is a curve rather than a comb, which is the whole
     * argument for a kilometre over five hundred metres (contract §2.3).
     */
    @Test
    fun aTrailingKilometreIsACurveRatherThanAComb() {
        // A launch and a descent inside an ordinary town run, at the log's own resolution.
        val perBucket = DoubleArray(110) { index ->
            when {
                index in 60..68 -> -60.0
                index in 70..78 -> 130.0
                else -> 20.0 + (index % 7) * 6.0
            }
        }
        val buckets = perBucket.mapIndexed { index, value ->
            bucket(100.0 + (index + 1) * 0.1, value * 0.1 / 100.0)
        }
        val chart = ConsumptionChart.of(buckets)
        assertEquals(ConsumptionChart.POINTS, chart.values.size)
        var worst = 0.0
        for (index in 1 until chart.values.size) {
            worst = maxOf(worst, abs(chart.values[index] - chart.values[index - 1]).toDouble())
        }
        // One 100 m bucket swings 190 between neighbours here; the trailing kilometre carries a
        // tenth of that into the drawing.
        assertTrue("neighbouring points jump by $worst", worst <= 20.0)
        assertMatchesTrailingMeans(buckets)
    }
}
