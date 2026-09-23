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
 * The strip itself: a lean custom View that draws directly on the screen background (no card, no
 * border, no frame), runs a Choreographer loop throttled to <=30 FPS, attaches to the
 * process-scoped [TripSession] hub, and draws the [TripPanelRenderer] strip. Relaunching the
 * activity re-attaches to the same trip — the view never owns or resets the engine.
 *
 * Inputs and rendering stop when the panel is detached or the activity is paused. The draw path
 * preallocates all Paint state.
 *
 * **The view is laid over the strip box** the Luminofor spec names for its window width
 * (`LuminoforSpec.Head.*.STRIP_BOX`), and draws in that box's own dp at the display's density -
 * with [overhang] to spare on the left, the right and the foot, because the board draws a few
 * things past the box's edge: the chart's newest point sits on the box's right edge with its glow
 * around it, the analyser's outer columns glow three dp past their own sides, and its haze is a
 * little wider than the field. A view exactly the box's size would cut the dot in half.
 *
 * ### The one thing it answers
 *
 * A horizontal swipe anywhere on the strip moves between the two pages, and the dots at its foot
 * say there are two. A vertical drag belongs to whatever scrolls above it, and a tap does nothing -
 * the strip is not a button.
 *
 * The swipe used to be taken on the analyser's field alone, because the trip's figures beside it
 * stayed put on both pages. Since the Luminofor strip each page is the whole strip - the car's page
 * has no trip readings on it - so the whole strip is what a finger turns.
 *
 * ### And what the second page costs
 *
 * [VehicleWatcher.STRIP] claims the vehicle hub while, and only while, the car's page is actually
 * on screen — visible, resumed, and chosen. That claim is a shell poll four times a second, so it
 * is not one to hold for a page nobody is looking at; the cluster's own claim is independent, and
 * either may be up without the other.
 *
 * ### A fixed scene
 *
 * [fixture] draws a [StripModel] instead of the live strip: the debug build sets one from the
 * Luminofor board's fixtures, so a screenshot of the app can be laid over the board's PNG. While a
 * fixture is up the view reads no source, claims no hub and runs no loop; [page] is not remembered.
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
    var page: StripPage = StripPageSettings.page(context)
        set(value) {
            if (field == value) return
            field = value
            if (fixture == null) StripPageSettings.setPage(context, value)
            syncVehicle()
            invalidate()
        }

    /**
     * A still scene to draw in place of the live strip, or null for the live one.
     *
     * Setting one stops the loop and releases the hubs; clearing it takes them back if the view is
     * up. The model is drawn as it is, every time the view draws - it is the caller's to change.
     */
    var fixture: StripModel? = null
        set(value) {
            if (field === value) return
            field = value
            syncLive()
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
    private var turned = false

    private val swipe = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true

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
        if (turned || abs(dx) < swipeSlop || abs(dx) <= abs(dy)) return false
        turned = true
        page = page.next(forward = dx < 0)
        return true
    }

    private var looping = false
    private var attached = false
    private var resumed = false
    private var hubHeld = false
    private var lastDrawNs = 0L
    private var lastFrameNs = 0L

    var layout: TripPanelLayout = TripPanelLayout.WIDE
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
            invalidate()
        }

    /**
     * How far, in pixels, this view reaches past the strip box on its left, its right and its foot.
     *
     * `SpectrumPanel` lays the view [OVERHANG_DP] larger than the box it is handed on those three
     * sides and sets this to match, so the box itself stays where the dashboard put it and the few
     * things the board draws past its edge are drawn rather than cut. Zero - the view is the box -
     * for a host that places the view itself.
     */
    var overhang: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onResume(owner: LifecycleOwner) {
            resumed = true
            syncLive()
        }

        override fun onPause(owner: LifecycleOwner) {
            resumed = false
            syncLive()
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
        syncLive()
    }

    override fun onDetachedFromWindow() {
        findViewTreeLifecycleOwner()?.lifecycle?.removeObserver(lifecycleObserver)
        attached = false
        syncLive()
        super.onDetachedFromWindow()
    }

    /** The hub, the loop and the car's claim, each held exactly while the live strip is on screen. */
    private fun syncLive() {
        val live = attached && resumed && fixture == null
        if (live && !hubHeld) {
            hub.start(context)
            hubHeld = true
        } else if (!live && hubHeld) {
            hub.stop()
            hubHeld = false
        }
        if (live) startLoop() else stopLoop()
        syncVehicle()
    }

    /** The car is polled while its page is on screen, and not one moment longer. */
    private fun syncVehicle() {
        vehicle.setActive(
            VehicleWatcher.STRIP,
            attached && resumed && fixture == null && page == StripPage.VEHICLE,
        )
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                turned = false
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                if (!turned && abs(dx) >= swipeSlop && abs(dx) > abs(event.y - downY)) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }

            MotionEvent.ACTION_UP -> turn(event.x - downX, event.y - downY)
        }
        swipe.onTouchEvent(event)
        return true
    }

    private fun startLoop() {
        if (looping) return
        looping = true
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
        // The box is the view less its overhang, and the canvas's origin is moved onto the box.
        val boxW = width - 2f * overhang
        val boxH = height - overhang
        if (boxW <= 0f || boxH <= 0f) return
        val density = resources.displayMetrics.density
        val save = canvas.save()
        // The board's box stands on fractions of a pixel - 124.7 dp is 249.4 px on a pane - and a
        // view on whole ones, so every horizontal line would land up to half a pixel off the
        // board's. Where the view stands on its box to within that rounding, the canvas takes the
        // missing fraction; a view laid somewhere else on purpose is left where it is.
        getLocationInWindow(location)
        val box = TripPanelRenderer.box(layout)
        canvas.translate(overhang + fraction(box.left * density - (location[0] + overhang)), fraction(box.top * density - location[1]))
        val still = fixture
        if (still != null) {
            renderer.drawModel(canvas, boxW, boxH, density, layout, page, still)
        } else {
            val now = System.nanoTime()
            val dt = if (lastFrameNs == 0L) 1.0 / 30.0 else (now - lastFrameNs) / 1_000_000_000.0
            lastFrameNs = now
            renderer.draw(
                canvas, boxW, boxH, density,
                hub.engine, hub.spectrum, hub.nowPlaying,
                dt,
                showLocationHint = !hub.locationGranted,
                layout = layout,
                page = page,
                vehicle = vehicle.snapshot,
            )
        }
        canvas.restoreToCount(save)
    }

    private val location = IntArray(2)

    /** A rounding remainder, or nothing when the difference is more than a pixel's rounding. */
    private fun fraction(d: Float): Float = if (kotlin.math.abs(d) < 1f) d else 0f

    companion object {
        private const val MIN_FRAME_NS = 1_000_000_000L / 30L

        /**
         * What the view reaches past the strip box, in dp: the chart's end dot (three) and its glow
         * (eight), which is the widest of the board's overhangs. Every composition's page margin is
         * at least this wide (12 dp on the one-third pane), so the overhang lands on the margin.
         */
        const val OVERHANG_DP = 12f
    }
}
