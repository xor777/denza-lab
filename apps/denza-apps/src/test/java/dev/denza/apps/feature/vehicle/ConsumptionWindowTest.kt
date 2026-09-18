package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumptionWindowTest {

    /** [n] ordinary hundred-metre reading buckets, each spending [kwh], starting at 100 km. */
    private fun road(n: Int, kwh: Double = 0.02, from: Double = 100.0) =
        List(n) { ConsumptionSample(from + (it + 1) * 0.1, kwh, 0.1, 0.1) }

    @Test
    fun bothScreensShowTenKilometresOfRoad() {
        // Three until the first drive; the owner read thirty steps as «крупные ступеньки» and asked
        // for ten on the cluster and on the head unit's car page alike. One object, one number.
        assertEquals(10.0, ConsumptionWindow.KM, 1e-9)
        assertEquals(0.1, ConsumptionLog.DEFAULT_BUCKET_KM, 1e-9)
    }

    @Test
    fun theJournalRetainsThirtyKilometresWithoutChangingTheVisibleWindow() {
        assertEquals(30.0, ConsumptionLog.RETENTION_KM, 1e-9)
        assertEquals(300, ConsumptionLog.DEFAULT_CAPACITY)
        assertEquals(100, ConsumptionWindow.raw(road(300)).size)
    }

    @Test
    fun theChartOnlyEverLooksAtItsOwnTail() {
        val visible = ConsumptionWindow.raw(road(300))
        assertEquals(120.1, visible.first().odometerKm, 1e-9)
        assertEquals(130.0, visible.last().odometerKm, 1e-9)
    }

    @Test
    fun theTailIsMeasuredInRoadRatherThanInRecords() {
        // Three hundred metres a bucket - an odometer step no tick can explain - so ten kilometres
        // is thirty-four records rather than a hundred. Counting records counted the wrong axis.
        val long = List(60) { ConsumptionSample(100.0 + (it + 1) * 0.3, 0.06, 0.3, 0.3) }
        val window = ConsumptionWindow.raw(long)
        assertEquals(34, window.size)
        assertEquals("and the road it holds", 10.2, window.sumOf { it.km }, 1e-9)
    }

    /**
     * And it is measured in **recorded** road: what is not a reading carries none of it.
     *
     * A kilometre the link was down is a kilometre of road nobody can say anything about, so the
     * window reaches ten kilometres of readings *past* it. The unit says ten and means ten.
     */
    @Test
    fun theWalkCountsBackOverReadingsAndNothingElse() {
        val blind = List(10) { ConsumptionSample(105.0 + (it + 1) * 0.1, 0.0, 0.1, 0.0) }
        val all = road(50) + blind + road(60, from = 106.0)
        val window = ConsumptionWindow.raw(all)
        assertEquals("a hundred readings and the ten blind ones between them", 110, window.size)
        assertEquals("ten kilometres of readings", 10.0, ConsumptionWindow.coveredKm(all), 1e-9)
        assertEquals(
            "which is exactly the chart's own width",
            ConsumptionChart.of(all).span * ConsumptionChart.PITCH_KM,
            ConsumptionWindow.coveredKm(all),
            1e-9,
        )
    }

    /**
     * Yesterday's readings are readings, and they stay until today's road pushes them out.
     *
     * The odometer floor the second review added - «yesterday's road in the window» - is gone with
     * the grid it belonged to (contract §2.6). A history that empties itself because the car was
     * driven somewhere else with the app closed is not a history; the chart is what was recorded,
     * and a re-anchor in the middle of it is a seam nothing marks.
     */
    @Test
    fun yesterdaysReadingsStayUntilTodaysRoadPushesThemOut() {
        val yesterday = road(100, from = 900.0)
        assertEquals("all of it is the window", 0, ConsumptionWindow.firstIndex(yesterday))
        assertEquals(10.0, ConsumptionWindow.coveredKm(yesterday), 1e-9)

        // Forty kilometres later the car records three hundred metres: the window is those three
        // hundred metres and the 9.7 km of yesterday in front of them, and not a kilometre more.
        val andToday = yesterday + road(3, from = 940.0)
        val window = ConsumptionWindow.raw(andToday)
        assertEquals(100, window.size)
        assertEquals("the oldest three are pushed out", 900.4, window.first().odometerKm, 1e-9)
        assertEquals(940.3, window.last().odometerKm, 1e-9)
        assertEquals(10.0, ConsumptionWindow.coveredKm(andToday), 1e-9)
    }

    @Test
    fun aPartlyFilledChartReportsHowMuchKnownRoadItActuallyHas() {
        assertEquals(1.5, ConsumptionWindow.coveredKm(road(15)), 1e-9)
        assertEquals(10.0, ConsumptionWindow.coveredKm(road(300)), 1e-9)
        assertEquals(0.0, ConsumptionWindow.coveredKm(emptyList()), 1e-9)
        assertTrue(ConsumptionWindow.raw(emptyList()).isEmpty())
    }

    @Test
    fun theRoadUnderAGapIsNotCountedAndTheFigureIsTheMeanOfWhatIsKnown() {
        // A kilometre the log has no energy for: it is out of the figure and off the axis alike.
        val withGap = road(20).toMutableList()
        for (index in 5 until 15) {
            withGap[index] = withGap[index].copy(kwh = 0.0, knownKm = 0.0)
        }
        assertEquals("the record still carries the road", 2.0, withGap.sumOf { it.km }, 1e-9)
        assertEquals("the recorded road is one kilometre", 1.0, ConsumptionWindow.coveredKm(withGap), 1e-9)
        // Ten known buckets of 0.02 kWh over one kilometre.
        assertEquals(0.2 / 1.0 * 100.0, ConsumptionWindow.mean(withGap)!!, 1e-9)
    }

    /**
     * And a bucket's own scrap of known road is in neither the figure nor the unit.
     *
     * A bucket that answered for forty of its hundred metres is not a reading -
     * [ConsumptionSample.known] refuses it whole - so counting its 0.04 km under «за 3,7 км»
     * promised road the number beside it was never taken over. The two had to be the same set of
     * buckets and were not.
     */
    @Test
    fun theRoadUnderTheUnitIsTheRoadTheFigureIsTheMeanOf() {
        val known = ConsumptionSample(100.1, 0.02, 0.1, 0.1)
        val nearlyKnown = ConsumptionSample(100.2, 0.008, 0.1, 0.04)
        assertEquals("the scrap is not in the unit", 0.1, ConsumptionWindow.coveredKm(listOf(known, nearlyKnown)), 1e-9)
        assertEquals(
            "nor in the figure",
            0.02 / 0.1 * 100.0,
            ConsumptionWindow.mean(listOf(known, nearlyKnown))!!,
            1e-9,
        )
    }

    @Test
    fun aListThatIsAlreadyTheWindowIsNotCopiedToLookAtIt() {
        // The panel draws at sixty frames a second and reads this in each of them. The snapshot
        // carries the tail rather than the journal's whole thirty kilometres, so the window is the
        // identity here and a frame allocates nothing to find it.
        val window = road(100)
        assertSame(window, ConsumptionWindow.raw(window))
        val filling = road(12)
        assertSame(filling, ConsumptionWindow.raw(filling))
    }

    @Test
    fun theFigureIsNetEnergyOverKnownRoad() {
        // Energy `Σ kWh` over road `Σ knownKm`, signed. The old reader dropped returning buckets
        // from both the sum and the count, so [10, −8, 30] printed 20 where the road cost 10.7.
        val ten = ConsumptionSample(100.1, 0.010, 0.1, 0.1)
        val back = ConsumptionSample(100.2, -0.008, 0.1, 0.1)
        val thirty = ConsumptionSample(100.3, 0.030, 0.1, 0.1)
        assertEquals(
            "the road cost what the pack paid",
            (0.010 - 0.008 + 0.030) / 0.3 * 100.0,
            ConsumptionWindow.mean(listOf(ten, back, thirty))!!,
            1e-9,
        )
        assertEquals(10.666, ConsumptionWindow.mean(listOf(ten, back, thirty))!!, 0.001)
    }

    @Test
    fun aWindowThatOnlyGaveBackPrintsItsOwnMinus() {
        // A long descent. It is the one signed figure on either screen, and it is signed because
        // it is an exception - not because it is dropped.
        val descent = List(5) { ConsumptionSample(100.0 + (it + 1) * 0.1, -0.015, 0.1, 0.1) }
        assertEquals(-15.0, ConsumptionWindow.mean(descent)!!, 1e-9)
    }

    @Test
    fun nothingKnownIsNoFigureAtAll() {
        val blind = List(5) { ConsumptionSample(100.0 + (it + 1) * 0.1, 0.0, 0.1, 0.0) }
        assertNull("a record with nothing recorded in it has no mean", ConsumptionWindow.mean(blind))
        assertNull(ConsumptionWindow.mean(emptyList()))
    }
}
