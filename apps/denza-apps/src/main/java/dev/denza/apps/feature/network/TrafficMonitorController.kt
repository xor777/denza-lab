package dev.denza.apps.feature.network

import dev.denza.apps.R
import dev.denza.apps.core.AppText
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class TrafficMonitorSnapshot(
    val state: TrafficMonitorState = TrafficMonitorState.UNKNOWN,
    val busy: Boolean = false,
    val error: String? = null,
    val result: String? = null,
) {
    val known: Boolean get() = state == TrafficMonitorState.ENABLED || state == TrafficMonitorState.DISABLED
    val canDisable: Boolean get() = state == TrafficMonitorState.ENABLED && !busy
    val summary: String get() = when {
        busy -> if (known) AppText.s(R.string.network_running_command) else AppText.s(R.string.network_checking_state)
        state == TrafficMonitorState.ENABLED -> AppText.s(R.string.network_monitor_on)
        state == TrafficMonitorState.DISABLED -> AppText.s(R.string.network_monitor_off)
        state == TrafficMonitorState.ABSENT -> AppText.s(R.string.network_monitor_absent)
        else -> AppText.s(R.string.network_check_failed)
    }
}

internal object TrafficMonitorText {
    val TITLE: String get() = AppText.s(R.string.network_title)
    val MONITOR: String get() = AppText.s(R.string.network_monitor)
    val DISABLE: String get() = AppText.s(R.string.network_disable)
    val CHECK: String get() = AppText.s(R.string.network_check)
}

/**
 * One finite operation at a time, including across page reopenings. Construction does nothing;
 * refresh is read-only and a write requires a press plus a fresh, known package state. No saved
 * wish, startup enforcement, shell-write retries or cancellation halfway through verification.
 */
internal class TrafficMonitorController(
    private val executor: Executor,
    private val shell: (String) -> String,
) {
    private val mutable = MutableStateFlow(TrafficMonitorSnapshot())
    val state = mutable.asStateFlow()

    @Synchronized fun refresh() {
        if (mutable.value.busy) return
        mutable.value = TrafficMonitorSnapshot(busy = true)
        executor.execute { mutable.value = TrafficMonitorSnapshot(state = read()) }
    }

    @Synchronized fun disable() {
        val current = mutable.value
        if (!current.canDisable) return
        mutable.value = current.copy(busy = true, error = null, result = null)
        executor.execute {
            val wanted = TrafficMonitorState.DISABLED
            val before = read()
            if (before == wanted) {
                mutable.value = TrafficMonitorSnapshot(state = before,
                    result = AppText.s(R.string.network_already_off))
            } else if (before != TrafficMonitorState.ENABLED) {
                mutable.value = TrafficMonitorSnapshot(state = before,
                    error = if (before == TrafficMonitorState.ABSENT) AppText.s(R.string.network_not_found_not_run)
                        else AppText.s(R.string.network_check_failed_not_run))
            } else {
                val completed = runCatching {
                    TrafficMonitorProtocol.writeCompleted(shell(TrafficMonitorProtocol.disableCommand))
                }.getOrDefault(false)
                // Even after a timeout the command may have reached Android. Never repeat it;
                // read what actually happened, discarding the pre-write value if reading fails.
                val after = read()
                mutable.value = TrafficMonitorSnapshot(
                    state = after,
                    error = when {
                        after == TrafficMonitorState.UNKNOWN -> AppText.s(R.string.network_state_unconfirmed)
                        after != wanted -> AppText.s(R.string.network_disable_failed)
                        !completed -> AppText.s(R.string.network_answer_unconfirmed)
                        else -> null
                    },
                    result = if (completed && after == wanted) {
                        AppText.s(R.string.network_disable_confirmed)
                    } else null,
                )
            }
        }
    }

    private fun read(): TrafficMonitorState =
        runCatching { TrafficMonitorProtocol.parse(shell(TrafficMonitorProtocol.readCommand)) }
            .getOrDefault(TrafficMonitorState.UNKNOWN)
}
