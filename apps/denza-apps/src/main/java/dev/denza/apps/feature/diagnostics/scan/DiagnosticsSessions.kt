package dev.denza.apps.feature.diagnostics.scan

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.feature.diagnostics.DiagnosticExtraReader
import dev.denza.apps.feature.diagnostics.DiagnosticExtras
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The section's session of the main process, as `DenzaVehicleSignals` is the signal hub's. */
internal fun DiagnosticsSession.Companion.of(context: Context): DiagnosticsSession = DiagnosticsSessions.of(context)

/**
 * Main-process composition root of the diagnostic section: the resident bus, the shared hub under
 * [VehicleWatcher.DIAGNOSTICS], the `autoservice` GET batch every five seconds, and the files under
 * `filesDir/diagnostics/`. Secondary processes may not create car transports.
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
        return DiagnosticsSession(
            buses = { ResidentDiagnosticBus.create(app) },
            store = FileScanStore(dir),
            car = HubCarFeed(VehicleSession.hub(app), ExtrasPoller({ DenzaLocalAdb.client(app).openPersistentShell() }, log)),
            sinks = CsvStreamSinks(dir, zone),
            zone = zone,
            log = log,
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
        )
    }

    override fun close() {
        extras.stop()
        hub.setActive(VehicleWatcher.DIAGNOSTICS, false)
    }
}

/**
 * The `autoservice` GET batch of the system screens, as the retired window read it: one persistent
 * shell, profile 170 checked once per shell, [DiagnosticExtraReader.command] every five seconds,
 * values good for [DiagnosticExtras.STALE_MS]. A failed read closes the shell; the next round opens
 * a new one five seconds later, never sooner.
 */
internal class ExtrasPoller(
    private val open: () -> LocalAdbClient.PersistentShellSession,
    private val log: (String) -> Unit,
) {
    @Volatile var latest = DiagnosticExtras()
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var shell: LocalAdbClient.PersistentShellSession? = null

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
    }

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            try {
                val s = shell ?: open().also { opened ->
                    val profile = opened.shell("getprop persist.sys.AutoType").trim()
                    if (profile != "170") {
                        opened.close()
                        throw IllegalStateException("profile $profile, readings are qualified for 170")
                    }
                    shell = opened
                }
                val output = s.shell(DiagnosticExtraReader.command)
                latest = DiagnosticExtraReader.decode(output, System.currentTimeMillis(), SystemClock.elapsedRealtime())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                log("diagnostic readings: ${error.javaClass.simpleName} ${error.message ?: ""}")
                shell?.close()
                shell = null
            }
            delay(PERIOD_MS)
        }
    }

    companion object {
        const val PERIOD_MS = 5_000L
    }
}
