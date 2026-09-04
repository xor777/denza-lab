package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.vehicle.signal.TurnIndicatorMode
import dev.denza.apps.feature.vehicle.signal.VehicleSignalState

/** Evidence for one narrow recovery path, never a request to open a camera on its own. */
internal object MirrorSameSideRearm {
    fun observedAtMs(
        mode: VehicleSignalState<TurnIndicatorMode>,
        windowSide: MirrorSide?,
        nowMs: Long,
    ): Long? {
        val fresh = mode as? VehicleSignalState.Fresh ?: return null
        val side = when (fresh.value) {
            TurnIndicatorMode.LEFT -> MirrorSide.LEFT
            TurnIndicatorMode.RIGHT -> MirrorSide.RIGHT
            else -> return null
        }
        if (side != windowSide || fresh.observedAtElapsedMs < 0L ||
            fresh.verifiedAtElapsedMs < fresh.observedAtElapsedMs ||
            fresh.verifiedAtElapsedMs > nowMs
        ) return null
        // Verification/heartbeat alone must never turn a pre-cancellation value into new intent.
        return fresh.observedAtElapsedMs
    }
}
