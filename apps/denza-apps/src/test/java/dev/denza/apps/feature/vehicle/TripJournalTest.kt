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
        assertTrue(loaded.energy.engineKwhKnown)
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

    /**
     * A checkpoint that cannot be written - a full disk, a sync that failed - leaves the file it
     * found. [JournalFile.replace] already kept it; the journal then deleted it, so one failed
     * checkpoint lost the last good trip and a restart before the next good one started it again.
     */
    @Test
    fun aCheckpointThatFailsKeepsTheLastGoodRecord() {
        val journal = journal()
        assertTrue(journal.save(record))
        // Where the temporary file has to go, something that cannot be opened as a file.
        File(directory, "trip.log.tmp").mkdirs()

        assertFalse("the failure is reported", journal.save(record.copy(energy = record.energy.copy(netKwh = 11.7))))
        assertTrue("the last good record is still there", file().exists())
        assertEquals(9.3, journal().load()!!.energy.netKwh, 1e-4)
        assertTrue(wipes.isEmpty())
    }

    /**
     * Standing in P the record does not change, and the ten-second checkpoint wrote it again and
     * synced it, up to 360 times an hour. The same record is not written twice; a changed one is.
     */
    @Test
    fun anUnchangedRecordIsNotWrittenAgain() {
        val journal = journal()
        journal.save(record.copy(armed = true))
        val long = System.currentTimeMillis() - 3_600_000L
        assertTrue(file().setLastModified(long))

        assertTrue(journal.save(record.copy(armed = true)))
        assertEquals("the file was not touched", long, file().lastModified())

        assertTrue(journal.save(record.copy(armed = true, odometerKm = 12_345.7)))
        assertTrue("a changed record is written", file().lastModified() > long)
    }

    /**
     * 0.7.1's record, as it wrote it. Its third field was `∫GENERATION_KW dt`, and this build's is
     * the pack's charge while the engine runs: the totals that mean the same are kept, and the
     * engine charge is unknown rather than a sum of the two.
     */
    @Test
    fun aRecordFrom071KeepsItsTotalsAndNotItsEngineCharge() {
        // `"%.4f,%.4f,%.4f,%.1f,%.3f,%.1f,%d\n"`, from denza-apps-v0.7.1's TripJournal.save.
        file().writeText("9.3000,3.1000,1.1000,372.0,42.400,12345.6,0\n")

        val loaded = journal().load()!!

        assertEquals(9.3, loaded.energy.netKwh, 1e-4)
        assertEquals(3.1, loaded.energy.recoveredKwh, 1e-4)
        assertEquals(372.0, loaded.energy.engineSeconds, 1e-1)
        assertEquals(42.4, loaded.energy.kilometres, 1e-3)
        assertEquals(12_345.6, loaded.odometerKm, 1e-1)
        assertFalse(loaded.armed)
        assertFalse("the engine charge of 0.7.1 is another quantity", loaded.energy.engineKwhKnown)
        assertEquals(0.0, loaded.energy.engineKwh, 0.0)
        assertTrue(loaded.energy.opened)
        assertTrue(wipes.isEmpty())
    }

    @Test
    fun theRecordSaysItsVersionAndWhatItKnows() {
        val unknown = record.copy(energy = record.energy.copy(engineKwh = 0.0, engineKwhKnown = false, opened = true))
        journal().save(unknown)

        assertTrue(file().readText().startsWith(TripJournal.VERSION + ","))
        val loaded = journal().load()!!
        assertFalse(loaded.energy.engineKwhKnown)
        assertTrue(loaded.energy.opened)

        journal().save(record)
        val known = journal().load()!!
        assertTrue(known.energy.engineKwhKnown)
        assertFalse(known.energy.opened)
    }

    /**
     * P, and the power cut a few seconds later. The checkpoint ran every ten seconds, so the last
     * record on disk was the open trip; the drive after the restart restored it as open and added
     * itself to the previous one. The record that says P is written on the sweep that read it.
     */
    @Test
    fun aParkTheIgnitionCutSecondsLaterIsOnDiskAndTheNextDriveIsANewTrip() {
        val ledger = TripEnergyLedger(maxGapSeconds = 7200.0)
        val checkpoint = TripCheckpoint()
        val journal = journal()
        fun sweep(atMs: Long, odometerKm: Double, powerKw: Double, parked: Boolean, seconds: Double) {
            ledger.sample(odometerKm, powerKw, false, parked, seconds)
            val record = ledger.record() ?: return
            if (!checkpoint.due(record, atMs)) return
            checkpoint.attempted(record, atMs)
            journal.save(record)
        }
        sweep(100_000L, 1000.0, 0.0, false, 1.0)
        sweep(101_000L, 1000.1, 10.0, false, 3600.0)
        // The periodic checkpoint writes the open trip.
        sweep(111_000L, 1000.2, 10.0, false, 3600.0)
        // The car stops and the driver selects P two seconds later.
        sweep(113_000L, 1000.2, 0.0, true, 1.0)

        // The ignition is cut. The next process restores what is on disk.
        val restored = TripEnergyLedger(maxGapSeconds = 7200.0)
        assertTrue(restored.restore(journal().load()!!, 1000.2))
        restored.sample(1000.2, 0.0, false, false, 1.0)
        restored.sample(1000.3, 4.0, false, false, 3600.0)

        assertEquals("the next drive is its own trip", 4.0, restored.trip.netKwh, 1e-9)
        assertEquals(0.1, restored.trip.kilometres, 1e-6)
    }

    @Test
    fun theTripIsCheckpointedEveryTenSecondsAndAParkOnlyOnce() {
        val checkpoint = TripCheckpoint()
        val open = record
        val parked = record.copy(armed = true)
        assertTrue(checkpoint.due(open, 20_000L))
        checkpoint.attempted(open, 20_000L)
        assertFalse(checkpoint.due(open, 29_999L))
        assertTrue("P is written at once", checkpoint.due(parked, 21_000L))
        checkpoint.attempted(parked, 21_000L)
        assertFalse("and once, not on every sweep it stands", checkpoint.due(parked, 22_000L))
        assertTrue("the periodic checkpoint still runs", checkpoint.due(parked, 31_000L))
        checkpoint.attempted(open, 31_000L)
        assertTrue("the next P is a boundary again", checkpoint.due(parked, 32_000L))
    }

    @Test
    fun aNegativeNetIsAnAnswerBecauseADescentIsOne() {
        // A trip that ended lower than it started really can hand the pack more than it took.
        journal().save(record.copy(energy = record.energy.copy(netKwh = -0.8)))
        assertEquals(-0.8, journal().load()!!.energy.netKwh, 1e-4)
    }
}
