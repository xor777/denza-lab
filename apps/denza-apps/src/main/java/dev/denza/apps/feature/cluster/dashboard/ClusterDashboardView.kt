package dev.denza.apps.feature.cluster.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import dev.denza.apps.feature.vehicle.VehicleSession
import dev.denza.apps.feature.vehicle.VehicleTelemetry

/**
 * The Contour's window onto the driver's display, and its clock.
 *
 * A `Presentation` has no lifecycle owner, so attachment and window visibility are the lifecycle
 * signals for this view. The presentation adds it when the driver asks for the panel and removes it
 * when they leave, which is exactly when vehicle polling should run.
 *
 * ### Why this draws faster than the data arrives
 *
 * The panel this replaces redrew only when a snapshot changed, at the poll's own three times a
 * second, because nothing on it moved between snapshots. The Contour's band, hero, glow and
 * revolutions are all critically damped followers, and a follower has to be *integrated* - the whole
 * point of it is the frames between two readings. So the loop runs at [FAST_FPS] and every frame
 * advances [ContourMotion] and [ContourScene] by the time that actually passed.
 *
 * It falls to [SLOW_FPS] when nothing is moving and no reading has arrived, which on a car standing
 * in P is most of the time. That is a real saving on a panel that is drawn over somebody else's
 * instruments, and it costs nothing: the followers are integrated exactly, so a 200 ms frame is as
 * correct as a 33 ms one.
 *
 * `dt` comes from the frame clock rather than from a wall clock, and is clamped, so a stall cannot
 * hand a follower a second of travel in one step.
 */
@SuppressLint("ViewConstructor")
internal class ClusterDashboardView(
    context: Context,
    private val layout: ClusterDashboardLayout,
) : View(context), Choreographer.FrameCallback {

    private val hub = VehicleSession.hub(context)
    private val renderer = ClusterDashboardRenderer()
    private val motion = ContourMotion()
    private val scene = ContourScene()

    private var looping = false
    private var lastFrameNs = 0L
    private var lastDrawNs = 0L
    private var lastSnapshot: VehicleTelemetry? = null

    init {
        contentDescription = CONTENT_DESCRIPTION
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hub.setDashboardActive(true)
        startLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        hub.setDashboardActive(false)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        val visible = visibility == VISIBLE
        hub.setDashboardActive(visible)
        if (visible) startLoop() else stopLoop()
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        lastFrameNs = 0L
        lastDrawNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopLoop() {
        if (!looping) return
        looping = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!looping) return
        Choreographer.getInstance().postFrameCallback(this)

        val snapshot = hub.snapshot
        // The hub allocates a fresh snapshot per sweep, so identity is the change test.
        val arrived = snapshot !== lastSnapshot
        if (arrived) lastSnapshot = snapshot

        val budget = if (arrived || moving()) FAST_FRAME_NS else SLOW_FRAME_NS
        if (lastDrawNs != 0L && frameTimeNanos - lastDrawNs < budget) return

        val dt = if (lastFrameNs == 0L) {
            0f
        } else {
            ((frameTimeNanos - lastFrameNs) / NANOS_PER_SECOND).toFloat().coerceIn(0f, MAX_STEP_S)
        }
        lastFrameNs = frameTimeNanos
        lastDrawNs = frameTimeNanos

        scene.frame(snapshot, arrived, dt)
        motion.step(power(snapshot), revolutions(snapshot), dt)
        invalidate()
    }

    /**
     * Whether anything on the panel is still travelling toward a reading.
     *
     * Cheap and honest: the band is the fastest thing here, so if its follower has arrived, the glow
     * behind it is the only thing still moving and a fifth of a second of it is invisible.
     */
    private fun moving(): Boolean = motion.powerReady && kotlin.math.abs(motion.powerKw - motion.glowKw) > STILL_KW

    /**
     * What the band is drawn from, and a charge reads as energy arriving rather than as a load.
     *
     * A gun in and the pack taking two kilowatts is the same event the band already draws going the
     * other way, so it is drawn going the other way.
     */
    private fun power(t: VehicleTelemetry): Float? {
        if (!scene.fresh(ContourValue.POWER)) return null
        if (t.charging) {
            val charge = t.chargeKw ?: return t.loadKw?.toFloat()
            return -kotlin.math.abs(charge).toFloat()
        }
        return t.loadKw?.toFloat()
    }

    private fun revolutions(t: VehicleTelemetry): Float? {
        if (!scene.fresh(ContourValue.RPM)) return null
        return t.engineRpm?.toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        renderer.draw(
            canvas = canvas,
            width = width.toFloat(),
            height = height.toFloat(),
            layout = layout,
            telemetry = lastSnapshot ?: hub.snapshot,
            motion = motion,
            scene = scene,
        )
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0

        /** While anything is moving or a reading has just landed. */
        const val FAST_FPS = 30L
        const val FAST_FRAME_NS = 1_000_000_000L / FAST_FPS

        /** And while nothing is, which on a parked car is most of the time. */
        const val SLOW_FPS = 5L
        const val SLOW_FRAME_NS = 1_000_000_000L / SLOW_FPS

        /** A stall must not hand a follower a second of travel in one step. */
        const val MAX_STEP_S = 0.25f

        /** Under this the band and its glow are the same picture. */
        const val STILL_KW = 0.25f

        const val CONTENT_DESCRIPTION = "Приборы на экране водителя"
    }
}
