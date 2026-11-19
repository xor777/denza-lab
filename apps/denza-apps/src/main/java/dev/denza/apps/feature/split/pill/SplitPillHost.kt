package dev.denza.apps.feature.split.pill

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Region
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import dev.denza.apps.feature.split.SplitLiveScene
import dev.denza.apps.feature.split.SplitPair
import dev.denza.apps.feature.split.SplitPane
import dev.denza.apps.feature.split.SplitPickerCatalog
import dev.denza.apps.feature.split.SplitPillOpenRequest
import dev.denza.apps.feature.split.SplitScreenCoordinator
import dev.denza.apps.feature.split.SplitSettled
import dev.denza.apps.feature.split.SplitState
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The pill's window, owned by the split's own accessibility service (decision 1): one
 * `TYPE_ACCESSIBILITY_OVERLAY` window of fixed size and place ([PillGeometry.window]), never
 * focusable, taking touches only inside the region the view sets (decision 2). It is added when a
 * product two-pane scene first comes on the screen and removed when the scene has gone and the
 * pill has faded, when the toggle's off is asked, or with the service.
 *
 * It reads three things and decides nothing: the automaton's settled readings
 * ([SplitScreenCoordinator.observeSettled], whole and numbered - an older one than it has is
 * dropped), the divider's place from the window list ([PillDivider], read off the main thread, at
 * most once a frame) and the launch catalogue's order. [SplitPillModel] turns them into what the
 * view shows; every tap goes back to the coordinator as an operation, which resolves the app itself
 * - nothing here looks a package up on the main thread.
 *
 * Main thread only, apart from the catalogue and window-list reads.
 */
internal class SplitPillHost(private val service: AccessibilityService) : SplitPillActions {

    private val main = Handler(Looper.getMainLooper())
    private val wm = service.getSystemService(WindowManager::class.java)
    private val screen: Rect = wm.maximumWindowMetrics.bounds
    private val scale = screen.width().toDouble() / SplitPillSpec.Stage.W
    private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "split-pill-catalogue") }
    private val windowReader = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "split-pill-windows") }

    private var view: SplitPillView? = null
    private var params: WindowManager.LayoutParams? = null
    private var bands: List<IntArray> = emptyList()
    private val region = Region()
    private val rect = Rect()

    /** Touch is let in only once the region is in place: never the whole window (decision 2). */
    private var touchable = false
    private var enabling = false

    /** A step of letting touch in failed: the window is gone and stays gone until the service restarts. */
    private var failedClosed = false

    private var state: SplitState? = null
    private var live: SplitLiveScene? = null
    private var revision = 0L
    private var withdrawn = false
    private var openSeen = 0L
    private var openPending: SplitPillOpenRequest? = null
    private var dividerX: Double? = null
    private var catalogue: List<String> = emptyList()

    @Volatile
    private var ownWindowId = -1
    private var stopped = false

    // The window list: asked for at most once a frame, read off main, the latest read wins.
    private val readQueued = AtomicBoolean(false)
    private var readsAsked = 0L
    private var readApplied = 0L

    fun start() {
        SplitScreenCoordinator.observeSettled(service) { settled -> main.post { accept(settled) } }
    }

    fun stop() {
        stopped = true
        SplitScreenCoordinator.observeSettled(service, null)
        main.removeCallbacksAndMessages(null)
        io.shutdownNow()
        windowReader.shutdownNow()
        removeWindow()
    }

    /**
     * One settled reading, the first one included, in revision order: an older one than what is
     * already held is dropped. Off asked withdraws the pill at once; a one-shot open request from
     * the dock's button is kept until the pill it opens has a place to stand.
     */
    private fun accept(settled: SplitSettled) {
        if (stopped || settled.revision <= revision) return
        revision = settled.revision
        state = settled.state
        live = settled.live
        withdrawn = settled.withdrawn
        settled.open?.let { request -> if (request.id > openSeen) openPending = request }
        if (withdrawn) openPending = null
        refresh()
        requestDivider()
        loadCatalogue()
    }

    /** A window change: the divider may have moved. Coalesced: one read for a frame's events. */
    fun onWindowsChanged() {
        if (stopped || state == null) return
        requestDivider()
    }

    /**
     * Whether a window event is the pill's own: its region changing reports a window change, and
     * that is no hint that the split's topology moved.
     */
    fun isOwnWindow(windowId: Int): Boolean = windowId != -1 && windowId == ownWindowId

    private fun describe(): SplitPillDescription? {
        if (withdrawn) return null
        val s = state ?: return null
        val l = live ?: return null
        return SplitPillModel.describe(s, l, dividerX, catalogue)
    }

    private fun refresh() {
        val description = describe()
        if (description != null && view == null && !failedClosed) addWindow()
        val current = view ?: return
        current.setDescription(description)
        val request = openPending
        if (description != null && request != null) {
            openPending = null
            openSeen = request.id
            current.requestOpen(if (request.afterHome) SplitPillSpec.Timer.SPLIT_OPEN else 0.0)
        } else if (description == null && dividerX != null) {
            // The scene this request was for is not on the screen: it is spent.
            openPending = null
        }
    }

    private fun requestDivider() {
        if (stopped || !readQueued.compareAndSet(false, true)) return
        Choreographer.getInstance().postFrameCallback {
            readQueued.set(false)
            if (stopped || windowReader.isShutdown) return@postFrameCallback
            val asked = ++readsAsked
            runCatching { windowReader.execute { readDivider(asked) } }
        }
    }

    /** On the reader thread: the window list, once, and its answer posted back to main. */
    private fun readDivider(asked: Long) {
        val bounds = Rect()
        var own = -1
        val boxes = runCatching { service.windows }.getOrDefault(emptyList()).map { window ->
            window.getBoundsInScreen(bounds)
            val title = window.title?.toString()
            if (title == WINDOW_TITLE) own = window.id
            PillDivider.Box(
                system = window.type == AccessibilityWindowInfo.TYPE_SYSTEM,
                application = window.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                title = title,
                l = bounds.left,
                t = bounds.top,
                r = bounds.right,
                b = bounds.bottom,
            )
        }
        val x = PillDivider.centrePx(boxes, screen.height())?.let { px -> px / scale }
        main.post {
            if (stopped || asked <= readApplied) return@post
            readApplied = asked
            if (own != -1) ownWindowId = own
            if (x != dividerX) {
                dividerX = x
                refresh()
            }
        }
    }

    /** The launcher catalogue in its own order, off the main thread; memoised by the catalogue cache. */
    private fun loadCatalogue() {
        if (io.isShutdown) return
        runCatching {
            io.execute {
                val apps = runCatching { SplitPickerCatalog.load(service).map { it.packageName }.distinct() }
                    .onFailure { error -> Log.w(TAG, "catalogue unavailable", error) }
                    .getOrNull() ?: return@execute
                main.post {
                    if (stopped || apps == catalogue) return@post
                    catalogue = apps
                    refresh()
                }
            }
        }
    }

    // region the window

    private fun addWindow() {
        val px = PillGeometry.toPx(PillGeometry.window, scale)
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            // Untouchable until the first region is in place: the full window must never take a touch.
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.LEFT
            x = px[0]
            y = px[1]
            width = px[2] - px[0]
            height = px[3] - px[1]
            windowAnimations = 0
            setFitInsetsTypes(0)
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            title = WINDOW_TITLE
        }
        noMoveAnimation(lp)
        val next = SplitPillView(service, scale, this)
        runCatching { wm.addView(next, lp) }
            .onFailure { error ->
                next.dispose()
                Log.w(TAG, "pill window not added", error)
                return
            }
        view = next
        params = lp
        touchable = false
        enabling = false
        bands = emptyList()
        Log.i(TAG, "pill window added px=${px.joinToString()} scale=$scale")
    }

    private fun removeWindow() {
        val current = view ?: return
        view = null
        params = null
        touchable = false
        enabling = false
        ownWindowId = -1
        current.dispose()
        runCatching { wm.removeViewImmediate(current) }
            .onFailure { error -> Log.w(TAG, "pill window not removed", error) }
        Log.i(TAG, "pill window removed")
    }

    /** Something went wrong while letting touch in: no half-touchable window is left on the screen. */
    private fun failClosed(step: String, error: Throwable) {
        Log.w(TAG, "pill window withdrawn: $step failed", error)
        failedClosed = true
        removeWindow()
    }

    override fun touchRegion(bands: List<IntArray>) {
        this.bands = bands
        applyRegion()
    }

    /**
     * The region goes in first; touch is let in only after a frame's traversal has carried it to
     * the window manager, and taken away again - flag and all - whenever the region empties (decision 2,
     * ревью пилюли Bn1). A failure at either step removes the window.
     */
    private fun applyRegion() {
        val current = view ?: return
        val root = current.rootSurfaceControl
        if (root == null) {
            current.post { applyRegion() }
            return
        }
        region.setEmpty()
        bands.forEach { band ->
            rect.set(band[0], band[1], band[2], band[3])
            region.op(rect, Region.Op.UNION)
        }
        try {
            root.setTouchableRegion(region)
        } catch (error: RuntimeException) {
            failClosed("region", error)
            return
        }
        if (bands.isEmpty()) {
            if (touchable) setTouchable(current, false)
            return
        }
        if (touchable || enabling) return
        enabling = true
        // Setting the region asks for a traversal, which sends it to the window manager. A frame
        // callback runs before that frame's traversal, so the second one runs after it: only then
        // is the flag cleared.
        val choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback {
            choreographer.postFrameCallback {
                if (view !== current || stopped) return@postFrameCallback
                enabling = false
                if (bands.isNotEmpty() && !touchable) setTouchable(current, true)
            }
        }
    }

    private fun setTouchable(current: SplitPillView, on: Boolean) {
        val lp = params ?: return
        lp.flags = if (on) {
            lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            wm.updateViewLayout(current, lp)
            touchable = on
        } catch (error: RuntimeException) {
            failClosed(if (on) "touch on" else "touch off", error)
        }
    }

    override fun gone() {
        if (describe() == null) removeWindow()
    }

    // endregion

    // region what a tap asks

    override fun choosePair(pair: SplitPair) = SplitScreenCoordinator.choosePair(service, pair)

    override fun selectIntoPane(pane: SplitPane, packageName: String) =
        SplitScreenCoordinator.selectIntoPane(service, pane, packageName)

    override fun setPinned(pair: SplitPair, pinned: Boolean) =
        SplitScreenCoordinator.setPairPinned(service, pair, pinned)

    // endregion

    private companion object {
        const val TAG = "DenzaSplitPill"
        const val WINDOW_TITLE = "denza-split-pill"

        /** `PRIVATE_FLAG_NO_MOVE_ANIMATION`: the window never animates a change of place. */
        const val PRIVATE_FLAG_NO_MOVE_ANIMATION = 0x40

        fun noMoveAnimation(lp: WindowManager.LayoutParams) {
            runCatching {
                val field = WindowManager.LayoutParams::class.java.getField("privateFlags")
                field.setInt(lp, field.getInt(lp) or PRIVATE_FLAG_NO_MOVE_ANIMATION)
            }
        }
    }
}
