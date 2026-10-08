package dev.denza.apps.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeAutostartRetryScheduleTest {
    /**
     * What the coordinator assumes of the moments, rather than the moments: it makes the first
     * attempt itself and posts the rest (`drop(1)`), and it ends the cycle at
     * [RuntimeRecoveryServicePolicy.MAX_DURATION_MILLIS]. A check at or past that would land in a
     * cycle that has already finished. The two numbers used to be copied here, each on its own.
     */
    @Test
    fun `every passive check falls inside the cycle that schedules it`() {
        val moments = RuntimeAutostartRetrySchedule.atMillis
        assertEquals("the first is the coordinator's own attempt, made at once", 0L, moments.first())
        assertTrue("each later than the one before: $moments", moments.zipWithNext().all { (a, b) -> b > a })
        assertTrue(
            "and the last before the cycle's own timeout: $moments",
            moments.last() < RuntimeRecoveryServicePolicy.MAX_DURATION_MILLIS,
        )
    }
}
