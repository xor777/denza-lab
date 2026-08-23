package dev.denza.apps.feature.vehicle

import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner

/**
 * Hosts [EnginePanelRenderer] on the same telemetry hub the vehicle page uses.
 *
 * Nothing on this page animates, so the frame loop exists only to notice a new
 * snapshot; it redraws on a changed reading and otherwise costs a comparison per
 * frame, capped well below display rate.
 *
 * Being on screen is what switches the combustion half of the allowlist into the
 * poll — see [VehicleTelemetryHub.setEngineActive]. Leaving the page switches it
 * back out, which is why the other page's power figure does not slow down for a
 * set of lamps nobody is looking at.
 */
internal class EnginePanelView(context: Context) : View(context), Choreographer.FrameCallback {

    private val hub = VehicleSession.hub(context)
    private val renderer = EnginePanelRenderer().apply { icons = VehicleIcons(context) }

    private var looping = false
    private var attached = false
    private var resumed = false
    private var lastDrawNs = 0L
    private var lastSnapshot: VehicleTelemetry? = null

    var narrowLayout: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    var pageVisible: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            hub.setEngineActive(value)
            syncHub()
            syncLoop()
            if (value) invalidate()
        }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            resumed = true
            syncHub()
            syncLoop()
        }

        override fun onPause(owner: LifecycleOwner) {
            resumed = false
            syncLoop()
            hub.stop()
        }
    }

    init {
        contentDescription = "Панель двигателя"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        hub.setEngineActive(pageVisible)
        val lifecycle = findViewTreeLifecycleOwner()?.lifecycle
        if (lifecycle != null) {
            lifecycle.addObserver(lifecycleObserver)
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        } else {
            resumed = true
        }
        syncHub()
        syncLoop()
    }

    override fun onDetachedFromWindow() {
        findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycleObserver)
        attached = false
        hub.setEngineActive(false)
        stopLoop()
        hub.stop()
        super.onDetachedFromWindow()
    }

    private fun syncHub() {
        if (attached && resumed && (pageVisible || hub.visited)) hub.start() else hub.stop()
    }

    private fun syncLoop() {
        if (attached && resumed && pageVisible) startLoop() else stopLoop()
    }

    private fun startLoop() {
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
            telemetry = hub.snapshot,
            narrowLayout = narrowLayout,
        )
    }

    private companion object {
        const val MIN_FRAME_NS = 1_000_000_000L / 10L
    }
}
