package dev.denza.apps

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single write boundary for dashboard state.
 *
 * [update] and [updateIf] may run their callbacks more than once when writers race, so callers
 * must calculate Android reads and perform side effects before or after them, never inside them.
 */
internal class DenzaUiStateStore(
    initialState: DenzaUiState = DenzaUiState(),
) {
    /** Exact commit token; its revision prevents equal-valued ABA states from being interchangeable. */
    internal class Snapshot internal constructor(
        val state: DenzaUiState,
        internal val revision: Long,
    )

    private val commitLock = Any()
    private val mutableState = MutableStateFlow(initialState)

    @Volatile
    private var current = Snapshot(initialState, revision = 0L)

    val state: StateFlow<DenzaUiState> = mutableState.asStateFlow()

    fun snapshot(): Snapshot = current

    fun update(transform: (DenzaUiState) -> DenzaUiState) {
        while (true) {
            val expected = current
            if (commit(expected, transform(expected.state))) return
        }
    }

    fun updateIf(
        predicate: (DenzaUiState) -> Boolean,
        transform: (DenzaUiState) -> DenzaUiState,
    ): Boolean {
        while (true) {
            val expected = current
            if (!predicate(expected.state)) return false
            if (commit(expected, transform(expected.state))) return true
        }
    }

    /**
     * Commits a compound decision only if the state used to make it is still current.
     *
     * This is intentionally narrower than exposing the mutable flow. Callers retry a pure
     * decision and start any resulting operation only after this method succeeds.
     */
    fun compareAndSet(expected: Snapshot, updated: DenzaUiState): Boolean =
        commit(expected, updated)

    private fun commit(expected: Snapshot, updated: DenzaUiState): Boolean =
        synchronized(commitLock) {
            if (current !== expected) return@synchronized false
            current = Snapshot(updated, revision = expected.revision + 1L)
            mutableState.value = updated
            true
        }
}
