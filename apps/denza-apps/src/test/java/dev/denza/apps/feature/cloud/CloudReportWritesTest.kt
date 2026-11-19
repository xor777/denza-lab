package dev.denza.apps.feature.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An idle, disconnected client is read every 15 s and every pass ends in an export. Until
 * 2026-11-19 the export time in the text defeated the comparison and each pass rewrote the report,
 * both histories in it.
 */
class CloudReportWritesTest {
    @Test
    fun anAwakeHourOfIdenticalPassesWritesTheReportFourTimesNotTwoHundredForty() {
        val writes = CloudReportWrites()
        var count = 0
        var now = 0L
        while (now < 3_600_000L) {
            if (writes.due(CONTENT, now)) {
                writes.written(CONTENT, now)
                count++
            }
            now += CloudNativeLog.INTERVAL_MS
        }
        assertEquals(4, count)
    }

    @Test
    fun aChangedReportIsWrittenAtOnce() {
        val writes = CloudReportWrites()
        writes.written(CONTENT, 0L)
        assertFalse(writes.due(CONTENT, 15_000L))
        assertTrue(writes.due("$CONTENT\n2026-11-19T10:00:00Z read network=WIFI", 15_000L))
    }

    @Test
    fun aFailedWriteIsTriedAgainOnTheNextChangeOrTheRefresh() {
        val writes = CloudReportWrites()
        assertTrue("never written", writes.due(CONTENT, 0L))
        // The write failed: nothing recorded, so the same content is still due.
        assertTrue(writes.due(CONTENT, 15_000L))
    }

    private companion object {
        const val CONTENT = "request=ON\nadapter=Adapter(gate=WAITING)\nHistory (UTC):\n"
    }
}
