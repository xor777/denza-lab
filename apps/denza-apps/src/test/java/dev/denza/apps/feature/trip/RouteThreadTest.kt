package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The thread's point series: what it records and how it thins out. */
class RouteThreadTest {

    private fun feed(
        acc: GnssTripAccumulator,
        elapsed: Double,
        lon: Double,
        alt: Double = 0.0,
        hasAlt: Boolean = false,
    ) {
        acc.onFix(55.0, lon, alt, hasAlt, 5.0, true, 20.0, elapsed, 1.0, true)
        acc.maybeAddRoutePoint(elapsed, 55.0, lon, 0.3, 0.4, 0.0, 1.0)
    }

    @Test
    fun recordsTravelledDistanceSoStopsDoNotStretchTheThread() {
        val acc = GnssTripAccumulator()
        var lon = 37.0
        for (i in 0..20) {
            feed(acc, elapsed = i.toDouble(), lon = lon)
            lon += 0.0005
        }
        val points = acc.routePoints()
        assertTrue(points.size >= 3)
        // Distance is monotonic and matches the accumulator's own tally.
        for (i in 1 until points.size) {
            assertTrue(points[i].distanceMeters >= points[i - 1].distanceMeters)
        }
        assertEquals(acc.distanceMeters, points.last().distanceMeters, 60.0)
    }

    @Test
    fun altitudeStaysZeroUntilTheChannelIsTrustworthy() {
        val acc = GnssTripAccumulator()
        feed(acc, elapsed = 0.0, lon = 37.0)
        feed(acc, elapsed = 5.0, lon = 37.001)
        assertTrue(acc.routePoints().all { it.altitudeMeters == 0.0 })
    }

    @Test
    fun keepsTheStartOfALongTripInsteadOfDroppingIt() {
        // Capacity 6 so the halving is easy to reason about.
        val acc = GnssTripAccumulator(routeCapacity = 6)
        var lon = 37.0
        for (i in 0..40) {
            feed(acc, elapsed = i * 5.0, lon = lon)
            lon += 0.001
        }
        val points = acc.routePoints()
        assertTrue("size=${points.size}", points.size <= 6)
        // The very first point survives: a long drive thins out evenly rather
        // than forgetting where it began.
        assertEquals(0.0, points.first().distanceMeters, 1e-9)
        assertTrue(points.last().distanceMeters > 0.0)
    }

    @Test
    fun thinningKeepsPointsEvenlySpaced() {
        val acc = GnssTripAccumulator(routeCapacity = 8)
        var lon = 37.0
        for (i in 0..60) {
            feed(acc, elapsed = i * 5.0, lon = lon)
            lon += 0.001
        }
        val gaps = acc.routePoints().zipWithNext { a, b -> b.distanceMeters - a.distanceMeters }
        val min = gaps.min()
        val max = gaps.max()
        assertTrue("min=$min max=$max", max <= min * 2.5)
    }
}
