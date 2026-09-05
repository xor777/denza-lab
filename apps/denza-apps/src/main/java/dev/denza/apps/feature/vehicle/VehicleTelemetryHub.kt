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
import kotlinx.coroutines.sync.Mutex

/**
 * Polls the native `autoservice` allowlist over the local ADB shell and
 * publishes one immutable [VehicleTelemetry] snapshot for the cluster dashboard.
 *
 * Identity, and why this is not in the app process: these values exist on the
 * `android.gui.BYDAutoServer` Binder, which answers a trusted `shell` UID and
 * refuses ours. The app therefore asks the same way a diagnostic session would —
 * `service call` through [DenzaLocalAdb], whose policy is PASSIVE, so a missing
 * key produces an unavailable dashboard and never an authorization prompt. No
 * `BYDAUTO_*` permission is declared, no `app_process` proxy is spawned, and
 * only the read transacts (5 and 7) are ever issued. See
 * docs/vehicle-data-findings.md.
 *
 * Cadence: the hot set — pack power and voltage, the odometer, the park switch,
 * engine revolutions, engine running and generation, seven signals — is one
 * batched command every 100 ms, which is a cycle of about 250 ms and four
 * readings a second. Temperatures, cell voltages, the charging estimate and the
 * generation state join it every ten seconds. Splitting the hot set finer would
 * buy nothing — a one-call batch costs almost what a five-call batch costs. The
 * loop runs only while the cluster dashboard is visible.
 *
 * Threading: the loop runs on [Dispatchers.IO] and only ever writes [snapshot];
 * the renderer reads it from the main thread. [VehiclePollLoopGate] also keeps a
 * cancelled loop inside the single-writer boundary until a blocking shell call
 * has actually returned and its `finally` block has flushed the journal.
 */
internal class VehicleTelemetryHub(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The bars on disk, and the batch waiting to join them.
     *
     * Batching is not an optimisation, it is the durability decision: everything
     * before the last flush survives the ignition, so the batch size is the size
     * of the hole a sudden power cut leaves. Ten bars is a kilometre of road,
     * which at any speed worth measuring is well under a minute.
     */
    private val journal = ConsumptionJournal.of(app.filesDir) { why ->
        Log.w(TAG, "Журнал расхода сброшен: $why")
    }
    private val pending = ArrayList<ConsumptionSample>(FLUSH_EVERY)
    private var restored = false

    private val log = ConsumptionLog(onBucketClosed = ::record)

    /**
     * The trip on the right shelf, and its own record on disk.
     *
     * It is journalled for the same reason the bars are and against a stricter test: a trip is
     * bounded by the selector rather than by the ignition, so a process restart in the middle of a
     * drive must not reset the figure the driver is watching. [TripEnergyLedger.restore] refuses a
     * record from road this process did not see.
     */
    private val tripJournal = TripJournal.of(app.filesDir) { why ->
        Log.w(TAG, "Журнал поездки сброшен: $why")
    }
    private val ledger = TripEnergyLedger()
    private var tripSavedAt = 0L

    /**
     * Kept in memory only. The consumption journal survives a restart because it is about the road;
     * two minutes of revolutions is about right now, and a restart is long enough to make it a lie.
     */
    private val trace = EngineTrace()

    /**
     * And the same two minutes of the pack's own power, for the head unit's second page.
     *
     * Kept here rather than beside the strip for the reason every other history is: this is the
     * one place that sees every sweep, and a trace filled from the panel's frame loop would be a
     * history of when somebody was looking rather than of what the car did.
     */
    private val power = PowerTrace()

    @Volatile
    var snapshot: VehicleTelemetry = VehicleTelemetry()
        private set

    /**
     * Who is asking the car for numbers, and the loop runs while anybody is.
     *
     * There was one consumer when this was written and the flag said so. There are two now - the
     * cluster on the driver's display and the head unit's strip on its second page - and either
     * may be up without the other. A boolean cannot hold that: whichever of the two went away
     * last would have stopped the poll under the one still drawing.
     *
     * Mutated from the main thread by the views that own the claims; the loop reads [polling],
     * which is why that one is volatile and the set is not.
     */
    private val watchers = HashSet<VehicleWatcher>()

    @Volatile
    private var polling = false

    @Volatile
    private var forceCold = false

    private var job: Job? = null
    private val loopGate = VehiclePollLoopGate()

    private val running: Boolean get() = job?.isActive == true

    private fun start() {
        if (running) return
        job = scope.launch {
            loopGate.run {
                if (polling) pollLoop()
            }
        }
    }

    /**
     * Stops the poll loop.
     *
     * The loop's `finally` block flushes the pending journal batch before closing
     * its shell session.
     */
    private fun stop() {
        job?.cancel()
        job = null
        // The batch is flushed by the loop's own `finally`, not from here: the
        // journal is single-threaded by design, and a flush launched beside a
        // cancelling loop would be the one place two threads could meet in it.
    }

    /**
     * A bar closed. Hold it until the batch is worth a write.
     *
     * Called from the poll loop's own thread, which is the only thread that ever
     * touches the journal.
     */
    private fun record(sample: ConsumptionSample) {
        pending.add(sample)
        if (pending.size >= FLUSH_EVERY) flush()
    }

    private fun flush() {
        if (pending.isEmpty()) return
        journal.append(pending)
        pending.clear()
    }

    /**
     * Seed the bars from disk, once, as soon as the car says where it is.
     *
     * It waits for an odometer rather than doing this at construction because the
     * odometer is the only thing that can say whether a journal describes the last
     * thirty kilometres or a drive that happened with the app closed. A journal
     * that fails that test is not repaired, it is dropped.
     */
    private fun restoreOnce(odometerKm: Double?) {
        if (restored || odometerKm == null) return
        restored = true
        val trip = tripJournal.load()
        if (trip != null && !ledger.restore(trip, odometerKm)) {
            Log.w(TAG, "Журнал поездки от другой дороги, сброшен")
            tripJournal.clear()
        }
        val samples = journal.load()
        if (samples.isEmpty()) return
        if (!log.restore(samples, odometerKm, ConsumptionLog.RETENTION_KM)) {
            Log.w(TAG, "Журнал расхода от другого одометра, сброшен")
            journal.clear()
        }
    }

    /**
     * Persist the trip, at most every [TRIP_SAVE_MS].
     *
     * The interval is the size of the hole an ignition cut leaves in the figure, and ten seconds of
     * driving is under a hundredth of a kilowatt-hour. It costs one small `fsync` and it is a
     * rename, so a cut write leaves the previous record rather than half of this one.
     */
    private fun saveTrip(now: Long) {
        if (now - tripSavedAt < TRIP_SAVE_MS) return
        val record = ledger.record() ?: return
        tripSavedAt = now
        tripJournal.save(record)
    }

    /**
     * Called by each consumer as it appears and goes.
     *
     * A consumer that has just appeared asks for a full cold sweep immediately rather than
     * leaving slow-changing rows dashed for ten seconds - it costs one longer batch, and it is
     * what makes a page that has just been swiped to arrive with its temperatures on it.
     */
    fun setActive(watcher: VehicleWatcher, value: Boolean) {
        val changed = if (value) watchers.add(watcher) else watchers.remove(watcher)
        if (!changed) return
        polling = watchers.isNotEmpty()
        if (value) {
            forceCold = true
            start()
        } else if (!polling) {
            stop()
        }
    }

    private suspend fun CoroutineScope.pollLoop() {
        var shell: LocalAdbClient.PersistentShellSession? = null
        val clock = VehicleSweepClock()
        var coldDueAt = 0L
        var backoffMs = FIRST_BACKOFF_MS
        val cold = LinkedHashMap<VehicleSignal, Double>()
        try {
            while (isActive) {
                val session = shell ?: DenzaLocalAdb.client(app).openPersistentShell().also { shell = it }
                val includeCold = forceCold || SystemClock.elapsedRealtime() >= coldDueAt
                val hot = VehicleSignal.HOT
                val batch = if (includeCold) hot + VehicleSignal.COLD else hot
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
                    // The timeout, this wait and the reconnect after it are time nobody watched.
                    clock.interrupted()
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    continue
                }

                backoffMs = FIRST_BACKOFF_MS
                val parsed = AutoserviceShell.parse(output, batch)

                if (includeCold) {
                    forceCold = false
                    coldDueAt = SystemClock.elapsedRealtime() + COLD_INTERVAL_MS
                    VehicleColdSweep.rebuild(cold, parsed)
                }

                restoreOnce(parsed[VehicleSignal.ODOMETER_KM])

                val now = SystemClock.elapsedRealtime()
                val dtSeconds = clock.tick(now)
                // Sampled on every sweep so both traces share one time axis.
                trace.sample(
                    atMillis = now,
                    rpm = parsed[VehicleSignal.ENGINE_RPM],
                    generationKw = parsed[VehicleSignal.GENERATION_KW],
                )
                power.sample(now, VehicleConvention.load(parsed[VehicleSignal.POWER_KW]))
                log.sample(
                    odometerKm = parsed[VehicleSignal.ODOMETER_KM],
                    powerKw = VehicleConvention.load(parsed[VehicleSignal.POWER_KW]),
                    dtSeconds = dtSeconds,
                )
                val engineRunning = parsed[VehicleSignal.ENGINE_RUNNING]?.let { it >= 1.0 }
                ledger.sample(
                    odometerKm = parsed[VehicleSignal.ODOMETER_KM],
                    powerKw = VehicleConvention.load(parsed[VehicleSignal.POWER_KW]),
                    generationKw = parsed[VehicleSignal.GENERATION_KW],
                    engineRunning = engineRunning,
                    parked = parsed[VehicleSignal.GEARBOX_PARK]?.let { it >= 1.0 },
                    dtSeconds = dtSeconds,
                )
                saveTrip(now)

                // Cold values carry across a *hot* sweep — temperatures do not
                // change in a second. Hot values never do: they are either fresh
                // or absent, so the dashboard cannot show a stale kilowatt figure.
                //
                // What they do not carry across is a *cold* sweep: VehicleColdSweep.rebuild
                // clears the map and refills it from what that sweep answered, so a temperature
                // that stopped answering leaves the snapshot the way a power reading does. That
                // is the invariant ContourScene's one staleness rule stands on, and it is why
                // VehiclePoll.COLD's horizon is two of its own intervals rather than two seconds.
                //
                // A fresh map per sweep, deliberately. The snapshot published a moment ago is still
                // being read by the panel's frame loop, so a map shared with the next one would
                // change under a reader. This is four allocations a second against a panel that
                // draws sixty times in that second: the cost worth chasing was never here.
                val merged = LinkedHashMap<VehicleSignal, Double>(cold)
                VehicleSignal.HOT.forEach { signal -> parsed[signal]?.let { merged[signal] = it } }

                snapshot = VehicleTelemetry(
                    access = if (merged.isEmpty()) VehicleAccess.UNAVAILABLE else VehicleAccess.READY,
                    message = if (merged.isEmpty()) NO_ANSWER else "",
                    values = merged,
                    consumption = log.window,
                    engineTrace = trace.snapshot(),
                    powerTrace = power.snapshot(),
                    trip = ledger.trip,
                )

                delay(HOT_INTERVAL_MS)
            }
        } finally {
            flush()
            ledger.record()?.let(tripJournal::save)
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
        // Everything the hub still holds goes with it. The engine trace used to be left out, so a
        // four-second backoff swapped the right shelf out of the box and back - defeating the
        // hundred and twenty seconds of hysteresis the trace's own length exists to give it.
        snapshot = VehicleTelemetry(
            access = VehicleAccess.UNAVAILABLE,
            message = message,
            consumption = log.window,
            engineTrace = trace.snapshot(),
            powerTrace = power.snapshot(),
            trip = ledger.trip,
        )
    }

    private companion object {
        /** Bars per journal write: one kilometre of road. */
        const val FLUSH_EVERY = 10

        /** How often the trip record is made durable. See [saveTrip]. */
        const val TRIP_SAVE_MS = 10_000L

        const val TAG = "DenzaVehicle"

        /**
         * Measured on the car: a batch costs about 130 ms of fixed shell and
         * process overhead plus 4–5 ms per call, so the hot batch takes
         * roughly 150 ms whatever the interval. The interval is therefore the
         * only real cost knob. It was 300 ms - a fresh power figure about twice
         * a second - and the owner, who had driven with the previous panel,
         * asked for the live figures to answer about twice as fast. At 100 ms
         * the cycle is about 250 ms, four readings a second, and the shell is
         * busy some sixty per cent of the time while this dashboard is visible;
         * nothing else in the app runs it. Whether the car sustains that is the
         * first thing a drive with this build has to show.
         */
        const val HOT_INTERVAL_MS = 100L
        const val COLD_INTERVAL_MS = 10_000L

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
 * What one cold sweep leaves behind, which is **only what that sweep answered**.
 *
 * Rebuilt rather than merged into. A cold value that stopped answering used to sit in the carried
 * map forever, so "present in the snapshot" meant "answered at some point" for the slow rows and
 * "answered just now" for the fast ones. The Contour has one rule for a stale reading - it goes
 * two seconds after its last sample and its caption stays - and that rule needs
 * absence to mean the same thing on both cadences. That rule is `ContourScene.STALE_SECONDS`.
 *
 * It is out here for the reason [VehiclePollLoopGate] is: it is the whole of a rule the panel hangs
 * off, and inside the poll loop nothing could state it. The map is handed in rather than returned so
 * the loop keeps one instance across sweeps.
 */
internal object VehicleColdSweep {

    fun rebuild(into: MutableMap<VehicleSignal, Double>, parsed: Map<VehicleSignal, Double>) {
        into.clear()
        VehicleSignal.COLD.forEach { signal -> parsed[signal]?.let { into[signal] = it } }
    }
}

/**
 * The interval two integrals are taken over, and the rule that a failure is not one of them.
 *
 * `ConsumptionLog` and `TripEnergyLedger` both refuse an interval longer than
 * [OdometerGate.MAX_GAP_SECONDS], because multiplying one stale power reading by minutes nobody was
 * watching is the one way either of them can invent a number. That guard was defeated by the shell's
 * own failure path: nothing reset the clock there, so the first backoff - a hot timeout of three
 * seconds plus four of waiting plus a reconnect, about seven and a half in all - came back as one
 * legal-looking interval just under the eight, and 200 kW across it is 0.4 kWh of pure invention.
 *
 * It is out here for the reason [VehicleColdSweep] is: it is the whole of a rule two consumers hang
 * off, and inside the poll loop it was three lines that looked like bookkeeping.
 */
internal class VehicleSweepClock {

    private var lastAt = 0L

    /** The interval since the previous sweep, or zero when there is not one to speak of. */
    fun tick(nowMillis: Long): Double {
        val previous = lastAt
        lastAt = nowMillis
        if (previous == 0L || nowMillis <= previous) return 0.0
        return (nowMillis - previous) / 1000.0
    }

    /** Time nobody was watching. The next sweep starts a new interval rather than closing this one. */
    fun interrupted() {
        lastAt = 0L
    }
}

/**
 * Serialises poll-loop lifetimes, including their non-cancellable shell tail.
 *
 * `PersistentShellSession.shell()` is a synchronous Java call. Cancelling its
 * coroutine marks the [Job] inactive but cannot interrupt that call, so a
 * replacement loop must wait for the old call and `finally` block to leave.
 */
internal class VehiclePollLoopGate {
    private val mutex = Mutex()

    suspend fun run(block: suspend () -> Unit) {
        mutex.lock()
        try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}

/**
 * The two things that ask this car for numbers.
 *
 * They are named rather than counted because they are not interchangeable: the cluster's claim
 * lasts as long as the driver's display is showing our panel, and the strip's lasts only while its
 * second page is on screen. A reference count would have told the hub how many claims there are
 * and nothing about what to do when one of them misbehaves.
 */
internal enum class VehicleWatcher {
    /** The instrument panel on the driver's display. */
    CLUSTER,

    /** The head unit's strip, while it is drawing the car's page. */
    STRIP,
}

/**
 * Process-scoped owner of the vehicle hub, mirroring
 * [dev.denza.apps.feature.trip.TripSession]: the view attaches and detaches, while
 * the hub outlives activity recreation. Closed consumption bars also survive a
 * process restart through [ConsumptionJournal]; the short engine trace does not.
 */
internal object VehicleSession {
    private var hub: VehicleTelemetryHub? = null

    fun hub(context: Context): VehicleTelemetryHub =
        hub ?: VehicleTelemetryHub(context.applicationContext).also { hub = it }
}
