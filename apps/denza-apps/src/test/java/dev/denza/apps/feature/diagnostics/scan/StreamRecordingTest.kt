package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.report.StreamCsv
import dev.denza.apps.feature.diagnostics.scan.Fixtures.carNow
import dev.denza.apps.feature.diagnostics.scan.Fixtures.session
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Запись» never loses a recording and never says more than it captured: each recording its own new file, the
 * newest found by when it was written; a recording ends by itself when it is full or the storage refuses a
 * line, and the tab then shows it stopped, with the length it has.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StreamRecordingTest {
    private fun dir(): File = Files.createTempDirectory("diag-rec").toFile()

    @Test
    fun aSecondRecordingInTheSameSecondIsANewFileAndTheFirstStaysWhole() {
        val dir = dir()
        val sinks = CsvStreamSinks(dir, ZoneOffset.UTC)
        val first = sinks.open(Fixtures.EPOCH)!!
        first.write(listOf("1,7E0,0C,0000,0"))
        first.close()
        val second = sinks.open(Fixtures.EPOCH)!!
        second.write(listOf("2,7E0,0C,0000,0"))
        second.close()

        val files = CsvStreamSinks.recordings(dir)
        assertEquals(setOf("stream-20261020-133100.csv", "stream-20261020-133100-2.csv"), files.map(File::getName).toSet())
        assertEquals(listOf(CsvStreamSinks.HEADER, "1,7E0,0C,0000,0"), File(dir, "stream-20261020-133100.csv").readLines())
        assertEquals(listOf(CsvStreamSinks.HEADER, "2,7E0,0C,0000,0"), File(dir, "stream-20261020-133100-2.csv").readLines())
    }

    @Test
    fun theNewestRecordingIsTheLastWrittenWhateverItsName() {
        val dir = dir()
        val sinks = CsvStreamSinks(dir, ZoneOffset.UTC)
        // the clock set back between two recordings: the later one has the earlier name
        sinks.open(Fixtures.EPOCH + 3_600_000)!!.apply { write(listOf("1,7E0,0C,0000,0")) }.close()
        File(dir, "stream-20261020-143100.csv").setLastModified(1_000_000L)
        sinks.open(Fixtures.EPOCH)!!.apply { write(listOf("2,7E0,0C,0000,0")) }.close()
        File(dir, "stream-20261020-133100.csv").setLastModified(2_000_000L)
        assertEquals("stream-20261020-133100.csv", CsvStreamSinks.recordings(dir).last().name)
    }

    @Test
    fun aFullRecordingEndsAndKeepsWhatItHas() {
        val dir = dir()
        val sink = CsvStreamSinks(dir, ZoneOffset.UTC, maxBytes = 64).open(Fixtures.EPOCH)!!
        assertTrue(sink.write(listOf("1,7E0,0C,0000,0")))
        assertFalse(sink.write(listOf("2,7E0,0C,0000,0".repeat(4))))
        assertFalse("ended: nothing more goes in", sink.write(listOf("3,7E0,0C,0000,0")))
        sink.close()
        assertEquals(listOf(CsvStreamSinks.HEADER, "1,7E0,0C,0000,0"), CsvStreamSinks.recordings(dir).single().readLines())
    }

    @Test
    fun onlyTheNewestRecordingsAreKept() {
        val dir = dir()
        val sinks = CsvStreamSinks(dir, ZoneOffset.UTC, keep = 2)
        (0 until 4).forEach { i ->
            sinks.open(Fixtures.EPOCH + i * 1_000L)!!.apply { write(listOf("$i,7E0,0C,0000,0")) }.close()
            File(dir, "stream-20261020-13310$i.csv").setLastModified(1_000_000L * (i + 1))
        }
        assertEquals(listOf("stream-20261020-133102.csv", "stream-20261020-133103.csv"), CsvStreamSinks.recordings(dir).map(File::getName))
    }

    /**
     * A recording started and stopped before its first line - a paused graph, a quick tap - deletes no recording and
     * leaves no file; the report still finds the newest that has a line.
     */
    @Test
    fun anEmptyRecordingNeitherPrunesNorSurvives() {
        val dir = dir()
        (0 until CsvStreamSinks.KEEP).forEach { i ->
            File(dir, "stream-20261020-1331%02d.csv".format(i)).apply {
                writeText("${CsvStreamSinks.HEADER}\n${1_000_000_000L * (i + 1)},7E0,0C,0000,0\n")
                setLastModified(1_000_000L * (i + 1))
            }
        }
        val before = CsvStreamSinks.recordings(dir).map(File::getName)
        CsvStreamSinks(dir, ZoneOffset.UTC).open(Fixtures.EPOCH + 600_000)!!.close()
        assertEquals(before, CsvStreamSinks.recordings(dir).map(File::getName))

        // a header-only file - left by an earlier build - is passed over, not taken as «no recording»
        File(dir, "stream-20261020-140000.csv").apply {
            writeText("${CsvStreamSinks.HEADER}\n")
            setLastModified(100_000_000L)
        }
        assertEquals("stream-20261020-133107.csv", File(StreamCsv.latest(dir)!!.path).name)
    }

    /** The oldest recording goes only once the new one holds a line. */
    @Test
    fun aNewRecordingPrunesAtItsFirstLine() {
        val dir = dir()
        val sinks = CsvStreamSinks(dir, ZoneOffset.UTC, keep = 2)
        (0 until 2).forEach { i ->
            sinks.open(Fixtures.EPOCH + i * 1_000L)!!.apply { write(listOf("$i,7E0,0C,0000,0")) }.close()
            File(dir, "stream-20261020-13310$i.csv").setLastModified(1_000_000L * (i + 1))
        }
        val third = sinks.open(Fixtures.EPOCH + 2_000L)!!
        assertEquals(3, CsvStreamSinks.recordings(dir).size)
        third.write(listOf("2,7E0,0C,0000,0"))
        assertEquals(setOf("stream-20261020-133101.csv", "stream-20261020-133102.csv"), CsvStreamSinks.recordings(dir).map(File::getName).toSet())
        third.close()
    }

    /** A sink that takes [lines] writes and then refuses, as a full storage does. */
    private class Refusing(private var lines: Int) : StreamSink {
        var closed = false
        override fun write(lines: List<String>): Boolean = this.lines-- > 0
        override fun close() { closed = true }
    }

    @Test
    fun aRecordingTheStorageRefusesStopsOnItsTab() = runTest {
        val bus = carNow()
        CarNow.scriptEcmIdent(bus)
        Fixtures.ecmAnswers(bus)
        bus.delayMs = 130
        val sink = Refusing(lines = 3)
        val s = session(bus, sinks = StreamSinks { sink })
        s.start()
        runCurrent()
        s.setModuleOpen(0x7E0, true)
        s.setLiveVisible(0x7E0, true)
        s.setLiveTab(GraphScope.Block(0x7E0), true)
        s.setRecording(0x7E0, true)
        assertTrue(s.model.value.modules.getValue(0x7E0).obd!!.live.recording)
        advanceTimeBy(20_000)
        runCurrent()

        val live = s.model.value.modules.getValue(0x7E0).obd!!.live
        assertFalse("a refused write stops the recording", live.recording)
        assertTrue(sink.closed)
        assertNull(live.recordingMs)
        assertNotNull("what it captured is said, stopped", live.recordedMs)
        s.stop()
    }

    /** A recording stopped before its first line left no file: its tab does not say it goes into the report. */
    @Test
    fun aRecordingStoppedBeforeItsFirstLineSaysNothingOnItsTab() = runTest {
        val bus = carNow()
        CarNow.scriptEcmIdent(bus)
        Fixtures.ecmAnswers(bus)
        val sink = Refusing(lines = 100)
        val s = session(bus, sinks = StreamSinks { sink })
        s.start()
        runCurrent()
        s.setModuleOpen(0x7E0, true)
        s.setLiveVisible(0x7E0, true)
        s.setLiveTab(GraphScope.Block(0x7E0), true)
        s.setRecording(0x7E0, true)
        s.setRecording(0x7E0, false)

        val live = s.model.value.modules.getValue(0x7E0).obd!!.live
        assertTrue(sink.closed)
        assertNull(live.recordedMs)
        s.stop()
    }
}
