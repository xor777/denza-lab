package dev.denza.apps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAutostartRetryScheduleTest {
    @Test
    fun `autoload recovery window is finite and reaches one minute`() {
        val delays = RuntimeAutostartRetrySchedule.delaysMillis

        assertEquals(4, delays.size)
        assertTrue(delays.all { it > 0L })
        assertEquals(60_000L, delays.sum())
    }
}
