package dev.denza.apps.feature.diagnostics

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

/** One queued DTC cannot be replaced by refresh taps or a periodic tick. */
internal class DiagnosticReadQueue {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var pending: DtcEcu? = null
    private var reading = false
    @Synchronized fun request(ecu: DtcEcu): Boolean {
        if (reading || pending != null) return false
        pending = ecu
        wake.trySend(Unit)
        return true
    }
    fun refresh() { wake.trySend(Unit) }
    suspend fun next(): DtcEcu? {
        withTimeoutOrNull(5_000) { wake.receive() }
        return take()
    }
    @Synchronized private fun take(): DtcEcu? = pending.also {
        pending = null
        reading = it != null
    }
    @Synchronized fun finished() { reading = false }
}
