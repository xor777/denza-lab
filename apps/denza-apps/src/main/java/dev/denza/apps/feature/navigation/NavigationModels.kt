package dev.denza.apps.feature.navigation

import dev.denza.apps.core.FeatureResolution

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
    val resolution: FeatureResolution? = null,
) {
    val buttonLabel: String
        get() = when (phase) {
            NavigationPhase.PROJECTED, NavigationPhase.RETURNING -> "Вернуть"
            NavigationPhase.PROJECTING, NavigationPhase.RECOVERING -> "Проверяю"
            else -> if (taskId == null) "Открыть" else "На приборку"
        }
}

object NavigationRecovery {
    fun shouldRetryAfterClusterSelection(session: NavigationSession): Boolean =
        session.phase == NavigationPhase.NEEDS_ACTION &&
            session.resolution == FeatureResolution.SELECT_CLUSTER_DISPLAY

    fun proxyLost(session: NavigationSession): NavigationSession =
        if (session.phase == NavigationPhase.PROJECTED || session.virtualDisplayId != null) {
            session.copy(
                phase = NavigationPhase.RECOVERING,
                message = "Безопасно возвращаю навигацию",
                details = "shell proxy disconnected",
                resolution = null,
            )
        } else {
            NavigationSession(message = "Соединение восстановится при запуске")
        }
}

internal sealed interface NavigationProjectionHealthDecision {
    data object Healthy : NavigationProjectionHealthDecision

    data class Uncertain(
        val actualDisplayId: Int,
        val confirmationCount: Int,
    ) : NavigationProjectionHealthDecision

    data class ConfirmedElsewhere(
        val actualDisplayId: Int,
    ) : NavigationProjectionHealthDecision
}

/**
 * A projected task may temporarily disappear from getRunningTasks while
 * DiShare creates or removes its mirror displays. A negative observation is
 * therefore never permission to release the display that still owns the task.
 * Only the same positive, different display observed twice is authoritative.
 */
internal class NavigationProjectionHealthTracker(
    private val requiredConfirmations: Int = 2,
) {
    private var candidateDisplayId: Int? = null
    private var confirmationCount = 0

    init {
        require(requiredConfirmations >= 2)
    }

    fun observe(
        actualDisplayId: Int,
        expectedDisplayId: Int,
    ): NavigationProjectionHealthDecision {
        if (actualDisplayId == expectedDisplayId) {
            reset()
            return NavigationProjectionHealthDecision.Healthy
        }
        if (actualDisplayId < 0) {
            reset()
            return NavigationProjectionHealthDecision.Uncertain(
                actualDisplayId = actualDisplayId,
                confirmationCount = 0,
            )
        }
        if (candidateDisplayId != actualDisplayId) {
            candidateDisplayId = actualDisplayId
            confirmationCount = 1
        } else {
            confirmationCount += 1
        }
        if (confirmationCount < requiredConfirmations) {
            return NavigationProjectionHealthDecision.Uncertain(
                actualDisplayId = actualDisplayId,
                confirmationCount = confirmationCount,
            )
        }
        val confirmed = actualDisplayId
        reset()
        return NavigationProjectionHealthDecision.ConfirmedElsewhere(confirmed)
    }

    fun reset() {
        candidateDisplayId = null
        confirmationCount = 0
    }
}

internal enum class NavigationProjectionCleanupDecision {
    RELEASE,
    RETURN_THEN_RELEASE,
    PRESERVE,
}

/**
 * Releasing a live app-owned display is destructive while its task may still
 * be attached. An unknown task location therefore fails closed.
 */
internal fun navigationProjectionCleanupDecision(
    ownedDisplayId: Int?,
    ownedDisplayAlive: Boolean,
    actualTaskDisplayId: Int?,
): NavigationProjectionCleanupDecision {
    if (ownedDisplayId == null || !ownedDisplayAlive) {
        return NavigationProjectionCleanupDecision.RELEASE
    }
    if (actualTaskDisplayId == null || actualTaskDisplayId < 0) {
        return NavigationProjectionCleanupDecision.PRESERVE
    }
    return if (actualTaskDisplayId == ownedDisplayId) {
        NavigationProjectionCleanupDecision.RETURN_THEN_RELEASE
    } else {
        NavigationProjectionCleanupDecision.RELEASE
    }
}
