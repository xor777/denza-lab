package dev.denza.apps.feature.hud

import java.util.concurrent.atomic.AtomicLong

/** Where the map window stands. */
enum class HudMapState {
    /** The switch is off, or the hints are. */
    OFF,

    /** On, and no Yandex route is held: the HUD shows no map window without one. */
    WAITING_ROUTE,

    /** A route is held and Yandex is not on the source's screen: minimised, closed or elsewhere. */
    NOT_ON_SCREEN,

    /** Yandex is on the screen and another window lies over the cut. */
    COVERED,

    /** Frames are going to the map window. */
    SENDING,

    /** The source cannot capture at all - the service lacks the screenshot capability. */
    NO_CAPABILITY,
}

/** What «Сервис» shows of the map window. No frame and no place is kept here. */
data class HudMapSnapshot(
    val state: HudMapState = HudMapState.OFF,
    val source: String = "",
    val framesSent: Long = 0,
    /** Frames the source asked for and did not get, by the source's own failure code. */
    val captureFailures: Map<Int, Long> = emptyMap(),
    /** Frames the pipeline threw on. */
    val processingFailures: Long = 0,
    /** Milliseconds the pipeline spent on the last frame, and its running mean. */
    val lastFrameMs: Long? = null,
    val meanFrameMs: Long? = null,
    val anchor: HudMapAnchorSource? = null,
    val coveredBy: String? = null,
    val lastFireResult: Int? = null,
)

/**
 * Process-local state of the map window for the support report. Written from the frame thread and
 * the main thread: every field is volatile or atomic, and what changes together - the state and
 * the window over the cut, the frame times, the failure counts - is written and read under one lock.
 */
object HudMapRuntime {
    private val framesSent = AtomicLong()
    private val processingFailures = AtomicLong()
    private val captureFailures = LinkedHashMap<Int, Long>()

    @Volatile
    private var state = HudMapState.OFF

    @Volatile
    private var source = ""

    @Volatile
    private var lastFrameMs: Long? = null

    @Volatile
    private var meanFrameMs: Double? = null

    @Volatile
    private var anchor: HudMapAnchorSource? = null

    @Volatile
    private var coveredBy: String? = null

    @Volatile
    private var lastFireResult: Int? = null

    /**
     * The map window is now in [next]; [cover] names the window over the cut when it is
     * [HudMapState.COVERED]. True when the state changed, which is when the caller logs it.
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun onState(next: HudMapState, cover: String? = null): Boolean {
        val changed = next != state || (next == HudMapState.COVERED && cover != coveredBy)
        state = next
        coveredBy = if (next == HudMapState.COVERED) cover else null
        return changed
    }

    @JvmStatic
    fun state(): HudMapState = state

    @JvmStatic
    fun onSource(name: String) {
        source = name
    }

    /**
     * A frame made in [ms], its cursor from [anchorSource]. A [warmingUp] frame - the first few of
     * a run, which pay for the renderer and the compiler - stays out of the mean: on the emulator
     * the first one took 0.5-0.9 s against 20-30 ms after.
     */
    @JvmStatic
    @JvmOverloads
    @Synchronized
    fun onFrame(ms: Long, anchorSource: HudMapAnchorSource, warmingUp: Boolean = false) {
        lastFrameMs = ms
        anchor = anchorSource
        if (warmingUp) return
        val mean = meanFrameMs
        meanFrameMs = if (mean == null) ms.toDouble() else mean + (ms - mean) * MEAN_WEIGHT
    }

    /** The stock service's answer to a frame of the map window. */
    @JvmStatic
    fun onFireResult(result: Int) {
        lastFireResult = result
        if (result == 0) framesSent.incrementAndGet()
    }

    @JvmStatic
    @Synchronized
    fun onCaptureFailure(code: Int) {
        captureFailures[code] = (captureFailures[code] ?: 0L) + 1
    }

    @JvmStatic
    fun onProcessingFailure() {
        processingFailures.incrementAndGet()
    }

    @Synchronized
    fun snapshot(): HudMapSnapshot = HudMapSnapshot(
        state = state,
        source = source,
        framesSent = framesSent.get(),
        captureFailures = captureFailures.toMap(),
        processingFailures = processingFailures.get(),
        lastFrameMs = lastFrameMs,
        meanFrameMs = meanFrameMs?.let { Math.round(it) },
        anchor = anchor,
        coveredBy = coveredBy,
        lastFireResult = lastFireResult,
    )

    @Synchronized
    internal fun resetForTest() {
        framesSent.set(0)
        processingFailures.set(0)
        captureFailures.clear()
        state = HudMapState.OFF
        source = ""
        lastFrameMs = null
        meanFrameMs = null
        anchor = null
        coveredBy = null
        lastFireResult = null
    }

    /** How much one frame moves the running mean: about the last twenty frames. */
    private const val MEAN_WEIGHT = 0.05
}
