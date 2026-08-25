package dev.denza.apps.feature.vehicle

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner

/**
 * The vehicle page of the bottom panel: a frameless custom View that draws
 * [VehiclePanelRenderer] from whatever [VehicleTelemetryHub] last published.
 *
 * Two separate switches, deliberately:
 *
 *  - the hub runs while the panel is attached and the activity is resumed, even
 *    when the other page is the one on screen. That is what keeps the
 *    consumption histogram continuous across a swipe.
 *  - the draw loop runs only while this page is the visible one ([pageVisible]),
 *    so the invisible neighbour costs nothing to render.
 *
 * The loop is capped at 15 FPS and only redraws when the snapshot actually
 * changed — the page is instruments, not animation. Charging is the one
 * exception: the battery's next segment breathes, so those frames keep coming.
 */
@SuppressLint("ViewConstructor")
internal class VehiclePanelView(context: Context) : View(context), Choreographer.FrameCallback {

    private val hub = VehicleSession.hub(context)
    private val renderer = VehiclePanelRenderer().apply { icons = VehicleIcons(context) }

    private var looping = false
    private var attached = false
    private var resumed = false
    private var startNs = 0L
    private var lastDrawNs = 0L
    private var lastSnapshot: VehicleTelemetry? = null

    var narrowLayout: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    var pageVisible: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            hub.setActive(value)
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
        contentDescription = "Панель машины"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        hub.setActive(pageVisible)
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
        stopLoop()
        hub.stop()
        super.onDetachedFromWindow()
    }

    /**
     * The hub polls while the panel is alive, but not before this page has been
     * opened once: a session that never swipes here never touches the car.
     */
    private fun syncHub() {
        if (attached && resumed && (pageVisible || hub.visited)) hub.start() else hub.stop()
    }

    private fun syncLoop() {
        if (attached && resumed && pageVisible) startLoop() else stopLoop()
    }

    /** The window is re-read where it can have changed, not on every frame. */
    private fun syncWindow() {
        renderer.window = ConsumptionSettings.window(context)
    }

    private fun startLoop() {
        syncWindow()
        if (looping) return
        looping = true
        startNs = System.nanoTime()
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
        if (snapshot !== lastSnapshot || snapshot.charging) {
            lastSnapshot = snapshot
            invalidate()
        }
    }

    /**
     * A tap on the chart moves to the next window.
     *
     * One control, on the thing it controls, and no chrome: this panel has no
     * widgets and adding a row of chips would mean giving it a second layout
     * system. The window's name is drawn under the chart, so the tap has
     * something to have changed.
     *
     * The choice is written to settings rather than held here because the
     * driver's cluster obeys it too and has no touchscreen of its own.
     */
    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) {
            return event.actionMasked == MotionEvent.ACTION_DOWN &&
                renderer.chartBounds.contains(event.x, event.y)
        }
        if (!renderer.chartBounds.contains(event.x, event.y)) return false
        renderer.window = renderer.window.next
        ConsumptionSettings.setWindow(context, renderer.window)
        performClick()
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val frameTimeSec = (System.nanoTime() - startNs) / 1_000_000_000.0
        renderer.draw(
            canvas = canvas,
            width = width.toFloat(),
            height = height.toFloat(),
            telemetry = hub.snapshot,
            frameTimeSec = frameTimeSec,
            narrowLayout = narrowLayout,
        )
    }

    private companion object {
        const val MIN_FRAME_NS = 1_000_000_000L / 15L
    }
}
