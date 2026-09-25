package dev.denza.apps.feature.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import dev.denza.apps.core.FeatureId
import dev.denza.apps.core.FeatureReducer
import dev.denza.apps.core.FeatureSnapshot
import dev.denza.apps.core.FeatureStatus

/**
 * The driver's wish, and nothing else.
 *
 * Only the link has one. Keeping Wi-Fi on in sleep is the car's own setting and the panel reads it
 * back from the car, so there is no second copy of it here to disagree with the first.
 *
 * Absent is off, and off on its own does nothing to the car: a new install over a car whose link
 * is already up leaves it up until the driver says «on» and then «off» (see [CloudLinkCore]).
 */
object CloudLinkSettings {
    private const val PREFS = "cloud_link"
    private const val ENABLED = "enabled"
    private const val PENDING_DISABLE = "pending_disable"
    private const val AWAITING_TCP_DOWN = "awaiting_tcp_down"
    private const val MODE = "sim_mode"
    private const val ICCID = "custom_iccid"
    private const val IMSI = "custom_imsi"
    private const val CUSTOM_OWNER = "custom_owner_nonce"
    private const val CUSTOM_TERMINAL = "custom_terminal_code"
    private const val CUSTOM_TERMINAL_GENERATION = "custom_terminal_generation"
    private const val CUSTOM_CLEANUP_REQUIRED = "custom_cleanup_required"
    private const val APP_OPEN_RESUME_PENDING = "app_open_resume_pending"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    internal fun request(context: Context): CloudLinkRequest =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let {
            CloudLinkRequest(it.getBoolean(ENABLED, false), it.getBoolean(PENDING_DISABLE, false), it.getBoolean(AWAITING_TCP_DOWN, false))
        }

    fun needsService(context: Context): Boolean = request(context).needsService
    fun pendingDisable(context: Context): Boolean = request(context).pendingDisable

    /** Preserves the saved ON wish while an explicit app-open restart cleans up the old owner. */
    internal fun appOpenResumePending(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(APP_OPEN_RESUME_PENDING, false)

    @Synchronized internal fun beginAppOpenRestart(context: Context) {
        val request = request(context)
        check(request.enabled || appOpenResumePending(context)) { "Нет запроса на запуск" }
        val stopped = request.request(false)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, false)
            .putBoolean(PENDING_DISABLE, stopped.pendingDisable)
            .putBoolean(AWAITING_TCP_DOWN, stopped.awaitingTcpDown)
            .putBoolean(APP_OPEN_RESUME_PENDING, true).commit()) { "Не удалось сохранить запрос" }
    }

    @Synchronized internal fun completeAppOpenRestart(context: Context) {
        check(appOpenResumePending(context) && !isEnabled(context) && !pendingDisable(context) &&
            customOwner(context) == null) { "Выключение не завершено" }
        save(context, request(context).request(true))
    }

    @Synchronized internal fun cancelAppOpenRestart(context: Context) {
        if (!appOpenResumePending(context)) return
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(APP_OPEN_RESUME_PENDING, false).commit()) { "Не удалось сохранить выключение" }
    }

    /** A later explicit OFF or mode choice wins even if restart just persisted ON. */
    @Synchronized internal fun cancelAppOpenRestartAndStop(context: Context) {
        val stopped = request(context).request(false)
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, false)
            .putBoolean(PENDING_DISABLE, stopped.pendingDisable)
            .putBoolean(AWAITING_TCP_DOWN, stopped.awaitingTcpDown)
            .putBoolean(APP_OPEN_RESUME_PENDING, false).commit()) { "Не удалось сохранить выключение" }
    }

    /** A durable hint to probe the global owner after this installation has used CUSTOM. */
    fun customCleanupRequired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(CUSTOM_CLEANUP_REQUIRED, false) || customOwner(context) != null ||
            (mode(context) == CloudSimMode.CUSTOM && (isEnabled(context) || pendingDisable(context)))

    @Synchronized internal fun markCustomCleanupRequired(context: Context) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(CUSTOM_CLEANUP_REQUIRED, true).commit()) { "Не удалось сохранить владение" }
    }

    @Synchronized internal fun clearCustomCleanupRequired(context: Context) {
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(CUSTOM_CLEANUP_REQUIRED, false).commit()) { "Не удалось сохранить выключение" }
    }

    /** An old enabled install predates the mode choice and keeps its factory path. */
    fun mode(context: Context): CloudSimMode? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { prefs ->
            resolveMode(prefs.getString(MODE, null), prefs.getBoolean(ENABLED, false))
        }

    internal fun resolveMode(saved: String?, enabled: Boolean): CloudSimMode? = when (saved) {
        CloudSimMode.FACTORY.name -> CloudSimMode.FACTORY
        CloudSimMode.CUSTOM.name -> CloudSimMode.CUSTOM
        else -> CloudSimMode.FACTORY
    }

    fun customIdentity(context: Context): CloudIdentity? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).let { prefs ->
            val iccid = prefs.getString(ICCID, null)
            val imsi = prefs.getString(IMSI, null)
            if (iccid == null || imsi == null) null else CloudIdentity(iccid, imsi)
        }

    /** Controller-only transition after the old side has durably finished teardown. */
    @Synchronized internal fun modeAfterStop(context: Context, mode: CloudSimMode) {
        check(!isEnabled(context) && !pendingDisable(context) && customOwner(context) == null) {
            "Выключение не завершено"
        }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(MODE, mode.name).putBoolean(APP_OPEN_RESUME_PENDING, false).commit()) {
            "Не удалось сохранить режим"
        }
    }

    @Synchronized internal fun identityAfterStop(context: Context, identity: CloudIdentity) {
        check(!isEnabled(context) && !pendingDisable(context) && customOwner(context) == null &&
            mode(context) == CloudSimMode.CUSTOM && identity.valid()) { "Выключение не завершено" }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ICCID, identity.iccid).putString(IMSI, identity.imsi).commit()) {
            "Не удалось сохранить номера SIM"
        }
    }

    fun configurable(context: Context): Boolean =
        canConfigure(isEnabled(context), pendingDisable(context), CloudLinkRuntime.busy,
            customOwner(context) != null)

    internal fun customOwner(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CUSTOM_OWNER, null)

    /** Durable before the helper can run; a timed-out START is an ambiguous owner, not absence. */
    @Synchronized internal fun claimCustomOwner(context: Context, nonce: String) {
        check(customOwner(context) == null) { "Предыдущая сессия не закрыта" }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CUSTOM_OWNER, nonce).commit()) { "Не удалось сохранить владение" }
    }

    @Synchronized internal fun clearCustomOwner(context: Context, nonce: String) {
        check(customOwner(context) == nonce) { "Владение изменилось" }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(CUSTOM_OWNER).commit()) { "Не удалось сохранить выключение" }
    }

    @Synchronized internal fun adoptCustomOwner(context: Context, expected: String?, owner: String) {
        check(owner.matches(Regex("[0-9a-f]{32}"))) { "Владение изменилось" }
        check(customOwner(context) == expected) { "Владение изменилось" }
        check(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(CUSTOM_OWNER, owner).commit()) { "Не удалось сохранить владение" }
    }

    internal fun customTerminal(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(CUSTOM_TERMINAL_GENERATION, -1) != CloudCustomInstallation.generation(context)) return null
        return prefs.getString(CUSTOM_TERMINAL, null)?.also {
            check(it.matches(Regex("[a-z][a-z0-9_]{0,79}")) && !Regex("[0-9]{4,}").containsMatchIn(it)) {
                "Не удалось прочитать настройки облака"
            }
        }
    }

    @Synchronized internal fun saveCustomTerminal(context: Context, code: String) {
        val generation = CloudCustomInstallation.generation(context)
        check(generation > 0) { "Не удалось прочитать настройки облака" }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // commit(false) may already have changed SharedPreferences' RAM map. Equality
        // cannot prove durability; repeat commit so a prior failed disk write is retried.
        check(prefs.edit().putString(CUSTOM_TERMINAL, code)
            .putLong(CUSTOM_TERMINAL_GENERATION, generation).commit()) { "Не удалось сохранить настройки облака" }
    }

    @Synchronized internal fun clearCustomTerminal(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        check(prefs.edit().remove(CUSTOM_TERMINAL).remove(CUSTOM_TERMINAL_GENERATION).commit()) {
            "Не удалось сохранить настройки облака"
        }
    }

    internal fun canConfigure(enabled: Boolean, pendingDisable: Boolean, busy: Boolean,
                              customOwner: Boolean = false): Boolean =
        !enabled && !pendingDisable && !busy && !customOwner

    /** Called on the controller thread, durably before writes to the car. */
    internal fun save(context: Context, request: CloudLinkRequest) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacyFactory = !prefs.contains(MODE) && prefs.getBoolean(ENABLED, false)
        val editor = prefs.edit()
            .putBoolean(ENABLED, request.enabled)
            .putBoolean(AWAITING_TCP_DOWN, request.awaitingTcpDown)
            .putBoolean(PENDING_DISABLE, request.pendingDisable)
        if (request.enabled) editor.putBoolean(APP_OPEN_RESUME_PENDING, false)
        if (legacyFactory) editor.putString(MODE, CloudSimMode.FACTORY.name)
        check(editor.commit()) { "Не удалось сохранить запрос" }
    }
}

/**
 * What the screen may know about the link without asking the car: the last reading, whether a
 * switch is on the wire, and a press that did not take.
 *
 * Written by [CloudLinkController] on its own thread, read by `DenzaAppRepository.refresh`, which
 * must never wait on a shell.
 */
object CloudLinkRuntime {
    @Volatile
    var car: CloudCarState? = null

    /** A switch is being written; both of the panel's switches grey until the car answers. */
    @Volatile
    var busy: Boolean = false

    /** The driver's last press that the car did not take, in the tile's words; null once one does. */
    @Volatile
    var failure: String? = null

    /** What the adapter believes about the gate and its clocks, for the service report. */
    @Volatile
    var adapter: CloudLinkReport.Adapter? = null

    /** When the car was last read (elapsedRealtime), so a report can say how old [car] is. */
    @Volatile
    var readAtMs: Long? = null

    @Volatile
    var readFailure: String? = null

    @Volatile var wifiRetained: Boolean? = null
    @Volatile var wifiFailure: String? = null

    @Volatile internal var registrationFailure: CloudRegistrationFailure? = null
    @Volatile internal var registrationNotBeforeEpochMs: Long = 0

    @Volatile internal var custom: CloudCustomStatus? = null
    @Volatile var customReadAtMs: Long? = null
    @Volatile var leaseFailure: String? = null
    @Volatile internal var customServiceStartPendingUntilMs: Long = 0L

    fun readingFailed(nowMs: Long): Boolean = readFailure != null ||
        readAtMs?.let { nowMs - it !in 0..90_000L } == true

    fun snapshot(enabled: Boolean, network: Boolean, pendingDisable: Boolean, nowMs: Long,
                 mode: CloudSimMode? = CloudSimMode.FACTORY, uptimeMs: Long = nowMs,
                 identityValid: Boolean = true, serviceAlive: Boolean = true): FeatureSnapshot =
        if (mode == CloudSimMode.CUSTOM) customSnapshot(enabled, network, pendingDisable, nowMs,
            uptimeMs, identityValid, serviceAlive) else
        CloudLinkStatus.snapshot(
            enabled, car, network, failure,
            readingFailed = readingFailed(nowMs),
            awaitingFreshRead = busy && readFailure == null,
            pendingDisable = pendingDisable && !busy,
            stalled = adapter?.disconnectedSinceMs?.let { nowMs - it >= CloudLinkCore.SETTLE_MS } == true,
            profileDrift = car?.let { !it.wifiProfile && !it.cellular && it.connected == false } == true &&
                adapter?.let { it.attempts > 0 && it.gate == "UNKNOWN" } == true,
            registrationFailure = registrationFailure?.message(car, nowMs),
        )

    private fun customSnapshot(enabled: Boolean, network: Boolean, pendingDisable: Boolean,
                               nowMs: Long, uptimeMs: Long, identityValid: Boolean,
                               serviceAlive: Boolean): FeatureSnapshot {
        val base = if (enabled) FeatureReducer.starting(FeatureId.CLOUD_LINK) else FeatureReducer.disabled(FeatureId.CLOUD_LINK)
        val status = custom
        return when {
            pendingDisable -> if (busy) base else base.copy(status = FeatureStatus.ERROR, message = "Выключение не завершено")
            !enabled -> base
            !identityValid -> FeatureReducer.needsAction(base, "Нужны номера SIM")
            leaseFailure != null -> base.copy(status = FeatureStatus.ERROR, message = leaseFailure.orEmpty())
            status?.stage == "failed" && !status.retryable ->
                base.copy(status = FeatureStatus.ERROR, message = CloudCustomMessages.error(status.code))
            !serviceAlive && !busy && nowMs >= customServiceStartPendingUntilMs -> base.copy(status = FeatureStatus.ERROR,
                message = "Служба связи остановилась")
            !network -> FeatureReducer.ready(FeatureId.CLOUD_LINK).copy(message = "Нет интернета")
            failure != null -> base.copy(status = FeatureStatus.ERROR, message = failure.orEmpty())
            status == null -> base
            customReadAtMs?.let { nowMs - it !in 0..30_000L } != false ->
                if (busy) base else base.copy(status = FeatureStatus.ERROR, message = "Нет свежих данных")
            nowMs - status.updatedElapsedMs !in -5_000L..90_000L ->
                if (busy) base else base.copy(status = FeatureStatus.ERROR, message = "Нет свежих данных")
            status.leaseActive && status.leaseUntilUptimeMs <= uptimeMs ->
                base.copy(status = FeatureStatus.ERROR, message = "Служба связи остановилась")
            status.sessionLive && status.leaseActive -> FeatureReducer.ready(FeatureId.CLOUD_LINK, active = true)
            status.stage == "retry_wait" && status.retryable -> base
            status.stage == "failed" || status.stage == "stopped" || status.stage == "retry_wait" ->
                base.copy(status = FeatureStatus.ERROR, message = CloudCustomMessages.error(status.code))
            else -> base
        }
    }
}

/** The kind of internet the car is on, as far as the cloud link is concerned. */
enum class CloudNetworkKind(val label: String) {
    WIFI("Wi-Fi"),
    MOBILE("мобильный"),
    NONE("нет"),
}

/**
 * The stock factory adapter requires validated Wi-Fi. The custom worker may use validated Wi-Fi
 * or cellular internet; choosing an identity never changes the car's physical APN/SIM network.
 *
 * Operator metadata cannot establish whether a private BYD APN is active. Core and operations
 * guard the actual APN1/APN3 state before changing the profile or notifying the client.
 */
object CloudNetwork {
    fun usable(context: Context): Boolean = usable(reading(context))

    fun usable(context: Context, mode: CloudSimMode?): Boolean = usable(reading(context), mode)

    internal fun usable(reading: CloudNetworkReading): Boolean = reading.kind == CloudNetworkKind.WIFI

    internal fun usable(reading: CloudNetworkReading, mode: CloudSimMode?): Boolean =
        reading.kind == CloudNetworkKind.WIFI ||
            (mode == CloudSimMode.CUSTOM && reading.kind == CloudNetworkKind.MOBILE)

    fun kind(context: Context): CloudNetworkKind = reading(context).kind

    /** The default network as the rule sees it, raw, for the rule and for the service report. */
    fun reading(context: Context): CloudNetworkReading {
        // No permission needed: the operator code of the SIM, never its identity.
        val sim = context.getSystemService(TelephonyManager::class.java)?.simOperator
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
            ?: return CloudNetworkReading(validated = false, wifi = false, cellular = false, simOperator = sim)
        return CloudNetworkReading(
            validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            simOperator = sim,
        )
    }

    /** The rule itself, without Android, so it is tested on the JVM. */
    internal fun kindOf(
        validated: Boolean,
        wifi: Boolean,
        cellular: Boolean,
    ): CloudNetworkKind = when {
        !validated -> CloudNetworkKind.NONE
        wifi -> CloudNetworkKind.WIFI
        cellular -> CloudNetworkKind.MOBILE
        else -> CloudNetworkKind.NONE
    }

    /** Report metadata only: the supplied operator code has MCC 460, not proof of SIM hardware/APN. */
    internal fun chineseSim(simOperator: String?): Boolean = simOperator?.startsWith("460") == true
}

/** The default network's facts the rule reads, before the rule reads them. */
data class CloudNetworkReading(
    val validated: Boolean,
    val wifi: Boolean,
    val cellular: Boolean,
    val simOperator: String?,
) {
    val kind: CloudNetworkKind
        get() = CloudNetwork.kindOf(validated, wifi, cellular)
}

/**
 * The tile's status, read from the wish and the last reading - never from what the controller is
 * doing this second.
 *
 * | status   | when                                                   | tile            |
 * | -------- | ------------------------------------------------------ | --------------- |
 * | OFF      | switched off                                           | «Выключено»     |
 * | ACTIVE   | the stock client holds its connection                  | «На связи»      |
 * | READY    | switched on, no usable internet to translate           | «Нет интернета» |
 * | STARTING | switched on, on internet, not connected (yet)          | «Подключается»  |
 * | ERROR    | the driver's last press was not taken by the car       | the failure     |
 *
 * READY is on and healthy: the adapter has nothing to do until internet comes back, as the mirrors
 * have nothing to do until a turn signal. STARTING may last - the client retries on its own and the
 * adapter repeats «ready» on a growing backoff - and it is drawn as working for as long as it is
 * true, as weather is before its first forecast.
 */
object CloudLinkStatus {
    /**
     * The tile's words for a snapshot - here rather than on the tile so the service report says the
     * link's state in the same words the tile does, from the one place they are written.
     */
    fun words(snapshot: FeatureSnapshot): String = when (snapshot.status) {
        FeatureStatus.OFF -> "Выключено"
        FeatureStatus.ACTIVE -> "На связи"
        FeatureStatus.READY -> snapshot.message.ifBlank { "Ждёт Wi-Fi" }
        FeatureStatus.NEEDS_ACTION -> snapshot.message.ifBlank { "Нужно действие" }
        FeatureStatus.ERROR, FeatureStatus.UNAVAILABLE -> snapshot.message.ifBlank { "Не переключилось" }
        else -> "Подключается"
    }

    fun snapshot(
        enabled: Boolean,
        car: CloudCarState?,
        network: Boolean,
        failure: String?,
        readingFailed: Boolean = false,
        pendingDisable: Boolean = false,
        stalled: Boolean = false,
        profileDrift: Boolean = false,
        registrationFailure: String? = null,
        awaitingFreshRead: Boolean = false,
    ): FeatureSnapshot {
        val base = if (enabled) {
            FeatureReducer.starting(FeatureId.CLOUD_LINK)
        } else {
            FeatureReducer.disabled(FeatureId.CLOUD_LINK)
        }
        return when {
            pendingDisable -> base.copy(status = FeatureStatus.ERROR, message = "Выключение не завершено")
            failure != null -> base.copy(status = FeatureStatus.ERROR, message = failure)
            !enabled -> base
            // Off stops polling. On after a long pause must wait for its bounded operation's
            // fresh read, not flash an error or claim success from the expired TCP snapshot.
            // An actual read/operation failure still wins, and idle stale readings still fail.
            readingFailed -> if (awaitingFreshRead) base else
                base.copy(status = FeatureStatus.ERROR, message = "Нет свежих данных")
            car?.connected == true -> FeatureReducer.ready(FeatureId.CLOUD_LINK, active = true)
            !network -> FeatureReducer.ready(FeatureId.CLOUD_LINK).copy(message = "Ждёт Wi-Fi")
            profileDrift -> base.copy(status = FeatureStatus.ERROR, message = "Профиль изменился")
            registrationFailure != null -> base.copy(status = FeatureStatus.ERROR, message = registrationFailure)
            stalled -> base.copy(status = FeatureStatus.ERROR, message = "Нет связи с облаком")
            else -> base
        }
    }
}
