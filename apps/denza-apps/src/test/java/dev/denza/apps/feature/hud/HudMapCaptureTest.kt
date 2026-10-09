package dev.denza.apps.feature.hud

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The opt-in recording: off without its marker, one frame per interval, the newest 200 kept. */
class HudMapCaptureTest {
    private val directory: File = Files.createTempDirectory("hud-map-capture").toFile()
    private var now = 0L
    private var wall = 1_760_000_000_000L

    @After
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun withoutTheMarkerNothingIsDueAndNothingIsWritten() {
        val capture = capture()

        assertFalse(capture.due())
        now += HudMapCapture.INTERVAL_MS * 3
        assertFalse(capture.due())
        assertEquals(0, directory.list()!!.size)
    }

    @Test
    fun withTheMarkerOneFrameIsKeptPerInterval() {
        File(directory, HudMapCapture.MARKER).createNewFile()
        val capture = capture()

        assertTrue(capture.due())
        capture.save(byteArrayOf(1), byteArrayOf(2))
        now += HudMapCapture.INTERVAL_MS - 1
        assertFalse(capture.due())
        now += 1
        assertTrue(capture.due())
        val frames = directory.list()!!.filter { it.endsWith(".png") }.sorted()
        assertEquals(2, frames.size)
        assertTrue(frames[0].endsWith("-analysis.png"))
        assertTrue(frames[1].endsWith("-map.png"))
    }

    @Test
    fun theMarkerIsLookedForAtMostOncePerInterval() {
        val capture = capture()
        assertFalse(capture.due())
        File(directory, HudMapCapture.MARKER).createNewFile()

        now += 1_000
        assertFalse(capture.due())
        now += HudMapCapture.INTERVAL_MS
        assertTrue(capture.due())
    }

    @Test
    fun theOldestFramesGoBeyondTwoHundredFiles() {
        File(directory, HudMapCapture.MARKER).createNewFile()
        val capture = capture()
        repeat(110) {
            capture.save(byteArrayOf(1), byteArrayOf(2))
            now += HudMapCapture.INTERVAL_MS
            wall += HudMapCapture.INTERVAL_MS
        }

        val frames = directory.list()!!.filter { it.endsWith(".png") }.sorted()
        assertEquals(HudMapCapture.MAX_FILES, frames.size)
        // 220 written, the first ten frames' twenty files gone: the newest stays.
        assertTrue(File(directory, HudMapCapture.MARKER).isFile)
        assertEquals(frames.last(), directory.list()!!.filter { it.endsWith("-map.png") }.max())
        assertFalse(frames.any { it.contains(stampOf(1_760_000_000_000L)) })
    }

    private fun capture() = HudMapCapture(directory, { now }, { wall })

    private fun stampOf(ms: Long): String =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS", java.util.Locale.US).format(java.util.Date(ms))
}
