package dev.denza.apps.feature.cloud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.BuildConfig
import dev.denza.apps.DenzaAppRepository
import dev.denza.apps.adb.DenzaLocalAdb
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** One writer. Desired state and pending teardown are durable before any vehicle operation. */
object CloudLinkController {
    private const val TAG = "DenzaCloudLink"
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "denza-cloud-link").apply { isDaemon = true }
    }
    private val core = CloudLinkCore()
    private var watching = false
    private var tick: ScheduledFuture<*>? = null
    private val followUps = mutableListOf<ScheduledFuture<*>>()
    private val pressesInFlight = AtomicInteger()
    private val hintQueued = AtomicBoolean()
    private val appOpenQueued = AtomicBoolean()
    private var disableRetryAt = 0L
    private var customLifecycle: CloudCustomLifecycle? = null
    private val renewExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "denza-cloud-lease").apply { isDaemon = true }
    }
    @Volatile private var serviceInstance: String? = null
    @Volatile private var leaseOwner: String? = null
    private val renewSeq = AtomicLong()
    @Volatile private var renewTask: ScheduledFuture<*>? = null
    private var renewBackend: CloudCustomBackend? = null

    fun hasLiveService(): Boolean = serviceInstance != null

    fun switchOn(context: Context) = switch(context, true)
    fun switchOff(context: Context) = switch(context, false)

    private fun switch(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        synchronized(CloudLinkSettings) {
            val requestedMode = CloudLinkSettings.mode(app)
            if (enabled && requestedMode == null) {
                CloudLinkRuntime.failure = "Сначала выберите SIM"
                DenzaAppRepository.refresh()
                return
            }
            if (enabled && requestedMode == CloudSimMode.CUSTOM && !BuildConfig.CLOUD_NATIVE_PILOT) {
                CloudLinkRuntime.failure = "Адаптер заменённой SIM пока недоступен"
                DenzaAppRepository.refresh()
                return
            }
            explicit(app) {
                check(CloudLinkSettings.mode(app) == requestedMode) { "Режим изменился" }
                cancelFollowUps()
                CloudLinkRuntime.registrationFailure = null
                CloudLinkRuntime.registrationNotBeforeEpochMs = System.currentTimeMillis()
                CloudLinkSettings.save(app, CloudLinkSettings.request(app).request(enabled))
                record(app, "request enabled=$enabled pendingDisable=${CloudLinkSettings.pendingDisable(app)}")
                // Start recovery before touching the car; keep it alive even when the desired state is off.
                CloudLinkService.reconcile(app, explicitCustomStart = requestedMode == CloudSimMode.CUSTOM)
                if (requestedMode == CloudSimMode.CUSTOM) {
                    // Android creates the service asynchronously. Only its onCreate may supply
                    // the instance token that authorizes START, ATTACH and renewal.
                    if (enabled && !CloudLinkSettings.pendingDisable(app)) return@explicit true
                    check(customReconcile(app, force = true)) { "Выключение не завершено" }
                    return@explicit true
                }
                check(resolveCustomOwnership(app)) { "Предыдущая сессия не закрыта" }
                check(finishDisable(app, force = true)) { "Выключение не завершено" }
                if (enabled) {
                    attempt(app, core.switchedOn(read(app), CloudNetwork.usable(app), now()))
                }
                true
            }
        }
    }

    fun setWifiRetained(context: Context, retain: Boolean) {
        val app = context.applicationContext
        explicit(app, wifiOperation = true) {
            shell(app, CloudLinkProtocol.wifiRetentionCommand(retain))
            val confirmed = if (CloudLinkSettings.mode(app) == CloudSimMode.CUSTOM)
                readWifiRetention(app) else read(app).wifiRetained
            check(confirmed == retain) { "Настройка Wi-Fi не подтвердилась" }
            record(app, "wifiRetention=$retain confirmed")
            true
        }
    }

    /** Reading and reporting do not write to the vehicle. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        val reportTicket = CloudLinkDiagnostics.exportTicket()
        executor.execute {
            if (CloudLinkSettings.mode(app) == CloudSimMode.CUSTOM) {
                runCatching { readWifiRetention(app) }
                    .onFailure { Log.w(TAG, "Wi-Fi setting read failed: ${it.javaClass.simpleName}") }
                if (CloudLinkSettings.needsService(app)) {
                    automatic(app, "refresh", reportTicket)
                    return@execute
                }
            } else {
                runCatching { read(app) }.onFailure { recordError(app, "read", it) }
            }
            publish(app, reportTicket)
        }
    }

    /** A report opt-in captures the current state even on a clean cloud-OFF install. */
    fun reportNow(context: Context) {
        val app = context.applicationContext
        val ticket = CloudLinkDiagnostics.exportTicket()
        executor.execute {
            runCatching { CloudLinkDiagnostics.requestExport(app, ticket) }
                .onFailure { Log.w(TAG, "report snapshot failed: ${it.javaClass.simpleName}") }
        }
    }

    fun serviceStarted(context: Context, instance: String) {
        val app = context.applicationContext
        serviceInstance = instance
        leaseOwner = null
        renewTask?.cancel(false)
        renewTask = null
        renewSeq.set(0)
        CloudLinkRuntime.custom = null
        CloudLinkRuntime.customReadAtMs = null
        renewExecutor.execute {
            renewBackend?.close()
            renewBackend = null
        }
        val reportTicket = CloudLinkDiagnostics.exportTicket()
        executor.execute {
            watching = true
            customLifecycle?.serviceClosed()
            record(app, "service start")
            automatic(app, "start", reportTicket)
        }
    }

    fun serviceStopped(context: Context, instance: String) {
        if (serviceInstance != instance) return
        serviceInstance = null
        leaseOwner = null
        renewTask?.cancel(false)
        renewTask = null
        val app = context.applicationContext
        CloudLinkRuntime.custom = null
        CloudLinkRuntime.customReadAtMs = null
        if (CloudLinkSettings.mode(app) == CloudSimMode.CUSTOM && CloudLinkSettings.isEnabled(app))
            CloudLinkRuntime.leaseFailure = "Служба связи остановилась"
        executor.execute {
            if (serviceInstance != null) return@execute
            watching = false
            tick?.cancel(false)
            tick = null
            cancelFollowUps()
            // STOP is best effort. The guardian lease still protects sudden process death.
            if (CloudLinkSettings.mode(app) == CloudSimMode.CUSTOM) {
                runCatching {
                    // Service destruction is not the driver's OFF wish. Keep the marker and
                    // desired state intact; a stale onDestroy must not publish OFF after a
                    // replacement service has started renewing the same installation.
                    val result = lifecycle(app).resolveOwnership(force = true)
                    acceptLifecycle(app, result)
                }
                    .onFailure { Log.w(TAG, "custom service teardown failed: ${it.javaClass.simpleName}") }
            }
            customLifecycle?.serviceClosed()
            CloudLinkRuntime.custom = null
            CloudLinkRuntime.customReadAtMs = null
        }
        renewExecutor.execute {
            renewBackend?.close()
            renewBackend = null
        }
    }

    /** A user launching the app may resume a saved CUSTOM wish only after old-owner cleanup. */
    fun explicitAppOpened(context: Context, retryAfterLease: Boolean = true) {
        val app = context.applicationContext
        if (CloudLinkSettings.mode(app) != CloudSimMode.CUSTOM || hasLiveService()) return
        if (!appOpenQueued.compareAndSet(false, true)) return
        executor.execute {
            try {
                if (hasLiveService()) return@execute
                if (CloudLinkSettings.isEnabled(app) || CloudLinkSettings.pendingDisable(app)) {
                    val request = CloudLinkSettings.request(app)
                    if (request.enabled && !request.pendingDisable) {
                        val probe = CloudCustomBackend.open(app).use { it.probe() }
                        if (probe.protocol == 3 && probe.leaseActive) {
                            // A different live service may still own this installation. Do not
                            // publish OFF or stop its lease just because this Activity opened.
                            val delay = (probe.leaseUntilUptimeMs - SystemClock.uptimeMillis() + 1_000L)
                                .coerceIn(1_000L, 30_000L)
                            if (retryAfterLease) executor.schedule(
                                { explicitAppOpened(app, retryAfterLease = false) }, delay, TimeUnit.MILLISECONDS)
                            return@execute
                        }
                    }
                    if (!request.pendingDisable) CloudLinkSettings.save(app, request.copy(pendingDisable = true))
                    customReconcile(app, force = true)
                    if (CloudLinkSettings.isEnabled(app) && !CloudLinkSettings.pendingDisable(app) &&
                        BuildConfig.CLOUD_NATIVE_PILOT) {
                        CloudLinkService.reconcile(app, explicitCustomStart = true)
                    }
                }
            } catch (error: Exception) {
                customError(app, "app open", error)
            } finally {
                appOpenQueued.set(false)
                publish(app, CloudLinkDiagnostics.exportTicket())
            }
        }
    }

    fun networkReturned(context: Context) {
        val app = context.applicationContext
        val reportTicket = CloudLinkDiagnostics.exportTicket()
        executor.execute { automatic(app, "network returned", reportTicket, returned = true) }
    }

    fun networkGone(context: Context) {
        val app = context.applicationContext
        val reportTicket = CloudLinkDiagnostics.exportTicket()
        executor.execute { automatic(app, "network gone", reportTicket, lost = true) }
    }

    fun hint(context: Context) {
        if (!hintQueued.compareAndSet(false, true)) return
        val app = context.applicationContext
        val reportTicket = CloudLinkDiagnostics.exportTicket()
        executor.execute {
            try { automatic(app, "status broadcast", reportTicket) } finally { hintQueued.set(false) }
        }
    }

    private fun automatic(app: Context, reason: String, reportTicket: Long,
                          returned: Boolean = false, lost: Boolean = false) {
        try {
            if (!CloudLinkSettings.needsService(app)) return
            if (CloudLinkSettings.mode(app) == CloudSimMode.CUSTOM) {
                customReconcile(app)
                return
            }
            if (!resolveCustomOwnership(app)) return
            if (!finishDisable(app)) return
            if (!CloudLinkSettings.isEnabled(app)) return
            val car = read(app)
            val network = CloudNetwork.usable(app)
            val steps = when {
                returned -> core.networkReturned(car, now(), network)
                lost && !network -> core.networkGone(car, now())
                else -> core.reconcile(car, network, now())
            }
            if (steps.isNotEmpty()) {
                record(app, "$reason steps=$steps")
                attempt(app, steps, lossOnly = !network && CloudStep.AnnounceGone in steps)
                CloudLinkRuntime.failure = null
            } else if (car.connected == true) {
                CloudLinkRuntime.failure = null
            }
        } catch (error: Exception) {
            if (CloudLinkSettings.mode(app) == CloudSimMode.CUSTOM) customError(app, reason, error)
            else {
                CloudLinkRuntime.failure = failure(error)
                recordError(app, reason, error)
            }
        } finally {
            publish(app, reportTicket)
            schedule(app)
        }
    }

    /** Custom never calls stock core, reads stock TCP as success, or writes physical APN settings. */
    private fun customReconcile(app: Context, force: Boolean = false): Boolean {
        val request = CloudLinkSettings.request(app)
        val marker = runCatching { CloudCustomInstallation.publish(app) }
        // A failed OFF publication must not prevent STOP over an already authenticated handle.
        // Keep pendingDisable until both the marker and local cleanup have been confirmed.
        if (request.enabled && !request.pendingDisable) marker.getOrThrow()
        val result = lifecycle(app).reconcile(
            enabled = request.enabled && BuildConfig.CLOUD_NATIVE_PILOT,
            pendingDisable = request.pendingDisable,
            networkUsable = CloudNetwork.usable(app, CloudSimMode.CUSTOM),
            identity = CloudLinkSettings.customIdentity(app),
            force = force,
        )
        acceptLifecycle(app, result)
        if (request.pendingDisable && result.stopConfirmed && marker.isSuccess) {
            CloudLinkSettings.save(app, CloudLinkSettings.request(app).copy(
                pendingDisable = false, awaitingTcpDown = false,
            ))
            record(app, "custom disable confirmed pendingDisable=false")
            CloudLinkService.reconcile(app)
            // New START is a separate phase after the old OFF obligation is durably discharged.
            if (CloudLinkSettings.isEnabled(app)) return customReconcile(app, force)
        }
        marker.getOrThrow()
        if (!BuildConfig.CLOUD_NATIVE_PILOT && request.enabled && result.completed) {
            CloudLinkRuntime.failure = "Адаптер отсутствует в этой сборке"
        }
        return result.completed
    }

    /** A fresh helper proves the global native lock is free before clearing an old journal. */
    private fun resolveCustomOwnership(app: Context, force: Boolean = false): Boolean {
        // An old guardian can outlive app data and an APK downgrade. A missing local
        // journal or a build without START support is never proof of its absence.
        CloudCustomInstallation.publish(app)
        val result = lifecycle(app).resolveOwnership(force)
        acceptLifecycle(app, result)
        return result.completed
    }

    private fun lifecycle(app: Context): CloudCustomLifecycle = customLifecycle ?: CloudCustomLifecycle(
        store = object : CloudCustomLifecycle.Store {
            override fun ownerNonce() = CloudLinkSettings.customOwner(app)
            override fun claim(nonce: String) = CloudLinkSettings.claimCustomOwner(app, nonce)
            override fun adopt(expected: String?, owner: String) = CloudLinkSettings.adoptCustomOwner(app, expected, owner)
            override fun clear(nonce: String) = CloudLinkSettings.clearCustomOwner(app, nonce)
            override fun terminalCode() = CloudLinkSettings.customTerminal(app)
            override fun saveTerminal(code: String) = CloudLinkSettings.saveCustomTerminal(app, code)
            override fun clearTerminal() = CloudLinkSettings.clearCustomTerminal(app)
        },
        openBackend = { CloudCustomBackend.open(app, serviceInstance) { renewSeq.set(1) } },
        clock = ::now,
        serviceInstance = { serviceInstance },
    ).also { customLifecycle = it }

    private fun acceptLifecycle(app: Context, result: CloudCustomLifecycle.Result) {
        val liveRead = result.events.any {
            it.operation == CloudCustomLifecycle.Operation.START ||
                it.operation == CloudCustomLifecycle.Operation.ATTACH ||
                it.operation == CloudCustomLifecycle.Operation.STATUS
        }
        val status = result.status.takeIf { result.completed && liveRead }
        if (status != null && result.processNonce != null) {
            CloudLinkRuntime.leaseFailure = null
            val previous = CloudLinkRuntime.custom
            if (previous == null || previous.ownerId != status.ownerId ||
                previous.updatedElapsedMs <= status.updatedElapsedMs) {
                CloudLinkRuntime.custom = status
                CloudLinkRuntime.customReadAtMs = now()
            }
            runCatching { CloudLinkDiagnostics.observeCustom(app, result.processNonce, status) }
                .onFailure { Log.w(TAG, "custom event history failed: ${it.javaClass.simpleName}") }
            val instance = serviceInstance
            if (instance != null && status.protocol == 3 && status.ownerId.isNotEmpty() &&
                result.events.any { it.operation == CloudCustomLifecycle.Operation.START ||
                    it.operation == CloudCustomLifecycle.Operation.ATTACH }) {
                leaseOwner = status.ownerId
                scheduleRenew(app, instance, status.ownerId,
                    if (result.events.any { it.operation == CloudCustomLifecycle.Operation.ATTACH }) 0L else 5_000L)
            }
        } else if (result.stopConfirmed || result.failure != null) {
            CloudLinkRuntime.custom = null
            CloudLinkRuntime.customReadAtMs = null
        }
        result.failure?.let { failure ->
            CloudLinkRuntime.failure = when (failure.reason) {
                CloudCustomLifecycle.Reason.OWNER_PRESENT -> "Предыдущая сессия не закрыта"
                CloudCustomLifecycle.Reason.OWNER_CHANGED -> "Владение изменилось"
                CloudCustomLifecycle.Reason.STOP_UNCONFIRMED -> "Выключение не завершено"
                CloudCustomLifecycle.Reason.IDENTITY_REQUIRED -> "Сохраните ICCID и IMSI"
                CloudCustomLifecycle.Reason.FUTURE_STATUS -> "Нет свежих данных адаптера"
                CloudCustomLifecycle.Reason.REJECTED -> CloudCustomMessages.error(failure.code)
                CloudCustomLifecycle.Reason.IO -> "Адаптер недоступен: ${failure.errorType ?: "Ошибка"}"
            }
            record(app, "custom ${failure.operation} failed ${CloudLinkRuntime.failure}")
        }
        if (result.completed && result.failure == null &&
            (status != null || result.stopConfirmed)) CloudLinkRuntime.failure = null
        if (result.stopConfirmed) {
            leaseOwner = null
            renewTask?.cancel(false)
            renewTask = null
            CloudLinkRuntime.leaseFailure = null
        }
    }

    private fun scheduleRenew(app: Context, instance: String, owner: String, delayMs: Long) {
        renewTask?.cancel(false)
        renewTask = renewExecutor.schedule({ renew(app, instance, owner) }, delayMs, TimeUnit.MILLISECONDS)
    }

    private fun renew(app: Context, instance: String, owner: String) {
        if (serviceInstance != instance || leaseOwner != owner ||
            !CloudLinkSettings.isEnabled(app) || CloudLinkSettings.pendingDisable(app) ||
            CloudLinkSettings.mode(app) != CloudSimMode.CUSTOM) return
        try {
            val channel = renewBackend ?: CloudCustomBackend.open(app, instance).also { renewBackend = it }
            val status = channel.renew(owner, renewSeq.incrementAndGet())
            if (serviceInstance != instance || leaseOwner != owner || status.ownerId != owner) return
            CloudLinkRuntime.custom = status
            CloudLinkRuntime.customReadAtMs = now()
            CloudLinkRuntime.leaseFailure = null
            runCatching { DenzaAppRepository.refresh() }
            if (status.stage == "failed" && !status.retryable) return
        } catch (error: CloudCustomRejected) {
            if (serviceInstance != instance || leaseOwner != owner) return
            if (!error.retryable) {
                CloudLinkRuntime.leaseFailure = CloudCustomMessages.error(error.code)
                runCatching { DenzaAppRepository.refresh() }
                return
            }
            renewBackend?.close()
            renewBackend = null
        } catch (error: Exception) {
            renewBackend?.close()
            renewBackend = null
        }
        if (serviceInstance == instance && leaseOwner == owner) scheduleRenew(app, instance, owner, 5_000L)
    }

    private fun customError(app: Context, stage: String, error: Throwable) {
        // The failed START request may contain identity. Never log throwable messages or causes.
        CloudLinkRuntime.failure = if (error is IllegalStateException && error.message in setOf(
                "Сохраните ICCID и IMSI", "Некорректный ответ адаптера", "Неполный ответ адаптера",
                "Ответ не относится к запросу", "Версия адаптера не совпадает", "Адаптер отклонил запрос",
                "Неизвестный этап адаптера", "Неподтверждённая сессия адаптера",
                "Неподтверждённое владение", "Предыдущая сессия не закрыта",
                "Адаптер отсутствует в сборке", "Проверка файла адаптера не прошла",
            )) error.message else "Адаптер недоступен: ${error.javaClass.simpleName}"
        record(app, "$stage failed ${CloudLinkRuntime.failure}")
    }

    /** A failed disable survives process death and is resumed before any new enable. */
    private fun finishDisable(app: Context, force: Boolean = false): Boolean {
        if (!CloudLinkSettings.pendingDisable(app)) return true
        if (!force && now() < disableRetryAt) return false
        disableRetryAt = now() + CloudLinkCore.NETWORK_LOSS_GRACE_MS
        val car = read(app)
        // The persisted OFF already stops future READY attempts. Let the stock modem finish
        // its transition before deciding whether its gate is ours to close or restore.
        if (car.stockApnTransitioning) return false
        val closing = CloudLinkSettings.request(app).let {
            it.copy(awaitingTcpDown = it.awaitingTcpDown || (car.wifiProfile && !car.cellular))
        }
        // Journal the teardown obligation before -5; keep it if the process dies or the
        // profile is changed by somebody else while TCP is still being torn down.
        CloudLinkSettings.save(app, closing)
        val steps = core.switchedOff(car).toMutableList()
        if (closing.awaitingTcpDown && !car.cellular && CloudStep.WaitDisconnected !in steps) {
            steps.add(0, CloudStep.WaitDisconnected)
        }
        operations(app).run(steps)
        val request = CloudLinkSettings.request(app).disabled(read(app))
        CloudLinkSettings.save(app, request)
        CloudLinkRuntime.failure = null
        record(app, "disable confirmed pendingDisable=false")
        CloudLinkService.reconcile(app)
        return true
    }

    private fun explicit(app: Context, wifiOperation: Boolean = false, block: () -> Boolean) {
        val reportTicket = CloudLinkDiagnostics.exportTicket()
        pressesInFlight.incrementAndGet()
        CloudLinkRuntime.busy = true
        // Core is worker-owned; the caller publishes only the atomic busy flag.
        runCatching { DenzaAppRepository.refresh() }.onFailure { Log.w(TAG, "publish busy failed", it) }
        executor.execute {
            try {
                check(block()) { "Операция не подтвердилась" }
                if (wifiOperation) CloudLinkRuntime.wifiFailure = null
                else if (CloudLinkSettings.mode(app) != CloudSimMode.CUSTOM) CloudLinkRuntime.failure = null
            } catch (error: Exception) {
                if (wifiOperation) {
                    CloudLinkRuntime.wifiFailure = if (error is IllegalStateException &&
                        error.message == "Настройка Wi-Fi не подтвердилась") error.message
                        else "Настройка Wi-Fi: ${error.javaClass.simpleName}"
                    record(app, "wifi setting failed ${CloudLinkRuntime.wifiFailure}")
                } else if (CloudLinkSettings.mode(app) == CloudSimMode.CUSTOM) {
                    if (CloudLinkRuntime.failure == null) customError(app, "explicit", error)
                } else {
                    CloudLinkRuntime.failure = failure(error)
                    recordError(app, "explicit", error)
                }
            } finally {
                CloudLinkRuntime.busy = pressesInFlight.decrementAndGet() > 0
                publish(app, reportTicket)
                schedule(app)
            }
        }
    }

    private fun attempt(app: Context, steps: List<CloudStep>, lossOnly: Boolean = false) {
        try {
            operations(app).run(steps, lossOnly)
            if (CloudStep.AnnounceReady in steps) followUp(app)
        } catch (error: Exception) {
            if (CloudStep.AnnounceReady in steps) core.readyFailed(now())
            throw error
        }
    }

    private fun operations(app: Context) = CloudLinkOperations(
        core, { read(app) }, { shell(app, it) }, { CloudNetwork.usable(app) },
        ::now, Thread::sleep, { record(app, it) },
    )

    private fun read(app: Context): CloudCarState {
        try {
            val car = CloudLinkProtocol.parseRead(shell(app, CloudLinkProtocol.readCommand()))
            CloudLinkRuntime.car = car
            CloudLinkRuntime.wifiRetained = car.wifiRetained
            CloudLinkRuntime.readAtMs = now()
            runCatching { CloudLinkDiagnostics.observe(app, car, CloudNetwork.reading(app)) }
                .onFailure { Log.w(TAG, "diagnostic observation failed", it) }
            CloudLinkProtocol.readFailure(car)?.let { error(it) }
            CloudLinkRuntime.readFailure = null
            if (car.connected == true) {
                // Do not resurrect a preceding rejection if this working session later drops.
                CloudLinkRuntime.registrationFailure = null
                CloudLinkRuntime.registrationNotBeforeEpochMs = System.currentTimeMillis()
            }
            core.observe(car)
            return car
        } catch (error: Exception) {
            CloudLinkRuntime.readFailure = failure(error)
            throw error
        }
    }

    private fun shell(app: Context, command: String): String = DenzaLocalAdb.client(app).shell(command)

    /** Independent read for custom mode; stock TCP/profile fields never enter its status. */
    private fun readWifiRetention(app: Context): Boolean? {
        val value = CloudLinkProtocol.parseWifiRetention(
            DenzaLocalAdb.client(app).shell(CloudLinkProtocol.wifiRetentionReadCommand(), 4_000),
        )
        CloudLinkRuntime.wifiRetained = value
        return value
    }

    private fun followUp(app: Context) {
        cancelFollowUps()
        val reportTicket = CloudLinkDiagnostics.exportTicket()
        for (delay in longArrayOf(5_000, 15_000, 30_000, 60_000)) {
            followUps += executor.schedule({
                if (watching && CloudLinkSettings.needsService(app)) automatic(app, "follow up", reportTicket)
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    private fun cancelFollowUps() {
        followUps.forEach { it.cancel(false) }
        followUps.clear()
    }

    private fun schedule(app: Context) {
        tick?.cancel(false)
        tick = null
        if (!watching || !CloudLinkSettings.needsService(app)) return
        if (CloudLinkSettings.mode(app) == CloudSimMode.CUSTOM) {
            val delay = if (CloudLinkSettings.pendingDisable(app)) 5_000L else 10_000L
            val reportTicket = CloudLinkDiagnostics.exportTicket()
            tick = executor.schedule({ automatic(app, "tick", reportTicket) }, delay, TimeUnit.MILLISECONDS)
            return
        }
        // Poll while offline too: an initial or failed network-loss action must be repaired.
        val delay = when {
            CloudLinkSettings.pendingDisable(app) || !CloudNetwork.usable(app) -> 30_000L
            // Keep the bounded native log window while connecting. Core's write/retry budgets
            // are unchanged; a diagnostic sample never sends a network notification.
            CloudLinkRuntime.car?.connected != true -> CloudNativeLog.INTERVAL_MS
            else -> 60_000L
        }
        val reportTicket = CloudLinkDiagnostics.exportTicket()
        tick = executor.schedule({ automatic(app, "tick", reportTicket) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun publish(app: Context? = null, reportTicket: Long) {
        if (app == null || CloudLinkSettings.mode(app) != CloudSimMode.CUSTOM) {
            CloudLinkRuntime.adapter = CloudLinkReport.Adapter(
                core.gate.name, core.attempts, core.lastReadyAt, core.disconnectedSince, core.nextReadyAt,
            )
        }
        // Reporting must never interrupt cleanup, leave busy set or stop recovery scheduling.
        if (app != null) {
            if (CloudLinkSettings.mode(app) != CloudSimMode.CUSTOM) {
                CloudLinkDiagnostics.captureNative(app, now()) { DenzaLocalAdb.client(app).shell(it, 4_000) }
            }
            runCatching { CloudLinkDiagnostics.requestExport(app, reportTicket) }
                .onFailure { Log.w(TAG, "report snapshot failed: ${it.javaClass.simpleName}") }
        }
        runCatching { DenzaAppRepository.refresh() }.onFailure { Log.w(TAG, "publish failed", it) }
    }

    private fun record(app: Context, message: String) {
        Log.i(TAG, message)
        CloudLinkDiagnostics.record(app, message)
    }

    private fun recordError(app: Context, stage: String, error: Throwable) {
        Log.w(TAG, "$stage failed", error)
        // Exception messages/command output can contain identifiers. Export only our controlled
        // state-machine messages; arbitrary transport exceptions are represented by class name.
        record(app, "$stage failed ${failure(error)}")
    }

    private fun failure(error: Throwable): String =
        if (error is IllegalStateException) error.message.orEmpty().take(160) else "Нет ответа: ${error.javaClass.simpleName}"

    private fun now(): Long = SystemClock.elapsedRealtime()
}
