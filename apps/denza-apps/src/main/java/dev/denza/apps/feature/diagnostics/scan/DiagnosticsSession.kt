package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.ExtraPage
import dev.denza.apps.feature.diagnostics.bus.DiagFailure
import dev.denza.apps.feature.diagnostics.bus.DiagReply
import dev.denza.apps.feature.diagnostics.bus.DiagRequest
import dev.denza.apps.feature.diagnostics.bus.DiagnosticBus
import dev.denza.apps.feature.diagnostics.ecu.EcuCatalog
import dev.denza.apps.feature.diagnostics.protocol.OemDtcIndex
import java.time.ZoneId
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The diagnostic section's engine (docs/vehicle-diagnostics-architecture.md, 6 and 6.1): it lives
 * while the section is open ([start], [stop]), does what the screens ask ([DiagnosticsActions]) and
 * publishes everything as one [DiagnosticsModel].
 *
 * **One queue, one exchange at a time.** Work comes in four kinds, taken in this order whenever the
 * channel is free: a button the user pressed (read one module's DTC), the identification of a
 * module whose screen is open, the next module of the autoscan, the next exchange of the live
 * stream. A unit of work is a whole module for the first three and one exchange for the stream, so
 * the autoscan yields between modules and never inside one. While the stream's tab is visible the
 * autoscan stands; the stream and the button still run.
 *
 * **One parameter alone** (6.2, [setParam]): while a parameter page is shown, nothing but its
 * reading is polled - the autoscan, the identifications and every stream stand, a button the user
 * pressed still goes first. A PID is a single `01 xx` back to back with the helper's gap at
 * [PARAM_GAP_MS]; a non-answer or a busy gateway doubles the gap, up to [STREAM_GAP_MS], and it
 * stays there for the page; leaving the page gives the stream its gap back. A car signal is read by
 * the [CarFeed] - a GET of its own about ten times a second, or the hub's sweeps for a hot signal -
 * and never touches the bus. The page's halts are the stream's: the same pause on «not parked», the
 * same stop on a busy or lost channel.
 *
 * **Nothing loops on a failure.** The bus already opens a lost helper once more (3.5); every
 * [DiagFailure] that still reaches here halts all work until something changes:
 * - [DiagFailure.NotParked] pauses with «Только на стоянке: P и 0 км/ч». The work resumes by itself
 *   when the shared hub - not the diagnostic channel - sees P at 0 km/h again, or on [rescan]. When
 *   the hub already said P and 0 km/h while the helper refused, the hub has to see the car leave
 *   and come back first, so a disagreement between the two costs one refused exchange, not a loop.
 * - [DiagFailure.ChannelBusy], [DiagFailure.ChannelLost], [DiagFailure.NoAccess],
 *   [DiagFailure.Refused] stop until [rescan]. A busy or lost channel is also tried once more by
 *   the next thing the user asks by hand - a module's read, its screen, its live data: one attempt
 *   a request, never on its own.
 * Every `END err` closes the helper's stream and the next exchange starts a new helper (about
 * three seconds), so the session asks nothing it can see would be refused: before each exchange it
 * looks at the hub, and a car the hub sees out of P or moving pauses the work without a request.
 *
 * **The pack's cells** (6.3, [setCellsVisible]): while «Ячейки» shows, the bus's [DiagnosticBus.batteryPages]
 * is collected beside the work - listening only, so the autoscan, the button and the streams go on -
 * and [PackCells] assembles the pages a sweep at a time. One subscription a showing: a refused or
 * lost one ends with «поток BMS прерван» on the age line until the tab is shown again or
 * «Продолжить». [setCellsPaused] leaves the subscription and keeps the drawn sweep; resuming
 * subscribes again; leaving the tab ends the pause.
 *
 * **The code history** (5.5): every good software code a read brings - the autoscan's F194 gate, an
 * opened block's F194 and F195, the modules behind it - goes through [SoftwareHistory], loaded from
 * [software] when the work starts and written back by the worker whenever it learned something.
 *
 * Only [ScanStore], [SoftwareStore] and [StreamSinks] touch files and only [CarFeed] touches the hub;
 * the rest is plain Kotlin on a [DiagnosticBus], tested on the scripted fake.
 */
internal class DiagnosticsSession(
    private val buses: () -> DiagnosticBus,
    private val store: ScanStore,
    private val car: CarFeed,
    private val sinks: StreamSinks,
    private val wallClock: () -> Long = System::currentTimeMillis,
    zone: ZoneId = ZoneId.systemDefault(),
    private val context: CoroutineContext = Dispatchers.IO,
    private val tickMs: Long = TICK_MS,
    private val log: (String) -> Unit = {},
    /**
     * The public DTC catalog's candidate headings (architecture, 4); loaded on first use, which the
     * work loop makes first so a stored scan's records do not parse it on the caller's thread.
     */
    private val catalog: () -> OemDtcIndex = { OemDtcIndex.EMPTY },
    /** The car's `elapsedRealtimeNanos`, the clock the helper stamps the BMS pages with. */
    private val carNanos: () -> Long = System::nanoTime,
    /** The code history's file (5.5); read and written on the worker, never on the caller's thread. */
    private val software: SoftwareStore = MemorySoftwareStore(),
) : DiagnosticsActions {
    private val clock = ScanClock(zone)
    private val builder = ModelBuilder(clock, catalog)
    private val lock = Any()
    private val saving = Any()

    private var scope: CoroutineScope? = null
    private var bus: DiagnosticBus? = null
    private var wake = Channel<Unit>(Channel.CONFLATED)

    // Everything below is guarded by [lock].
    private var link = LinkState.IDLE
    private var phase = ScanPhase.NEVER
    private var scanIndex = 0
    private val results = HashMap<Int, ModuleResult>()
    private var finishedAt: Long? = null
    private var notice: String? = null
    private var polling: Int? = null
    private var halt: Halt? = null
    private val userReads = LinkedHashSet<Int>()
    private val identPending = LinkedHashSet<Int>()
    private val idents = HashMap<Int, IdentResult>()
    private val open = HashSet<Int>()
    private val streams = HashMap<Int, LiveStream>()
    private val focus = HashMap<Int, List<Int>>()
    private var live: Int? = null
    private var recording: Pair<Int, StreamSink>? = null
    private var readings = CarReadings()
    private var param: ParamState? = null
    /** «Ячейки»: the assembled pages while the tab shows, its subscription (none while paused), and whether that ended. */
    private var cells: PackCells? = null
    private var cellsJob: Job? = null
    private var cellsStopped = false
    /** The gap last asked of the bus; a new bus starts at its launch parameter. */
    private var gapAsked = STREAM_GAP_MS
    /** What the scanner remembers of the software codes (5.5): read from [software] once, then kept here. */
    private var codes = SoftwareHistory()
    private var codesLoaded = false

    /** The parameter page: what it reads, its trace, and its own gap. */
    private class ParamState(val spec: ParamSpec) {
        val trace = ParamTrace(spec)
        var paused = false
        var gap = PARAM_GAP_MS
    }

    private val mutable = MutableStateFlow(builder.build(snapshot()))
    val model: StateFlow<DiagnosticsModel> = mutable.asStateFlow()

    /** Why the work stands. */
    private sealed interface Halt {
        /** Not parked: resumes on its own; [awaitingLeave] while the hub must first see the car leave P. */
        data class Paused(val awaitingLeave: Boolean) : Halt
        /** Busy, lost, no access: until [rescan]; busy and lost also until a request by hand. */
        data class Stopped(val link: LinkState) : Halt
    }

    private sealed interface Work {
        val request: Int
        data class Read(override val request: Int) : Work
        data class Ident(override val request: Int) : Work
        data class Scan(override val request: Int) : Work
        data class Stream(override val request: Int, val ask: DiagRequest) : Work
        data class Param(override val request: Int, val key: ParamKey.Pid, val ask: DiagRequest) : Work
    }

    /** A unit of work ended early because the channel halted; nothing of it is kept. */
    private class Aborted : Exception()

    /** The section opened: the hub claim, the last scan, and an autoscan when that one is old or missing. */
    fun start() {
        synchronized(lock) {
            if (scope != null) return
            reset()
            car.open()
            readings = runCatching { car.read() }.getOrDefault(CarReadings())
            bus = buses()
            gapAsked = STREAM_GAP_MS
            wake = Channel(Channel.CONFLATED)
            val s = CoroutineScope(context + SupervisorJob(context[Job]))
            scope = s
            s.launch {
                catalog()
                work()
            }
            s.launch { tick() }
            publish()
        }
    }

    /** The section closed: the helper ends with its stream, a recording is closed, the hub claim released. */
    fun stop() {
        val (s, b) = synchronized(lock) {
            val pair = scope to bus
            if (pair.first == null) return
            scope = null
            bus = null
            recording?.second?.close()
            recording = null
            live = null
            polling = null
            cells = null
            cellsJob = null
            if (param?.spec?.get != null) car.focus(null)
            param = null
            if (phase == ScanPhase.RUNNING || phase == ScanPhase.PAUSED) phase = ScanPhase.STOPPED
            publish()
            pair
        }
        s?.cancel()
        b?.close()
        car.close()
    }

    // ------------------------------------------------------------------ actions

    override fun rescan() = act {
        halt = null
        notice = null
        link = LinkState.IDLE
        beginScan()
    }

    override fun readModule(request: Int) = act {
        if (EcuCatalog.byRequest(request) == null) return@act
        userReads += request
        retryStopped()
    }

    override fun setModuleOpen(request: Int, open: Boolean) = act {
        if (EcuCatalog.byRequest(request) == null) return@act
        if (open) {
            this.open += request
            if (request !in idents) identPending += request
            retryStopped()
        } else {
            this.open -= request
            identPending -= request
        }
    }

    override fun setLiveVisible(request: Int, visible: Boolean) = act {
        if (EcuCatalog.byRequest(request)?.obd != true) return@act
        if (visible) {
            live = request
            streams[request]?.resetMinMax()
            if (request !in idents) identPending += request
            retryStopped()
        } else if (live == request) {
            live = null
        }
    }

    override fun setFocus(request: Int, pids: List<Int>) = act {
        focus[request] = pids
        streams[request]?.setFocus(pids)
    }

    override fun resetMinMax(request: Int) = act { streams[request]?.resetMinMax() }

    override fun setRecording(request: Int, on: Boolean) = act {
        if (on) {
            if (recording?.first == request) return@act
            recording?.second?.close()
            recording = sinks.open(wallClock())?.let { request to it }
        } else if (recording?.first == request) {
            recording?.second?.close()
            recording = null
        }
    }

    override fun setParam(key: ParamKey?) = act {
        if (key == param?.spec?.key) return@act
        val spec = key?.let(ParamSpec::of)
        if (param?.spec?.get != null) car.focus(null)
        param = spec?.let(::ParamState)
        when {
            spec == null -> gap(STREAM_GAP_MS)
            spec.key is ParamKey.Pid -> {
                gap(PARAM_GAP_MS)
                retryStopped()
            }
            else -> {
                gap(STREAM_GAP_MS)
                spec.get?.let(car::focus)
            }
        }
    }

    override fun setParamPaused(paused: Boolean) = act {
        val p = param ?: return@act
        if (p.paused == paused) return@act
        p.paused = paused
        p.spec.get?.let { car.focus(if (paused) null else it) }
        if (!paused && p.spec.key is ParamKey.Pid) retryStopped()
    }

    override fun resetParam() = act { param?.trace?.reset() }

    override fun setCellsVisible(visible: Boolean) = act {
        if (visible) {
            if (cells != null) return@act
            val s = scope ?: return@act
            cells = PackCells(clock)
            cellsStopped = false
            cellsJob = s.launch { listenCells() }
        } else {
            cellsJob?.cancel()
            cellsJob = null
            cells = null
        }
    }

    override fun setCellsPaused(paused: Boolean) = act {
        val c = cells ?: return@act
        if (c.paused == paused) return@act
        if (paused) {
            c.pause(carNanos(), wallClock())
            cellsJob?.cancel()
            cellsJob = null
        } else {
            val s = scope ?: return@act
            c.resume()
            cellsStopped = false
            cellsJob = s.launch { listenCells() }
        }
    }

    /** A page's own GETs ride the car feed's batch; nothing on the bus changes. */
    override fun setExtrasVisible(page: ExtraPage, visible: Boolean) = car.page(page, visible)

    /** One subscription to the BMS pages for this showing of «Ячейки»; its end is said, not retried. */
    private suspend fun listenCells() {
        val b = synchronized(lock) { bus } ?: return
        val me = currentCoroutineContext()[Job]
        try {
            b.batteryPages().collect { page ->
                synchronized(lock) {
                    val c = cells?.takeIf { cellsJob === me } ?: return@synchronized
                    val first = c.cells.newestNanos == null && c.probes.newestNanos == null
                    // The tick republishes four times a second; the first page and a new sweep need not wait for it.
                    if (c.accept(page) || first) publish()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            log("diagnostics: the BMS pages ended: $error")
        }
        synchronized(lock) {
            if (cells != null && cellsJob === me) {
                cellsStopped = true
                publish()
            }
        }
    }

    /** The helper's gap from the next exchange on; asked only when it changes. Under [lock]. */
    private fun gap(ms: Int) {
        if (ms == gapAsked) return
        gapAsked = ms
        bus?.setGap(ms)
    }

    private inline fun act(block: () -> Unit) {
        synchronized(lock) {
            block()
            publish()
        }
        wake.trySend(Unit)
    }

    // ------------------------------------------------------------------ the worker

    private suspend fun work() {
        val stored = runCatching { store.load() }.onFailure { log("diagnostics: last scan unreadable: $it") }.getOrNull()
        // Once a process: after that the memory is ahead of the file, which a closing worker may still be writing.
        val history = if (synchronized(lock) { codesLoaded }) null
            else runCatching { software.load() }.onFailure { log("diagnostics: code history unreadable: $it") }.getOrDefault(SoftwareHistory())
        synchronized(lock) {
            if (history != null && !codesLoaded) {
                codes = history
                codesLoaded = true
            }
            val now = wallClock()
            val age = stored?.let { now - it.finishedAtMillis }
            if (stored != null) {
                // Not fed to the code history: its codes were learned when it was live, and read again
                // they could only undo a change seen since.
                stored.reads.forEach { r -> EcuCatalog.byRequest(r.request)?.let { results[r.request] = ModuleResult(it, r) } }
                finishedAt = stored.finishedAtMillis
                phase = ScanPhase.DONE
                scanIndex = EcuCatalog.routed.size
            }
            if (age == null || age !in 0..RESCAN_AFTER_MS) {
                beginScan()
            } else {
                notice = "Показан скан ${clock.minutes(stored.finishedAtMillis, now)}, ${clock.age(stored.finishedAtMillis, now)} назад"
            }
            publish()
        }
        while (true) {
            val job = synchronized(lock) { pick() }
            if (job == null) {
                wake.receive()
                continue
            }
            try {
                run(job)
            } catch (_: Aborted) {
                // The halt is already on the screen; the job waits where it was.
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log("diagnostics: ${job::class.simpleName} ${Figures.address(job.request)} failed: $error")
                synchronized(lock) {
                    stopWith(LinkState.LOST, "Сбой чтения ${Figures.address(job.request)}: ${error.javaClass.simpleName}")
                    publish()
                }
            }
        }
    }

    /** The next unit of work by priority, or null to wait. Under [lock]. */
    private fun pick(): Work? {
        if (halt != null) return null
        val shown = live
        val job: Work = userReads.firstOrNull()?.let { Work.Read(it) }
            // The page alone: nothing else, and nothing while paused or for a car signal.
            ?: param?.let { page -> paramWork(page) ?: return null }
            ?: identPending.firstOrNull()?.let { Work.Ident(it) }
            ?: when {
                shown != null -> stream(shown)?.next()?.let { Work.Stream(shown, it) } ?: return null
                phase == ScanPhase.RUNNING && scanIndex < EcuCatalog.routed.size -> Work.Scan(EcuCatalog.routed[scanIndex].request!!)
                else -> return null
            }
        if (readings.parkedStill == false) {
            pause(awaitingLeave = false)
            publish()
            return null
        }
        return job
    }

    private fun paramWork(page: ParamState): Work? {
        val key = page.spec.key as? ParamKey.Pid ?: return null
        if (page.paused) return null
        return Work.Param(key.request, key, DiagRequest(key.request, byteArrayOf(0x01, key.pid.toByte())))
    }

    private fun stream(request: Int): LiveStream? = streams[request] ?: idents[request]?.let { id ->
        if (id.offered.none(IdentResult::streamable)) return null
        LiveStream(request, id.offered, carRows = request == ECM).also { s ->
            focus[request]?.let(s::setFocus)
            streams[request] = s
        }
    }

    private suspend fun run(job: Work) {
        val ecu = EcuCatalog.byRequest(job.request)!!
        when (job) {
            is Work.Read, is Work.Scan -> {
                synchronized(lock) {
                    polling = job.request
                    publish()
                }
                val read = try {
                    ModuleProcedure.read(ecu, wallClock, ::ask)
                } finally {
                    synchronized(lock) {
                        polling = null
                        publish()
                    }
                }
                val (save, learned) = synchronized(lock) {
                    val result = ModuleResult(ecu, read)
                    results[job.request] = result
                    val fresh = learn(SoftwareReads.of(result), read.atMillis)
                    var persist = false
                    if (job is Work.Scan) {
                        scanIndex++
                        if (scanIndex >= EcuCatalog.routed.size) {
                            phase = ScanPhase.DONE
                            finishedAt = wallClock()
                            persist = true
                        }
                    } else {
                        userReads -= job.request
                        persist = phase == ScanPhase.DONE && finishedAt != null
                    }
                    publish()
                    (if (persist) stored() else null) to fresh
                }
                save?.let { s -> runCatching { store.save(s) }.onFailure { log("diagnostics: last scan not saved: $it") } }
                if (learned) keep()
            }
            is Work.Ident -> {
                val read = IdentProcedure.read(ecu, wallClock, ::ask)
                val learned = synchronized(lock) {
                    val ident = IdentResult(ecu, read)
                    idents[job.request] = ident
                    identPending -= job.request
                    learn(SoftwareReads.of(ident), read.atMillis).also { publish() }
                }
                if (learned) keep()
            }
            is Work.Stream -> {
                val reply = ask(job.ask)
                synchronized(lock) {
                    val lines = streams[job.request]?.accept(reply).orEmpty()
                    recording?.takeIf { it.first == job.request }?.second?.write(lines)
                    publish()
                }
            }
            is Work.Param -> {
                val reply = ask(job.ask)
                synchronized(lock) {
                    val page = param?.takeIf { it.spec.key == job.key } ?: return
                    val lines = page.trace.accept(reply)
                    // The table's row stays fresh for the way back; its own CSV lines would double these.
                    streams[job.request]?.accept(reply)
                    recording?.takeIf { it.first == job.request }?.second?.write(lines)
                    if (reply is DiagReply.NoReply || reply is DiagReply.GatewayBusy) {
                        page.gap = (page.gap * 2).coerceAtMost(STREAM_GAP_MS)
                        gap(page.gap)
                    }
                    publishParam()
                }
            }
        }
    }

    /** [reads], made at [atMillis], into the history; whether it learned anything. Under [lock]. */
    private fun learn(reads: List<SoftwareRead>, atMillis: Long): Boolean {
        val next = codes.seen(reads, clock.day(atMillis))
        if (next === codes) return false
        next.records.forEach { (key, r) ->
            val change = r.lastChange
            if (change != null && change !== codes.records[key]?.lastChange) {
                log("diagnostics: ${key.id} software ${change.oldCode} → ${change.newCode}")
            }
        }
        codes = next
        return true
    }

    /**
     * Writes the history down, on the worker: always the latest, one write at a time, so a worker that
     * is closing cannot put back an older one after the next section's.
     */
    private fun keep() = synchronized(saving) {
        val latest = synchronized(lock) { codes }
        runCatching { software.save(latest) }.onFailure { log("diagnostics: code history not saved: $it") }
    }

    /** One exchange; a halt of any kind ends the unit of work with [Aborted]. */
    private suspend fun ask(request: DiagRequest): DiagReply {
        val b = synchronized(lock) {
            if (halt != null) throw Aborted()
            // The hub sees the car leave P before the helper would refuse: no refused request, no new helper.
            if (readings.parkedStill == false) {
                pause(awaitingLeave = false)
                publish()
                throw Aborted()
            }
            if (link != LinkState.OK && link != LinkState.CONNECTING) {
                link = LinkState.CONNECTING
                publish()
            }
            bus ?: throw Aborted()
        }
        val reply = try {
            b.exchange(request)
        } catch (failure: DiagFailure) {
            synchronized(lock) {
                fail(failure)
                publish()
            }
            throw Aborted()
        }
        synchronized(lock) {
            if (link != LinkState.OK) {
                link = LinkState.OK
                publish()
            }
        }
        return reply
    }

    private fun fail(failure: DiagFailure) {
        log("diagnostics: ${failure.javaClass.simpleName}: ${failure.message}")
        when (failure) {
            is DiagFailure.NotParked -> pause(awaitingLeave = readings.parkedStill == true)
            is DiagFailure.ChannelBusy -> stopWith(LinkState.BUSY, BUSY_NOTICE)
            is DiagFailure.ChannelLost -> stopWith(LinkState.LOST, LOST_NOTICE)
            is DiagFailure.NoAccess -> stopWith(LinkState.NO_ACCESS, NO_ACCESS_NOTICE)
            is DiagFailure.Refused -> stopWith(LinkState.LOST, "Помощник отклонил запрос: ${failure.why}")
        }
    }

    private fun pause(awaitingLeave: Boolean) {
        halt = Halt.Paused(awaitingLeave)
        link = LinkState.NOT_PARKED
        notice = PARK_NOTICE
        if (phase == ScanPhase.RUNNING) phase = ScanPhase.PAUSED
    }

    private fun stopWith(state: LinkState, text: String) {
        halt = Halt.Stopped(state)
        link = state
        notice = text
        if (phase == ScanPhase.RUNNING || phase == ScanPhase.PAUSED) phase = ScanPhase.STOPPED
    }

    /**
     * A request by hand on a channel that stopped busy or lost: the halt is lifted for one more
     * attempt, and an autoscan that stopped with it goes on. A failure halts it again.
     */
    private fun retryStopped() {
        val stopped = halt as? Halt.Stopped ?: return
        if (stopped.link != LinkState.BUSY && stopped.link != LinkState.LOST) return
        halt = null
        notice = null
        link = LinkState.IDLE
        if (phase == ScanPhase.STOPPED) phase = ScanPhase.RUNNING
    }

    private fun resume() {
        halt = null
        notice = null
        link = LinkState.IDLE
        if (phase == ScanPhase.PAUSED) phase = ScanPhase.RUNNING
    }

    private fun beginScan() {
        results.clear()
        scanIndex = 0
        finishedAt = null
        phase = ScanPhase.RUNNING
    }

    private fun stored() = StoredScan(
        finishedAt ?: wallClock(),
        EcuCatalog.routed.mapNotNull { e -> results[e.request]?.read },
    )

    // ------------------------------------------------------------------ the car, four times a second

    private suspend fun tick() {
        var sinceFull = 0L
        while (true) {
            val r = runCatching { car.read() }.getOrNull()
            val focused = runCatching { car.focused() }.getOrDefault(emptyList())
            var resumed = false
            val interval = synchronized(lock) {
                paramTick(r, focused)
                if (r != null) {
                    readings = r
                    live?.let { request ->
                        val lines = streams[request]?.carTick(r).orEmpty()
                        recording?.takeIf { it.first == request }?.second?.write(lines)
                    }
                    val h = halt
                    if (h is Halt.Paused) {
                        val still = r.parkedStill
                        if (h.awaitingLeave) {
                            if (still == false) halt = Halt.Paused(awaitingLeave = false)
                        } else if (still == true) {
                            resume()
                            resumed = true
                        }
                    }
                }
                // A car signal's page samples faster than the rest of the model needs rebuilding.
                val fast = param?.let { it.spec.key is ParamKey.Signal && !it.paused } == true
                if (!fast || resumed || sinceFull >= tickMs) {
                    publish()
                    sinceFull = 0
                } else {
                    publishParam()
                }
                if (fast) PARAM_TICK_MS.coerceAtMost(tickMs) else tickMs
            }
            if (resumed) wake.trySend(Unit)
            delay(interval)
            sinceFull += interval
        }
    }

    /** A car signal's page takes the hub's new sweep or the focused GET's readings. Under [lock]. */
    private fun paramTick(r: CarReadings?, focused: List<SignalReading>) {
        val page = param?.takeIf { it.spec.key is ParamKey.Signal && !it.paused } ?: return
        val hub = page.spec.hub
        if (hub != null) {
            if (r == null || r.sweep == 0L || r.sweep == page.trace.lastSweep) return
            page.trace.lastSweep = r.sweep
            page.trace.put(r.atNanos, r.hub[hub])
        } else {
            focused.forEach { page.trace.put(it.atNanos, it.value) }
        }
    }

    // ------------------------------------------------------------------ the model

    private fun snapshot() = SessionSnapshot(
        link = link,
        phase = phase,
        done = scanIndex.coerceAtMost(EcuCatalog.routed.size),
        finishedAtMillis = finishedAt,
        notice = notice,
        polling = polling,
        results = HashMap(results),
        idents = HashMap(idents),
        open = HashSet(open),
        streams = HashMap(streams),
        live = live,
        streaming = halt == null,
        recording = recording?.first,
        car = readings,
        nowMillis = wallClock(),
        param = paramView(),
        packCells = cells?.view(carNanos(), cellsStopped),
        software = codes,
    )

    private fun publish() {
        mutable.value = builder.build(snapshot())
    }

    /** Only the page changed: the rest of the model stands as it was built. */
    private fun publishParam() {
        mutable.value = mutable.value.copy(param = paramView())
    }

    private fun paramView(): ParamView? {
        val page = param ?: return null
        val key = page.spec.key
        val polling = !page.paused && (key !is ParamKey.Pid || (halt == null && scope != null))
        return page.trace.view(
            running = polling,
            recording = key is ParamKey.Pid && recording?.first == key.request,
            car = readings,
        )
    }

    private fun reset() {
        link = LinkState.IDLE
        phase = ScanPhase.NEVER
        scanIndex = 0
        results.clear()
        finishedAt = null
        notice = null
        polling = null
        halt = null
        userReads.clear()
        identPending.clear()
        idents.clear()
        open.clear()
        streams.clear()
        focus.clear()
        live = null
        recording = null
        param = null
        cells = null
        cellsJob = null
        cellsStopped = false
    }

    companion object {
        /** An older last scan is scanned again on opening (architecture, 6). */
        const val RESCAN_AFTER_MS = 10 * 60_000L
        /** How often the hub is looked at: P and speed for the pause, the fast signals, the clock. */
        const val TICK_MS = 250L
        /** How often a car signal's page is sampled: the hub's sweeps and the focused GET (6.2). */
        const val PARAM_TICK_MS = 100L
        /** The helper's gap for the stream and the autoscan: its launch parameter (3.3). */
        const val STREAM_GAP_MS = 100
        /** The parameter page's gap for a PID, before any back-off (6.2). */
        const val PARAM_GAP_MS = 20

        const val PARK_NOTICE = "Только на стоянке: P и 0 км/ч"
        const val BUSY_NOTICE = "Канал занят штатной диагностикой или другим читателем"
        const val LOST_NOTICE = "Связь с блоками потеряна"
        const val NO_ACCESS_NOTICE = "Нет доверенного доступа ADB"

        private val ECM: Int = EcuCatalog.all.first { it.abbreviation == "ECM" }.request!!
    }
}
