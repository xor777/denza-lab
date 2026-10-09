package dev.denza.apps.feature.hud

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * The map window's loop: a [source]'s pictures through the [HudMapPipeline] to the HUD, while a
 * Yandex route is held and the switch is on.
 *
 * The hints decide when it runs ([HudGuidanceAccessibilityMonitor] calls [run], [pause] and
 * [stop] from its poll), because the HUD shows its map window only beside a road packet that says
 * a route is on, and the hints are what send it. The pictures are made on the runner's own thread,
 * `denza-hud-map`; what goes to the HUD goes through [publisher] on the main thread, where the
 * SOME/IP session lives.
 *
 * The map window is emptied with a black frame ([Publisher.blank]) whenever it stops being fed: on
 * every stop of the loop, and while it runs on the first frame Yandex is gone from the screen or
 * hidden under another window. A frame still on its way when the loop stops is dropped on the main
 * thread, so it can never land after the black one.
 *
 * While frames are going, the last one is sent again every [REPEAT_MS] that no new one came, as the
 * stock navigator repeats its last frame once a second: a mirrored screen that does not change
 * delivers no frames, and the HUD is not left waiting on a picture that is simply still.
 *
 * Main-thread calls; [shutdown] ends the thread, and the runner is not used after it.
 */
internal class HudMapRunner(
    private val source: HudMapFrameSource,
    private val publisher: Publisher,
    captureDirectory: File,
) {
    /** The SOME/IP session's two calls for the map window, made on the main thread. */
    interface Publisher {
        fun publish(payload: ByteArray)

        fun blank()
    }

    private val main = Handler(Looper.getMainLooper())
    private val repeat = Runnable { repeatLast() }

    /** The frame on the glass, for [repeatLast]; main thread. */
    private var lastPayload: ByteArray? = null
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
        publisher.blank()
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
            return
        }
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
                    if (made == generation && active) send(result.payload)
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
                    publisher.blank()
                }
            }
        }
        setState(state, by)
    }

    /** Main thread: a new frame, and the clock for its repeat starts again. */
    private fun send(payload: ByteArray) {
        lastPayload = payload
        publisher.publish(payload)
        main.removeCallbacks(repeat)
        main.postDelayed(repeat, REPEAT_MS)
    }

    private fun repeatLast() {
        val payload = lastPayload ?: return
        if (!active) return
        send(payload)
    }

    private fun forgetLast() {
        lastPayload = null
        main.removeCallbacks(repeat)
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

        /** The stock navigator's own repeat of its last frame. */
        const val REPEAT_MS = 1_000L
    }
}
