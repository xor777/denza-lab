package dev.denza.apps.feature.diagnostics

import android.content.Context
import android.os.SystemClock
import dev.denza.apps.adb.DenzaLocalAdb
import dev.denza.apps.platform.shell.ShellProxyJar
import dev.denza.apps.platform.shell.ShellProxyStager
import dev.denza.apps.platform.shell.shellQuote
import dev.denza.disharebridge.LocalAdbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class DiagnosticStatus(
    val running: Boolean = false,
    val message: String = "DTC ещё не прочитаны",
    val profile: String = "—",
    val indicators: DiagnosticIndicators = DiagnosticIndicators(),
    val dtc: DtcSnapshot? = null,
    val extras: DiagnosticExtras = DiagnosticExtras(),
    val lbms: DtcSnapshot? = null,
    val dataMessage: String = "Ожидаем показания",
)

/** A window owns one serialized worker. Stop closes its shell; restart waits for its exit. */
internal class VehicleDiagnostics private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(DiagnosticStatus())
    val state = mutable.asStateFlow()
    private val stager = ShellProxyStager(ShellProxyJar.VEHICLE_DTC, {
        app.assets.open(ShellProxyJar.VEHICLE_DTC.asset).use { it.readBytes() }
    })
    @Volatile private var active: LocalAdbClient.PersistentShellSession? = null
    private var job: Job? = null
    private var queue: DiagnosticReadQueue? = null
    private var generation = 0L

    @Synchronized fun start() {
        if (queue != null) return
        val previous = job
        val mine = ++generation
        val requests = DiagnosticReadQueue().also { it.refresh() }
        queue = requests
        job = scope.launch {
            previous?.join()
            var session: LocalAdbClient.PersistentShellSession? = null
            try {
                while (isActive) {
                    val ecu = requests.next()
                    try {
                        ensureActive()
                        val owned = session ?: DenzaLocalAdb.client(app).openPersistentShell().also { session = it; active = it }
                        if (ecu == null) {
                            val profile = owned.shell("getprop persist.sys.AutoType").trim()
                            if (profile != "170") throw DiagnosticFailure("Показания проверены для Z9GT · профиль 170")
                            val output = owned.shell(DiagnosticSignals.command + "; " + DiagnosticExtraReader.command)
                            ensureActive()
                            val at = System.currentTimeMillis()
                            val lamps = DiagnosticSignals.indicators(output, at)
                            val extras = DiagnosticExtraReader.decode(output, at, SystemClock.elapsedRealtime())
                            publish(mine) { it.copy(profile = profile, indicators = lamps, extras = extras,
                                dataMessage = "Дополнительно ${extras.values.size}/${DiagnosticExtra.entries.size} · каждые 5 с") }
                        } else {
                            publish(mine) { it.copy(running = true, message = "Читаем ECU ${ecu.address}…") }
                            fun preflight() = DiagnosticSignals.requireParked(
                                owned.shell("getprop persist.sys.AutoType"),
                                owned.shell("service call autoservice 5 i32 1011 i32 89129008"),
                                owned.shell("service call autoservice 7 i32 1013 i32 -1807745016"),
                            )
                            preflight()
                            ensureActive()
                            val entry = stager.stage(owned::shell)
                            preflight()
                            ensureActive()
                            val count = owned.shell(command(entry, "count", ecu), 22_000)
                            ensureActive()
                            DtcProtocol.payload(count, 1, ecu)
                            preflight()
                            ensureActive()
                            val listed = owned.shell(command(entry, "list", ecu), 22_000)
                            ensureActive()
                            val snapshot = DtcProtocol.decode(count, listed, System.currentTimeMillis(), ecu)
                            publish(mine) { it.copy(dtc = if (ecu == DtcEcu.BODY) snapshot else it.dtc,
                                lbms = if (ecu == DtcEcu.LBMS) snapshot else it.lbms,
                                message = "ECU ${ecu.address}: ${snapshot.records.size} DTC · снимок получен") }
                            requests.refresh()
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        ensureActive()
                        val message = when (error) {
                            is DiagnosticFailure -> error.message ?: "Ошибка диагностики"
                            is LocalAdbClient.AuthorizationRequiredException -> "Нет доверенного ADB-доступа"
                            else -> "Канал чтения недоступен · ${error.javaClass.simpleName}"
                        }
                        publish(mine) {
                            if (ecu != null) it.copy(message = "${ecu.address}: $message")
                            else it.copy(extras = DiagnosticExtras(), indicators = DiagnosticIndicators(), dataMessage = message)
                        }
                        session?.close()
                        if (active === session) active = null
                        session = null
                    } finally {
                        if (ecu != null) {
                            complete(mine, requests)
                        }
                    }
                }
            } finally {
                session?.close()
                if (active === session) active = null
            }
        }
    }

    @Synchronized private fun complete(mine: Long, requests: DiagnosticReadQueue) {
        requests.finished()
        publish(mine) { it.copy(running = false) }
    }
    @Synchronized private fun publish(mine: Long, change: (DiagnosticStatus) -> DiagnosticStatus) {
        if (mine == generation) mutable.update(change)
    }
    @Synchronized fun read(ecu: DtcEcu) {
        if (queue?.request(ecu) == true) mutable.update { it.copy(running = true, message = "В очереди: ECU ${ecu.address}") }
    }
    @Synchronized fun refresh() { queue?.refresh() }
    @Synchronized fun stop() {
        ++generation
        queue = null
        job?.cancel()
        active?.close()
        mutable.update { it.copy(running = false, profile = "—", extras = DiagnosticExtras(), indicators = DiagnosticIndicators(),
            dataMessage = "Ожидаем показания", message = if (it.running) "Чтение остановлено" else it.message) }
    }

    companion object {
        @Volatile private var instance: VehicleDiagnostics? = null
        @Synchronized fun of(context: Context): VehicleDiagnostics = instance ?: VehicleDiagnostics(context).also { instance = it }

        fun command(entry: String, mode: String, ecu: DtcEcu = DtcEcu.BODY): String {
            require(mode == "count" || mode == "list")
            return "CLASSPATH=${shellQuote(entry)} app_process /system/bin --nice-name=denza_vehicle_dtc " +
                "${VehicleDtcProxyMain::class.java.name} $mode ${ecu.argument}"
        }
    }
}
