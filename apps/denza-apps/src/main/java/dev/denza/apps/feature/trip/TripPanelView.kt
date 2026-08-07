package dev.denza.apps.feature.trip

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner

/**
 * The trip panel itself: a lean custom View that draws directly on the screen
 * background (no card, no border, no frame), runs a Choreographer loop throttled
 * to <=30 FPS, attaches to the process-scoped [TripSession] hub, and draws the
 * single [TripPanelRenderer] screen. Relaunching the activity re-attaches to the
 * same trip — the view never owns or resets the engine. Touches do nothing.
 *
 * The whole panel is gated by the compile-time [TripPanelFlag]; when it is off,
 * Compose never adds this view, so nothing here runs.
 *
 * Sensors and rendering are fully stopped when the panel is not visible or the
 * activity is paused. The draw path preallocates all Paint state.
 */
@SuppressLint("ViewConstructor")
class TripPanelView(context: Context) : View(context), Choreographer.FrameCallback {

    private val hub = TripSession.hub(context)
    private val renderer = TripPanelRenderer()

    private var looping = false
    private var startNs = 0L
    private var lastDrawNs = 0L
    private var lastFrameNs = 0L

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) = startLoop()
        override fun onPause(owner: LifecycleOwner) = stopLoop()
    }

    init {
        contentDescription = "Панель поездки"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val lifecycle = findViewTreeLifecycleOwner()?.lifecycle
        if (lifecycle != null) {
            lifecycle.addObserver(lifecycleObserver)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) startLoop()
        } else {
            startLoop()
        }
    }

    override fun onDetachedFromWindow() {
        findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycleObserver)
        stopLoop()
        super.onDetachedFromWindow()
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        hub.start(context)
        startNs = System.nanoTime()
        lastDrawNs = 0L
        lastFrameNs = 0L
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun stopLoop() {
        if (!looping) return
        looping = false
        Choreographer.getInstance().removeFrameCallback(this)
        hub.stop()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!looping) return
        Choreographer.getInstance().postFrameCallback(this)
        if (lastDrawNs != 0L && frameTimeNanos - lastDrawNs < MIN_FRAME_NS) return
        lastDrawNs = frameTimeNanos
        hub.tick()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 1.0 / 30.0 else (now - lastFrameNs) / 1_000_000_000.0
        lastFrameNs = now
        val frameTime = (now - startNs) / 1_000_000_000.0
        // The renderer places the "no location access" hint in an area that
        // stays clear of its own layout.
        renderer.draw(
            canvas, width.toFloat(), height.toFloat(), hub.engine, frameTime, dt,
            showLocationHint = !hub.locationGranted,
        )
    }

    private companion object {
        const val MIN_FRAME_NS = 1_000_000_000L / 30L
    }
}
