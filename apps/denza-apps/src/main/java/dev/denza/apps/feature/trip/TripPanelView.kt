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
 * Inputs and rendering stop when the panel is detached or the activity is
 * paused. The draw path preallocates all Paint state.
 */
@SuppressLint("ViewConstructor")
class TripPanelView(context: Context) : View(context), Choreographer.FrameCallback {

    private val hub = TripSession.hub(context)
    private val renderer = TripPanelRenderer()

    private var looping = false
    private var attached = false
    private var resumed = false
    private var startNs = 0L
    private var lastDrawNs = 0L
    private var lastFrameNs = 0L

    var layout: TripPanelLayout = TripPanelLayout.WIDE
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            resumed = true
            hub.start(context)
            syncLoop()
        }

        override fun onPause(owner: LifecycleOwner) {
            resumed = false
            syncLoop()
            hub.stop()
        }
    }

    init {
        contentDescription = "Панель поездки"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        val lifecycle = findViewTreeLifecycleOwner()?.lifecycle
        if (lifecycle != null) {
            lifecycle.addObserver(lifecycleObserver)
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        } else {
            resumed = true
        }
        if (resumed) hub.start(context)
        syncLoop()
    }

    override fun onDetachedFromWindow() {
        findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycleObserver)
        attached = false
        stopLoop()
        hub.stop()
        super.onDetachedFromWindow()
    }

    private fun syncLoop() {
        if (attached && resumed) startLoop() else stopLoop()
    }

    private fun startLoop() {
        if (looping) return
        looping = true
        startNs = System.nanoTime()
        lastDrawNs = 0L
        lastFrameNs = 0L
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
            canvas, width.toFloat(), height.toFloat(), hub.engine, hub.spectrum, hub.nowPlaying,
            frameTime, dt,
            showLocationHint = !hub.locationGranted,
            layout = layout,
        )
    }

    private companion object {
        const val MIN_FRAME_NS = 1_000_000_000L / 30L
    }
}
