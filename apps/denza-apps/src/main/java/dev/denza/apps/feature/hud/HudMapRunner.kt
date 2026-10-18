package dev.denza.apps.feature.hud

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.denza.hudlink.HudScene
import java.io.File

/**
 * The map window's loop: a [source]'s pictures through the [HudMapPipeline] to the HUD, while a
 * Yandex route is held and the switch is on.
 *
 * The hints decide when it runs ([HudGuidanceAccessibilityMonitor] calls [run], [pause] and
 * [stop] from its poll), because the HUD shows its map window only beside a road packet that says
 * a route is on, and the hints are what send it. The pictures are made on the runner's own thread,
 * `denza-hud-map`; each goes through [publisher] on the main thread, where the compositor lives
 * ([HudCaptureSource]).
 *
 * The frame is taken away ([Publisher.clear]) whenever the window stops being fed: on every stop
 * of the loop, and while it runs on the first frame Yandex is gone from the screen or hidden under
 * another window; the compositor then empties the window with black frames. A frame still on its
 * way when the loop stops is dropped on the main thread, so it can never land after the clear.
 *
 * While frames are going, the last one is published again every [KEEP_ALIVE_MS] that no new one
 * came: a mirrored screen that does not change delivers no frames, and a frame not published again
 * dies after [HudScene.LAYER_TTL_MS]. The same picture renews the frame without sending it; the
 * session repeats it on the HUD once a second, as the stock navigator repeats its own.
 *
 * Main-thread calls; [shutdown] ends the thread, and the runner is not used after it.
 */
internal class HudMapRunner(
    private val source: HudMapFrameSource,
    private val publisher: Publisher,
    captureDirectory: File,
) {
    /** The capture source's two calls for the map window, made on the main thread. */
    interface Publisher {
        /** A frame as a PNG; the same array again keeps it on the glass. */
        fun publish(png: ByteArray)

        /** No frame now: the window is emptied. */
        fun clear()
    }

    private val main = Handler(Looper.getMainLooper())
    private val keepAlive = Runnable { renew() }

    /** The frame on the glass, for [renew]; main thread. */
    private var lastPng: ByteArray? = null
    private val capture = HudMapCapture(captureDirectory, SystemClock::uptimeMillis)
    private var thread: HandlerThread? = null
    private var worker: Handler? = null
    private var active = false

    /** Raised on every stop; a frame made under an older one is dropped. Read on both threads. */
    @Volatile
    private var generation = 0

    // The frame thread's own state.
    private var pipeline: HudMapPipeline? = null
    private var showing = false
    private var failureLogged = false
    private var framesThisRun = 0
    private val failures = HudMapFailureClock()

    private val sink = object : HudMapFrameSink {
        override fun onFrame(frame: HudMapFrame) = onPicture(frame)

        override fun onGap(state: HudMapState) = onHidden(state, null)
    }

    init {
        HudMapRuntime.onSource("${source.name}, ${source.frameIntervalMs} мс")
    }

    /** A route is held: the map runs if [enabled]. */
    fun run(enabled: Boolean) {
        if (!enabled) {
            stop()
            return
        }
        if (active) return
        active = true
        // The state follows the source's first answer: a frame, Yandex hidden, or no capture.
        val made = generation
        val worker = worker ?: HandlerThread(THREAD).let { started ->
            started.start()
            thread = started
            Handler(started.looper).also { this.worker = it }
        }
        worker.post {
            if (made != generation) return@post
            showing = false
            failureLogged = false
            framesThisRun = 0
            failures.succeeded()
            HudMapRuntime.onRunStarted()
            (pipeline ?: HudMapPipeline(capture).also { pipeline = it }).reset()
            source.start(worker, sink)
        }
    }

    /** No route: the map waits for one if [enabled], and is off otherwise. */
    fun pause(enabled: Boolean) = halt(if (enabled) HudMapState.WAITING_ROUTE else HudMapState.OFF)

    /** The switch or the hints went off, or guidance stopped. */
    fun stop() = halt(HudMapState.OFF)

    /** [stop], and the thread ends; the GPU buffers go with it. */
    fun shutdown() {
        stop()
        val worker = worker ?: return
        val thread = thread
        this.worker = null
        this.thread = null
        worker.post {
            pipeline?.close()
            pipeline = null
            thread?.quitSafely()
        }
    }

    private fun halt(state: HudMapState) {
        if (!active) {
            setState(state)
            return
        }
        active = false
        generation++
        worker?.post {
            source.stop()
            showing = false
            pipeline?.close()
            pipeline = null
        }
        forgetLast()
        publisher.clear()
        setState(state)
    }

    /** Frame thread. */
    private fun onPicture(frame: HudMapFrame) {
        val made = generation
        val pipeline = pipeline ?: return
        val started = SystemClock.uptimeMillis()
        val result = try {
            pipeline.process(frame, started)
        } catch (error: RuntimeException) {
            HudMapRuntime.onProcessingFailure()
            // Once per run, never per frame: the car's log ring is seconds long.
            if (!failureLogged) {
                failureLogged = true
                Log.w(TAG, "frame failed", error)
            }
            // The last good frame stays while failures are brief; once they last, the window is
            // emptied, or the HUD would go on repeating a map that no longer moves.
            if (made == generation && failures.failed(started)) onHidden(HudMapState.FAILING, null)
            return
        }
        failures.succeeded()
        // Stopped while the frame was made: the stop has said what the state is.
        if (made != generation) return
        when (result) {
            is HudMapResult.Covered -> onHidden(HudMapState.COVERED, result.by)
            is HudMapResult.Frame -> {
                HudMapRuntime.onFrame(
                    SystemClock.uptimeMillis() - started,
                    result.anchor,
                    startedAtMs = started,
                    warmingUp = ++framesThisRun <= WARM_UP_FRAMES,
                )
                showing = true
                setState(HudMapState.SENDING)
                main.post {
                    if (made == generation && active) send(result.png)
                }
            }
        }
    }

    /** Frame thread: no picture now. The window is emptied once, as it stops being fed. */
    private fun onHidden(state: HudMapState, by: HudMapWindow?) {
        val made = generation
        if (pipeline == null) return
        if (showing) {
            showing = false
            main.post {
                if (made == generation && active) {
                    forgetLast()
                    publisher.clear()
                }
            }
        }
        setState(state, by)
    }

    /** Main thread: a new frame, and the clock for its renewal starts again. */
    private fun send(png: ByteArray) {
        lastPng = png
        publisher.publish(png)
        main.removeCallbacks(keepAlive)
        main.postDelayed(keepAlive, KEEP_ALIVE_MS)
    }

    private fun renew() {
        val png = lastPng ?: return
        if (!active) return
        send(png)
    }

    private fun forgetLast() {
        lastPng = null
        main.removeCallbacks(keepAlive)
    }

    /** One log line per change of state, from either thread; never one per frame. */
    private fun setState(state: HudMapState, by: HudMapWindow? = null) {
        val cover = by?.describe()
        if (HudMapRuntime.onState(state, cover)) {
            Log.i(TAG, if (cover == null) "state ${state.name}" else "state ${state.name} under $cover")
        }
    }

    private companion object {
        const val TAG = "DenzaHudMap"
        const val THREAD = "denza-hud-map"

        /** Frames of a run kept out of the mean frame time ([HudMapRuntime.onFrame]). */
        const val WARM_UP_FRAMES = 3

        /** Inside the frame's lifetime, [HudScene.LAYER_TTL_MS], with room for a busy main thread. */
        const val KEEP_ALIVE_MS = 1_000L
    }
}

/**
 * How long frames may fail before the map window stops showing the last good one: a failure now and
 * then is covered by the repeat, failures that go on are not. Frame thread.
 */
internal class HudMapFailureClock(private val blankAfterMs: Long = BLANK_AFTER_MS) {
    private var since = NONE

    /** A frame failed at [nowMs]; true once failures have lasted [blankAfterMs] with no good frame. */
    fun failed(nowMs: Long): Boolean {
        if (since == NONE) since = nowMs
        return nowMs - since >= blankAfterMs
    }

    /** A frame was made: the next failure starts the clock again. */
    fun succeeded() {
        since = NONE
    }

    companion object {
        const val BLANK_AFTER_MS = 1_000L
        private const val NONE = Long.MIN_VALUE
    }
}
