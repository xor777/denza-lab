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
    private val failures = mutableListOf<String>()

    private fun journal(): ConsumptionJournal =
        ConsumptionJournal(File(folder.root, "consumption.log"), { wipes.add(it) }, { failures.add(it) })

    private fun file() = File(folder.root, "consumption.log")

    private fun bars(count: Int, from: Double = 1000.0) =
        List(count) { ConsumptionSample(from + it * 0.1, 0.018 + it * 0.001, 0.1, 0.1) }

    @Test
    fun whatGoesInComesBackOut() {
        journal().append(bars(3))
        val read = journal().load()
        assertEquals(3, read.size)
        assertEquals(1000.0, read.first().odometerKm, 1e-6)
        assertEquals(0.020, read.last().kwh, 1e-5)
        assertEquals("the road comes back with it", 0.1, read.last().km, 1e-6)
        assertEquals(0.1, read.last().knownKm, 1e-6)
        assertTrue(wipes.isEmpty())
    }

    @Test
    fun appendingAddsToWhatIsAlreadyThereRatherThanReplacingIt() {
        val journal = journal()
        journal.append(bars(2))
        journal.append(bars(2, from = 2000.0))
        assertEquals(4, journal().load().size)
    }

    /**
     * The longest bucket the log can close is a re-anchor's jump plus the tick that closed it.
     *
     * A bucket already part full when a five-kilometre step arrives closes carrying both, which is
     * exactly [OdometerGate.MAX_JUMP_KM] + [ConsumptionLog.DEFAULT_BUCKET_KM] one epsilon short of
     * the boundary. The parser's bound used to be the jump alone, so the longest bucket the app can
     * write was one the app would refuse to read back.
     */
    @Test
    fun theLongestBucketTheLogCanCloseSurvivesARestart() {
        val log = ConsumptionLog()
        var longest = 0.0
        val kept = mutableListOf<ConsumptionSample>()
        val almost = ConsumptionLog.DEFAULT_BUCKET_KM - 1e-5
        log.sample(1000.0, 20.0, 0.0)
        // A hair short of closing, then the longest step that is still road rather than a jump.
        log.sample(1000.0 + almost, 20.0, 6.0)
        log.sample(1000.0 + almost + OdometerGate.MAX_JUMP_KM, 20.0, 6.0)
        log.buckets.forEach {
            longest = maxOf(longest, it.km)
            kept += it
        }
        assertTrue("longer than the jump alone: $longest", longest > OdometerGate.MAX_JUMP_KM)
        assertTrue(
            "and no longer than the jump and the tick that closed it: $longest",
            longest <= OdometerGate.MAX_JUMP_KM + ConsumptionLog.DEFAULT_BUCKET_KM + 1e-9,
        )
        journal().append(kept)
        assertEquals("and it reads back", kept.size, journal().load().size)
        assertTrue(wipes.isEmpty())
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

    /**
     * The cut is followed by a drive. The torn tail used to be joined onto the next line written,
     * and the line after that made the joined one an interior line, which takes the whole file: up
     * to thirty kilometres for a cut that had cost one line, every time the car is switched off.
     */
    @Test
    fun theDriveAfterACutKeepsEverythingTheCutLeft() {
        journal().append(bars(3))
        file().appendText("1000.4")

        val restarted = journal()
        assertEquals(3, restarted.load().size)
        restarted.append(bars(1, from = 1000.5))
        restarted.append(bars(1, from = 1000.6))

        val read = journal().load()
        assertEquals(listOf(1000.0, 1000.1, 1000.2, 1000.5, 1000.6), read.map { it.odometerKm })
        assertTrue("nothing was wiped: $wipes", wipes.isEmpty())
        assertTrue("and the file ends with a whole line", file().readText().endsWith("\n"))
    }

    /**
     * A cut inside the last number: `1000.2,0.02000,0.1000,0.0650` ended at `0.06`, which parses -
     * as a bucket of 33.3 kWh/100 km where 30.8 was written. It used to be read and completed with a
     * newline, and both screens used the altered value after the restart. A tail is never a reading.
     */
    @Test
    fun aTailCutInsideItsLastNumberIsNotReadAsTheBucketItLooksLike() {
        journal().append(bars(2))
        file().appendText("1000.2,0.02000,0.1000,0.06")

        val restarted = journal()
        assertEquals(listOf(1000.0, 1000.1), restarted.load().map { it.odometerKm })
        restarted.append(bars(1, from = 1000.3))

        assertEquals(listOf(1000.0, 1000.1, 1000.3), journal().load().map { it.odometerKm })
        assertFalse("the tail was cut, not completed", file().readText().contains("0.06\n"))
        assertTrue(wipes.isEmpty())
    }

    /** Even a whole bucket short only of its newline: one bucket is the accepted cost. */
    @Test
    fun aTailWithoutItsNewlineIsCutEvenWhenItIsWhole() {
        journal().append(bars(2))
        file().appendText("1000.2,0.02000,0.1000,0.1000")

        val restarted = journal()
        assertEquals(2, restarted.load().size)
        restarted.append(bars(2, from = 1000.3))

        assertEquals(listOf(1000.0, 1000.1, 1000.3, 1000.4), journal().load().map { it.odometerKm })
        assertTrue(wipes.isEmpty())
    }

    /** A cut inside the very first line leaves nothing worth keeping, and nothing to wipe either. */
    @Test
    fun aFileThatIsOnlyATornLineStartsCleanOnTheNextAppend() {
        file().writeText("1000.0,0.0")
        journal().append(bars(2, from = 1000.1))
        assertEquals(2, journal().load().size)
    }

    /**
     * A write that fails - here a file the writer may not open, as a full disk or a refused sync
     * would be - costs its own batch. It used to wipe the file, and thirty kilometres of valid
     * lines went with one failed bucket. Reported once, however many buckets keep failing.
     */
    @Test
    fun aWriteThatFailsKeepsWhatIsAlreadyOnDisk() {
        val journal = journal()
        journal.append(bars(3))
        assertTrue(file().setWritable(false))

        journal.append(bars(1, from = 1000.3))
        journal.append(bars(1, from = 1000.4))

        assertTrue("the file is still there", file().exists())
        assertEquals(listOf(1000.0, 1000.1, 1000.2), journal().load().map { it.odometerKm })
        assertTrue("nothing was wiped: $wipes", wipes.isEmpty())
        assertEquals("said once", 1, failures.size)

        assertTrue(file().setWritable(true))
        journal.append(bars(1, from = 1000.5))
        assertEquals(listOf(1000.0, 1000.1, 1000.2, 1000.5), journal().load().map { it.odometerKm })
    }

    @Test
    fun aBadLineAnywhereElseTakesTheWholeFile() {
        journal().append(bars(3))
        file().writeText(file().readText().replace("1000.1", "не число"))
        assertTrue(journal().load().isEmpty())
        assertFalse(file().exists())
        assertEquals(1, wipes.size)
    }

    /**
     * The two-column format is refused rather than upgraded.
     *
     * A bucket carries its road and its known road now, and neither can be invented from a file
     * that never held them: guessing one tick of road per line would put an odometer step nobody
     * watched under a figure as if it had been measured. So a journal written before 2026-09-07 is
     * a journal this class did not write, and the drive up to now is the whole cost.
     */
    @Test
    fun aJournalInTheOldTwoColumnFormatIsRefusedAndStartsAgain() {
        file().writeText("1000.0,18.000\n1000.1,19.000\n1000.2,20.000\n")
        assertTrue(journal().load().isEmpty())
        assertFalse(file().exists())
        assertEquals(1, wipes.size)
    }

    @Test
    fun aRecordWhoseKnownRoadIsLongerThanItsRoadIsNotOurs() {
        file().writeText("1000.0,0.02000,0.1000,0.4000\n1000.1,0.02000,0.1000,0.1000\n")
        assertTrue(journal().load().isEmpty())
        assertFalse(file().exists())
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
        file().writeText("99999999.0,0.02000,0.1000,0.1000\n1000.0,0.02000,0.1000,0.1000\n")
        assertTrue(journal().load().isEmpty())
        assertFalse(file().exists())
    }

    @Test
    fun aFileTooBigToBeOursIsDroppedWithoutParsingIt() {
        file().writeText("1000.0,0.02000,0.1000,0.1000\n".repeat(20_000))
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

    /**
     * The append past the cap succeeds and the rewrite that trims it does not - a nearly full disk
     * takes a line and refuses a file. Here the temporary file's path is taken by a directory that
     * cannot be removed. [JournalFile.replace] leaves the journal whole, and the trim used to delete
     * it anyway.
     */
    @Test
    fun aTrimThatCannotWriteKeepsTheJournal() {
        val journal = journal()
        val blocker = File(folder.root, "consumption.log.tmp")
        assertTrue(blocker.mkdirs())
        File(blocker, "occupied").writeText("x")
        val past = ConsumptionJournal.MAX_LINES + ConsumptionJournal.TRIM_SLACK
        repeat(past / 10 + 2) { batch -> journal.append(bars(10, from = 1000.0 + batch * 1.0)) }

        assertTrue("the journal is still there", file().exists())
        assertTrue("nothing was wiped: $wipes", wipes.isEmpty())
        assertEquals("said once", 1, failures.size)
        assertEquals("every line is kept", (past / 10 + 2) * 10, journal().load().size)

        // The disk frees up: the next append trims.
        File(blocker, "occupied").delete()
        assertTrue(blocker.delete())
        journal.append(bars(1, from = 2000.0))
        val read = journal().load()
        assertEquals(ConsumptionJournal.MAX_LINES, read.size)
        assertEquals(2000.0, read.last().odometerKm, 1e-6)
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
