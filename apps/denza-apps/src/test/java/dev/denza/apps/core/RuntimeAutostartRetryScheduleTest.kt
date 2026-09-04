package dev.denza.apps.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeAutostartRetryScheduleTest {
    @Test
    fun `passive checks happen at the bounded boot moments`() {
        assertEquals(
            listOf(0L, 4_000L, 8_000L, 16_000L, 32_000L),
            RuntimeAutostartRetrySchedule.atMillis,
        )
    }

    @Test
    fun `foreground recovery window is capped at one minute`() {
        assertEquals(60_000L, RuntimeRecoveryServicePolicy.MAX_DURATION_MILLIS)
    }
}
