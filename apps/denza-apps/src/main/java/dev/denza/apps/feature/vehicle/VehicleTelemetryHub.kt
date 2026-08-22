package dev.denza.apps.feature.vehicle

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.disharebridge.LocalAdbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls the native `autoservice` allowlist over the local ADB shell and
 * publishes one immutable [VehicleTelemetry] snapshot for the panel to draw.
 *
 * Identity, and why this is not in the app process: these values exist on the
 * `android.gui.BYDAutoServer` Binder, which answers a trusted `shell` UID and
 * refuses ours. The app therefore asks the same way a diagnostic session would —
 * `service call` through [DenzaLocalAdb], whose policy is PASSIVE, so a missing
 * key produces a closed panel and never an authorization prompt. No `BYDAUTO_*`
 * permission is declared, no `app_process` proxy is spawned, and only the read
 * transacts (5 and 7) are ever issued. See docs/vehicle-data-findings.md.
 *
 * Cadence: the hot set (power, charge, voltages, odometer) is one small batched
 * command; the cold set (pack, drivetrain, tyres, cabin, charging) joins it
 * every few seconds. While the vehicle page is not the one on screen the hub
 * keeps running at a slow cadence — that is what keeps the consumption
 * histogram continuous — and it stops completely with the panel.
 *
 * Threading: the loop runs on [Dispatchers.IO] and only ever writes [snapshot];
 * the renderer reads it from the main thread.
 */
internal class VehicleTelemetryHub(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val log = ConsumptionLog()

    @Volatile
    var snapshot: VehicleTelemetry = VehicleTelemetry()
        private set

    @Volatile
    private var active = false

    @Volatile
    private var forceCold = false

    private var job: Job? = null

    val running: Boolean get() = job?.isActive == true

    /**
     * True once the vehicle page has actually been looked at. Before that the
     * hub never opens a shell: a session that only ever uses the trip page
     * should cost the car nothing. Afterwards it keeps polling in the background
     * so the consumption histogram survives a swipe away and back.
     */
    var visited: Boolean = false
        private set

    fun start() {
        if (running) return
        job = scope.launch { pollLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * True while the vehicle page is the visible one. Only the poll cadence
     * depends on it; the loop itself is owned by the panel's lifecycle.
     */
    fun setActive(value: Boolean) {
        if (value) visited = true
        if (active == value) return
        active = value
        if (value) forceCold = true
    }

    private suspend fun CoroutineScope.pollLoop() {
        var shell: LocalAdbClient.PersistentShellSession? = null
        var lastSampleAt = 0L
        var coldDueAt = 0L
        var backoffMs = FIRST_BACKOFF_MS
        val cold = LinkedHashMap<VehicleSignal, Double>()
        try {
            while (isActive) {
                val session = shell ?: DenzaLocalAdb.client(app).openPersistentShell().also { shell = it }
                val includeCold = forceCold || SystemClock.elapsedRealtime() >= coldDueAt
                val batch = if (includeCold) VehicleSignal.HOT + VehicleSignal.COLD else VehicleSignal.HOT
                val startedAt = SystemClock.elapsedRealtime()

                val output = try {
                    session.shell(
                        AutoserviceShell.command(batch),
                        if (includeCold) COLD_TIMEOUT_MS else HOT_TIMEOUT_MS,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    shell?.runCatching { close() }
                    shell = null
                    publishUnavailable(error)
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }

                backoffMs = FIRST_BACKOFF_MS
                val sweepMillis = (SystemClock.elapsedRealtime() - startedAt).toInt()
                val parsed = AutoserviceShell.parse(output, batch)

                if (includeCold) {
                    forceCold = false
                    coldDueAt = SystemClock.elapsedRealtime() +
                        if (active) ACTIVE_COLD_MS else IDLE_COLD_MS
                    VehicleSignal.COLD.forEach { signal -> parsed[signal]?.let { cold[signal] = it } }
                }

                val now = SystemClock.elapsedRealtime()
                val dtSeconds = if (lastSampleAt == 0L) 0.0 else (now - lastSampleAt) / 1000.0
                lastSampleAt = now
                log.sample(
                    odometerKm = parsed[VehicleSignal.ODOMETER_KM],
                    powerKw = VehicleConvention.load(parsed[VehicleSignal.POWER_KW]),
                    dtSeconds = dtSeconds,
                )

                // Cold values carry over between sweeps — temperatures do not
                // change in a second. Hot values never do: they are either fresh
                // or absent, so the panel can't show a stale kilowatt figure.
                val merged = LinkedHashMap<VehicleSignal, Double>(cold)
                VehicleSignal.HOT.forEach { signal -> parsed[signal]?.let { merged[signal] = it } }

                snapshot = VehicleTelemetry(
                    access = if (merged.isEmpty()) VehicleAccess.UNAVAILABLE else VehicleAccess.READY,
                    message = if (merged.isEmpty()) NO_ANSWER else "",
                    values = merged,
                    consumption = log.buckets,
                    currentConsumption = log.current,
                    sweepMillis = sweepMillis,
                )

                delay(if (active) ACTIVE_HOT_MS else IDLE_HOT_MS)
            }
        } finally {
            shell?.runCatching { close() }
        }
    }

    private fun publishUnavailable(error: Throwable) {
        val message = when (error) {
            is LocalAdbClient.AuthorizationRequiredException -> AUTHORIZATION_REQUIRED
            else -> NO_CHANNEL
        }
        if (snapshot.access != VehicleAccess.UNAVAILABLE || snapshot.message != message) {
            Log.w(TAG, "Данные машины недоступны: ${error.javaClass.simpleName} ${error.message}")
        }
        snapshot = VehicleTelemetry(
            access = VehicleAccess.UNAVAILABLE,
            message = message,
            consumption = log.buckets,
            currentConsumption = log.current,
        )
    }

    private companion object {
        const val TAG = "DenzaVehicle"

        const val ACTIVE_HOT_MS = 700L
        const val ACTIVE_COLD_MS = 10_000L
        const val IDLE_HOT_MS = 2_500L
        const val IDLE_COLD_MS = 30_000L

        const val HOT_TIMEOUT_MS = 3_000
        const val COLD_TIMEOUT_MS = 8_000

        const val FIRST_BACKOFF_MS = 4_000L
        const val MAX_BACKOFF_MS = 60_000L

        const val AUTHORIZATION_REQUIRED = "ADB-ключ не подтверждён · Помощь → Диагностика"
        const val NO_CHANNEL = "Нет связи с локальным ADB"
        const val NO_ANSWER = "Машина не ответила ни на один запрос"
    }
}

/**
 * Process-scoped owner of the vehicle hub, mirroring
 * [dev.denza.apps.feature.trip.TripSession]: the view attaches and detaches, the
 * hub and its consumption history outlive activity recreation, and nothing is
 * persisted to disk.
 */
internal object VehicleSession {
    private var hub: VehicleTelemetryHub? = null

    fun hub(context: Context): VehicleTelemetryHub =
        hub ?: VehicleTelemetryHub(context.applicationContext).also { hub = it }
}
