package dev.denza.apps.feature.navigation

object YandexPackagePolicy {
    const val NAVIGATOR = "ru.yandex.yandexnavi"

    fun isAllowed(packageName: String): Boolean = packageName == NAVIGATOR
}

enum class NavigationPhase {
    READY,
    OPENING,
    PROJECTING,
    PROJECTED,
    RETURNING,
    RECOVERING,
    NEEDS_ACTION,
}

data class NavigationSession(
    val phase: NavigationPhase = NavigationPhase.READY,
    val taskId: Int? = null,
    val virtualDisplayId: Int? = null,
    val message: String = "",
    val details: String? = null,
) {
    val buttonLabel: String
        get() = when (phase) {
            NavigationPhase.PROJECTED, NavigationPhase.RETURNING -> "Вернуть"
            NavigationPhase.PROJECTING, NavigationPhase.RECOVERING -> "Проверяю"
            else -> if (taskId == null) "Открыть Яндекс" else "На приборку"
        }
}

object NavigationRecovery {
    fun proxyLost(session: NavigationSession): NavigationSession =
        if (session.phase == NavigationPhase.PROJECTED || session.virtualDisplayId != null) {
            session.copy(
                phase = NavigationPhase.RECOVERING,
                message = "Безопасно возвращаю навигацию",
                details = "shell proxy disconnected",
            )
        } else {
            NavigationSession(message = "Соединение восстановится при запуске")
        }
}
