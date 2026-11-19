package dev.denza.apps.feature.adb

import dev.denza.apps.R
import dev.denza.apps.core.AppText
import java.util.Locale

enum class AdbStartupPrimaryAction {
    NONE,
    CHECK_ACCESS,

    /** One request now: the gate's control after a request ended unanswered, or past the daily cap. */
    ASK,
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
    /** Empty where [checkbox] says it all. */
    val message: String = "",
    /**
     * Why this particular car is in this state, when the app can actually tell.
     *
     * [message] is the same sentence for every car in a given state, which is what makes it useless
     * for telling two of them apart: a car whose ADB switch is off and a car that stopped answering
     * both read "ADB недоступен", and the owner is given no way to know which one they are looking
     * at - or to repeat it to whoever they call.
     *
     * It carries a classification and never a failure label. The exception names the coordinator
     * records are worth having, and they are already on the service screen, which is where a name
     * like `ConnectException` means something to the person reading it.
     */
    val details: String? = null,
    /**
     * The box on the car's own prompt, ticked, in the car's words: the last approval came without it.
     * Shown in place of the message and the note - the box is all the driver needs to see.
     */
    val checkbox: String? = null,
    val primaryLabel: String? = null,
    val primaryAction: AdbStartupPrimaryAction = AdbStartupPrimaryAction.NONE,
    val busy: Boolean = false,
    /** The glyph in amber: the next step is the driver's, not the car's. */
    val attention: Boolean = false,
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
     * A passive check signs with the key it has and never submits it, so it costs no prompt. It is
     * skipped only while a check or a request is already on the worker. The request a refused key
     * needs is the foreground opening's ([AdbRescueCoordinator.foregroundStarted]), not this one's:
     * a process can start without anybody at the screen.
     */
    fun entryAction(phase: AdbRescuePhase): AdbStartupEntryAction = when (phase) {
        AdbRescuePhase.TRUSTED -> AdbStartupEntryAction.START_RUNTIME
        AdbRescuePhase.UNKNOWN,
        AdbRescuePhase.AUTHORIZATION_REQUIRED,
        AdbRescuePhase.UNAVAILABLE,
        AdbRescuePhase.ERROR,
        -> AdbStartupEntryAction.CHECK_ACCESS
        AdbRescuePhase.CHECKING,
        AdbRescuePhase.AWAITING_CONFIRMATION,
        -> AdbStartupEntryAction.NONE
    }

    /** [carLocale] is the language the car draws its own prompt in, for the box's words. */
    fun overlay(
        snapshot: AdbRescueSnapshot,
        restore: AdbRestoreSnapshot? = null,
        carLocale: Locale = Locale.getDefault(),
    ): AdbStartupOverlayModel {
        if (snapshot.phase != AdbRescuePhase.TRUSTED && restore?.enabled == true) {
            when (restore.state) {
                AdbRestoreState.WaitingWifi -> return AdbStartupOverlayModel(
                    visible = true,
                    title = AppText.s(R.string.adb_gate_wifi_title),
                    message = AppText.s(R.string.adb_gate_wifi_message),
                    details = AppText.s(R.string.adb_gate_wifi_details),
                    explainerAvailable = true,
                )
                // An attempt is up to about 100 s, and the dashboard behind is blocked all that time:
                // the gate says what is happening and keeps the door to «Сервис». It was hidden here,
                // and a car whose restore could not succeed showed a disabled dashboard with nothing on it.
                AdbRestoreState.Connecting -> return restoring()
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
                // The port is open again and the check that will say TRUSTED has not answered yet:
                // the phase is still the closed port's, and its gate would flash between the two.
                // Only over the phases a closed port leaves - a car that answered and refused, or a
                // request waiting on the driver, is that phase's gate and not a ring.
                is AdbRestoreState.Restored ->
                    if ((snapshot.recheckOf ?: snapshot.phase) in CLOSED_PORT) return restoring()
                else -> Unit
            }
        }
        val recheckOf = snapshot.recheckOf
        if (snapshot.phase == AdbRescuePhase.CHECKING && recheckOf != null) {
            // A check of a gate that is up: the same gate, the ring in place of its glyph and nothing
            // else changed, so the card neither leaves nor resizes. The button stops answering while
            // the ring turns: a press would only send the restore another forcing hint.
            return phaseGate(recheckOf, snapshot, carLocale).copy(busy = true)
        }
        return phaseGate(snapshot.phase, snapshot, carLocale)
    }

    private val CLOSED_PORT = setOf(
        AdbRescuePhase.UNKNOWN,
        AdbRescuePhase.CHECKING,
        AdbRescuePhase.UNAVAILABLE,
        AdbRescuePhase.ERROR,
    )

    private fun restoring() = AdbStartupOverlayModel(
        visible = true,
        title = AppText.s(R.string.adb_gate_failed_title),
        message = AppText.s(R.string.adb_gate_connecting_message),
        busy = true,
        explainerAvailable = true,
    )

    private fun phaseGate(phase: AdbRescuePhase, snapshot: AdbRescueSnapshot, carLocale: Locale): AdbStartupOverlayModel =
        when (phase) {
        AdbRescuePhase.TRUSTED -> AdbStartupOverlayModel(visible = false)
        AdbRescuePhase.UNKNOWN,
        AdbRescuePhase.CHECKING,
        -> AdbStartupOverlayModel(
            // The first passive handshake normally completes before the first useful frame. Keep
            // startup visually stable; the root still installs an invisible input shield and
            // does not start any ADB-dependent runtime until TRUSTED.
            visible = false,
        )
        // A car that trusted this app before and stopped answering is the newer firmware closing
        // the port at start-up; the service centre is for a car that was never opened.
        AdbRescuePhase.UNAVAILABLE -> AdbStartupOverlayModel(
            visible = true,
            title = AppText.s(R.string.adb_gate_unavailable_title),
            message = if (snapshot.trustedBefore) AppText.s(R.string.adb_gate_closed_at_start) else SERVICE_INSTRUCTION,
            details = systemSwitchReading(snapshot.systemSwitch),
            primaryLabel = AppText.s(R.string.adb_gate_check_again),
            primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
            explainerAvailable = true,
        )
        // No request out: the opening's own went unanswered, or the day's were spent. The control
        // sends one now.
        AdbRescuePhase.AUTHORIZATION_REQUIRED -> confirm(snapshot, AppText.s(R.string.adb_gate_not_granted), carLocale).copy(
            primaryLabel = AppText.s(R.string.adb_gate_ask_again),
            primaryAction = AdbStartupPrimaryAction.ASK,
            attention = true,
        )
        // A request is out and its transport held; the gate leaves by itself on the approval.
        AdbRescuePhase.AWAITING_CONFIRMATION ->
            confirm(snapshot, AppText.s(R.string.adb_gate_awaiting_message), carLocale).copy(busy = true)
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

    /** «Подтвердите доступ к ADB» with [message], or with the box the last approval left unticked. */
    private fun confirm(snapshot: AdbRescueSnapshot, message: String, carLocale: Locale): AdbStartupOverlayModel =
        if (snapshot.alwaysAllowMissed) {
            AdbStartupOverlayModel(
                visible = true,
                title = AppText.s(R.string.adb_gate_confirm_title),
                checkbox = AdbAlwaysAllowLabel.of(carLocale),
                explainerAvailable = true,
            )
        } else {
            AdbStartupOverlayModel(
                visible = true,
                title = AppText.s(R.string.adb_gate_confirm_title),
                message = message,
                details = systemSwitchReading(snapshot.systemSwitch),
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
     * Ни одна из трёх строк ничего не советует: это показание, не диагноз и не отказ.
     */
    private fun systemSwitchReading(systemSwitch: AdbSystemSwitch): String =
        AdbRescuePolicy.switchReading(systemSwitch)
}

/**
 * A bounded autoload retry may repeat passive checks, but never requests a new ADB key.
 *
 * A refused key is looked at too: a prompt answered with «always allow» after its request was
 * closed - the driver left, the hold ran out - trusts the key with nobody watching, and a wake is
 * when a car with no one at the screen notices it. A look is free: it never offers the key.
 */
object AdbAutostartRetryPolicy {
    fun action(phase: AdbRescuePhase): AdbAutostartRetryAction = when (phase) {
        AdbRescuePhase.UNKNOWN,
        AdbRescuePhase.UNAVAILABLE,
        AdbRescuePhase.ERROR,
        AdbRescuePhase.AUTHORIZATION_REQUIRED,
        -> AdbAutostartRetryAction.CHECK_ACCESS
        AdbRescuePhase.TRUSTED -> AdbAutostartRetryAction.START_RUNTIME
        AdbRescuePhase.CHECKING,
        AdbRescuePhase.AWAITING_CONFIRMATION,
        -> AdbAutostartRetryAction.NONE
    }
}
