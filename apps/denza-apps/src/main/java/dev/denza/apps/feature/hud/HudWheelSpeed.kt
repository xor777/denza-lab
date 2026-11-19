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

    /** km/h; null when the car did not answer the last sweep or its read began a horizon ago. */
    fun kmh(now: Long): Double? = kmh(hub.snapshot, now)

    companion object {
        /**
         * [snapshot]'s speed at [now] (`elapsedRealtime`), aged on the Contour's horizon
         * ([VehicleTelemetry.HOT_HORIZON_MS]) from when its sweep's read started: the wheels may
         * have answered at the start of a batch that ran seconds with its cold ids. Until
         * 2026-11-19 the age counted from when this reader first saw the snapshot, then from the
         * batch's end; both let an 80 km/h read before braking pass as now.
         */
        fun kmh(snapshot: VehicleTelemetry, now: Long): Double? {
            val at = snapshot.hotReadStartedAtMillis
            if (at <= 0L || now - at >= VehicleTelemetry.HOT_HORIZON_MS) return null
            return snapshot[VehicleSignal.VEHICLE_SPEED]
        }
    }
}
