package dev.denza.apps.feature.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripParkSignalTest {

    @Test
    fun parsesTheLiveProvenParkAndDriveWords() {
        assertEquals(
            true,
            TripParkSignal.parse("Result: Parcel(00000000 00000001   '........')"),
        )
        assertEquals(
            false,
            TripParkSignal.parse("Result: Parcel(00000000 00000000   '........')"),
        )
    }

    @Test
    fun refusesMissingAndUnknownAnswers() {
        assertNull(TripParkSignal.parse(""))
        assertNull(TripParkSignal.parse("Result: Parcel(00000000 00000003   '........')"))
    }
}
