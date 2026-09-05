package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsumptionWindowTest {

    private fun ramp(n: Int) = List(n) { it.toDouble() }

    @Test
    fun theClusterShowsThreeKilometresAtOdometerResolution() {
        assertEquals(3.0, ConsumptionWindow.KM, 1e-9)
        assertEquals(30, ConsumptionWindow.buckets)
        assertEquals(0.1, ConsumptionLog.DEFAULT_BUCKET_KM, 1e-9)
    }

    @Test
    fun theJournalRetainsThirtyKilometresWithoutChangingTheVisibleWindow() {
        assertEquals(30.0, ConsumptionLog.RETENTION_KM, 1e-9)
        assertEquals(300, ConsumptionLog.DEFAULT_CAPACITY)
        assertEquals(30, ConsumptionWindow.raw(ramp(300)).size)
    }

    @Test
    fun theChartOnlyEverLooksAtItsOwnTail() {
        val visible = ConsumptionWindow.raw(ramp(300))
        assertEquals(270.0, visible.first(), 1e-9)
        assertEquals(299.0, visible.last(), 1e-9)
    }

    @Test
    fun aPartlyFilledChartReportsHowMuchRoadItActuallyHas() {
        assertEquals(1.5, ConsumptionWindow.coveredKm(ramp(15)), 1e-9)
        assertEquals(3.0, ConsumptionWindow.coveredKm(ramp(300)), 1e-9)
        assertEquals(0.0, ConsumptionWindow.coveredKm(emptyList()), 1e-9)
        assertTrue(ConsumptionWindow.raw(emptyList()).isEmpty())
    }

    @Test
    fun aListThatIsAlreadyTheWindowIsNotCopiedToLookAtIt() {
        // The panel draws at sixty frames a second and reads this three times in each of them.
        // The snapshot carries the tail rather than the journal's whole thirty kilometres, so the
        // window is the identity here and a frame allocates nothing to find it.
        val window = ramp(30)
        assertSame(window, ConsumptionWindow.raw(window))
        val filling = ramp(12)
        assertSame(filling, ConsumptionWindow.raw(filling))
    }

    @Test
    fun theMeanIsOfWhatWasSpentAndNotOfWhatCameBack() {
        // A returning bucket is energy the road gave back, and a mean that averages it in reports a
        // consumption nobody had. The old reader built two lists per frame to say this.
        assertEquals(20.0, ConsumptionWindow.mean(listOf(10.0, 30.0))!!, 1e-9)
        assertEquals(20.0, ConsumptionWindow.mean(listOf(10.0, -8.0, 30.0))!!, 1e-9)
        assertEquals("a bucket that spent nothing still spent", 0.0, ConsumptionWindow.mean(listOf(0.0))!!, 1e-9)
        assertNull("nothing but return is not a consumption", ConsumptionWindow.mean(listOf(-1.0, -2.0)))
        assertNull(ConsumptionWindow.mean(emptyList()))
    }
}
