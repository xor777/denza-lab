package dev.denza.apps.feature.trip

import dev.denza.apps.feature.vehicle.VehicleTelemetry
import dev.denza.apps.feature.vehicle.VehicleWatcher

/**
 * Whether the selector is in P, as the vehicle hub reads it, handed to the trip engine.
 *
 * The trip clock used to poll the same feature id itself, once a second over a shell session of
 * its own, while the vehicle hub - which runs for the life of the process - read it in every one of
 * its own sweeps. One id, two readers, two parsers that could disagree about where a trip ends. The
 * clock reads the hub's answer now ([VehicleTelemetry.parked]) under its own claim,
 * [VehicleWatcher.TRIP], held from [start] to [stop], so P stays polled while the strip runs
 * whatever else watches the car.
 *
 * What the engine is handed has not changed: `true` in P, `false` out of it, `null` when the read
 * did not answer - a sweep that failed, or one whose park word was not a switch value. It is handed
 * once a second whatever the screen does, as the old reader handed it: the frames the strip draws
 * and the GNSS fixes that drive the trip's movement gate hand it too, sooner, but a stalled frame
 * loop must not leave the gate deciding on a P that is no longer true. A sweep is handed once,
 * however many of those ask.
 */
internal class TripParkFeed(
    private val engine: TripEngine,
    /** The vehicle hub's latest sweep. */
    private val telemetry: () -> VehicleTelemetry,
    /** The vehicle hub's claims: `VehicleTelemetryHub.setActive`. */
    private val claim: (VehicleWatcher, Boolean) -> Unit,
    /** The engine's clock, `SystemClock.elapsedRealtime`. */
    private val clock: () -> Long,
    /** Runs a block after a delay on the main looper, and takes one back. */
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
) {
    private var last: VehicleTelemetry? = null
    private var running = false

    private val second = object : Runnable {
        override fun run() {
            if (!running) return
            feed()
            schedule(this, EVERY_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        claim(VehicleWatcher.TRIP, true)
        feed()
        schedule(second, EVERY_MS)
    }

    fun stop() {
        if (!running) return
        running = false
        unschedule(second)
        claim(VehicleWatcher.TRIP, false)
    }

    /** Hands the hub's latest sweep to the engine, unless it already has it. */
    fun feed() {
        onTelemetry(telemetry(), clock())
    }

    fun onTelemetry(snapshot: VehicleTelemetry, nowElapsedMs: Long) {
        if (snapshot === last) return
        last = snapshot
        engine.onParkState(snapshot.parked, nowElapsedMs)
    }

    companion object {
        /** The old reader's own cadence, and about the ledger's sweep. */
        const val EVERY_MS = 1_000L
    }
}
