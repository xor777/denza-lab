package dev.denza.apps.feature.cloud

import java.net.ConnectException
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-2 finding 10: a car whose local ADB is gone, with the link on and the client not
 * connected, is read every 15 s and every pass fails the same way. The path here is the
 * controller's: the transport's exception becomes the reason ([CloudRepeatedFailures.reason]), the
 * lines go into the history under the UTC time of the pass ([CloudLinkTrace.add], as
 * `CloudLinkDiagnostics.record` does), and the report's content, which carries that history, is
 * offered to [CloudReportWrites] at the end of the pass, as `publish` does.
 */
class CloudRepeatedFailuresTest {
    private val started = Instant.parse("2026-11-19T10:00:00Z")

    private class Pass(val history: CloudLinkTrace, val writes: CloudReportWrites) {
        var reportWrites = 0

        fun record(atMs: Long, lines: List<String>, started: Instant) {
            lines.forEach { history.add(started.plusMillis(atMs).toString(), it) }
            val content = "request=CloudLinkRequest(enabled=true)\nHistory (UTC):\n${history.text()}\n"
            if (writes.due(content, atMs)) {
                writes.written(content, atMs)
                reportWrites += 1
            }
        }
    }

    @Test
    fun anHourWithoutLocalAdbWritesTheHistoryAndTheReportFiveTimesNotTwoHundredForty() {
        val failures = CloudRepeatedFailures()
        val pass = Pass(CloudLinkTrace(), CloudReportWrites())
        var now = 0L
        var stage = 0
        while (now < 3_600_000L) {
            // A fresh exception every pass, met by whichever pass ran: one run all the same.
            val error = ConnectException("Connection refused")
            val reason = CloudRepeatedFailures.reason(error)
            val by = if (stage++ % 3 == 0) "status broadcast" else "tick"
            pass.record(now, failures.failed(by, reason, now), started)
            now += CloudNativeLog.INTERVAL_MS
        }
        pass.record(now, failures.passed("tick"), started)

        val lines = pass.history.text().lines()
        assertEquals("history lines", 5, lines.size)
        assertTrue(lines.first(), lines.first().endsWith("status broadcast failed Нет ответа: ConnectException"))
        assertTrue(lines[1], lines[1].endsWith("(×61 in a row)"))
        assertTrue(lines.last(), lines.last().endsWith("tick passed after ×240 failed in a row (Нет ответа: ConnectException)"))
        assertEquals("first, three refreshes, recovery", 5, pass.reportWrites)
    }

    @Test
    fun aDifferentFailureEndsTheRunWithItsCountAndIsToldAtOnce() {
        val failures = CloudRepeatedFailures()
        assertEquals(listOf("tick failed A"), failures.failed("tick", "A", 0))
        assertEquals(emptyList<String>(), failures.failed("tick", "A", 15_000))
        assertEquals(emptyList<String>(), failures.failed("tick", "A", 30_000))
        assertEquals(
            listOf("the failure before repeated ×3 in a row (A)", "tick failed B"),
            failures.failed("tick", "B", 45_000),
        )
    }

    @Test
    fun aSingleFailureAndItsRecoveryAreBothTold() {
        val failures = CloudRepeatedFailures()
        assertEquals(listOf("follow up failed A"), failures.failed("follow up", "A", 0))
        assertEquals(listOf("tick passed after ×1 failed in a row (A)"), failures.passed("tick"))
        assertEquals("nothing open, nothing told", emptyList<String>(), failures.passed("tick"))
    }

    @Test
    fun aPressStartsANewStory() {
        val failures = CloudRepeatedFailures()
        failures.failed("tick", "A", 0)
        failures.failed("tick", "A", 15_000)
        assertEquals(listOf("the failure before repeated ×2 in a row (A)"), failures.pressed())
        assertEquals(listOf("tick failed A"), failures.failed("tick", "A", 30_000))
    }

    @Test
    fun aRunAlreadyToldInFullEndsWithoutAnotherLine() {
        val failures = CloudRepeatedFailures(refreshMs = 15_000)
        failures.failed("tick", "A", 0)
        assertEquals(listOf("tick failed A (×2 in a row)"), failures.failed("tick", "A", 15_000))
        assertEquals(emptyList<String>(), failures.pressed())
    }

    @Test
    fun onlyOurOwnMessagesAreKeptAsReasons() {
        assertEquals("Профиль не подтвердился после записи",
            CloudRepeatedFailures.reason(IllegalStateException("Профиль не подтвердился после записи")))
        assertEquals("Нет ответа: ConnectException",
            CloudRepeatedFailures.reason(ConnectException("10.0.0.1:5555 VIN LGX…")))
    }
}
