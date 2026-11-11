package dev.denza.apps.feature.adb

import dev.denza.apps.R
import dev.denza.apps.core.AppText

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

enum class AdbAutostartRetryAction {
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
    val SERVICE_INSTRUCTION: String get() = AppText.s(R.string.adb_gate_service_instruction)

    /**
     * What opening the app does about access: a passive look in every phase that is not settled.
     *
     * Only UNKNOWN used to be checked, and that left a hole exactly where it hurt. A request the
     * owner approved, with «always allow», on a car that then slept before «Я подтвердил —
     * проверить» was pressed, brought every later process up in AWAITING_CONFIRMATION, and nothing
     * ever looked again: the key was trusted and no feature started. A passive check signs with the
     * key it has and never submits it, so it costs no prompt and no attempt. It is skipped only
     * while a check or the request is already in flight.
     */
    fun entryAction(phase: AdbRescuePhase): AdbStartupEntryAction = when (phase) {
        AdbRescuePhase.TRUSTED -> AdbStartupEntryAction.START_RUNTIME
        AdbRescuePhase.UNKNOWN,
        AdbRescuePhase.AUTHORIZATION_REQUIRED,
        AdbRescuePhase.AWAITING_CONFIRMATION,
        AdbRescuePhase.UNAVAILABLE,
        AdbRescuePhase.ERROR,
        -> AdbStartupEntryAction.CHECK_ACCESS
        AdbRescuePhase.CHECKING,
        AdbRescuePhase.REQUESTING,
        -> AdbStartupEntryAction.NONE
    }

    fun overlay(snapshot: AdbRescueSnapshot, restore: AdbRestoreSnapshot? = null): AdbStartupOverlayModel {
        if (snapshot.phase != AdbRescuePhase.TRUSTED && restore?.enabled == true) {
            when (restore.state) {
                AdbRestoreState.WaitingWifi -> return AdbStartupOverlayModel(
                    visible = true,
                    title = AppText.s(R.string.adb_gate_wifi_title),
                    message = AppText.s(R.string.adb_gate_wifi_message),
                    details = AppText.s(R.string.adb_gate_wifi_details),
                    explainerAvailable = true,
                )
                AdbRestoreState.Connecting -> return AdbStartupOverlayModel(visible = false)
                AdbRestoreState.NeedsDialog -> return AdbStartupOverlayModel(
                    visible = true,
                    title = AppText.s(R.string.adb_gate_dialog_title),
                    message = AppText.s(R.string.adb_gate_dialog_message),
                    explainerAvailable = true,
                )
                is AdbRestoreState.Failed -> return AdbStartupOverlayModel(
                    visible = true,
                    title = AppText.s(R.string.adb_gate_failed_title),
                    message = AppText.s(R.string.adb_gate_failed_message),
                    primaryLabel = AppText.s(R.string.adb_check_access),
                    primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
                    explainerAvailable = true,
                )
                else -> Unit
            }
        }
        return when (snapshot.phase) {
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
            title = AppText.s(R.string.adb_gate_unavailable_title),
            message = SERVICE_INSTRUCTION,
            details = systemSwitchReading(snapshot.systemSwitch),
            primaryLabel = AppText.s(R.string.adb_gate_check_again),
            primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
            explainerAvailable = true,
        )
        AdbRescuePhase.AUTHORIZATION_REQUIRED -> AdbStartupOverlayModel(
            visible = true,
            title = AppText.s(R.string.adb_gate_confirm_title),
            message = AppText.s(R.string.adb_gate_authorize_message),
            details = systemSwitchReading(snapshot.systemSwitch),
            primaryLabel = AppText.s(R.string.adb_gate_request),
            primaryAction = AdbStartupPrimaryAction.REQUEST_AUTHORIZATION,
            recoveryAvailable = true,
            explainerAvailable = true,
        )
        AdbRescuePhase.REQUESTING -> AdbStartupOverlayModel(
            visible = true,
            title = AppText.s(R.string.adb_gate_requesting_title),
            message = AppText.s(R.string.adb_gate_requesting_message),
            busy = true,
            explainerAvailable = true,
        )
        AdbRescuePhase.AWAITING_CONFIRMATION -> AdbStartupOverlayModel(
            visible = true,
            title = AppText.s(R.string.adb_gate_confirm_title),
            message = AppText.s(R.string.adb_gate_awaiting_message),
            details = systemSwitchReading(snapshot.systemSwitch),
            primaryLabel = AppText.s(R.string.adb_gate_confirmed_check),
            primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
            recoveryAvailable = true,
            explainerAvailable = true,
        )
        // A state, and the button under it is the check again. «Не удалось проверить ADB» over
        // «Повторите проверку доступа» was a failure and an instruction the button already is.
        AdbRescuePhase.ERROR -> AdbStartupOverlayModel(
            visible = true,
            title = AppText.s(R.string.adb_access_not_confirmed),
            message = AppText.s(R.string.adb_gate_error_message),
            details = systemSwitchReading(snapshot.systemSwitch),
            primaryLabel = AppText.s(R.string.adb_gate_check_again),
            primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
            explainerAvailable = true,
        )
        }
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
    private fun systemSwitchReading(systemSwitch: AdbSystemSwitch): String =
        AdbRescuePolicy.switchReading(systemSwitch)
}

/**
 * A bounded autoload retry may repeat passive checks, but never requests a new ADB key.
 *
 * AWAITING_CONFIRMATION is checked because an approval can land at any moment, and every sleep
 * kills the process: a wake is the only time a car with no one at the screen gets to notice it. A
 * plain refusal (AUTHORIZATION_REQUIRED) is not: nothing was submitted, so nothing can approve it.
 */
object AdbAutostartRetryPolicy {
    fun action(phase: AdbRescuePhase): AdbAutostartRetryAction = when (phase) {
        AdbRescuePhase.UNKNOWN,
        AdbRescuePhase.UNAVAILABLE,
        AdbRescuePhase.ERROR,
        AdbRescuePhase.AWAITING_CONFIRMATION,
        -> AdbAutostartRetryAction.CHECK_ACCESS
        AdbRescuePhase.TRUSTED -> AdbAutostartRetryAction.START_RUNTIME
        AdbRescuePhase.CHECKING,
        AdbRescuePhase.AUTHORIZATION_REQUIRED,
        AdbRescuePhase.REQUESTING,
        -> AdbAutostartRetryAction.NONE
    }
}
