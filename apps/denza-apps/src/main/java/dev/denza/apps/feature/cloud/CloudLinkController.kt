package dev.denza.apps.feature.cloud

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.DenzaAppRepository
import dev.denza.apps.adb.DenzaLocalAdb
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The one place the cloud link talks to the car.
 *
 * Every read and write goes through one thread, in order, so the driver's «off» can never land in
 * the middle of an automatic «ready», and each automatic task re-reads the switch when it runs
 * rather than when it was queued. [CloudLinkCore] decides; this carries the decision out over the
 * local ADB shell, publishes the reading to [CloudLinkRuntime] and asks the dashboard to redraw.
 *
 * [CloudLinkService] is what keeps the process alive and feeds the Wi-Fi events in; the readings
 * it asks for between events are scheduled here, and stop when it stops.
 */
object CloudLinkController {
    private const val TAG = "DenzaCloudLink"

    /** The live run waited this long after the profile broadcast before trusting it. */
    private const val PROFILE_SETTLE_MS = 3_000L

    /** And this long between «gone» and the profile it no longer needs. */
    private const val GONE_SETTLE_MS = 1_000L

    /** Readings after a «ready», to catch the connection coming up: the live run's own schedule. */
    private val FOLLOW_UP_MS = longArrayOf(5_000L, 15_000L, 30_000L, 60_000L)

    /** Between events: once a minute while there is something to wait for, else every five. */
    private const val WATCH_MS = 60_000L
    private const val IDLE_MS = 5 * 60_000L

    private const val ENABLE_FAILED = "Не включилось"
    private const val DISABLE_FAILED = "Не выключилось"

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "denza-cloud-link").apply { isDaemon = true }
    }
    private val core = CloudLinkCore()
    private var watching = false
    private var tick: ScheduledFuture<*>? = null
    private val pressesInFlight = AtomicInteger()

    /** The driver switched the link on. */
    fun switchOn(context: Context) {
        val app = context.applicationContext
        explicit(ENABLE_FAILED) {
            val car = read(app)
            run(app, core.switchedOn(car, CloudWifi.validated(app), now())).also { followUp(app) }
        }
    }

    /** The driver switched the link off: close the gate and hand the car its profile back. */
    fun switchOff(context: Context) {
        val app = context.applicationContext
        explicit(DISABLE_FAILED) {
            val car = read(app)
            run(app, core.switchedOff(car)).also { read(app) }
        }
    }

    /**
     * Keep client Wi-Fi on through sleep, or give the choice back to the car.
     *
     * The panel's switch shows the car's value, not this call's: a write the car did not take
     * leaves the switch where the car says it is, which is the whole of the error handling.
     */
    fun setWifiRetained(context: Context, retain: Boolean) {
        val app = context.applicationContext
        explicit(failure = null) {
            val output = shell(app, CloudLinkProtocol.wifiRetentionCommand(retain))
            val taken = read(app).wifiRetained == retain
            Log.i(TAG, "wifi retention retain=$retain taken=$taken output=${output.trim()}")
            taken
        }
    }

    /** Read the car for the screen, and change nothing. */
    fun refresh(context: Context) {
        val app = context.applicationContext
        executor.execute {
            runCatching { read(app) }.onFailure { Log.i(TAG, "read failed", it) }
            publish()
        }
    }

    /** The service is up: reconcile now, and keep reading until it stops. */
    fun serviceStarted(context: Context) {
        val app = context.applicationContext
        executor.execute {
            watching = true
            automatic(app, "service start") { car, wifi -> core.reconcile(car, wifi, now()) }
        }
    }

    fun serviceStopped() {
        executor.execute {
            watching = false
            tick?.cancel(false)
            tick = null
        }
    }

    /** Validated Wi-Fi came back. */
    fun wifiReturned(context: Context) {
        val app = context.applicationContext
        executor.execute {
            automatic(app, "wifi returned") { car, _ -> core.wifiReturned(car, now()) }
        }
    }

    /** Validated Wi-Fi has stayed gone for the grace period. */
    fun wifiGone(context: Context) {
        val app = context.applicationContext
        executor.execute {
            automatic(app, "wifi gone") { car, wifi -> if (wifi) emptyList() else core.wifiGone(car) }
        }
    }

    /**
     * The stock client announced a status change. A hint to read, never a fact to act on: its
     * delivery to an ordinary app is unproven, and nothing here depends on it arriving.
     */
    fun hint(context: Context) {
        val app = context.applicationContext
        executor.execute {
            automatic(app, "status broadcast") { car, wifi -> core.reconcile(car, wifi, now()) }
        }
    }

    /**
     * One automatic pass: read, ask the core, carry out, schedule the next reading.
     *
     * The switch is re-read here, on this thread, because an automatic task may have been queued
     * before the driver switched off - and a switched-off link says nothing to the car.
     */
    private fun automatic(
        app: Context,
        reason: String,
        decide: (CloudCarState, Boolean) -> List<CloudStep>,
    ) {
        try {
            if (!CloudLinkSettings.isEnabled(app)) return
            val car = read(app)
            val steps = decide(car, CloudWifi.validated(app))
            if (steps.isNotEmpty()) {
                Log.i(TAG, "$reason: $steps gate=${core.gate} attempts=${core.attempts}")
                run(app, steps)
                if (CloudStep.AnnounceReady in steps) followUp(app)
            }
        } catch (error: Exception) {
            Log.i(TAG, "$reason failed", error)
        } finally {
            publish()
            schedule(app)
        }
    }

    /**
     * A press: grey the switches, do it, and let go.
     *
     * [block] answers whether the car took it. One that did not - refused, or never reached because
     * the shell itself failed - leaves [failure] on the tile until a later press is taken; a press
     * with no [failure] of its own (Wi-Fi in sleep) is answered by its switch reading the car back.
     */
    private fun explicit(failure: String?, block: () -> Boolean) {
        pressesInFlight.incrementAndGet()
        CloudLinkRuntime.busy = true
        publish()
        executor.execute {
            try {
                val taken = runCatching(block)
                    .onFailure { Log.w(TAG, "switch failed", it) }
                    .getOrDefault(false)
                if (failure != null) CloudLinkRuntime.failure = if (taken) null else failure
            } finally {
                CloudLinkRuntime.busy = pressesInFlight.decrementAndGet() > 0
                publish()
            }
        }
    }

    /**
     * Carry the steps out in order and stop at the first the car refuses. True when all were taken.
     *
     * A profile change is trusted only once the car reads it back: the broadcast is answered by
     * `com.android.phone`, and a completed broadcast is not yet a written property.
     */
    private fun run(app: Context, steps: List<CloudStep>): Boolean {
        for (step in steps) {
            val taken = when (step) {
                CloudStep.UseWifiProfile -> switchProfile(app, CloudLinkProtocol.WIFI_PROFILE) { it.wifiProfile }
                is CloudStep.RestoreProfile -> switchProfile(app, step.profile) { it.onStockProfile }
                CloudStep.AnnounceReady -> notify(app, CloudLinkProtocol.READY).also { taken ->
                    if (taken) core.readySent(now())
                }
                CloudStep.AnnounceGone -> notify(app, CloudLinkProtocol.GONE).also { taken ->
                    if (taken) {
                        core.goneSent()
                        Thread.sleep(GONE_SETTLE_MS)
                    }
                }
            }
            Log.i(TAG, "step=$step taken=$taken")
            if (!taken) return false
        }
        return true
    }

    private fun switchProfile(app: Context, profile: String, done: (CloudCarState) -> Boolean): Boolean {
        val output = shell(app, CloudLinkProtocol.profileCommand(profile))
        if (!CloudLinkProtocol.profileAccepted(output)) {
            Log.w(TAG, "profile $profile refused: ${output.trim()}")
            return false
        }
        Thread.sleep(PROFILE_SETTLE_MS)
        return done(read(app))
    }

    private fun notify(app: Context, state: Int): Boolean {
        val output = shell(app, CloudLinkProtocol.notifyCommand(state))
        return CloudLinkProtocol.notifyAccepted(output).also { taken ->
            if (!taken) Log.w(TAG, "notify $state refused: ${output.trim()}")
        }
    }

    private fun read(app: Context): CloudCarState =
        CloudLinkProtocol.parseRead(shell(app, CloudLinkProtocol.readCommand())).also {
            CloudLinkRuntime.car = it
        }

    private fun shell(app: Context, command: String): String =
        DenzaLocalAdb.client(app).shell(command)

    /** Readings to catch the connection a «ready» should bring, so the tile turns without a wait. */
    private fun followUp(app: Context) {
        FOLLOW_UP_MS.forEach { delay ->
            executor.schedule({
                runCatching { read(app) }
                publish()
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    /** The next reading, while the service watches. */
    private fun schedule(app: Context) {
        tick?.cancel(false)
        tick = null
        if (!watching || !CloudLinkSettings.isEnabled(app)) return
        val car = CloudLinkRuntime.car
        val waiting = car?.connected != true && CloudWifi.validated(app)
        tick = executor.schedule({
            automatic(app, "tick") { reading, wifi -> core.reconcile(reading, wifi, now()) }
        }, if (waiting) WATCH_MS else IDLE_MS, TimeUnit.MILLISECONDS)
    }

    private fun publish() = DenzaAppRepository.refresh()

    private fun now(): Long = SystemClock.elapsedRealtime()
}
