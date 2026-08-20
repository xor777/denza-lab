package dev.denza.apps.feature.adb

enum class AdbStartupPrimaryAction {
    NONE,
    CHECK_ACCESS,
    REQUEST_AUTHORIZATION,
}

enum class AdbStartupEntryAction {
    NONE,
    CHECK_ACCESS,
    START_RUNTIME,
}

data class AdbStartupOverlayModel(
    val visible: Boolean,
    val title: String = "",
    val message: String = "",
    val primaryLabel: String? = null,
    val primaryAction: AdbStartupPrimaryAction = AdbStartupPrimaryAction.NONE,
    val busy: Boolean = false,
    val recoveryAvailable: Boolean = false,
)

/** Maps the low-level ADB handshake into the blocking startup experience. */
object AdbStartupGatePolicy {
    const val SERVICE_INSTRUCTION =
        "Разблокировать доступ к ADB можно только в условиях сервиса при помощи " +
            "официального диагностического компьютера. Пожалуйста, обратитесь в сервис " +
            "для разблокировки доступа к ADB."

    fun entryAction(phase: AdbRescuePhase): AdbStartupEntryAction = when (phase) {
        AdbRescuePhase.UNKNOWN -> AdbStartupEntryAction.CHECK_ACCESS
        AdbRescuePhase.TRUSTED -> AdbStartupEntryAction.START_RUNTIME
        AdbRescuePhase.CHECKING,
        AdbRescuePhase.AUTHORIZATION_REQUIRED,
        AdbRescuePhase.REQUESTING,
        AdbRescuePhase.AWAITING_CONFIRMATION,
        AdbRescuePhase.UNAVAILABLE,
        AdbRescuePhase.ERROR,
        -> AdbStartupEntryAction.NONE
    }

    fun overlay(snapshot: AdbRescueSnapshot): AdbStartupOverlayModel = when (snapshot.phase) {
        AdbRescuePhase.TRUSTED -> AdbStartupOverlayModel(visible = false)
        AdbRescuePhase.UNKNOWN,
        AdbRescuePhase.CHECKING,
        -> AdbStartupOverlayModel(
            // The passive handshake normally completes before the first useful frame. Keep
            // startup visually stable; the root still installs an invisible input shield and
            // does not start any ADB-dependent runtime until TRUSTED.
            visible = false,
        )
        AdbRescuePhase.UNAVAILABLE -> AdbStartupOverlayModel(
            visible = true,
            title = "ADB недоступен",
            message = SERVICE_INSTRUCTION,
            primaryLabel = "Проверить снова",
            primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
        )
        AdbRescuePhase.AUTHORIZATION_REQUIRED -> AdbStartupOverlayModel(
            visible = true,
            title = "Подтвердите доступ к ADB",
            message = "Для работы Denza Apps разрешите системный запрос ADB на экране автомобиля",
            primaryLabel = "Запросить доступ",
            primaryAction = AdbStartupPrimaryAction.REQUEST_AUTHORIZATION,
            recoveryAvailable = true,
        )
        AdbRescuePhase.REQUESTING -> AdbStartupOverlayModel(
            visible = true,
            title = "Отправляем запрос ADB",
            message = "Повторных запросов в фоне не будет",
            busy = true,
        )
        AdbRescuePhase.AWAITING_CONFIRMATION -> AdbStartupOverlayModel(
            visible = true,
            title = "Подтвердите доступ к ADB",
            message = "Разрешите системный запрос на экране автомобиля",
            primaryLabel = "Я подтвердил — проверить",
            primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
            recoveryAvailable = true,
        )
        AdbRescuePhase.ERROR -> AdbStartupOverlayModel(
            visible = true,
            title = "Не удалось проверить ADB",
            message = "Повторите проверку доступа",
            primaryLabel = "Проверить снова",
            primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
        )
    }
}
