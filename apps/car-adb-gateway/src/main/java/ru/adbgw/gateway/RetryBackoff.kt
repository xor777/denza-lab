package ru.adbgw.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.random.Random

class RetryBackoff(
    private val minimumMillis: Long = 1_000,
    // 15 s, not a minute: a network that appears without Android telling us (no callback)
    // still gets an attempt within 15 s.
    private val maximumMillis: Long = 15_000,
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

/**
 * Paces the relay loop between attempts. After a failure the loop waits out [RetryBackoff]; while
 * the relay still holds the previous tunnel's port it waits a short fixed [portBusyRetryMillis]
 * instead, for at most [portBusyWindowMillis] after the first such refusal, then the backoff takes
 * over. [wake] (a network change, a network that became usable, an explicit start) restarts the
 * backoff and ends a pause at once.
 */
class RelayRetryPacer(
    private val backoff: RetryBackoff = RetryBackoff(),
    private val portBusyRetryMillis: Long = PORT_BUSY_RETRY_MILLIS,
    private val portBusyWindowMillis: Long = PORT_BUSY_WINDOW_MILLIS,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val wakeups = MutableStateFlow(0L)
    private var portBusySinceMillis: Long? = null

    /** The wake count now. A [pause] given this mark ends as soon as a later wake arrives. */
    fun mark(): Long = wakeups.value

    /** Retry now: restarts the backoff and ends the current or next pause. Any thread. */
    fun wake() {
        restart()
        wakeups.update { it + 1 }
    }

    /** The tunnel is up: the next failure starts again from the shortest delay. */
    fun connected() = restart()

    /** The pause before the next attempt after a failed one. */
    @Synchronized
    fun nextDelayMillis(portBusy: Boolean): Long {
        if (portBusy) {
            val now = clock()
            val since = portBusySinceMillis ?: now.also { portBusySinceMillis = it }
            if (now - since < portBusyWindowMillis) return portBusyRetryMillis
        }
        return backoff.nextDelayMillis()
    }

    /** Waits [millis], or less when a wake comes after [mark]. True when a wake ended it. */
    suspend fun pause(millis: Long, mark: Long): Boolean =
        withTimeoutOrNull(millis) { wakeups.first { it != mark } } != null

    @Synchronized
    private fun restart() {
        backoff.reset()
        portBusySinceMillis = null
    }

    companion object {
        const val PORT_BUSY_RETRY_MILLIS = 2_500L
        const val PORT_BUSY_WINDOW_MILLIS = 30_000L
    }
}
