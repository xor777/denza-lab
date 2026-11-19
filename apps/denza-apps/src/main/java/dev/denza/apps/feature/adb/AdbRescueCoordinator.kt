package dev.denza.apps.feature.adb

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import dev.denza.apps.R
import dev.denza.apps.adb.AdbProblem
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.core.AppText
import dev.denza.disharebridge.LocalAdbClient
import java.util.concurrent.Executors

enum class AdbRescuePhase {
    UNKNOWN,
    CHECKING,
    TRUSTED,
    AUTHORIZATION_REQUIRED,

    /** A request is out and its transport held: the car's prompt is this app's, and the gate turns. */
    AWAITING_CONFIRMATION,
    UNAVAILABLE,
    ERROR,
}

data class AdbRescueSnapshot(
    val phase: AdbRescuePhase = AdbRescuePhase.UNKNOWN,
    val message: String = AppText.s(R.string.adb_not_checked),
    val details: String? = null,
    val attemptCount: Int = 0,
    val lastAttemptAtMillis: Long = 0L,
    /** What the last reading of Android's own switch said, and therefore what decided the phase. */
    val systemSwitch: AdbSystemSwitch = AdbSystemSwitch.UNKNOWN,
    /**
     * The last failure's own name - `ConnectException`, an unexpected reply - for «Технические
     * сведения» alone. It used to be [details], which the service panel and the gate's recovery
     * dialog print under the message, so a class name stood on the panel the driver opens.
     */
    val lastFailure: String? = null,
    /**
     * The gate a check is looking at again: the phase before it, while [phase] is CHECKING and that
     * phase had a gate up. Null at every other time, and during the first check, which has no gate.
     * The gate stays as it was through the check, with the ring in place of its glyph; taking it
     * down for the check's seconds and putting it back was a flash on every press.
     */
    val recheckOf: AdbRescuePhase? = null,
    /**
     * This car has trusted this app's key before. A port that does not answer is then the firmware
     * closing it at start-up, which Wi-Fi brings back, and not a car that was never opened.
     */
    val trustedBefore: Boolean = false,
    /**
     * The last approval came without «always allow»: adbd let that one connection in and the key
     * stayed untrusted. The gate shows that box, named as the car names it. This process only.
     */
    val alwaysAllowMissed: Boolean = false,
    /**
     * A request went out from this process since the key was last trusted, so the control asks
     * «again»; before one has, it asks for the first time. This process only.
     */
    val asked: Boolean = false,
) {
    /** The control that sends a request now: a refused key, on a car whose prompt can be drawn. */
    val canAsk: Boolean
        get() = phase == AdbRescuePhase.AUTHORIZATION_REQUIRED && systemSwitch != AdbSystemSwitch.DISABLED
}

internal enum class AdbCheckOutcome {
    TRUSTED,
    AUTHORIZATION_REQUIRED,
    UNAVAILABLE,
    ERROR,
}

internal object AdbRescuePolicy {
    /**
     * Requests the app may send by itself in 24 hours: an opening's own and the follow-up after an
     * approval without the box. Every request counts toward it, a press of the control included,
     * because each is an entry the car's queue keeps; the press is the only one it never stops.
     */
    const val DAILY_ASKS = 3
    const val DAY_MS = 24 * 60 * 60 * 1000L

    /** Request times kept in the log: enough for the cap, bounded where the clock cannot bound it. */
    const val ASK_LOG_KEEP = 10

    /** How long a request's transport is held for the driver's answer. */
    const val HOLD_MS = 120_000L

    /** How often a held request looks passively for an approval that came by another transport. */
    const val LOOK_MS = 3_000L

    /** How long a configuration change may take to bring the screen back before it counts as leaving. */
    const val RECREATE_MS = 5_000L

    /**
     * When the looks after an approval run, from the approval: it counts as given without «always
     * allow» only if the last still finds the key refused. The framework answers adbd before it
     * stores the key, on its own handler; a store that lands after a second is not unusual on a
     * busy unit, and taking it for a missed box sends the driver a prompt they already answered.
     */
    val APPROVAL_LOOKS_MS = listOf(0L, 1_000L, 2_500L)

    /** Report words: the technical page's «Последний сбой». */
    const val NO_ANSWER = "нет ответа за 2 мин"
    const val NOT_RECORDED = "запрос не записан в настройки"
    const val UNEXPECTED_REPLY = "неожиданный ответ на проверку"

    /**
     * Что приложение прочитало о системном тумблере отладки — на всех трёх исходах чтения.
     *
     * Раньше здесь была одна строка, и только про выключенный тумблер: включённый и нечитаемый
     * молчали. Молчание приходилось ровно на те два случая, в которых мы НЕ знаем, что с машиной,
     * — то есть экран не говорил ничего именно тогда, когда сказать было важнее всего.
     *
     * Это факт чтения, а не сообщение об ошибке: ни одно из трёх ничего не советует и ни в чём не
     * обвиняет.
     */
    val SYSTEM_SWITCH_OFF_DETAIL: String get() = AppText.s(R.string.adb_switch_off)

    val SYSTEM_SWITCH_ON_DETAIL: String get() = AppText.s(R.string.adb_switch_on)

    val SYSTEM_SWITCH_UNREADABLE_DETAIL: String get() = AppText.s(R.string.adb_switch_unreadable)

    /** The reading of the switch, as the gate and the access row both say it. */
    fun switchReading(systemSwitch: AdbSystemSwitch): String = when (systemSwitch) {
        AdbSystemSwitch.DISABLED -> SYSTEM_SWITCH_OFF_DETAIL
        AdbSystemSwitch.ENABLED -> SYSTEM_SWITCH_ON_DETAIL
        AdbSystemSwitch.UNKNOWN -> SYSTEM_SWITCH_UNREADABLE_DETAIL
    }

    /** The access row's words for a car whose adbd does not answer, or whose switch is off. */
    val NO_LINK: String get() = AppText.s(R.string.adb_no_link)

    /**
     * The requests that count as recent: those of the last 24 hours, and every one dated ahead of
     * the clock. A head unit often boots with its clock behind until it syncs; dropping the entries
     * that clock puts in the future would reset the cap on every such boot. A clock set back so
     * errs toward asking less, never more, and [keep] bounds what it can hold on to.
     */
    fun recent(times: List<Long>, nowMs: Long): List<Long> = times.filter { it > nowMs || nowMs - it < DAY_MS }

    /** The log's times after a request at [nowMs]: the recent ones and it, the last [ASK_LOG_KEEP]. */
    fun keep(times: List<Long>, nowMs: Long): List<Long> = (recent(times, nowMs) + nowMs).takeLast(ASK_LOG_KEEP)

    /** A car that answers, refuses this key, and can never draw the prompt that would fix it. */
    fun systemSwitchOff(previous: AdbRescueSnapshot): AdbRescueSnapshot = previous.copy(
        phase = AdbRescuePhase.UNAVAILABLE,
        recheckOf = null,
        message = NO_LINK,
        details = SYSTEM_SWITCH_OFF_DETAIL,
        systemSwitch = AdbSystemSwitch.DISABLED,
    )

    fun checking(previous: AdbRescueSnapshot): AdbRescueSnapshot = previous.copy(
        phase = AdbRescuePhase.CHECKING,
        message = AppText.s(R.string.adb_checking),
        details = null,
        // A gate that is up is looked at again with its words kept; a check pressed again during a
        // check keeps what is already kept.
        recheckOf = when (previous.phase) {
            in STANDING_GATES -> previous.phase
            AdbRescuePhase.CHECKING -> previous.recheckOf
            else -> null
        },
    )

    /** The phases whose gate stays up while a check looks again. */
    private val STANDING_GATES = setOf(
        AdbRescuePhase.UNAVAILABLE,
        AdbRescuePhase.AUTHORIZATION_REQUIRED,
        AdbRescuePhase.ERROR,
    )

    fun afterCheck(
        previous: AdbRescueSnapshot,
        outcome: AdbCheckOutcome,
        systemSwitch: AdbSystemSwitch,
        failure: String? = null,
    ): AdbRescueSnapshot {
        val base = previous.copy(systemSwitch = systemSwitch, recheckOf = null)
        return when (outcome) {
            // A completed handshake outranks the flag: whatever the switch says, this key is
            // trusted and the transport works.
            AdbCheckOutcome.TRUSTED -> base.copy(
                phase = AdbRescuePhase.TRUSTED,
                message = AppText.s(R.string.adb_trusted),
                details = AppText.s(R.string.adb_trusted_details),
                trustedBefore = true,
                alwaysAllowMissed = false,
                asked = false,
            )
            // A refused key means one of two different things, and only the switch separates them.
            // Off is the service state; enabled and unreadable both keep the request path, because
            // an unreadable flag is not a reason to send a working car to a service centre.
            AdbCheckOutcome.AUTHORIZATION_REQUIRED -> when (systemSwitch) {
                AdbSystemSwitch.DISABLED -> systemSwitchOff(base)
                AdbSystemSwitch.ENABLED,
                AdbSystemSwitch.UNKNOWN,
                -> refused(base).copy(lastFailure = failure ?: base.lastFailure)
            }
            // The note under the message is what the car said about its switch, as on the gate; the
            // failure's own name is the technical page's.
            AdbCheckOutcome.UNAVAILABLE -> base.copy(
                phase = AdbRescuePhase.UNAVAILABLE,
                message = NO_LINK,
                details = switchReading(systemSwitch),
                lastFailure = failure,
            )
            AdbCheckOutcome.ERROR -> base.copy(
                phase = AdbRescuePhase.ERROR,
                message = AppText.s(R.string.adb_access_not_confirmed),
                details = switchReading(systemSwitch),
                lastFailure = failure,
            )
        }
    }

    /** A refused key with no request out: the gate's control is the way forward. */
    fun refused(previous: AdbRescueSnapshot): AdbRescueSnapshot = previous.copy(
        phase = AdbRescuePhase.AUTHORIZATION_REQUIRED,
        recheckOf = null,
        message = AppText.s(R.string.adb_permission_needed),
        details = switchReading(previous.systemSwitch),
    )

    /** A request about to go out: the ring from the moment it is decided. */
    fun asking(previous: AdbRescueSnapshot): AdbRescueSnapshot = previous.copy(
        phase = AdbRescuePhase.AWAITING_CONFIRMATION,
        recheckOf = null,
        message = AppText.s(R.string.adb_gate_awaiting_message),
        details = switchReading(previous.systemSwitch),
    )

    /** The request recorded, with the switch reading that let it through. */
    fun sent(previous: AdbRescueSnapshot, systemSwitch: AdbSystemSwitch, log: AdbAskLog): AdbRescueSnapshot =
        asking(previous.copy(systemSwitch = systemSwitch)).copy(
            attemptCount = log.total,
            lastAttemptAtMillis = log.lastAtMs,
            asked = true,
        )

    fun failureLabel(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        return root.javaClass.simpleName.ifBlank { "UnknownFailure" }
    }
}

/**
 * The app's single owner of access to the car's adbd: passive looks for everyone, and the key
 * request for the foreground opening and the gate's control alone. [AdbAccessCore] decides; this
 * wires it to the car, the clocks and the preferences.
 */
object AdbRescueCoordinator {
    private const val PREFS_NAME = "adb_rescue"
    private const val KEY_ATTEMPT_COUNT = "attempt_count"
    private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
    private const val KEY_ASK_TIMES = "ask_times"
    private const val CHECK_MARKER = "DENZA_ADB_RESCUE_OK"

    /**
     * A note about this code, not a message to anybody driving.
     *
     * It was printed in amber on the service screen and on the recovery dialog, permanently, on
     * every car - a sentence about an unverified code path, with an English word inside a Russian
     * one, that no driver can act on and that never goes away. The support report is where a note
     * like this belongs, and it is the only place it goes now.
     */
    const val QUEUE_RECOVERY_STATUS =
        "очистка системной очереди отключена до проверки на машине"

    private val executor = Executors.newSingleThreadExecutor()

    @Volatile
    private var core: AdbAccessCore? = null

    /** The first call wins; [onChanged] hears every change of the snapshot, from any thread. */
    @Synchronized
    fun initialize(context: Context, onChanged: () -> Unit) {
        if (core != null) return
        val app = context.applicationContext
        core = AdbAccessCore(
            system = AndroidAdbAccessSystem(app),
            store = PreferencesAskStore(app),
            worker = { executor.execute(it) },
            trustedBefore = AdbRestorePreferences(app).trustedBefore,
            listener = onChanged,
        )
    }

    fun snapshot(): AdbRescueSnapshot = core?.current ?: AdbRescueSnapshot()

    /** Requests recorded in the 24 hours before now: counted at the moment of reading. */
    fun requestsLastDay(): Int = core?.requestsLastDay() ?: 0

    /** A passive look: never a prompt. [after] runs once it has answered. */
    fun checkAccess(after: (() -> Unit)? = null) {
        core?.check(after)
    }

    /** The gate's control: one request now. */
    fun ask() {
        core?.ask()
    }

    fun foregroundStarted() {
        core?.foregroundStarted()
    }

    fun foregroundStopped(changingConfigurations: Boolean) {
        core?.foregroundStopped(changingConfigurations)
    }

    private class AndroidAdbAccessSystem(private val app: Context) : AdbAccessSystem {
        override fun passiveCheck(): AdbCheckResult = try {
            val output = DenzaLocalAdb.client(app).shell("printf $CHECK_MARKER")
            if (output.contains(CHECK_MARKER)) {
                AdbCheckResult(AdbCheckOutcome.TRUSTED)
            } else {
                AdbCheckResult(AdbCheckOutcome.ERROR, AdbRescuePolicy.UNEXPECTED_REPLY)
            }
        } catch (_: LocalAdbClient.AuthorizationRequiredException) {
            AdbCheckResult(AdbCheckOutcome.AUTHORIZATION_REQUIRED)
        } catch (error: Exception) {
            requestFailure(error)
        }

        override fun openRequest(cancel: AdbRequestCancel): AdbHeldRequest {
            val cancellation = LocalAdbClient.RequestCancellation()
            cancel.onCancel(cancellation::cancel)
            val request = DenzaLocalAdb.client(app).openAuthorizationRequest(cancellation)
            return object : AdbHeldRequest {
                override val alreadyTrusted: Boolean =
                    request.result == LocalAdbClient.AuthorizationRequestResult.ALREADY_AUTHORIZED

                override fun awaitApproval(waitMs: Int): Boolean = request.awaitApproval(waitMs)

                override fun close() = request.close()
            }
        }

        /** An adbd that does not answer, as opposed to one that answered something unexpected. */
        override fun requestFailure(error: Throwable): AdbCheckResult = AdbCheckResult(
            if (AdbProblem.of(error) == AdbProblem.NO_LINK) AdbCheckOutcome.UNAVAILABLE else AdbCheckOutcome.ERROR,
            AdbRescuePolicy.failureLabel(error),
        )

        // Read as late as possible, so the flag that classifies an outcome is as fresh as it.
        override fun systemSwitch(): AdbSystemSwitch = AdbSystemSwitchReader.read(app)
        override fun wallMs(): Long = System.currentTimeMillis()
        override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
        override fun sleep(ms: Long) = Thread.sleep(ms)
        override fun recordTrusted() = AdbRestore.recordTrusted(app)
    }

    private class PreferencesAskStore(context: Context) : AdbAskStore {
        private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        override fun load(): AdbAskLog = AdbAskLog(
            times = prefs.getString(KEY_ASK_TIMES, null).orEmpty().split(',').mapNotNull { it.toLongOrNull() },
            total = prefs.getInt(KEY_ATTEMPT_COUNT, 0),
            lastAtMs = prefs.getLong(KEY_LAST_ATTEMPT_AT, 0L),
        )

        // Synchronous: a request is a queue entry the car keeps, so it is never sent unrecorded.
        @SuppressLint("UseKtx", "ApplySharedPref")
        override fun save(log: AdbAskLog): Boolean = prefs.edit()
            .putString(KEY_ASK_TIMES, log.times.joinToString(","))
            .putInt(KEY_ATTEMPT_COUNT, log.total)
            .putLong(KEY_LAST_ATTEMPT_AT, log.lastAtMs)
            .commit()
    }
}
