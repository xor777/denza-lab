package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumptionWindowTest {

    /** [n] ordinary hundred-metre buckets, each spending [kwh], starting at 100 km. */
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
        assertEquals(10.2, ConsumptionWindow.roadKm(long), 1e-9)
    }

    @Test
    fun aPartlyFilledChartReportsHowMuchKnownRoadItActuallyHas() {
        assertEquals(1.5, ConsumptionWindow.coveredKm(road(15)), 1e-9)
        assertEquals(10.0, ConsumptionWindow.coveredKm(road(300)), 1e-9)
        assertEquals(0.0, ConsumptionWindow.coveredKm(emptyList()), 1e-9)
        assertTrue(ConsumptionWindow.raw(emptyList()).isEmpty())
    }

    @Test
    fun theRoadUnderAHoleIsCountedAndTheRoadInTheFigureIsNot() {
        // A kilometre the log has no energy for: it is under the chart, and out of the figure.
        val withHole = road(20).toMutableList()
        for (index in 5 until 15) {
            withHole[index] = withHole[index].copy(kwh = 0.0, knownKm = 0.0)
        }
        assertEquals("the road is all there", 2.0, ConsumptionWindow.roadKm(withHole), 1e-9)
        assertEquals("the known road is not", 1.0, ConsumptionWindow.coveredKm(withHole), 1e-9)
        // Ten known buckets of 0.02 kWh over one kilometre.
        assertEquals(0.2 / 1.0 * 100.0, ConsumptionWindow.mean(withHole)!!, 1e-9)
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
        val holes = List(5) { ConsumptionSample(100.0 + (it + 1) * 0.1, 0.0, 0.1, 0.0) }
        assertNull("a chart of holes has no mean", ConsumptionWindow.mean(holes))
        assertNull(ConsumptionWindow.mean(emptyList()))
    }
}
