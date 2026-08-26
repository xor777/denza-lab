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
    /**
     * Why this particular car is in this state, when the app can actually tell.
     *
     * [message] is the same sentence for every car in a given state, which is what makes it useless
     * for telling two of them apart: a car whose ADB switch is off and a car that stopped answering
     * both read "ADB недоступен" and are both sent to a service, and the owner is given no way to
     * know which one they are looking at - or to repeat it to whoever they call.
     *
     * It carries a classification and never a failure label. The exception names the coordinator
     * records are worth having, and they are already on the service screen, which is where a name
     * like `ConnectException` means something to the person reading it.
     */
    val details: String? = null,
    val primaryLabel: String? = null,
    val primaryAction: AdbStartupPrimaryAction = AdbStartupPrimaryAction.NONE,
    val busy: Boolean = false,
    val recoveryAvailable: Boolean = false,
    /**
     * Whether the gate offers [AdbExplainer], and through it the service screen.
     *
     * True in every state that blocks, which is the whole point of it: the gate covers the
     * dashboard, the dashboard holds the only other door to diagnostics, and a car that cannot show
     * its own readings when something is wrong has them exactly when they are of no use.
     */
    val explainerAvailable: Boolean = false,
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
            details = unavailableCause(snapshot.systemSwitch),
            primaryLabel = "Проверить снова",
            primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
            explainerAvailable = true,
        )
        AdbRescuePhase.AUTHORIZATION_REQUIRED -> AdbStartupOverlayModel(
            visible = true,
            title = "Подтвердите доступ к ADB",
            message = "Для работы Denza Apps разрешите системный запрос ADB на экране автомобиля",
            primaryLabel = "Запросить доступ",
            primaryAction = AdbStartupPrimaryAction.REQUEST_AUTHORIZATION,
            recoveryAvailable = true,
            explainerAvailable = true,
        )
        AdbRescuePhase.REQUESTING -> AdbStartupOverlayModel(
            visible = true,
            title = "Отправляем запрос ADB",
            message = "Повторных запросов в фоне не будет",
            busy = true,
            explainerAvailable = true,
        )
        AdbRescuePhase.AWAITING_CONFIRMATION -> AdbStartupOverlayModel(
            visible = true,
            title = "Подтвердите доступ к ADB",
            message = "Разрешите системный запрос на экране автомобиля",
            primaryLabel = "Я подтвердил — проверить",
            primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
            recoveryAvailable = true,
            explainerAvailable = true,
        )
        AdbRescuePhase.ERROR -> AdbStartupOverlayModel(
            visible = true,
            title = "Не удалось проверить ADB",
            message = "Повторите проверку доступа",
            primaryLabel = "Проверить снова",
            primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
            explainerAvailable = true,
        )
    }

    /**
     * Which of the two unavailable cars this is, when the switch says so.
     *
     * A refused key on a car whose ADB switch is off is the one failure the app can name outright,
     * because it is a reading and not a guess - and it is also the one the shared service copy
     * above cannot express, since that copy has to hold for a car that simply stopped answering.
     * An unreadable flag stays silent: absence of evidence is not evidence of an off switch, and
     * inventing a cause here would be worse than saying nothing.
     */
    private fun unavailableCause(systemSwitch: AdbSystemSwitch): String? = when (systemSwitch) {
        AdbSystemSwitch.DISABLED -> AdbRescuePolicy.SYSTEM_SWITCH_OFF_DETAIL
        AdbSystemSwitch.ENABLED,
        AdbSystemSwitch.UNKNOWN,
        -> null
    }
}
