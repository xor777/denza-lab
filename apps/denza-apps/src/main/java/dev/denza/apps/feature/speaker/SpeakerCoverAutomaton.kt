package dev.denza.apps.feature.speaker

/**
 * One serialized motor operation: where the covers should end up, nothing more.
 *
 * There used to be a third, `ESTABLISH_OPEN`, standing for close-then-open on a car whose cover
 * position was unknown - the amplifier property is edge-triggered, so a bare open cannot move a car
 * whose last written value is already open. That belonged here only by accident: the edge is a fact
 * about the protocol, not about where the covers are, and [SpeakerCoverMotor] now settles it from
 * the value it remembers writing. Asking for a position is all this ever meant.
 */
enum class SpeakerCoverMotorAction {
    OPEN,
    CLOSE,
}

data class SpeakerCoverMotorRequest(
    val action: SpeakerCoverMotorAction,
    val reason: String,
)

/**
 * Pure timing and command state for the cover automation.
 *
 * Missing samples never count as silence. A short real sound resets the close
 * timer, while only sustained sound can trigger the audio fallback open.
 */
class SpeakerCoverAutomaton(
    private val fallbackSoundMs: Long = FALLBACK_SOUND_MS,
    private val closeSilenceMs: Long = CLOSE_SILENCE_MS,
    private val maxConfirmedSampleGapMs: Long = MAX_CONFIRMED_SAMPLE_GAP_MS,
    private val retryGuardMs: Long = RETRY_GUARD_MS,
) {
    /**
     * The last thing this app asked the covers to do, or null before it has asked for anything.
     *
     * Deliberately not "where the covers are". There is no sensor, the amplifier lowers them on
     * its own, and a field named `position` was a claim the app had no way to make - it was only
     * ever set from our own commands, which is what this says out loud.
     */
    var raised: Boolean? = null
        private set

    var pendingAction: SpeakerCoverMotorAction? = null
        private set

    var confirmedSilenceMs: Long = 0L
        private set

    var consecutiveSoundMs: Long = 0L
        private set

    private var desiredOpen: Boolean? = null
    private var lastSampleAtMs: Long? = null
    private var lastSampleHadSignal = false
    private var askedAtMs = 0L
    private var askFailed = false
    private var desiredReason = ""

    fun onImmediateOpen(nowMs: Long, reason: String): SpeakerCoverMotorRequest? {
        desiredOpen = true
        desiredReason = reason
        // Opening a player is fresh user intent. Without resetting this, an app opened after the
        // previous 30-minute close would be opened and then closed again by the very next silent
        // FFT frame because the saturated silence counter was still retained.
        confirmedSilenceMs = 0L
        lastSampleAtMs = null
        lastSampleHadSignal = false
        consecutiveSoundMs = 0L
        return reconcile(nowMs)
    }

    /**
     * The driver moved the covers from the panel.
     *
     * A hand on a button outranks whatever the ear was concluding, so this becomes the wish - and
     * the sound counters restart, or lowering the covers during a track would be undone by the very
     * next frame of it. The three-second fallback can still raise them again afterwards; that is
     * the automation working, and the panel says so in as many words.
     */
    fun onManualPosition(open: Boolean, nowMs: Long): SpeakerCoverMotorRequest? {
        desiredOpen = open
        desiredReason = "вручную"
        confirmedSilenceMs = 0L
        consecutiveSoundMs = 0L
        lastSampleAtMs = null
        lastSampleHadSignal = false
        return reconcile(nowMs)
    }

    fun onAudioSample(
        capturedAtMs: Long,
        hasSignal: Boolean,
    ): SpeakerCoverMotorRequest? {
        val previousAt = lastSampleAtMs
        val delta = if (
            previousAt != null &&
            capturedAtMs > previousAt &&
            capturedAtMs - previousAt <= maxConfirmedSampleGapMs
        ) {
            capturedAtMs - previousAt
        } else {
            0L
        }

        if (hasSignal) {
            confirmedSilenceMs = 0L
            consecutiveSoundMs = if (lastSampleHadSignal) {
                consecutiveSoundMs + delta
            } else {
                0L
            }
            if (consecutiveSoundMs >= fallbackSoundMs) {
                desiredOpen = true
                desiredReason = "звук ${fallbackSoundMs / 1_000} с"
            }
        } else {
            consecutiveSoundMs = 0L
            if (delta > 0L) {
                confirmedSilenceMs = (confirmedSilenceMs + delta).coerceAtMost(closeSilenceMs)
            }
            if (confirmedSilenceMs >= closeSilenceMs) {
                desiredOpen = false
                desiredReason = "тишина ${closeSilenceMs / 60_000} мин"
            }
        }

        lastSampleAtMs = capturedAtMs
        lastSampleHadSignal = hasSignal
        return reconcile(capturedAtMs)
    }

    /** Breaks continuity without turning an unavailable detector into silence. */
    fun onCaptureUnavailable() {
        lastSampleAtMs = null
        lastSampleHadSignal = false
        consecutiveSoundMs = 0L
    }

    /** Lets a failed motor operation retry even while audio capture is unavailable. */
    fun onTick(nowMs: Long): SpeakerCoverMotorRequest? = reconcile(nowMs)

    fun onMotorResult(
        action: SpeakerCoverMotorAction,
        success: Boolean,
        nowMs: Long,
    ): SpeakerCoverMotorRequest? {
        if (pendingAction != action) return null
        pendingAction = null
        askFailed = !success
        return reconcile(nowMs)
    }

    /**
     * Best effort, and a guard against saying it twice.
     *
     * There is no model of the car here any more. A signal arrives, the command goes out, and that
     * is the whole of it - what used to be a reconciliation between a wish and a believed position
     * was two names for one fact, because the believed position was only ever set from the commands
     * this same object sent. The owner said as much looking at it: there is nothing to keep in step
     * with, only a thing to ask for.
     *
     * What has to stay is the guard. The audio sampler ticks five times a second and the wish is
     * unchanged between ticks, so without it a playing track would send the same command every
     * 200 ms - and a command that fails would be retried at that rate too. So: ask once, and ask
     * again only if the wish changes, or if the last attempt failed and [retryGuardMs] has passed.
     *
     * A repeat of a wish that succeeded is skipped rather than sent, and that costs nothing: the
     * property is edge-triggered, so writing the value it already holds moves no motor. The one
     * thing that could make such a repeat useful - the amplifier having lowered the covers behind
     * our back - cannot be told apart from the ordinary case without a sensor, and forcing an edge
     * on the ordinary case is exactly the twitch this feature was reported for.
     */
    private fun reconcile(nowMs: Long): SpeakerCoverMotorRequest? {
        if (pendingAction != null) return null
        val targetOpen = desiredOpen ?: return null
        if (targetOpen == raised) {
            val retryDue = askFailed && nowMs - askedAtMs >= retryGuardMs
            if (!retryDue) return null
        }
        raised = targetOpen
        askedAtMs = nowMs
        askFailed = false
        val action = if (targetOpen) {
            SpeakerCoverMotorAction.OPEN
        } else {
            SpeakerCoverMotorAction.CLOSE
        }
        pendingAction = action
        return SpeakerCoverMotorRequest(action, desiredReason)
    }

    companion object {
        const val FALLBACK_SOUND_MS = 3_000L
        const val CLOSE_SILENCE_MS = 30L * 60L * 1_000L
        const val MAX_CONFIRMED_SAMPLE_GAP_MS = 1_000L
        /** How long a failed command waits before the same wish may be sent again. */
        const val RETRY_GUARD_MS = 30_000L
    }
}
