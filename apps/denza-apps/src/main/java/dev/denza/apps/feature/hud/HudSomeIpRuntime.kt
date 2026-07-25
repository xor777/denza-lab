package dev.denza.apps.feature.hud

import java.util.concurrent.atomic.AtomicLong

enum class HudSomeIpPhase {
    IDLE,
    BINDING,
    STARTING,
    ACTIVE,
    RECOVERING,
}

data class HudSomeIpSnapshot(
    val phase: HudSomeIpPhase = HudSomeIpPhase.IDLE,
    val lastStartResult: Int? = null,
    val lastFireResult: Int? = null,
    val recoveryAttempts: Long = 0,
    val detail: String = "Ожидаю маршрут",
)

/** Process-local delivery state for support reports; contains no route data. */
object HudSomeIpRuntime {
    private val recoveryAttempts = AtomicLong()

    @Volatile
    private var phase = HudSomeIpPhase.IDLE

    @Volatile
    private var lastStartResult: Int? = null

    @Volatile
    private var lastFireResult: Int? = null

    @Volatile
    private var detail = "Ожидаю маршрут"

    @JvmStatic
    fun onBinding() {
        phase = HudSomeIpPhase.BINDING
        detail = "Подключаю штатный HUD"
    }

    @JvmStatic
    fun onStarting() {
        phase = HudSomeIpPhase.STARTING
        detail = "Запускаю сессию HUD"
    }

    @JvmStatic
    fun onStartResult(result: Int) {
        lastStartResult = result
        if (result == 0) {
            phase = HudSomeIpPhase.ACTIVE
            detail = "Сессия HUD запущена"
        }
    }

    @JvmStatic
    fun onFireResult(result: Int) {
        lastFireResult = result
        if (result == 0) {
            phase = HudSomeIpPhase.ACTIVE
            detail = "Штатный сервис принял подсказку"
        }
    }

    @JvmStatic
    fun onRecovering(reason: String) {
        recoveryAttempts.incrementAndGet()
        phase = HudSomeIpPhase.RECOVERING
        detail = reason
    }

    @JvmStatic
    @JvmOverloads
    fun onIdle(value: String = "Ожидаю маршрут") {
        phase = HudSomeIpPhase.IDLE
        detail = value
    }

    fun snapshot(): HudSomeIpSnapshot = HudSomeIpSnapshot(
        phase = phase,
        lastStartResult = lastStartResult,
        lastFireResult = lastFireResult,
        recoveryAttempts = recoveryAttempts.get(),
        detail = detail,
    )

    internal fun resetForTest() {
        recoveryAttempts.set(0)
        phase = HudSomeIpPhase.IDLE
        lastStartResult = null
        lastFireResult = null
        detail = "Ожидаю маршрут"
    }
}
