package ru.adbgw.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RetryBackoffTest {
    @Test
    fun `backoff grows and caps at sixty seconds`() {
        val backoff = RetryBackoff(jitterRatio = 0.0, random = Random(1))
        val values = List(8) { backoff.nextDelayMillis() }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), values)
    }

    @Test
    fun `reset returns to the minimum`() {
        val backoff = RetryBackoff(jitterRatio = 0.0)
        repeat(5) { backoff.nextDelayMillis() }
        backoff.reset()
        assertEquals(1_000L, backoff.nextDelayMillis())
    }

    @Test
    fun `jitter stays within configured bounds`() {
        val backoff = RetryBackoff(jitterRatio = 0.2, random = Random(9))
        repeat(20) {
            assertTrue(backoff.nextDelayMillis() in 1_000L..60_000L)
        }
    }
}
