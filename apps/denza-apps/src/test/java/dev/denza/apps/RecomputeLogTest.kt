package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class RecomputeLogTest {
    @Test
    fun `nothing recorded is one row saying so`() {
        assertEquals(listOf(TechnicalRow("Пересчётов", "0")), RecomputeLog().rows(nowMs = 0L))
    }

    @Test
    fun `the totals count the main thread apart and the newest recompute leads`() {
        val log = RecomputeLog()
        log.record(atMs = 1_000L, trigger = "resume", thread = "main", durationUs = 40_000L)
        log.record(atMs = 2_000L, trigger = "mirrors", thread = "mirrors-monitor", durationUs = 2_500L)
        log.record(atMs = 9_000L, trigger = "split", thread = "main", durationUs = 1_200_000L)

        assertEquals(
            listOf(
                TechnicalRow("Пересчётов", "3 · на главном потоке 2"),
                TechnicalRow(
                    "Время",
                    "всего 1,2 с · на главном 1,2 с · в среднем 414 мс · самый долгий 1,2 с",
                ),
                TechnicalRow("split", "1,2 с · main · 1 с назад"),
                TechnicalRow("mirrors", "2,5 мс · mirrors-monitor · 8 с назад"),
                TechnicalRow("resume", "40 мс · main · 9 с назад"),
            ),
            log.rows(nowMs = 10_000L),
        )
    }

    @Test
    fun `the ring keeps the newest and the totals keep everything`() {
        val log = RecomputeLog(capacity = 2)
        repeat(5) { log.record(atMs = it * 1_000L, trigger = "t$it", thread = "w", durationUs = 1_000L) }

        val rows = log.rows(nowMs = 5_000L, shown = 10)

        assertEquals(TechnicalRow("Пересчётов", "5 · на главном потоке 0"), rows.first())
        assertEquals(listOf("t4", "t3"), rows.drop(2).map(TechnicalRow::key))
    }

    @Test
    fun `durations read in the unit that says something`() {
        assertEquals("0,0 мс", RecomputeLog.duration(40L))
        assertEquals("0,4 мс", RecomputeLog.duration(400L))
        assertEquals("9,9 мс", RecomputeLog.duration(9_999L))
        assertEquals("10 мс", RecomputeLog.duration(10_000L))
        assertEquals("999 мс", RecomputeLog.duration(999_999L))
        assertEquals("1,0 с", RecomputeLog.duration(1_000_000L))
        assertEquals("12,3 с", RecomputeLog.duration(12_345_678L))
    }
}
