package dev.denza.apps.feature.split

import java.io.File
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The split journal on disk (2026-09-23): what the ring is told, readable after the car's log
 * buffer has long turned over, and never more than two files of [SplitDiagnosticJournal.CAP_BYTES].
 */
class SplitDiagnosticJournalTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var zone: TimeZone

    @Before
    fun pinTheClock() {
        zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    @After
    fun restoreTheClock() {
        TimeZone.setDefault(zone)
    }

    @Test
    fun aLineCarriesTheWallClockTheProcessAndTheLane() {
        val dir = folder.newFolder()
        val journal = SplitDiagnosticJournal(dir, "main", now = { SEP_23_19_24_07_636 })

        journal.append("open: picker launched", background = false)
        journal.append("reconcile: area 2", background = true)

        assertEquals(
            listOf(
                "09-23 19:24:07.636 main open: picker launched",
                "09-23 19:24:07.636 main bg reconcile: area 2",
            ),
            current(dir),
        )
    }

    /** The reconcile repeats an unchanged world every few seconds; the disk hears it once. */
    @Test
    fun aBackgroundLineRepeatingTheOneBeforeItIsNotWrittenAgain() {
        val dir = folder.newFolder()
        val journal = SplitDiagnosticJournal(dir, "main", now = { 0L })

        repeat(50) { journal.append("reconcile: мир не изменился", background = true) }
        journal.append("open: обращений 28", background = false)
        journal.append("reconcile: мир не изменился", background = true)

        assertEquals(3, current(dir).size)
    }

    /** Operations never collapse: two identical steps of one run are two facts. */
    @Test
    fun anOperationLineIsWrittenEveryTime() {
        val dir = folder.newFolder()
        val journal = SplitDiagnosticJournal(dir, "main", now = { 0L })

        repeat(3) { journal.append("open +100ms scene-read", background = false) }

        assertEquals(3, current(dir).size)
    }

    /** The whole promise to the car's storage: two files, each stopped at the cap. */
    @Test
    fun theJournalNeverHoldsMoreThanTwoFilesAtTheCap() {
        val dir = folder.newFolder()
        val cap = 1_000L
        val journal = SplitDiagnosticJournal(dir, "main", capBytes = cap, now = { 0L })

        repeat(5_000) { at -> journal.append("open step $at", background = false) }

        val files = dir.listFiles().orEmpty().map(File::getName).toSet()
        assertEquals(
            setOf(SplitDiagnosticJournal.CURRENT, SplitDiagnosticJournal.PREVIOUS),
            files,
        )
        val line = "01-01 00:00:00.000 main open step 4999\n".length
        dir.listFiles().orEmpty().forEach { file ->
            assertTrue("${file.name} ${file.length()}", file.length() < cap + line)
        }
        assertEquals("the newest line is the last one", "01-01 00:00:00.000 main open step 4999", current(dir).last())
        val previous = File(dir, SplitDiagnosticJournal.PREVIOUS).readLines()
        assertFalse("the previous file is the one just before, not the first", previous.first().endsWith("step 0"))
    }

    private fun current(dir: File): List<String> =
        File(dir, SplitDiagnosticJournal.CURRENT).readLines()

    private companion object {
        /** 2026-09-23 19:24:07.636 UTC. */
        const val SEP_23_19_24_07_636 = 1_790_191_447_636L
    }
}
