package dev.denza.apps.feature.diagnostics.scan

import dev.denza.apps.feature.diagnostics.DiagnosticExtra
import dev.denza.apps.feature.diagnostics.DiagnosticExtraReader
import dev.denza.apps.feature.diagnostics.ExtraPage
import dev.denza.apps.feature.vehicle.AutoserviceShell
import dev.denza.apps.feature.vehicle.VehicleAccess
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry

/**
 * The car as the app already reads it, without the diagnostic channel: the shared
 * `VehicleTelemetryHub` sweep (P, speed, the engine, the hot signals) and the `autoservice` GET
 * batch of the system screens (architecture, 6). The session never asks the ECUs for these.
 */
internal data class CarReadings(
    /** Hub values, each held only while it is fresh by its own poll's horizon. */
    val hub: Map<VehicleSignal, Double> = emptyMap(),
    /** Bumped once per new hub sweep, so a chart takes one sample per sweep and not per look. */
    val sweep: Long = 0,
    /** When this sweep was seen, on the car's `elapsedRealtimeNanos`. */
    val atNanos: Long = 0,
    /** The last `autoservice` batch, whatever its age; [extrasFresh] says whether it still counts. */
    val extras: Map<DiagnosticExtra, Double> = emptyMap(),
    val extrasFresh: Boolean = false,
) {
    val parked: Boolean? get() = hub[VehicleSignal.GEARBOX_PARK]?.let { it >= 1.0 }
    val speedKmh: Double? get() = hub[VehicleSignal.VEHICLE_SPEED]
    val engineRunning: Boolean? get() = hub[VehicleSignal.ENGINE_RUNNING]?.let { it >= 1.0 }

    /** P at 0 km/h by the hub: true, false, or null while either is unknown. */
    val parkedStill: Boolean?
        get() {
            val p = parked
            val v = speedKmh
            return when {
                p == false || (v != null && v > 0.0) -> false
                p == true && v == 0.0 -> true
                else -> null
            }
        }

    /** A fresh extra, or null. */
    fun extra(signal: DiagnosticExtra): Double? = if (extrasFresh) extras[signal] else null
}

/** Where [CarReadings] come from; opened and closed with the session. */
internal interface CarFeed {
    fun open()
    fun read(): CarReadings
    fun close()

    /**
     * The parameter page's GET (architecture, 6.2): while set, only [get] is polled, about ten times
     * a second, and the rest of the batch stands; null brings the batch back.
     */
    fun focus(get: SignalGet?) {}

    /** The focused GET's readings since the last call, oldest first. */
    fun focused(): List<SignalReading> = emptyList()

    /**
     * A page with GETs of its own shown or left (architecture, 6): while [page] shows the batch carries
     * its GETs too ([DiagnosticExtra.page]), from the next round on; no other page reads them.
     */
    fun page(page: ExtraPage, shown: Boolean) {}
}

/** One `autoservice` GET, polled alone for a parameter page: a [DiagnosticExtra] or a cold hub signal. */
internal class SignalGet(val device: Int, val fid: Int, val transact: Int, val decode: (Int) -> Double?) {
    /** The shell command; its answer's word is [DiagnosticExtraReader.word]. */
    val command: String get() = "service call autoservice $transact i32 $device i32 $fid"

    companion object {
        fun of(extra: DiagnosticExtra) = SignalGet(extra.device, extra.fid, extra.transact, extra::decode)
        fun of(signal: VehicleSignal) = SignalGet(signal.device, signal.fid, signal.transact.code) { AutoserviceShell.decode(signal, it) }
    }
}

/** One reading of the focused GET, on the head unit's `elapsedRealtimeNanos`; a null value is a gap. */
internal data class SignalReading(val atNanos: Long, val value: Double?)

/**
 * Hub values held for their own horizon, the way the retired window held them: a new snapshot is
 * a new read, the same snapshot is not, and a dropped sweep never makes cold data young again.
 */
internal class HeldTelemetry {
    private var previous: VehicleTelemetry? = null
    private val held = HashMap<VehicleSignal, Pair<Double, Long>>()

    /** Count of new answered sweeps seen. */
    var sweep = 0L
        private set

    fun read(t: VehicleTelemetry, nowMillis: Long): Map<VehicleSignal, Double> {
        if (t.access != VehicleAccess.READY) {
            held.clear()
        } else if (t !== previous && !t.dropped) {
            t.values.forEach { (key, value) -> held[key] = value to nowMillis }
            sweep++
        }
        previous = t
        held.entries.removeAll { (key, value) -> nowMillis - value.second >= (key.poll.staleSeconds * 1000).toLong() }
        return held.mapValues { it.value.first }
    }
}
