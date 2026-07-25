package dev.denza.apps.feature.trip

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import kotlin.math.abs

/**
 * The trip panel itself: a lean custom View that draws directly on the screen
 * background (no card, no border, no frame), runs a Choreographer loop throttled
 * to <=30 FPS, attaches to the process-scoped [TripSession] hub, and cycles the
 * three renderers. Relaunching the activity re-attaches to the same trip — the
 * view never owns or resets the engine.
 *
 * The whole panel is gated by the compile-time [TripPanelFlag]; when it is off,
 * Compose never adds this view, so nothing here runs.
 *
 * Controls: a tap cycles forward; a horizontal swipe changes mode (left = next,
 * right = previous). Mode changes play a short slide + crossfade between the two
 * renderers. Sensors and rendering are fully stopped when the panel is not
 * visible or the activity is paused. The draw path preallocates all Paint state.
 */
@SuppressLint("ViewConstructor")
class TripPanelView(context: Context) : View(context), Choreographer.FrameCallback {

    private val hub = TripSession.hub(context)
    private val renderers = arrayOf(FlightRenderer(), GlassRenderer(), ThreadRenderer())
    private var mode: TripMode = TripSettings.mode(context)

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var looping = false
    private var startNs = 0L
    private var lastDrawNs = 0L
    private var lastFrameNs = 0L

    // Slide + crossfade transition state.
    private var transitionFrom: TripMode? = null
    private var transitionDir = 1
    private var transitionStartNs = 0L

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                changeMode(mode.next(), direction = 1)
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                // Only a mostly-horizontal, fast enough gesture counts as a swipe;
                // GestureDetector already applied touch slop so taps never reach here.
                if (abs(velocityX) < SWIPE_MIN_VELOCITY || abs(velocityX) <= abs(velocityY)) {
                    return false
                }
                if (velocityX < 0) changeMode(mode.next(), direction = 1)
                else changeMode(mode.previous(), direction = -1)
                return true
            }
        },
    )

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) = startLoop()
        override fun onPause(owner: LifecycleOwner) = stopLoop()
    }

    init {
        contentDescription = "Панель поездки"
        isClickable = true
    }

    private fun changeMode(newMode: TripMode, direction: Int) {
        if (newMode == mode) return
        transitionFrom = mode
        transitionDir = direction
        transitionStartNs = System.nanoTime()
        mode = newMode
        TripSettings.setMode(context, mode)
        invalidate()
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
        transitionFrom = null
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
        val engine = hub.engine
        if (width <= 0 || height <= 0) return
        val now = System.nanoTime()
        val dt = if (lastFrameNs == 0L) 1.0 / 30.0 else (now - lastFrameNs) / 1_000_000_000.0
        lastFrameNs = now
        val frameTime = (now - startNs) / 1_000_000_000.0
        // Each renderer places the "no location access" hint in an area that
        // stays clear of its own layout; the panel no longer draws it globally.
        val showHint = !hub.locationGranted

        val from = transitionFrom
        if (from == null) {
            renderers[mode.ordinal].draw(
                canvas, width.toFloat(), height.toFloat(), engine, frameTime, dt, showHint,
            )
        } else {
            val raw = (now - transitionStartNs) / 1_000_000.0 / TRANSITION_MS
            if (raw >= 1.0) {
                transitionFrom = null
                renderers[mode.ordinal].draw(
                    canvas, width.toFloat(), height.toFloat(), engine, frameTime, dt, showHint,
                )
            } else {
                val ease = smoothstep(raw)
                val slide = width * SLIDE_FRACTION
                // Outgoing renderer slides away and fades out; incoming one slides
                // in from the swipe direction and fades in. A cheap, transient
                // saveLayerAlpha handles the fade — no bitmap snapshots.
                drawLayer(
                    canvas, from, engine, frameTime, dt, showHint,
                    offsetX = (-transitionDir * ease * slide).toFloat(),
                    alpha = (1.0 - ease).toFloat(),
                )
                drawLayer(
                    canvas, mode, engine, frameTime, dt, showHint,
                    offsetX = (transitionDir * (1.0 - ease) * slide).toFloat(),
                    alpha = ease.toFloat(),
                )
            }
        }
        drawModeIndicator(canvas)
    }

    private fun drawLayer(
        canvas: Canvas,
        m: TripMode,
        engine: TripEngine,
        frameTime: Double,
        dt: Double,
        showHint: Boolean,
        offsetX: Float,
        alpha: Float,
    ) {
        val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        val restore = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), a)
        canvas.translate(offsetX, 0f)
        renderers[m.ordinal].draw(canvas, width.toFloat(), height.toFloat(), engine, frameTime, dt, showHint)
        canvas.restoreToCount(restore)
    }

    private fun drawModeIndicator(canvas: Canvas) {
        val cx = width / 2f
        val sy = height / 360f
        val spacing = 16f * sy
        val radius = 3.5f * sy
        val y = height - 16f * sy
        for (i in renderers.indices) {
            dotPaint.color = if (i == mode.ordinal) {
                TripPalette.MINT
            } else {
                TripPalette.alpha(TripPalette.MUTED, 0.35f)
            }
            canvas.drawCircle(cx + (i - 1) * spacing, y, radius, dotPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private companion object {
        const val MIN_FRAME_NS = 1_000_000_000L / 30L
        const val TRANSITION_MS = 250.0
        const val SLIDE_FRACTION = 0.16f
        const val SWIPE_MIN_VELOCITY = 400f

        fun smoothstep(t: Double): Double {
            val x = t.coerceIn(0.0, 1.0)
            return x * x * (3.0 - 2.0 * x)
        }
    }
}
