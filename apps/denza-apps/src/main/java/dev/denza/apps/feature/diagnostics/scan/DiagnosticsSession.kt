package dev.denza.apps.feature.diagnostics.scan

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
 * Only [ScanStore] and [StreamSinks] touch files and only [CarFeed] touches the hub; the rest is
 * plain Kotlin on a [DiagnosticBus], tested on the scripted fake.
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
) : DiagnosticsActions {
    private val clock = ScanClock(zone)
    private val builder = ModelBuilder(clock, catalog)
    private val lock = Any()

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
        synchronized(lock) {
            val now = wallClock()
            val age = stored?.let { now - it.finishedAtMillis }
            if (stored != null) {
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
                val save = synchronized(lock) {
                    results[job.request] = ModuleResult(ecu, read)
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
                    if (persist) stored() else null
                }
                save?.let { s -> runCatching { store.save(s) }.onFailure { log("diagnostics: last scan not saved: $it") } }
            }
            is Work.Ident -> {
                val read = IdentProcedure.read(ecu, wallClock, ::ask)
                synchronized(lock) {
                    idents[job.request] = IdentResult(ecu, read)
                    identPending -= job.request
                    publish()
                }
            }
            is Work.Stream -> {
                val reply = ask(job.ask)
                synchronized(lock) {
                    val lines = streams[job.request]?.accept(reply).orEmpty()
                    recording?.takeIf { it.first == job.request }?.second?.write(lines)
                    publish()
                }
            }
        }
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
        while (true) {
            val r = runCatching { car.read() }.getOrNull()
            var resumed = false
            synchronized(lock) {
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
                publish()
            }
            if (resumed) wake.trySend(Unit)
            delay(tickMs)
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
    )

    private fun publish() {
        mutable.value = builder.build(snapshot())
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
    }

    companion object {
        /** An older last scan is scanned again on opening (architecture, 6). */
        const val RESCAN_AFTER_MS = 10 * 60_000L
        /** How often the hub is looked at: P and speed for the pause, the fast signals, the clock. */
        const val TICK_MS = 250L

        const val PARK_NOTICE = "Только на стоянке: P и 0 км/ч"
        const val BUSY_NOTICE = "Канал занят штатной диагностикой или другим читателем"
        const val LOST_NOTICE = "Связь с блоками потеряна"
        const val NO_ACCESS_NOTICE = "Нет доверенного доступа ADB"

        private val ECM: Int = EcuCatalog.all.first { it.abbreviation == "ECM" }.request!!
    }
}
