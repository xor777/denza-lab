package dev.denza.apps.feature.cluster

import dev.denza.apps.feature.mirrors.MirrorSide
import java.util.concurrent.atomic.AtomicReference

class CameraRuntimeTracker {
    private val current = AtomicReference(CameraRuntimeSnapshot())

    fun snapshot(): CameraRuntimeSnapshot = current.get()

    fun starting(side: MirrorSide): CameraRuntimeSnapshot =
        publish(CameraRuntimePhase.STARTING, side, "starting ${side.name.lowercase()}")

    fun ready(side: MirrorSide, details: String): CameraRuntimeSnapshot =
        publish(CameraRuntimePhase.READY, side, details)

    fun stopping(details: String): CameraRuntimeSnapshot =
        publish(CameraRuntimePhase.STOPPING, current.get().side, details)

    fun failed(details: String): CameraRuntimeSnapshot =
        publish(CameraRuntimePhase.FAILED, current.get().side, details)

    fun idle(details: String): CameraRuntimeSnapshot =
        publish(CameraRuntimePhase.IDLE, null, details)

    private fun publish(
        phase: CameraRuntimePhase,
        side: MirrorSide?,
        details: String,
    ): CameraRuntimeSnapshot = current.updateAndGet { previous ->
        CameraRuntimeSnapshot(
            phase = phase,
            side = side,
            generation = previous.generation + 1L,
            details = details,
        )
    }
}
