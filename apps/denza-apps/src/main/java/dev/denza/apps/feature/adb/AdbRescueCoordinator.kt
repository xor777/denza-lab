package dev.denza.apps.feature.adb

import android.annotation.SuppressLint
import android.content.Context
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.disharebridge.LocalAdbClient
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

enum class AdbRescuePhase {
    UNKNOWN,
    CHECKING,
    TRUSTED,
    AUTHORIZATION_REQUIRED,
    REQUESTING,
    AWAITING_CONFIRMATION,
    UNAVAILABLE,
    ERROR,
}

data class AdbRescueSnapshot(
    val phase: AdbRescuePhase = AdbRescuePhase.UNKNOWN,
    val message: String = "Доступ ещё не проверен",
    val details: String? = null,
    val requestPending: Boolean = false,
    val attemptCount: Int = 0,
    val lastAttemptAtMillis: Long = 0L,
    /** What the last reading of Android's own switch said, and therefore what decided the phase. */
    val systemSwitch: AdbSystemSwitch = AdbSystemSwitch.UNKNOWN,
) {
    // The single request is also a slot in Android's prompt queue. Never offer to spend it where
    // the system has already said no prompt can be drawn.
    val canRequest: Boolean
        get() = phase == AdbRescuePhase.AUTHORIZATION_REQUIRED && !requestPending &&
            systemSwitch != AdbSystemSwitch.DISABLED

    val canResetAttempt: Boolean
        get() = requestPending && phase != AdbRescuePhase.CHECKING &&
            phase != AdbRescuePhase.REQUESTING
}

internal enum class AdbCheckOutcome {
    TRUSTED,
    AUTHORIZATION_REQUIRED,
    UNAVAILABLE,
    ERROR,
}

internal enum class AdbRequestOutcome {
    ALREADY_TRUSTED,
    REQUEST_SENT,
    UNAVAILABLE,
    ERROR,
}

internal object AdbRescuePolicy {
    /**
     * Что приложение прочитало о системном тумблере отладки — на всех трёх исходах чтения.
     *
     * Раньше здесь была одна строка, и только про выключенный тумблер: включённый и нечитаемый
     * молчали. Молчание приходилось ровно на те два случая, в которых мы НЕ знаем, что с машиной,
     * — то есть экран не говорил ничего именно тогда, когда сказать было важнее всего.
     *
     * Это факт чтения, а не сообщение об ошибке: ни одно из трёх ничего не советует и ни в чём не
     * обвиняет. Хвост «системный запрос не появится» убран — на экране «ADB недоступен» кнопки
     * запроса нет вообще, и фраза говорила про орган, которого там не видно.
     */
    const val SYSTEM_SWITCH_OFF_DETAIL =
        "Отладка по ADB выключена в системе автомобиля"

    const val SYSTEM_SWITCH_ON_DETAIL =
        "Отладка по ADB включена в системе автомобиля"

    const val SYSTEM_SWITCH_UNREADABLE_DETAIL =
        "Состояние отладки по ADB прочитать не удалось"

    /**
     * Whether the single attempt may be spent, given a switch read at the moment of the press.
     *
     * The phase is already checked before this point, so the switch is the only condition that can
     * still refuse here — a car whose flag was unreadable when it was classified, or one switched
     * off between the check and the press.
     */
    fun maySubmit(previous: AdbRescueSnapshot, systemSwitch: AdbSystemSwitch): Boolean =
        previous.canRequest && systemSwitch != AdbSystemSwitch.DISABLED

    /**
     * The state of a car that answers, refuses this key, and can never draw the prompt that would
     * fix it. The one-shot latch and the attempt counter are left exactly as they were: nothing
     * was submitted, so nothing was spent.
     */
    fun systemSwitchOff(previous: AdbRescueSnapshot): AdbRescueSnapshot = previous.copy(
        phase = AdbRescuePhase.UNAVAILABLE,
        message = "Локальный ADB сейчас недоступен",
        details = SYSTEM_SWITCH_OFF_DETAIL,
        systemSwitch = AdbSystemSwitch.DISABLED,
    )

    fun initial(
        requestPending: Boolean,
        attemptCount: Int,
        lastAttemptAtMillis: Long,
    ): AdbRescueSnapshot = if (requestPending) {
        AdbRescueSnapshot(
            phase = AdbRescuePhase.AWAITING_CONFIRMATION,
            message = "Предыдущий запрос ожидает проверки",
            details = "Сначала проверьте доступ; новый запрос автоматически не отправится",
            requestPending = true,
            attemptCount = attemptCount,
            lastAttemptAtMillis = lastAttemptAtMillis,
        )
    } else {
        AdbRescueSnapshot(
            attemptCount = attemptCount,
            lastAttemptAtMillis = lastAttemptAtMillis,
        )
    }

    fun checking(previous: AdbRescueSnapshot): AdbRescueSnapshot = previous.copy(
        phase = AdbRescuePhase.CHECKING,
        message = "Проверяю существующий доступ…",
        details = "Публичный ключ не отправляется",
    )

    fun afterCheck(
        previous: AdbRescueSnapshot,
        outcome: AdbCheckOutcome,
        systemSwitch: AdbSystemSwitch,
        failure: String? = null,
    ): AdbRescueSnapshot {
        val base = previous.copy(systemSwitch = systemSwitch)
        return when (outcome) {
            // A completed handshake outranks the flag: whatever the switch says, this key is
            // trusted and the transport works.
            AdbCheckOutcome.TRUSTED -> base.copy(
                phase = AdbRescuePhase.TRUSTED,
                message = "ADB-доступ подтверждён",
                details = "Denza Apps использует уже доверенный ключ",
                requestPending = false,
            )
            // A refused key means one of two different things, and only the switch separates them.
            // Off is the service state; enabled and unreadable both keep the request path, because
            // an unreadable flag is not a reason to send a working car to a service centre.
            AdbCheckOutcome.AUTHORIZATION_REQUIRED -> when (systemSwitch) {
                AdbSystemSwitch.DISABLED -> systemSwitchOff(base)
                AdbSystemSwitch.ENABLED,
                AdbSystemSwitch.UNKNOWN,
                -> if (base.requestPending) {
                    base.copy(
                        phase = AdbRescuePhase.AWAITING_CONFIRMATION,
                        message = "Запрос отправлен, но доступ ещё не выдан",
                        details = "Разрешите запрос на экране машины, затем нажмите «Проверить доступ»",
                    )
                } else {
                    base.copy(
                        phase = AdbRescuePhase.AUTHORIZATION_REQUIRED,
                        message = "Нужно разрешение ADB для Denza Apps",
                        details = "Можно вручную отправить ровно один запрос",
                    )
                }
            }
            AdbCheckOutcome.UNAVAILABLE -> base.copy(
                phase = AdbRescuePhase.UNAVAILABLE,
                message = "Локальный ADB сейчас недоступен",
                details = failure,
            )
            AdbCheckOutcome.ERROR -> base.copy(
                phase = AdbRescuePhase.ERROR,
                message = "Не удалось проверить ADB",
                details = failure,
            )
        }
    }

    fun requesting(
        previous: AdbRescueSnapshot,
        attemptAtMillis: Long,
        systemSwitch: AdbSystemSwitch,
    ): AdbRescueSnapshot = previous.copy(
        phase = AdbRescuePhase.REQUESTING,
        message = "Отправляю один запрос…",
        details = "Повторов в фоне не будет",
        requestPending = true,
        attemptCount = previous.attemptCount + 1,
        lastAttemptAtMillis = attemptAtMillis,
        // The reading that let the attempt through, recorded where support can see it.
        systemSwitch = systemSwitch,
    )

    fun afterRequest(
        previous: AdbRescueSnapshot,
        outcome: AdbRequestOutcome,
        failure: String? = null,
    ): AdbRescueSnapshot = when (outcome) {
        AdbRequestOutcome.ALREADY_TRUSTED -> previous.copy(
            phase = AdbRescuePhase.TRUSTED,
            message = "ADB-доступ уже выдан",
            details = "Новый запрос не потребовался",
            requestPending = false,
        )
        AdbRequestOutcome.REQUEST_SENT -> previous.copy(
            phase = AdbRescuePhase.AWAITING_CONFIRMATION,
            message = "Один запрос отправлен",
            details = "Разрешите его на экране машины, затем проверьте доступ",
        )
        AdbRequestOutcome.UNAVAILABLE -> previous.copy(
            phase = AdbRescuePhase.UNAVAILABLE,
            message = "Отправка запроса не подтверждена",
            details = failure,
        )
        AdbRequestOutcome.ERROR -> previous.copy(
            phase = AdbRescuePhase.ERROR,
            message = "Отправка запроса не подтверждена",
            details = failure,
        )
    }

    fun resetAttempt(previous: AdbRescueSnapshot): AdbRescueSnapshot = previous.copy(
        phase = AdbRescuePhase.UNKNOWN,
        message = "Новая попытка разрешена вручную",
        details = "Сначала проверьте доступ; системная очередь этим не очищается",
        requestPending = false,
    )
}

/** Single owner for passive trust checks and explicit one-shot authorization. */
object AdbRescueCoordinator {
    private const val PREFS_NAME = "adb_rescue"
    private const val KEY_REQUEST_PENDING = "request_pending"
    private const val KEY_ATTEMPT_COUNT = "attempt_count"
    private const val KEY_LAST_ATTEMPT_AT = "last_attempt_at"
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
    private val running = AtomicBoolean(false)

    @Volatile
    private var current = AdbRescueSnapshot()

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        current = AdbRescuePolicy.initial(
            requestPending = prefs.getBoolean(KEY_REQUEST_PENDING, false),
            attemptCount = prefs.getInt(KEY_ATTEMPT_COUNT, 0),
            lastAttemptAtMillis = prefs.getLong(KEY_LAST_ATTEMPT_AT, 0L),
        )
        initialized = true
    }

    fun snapshot(): AdbRescueSnapshot = current

    fun checkAccess(context: Context, onChanged: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val app = context.applicationContext
        current = AdbRescuePolicy.checking(current)
        onChanged()
        executor.execute {
            val (outcome, failure) = try {
                val output = DenzaLocalAdb.client(app).shell("printf $CHECK_MARKER")
                if (output.contains(CHECK_MARKER)) {
                    AdbCheckOutcome.TRUSTED to null
                } else {
                    AdbCheckOutcome.ERROR to "Неожиданный ответ shell"
                }
            } catch (_: LocalAdbClient.AuthorizationRequiredException) {
                AdbCheckOutcome.AUTHORIZATION_REQUIRED to null
            } catch (error: Exception) {
                if (isUnavailable(error)) {
                    AdbCheckOutcome.UNAVAILABLE to failureLabel(error)
                } else {
                    AdbCheckOutcome.ERROR to failureLabel(error)
                }
            }
            if (outcome == AdbCheckOutcome.TRUSTED) {
                persistPending(app, pending = false)
            }
            // Read as late as possible, so the flag that classifies the outcome is as fresh as
            // the outcome itself.
            val systemSwitch = AdbSystemSwitchReader.read(app)
            current = AdbRescuePolicy.afterCheck(current, outcome, systemSwitch, failure)
            running.set(false)
            onChanged()
        }
    }

    fun requestOnce(context: Context, onChanged: () -> Unit) {
        val before = current
        if (!before.canRequest || !running.compareAndSet(false, true)) return
        val app = context.applicationContext
        // The attempt is spent for good — a latch that survives restarts, plus a slot in Android's
        // own prompt queue, which is where the documented stuck-queue failure comes from. Both are
        // wasted on a car that cannot draw the prompt, so the switch is re-read here rather than
        // trusted from the last check, and refusing costs the user nothing.
        val systemSwitch = AdbSystemSwitchReader.read(app)
        if (!AdbRescuePolicy.maySubmit(before, systemSwitch)) {
            current = AdbRescuePolicy.systemSwitchOff(before)
            running.set(false)
            onChanged()
            return
        }
        val request = AdbRescuePolicy.requesting(before, System.currentTimeMillis(), systemSwitch)
        if (!persistAttempt(app, request)) {
            running.set(false)
            current = before.copy(
                phase = AdbRescuePhase.ERROR,
                message = "Не удалось сохранить one-shot состояние",
                details = "Запрос не отправлен",
            )
            onChanged()
            return
        }
        current = request
        onChanged()
        executor.execute {
            val (outcome, failure) = try {
                when (DenzaLocalAdb.client(app).requestAuthorization()) {
                    LocalAdbClient.AuthorizationRequestResult.ALREADY_AUTHORIZED ->
                        AdbRequestOutcome.ALREADY_TRUSTED to null
                    LocalAdbClient.AuthorizationRequestResult.REQUEST_SENT ->
                        AdbRequestOutcome.REQUEST_SENT to null
                }
            } catch (error: Exception) {
                if (isUnavailable(error)) {
                    AdbRequestOutcome.UNAVAILABLE to failureLabel(error)
                } else {
                    AdbRequestOutcome.ERROR to failureLabel(error)
                }
            }
            if (outcome == AdbRequestOutcome.ALREADY_TRUSTED) {
                persistPending(app, pending = false)
            }
            current = AdbRescuePolicy.afterRequest(current, outcome, failure)
            running.set(false)
            onChanged()
        }
    }

    fun allowNewAttempt(context: Context, onChanged: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        if (!current.canResetAttempt) {
            running.set(false)
            return
        }
        val app = context.applicationContext
        if (!persistPending(app, pending = false)) {
            current = current.copy(
                phase = AdbRescuePhase.ERROR,
                message = "Не удалось разблокировать новую попытку",
            )
        } else {
            current = AdbRescuePolicy.resetAttempt(current)
        }
        running.set(false)
        onChanged()
    }

    // A synchronous result is part of the one-shot safety boundary: never submit a key unless
    // the latch is durably stored first.
    @SuppressLint("UseKtx")
    private fun persistAttempt(context: Context, snapshot: AdbRescueSnapshot): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_REQUEST_PENDING, true)
            .putInt(KEY_ATTEMPT_COUNT, snapshot.attemptCount)
            .putLong(KEY_LAST_ATTEMPT_AT, snapshot.lastAttemptAtMillis)
            .commit()

    @SuppressLint("UseKtx")
    private fun persistPending(context: Context, pending: Boolean): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_REQUEST_PENDING, pending)
            .commit()

    private fun isUnavailable(error: Throwable): Boolean = generateSequence(error) { it.cause }
        .any {
            it is ConnectException || it is SocketTimeoutException ||
                it is NoRouteToHostException ||
                (it is IOException && it.message.orEmpty().contains("Connection refused", true))
        }

    private fun failureLabel(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        return root.javaClass.simpleName.ifBlank { "UnknownFailure" }
    }
}
