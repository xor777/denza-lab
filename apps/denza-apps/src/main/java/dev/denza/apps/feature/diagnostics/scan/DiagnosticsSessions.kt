package dev.denza.apps.feature.diagnostics.scan

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.feature.diagnostics.DiagnosticExtraReader
import dev.denza.apps.feature.diagnostics.DiagnosticExtras
import dev.denza.apps.feature.diagnostics.ExtraPage
import dev.denza.apps.feature.diagnostics.bus.ResidentDiagnosticBus
import dev.denza.apps.feature.vehicle.VehicleSession
import dev.denza.apps.feature.vehicle.VehicleTelemetryHub
import dev.denza.apps.feature.vehicle.VehicleWatcher
import dev.denza.disharebridge.LocalAdbClient
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The section's session of the main process, as `DenzaVehicleSignals` is the signal hub's. */
internal fun DiagnosticsSession.Companion.of(context: Context): DiagnosticsSession = DiagnosticsSessions.of(context)

/**
 * Main-process composition root of the diagnostic section: the resident bus, the shared hub under
 * [VehicleWatcher.DIAGNOSTICS], the `autoservice` GET batch every five seconds, and the files under
 * `filesDir/diagnostics/`: the last scan, the code history (`software-codes.json`), the recordings. Secondary processes may not create car transports.
 */
internal object DiagnosticsSessions {
    @Volatile private var instance: DiagnosticsSession? = null

    fun of(context: Context): DiagnosticsSession {
        val app = context.applicationContext
        check(Application.getProcessName() == app.packageName) {
            "the diagnostic session is available only in the Denza Apps main process"
        }
        instance?.let { return it }
        return synchronized(this) { instance ?: create(app).also { instance = it } }
    }

    private fun create(app: Context): DiagnosticsSession {
        val dir = File(app.filesDir, DIRECTORY)
        val zone = ZoneId.systemDefault()
        val log: (String) -> Unit = { Log.i(TAG, it) }
        val catalog by lazy { OemDtcAssets.load(app.assets::open, log) }
        return DiagnosticsSession(
            buses = { ResidentDiagnosticBus.create(app) },
            store = FileScanStore(dir),
            car = HubCarFeed(VehicleSession.hub(app), ExtrasPoller({ AdbExtrasShell(DenzaLocalAdb.client(app).openPersistentShell()) }, log)),
            sinks = CsvStreamSinks(dir, zone),
            zone = zone,
            log = log,
            catalog = { catalog },
            carNanos = SystemClock::elapsedRealtimeNanos,
            software = FileSoftwareStore(dir),
        )
    }

    const val DIRECTORY = "diagnostics"
    private const val TAG = "DenzaDiagnostics"
}

/** [CarFeed] over the shared hub and the GET batch; the hub's claim is held while the section is open. */
internal class HubCarFeed(private val hub: VehicleTelemetryHub, private val extras: ExtrasPoller) : CarFeed {
    private val held = HeldTelemetry()

    override fun open() {
        hub.setActive(VehicleWatcher.DIAGNOSTICS, true)
        extras.start()
    }

    override fun read(): CarReadings = synchronized(this) {
        val now = SystemClock.elapsedRealtime()
        val values = held.read(hub.snapshot, now)
        val batch = extras.latest
        CarReadings(
            hub = values,
            sweep = held.sweep,
            atNanos = SystemClock.elapsedRealtimeNanos(),
            extras = batch.values,
            extrasFresh = batch.values.isNotEmpty() && batch.fresh(now).isNotEmpty(),
            extrasAtNanos = batch.atElapsed * 1_000_000L,
            extrasTookMs = batch.tookMs,
        )
    }

    override fun close() {
        extras.stop()
        hub.setActive(VehicleWatcher.DIAGNOSTICS, false)
    }

    override fun focus(get: SignalGet?) = extras.focus(get)

    override fun focused(): List<SignalReading> = extras.drain()

    override fun page(page: ExtraPage, shown: Boolean) = extras.show(page, shown)
}

/** The one persistent shell of [ExtrasPoller]. */
internal interface ExtrasShell : AutoCloseable {
    fun shell(command: String): String
    override fun close()
}

/** [ExtrasShell] over the app's own local ADB. */
internal class AdbExtrasShell(private val session: LocalAdbClient.PersistentShellSession) : ExtrasShell {
    override fun shell(command: String): String = session.shell(command)
    override fun close() = session.close()
}

/**
 * The `autoservice` GET batch of the system screens, as the retired window read it: one persistent
 * shell, profile 170 checked once per shell, [DiagnosticExtraReader.command] every five seconds,
 * values good for [DiagnosticExtras.STALE_MS]. A failed read closes the shell; the next round opens
 * a new one five seconds later, never sooner.
 *
 * **Focused** ([focus], architecture 6.2): while a parameter page shows a GET of its own, only that
 * GET is sent, one every [FOCUS_PERIOD_MS] from the start of one to the start of the next (back to
 * back when the shell is slower), each reading timed on the head unit's clock and kept for
 * [drain]; the batch stands, its last values ageing as they would. A failed read is a gap and the
 * same five seconds' wait: nothing loops on a failure. A new focus, or none, goes at once.
 *
 * **A page's own GETs** ([show]): while a page with GETs of its own ([ExtraPage]) shows, the batch
 * is [DiagnosticExtraReader.command] of the pages shown, the next one at once; what is shown outlives
 * [stop], as the page that set it does.
 *
 * **A page's round** ([ExtraPage.periodMs]): while a page that asks for a quicker round shows - the
 * pack's «Потоковые данные» (architecture 6.3) - a batch goes every that many ms from the start of
 * one to the start of the next, back to back when the batch is slower; without one the wait is the
 * five seconds after a batch's answer it always was. Every batch says how long it took
 * ([DiagnosticExtras.tookMs]). A failure still waits five seconds, whatever is shown.
 */
internal class ExtrasPoller(
    private val open: () -> ExtrasShell,
    private val log: (String) -> Unit,
    context: CoroutineContext = Dispatchers.IO,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val nanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    @Volatile var latest = DiagnosticExtras()
        private set

    private val scope = CoroutineScope(context + SupervisorJob(context[Job]))
    private var job: Job? = null
    @Volatile private var shell: ExtrasShell? = null
    @Volatile private var target: SignalGet? = null
    @Volatile private var pages: Set<ExtraPage> = emptySet()
    private val readings = ArrayDeque<SignalReading>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Synchronized fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    @Synchronized fun stop() {
        job?.cancel()
        job = null
        shell?.close()
        shell = null
        latest = DiagnosticExtras()
        target = null
        readings.clear()
    }

    /** Only [get] from the next round on; null brings the batch back. */
    fun focus(get: SignalGet?) {
        synchronized(this) {
            if (target === get) return
            target = get
            readings.clear()
        }
        wake.trySend(Unit)
    }

    /** [page]'s GETs in the batch or not; a change sends the next batch at once. */
    fun show(page: ExtraPage, shown: Boolean) {
        synchronized(this) {
            val next = if (shown) pages + page else pages - page
            if (next == pages) return
            pages = next
        }
        wake.trySend(Unit)
    }

    /** The focused readings since the last call, oldest first. */
    fun drain(): List<SignalReading> = synchronized(this) { readings.toList().also { readings.clear() } }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val get = target
            val began = elapsed()
            val ok = try {
                val s = shell ?: open().also { opened ->
                    val profile = opened.shell("getprop persist.sys.AutoType").trim()
                    if (profile != "170") {
                        opened.close()
                        throw IllegalStateException("profile $profile, readings are qualified for 170")
                    }
                    shell = opened
                }
                if (get == null) {
                    val output = s.shell(DiagnosticExtraReader.command(pages))
                    val at = elapsed()
                    latest = DiagnosticExtraReader.decode(output, wallClock(), at, at - began)
                } else {
                    val value = DiagnosticExtraReader.word(s.shell(get.command))?.let(get.decode)
                    keep(get, SignalReading(nanos(), value))
                }
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log("diagnostic readings: ${error.javaClass.simpleName} ${error.message ?: ""}")
                shell?.close()
                shell = null
                if (get != null) keep(get, SignalReading(nanos(), null))
                false
            }
            val period = when {
                !ok -> null
                get != null -> FOCUS_PERIOD_MS
                else -> pages.mapNotNull { it.periodMs }.minOrNull()
            }
            val wait = period?.let { (it - (elapsed() - began)).coerceAtLeast(0) } ?: PERIOD_MS
            withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    private fun keep(get: SignalGet, reading: SignalReading) = synchronized(this) {
        if (target !== get) return
        readings.addLast(reading)
        while (readings.size > MAX_KEPT) readings.removeFirst()
    }

    companion object {
        const val PERIOD_MS = 5_000L
        /** A parameter page's GET: about ten a second (architecture, 6.2). */
        const val FOCUS_PERIOD_MS = 100L
        /** Readings nobody drained: a minute's worth, then the oldest go. */
        private const val MAX_KEPT = 600
    }
}
