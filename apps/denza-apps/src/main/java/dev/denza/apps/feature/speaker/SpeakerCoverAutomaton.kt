package dev.denza.apps.feature.speaker

/** What the app last established, not a claim about an unreadable position sensor. */
enum class SpeakerCoverPosition {
    UNKNOWN,
    OPEN,
    CLOSED,
}

/**
 * One serialized motor operation.
 *
 * [ESTABLISH_OPEN] is the one-time unknown-state sequence: close, then open. The
 * amplifier command is edge-triggered, so a bare open cannot move a fresh car
 * whose last firmware value is already open.
 */
enum class SpeakerCoverMotorAction {
    OPEN,
    CLOSE,
    ESTABLISH_OPEN,
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
    private val retryCooldownMs: Long = RETRY_COOLDOWN_MS,
) {
    var position: SpeakerCoverPosition = SpeakerCoverPosition.UNKNOWN
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
    private var retryNotBeforeMs = 0L
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
        if (success) {
            position = when (action) {
                SpeakerCoverMotorAction.OPEN,
                SpeakerCoverMotorAction.ESTABLISH_OPEN,
                -> SpeakerCoverPosition.OPEN
                SpeakerCoverMotorAction.CLOSE -> SpeakerCoverPosition.CLOSED
            }
            retryNotBeforeMs = 0L
        } else {
            retryNotBeforeMs = nowMs + retryCooldownMs
        }
        return reconcile(nowMs)
    }

    private fun reconcile(nowMs: Long): SpeakerCoverMotorRequest? {
        if (pendingAction != null || nowMs < retryNotBeforeMs) return null
        val targetOpen = desiredOpen ?: return null
        val action = when {
            targetOpen && position == SpeakerCoverPosition.UNKNOWN ->
                SpeakerCoverMotorAction.ESTABLISH_OPEN
            targetOpen && position == SpeakerCoverPosition.CLOSED -> SpeakerCoverMotorAction.OPEN
            !targetOpen && position != SpeakerCoverPosition.CLOSED -> SpeakerCoverMotorAction.CLOSE
            else -> return null
        }
        pendingAction = action
        return SpeakerCoverMotorRequest(action, desiredReason)
    }

    companion object {
        const val FALLBACK_SOUND_MS = 3_000L
        const val CLOSE_SILENCE_MS = 30L * 60L * 1_000L
        const val MAX_CONFIRMED_SAMPLE_GAP_MS = 1_000L
        const val RETRY_COOLDOWN_MS = 30_000L
    }
}
