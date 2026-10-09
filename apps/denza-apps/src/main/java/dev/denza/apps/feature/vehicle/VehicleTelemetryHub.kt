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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

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
 * loop runs for the life of the process ([VehicleWatcher.LEDGER]); who is watching
 * sets the cadence ([VehicleSweepCadence]), and a watcher that appears cuts a
 * backoff short ([VehicleBackoff]).
 *
 * Threading: the loop runs on [Dispatchers.IO] and only ever writes [snapshot];
 * the renderer reads it from the main thread. [VehiclePollLoopGate] also keeps a
 * cancelled loop inside the single-writer boundary until a blocking shell call
 * has actually returned and its `finally` block has flushed the journal.
 *
 * That same thread also writes [VehicleCapture] — this sweep on disk while the host has left a
 * marker for it, and nothing at all while it has not. It adds no call to the batch.
 */
internal class VehicleTelemetryHub(context: Context) {

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The buckets on disk, and the batch waiting to join them.
     *
     * Batching is not an optimisation, it is the durability decision: everything
     * before the last flush survives the ignition, so the batch size is the size
     * of the hole a sudden power cut leaves. Ten buckets is a kilometre of road,
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
     * The sweep, written down, on the drives nobody can attach a laptop to.
     *
     * Off unless the host has left a marker in `files/vehicle-capture/` ([VehicleCapture]), which
     * is why it can sit in the loop unconditionally: with no marker it is an `exists()` a minute
     * and nothing else. It is handed the map that is about to be published and asks the car
     * nothing of its own.
     */
    private val capture = VehicleCapture.of(app.filesDir, SystemClock::elapsedRealtime) { why ->
        Log.w(TAG, "Запись сигналов машины остановлена: $why")
    }

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
     * which is why that one is volatile and the set is not. What a claim coming or going asks of
     * the loop is decided by [VehicleClaims], and only carried out here.
     */
    private val claims = VehicleClaims()

    @Volatile
    private var polling = false

    /**
     * How long the loop waits between sweeps, which is decided by who is watching.
     *
     * Read by the loop on its own thread and written by the views on theirs, so it is volatile for
     * the same reason [polling] is.
     */
    @Volatile
    private var sweepMs = VehicleSweepCadence.LEDGER_INTERVAL_MS

    @Volatile
    private var forceCold = false

    /** The loop's wait after a failed read; [setActive] wakes it from the views' thread. */
    private val backoff = VehicleBackoff()

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
     * A bucket closed. Hold it until the batch is worth a write.
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
     * Seed the buckets from disk, once, as soon as the car says where it is.
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
     * A screen that has just appeared asks for a full cold sweep immediately rather than
     * leaving slow-changing rows dashed for ten seconds - it costs one longer batch, and it is
     * what makes a page that has just been swiped to arrive with its temperatures on it. A claim
     * that draws nothing ([VehicleWatcher.onScreen] false) has no cold row to wait for: the trip
     * clock reads only the park switch, which is hot and in every sweep, and the ledger's claim
     * starts a loop whose first sweep is a cold one anyway.
     *
     * [VehicleWatcher.LEDGER] is claimed at the application's start and never released, so the
     * loop is always running and the cadence is what changes when a screen comes and goes
     * ([VehicleSweepCadence]). For the same reason [start] finds the loop running and does
     * nothing, so the attempt the new consumer is owed has to come from [VehicleBackoff.wake]:
     * a loop asleep in a backoff after a run of failures would otherwise leave the screen on its
     * last snapshot for up to a minute after the car had come back.
     */
    fun setActive(watcher: VehicleWatcher, value: Boolean) {
        val change = claims.set(watcher, value) ?: return
        polling = change.polling
        sweepMs = change.sweepMs
        if (change.coldAtOnce) forceCold = true
        if (change.wake) {
            start()
            backoff.wake()
        }
        if (change.stop) stop()
    }

    private suspend fun CoroutineScope.pollLoop() {
        var shell: LocalAdbClient.PersistentShellSession? = null
        val clock = VehicleSweepClock()
        var coldDueAt = 0L
        // A new loop owes nobody a wait, and a wake left over from before it is spent.
        backoff.reset()
        val cold = LinkedHashMap<VehicleSignal, Double>()
        val link = VehicleLink()
        // A loop that has just started has never looked at the marker, and the minute between
        // checks would be a lie about a process that came up a moment ago.
        capture.recheck()
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
                    val failure = if (error is LocalAdbClient.AuthorizationRequiredException) {
                        VehicleReadFailure.AUTHORIZATION
                    } else {
                        VehicleReadFailure.CHANNEL
                    }
                    if (link.failed(failure, SystemClock.elapsedRealtime())) {
                        publishUnavailable(failure, error)
                    } else {
                        Log.i(TAG, "Чтение машины сорвалось: ${error.javaClass.simpleName} ${error.message}")
                        publishDropped(cold)
                    }
                    // The timeout, this wait and the reconnect after it are time nobody watched.
                    clock.interrupted()
                    backoff.await()
                    continue
                }

                backoff.reset()
                val parsed = AutoserviceShell.parse(output, batch)

                if (includeCold) {
                    forceCold = false
                    coldDueAt = SystemClock.elapsedRealtime() + COLD_INTERVAL_MS
                    VehicleColdSweep.rebuild(cold, parsed)
                }

                restoreOnce(parsed[VehicleSignal.ODOMETER_KM])

                val now = SystemClock.elapsedRealtime()
                val merged = VehicleAnsweredSweep.feed(
                    parsed = parsed,
                    cold = cold,
                    atMillis = now,
                    dtSeconds = clock.tick(now),
                    log = log,
                    ledger = ledger,
                    trace = trace,
                )
                saveTrip(now)

                // A shell that answered with nothing in it - every id a sentinel and no cold value
                // carried - is a failed read like a timeout, not a closed shell: the bus is quiet,
                // and VehicleLink decides when quiet has lasted long enough to say so.
                if (merged.isEmpty()) {
                    if (link.failed(VehicleReadFailure.NO_ANSWER, now)) {
                        publishUnavailable(VehicleReadFailure.NO_ANSWER)
                    } else {
                        publishDropped(cold)
                    }
                } else {
                    link.answered(now)
                    snapshot = VehicleAnsweredSweep.snapshot(merged, log, ledger, trace)
                }

                // Last, and out of the same map the panel is now reading: a recording of what the
                // screen showed, not of something computed beside it. It never throws and it never
                // writes more than a row a second, whatever this cadence is.
                capture.sample(merged)

                delay(sweepMs)
            }
        } finally {
            flush()
            ledger.record()?.let(tripJournal::save)
            capture.close()
            shell?.runCatching { close() }
        }
    }

    /**
     * A read failed and the link is not closed: the figures go, the captions and the history stay.
     *
     * See [VehicleDroppedRead]. Everything the hub holds is handed on for the reason it is in
     * [publishUnavailable].
     */
    private fun publishDropped(cold: Map<VehicleSignal, Double>) {
        snapshot = VehicleDroppedRead.snapshot(
            previous = snapshot.access,
            cold = cold,
            consumption = log.window,
            chart = ConsumptionChart.of(log.buckets),
            engineTrace = trace.snapshot(),
            trip = ledger.trip,
        )
    }

    private fun publishUnavailable(failure: VehicleReadFailure, error: Throwable? = null) {
        val message = when (failure) {
            VehicleReadFailure.AUTHORIZATION -> AUTHORIZATION_REQUIRED
            VehicleReadFailure.CHANNEL -> NO_CHANNEL
            VehicleReadFailure.NO_ANSWER -> NO_ANSWER
        }
        if (snapshot.access != VehicleAccess.UNAVAILABLE || snapshot.message != message) {
            Log.w(TAG, "Данные машины недоступны: $failure ${error?.javaClass?.simpleName} ${error?.message}")
        }
        // Everything the hub still holds goes with it. The engine trace used to be left out, so a
        // four-second backoff swapped the right shelf out of the box and back - defeating the
        // hundred and twenty seconds of hysteresis the trace's own length exists to give it.
        val window = log.window
        snapshot = VehicleTelemetry(
            access = VehicleAccess.UNAVAILABLE,
            message = message,
            consumption = window,
            chart = ConsumptionChart.of(log.buckets),
            engineTrace = trace.snapshot(),
            trip = ledger.trip,
        )
    }

    private companion object {
        /**
         * Buckets per journal write: every one.
         *
         * It was ten - a kilometre of road a write - and the head unit does not stop the process
         * politely when the car is switched off, so up to nine hundred metres of the end of every
         * drive were lost with the batch, which is a hole at the end of every drive on a chart
         * that now exists to have none. A line of thirty-six bytes per hundred metres is nothing.
         */
        const val FLUSH_EVERY = 1

        /** How often the trip record is made durable. See [saveTrip]. */
        const val TRIP_SAVE_MS = 10_000L

        const val TAG = "DenzaVehicle"

        const val COLD_INTERVAL_MS = 10_000L

        const val HOT_TIMEOUT_MS = 3_000
        const val COLD_TIMEOUT_MS = 8_000

        const val AUTHORIZATION_REQUIRED = "ADB-ключ не подтверждён · откройте Denza Apps"

        /**
         * The two the panel says when the link is closed for anything but the key: a state each,
         * in the driver's words. They were «Нет связи с локальным ADB» - the app's own plumbing on
         * the cluster - and «Машина не ответила ни на один запрос».
         */
        const val NO_CHANNEL = "Нет связи с машиной"
        const val NO_ANSWER = "Машина не отвечает"
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
 * The wait after a failed read, and the one thing allowed to cut it short: somebody starting to
 * watch.
 *
 * Doubling from [FIRST_MS] to [MAX_MS], so a car asleep costs a shell round trip a minute. Until
 * [VehicleWatcher.LEDGER] that wait never met a screen: the loop ran only while one was watching,
 * and a screen that appeared started a fresh loop with an attempt at once. Since 2026-09-18 the
 * loop lives as long as the process, and a screen that appeared after a run of failures - adbd
 * not up yet at boot, the key not confirmed until the driver did it - found the loop asleep for
 * up to a minute after the car had come back, with nothing on the panel to say why.
 *
 * So [wake] ends the current wait and the next one starts again from [FIRST_MS]. A wake that
 * arrives while no wait is running is kept for the next one - the read in flight may be failing -
 * and spent by [reset] if that read answers instead, so a screen that appeared during a good
 * stretch does not cut a backoff minutes later.
 */
internal class VehicleBackoff {

    private val wakes = Channel<Unit>(Channel.CONFLATED)

    /** How long the next failed read waits. */
    var nextMs: Long = FIRST_MS
        private set

    /** Waits out [nextMs], or less if somebody starts watching; whether it was cut short. */
    suspend fun await(): Boolean {
        val woken = withTimeoutOrNull(nextMs) { wakes.receive() } != null
        nextMs = if (woken) FIRST_MS else (nextMs * 2).coerceAtMost(MAX_MS)
        return woken
    }

    /** A read answered, or the loop is new: the next failure waits [FIRST_MS] and no wake is owed. */
    fun reset() {
        nextMs = FIRST_MS
        wakes.tryReceive()
    }

    /** Somebody has just started watching. Any thread. */
    fun wake() {
        wakes.trySend(Unit)
    }

    companion object {
        const val FIRST_MS = 4_000L
        const val MAX_MS = 60_000L
    }
}

/** Why a sweep brought nothing back. */
internal enum class VehicleReadFailure {
    /** The local ADB key is not confirmed. Nothing but the driver changes that. */
    AUTHORIZATION,

    /** The shell itself failed: a timeout, a refused connection, a socket reset. */
    CHANNEL,

    /** The shell answered and nothing in the answer was a reading. */
    NO_ANSWER,
}

/**
 * When failed reads stop being a dropped read and become a link closed to us.
 *
 * `docs/energy-display-contract.md` §4 has two states for this and the hub used to know one of them:
 * any exception from the shell - a hot read's three-second timeout included - and any answer of
 * nothing but sentinels went straight to [VehicleAccess.UNAVAILABLE]. Both screens then put a
 * sentence where their instruments were for at least the four seconds of the first backoff, and
 * came back - once per hiccup. A dropped read is the other state: the figures leave on their own
 * horizons and the captions stay, which is what the Contour's one staleness rule already draws for
 * a hub that has gone quiet.
 *
 * So the link is closed by one of two things. A missing key closes it at once: it is an instruction
 * for the driver, and waiting would only delay it. Anything else closes it once the reads have
 * failed [CLOSE_AFTER_FAILURES] times in a row **and** nothing has answered for [CLOSE_AFTER_MS].
 * The count keeps a single failed read, however long its timeout, a dropped one - a cold read alone
 * takes eight seconds to fail. The time keeps a quiet bus, which fails a sweep every hundred
 * milliseconds, a dropped read for long enough that the panel has taken every live figure down by
 * itself first. Once closed it stays closed until something answers.
 */
internal class VehicleLink {

    /** The last answer, or the first failure when nothing has answered since the loop started. */
    private var quietSince = NEVER
    private var failures = 0
    private var closed = false

    fun answered(nowMillis: Long) {
        quietSince = nowMillis
        failures = 0
        closed = false
    }

    /** Whether the link is closed after this failure; false while it is still a dropped read. */
    fun failed(failure: VehicleReadFailure, nowMillis: Long): Boolean {
        if (quietSince == NEVER) quietSince = nowMillis
        failures++
        if (failure == VehicleReadFailure.AUTHORIZATION ||
            (failures >= CLOSE_AFTER_FAILURES && nowMillis - quietSince >= CLOSE_AFTER_MS)
        ) {
            closed = true
        }
        return closed
    }

    companion object {
        /** A second failure in a row: for the shell that is the retry after the first backoff. */
        const val CLOSE_AFTER_FAILURES = 2

        /**
         * Two hot horizons of silence ([VehiclePoll.HOT]). By then every live figure on the Contour
         * has gone by its own rule and the link-lost picture has stood for a full horizon; the first
         * backoff ([VehicleBackoff.FIRST_MS]) is this long too, so a shell left to its backoff is
         * closed by its second failure, and one a new screen woke early is not closed sooner.
         */
        val CLOSE_AFTER_MS: Long = (VehiclePoll.HOT.staleSeconds * 2 * 1000).toLong()

        private const val NEVER = Long.MIN_VALUE
    }
}

/**
 * What one answered sweep means, from the ids it parsed to the snapshot both screens draw.
 *
 * The poll loop keeps the transport, the clock, the files and the link; the rest is here. It used to
 * be inline in the loop, so the one place every energy figure on the cluster and the car page is put
 * together - the load's sign, the park flag the trip is told, which list is the window and which the
 * chart, which values carry - had no test, and `VehicleLogReplayTest` replayed a copy of it rather
 * than it. A copy agrees with itself: a chart built from the window instead of the log's whole
 * retention passed both. The hub and the replay run these lines now, and `VehicleAnsweredSweepTest`
 * states what they decide.
 */
internal object VehicleAnsweredSweep {

    /**
     * Feeds one sweep to the trace, the log and the ledger, and returns the values the screens are
     * shown: the cold ones [cold] carries and this sweep's hot ones. Empty when nothing answered.
     */
    fun feed(
        parsed: Map<VehicleSignal, Double>,
        cold: Map<VehicleSignal, Double>,
        atMillis: Long,
        dtSeconds: Double,
        log: ConsumptionLog,
        ledger: TripEnergyLedger,
        trace: EngineTrace,
    ): Map<VehicleSignal, Double> {
        val engineRunning = parsed[VehicleSignal.ENGINE_RUNNING]?.let { it >= 1.0 }
        // Sampled on every sweep. The engine's box is up for the slots this flag was true in: the
        // rpm and generation ids used to decide that and one of them is not zero on an electric
        // drive.
        trace.sample(
            atMillis = atMillis,
            engineRunning = engineRunning,
            generationKw = parsed[VehicleSignal.GENERATION_KW],
        )
        val load = VehicleConvention.load(parsed[VehicleSignal.POWER_KW])
        log.sample(
            odometerKm = parsed[VehicleSignal.ODOMETER_KM],
            powerKw = load,
            dtSeconds = dtSeconds,
            // This sweep's own reading, out of the same batch as the power it decides the fate of.
            // A standing interval's energy is the trip's and not the road's, and an absent reading
            // counts as moving (contract §2.2).
            speedKmh = parsed[VehicleSignal.VEHICLE_SPEED],
        )
        ledger.sample(
            odometerKm = parsed[VehicleSignal.ODOMETER_KM],
            powerKw = load,
            generationKw = parsed[VehicleSignal.GENERATION_KW],
            engineRunning = engineRunning,
            parked = parsed[VehicleSignal.GEARBOX_PARK]?.let { it >= 1.0 },
            dtSeconds = dtSeconds,
        )

        // Cold values carry across a *hot* sweep — temperatures do not change in a second. Hot
        // values never do: they are either fresh or absent, so the dashboard cannot show a stale
        // kilowatt figure.
        //
        // What they do not carry across is a *cold* sweep: VehicleColdSweep.rebuild clears the map
        // and refills it from what that sweep answered, so a temperature that stopped answering
        // leaves the snapshot the way a power reading does. That is the invariant ContourScene's
        // one staleness rule stands on, and it is why VehiclePoll.COLD's horizon is two of its own
        // intervals rather than two seconds.
        //
        // A fresh map per sweep, deliberately. The snapshot published a moment ago is still being
        // read by the panel's frame loop, so a map shared with the next one would change under a
        // reader. This is four allocations a second against a panel that draws sixty times in that
        // second: the cost worth chasing was never here.
        val merged = LinkedHashMap<VehicleSignal, Double>(cold)
        VehicleSignal.HOT.forEach { signal -> parsed[signal]?.let { merged[signal] = it } }
        return merged
    }

    /**
     * The snapshot of an answered sweep: its [values] and the history the records hold now.
     *
     * The window and its chart are built once here, beside the rest of the snapshot. Both screens
     * draw the same hundred points, and a chart built inside `onDraw` would be a hundred trailing
     * means allocated sixty times a second over a quantity the car answers four times a second. The
     * chart reads the log's whole retention rather than the window: its points are the window's own
     * readings, and the ten readings behind the oldest of them are road the window no longer
     * reaches.
     */
    fun snapshot(
        values: Map<VehicleSignal, Double>,
        log: ConsumptionLog,
        ledger: TripEnergyLedger,
        trace: EngineTrace,
    ): VehicleTelemetry = VehicleTelemetry(
        access = VehicleAccess.READY,
        values = values,
        consumption = log.window,
        chart = ConsumptionChart.of(log.buckets),
        engineTrace = trace.snapshot(),
        trip = ledger.trip,
    )
}

/**
 * What the hub publishes for a read that failed before the link is closed ([VehicleLink]).
 *
 * No hot value, because nothing answered; the cold values the last cold sweep left, because they
 * carry across a sweep that did not ask for them as they always have; the history and the trip as
 * the hub holds them; and [VehicleTelemetry.dropped], because to the Contour this is not a packet.
 * Its ages keep running across it, so its figures leave on their own horizons exactly as they do for
 * a hub that has stopped answering, while the car page, which reads each snapshot as it comes, loses
 * its live figures at once and keeps its captions. Nothing said by the car before the failure is
 * claimed as said after it.
 *
 * A panel that has heard nothing yet is still starting: a failure is not an answer.
 */
internal object VehicleDroppedRead {

    fun snapshot(
        previous: VehicleAccess,
        cold: Map<VehicleSignal, Double>,
        consumption: List<ConsumptionSample>,
        chart: ConsumptionChartSnapshot,
        engineTrace: EngineTraceSnapshot,
        trip: TripEnergy,
    ): VehicleTelemetry = VehicleTelemetry(
        access = if (previous == VehicleAccess.STARTING) VehicleAccess.STARTING else VehicleAccess.READY,
        // A copy: the loop's cold map is rebuilt in place by the next cold sweep, under a reader.
        values = LinkedHashMap(cold),
        consumption = consumption,
        chart = chart,
        engineTrace = engineTrace,
        trip = trip,
        dropped = true,
    )
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
 * The four things that ask this car for numbers.
 *
 * They are named rather than counted because they are not interchangeable: the cluster's claim
 * lasts as long as the driver's display is showing our panel, the strip's lasts only while its
 * second page is on screen, the trip clock's while the strip runs at all, and the ledger's lasts as
 * long as the process. A reference count would have told the hub how many claims there are and
 * nothing about what to do when one of them misbehaves - or about how fast to sweep for it.
 */
internal enum class VehicleWatcher(
    /** Whether somebody is looking at these numbers, which is what sets the cadence. */
    val onScreen: Boolean,
) {
    /** The instrument panel on the driver's display. */
    CLUSTER(onScreen = true),

    /** The head unit's strip, while it is drawing the car's page. */
    STRIP(onScreen = true),

    /**
     * The head unit's trip clock, while the strip runs on either page: a trip ends on P, and the
     * clock reads P from this hub's sweep ([VehicleSignal.GEARBOX_PARK], hot, so in every sweep).
     *
     * Until 2026-10-09 the clock polled the same id itself, once a second over a shell of its own,
     * beside this loop reading it anyway. Not a screen as far as cadence goes: the clock wants P
     * about once a second, which is the ledger's own cadence, and the strip's car page holds
     * [STRIP] when it wants four readings a second. Claimed beside [LEDGER] rather than left to it,
     * so the clock's need is stated where the loop can see it, and so a strip that comes up ends a
     * backoff it finds ([VehicleBackoff.wake]), as the clock's own reader used to start with a read.
     */
    TRIP(onScreen = false),

    /**
     * The road, recorded whether or not anyone looks.
     *
     * `docs/energy-display-contract.md` §2.7. The hub polled only while a screen watched it, so
     * the owner's photograph of 2026-09-18 had 1.4 km of its ten missing and 4.7 km more went
     * missing the moment it was taken - the car's page was swiped away and the cluster had not
     * been brought up since the build was replaced a week earlier. A ten-kilometre history that
     * exists only while it is being looked at is not a history, and neither is a trip ledger.
     *
     * Claimed by `DenzaAppsApplication` at start and never released.
     */
    LEDGER(onScreen = false),
}

/**
 * Who holds a claim on the hub, and what one claim coming or going asks of its loop.
 *
 * Out of [VehicleTelemetryHub.setActive], which only carries the answer out, because the hub cannot
 * be built off the car and these are the rules a claim lives by: the loop polls while anybody
 * holds one, at the cadence of who holds them ([VehicleSweepCadence]); a claim that arrives ends a
 * backoff it finds and, if it is a screen, is owed a cold sweep at once; the last one to go stops
 * the loop. Main thread only, like the views that make the claims.
 */
internal class VehicleClaims {
    private val watchers = HashSet<VehicleWatcher>()

    /** What the loop is to do now; null when the claim already stood as asked. */
    fun set(watcher: VehicleWatcher, value: Boolean): Change? {
        val changed = if (value) watchers.add(watcher) else watchers.remove(watcher)
        if (!changed) return null
        return Change(
            polling = watchers.isNotEmpty(),
            sweepMs = VehicleSweepCadence.intervalMs(watchers),
            coldAtOnce = value && VehicleSweepCadence.coldAtOnce(watcher),
            wake = value,
            stop = !value && watchers.isEmpty(),
        )
    }

    data class Change(
        val polling: Boolean,
        val sweepMs: Long,
        /** A full cold sweep at once, rather than at the next ten-second mark. */
        val coldAtOnce: Boolean,
        /** Start the loop if it is not running, and end a backoff it is waiting out. */
        val wake: Boolean,
        /** Nobody is left: stop the loop. */
        val stop: Boolean,
    )
}

/**
 * How fast the loop sweeps, which is decided by who is watching and by nothing else.
 *
 * One function rather than a branch inside the loop: the cadence is a rule about the watchers, and
 * the loop is the one place in this file where a rule cannot be read.
 */
internal object VehicleSweepCadence {

    /**
     * Measured on the car: a batch costs about 130 ms of fixed shell and process overhead plus
     * 4–5 ms per call, so the hot batch takes roughly 150 ms whatever the interval. The interval is
     * therefore the only real cost knob. It was 300 ms - a fresh power figure about twice a second
     * - and the owner, who had driven with the previous panel, asked for the live figures to answer
     * about twice as fast. At 100 ms the cycle is about 250 ms, four readings a second, and the
     * shell is busy some sixty per cent of the time while a screen is up; nothing else in the app
     * runs it.
     */
    const val HOT_INTERVAL_MS = 100L

    /**
     * And what the road costs when nobody is looking: one sweep a second.
     *
     * The integral needs no more - a kilowatt figure a second over a hundred-metre bucket is
     * several readings a bucket at any speed - and a shell round trip is the price, so the car
     * asleep in a garage costs one command a second and then the backoff, which is the same
     * backoff a screen would meet.
     */
    const val LEDGER_INTERVAL_MS = 1_000L

    fun intervalMs(watchers: Set<VehicleWatcher>): Long =
        if (watchers.any { it.onScreen }) HOT_INTERVAL_MS else LEDGER_INTERVAL_MS

    /**
     * Whether a claim that has just appeared is owed a cold sweep at once: a screen, which has
     * temperatures to show and would otherwise dash them for ten seconds. See
     * [VehicleTelemetryHub.setActive].
     */
    fun coldAtOnce(watcher: VehicleWatcher): Boolean = watcher.onScreen
}

/**
 * Process-scoped owner of the vehicle hub, mirroring
 * [dev.denza.apps.feature.trip.TripSession]: the view attaches and detaches, while
 * the hub outlives activity recreation. Closed consumption buckets also survive a
 * process restart through [ConsumptionJournal]; the short engine trace does not.
 */
internal object VehicleSession {
    private var hub: VehicleTelemetryHub? = null

    fun hub(context: Context): VehicleTelemetryHub =
        hub ?: VehicleTelemetryHub(context.applicationContext).also { hub = it }

    /**
     * Start recording the road, for the life of the process.
     *
     * `docs/energy-display-contract.md` §2.7. Called once from the application; there is no
     * release, because the claim is not about a view being on screen. The hub itself decides what
     * that costs - a sweep a second while nothing is drawn ([VehicleSweepCadence]).
     */
    fun record(context: Context) {
        hub(context).setActive(VehicleWatcher.LEDGER, true)
    }
}
