package dev.denza.apps.feature.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * What the journal does when the file is not what it left.
 *
 * The interesting cases here are all failures, because the happy path is a
 * round trip and the failures are what happen in a car: the ignition cuts a
 * write in half, the storage returns something else, the file outlives the drive
 * it described. Every one of them has to end somewhere safe, and the owner's
 * instruction was that "safe" may simply mean an empty journal.
 */
class ConsumptionJournalTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val wipes = mutableListOf<String>()

    private fun journal(): ConsumptionJournal =
        ConsumptionJournal(File(folder.root, "consumption.log")) { wipes.add(it) }

    private fun file() = File(folder.root, "consumption.log")

    private fun bars(count: Int, from: Double = 1000.0) =
        List(count) { ConsumptionSample(from + it * 0.1, 18.0 + it) }

    @Test
    fun whatGoesInComesBackOut() {
        journal().append(bars(3))
        val read = journal().load()
        assertEquals(3, read.size)
        assertEquals(1000.0, read.first().odometerKm, 1e-6)
        assertEquals(20.0, read.last().value, 1e-3)
        assertTrue(wipes.isEmpty())
    }

    @Test
    fun appendingAddsToWhatIsAlreadyThereRatherThanReplacingIt() {
        val journal = journal()
        journal.append(bars(2))
        journal.append(bars(2, from = 2000.0))
        assertEquals(4, journal().load().size)
    }

    @Test
    fun anAbsentFileIsAnEmptyJournalAndNotAFailure() {
        assertTrue(journal().load().isEmpty())
        assertTrue(wipes.isEmpty())
    }

    @Test
    fun aWriteTheIgnitionCutInHalfCostsOnlyItsOwnLine() {
        journal().append(bars(3))
        // The tail of a line that never finished being written. Not "17." -
        // Java parses that as a number, which is exactly the sort of thing that
        // makes a hand-written torn-write test pass for the wrong reason.
        file().appendText("1000.4")
        val read = journal().load()
        assertEquals(3, read.size)
        assertTrue("a torn tail is not a reason to wipe", wipes.isEmpty())
    }

    @Test
    fun aBadLineAnywhereElseTakesTheWholeFile() {
        journal().append(bars(3))
        file().writeText(file().readText().replace("1000.1", "не число"))
        assertTrue(journal().load().isEmpty())
        assertFalse(file().exists())
        assertEquals(1, wipes.size)
    }

    @Test
    fun aFileFromSomethingElseEntirelyIsDroppedRatherThanMined() {
        file().writeText("{\"buckets\":[18.0,19.0]}\n")
        assertTrue(journal().load().isEmpty())
        assertFalse(file().exists())
    }

    @Test
    fun anImplausibleOdometerIsABadParseRatherThanAReading() {
        // Put it first, so it is not excused as a torn tail.
        file().writeText("99999999.0,19.0\n1000.0,18.0\n")
        assertTrue(journal().load().isEmpty())
        assertFalse(file().exists())
    }

    @Test
    fun aFileTooBigToBeOursIsDroppedWithoutParsingIt() {
        file().writeText("1000.0,18.0\n".repeat(20_000))
        assertTrue(journal().load().isEmpty())
        assertFalse(file().exists())
        assertEquals(1, wipes.size)
    }

    @Test
    fun theFileIsBoundedSoItCannotGrowForever() {
        val journal = journal()
        // Well past the cap and its slack, in the batches the hub actually writes.
        repeat(80) { batch -> journal.append(bars(10, from = 1000.0 + batch * 1.0)) }
        val read = journal.load()
        // Bounded by the cap plus its slack rather than by the cap alone: the
        // trim is the expensive path and is not run on every append. A window
        // reads only the newest MAX_LINES of it either way.
        val bound = ConsumptionJournal.MAX_LINES + ConsumptionJournal.TRIM_SLACK
        assertTrue("kept ${read.size} against a bound of $bound", read.size <= bound)
        assertTrue("nothing was ever trimmed", read.size < 800)
        // Trimming keeps the newest road, which is the only part a window wants.
        assertTrue(read.last().odometerKm > 1070.0)
    }

    @Test
    fun clearingLeavesNothingBehind() {
        val journal = journal()
        journal.append(bars(5))
        journal.clear()
        assertFalse(file().exists())
        assertTrue(journal.load().isEmpty())
    }

    @Test
    fun everyWipeSaysWhyItHappened() {
        file().writeText("мусор\nещё мусор\n")
        journal().load()
        assertEquals(1, wipes.size)
        assertTrue("reason was '${wipes.single()}'", wipes.single().isNotBlank())
    }
}
