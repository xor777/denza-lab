package ru.adbgw.gateway

import kotlin.math.min
import kotlin.random.Random

class RetryBackoff(
    private val minimumMillis: Long = 1_000,
    private val maximumMillis: Long = 60_000,
    private val jitterRatio: Double = 0.2,
    private val random: Random = Random.Default,
) {
    private var currentMillis = minimumMillis

    fun nextDelayMillis(): Long {
        val jitter = (currentMillis * jitterRatio).toLong()
        val offset = if (jitter == 0L) 0L else random.nextLong(-jitter, jitter + 1)
        val result = (currentMillis + offset).coerceIn(minimumMillis, maximumMillis)
        currentMillis = min(maximumMillis, currentMillis * 2)
        return result
    }

    fun reset() {
        currentMillis = minimumMillis
    }
}
