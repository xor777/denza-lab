package dev.denza.apps.feature.trip

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import dev.denza.apps.feature.vehicle.VehicleSession
import dev.denza.apps.feature.vehicle.VehicleWatcher
import kotlin.math.abs

/**
 * The trip panel itself: a lean custom View that draws directly on the screen
 * background (no card, no border, no frame), runs a Choreographer loop throttled
 * to <=30 FPS, attaches to the process-scoped [TripSession] hub, and draws the
 * [TripPanelRenderer] strip. Relaunching the activity re-attaches to the same
 * trip — the view never owns or resets the engine.
 *
 * Inputs and rendering stop when the panel is detached or the activity is
 * paused. The draw path preallocates all Paint state.
 *
 * ### The one thing it answers
 *
 * A horizontal swipe over the **field** moves between the two pages, and the dots under the field
 * say there are two. Everything else about this view is still untouchable: a vertical drag belongs
 * to whatever scrolls above it, and a tap does nothing — the strip is not a button.
 *
 * The swipe is taken on the field alone rather than on the whole strip. The three trip figures on
 * the right are not a page, and a gesture that started on them would be a promise this view does
 * not keep.
 *
 * ### And what the second page costs
 *
 * [VehicleWatcher.STRIP] claims the vehicle hub while, and only while, the car's page is actually
 * on screen — visible, resumed, and chosen. That claim is a shell poll four times a second, so it
 * is not one to hold for a page nobody is looking at; the cluster's own claim is independent, and
 * either may be up without the other.
 */
@SuppressLint("ViewConstructor")
class TripPanelView(context: Context) : View(context), Choreographer.FrameCallback {

    private val hub = TripSession.hub(context)
    private val vehicle = VehicleSession.hub(context)
    private val renderer = TripPanelRenderer()

    /**
     * Which page is up, and it is remembered between runs by [StripPageSettings].
     *
     * Read once here rather than on every frame: a preferences lookup in a draw path is a file
     * read thirty times a second for an answer that changes when a finger moves.
     */
    private var page: StripPage = StripPageSettings.page(context)
        set(value) {
            if (field == value) return
            field = value
            StripPageSettings.setPage(context, value)
            syncVehicle()
            invalidate()
        }

    /**
     * The swipe, and the two ways a finger makes one.
     *
     * A flick is what most people do and [GestureDetector] measures it; a slow deliberate drag is
     * what the rest do, and it never reaches a fling. The page has no animation to follow a finger
     * with - it swaps - so both gestures are decided at their end, and [turned] keeps one touch
     * from being counted twice when a drag is also fast enough to fling.
     */
    private var downX = 0f
    private var downY = 0f
    private var paging = false
    private var turned = false

    private val swipe = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = paging

        override fun onFling(
            down: MotionEvent?,
            up: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean = turn(up.x - downX, up.y - downY)
    })

    private val swipeSlop = ViewConfiguration.get(context).scaledPagingTouchSlop.toFloat()

    /**
     * A gesture along the strip turns the page; one across it belongs to whatever scrolls above.
     *
     * The dashboard's own column scrolls vertically when the window is short, and a diagonal drag
     * that starts here would otherwise be taken by it halfway through - which is what
     * `requestDisallowInterceptTouchEvent` is for, asked for only once the drag has proved itself
     * horizontal.
     */
    private fun turn(dx: Float, dy: Float): Boolean {
        if (!paging || turned || abs(dx) < swipeSlop || abs(dx) <= abs(dy)) return false
        turned = true
        page = page.next(forward = dx < 0)
        return true
    }

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
            syncVehicle()
        }

        override fun onPause(owner: LifecycleOwner) {
            resumed = false
            syncLoop()
            syncVehicle()
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
        syncVehicle()
    }

    override fun onDetachedFromWindow() {
        findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycleObserver)
        attached = false
        stopLoop()
        syncVehicle()
        hub.stop()
        super.onDetachedFromWindow()
    }

    private fun syncLoop() {
        if (attached && resumed) startLoop() else stopLoop()
    }

    /** The car is polled while its page is on screen, and not one moment longer. */
    private fun syncVehicle() {
        vehicle.setActive(VehicleWatcher.STRIP, attached && resumed && page == StripPage.VEHICLE)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                paging = overField(event.x)
                turned = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (paging && !turned && abs(dx) >= swipeSlop && abs(dx) > abs(event.y - downY)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_UP -> turn(event.x - downX, event.y - downY)
        }
        swipe.onTouchEvent(event)
        return paging
    }

    /**
     * Whether a gesture started over the field, which is the only part of this strip that pages.
     *
     * The field is the analyser's own share of the width, which the renderer states for each of
     * the three window widths; the rest is the trip's figures.
     */
    private fun overField(x: Float): Boolean =
        width > 0 && x <= width * renderer.fieldFraction(layout)

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
            page = page,
            vehicle = vehicle.snapshot,
        )
    }

    private companion object {
        const val MIN_FRAME_NS = 1_000_000_000L / 30L
    }
}
