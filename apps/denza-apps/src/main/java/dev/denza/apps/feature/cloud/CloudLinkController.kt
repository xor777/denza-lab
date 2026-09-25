package dev.denza.apps.feature.cloud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.DenzaAppRepository
import dev.denza.apps.adb.DenzaLocalAdb
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    private var disableRetryAt = 0L

    fun switchOn(context: Context) = switch(context, true)
    fun switchOff(context: Context) = switch(context, false)

    private fun switch(context: Context, enabled: Boolean) {
        val app = context.applicationContext
        explicit(app) {
            cancelFollowUps()
            CloudLinkRuntime.registrationFailure = null
            CloudLinkRuntime.registrationNotBeforeEpochMs = System.currentTimeMillis()
            CloudLinkSettings.save(app, CloudLinkSettings.request(app).request(enabled))
            record(app, "request enabled=$enabled pendingDisable=${CloudLinkSettings.pendingDisable(app)}")
            // Start recovery before touching the car; keep it alive even when the desired state is off.
            CloudLinkService.reconcile(app)
            check(finishDisable(app, force = true)) { "Выключение не завершено" }
            if (enabled) {
                attempt(app, core.switchedOn(read(app), CloudNetwork.usable(app), now()))
            }
            true
        }
    }

    fun setWifiRetained(context: Context, retain: Boolean) {
        val app = context.applicationContext
        explicit(app) {
            shell(app, CloudLinkProtocol.wifiRetentionCommand(retain))
            check(read(app).wifiRetained == retain) { "Настройка Wi-Fi не подтвердилась" }
            record(app, "wifiRetention=$retain confirmed")
            true
        }
    }

    /** Reading and reporting do not write to the vehicle. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        executor.execute {
            runCatching { read(app) }.onFailure { recordError(app, "read", it) }
            publish(app)
        }
    }

    fun serviceStarted(context: Context) {
        val app = context.applicationContext
        executor.execute {
            watching = true
            record(app, "service start")
            automatic(app, "start")
        }
    }

    fun serviceStopped() {
        executor.execute {
            watching = false
            tick?.cancel(false)
            tick = null
            cancelFollowUps()
        }
    }

    fun networkReturned(context: Context) {
        val app = context.applicationContext
        executor.execute { automatic(app, "network returned", returned = true) }
    }

    fun networkGone(context: Context) {
        val app = context.applicationContext
        executor.execute { automatic(app, "network gone", lost = true) }
    }

    fun hint(context: Context) {
        if (!hintQueued.compareAndSet(false, true)) return
        val app = context.applicationContext
        executor.execute {
            try { automatic(app, "status broadcast") } finally { hintQueued.set(false) }
        }
    }

    private fun automatic(app: Context, reason: String, returned: Boolean = false, lost: Boolean = false) {
        try {
            if (!CloudLinkSettings.needsService(app)) return
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
            CloudLinkRuntime.failure = failure(error)
            recordError(app, reason, error)
        } finally {
            publish(app)
            schedule(app)
        }
    }

    /** A failed disable survives process death and is resumed before any new enable. */
    private fun finishDisable(app: Context, force: Boolean = false): Boolean {
        if (!CloudLinkSettings.pendingDisable(app)) return true
        if (!force && now() < disableRetryAt) return false
        disableRetryAt = now() + CloudLinkCore.NETWORK_LOSS_GRACE_MS
        val car = read(app)
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

    private fun explicit(app: Context, block: () -> Boolean) {
        pressesInFlight.incrementAndGet()
        CloudLinkRuntime.busy = true
        // Core is worker-owned; the caller publishes only the atomic busy flag.
        runCatching { DenzaAppRepository.refresh() }.onFailure { Log.w(TAG, "publish busy failed", it) }
        executor.execute {
            try {
                check(block()) { "Операция не подтвердилась" }
                CloudLinkRuntime.failure = null
            } catch (error: Exception) {
                CloudLinkRuntime.failure = failure(error)
                recordError(app, "explicit", error)
            } finally {
                CloudLinkRuntime.busy = pressesInFlight.decrementAndGet() > 0
                publish(app)
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

    private fun followUp(app: Context) {
        cancelFollowUps()
        for (delay in longArrayOf(5_000, 15_000, 30_000, 60_000)) {
            followUps += executor.schedule({
                if (watching && CloudLinkSettings.needsService(app)) automatic(app, "follow up")
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
        // Poll while offline too: an initial or failed network-loss action must be repaired.
        val delay = when {
            CloudLinkSettings.pendingDisable(app) || !CloudNetwork.usable(app) -> 30_000L
            // Keep the bounded native log window while connecting. Core's write/retry budgets
            // are unchanged; a diagnostic sample never sends a network notification.
            CloudLinkRuntime.car?.connected != true -> CloudNativeLog.INTERVAL_MS
            else -> 60_000L
        }
        tick = executor.schedule({ automatic(app, "tick") }, delay, TimeUnit.MILLISECONDS)
    }

    private fun publish(app: Context? = null) {
        CloudLinkRuntime.adapter = CloudLinkReport.Adapter(
            core.gate.name, core.attempts, core.lastReadyAt, core.disconnectedSince, core.nextReadyAt,
        )
        // Reporting must never interrupt cleanup, leave busy set or stop recovery scheduling.
        if (app != null) {
            CloudLinkDiagnostics.captureNative(app, now()) {
                DenzaLocalAdb.client(app).shell(it, 4_000)
            }
            runCatching { CloudLinkDiagnostics.export(app) }
                .onFailure { Log.w(TAG, "export failed", it) }
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
