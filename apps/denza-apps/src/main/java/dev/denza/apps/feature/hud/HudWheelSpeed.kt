package dev.denza.apps.feature.hud

import android.content.Context
import dev.denza.apps.feature.vehicle.VehicleSession
import dev.denza.apps.feature.vehicle.VehicleSignal
import dev.denza.apps.feature.vehicle.VehicleTelemetry
import dev.denza.apps.feature.vehicle.VehicleWatcher

/**
 * The car's speed from its wheels for HUD Speed's camera ([HudSpeeding]): the vehicle hub's
 * reading, at the hub's fast cadence while a camera is ahead ([VehicleWatcher.HUD_CAMERA]).
 * Main thread.
 */
internal class HudWheelSpeed(context: Context) {
    private val hub = VehicleSession.hub(context)
    private var watching = false

    /** A camera is ahead, or no longer is. */
    fun watch(on: Boolean) {
        if (on == watching) return
        watching = on
        hub.setActive(VehicleWatcher.HUD_CAMERA, on)
    }

    /** km/h; null when the car did not answer the last sweep or its answer is older than [STALE_MS]. */
    fun kmh(now: Long): Double? = kmh(hub.snapshot, now)

    companion object {
        /** The Contour's horizon for a reading that stopped coming (`ContourScene.STALE_SECONDS`). */
        const val STALE_MS = 2_000L

        /**
         * [snapshot]'s speed at [now] (`elapsedRealtime`), by the age of the sweep that read it.
         * Until 2026-11-19 the age counted from when this reader first saw the snapshot, so the
         * first camera stamped a hub's retained 80 km/h, already seconds old, as fresh.
         */
        fun kmh(snapshot: VehicleTelemetry, now: Long): Double? {
            val at = snapshot.hotReadAtMillis
            if (at <= 0L || now - at > STALE_MS) return null
            return snapshot[VehicleSignal.VEHICLE_SPEED]
        }
    }
}
