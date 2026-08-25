package dev.denza.apps.feature.cluster.dashboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import dev.denza.apps.feature.vehicle.ConsumptionSettings
import dev.denza.apps.feature.vehicle.ConsumptionWindow
import dev.denza.apps.feature.vehicle.VehicleSession
import dev.denza.apps.feature.vehicle.VehicleTelemetry

/**
 * The dashboard's window onto the driver's display.
 *
 * It differs from the two panel views in the bottom pager in one way that matters: there is no
 * lifecycle owner here. A `Presentation` sets no `ViewTreeLifecycleOwner`, so the usual
 * `findViewTreeLifecycleOwner()` returns null and the panels' resume/pause gate silently degrades
 * to "always resumed". Attachment is therefore the only signal this view has, and it is the right
 * one: the presentation adds this view when the driver asked for the dashboard and removes it when
 * they did not, which is exactly when the poll should run.
 *
 * Ten frames a second is the cap. Nothing here animates; the sweep that feeds it takes roughly half
 * a second once the combustion signals join, so anything faster would redraw identical pixels.
 */
@SuppressLint("ViewConstructor")
internal class ClusterDashboardView(
    context: Context,
    private val layout: ClusterDashboardLayout,
) : View(context), Choreographer.FrameCallback {

    private val hub = VehicleSession.hub(context)
    private val renderer = ClusterDashboardRenderer()

    /**
     * The window chosen on the head unit.
     *
     * Re-read whenever this view comes back, because that is the only moment it
     * can have changed: the driver's display has no touchscreen, so the choice is
     * always made somewhere else and arrives here through the setting.
     */
    private var window = ConsumptionWindow.DEFAULT

    private var looping = false
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
        window = ConsumptionSettings.window(context)
        if (looping) return
        looping = true
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
        if (lastDrawNs != 0L && frameTimeNanos - lastDrawNs < MIN_FRAME_NS) return
        lastDrawNs = frameTimeNanos
        val snapshot = hub.snapshot
        // The hub allocates a fresh snapshot per sweep, so identity is the change test.
        if (snapshot !== lastSnapshot) {
            lastSnapshot = snapshot
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        renderer.draw(
            canvas = canvas,
            width = width.toFloat(),
            height = height.toFloat(),
            layout = layout,
            telemetry = hub.snapshot,
            window = window,
        )
    }

    private companion object {
        const val MIN_FRAME_NS = 1_000_000_000L / 10L
        const val CONTENT_DESCRIPTION = "Приборы на экране водителя"
    }
}
