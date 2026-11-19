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

/**
 * What the car's access is, as the gate and the service row both say it. [AdbStartupGatePolicy.view]
 * is the one decision, in one order; the gate's card and the row's colour, words and buttons are
 * drawn from it and from nothing else.
 */
enum class AdbAccessView {
    /** The key is trusted: no gate, and the row may say that everything works. */
    TRUSTED,

    /** The process's first look: no gate yet, only the invisible shield, and the row waits. */
    FIRST_CHECK,

    /** A request is out and its prompt on the car's screen: the ring. */
    REQUEST_ON_SCREEN,

    /** A look ended with Android's switch read off: the service's sentence and the check again. */
    SWITCH_OFF,
    RESTORE_WIFI,
    RESTORE_CONNECTING,
    RESTORE_DIALOG,
    RESTORE_FAILED,
    UNAVAILABLE,
    NOT_GRANTED,
    NOT_CONFIRMED,
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

    /**
     * The one decision of what access is, in this order:
     *
     * 1. trusted - nothing to show;
     * 2. a request on the car's screen - its prompt is up now, and nothing may cover the one thing
     *    the driver has to answer, a restore state left over from before it least of all;
     * 3. a look that ended with the switch read off - the answer itself, which no restore changes,
     *    with its own sentence and the check again;
     * 4. the restore at work: waiting for Wi-Fi, connecting (also a restore that reopened the port
     *    before the look that will say trusted), waiting for the network dialog, failed;
     * 5. the phase's own gate; the process's first look has none.
     *
     * A check of a gate that is up is that gate: [AdbRescueSnapshot.recheckOf] is what it shows.
     */
    fun view(snapshot: AdbRescueSnapshot, restore: AdbRestoreSnapshot? = null): AdbAccessView {
        val shown = snapshot.recheckOf ?: snapshot.phase
        val restoring = restore?.takeIf { it.enabled }?.state
        return when {
            snapshot.phase == AdbRescuePhase.TRUSTED -> AdbAccessView.TRUSTED
            snapshot.phase == AdbRescuePhase.AWAITING_CONFIRMATION -> AdbAccessView.REQUEST_ON_SCREEN
            shown in STANDING && snapshot.systemSwitch == AdbSystemSwitch.DISABLED -> AdbAccessView.SWITCH_OFF
            restoring == AdbRestoreState.WaitingWifi -> AdbAccessView.RESTORE_WIFI
            restoring == AdbRestoreState.Connecting -> AdbAccessView.RESTORE_CONNECTING
            restoring == AdbRestoreState.NeedsDialog -> AdbAccessView.RESTORE_DIALOG
            restoring is AdbRestoreState.Failed -> AdbAccessView.RESTORE_FAILED
            restoring is AdbRestoreState.Restored && shown in CLOSED_PORT -> AdbAccessView.RESTORE_CONNECTING
            shown == AdbRescuePhase.UNAVAILABLE -> AdbAccessView.UNAVAILABLE
            shown == AdbRescuePhase.AUTHORIZATION_REQUIRED -> AdbAccessView.NOT_GRANTED
            shown == AdbRescuePhase.ERROR -> AdbAccessView.NOT_CONFIRMED
            else -> AdbAccessView.FIRST_CHECK
        }
    }

    /** [carLocale] is the language the car draws its own prompt in, for the box's words. */
    fun overlay(
        snapshot: AdbRescueSnapshot,
        restore: AdbRestoreSnapshot? = null,
        carLocale: Locale = Locale.getDefault(),
    ): AdbStartupOverlayModel {
        val gate = when (view(snapshot, restore)) {
            AdbAccessView.TRUSTED -> AdbStartupOverlayModel(visible = false)
            // The first passive handshake normally completes before the first useful frame. Keep
            // startup visually stable; the root still installs an invisible input shield and does
            // not start any ADB-dependent runtime until TRUSTED.
            AdbAccessView.FIRST_CHECK -> AdbStartupOverlayModel(visible = false)
            // A request is out and its transport held; the gate leaves by itself on the approval.
            AdbAccessView.REQUEST_ON_SCREEN -> confirm(
                snapshot,
                AppText.s(R.string.adb_gate_confirm_title),
                AppText.s(R.string.adb_gate_awaiting_message),
                carLocale,
            ).copy(busy = true)
            AdbAccessView.SWITCH_OFF, AdbAccessView.UNAVAILABLE -> AdbStartupOverlayModel(
                visible = true,
                title = AppText.s(R.string.adb_gate_unavailable_title),
                message = unavailableMessage(snapshot, restore),
                details = systemSwitchReading(snapshot.systemSwitch),
                primaryLabel = AppText.s(R.string.adb_gate_check_again),
                primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
                explainerAvailable = true,
            )
            AdbAccessView.RESTORE_WIFI -> AdbStartupOverlayModel(
                visible = true,
                title = AppText.s(R.string.adb_gate_wifi_title),
                message = AppText.s(R.string.adb_gate_wifi_message),
                details = AppText.s(R.string.adb_gate_wifi_details),
                explainerAvailable = true,
            )
            // An attempt is up to about 100 s, and the dashboard behind is blocked all that time:
            // the gate says what is happening and keeps the door to «Сервис».
            AdbAccessView.RESTORE_CONNECTING -> restoring()
            AdbAccessView.RESTORE_DIALOG -> AdbStartupOverlayModel(
                visible = true,
                title = AppText.s(R.string.adb_gate_dialog_title),
                message = AppText.s(R.string.adb_gate_dialog_message),
                explainerAvailable = true,
            )
            AdbAccessView.RESTORE_FAILED -> AdbStartupOverlayModel(
                visible = true,
                title = AppText.s(R.string.adb_gate_failed_title),
                message = AppText.s(R.string.adb_gate_failed_message),
                primaryLabel = AppText.s(R.string.adb_check_access),
                primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
                explainerAvailable = true,
            )
            // No request out: the opening's own went unanswered or was denied, or the day's were
            // spent. Nothing on the car is waiting to be confirmed, so the title is the state; the
            // control sends one request now.
            AdbAccessView.NOT_GRANTED ->
                confirm(snapshot, AppText.s(R.string.adb_gate_not_granted), message = "", carLocale).copy(
                    primaryLabel = askLabel(snapshot),
                    primaryAction = AdbStartupPrimaryAction.ASK,
                    attention = true,
                )
            // A state, and the button under it is the check again.
            AdbAccessView.NOT_CONFIRMED -> AdbStartupOverlayModel(
                visible = true,
                title = AppText.s(R.string.adb_access_not_confirmed),
                message = AppText.s(R.string.adb_gate_error_message),
                details = systemSwitchReading(snapshot.systemSwitch),
                primaryLabel = AppText.s(R.string.adb_gate_check_again),
                primaryAction = AdbStartupPrimaryAction.CHECK_ACCESS,
                explainerAvailable = true,
            )
        }
        // A check while a gate with a control is up: the same gate, the ring in place of its glyph
        // and nothing else changed, so the card neither leaves nor resizes. The button stops
        // answering while the ring turns.
        val checking = snapshot.phase == AdbRescuePhase.CHECKING && gate.primaryAction != AdbStartupPrimaryAction.NONE
        return if (checking) gate.copy(busy = true) else gate
    }

    /**
     * The control that sends a request: «Запросить снова» once one went out from this process and
     * ended unanswered, «Запросить доступ» while none has - the day's spent at the opening, a panel
     * opened before anything was sent.
     */
    fun askLabel(snapshot: AdbRescueSnapshot): String =
        AppText.s(if (snapshot.asked) R.string.adb_gate_ask_again else R.string.adb_gate_ask)

    /**
     * The sentence for a car whose port does not answer. «Newer firmware closes ADB at start, access
     * comes back over Wi-Fi» only where that is what will happen: a car that trusted the app before,
     * whose switch is not off, with the restore on and able to act. A switch read off is the answer
     * itself, and goes to the service; so does a car never trusted. A car trusted before whose
     * restore cannot bring the port back gets no sentence: the switch reading under the title is
     * what is known.
     */
    private fun unavailableMessage(snapshot: AdbRescueSnapshot, restore: AdbRestoreSnapshot?): String = when {
        snapshot.systemSwitch == AdbSystemSwitch.DISABLED -> SERVICE_INSTRUCTION
        !snapshot.trustedBefore -> SERVICE_INSTRUCTION
        restore.restoresThePort() -> AppText.s(R.string.adb_gate_closed_at_start)
        else -> ""
    }

    private fun AdbRestoreSnapshot?.restoresThePort(): Boolean =
        this != null && enabled && state != AdbRestoreState.Disabled && state != AdbRestoreState.Unsupported &&
            state !is AdbRestoreState.NeedsActivation

    /** The phases whose gate is the answer of a look: a check of one keeps it up. */
    private val STANDING = setOf(
        AdbRescuePhase.UNAVAILABLE,
        AdbRescuePhase.AUTHORIZATION_REQUIRED,
        AdbRescuePhase.ERROR,
    )

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

    /** [title] with [message] and the switch reading, or with the box the last approval left unticked. */
    private fun confirm(snapshot: AdbRescueSnapshot, title: String, message: String, carLocale: Locale): AdbStartupOverlayModel =
        if (snapshot.alwaysAllowMissed) {
            AdbStartupOverlayModel(
                visible = true,
                title = title,
                checkbox = AdbAlwaysAllowLabel.of(carLocale),
                explainerAvailable = true,
            )
        } else {
            AdbStartupOverlayModel(
                visible = true,
                title = title,
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
