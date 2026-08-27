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
            details = systemSwitchReading(snapshot.systemSwitch),
            primaryLabel = "Проверить снова",
            primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
            explainerAvailable = true,
        )
        AdbRescuePhase.AUTHORIZATION_REQUIRED -> AdbStartupOverlayModel(
            visible = true,
            title = "Подтвердите доступ к ADB",
            message = "Для работы Denza Apps разрешите системный запрос ADB на экране автомобиля",
            details = systemSwitchReading(snapshot.systemSwitch),
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
            details = systemSwitchReading(snapshot.systemSwitch),
            primaryLabel = "Я подтвердил — проверить",
            primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
            recoveryAvailable = true,
            explainerAvailable = true,
        )
        AdbRescuePhase.ERROR -> AdbStartupOverlayModel(
            visible = true,
            title = "Не удалось проверить ADB",
            message = "Повторите проверку доступа",
            details = systemSwitchReading(snapshot.systemSwitch),
            primaryLabel = "Проверить снова",
            primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
            explainerAvailable = true,
        )
    }

    /**
     * Что машина ответила про свой тумблер отладки — на каждом экране, где человек застрял.
     *
     * Первая редакция называла только выключенный тумблер, а включённый и нечитаемый оставляла
     * пустыми, и рассуждение было такое: отсутствие свидетельства - не свидетельство выключенного
     * тумблера, выдумывать причину хуже, чем промолчать. Первая половина верна и сейчас. Вторая
     * оказалась неверной: «прочитать не удалось» - это не выдумка, а ровно то, что произошло, и
     * молчали мы именно в тех двух случаях, где сами не знаем ответа.
     *
     * Практическая цена этого молчания известна поимённо. Владелец сообщил о дефекте скриншотом;
     * ответить, его ли это случай, оказалось нечем, потому что на снимке нет ни одного факта о
     * машине - а доступа к той машине у нас нет и не будет. Экран, который честно говорит, что
     * прочитал, отвечает на такой вопрос сам, без инструкций владельцу и без семи тапов.
     *
     * Ни одна из трёх строк ничего не советует: это показание, не диагноз и не отказ.
     */
    private fun systemSwitchReading(systemSwitch: AdbSystemSwitch): String = when (systemSwitch) {
        AdbSystemSwitch.DISABLED -> AdbRescuePolicy.SYSTEM_SWITCH_OFF_DETAIL
        AdbSystemSwitch.ENABLED -> AdbRescuePolicy.SYSTEM_SWITCH_ON_DETAIL
        AdbSystemSwitch.UNKNOWN -> AdbRescuePolicy.SYSTEM_SWITCH_UNREADABLE_DETAIL
    }
}
