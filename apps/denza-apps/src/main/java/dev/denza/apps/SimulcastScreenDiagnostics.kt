package dev.denza.apps

import android.content.Context
import android.graphics.Rect
import dev.denza.apps.feature.simulcast.ScreenTarget
import dev.denza.disharebridge.DiShareScreens
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps a human-readable snapshot of the two independent Simulcast discovery
 * layers: DiShare getScreens and the receiver cards visible to accessibility.
 * The snapshot deliberately survives closing the stock dialog so it can be read
 * later from Denza Apps Support.
 */
object SimulcastScreenDiagnostics {
    private const val DISHARE_PACKAGE = "com.byd.dishare"

    private data class ScreenRecord(
        val deviceId: String?,
        val screenId: String?,
        val available: Boolean,
    )

    private val queryRunning = AtomicBoolean(false)

    @Volatile
    private var queryStatus = "ещё не запрашивались"

    @Volatile
    private var screenRecords: List<ScreenRecord> = emptyList()

    @Volatile
    private var screenUpdatedAtMs = 0L

    @Volatile
    private var layoutStatus = "штатное окно Simulcast ещё не наблюдалось"

    @Volatile
    private var targetLines: List<String> = emptyList()

    @Volatile
    private var layoutUpdatedAtMs = 0L

    fun refresh(context: Context, onChanged: () -> Unit) {
        if (!queryRunning.compareAndSet(false, true)) return
        queryStatus = "запрос выполняется"
        onChanged()
        DiShareScreens.query(context.applicationContext, DISHARE_PACKAGE, object : DiShareScreens.Callback {
            override fun onScreens(screens: List<DiShareScreens.Screen>) {
                recordDiShareScreens(screens)
                queryRunning.set(false)
                onChanged()
            }

            override fun onFailed(message: String) {
                recordDiShareFailure(message)
                queryRunning.set(false)
                onChanged()
            }
        })
    }

    @JvmStatic
    fun recordDiShareScreens(screens: List<DiShareScreens.Screen>) {
        screenRecords = screens.map { screen ->
            ScreenRecord(
                deviceId = screen.deviceId,
                screenId = screen.screenId,
                available = screen.available,
            )
        }
        queryStatus = "получено экранов: ${screens.size}"
        screenUpdatedAtMs = System.currentTimeMillis()
    }

    @JvmStatic
    fun recordDiShareFailure(message: String?) {
        queryStatus = "ошибка: ${message?.ifBlank { "без описания" } ?: "без описания"}"
        screenUpdatedAtMs = System.currentTimeMillis()
    }

    @JvmStatic
    fun recordAccessibilityLayout(
        receiverBounds: Map<String, Rect>,
        availableReceiverIds: Set<String>,
        availabilityConfirmed: Boolean,
    ) {
        targetLines = ScreenTarget.SUPPORTED.map { target ->
            val bounds = receiverBounds[target.receiverId]
            val availability = when {
                !availabilityConfirmed -> "проверяется"
                target.receiverId in availableReceiverIds -> "доступен"
                else -> "не объявлен"
            }
            val usable = bounds != null && availabilityConfirmed &&
                target.receiverId in availableReceiverIds
            "Цель ${target.receiverId}=" +
                "узел=${target.viewResourceName}; " +
                "границы=${bounds?.diagnosticString() ?: "не найден"}; " +
                "DiShare=$availability; " +
                "итог=${if (usable) "можно использовать" else "не используется"}"
        }
        layoutStatus = "последний снимок штатного окна"
        layoutUpdatedAtMs = System.currentTimeMillis()
    }

    fun diagnosticLines(nowMs: Long = System.currentTimeMillis()): List<String> = buildList {
        add("DiShare getScreens=$queryStatus${ageSuffix(screenUpdatedAtMs, nowMs)}")
        if (screenRecords.isEmpty() && screenUpdatedAtMs > 0L && !queryStatus.startsWith("ошибка")) {
            add("DiShare receivers=пустой список")
        } else {
            screenRecords.forEachIndexed { index, screen ->
                val id = screen.screenId?.ifBlank { null } ?: "без-id-$index"
                add(
                    "DiShare $id=" +
                        "device=${screen.deviceId?.ifBlank { null } ?: "—"}; " +
                        "available=${if (screen.available) "да" else "нет"}",
                )
            }
        }
        add("Узлы Simulcast=$layoutStatus${ageSuffix(layoutUpdatedAtMs, nowMs)}")
        addAll(targetLines)
    }

    private fun Rect.diagnosticString(): String = "[$left,$top][$right,$bottom]"

    private fun ageSuffix(updatedAtMs: Long, nowMs: Long): String {
        if (updatedAtMs <= 0L) return ""
        val seconds = ((nowMs - updatedAtMs).coerceAtLeast(0L) / 1_000L)
        return "; ${seconds}с назад"
    }
}
