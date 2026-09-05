package dev.denza.apps.feature.cluster.dashboard

/**
 * The panel's clock: which vsync is worth drawing, and how long to sleep before asking again.
 *
 * It is out of [ClusterDashboardView] for the reason [ContourScene] and [ContourMotion] are - a
 * `Choreographer` callback is unreachable from a JVM test - and it decides two things that used to
 * be decided inside one:
 *
 * **A sweep is not consumed by a frame that refused to draw it.** The arrival was read, and
 * `lastSnapshot` written, *above* the budget test, so a snapshot first seen on a vsync the budget
 * turned down was marked as seen and then never handed to [ContourScene.frame]: its ages were never
 * reset and it was lost rather than deferred. At 30 fps against a 60 Hz vsync that is every other
 * frame, and the panel's whole staleness rule hangs off those ages. The arrival is latched here
 * until a frame actually takes it.
 *
 * **The slow lane sleeps.** [SLOW_FPS] used to save the draw and nothing else: the callback was
 * re-posted at the top of every vsync whatever the budget was, so a parked car woke sixty times a
 * second to decline fifty-five of them. [sleepNs] is what is left of the current budget, and the
 * view posts the next callback that far ahead.
 */
internal class ContourPace {

    private var started = false
    private var pending = false
    private var lastFrameNs = 0L
    private var lastDrawNs = 0L

    /** Whether the frame just admitted carries a sweep the scene has not been shown yet. */
    var arrived = false
        private set

    /** How much time that frame advances the followers by, clamped. */
    var dt = 0f
        private set

    /** How long to wait before asking again, in nanoseconds. Zero means the next vsync. */
    var sleepNs = 0L
        private set

    /** Forget the frame before this one, which is what starting the loop again means. */
    fun restart() {
        started = false
        pending = false
        lastFrameNs = 0L
        lastDrawNs = 0L
        arrived = false
        dt = 0f
        sleepNs = 0L
    }

    /**
     * A snapshot the panel has not drawn yet.
     *
     * Latched rather than passed to [frame], because the frame that hears about it is not
     * necessarily the frame that draws it.
     */
    fun sweepArrived() {
        pending = true
    }

    /**
     * One vsync.
     *
     * @param moving whether anything on the panel is still travelling toward a reading
     * @return whether this vsync is worth drawing. Either way [sleepNs] says when to ask again.
     */
    fun frame(frameTimeNanos: Long, moving: Boolean): Boolean {
        val budget = if (pending || moving) FAST_FRAME_NS else SLOW_FRAME_NS
        val waited = if (started) frameTimeNanos - lastDrawNs else budget
        if (waited < budget) {
            sleepNs = budget - waited
            return false
        }
        dt = if (started) {
            ((frameTimeNanos - lastFrameNs) / NANOS_PER_SECOND).toFloat().coerceIn(0f, MAX_STEP_S)
        } else {
            0f
        }
        started = true
        lastFrameNs = frameTimeNanos
        lastDrawNs = frameTimeNanos
        arrived = pending
        pending = false
        sleepNs = budget
        return true
    }

    companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val NANOS_PER_MILLI = 1_000_000L

        /** While anything is moving or a reading has just landed. */
        const val FAST_FPS = 30L
        const val FAST_FRAME_NS = 1_000_000_000L / FAST_FPS

        /** And while nothing is, which on a parked car is most of the time. */
        const val SLOW_FPS = 5L
        const val SLOW_FRAME_NS = 1_000_000_000L / SLOW_FPS

        /** A stall must not hand a follower a second of travel in one step. */
        const val MAX_STEP_S = 0.25f
    }
}
