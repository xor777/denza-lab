package dev.denza.apps.feature.trip

/**
 * When [SpectrumSource] tries to attach its effect, and when it leaves it alone. Main thread.
 *
 * The effect is `Visualizer(0)`, constructed on the main thread, and without `RECORD_AUDIO` the
 * construction fails (error -3). The watchdog that keeps a capture alive used to try it every two
 * seconds whatever the grant said, for as long as the strip was open: a fresh install, or a grant
 * the ADB heal could not make, was an endless main-thread retry and a failure in the log each time.
 * So the rules are three, and this class is all of them, with the effect behind four calls:
 *
 *  - **No permission, no attempt.** Nothing is constructed and nothing is scheduled. The grant comes
 *    back through [granted] - the ADB heal's result - or through the next [start], which is the
 *    panel's resume; either attaches at once, with no app restart.
 *  - **With the permission, a failure backs off**: four seconds, then eight, sixteen, thirty-two,
 *    and a minute from then on. A busy audio server or a client still releasing session 0 passes
 *    (`docs/audio-capture-findings.md`, «Attaching can fail transiently»); one that does not pass
 *    costs one construction a minute.
 *  - **An attached capture that goes quiet is rebuilt**, as before, on the watchdog's own two
 *    seconds.
 *
 * Meanwhile the analyser draws its idle state: the source hands it no frame.
 */
internal class SpectrumKeeper(
    private val permitted: () -> Boolean,
    /** Construct and enable the effect; whether it is attached now. */
    private val attach: () -> Boolean,
    private val release: () -> Unit,
    private val attached: () -> Boolean,
    /** How long since the attached effect last delivered a frame. */
    private val sinceLastFrameMs: () -> Long,
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
) {
    /** Whether anybody wants the spectrum. */
    var running: Boolean = false
        private set

    /** Attempts that failed in a row, which is what the next one waits for. */
    private var failures = 0

    private val watch = Runnable { tick() }

    /** The first owner arrived: attach now if the grant allows, and keep the capture alive. */
    fun start() {
        if (running) return
        running = true
        failures = 0
        resume()
    }

    /** The grant has landed while somebody is waiting: attach now. */
    fun granted() {
        if (!running || attached()) return
        failures = 0
        resume()
    }

    /** The last owner left: nothing scheduled, the effect released. */
    fun stop() {
        if (!running) return
        running = false
        unschedule(watch)
        release()
        failures = 0
    }

    private fun resume() {
        unschedule(watch)
        if (!permitted()) return
        if (!attached()) attempt()
        schedule(watch, delayMs())
    }

    private fun tick() {
        if (!running) return
        if (!permitted()) {
            // Revoked under us (the platform normally kills the process first): stop trying until
            // a grant or a resume says otherwise.
            release()
            return
        }
        if (!attached()) {
            attempt()
        } else if (sinceLastFrameMs() > REVIVE_AFTER_MS) {
            release()
            attempt()
        }
        schedule(watch, delayMs())
    }

    private fun attempt() {
        failures = if (attach()) 0 else failures + 1
    }

    /** The watchdog's interval while attached, and the backoff after [failures] failed attempts. */
    fun delayMs(): Long =
        if (failures == 0) WATCHDOG_INTERVAL_MS
        else minOf(WATCHDOG_INTERVAL_MS shl minOf(failures, MAX_DOUBLINGS), MAX_BACKOFF_MS)

    companion object {
        const val WATCHDOG_INTERVAL_MS = 2_000L
        const val REVIVE_AFTER_MS = 4_000L

        /** The longest a failing construction waits for the next one. */
        const val MAX_BACKOFF_MS = 60_000L

        /** Enough doublings of two seconds to pass [MAX_BACKOFF_MS], and no more. */
        private const val MAX_DOUBLINGS = 5
    }
}
