package dev.denza.apps.feature.vehicle

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hundred points both screens draw, and the three things they refuse to invent.
 *
 * `docs/energy-display-contract.md` §2.3. The expected values here are computed in the test from
 * the raw buckets - the overlap of each bucket's road with the kilometre ending at the point,
 * energy pro rata, `Σ kWh / Σ knownKm × 100` - rather than through the production helper, which is
 * what makes a sign flip, a smoothing window of the wrong length or an off-by-one in the anchoring
 * fail instead of agreeing with itself.
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

    /**
     * The independent arithmetic: what the kilometre ending at [endKm] cost, over the road of it
     * the energy is known for.
     *
     * Written without touching [ConsumptionChart] or the grid it walks. A bucket's share of the
     * kilometre is the overlap of its road with it, and its energy and known road go with that
     * share, which is the only division a bucket supports.
     */
    private fun trailingMean(buckets: List<ConsumptionSample>, endKm: Double): Double {
        // A grid step no bucket covers at all is a hole whatever the kilometre behind it holds:
        // the line breaks over road nobody recorded rather than running straight through it.
        if (roadIn(buckets, endKm - ConsumptionChart.PITCH_KM, endKm) <= 0.0) return Double.NaN
        val from = endKm - ConsumptionChart.SMOOTH_KM
        var kwh = 0.0
        var km = 0.0
        var known = 0.0
        buckets.forEach { sample ->
            if (sample.km <= 0.0) return@forEach
            val overlap = minOf(sample.odometerKm, endKm) - maxOf(sample.odometerKm - sample.km, from)
            if (overlap <= 0.0) return@forEach
            val share = overlap / sample.km
            kwh += sample.kwh * share
            km += overlap
            known += sample.knownKm * share
        }
        // Half of the kilometre, not half of the road recorded in it.
        if (known <= 0.0 || known * 2.0 < ConsumptionChart.SMOOTH_KM - 1e-9) return Double.NaN
        return kwh / known * 100.0
    }

    /** How much of `[from, to)` any bucket covers. */
    private fun roadIn(buckets: List<ConsumptionSample>, from: Double, to: Double): Double {
        var road = 0.0
        buckets.forEach { sample ->
            if (sample.km <= 0.0) return@forEach
            val overlap = minOf(sample.odometerKm, to) - maxOf(sample.odometerKm - sample.km, from)
            if (overlap > 1e-9) road += overlap
        }
        return road
    }

    /** Where the point at [index] of a chart drawn from [buckets] stands on the odometer. */
    private fun endOf(buckets: List<ConsumptionSample>, index: Int, count: Int): Double {
        val newest = buckets.last().odometerKm
        return newest - (count - 1 - index) * ConsumptionChart.PITCH_KM
    }

    private fun assertMatchesTrailingMeans(buckets: List<ConsumptionSample>) {
        val chart = ConsumptionChart.of(buckets)
        val count = chart.values.size
        for (index in 0 until count) {
            val expected = trailingMean(buckets, endOf(buckets, index, count))
            val actual = chart.values[index].toDouble()
            if (expected.isNaN()) {
                assertTrue("point $index should be a hole, was $actual", actual.isNaN())
            } else {
                assertFalse("point $index should be a reading", actual.isNaN())
                assertEquals("point $index", expected, actual, 1e-4)
            }
        }
    }

    @Test
    fun aHundredPointsOnTheOdometersOwnHundredMetres() {
        assertEquals(
            "the grid is the log's own bucket",
            ConsumptionLog.DEFAULT_BUCKET_KM,
            ConsumptionChart.PITCH_KM,
            1e-12,
        )
        assertEquals(0.1, ConsumptionChart.PITCH_KM, 1e-12)
        assertEquals("and every point is the last kilometre", 1.0, ConsumptionChart.SMOOTH_KM, 1e-12)
        assertEquals(100, ConsumptionChart.POINTS)
        assertEquals(
            "derived from the window rather than written twice",
            (ConsumptionWindow.KM / ConsumptionChart.PITCH_KM).toInt(),
            ConsumptionChart.POINTS,
        )
        assertEquals(10, ConsumptionChart.SMOOTH_STEPS)
        assertEquals(
            "the tail is the window plus the kilometre behind it",
            ConsumptionWindow.KM + ConsumptionChart.SMOOTH_KM,
            ConsumptionChart.TAIL_KM,
            1e-12,
        )
    }

    /**
     * A point is the mean of the kilometre ending at it, and the road is filed where it lies.
     *
     * Five hundred-metre buckets closing at 100.1…100.5 cover `[100.0, 100.5)`. Filing each by its
     * own close rather than by the road it covers put every point one bucket out of phase with the
     * road under it, which is how the second board lost 1.9 km off its axis.
     */
    @Test
    fun aPointIsTheKilometreEndingAtItOverTheRoadItKnows() {
        val buckets = (1..30).map { bucket(100.0 + it * 0.1, 0.002 * it) }
        val chart = ConsumptionChart.of(buckets)
        assertEquals("three kilometres of road", 30, chart.values.size)
        assertMatchesTrailingMeans(buckets)
        // And spelled out once, so the arithmetic is on the page rather than only in a loop: the
        // newest point ends at 103.0, so it is the twenty-first to thirtieth buckets.
        val lastTen = buckets.takeLast(10)
        assertEquals(
            lastTen.sumOf { it.kwh } / lastTen.sumOf { it.knownKm } * 100.0,
            chart.values.last().toDouble(),
            1e-4,
        )
    }

    /**
     * The newest point is the step the newest bucket's last metre is in, not the one it opens.
     *
     * A bucket closing exactly on a step's edge covers the road *behind* that edge. Opening a step
     * in front of it right-anchored the whole run against a hundred metres nothing had driven.
     */
    @Test
    fun theNewestPointIsTheStepTheNewestBucketEndsIn() {
        assertEquals("100.4→100.5 is step 1004", 1004L, ConsumptionChart.stepOf(100.45))
        assertEquals("and 100.5 opens 1005", 1005L, ConsumptionChart.stepOf(100.5))
        // Half a kilometre of buckets, so the newest point is a reading and not a hole.
        val chart = ConsumptionChart.of(road(5, toKm = 100.5))
        assertEquals("five buckets are five points", 5, chart.values.size)
        assertEquals(0.02 / 0.1 * 100.0, chart.values.last().toDouble(), 1e-4)
    }

    /**
     * The first point of a full window is backed by road the window does not reach.
     *
     * Which is why the hub hands this object `ConsumptionLog.chartTail` and not `window`: cut the
     * kilometre before the window off and the oldest point becomes the mean of a hundred metres.
     */
    @Test
    fun theFirstPointNeedsTheKilometreBeforeTheWindow() {
        // Eleven kilometres, and the oldest one of them costs four times what the rest do.
        val tail = (1..110).map { bucket(100.0 + it * 0.1, if (it <= 10) 0.08 else 0.02) }
        val full = ConsumptionChart.of(tail)
        assertEquals(ConsumptionChart.POINTS, full.values.size)
        assertEquals(
            "the oldest point is the kilometre 100.1…101.1",
            trailingMean(tail, 101.1),
            full.values[0].toDouble(),
            1e-4,
        )
        assertMatchesTrailingMeans(tail)

        val starved = ConsumptionChart.of(tail.drop(10))
        assertEquals("the same hundred points either way", ConsumptionChart.POINTS, starved.values.size)
        assertNotEquals(
            "and without the road behind the window the oldest one is a different figure",
            full.values[0],
            starved.values[0],
        )
    }

    /** The window is ten kilometres of points whatever the tail holds behind it. */
    @Test
    fun theChartIsTheNewestHundredPointsAndNothingOlder() {
        val tail = road(300, toKm = 130.0)
        val chart = ConsumptionChart.of(tail)
        assertEquals(ConsumptionChart.POINTS, chart.values.size)
        assertEquals(ConsumptionChart.POINTS, chart.span)
        // The newest point is the kilometre ending at 130.0 and the oldest the one ending at 120.1.
        assertEquals(trailingMean(tail, 130.0), chart.values.last().toDouble(), 1e-4)
        assertEquals(trailingMean(tail, 120.1), chart.values[0].toDouble(), 1e-4)
    }

    /**
     * A kilometre with under half its road known is a hole, and its road stays on the axis.
     *
     * The line breaks there and resumes where the road is known again; the axis is the odometer's
     * grid, so nothing shifts left to close the gap.
     */
    @Test
    fun aHoleIsAPointAndTheAxisKeepsItsPlace() {
        val buckets = (1..30).map {
            // 101.0…101.7 answered for nothing at all, which is seven hundred metres of a
            // kilometre - so every point whose kilometre is mostly inside it is a hole.
            val at = 100.0 + it * 0.1
            if (at > 101.0 + 1e-9 && at <= 101.7 + 1e-9) {
                bucket(at, 0.0, knownKm = 0.0)
            } else {
                bucket(at, 0.02)
            }
        }
        val chart = ConsumptionChart.of(buckets)
        assertEquals("the axis is the road, hole or no hole", 30, chart.values.size)
        assertTrue("some of it is drawn", chart.values.any { !it.isNaN() })
        assertTrue("and some of it is not", chart.values.any { it.isNaN() })
        assertMatchesTrailingMeans(buckets)
    }

    /**
     * After a hole the line waits for half a kilometre of known road.
     *
     * The owner's photograph of 2026-09-18 had a bin one fifth wide beside a hole; a point over a
     * hundred metres of known road would be the same spike drawn as a dot. The rule is asked of
     * the kilometre, not of the road that happened to be recorded.
     */
    @Test
    fun theLineResumesHalfAKilometreAfterAHole() {
        val before = (1..10).map { bucket(100.0 + it * 0.1, 0.02) }
        val after = (1..10).map { bucket(102.0 + it * 0.1, 0.02) }
        val chart = ConsumptionChart.of(before + after)
        val known = (0 until chart.values.size).map { !chart.values[it].isNaN() }
        assertEquals(
            "holes over the gap and four hundred metres past it",
            (10 until 24).toList(),
            known.indices.filter { !known[it] && it >= 10 },
        )
        assertEquals(
            "the first reading after the gap is the mean of half a kilometre",
            0.1 / 0.5 * 100.0,
            chart.values[24].toDouble(),
            1e-4,
        )
    }

    /** And the boundary is exactly half, which is a reading. */
    @Test
    fun exactlyHalfAKnownKilometreIsStillAReading() {
        val buckets = (1..10).map {
            val at = 100.0 + it * 0.1
            if (it <= 5) bucket(at, 0.02) else bucket(at, 0.0, knownKm = 0.0)
        }
        val chart = ConsumptionChart.of(buckets)
        val newest = chart.values.last().toDouble()
        assertFalse("half the kilometre is known", newest.isNaN())
        assertEquals(0.1 / 0.5 * 100.0, newest, 1e-4)
        // One bucket less and it is a hole.
        val under = ConsumptionChart.of(
            buckets.mapIndexed { index, sample ->
                if (index == 4) bucket(sample.odometerKm, 0.0, knownKm = 0.0) else sample
            },
        )
        assertTrue(under.values.last().isNaN())
    }

    /**
     * A stretch no bucket covers at all is a stretch of holes, and never a shorter chart.
     *
     * The odometer re-anchored - the car was driven with nothing watching - so a kilometre of road
     * has no record. Drawing the points either side of it as neighbours would claim the line ran
     * straight through it.
     */
    @Test
    fun aStretchWithNoBucketsAtAllIsAStretchOfHoles() {
        val before = (1..10).map { bucket(100.0 + it * 0.1, 0.02) }
        val after = (1..10).map { bucket(102.0 + it * 0.1, 0.02) }
        val chart = ConsumptionChart.of(before + after)
        assertEquals("three kilometres of axis for two of road", 30, chart.values.size)
        // 101.0…102.0 is ten grid steps nothing covers.
        for (index in 10 until 20) {
            assertTrue("point $index stands on road nobody recorded", chart.values[index].isNaN())
        }
        // The road resumes at 102.1, and the line half a kilometre later: a point over a hundred
        // metres of known road is the spike the chart exists to be rid of.
        for (index in 20 until 24) assertTrue("point $index has under half a kilometre", chart.values[index].isNaN())
        assertFalse("and the line resumes at half a kilometre", chart.values[24].isNaN())
        assertFalse(chart.values[9].isNaN())
        assertMatchesTrailingMeans(before + after)
    }

    /**
     * A bucket that carries a pause keeps its whole road on the axis.
     *
     * Two kilometres closed inside one record - the panel was away, the odometer moved - is twenty
     * grid steps of road with no energy behind it, and the whole of it is drawn as a gap.
     */
    @Test
    fun aBucketCarryingAPauseIsAsManyHolePointsAsItHasRoad() {
        val chart = ConsumptionChart.of(listOf(bucket(102.0, 0.0, km = 2.0, knownKm = 0.0)))
        assertEquals("twenty hundred metres", 20, chart.values.size)
        chart.values.forEach { assertTrue("nothing is known here", it.isNaN()) }
        assertEquals(20, chart.span)
    }

    /**
     * The run is anchored at the right edge, where new road arrives.
     *
     * A log with three kilometres in it draws thirty points, not a hundred with seventy holes in
     * front: the grid steps before its first bucket are *absent*, which is a different statement
     * from "the road there is unknown".
     */
    @Test
    fun theRunIsRightAnchoredWhileTheLogIsShort() {
        val chart = ConsumptionChart.of(road(30, toKm = 103.0))
        assertEquals(30, chart.values.size)
        assertEquals(30, chart.span)
        // The first four points have under half a kilometre behind them and are holes; from the
        // fifth on the log is a reading.
        for (index in 0 until 4) assertTrue("point $index", chart.values[index].isNaN())
        assertTrue("nothing past half a kilometre is a hole", chart.values.drop(4).none { it.isNaN() })
    }

    /**
     * A point that has settled does not move because a *different* bucket closed.
     *
     * The whole reason the grid is anchored to the odometer: grouping the tail from the oldest
     * re-phases every point every hundred metres, and the same road never comes back the same
     * shape.
     */
    @Test
    fun pointsAreAnchoredToTheOdometerAndDoNotRephaseWhenABucketCloses() {
        val road = (1..40).map { bucket(100.0 + it * 0.1, 0.002 * it) }
        val before = ConsumptionChart.of(road.dropLast(1))
        val after = ConsumptionChart.of(road)
        assertEquals(39, before.values.size)
        assertEquals(40, after.values.size)
        // Both runs start at the log's first bucket, so the same grid step is the same index in
        // both: a point that has settled keeps its value when the next hundred metres closes.
        for (index in 0 until 39) {
            assertEquals(
                "the point at ${100.1 + index * 0.1} km",
                before.values[index],
                after.values[index],
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

    /** The snapshot is values and a span, and nothing is a width any more. */
    @Test
    fun theSnapshotCarriesPointsAndNothingElse() {
        val chart = ConsumptionChart.of(road(45, toKm = 104.5))
        assertEquals(chart.values.size, chart.span)
        assertEquals(45, chart.span)
        assertTrue(
            "no field of the snapshot is a width",
            ConsumptionChartSnapshot::class.java.declaredFields.none {
                it.name.contains("width", ignoreCase = true)
            },
        )
    }

    /**
     * A bucket ending exactly on a grid edge is walked once per step it covers, and terminates.
     *
     * The walk used to advance along the road - `at = min(end, (step + 1) * PITCH_KM)` - and a tenth
     * is not a binary fraction, so for some metres on the grid `floor(at / 0.1)` comes out one step
     * low, the next edge back is `at` itself, the overlap is zero and the walk stands still for
     * ever. The 500 m bins got away with it because a half *is* exact. It walks the steps now, so
     * termination is the loop's own bound rather than a property of the arithmetic inside it.
     */
    @Test
    fun aBucketEndingOnAGridEdgeIsWalkedOnceAndTerminates() {
        // Every hundred-metre edge from 0.1 to 20.0, one bucket each, and a chart built from each.
        // A lone hundred metres is a hole under the half-kilometre rule, so the walk is checked by
        // the point count and then by half a kilometre of buckets ending on the same edge.
        for (tenth in 1..200) {
            val end = tenth / 10.0
            val chart = ConsumptionChart.of(listOf(bucket(end, 0.02)))
            assertEquals("one bucket at $end km is one point", 1, chart.values.size)
            val half = ConsumptionChart.of(List(5) { bucket(end - (4 - it) * 0.1, 0.02) })
            assertEquals("five buckets to $end km are five points", 5, half.values.size)
            assertEquals("at $end km", 20.0, half.values.last().toDouble(), 1e-6)
        }
        // And buckets carrying several steps, each ending on an edge: every step gets its own road
        // and nothing gets it twice.
        for (steps in 2..40) {
            val km = steps / 10.0
            val end = 100.0 + km
            val chart = ConsumptionChart.of(listOf(bucket(end, 0.02 * steps, km = km)))
            assertEquals("$steps steps", steps, chart.values.size)
            assertEquals(
                "the road under $steps steps",
                km,
                chart.values.size * ConsumptionChart.PITCH_KM,
                1e-9,
            )
            // The whole bucket is one rate, so every point with half a kilometre behind it reads
            // that rate; the first four have less and are holes.
            chart.values.forEachIndexed { index, value ->
                if (index < 4) assertTrue("point $index of $steps", value.isNaN())
                else assertEquals("point $index of $steps", 20.0, value.toDouble(), 1e-6)
            }
        }
    }

    @Test
    fun nothingClosedIsNoChart() {
        assertTrue(ConsumptionChart.of(emptyList()).isEmpty)
        assertTrue(ConsumptionChartSnapshot.EMPTY.isEmpty)
        assertEquals(0, ConsumptionChartSnapshot.EMPTY.span)
    }

    /**
     * And the shape a kilometre makes of the owner's own road is the shape the boards draw.
     *
     * A hundred 100 m buckets whose trailing kilometres are the car's journal of 2026-09-18: the
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
