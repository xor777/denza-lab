package dev.denza.apps.feature.network

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
        busy -> if (known) "Выполняем команду…" else "Проверяем состояние…"
        state == TrafficMonitorState.ENABLED -> "Монитор включён"
        state == TrafficMonitorState.DISABLED -> "Монитор отключён"
        state == TrafficMonitorState.ABSENT -> "Недоступен на этой прошивке"
        else -> "Не удалось проверить"
    }
}

internal object TrafficMonitorText {
    const val TITLE = "Мобильный интернет"
    const val MONITOR = "Штатный контроль трафика"
    const val DISABLE = "Отключить"
    const val CHECK = "Проверить состояние"
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
                    result = "Монитор уже отключён")
            } else if (before != TrafficMonitorState.ENABLED) {
                mutable.value = TrafficMonitorSnapshot(state = before,
                    error = if (before == TrafficMonitorState.ABSENT) "Монитор не найден. Команда не выполнена."
                        else "Не удалось проверить состояние. Команда не выполнена.")
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
                        after == TrafficMonitorState.UNKNOWN -> "Не удалось подтвердить состояние монитора"
                        after != wanted -> "Не удалось отключить монитор"
                        !completed -> "Состояние изменено, но ответ команды не подтверждён"
                        else -> null
                    },
                    result = if (completed && after == wanted) {
                        "Отключение подтверждено"
                    } else null,
                )
            }
        }
    }

    private fun read(): TrafficMonitorState =
        runCatching { TrafficMonitorProtocol.parse(shell(TrafficMonitorProtocol.readCommand)) }
            .getOrDefault(TrafficMonitorState.UNKNOWN)
}
