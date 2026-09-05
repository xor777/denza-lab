package dev.denza.apps.feature.cluster.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import dev.denza.apps.feature.vehicle.VehicleSession
import dev.denza.apps.feature.vehicle.VehicleWatcher
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
 * point of it is the frames between two readings. So the loop runs at [ContourPace.FAST_FPS] and
 * every frame
 * advances [ContourMotion] and [ContourScene] by the time that actually passed.
 *
 * It falls to [ContourPace.SLOW_FPS] when nothing is moving and no reading has arrived, which on a
 * car standing in P is most of the time. That is a real saving on a panel that is drawn over
 * somebody else's instruments, and it costs nothing: the followers are integrated exactly, so a
 * 200 ms frame is as correct as a 33 ms one - and the callback is posted that far ahead rather than
 * on the next vsync, so the saving is the wakeup as well as the draw.
 *
 * Which vsync is worth drawing, what `dt` it carries and whether a sweep is still waiting to be
 * shown are all [ContourPace], which is where they can be tested.
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
    private val pace = ContourPace()

    private var looping = false
    private var lastSnapshot: VehicleTelemetry? = null

    init {
        contentDescription = CONTENT_DESCRIPTION
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        hub.setActive(VehicleWatcher.CLUSTER, true)
        startLoop()
    }

    override fun onDetachedFromWindow() {
        stopLoop()
        hub.setActive(VehicleWatcher.CLUSTER, false)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        val visible = visibility == VISIBLE
        hub.setActive(VehicleWatcher.CLUSTER, visible)
        if (visible) startLoop() else stopLoop()
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        pace.restart()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopLoop() {
        if (!looping) return
        looping = false
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!looping) return

        val snapshot = hub.snapshot
        // The hub allocates a fresh snapshot per sweep, so identity is the change test. The arrival
        // is latched in the pace rather than consumed here: the frame that hears about a sweep is
        // not necessarily the frame the budget lets draw it.
        if (snapshot !== lastSnapshot) {
            lastSnapshot = snapshot
            pace.sweepArrived()
        }

        if (pace.frame(frameTimeNanos, moving())) {
            scene.frame(snapshot, pace.arrived, pace.dt)
            motion.step(
                scene.held(ContourValue.POWER),
                scene.held(ContourValue.RPM),
                pace.dt,
            )
            invalidate()
        }
        askAgain()
    }

    /** Sleep the rest of the budget rather than waking on every vsync to decline it. */
    private fun askAgain() {
        if (!looping) return
        val choreographer = Choreographer.getInstance()
        val delayMillis = pace.sleepNs / ContourPace.NANOS_PER_MILLI
        if (delayMillis <= 0L) {
            choreographer.postFrameCallback(this)
        } else {
            choreographer.postFrameCallbackDelayed(this, delayMillis)
        }
    }

    /**
     * Whether anything on the panel is still travelling toward a reading.
     *
     * Cheap and honest: the band is the fastest thing here, so if its follower has arrived, the glow
     * behind it is the only thing still moving and a fifth of a second of it is invisible.
     */
    private fun moving(): Boolean =
        motion.powerReady && kotlin.math.abs(motion.powerKw - motion.glowKw) > STILL_KW

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
        /** Under this the band and its glow are the same picture. */
        const val STILL_KW = 0.25f

        const val CONTENT_DESCRIPTION = "Приборы на экране водителя"
    }
}
