package dev.denza.apps.feature.vehicle

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The trip on disk: written whole, read back or thrown away, and never half of either.
 *
 * The failure this defends against is the one the consumption journal already defends against from
 * the other side. That file is appended to because it is a list; this one is rewritten because it is
 * a single record, and a rewrite is exactly what an ignition cut can leave in halves. So the write
 * goes through a temporary file and a rename, and everything it cannot parse is deleted rather than
 * repaired - a trip figure is about the last hour of driving, and one that cannot be trusted is
 * worth less than starting again.
 */
class TripJournalTest {

    private lateinit var directory: File
    private val wipes = mutableListOf<String>()

    private fun journal() = TripJournal.of(directory) { wipes += it }

    private fun file() = File(directory, "trip.log")

    private val record = TripRecord(
        energy = TripEnergy(
            netKwh = 9.3,
            recoveredKwh = 3.1,
            engineKwh = 1.1,
            engineSeconds = 372.0,
            kilometres = 42.4,
        ),
        odometerKm = 12_345.6,
        armed = false,
    )

    @Before
    fun makeDirectory() {
        directory = File.createTempFile("trip", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @After
    fun removeDirectory() {
        directory.deleteRecursively()
    }

    @Test
    fun aRecordComesBackAsItWentIn() {
        journal().save(record)
        val loaded = journal().load()!!

        assertEquals(9.3, loaded.energy.netKwh, 1e-4)
        assertEquals(3.1, loaded.energy.recoveredKwh, 1e-4)
        assertEquals(1.1, loaded.energy.engineKwh, 1e-4)
        assertEquals(372.0, loaded.energy.engineSeconds, 1e-1)
        assertEquals(42.4, loaded.energy.kilometres, 1e-3)
        assertEquals(12_345.6, loaded.odometerKm, 1e-1)
        assertFalse(loaded.armed)
    }

    @Test
    fun theArmedFlagSurvivesBecauseItIsWhatEndsTheTrip() {
        journal().save(record.copy(armed = true))
        assertTrue(journal().load()!!.armed)
    }

    @Test
    fun aSecondSaveReplacesTheRecordRatherThanGrowingTheFile() {
        val journal = journal()
        journal.save(record)
        journal.save(record.copy(energy = record.energy.copy(netKwh = 11.7)))

        assertEquals(1, file().readLines().count { it.isNotBlank() })
        assertEquals(11.7, journal.load()!!.energy.netKwh, 1e-4)
    }

    @Test
    fun noFileIsNoTripRatherThanAnEmptyOne() {
        assertNull(journal().load())
        assertTrue(wipes.isEmpty())
    }

    @Test
    fun aTornRecordIsDeletedRatherThanRepaired() {
        file().writeText("9.3,3.1,1.1,372.0")

        assertNull(journal().load())
        assertFalse(file().exists())
        assertEquals(1, wipes.size)
    }

    @Test
    fun aRecordWithAWordInItIsNotOurs() {
        file().writeText("9.3,3.1,1.1,372.0,42.4,двенадцать,0")

        assertNull(journal().load())
        assertFalse(file().exists())
    }

    @Test
    fun anImpossibleOdometerIsNotOurs() {
        file().writeText("9.3,3.1,1.1,372.0,42.4,-4.0,0")
        assertNull(journal().load())
    }

    @Test
    fun aFlagThatIsNotAFlagIsNotOurs() {
        file().writeText("9.3,3.1,1.1,372.0,42.4,100.0,2")
        assertNull(journal().load())
    }

    @Test
    fun aFileTooBigToBeOneRecordIsNotOurs() {
        file().writeText("9.3,3.1,1.1,372.0,42.4,100.0,0\n".repeat(200))

        assertNull(journal().load())
        assertFalse(file().exists())
    }

    @Test
    fun clearingLeavesNothingBehind() {
        val journal = journal()
        journal.save(record)
        journal.clear()

        assertFalse(file().exists())
        assertNull(journal.load())
    }

    @Test
    fun aNegativeNetIsAnAnswerBecauseADescentIsOne() {
        // A trip that ended lower than it started really can hand the pack more than it took.
        journal().save(record.copy(energy = record.energy.copy(netKwh = -0.8)))
        assertEquals(-0.8, journal().load()!!.energy.netKwh, 1e-4)
    }
}
