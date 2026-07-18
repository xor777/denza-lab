package dev.denza.apps.feature.navigation

data class NavigationAppDefinition(
    val packageName: String,
    val fallbackLabel: String,
)

object NavigationAppPolicy {
    const val DEFAULT_PACKAGE = "ru.yandex.yandexnavi"

    val supported = listOf(
        NavigationAppDefinition(DEFAULT_PACKAGE, "Яндекс Навигатор"),
        NavigationAppDefinition("ru.yandex.yandexmaps", "Яндекс Карты"),
        NavigationAppDefinition("com.google.android.apps.maps", "Google Maps"),
        NavigationAppDefinition("com.waze", "Waze"),
        NavigationAppDefinition("ru.dublgis.dgismobile", "2ГИС"),
    )

    fun isAllowed(packageName: String): Boolean = supported.any { it.packageName == packageName }

    fun fallbackLabel(packageName: String): String =
        supported.firstOrNull { it.packageName == packageName }?.fallbackLabel ?: "Навигация"
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
            else -> if (taskId == null) "Открыть" else "На приборку"
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
