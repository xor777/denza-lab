package dev.denza.apps.feature.cluster.dashboard

import dev.denza.apps.feature.vehicle.VehicleTelemetry

/**
 * The cluster as a test drives it: the panel's three stateful parts and the frame they fill,
 * stepped together as the view does, from a snapshot to the [ContourFrame] the renderer draws.
 *
 * `ContourFrameBuilderTest` holds that frame to the boards and `EnergyReadoutsTest` holds it to the
 * car page, and both go through this one harness, so they are about the same cluster.
 */
internal class ContourPanel {
    val scene = ContourScene()
    val motion = ContourMotion()
    val builder = ContourFrameBuilder()
    val frame = ContourFrame()
    var clock = 0f

    /** [seconds] of frames with [t] arriving three times a second, or never if [quiet]. */
    fun run(t: VehicleTelemetry, seconds: Float, quiet: Boolean = false): ContourFrame {
        var elapsed = 0f
        var next = 0f
        while (elapsed < seconds) {
            val arrived = !quiet && elapsed >= next
            if (arrived) next += 1f / 3f
            scene.frame(t, arrived, STEP)
            motion.step(scene.held(ContourValue.POWER), scene.held(ContourValue.RPM), STEP)
            clock += STEP
            elapsed += STEP
        }
        return builder.build(frame, t, motion, scene, clock)
    }

    private companion object {
        /** The view's own frame: thirty a second. */
        const val STEP = 1f / 30f
    }
}
