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
    private var seen: VehicleTelemetry? = null
    private var seenAt = 0L
    private var watching = false

    /** A camera is ahead, or no longer is. */
    fun watch(on: Boolean) {
        if (on == watching) return
        watching = on
        hub.setActive(VehicleWatcher.HUD_CAMERA, on)
    }

    /** km/h; null when the car did not answer the last sweep or no sweep came for [STALE_MS]. */
    fun kmh(now: Long): Double? {
        val snapshot = hub.snapshot
        if (snapshot !== seen) {
            seen = snapshot
            seenAt = now
        }
        return if (now - seenAt > STALE_MS) null else snapshot[VehicleSignal.VEHICLE_SPEED]
    }

    companion object {
        /** The Contour's horizon for a reading that stopped coming (`ContourScene.STALE_SECONDS`). */
        const val STALE_MS = 2_000L
    }
}
