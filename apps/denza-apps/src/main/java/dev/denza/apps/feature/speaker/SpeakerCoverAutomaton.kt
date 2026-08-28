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

/**
 * Who asked, which decides what the command is allowed to cost.
 *
 * The three differ in one thing only: how much visible movement is worth paying for a command that
 * may already be satisfied. A hand on a button is worth a twitch, because the driver is looking at
 * the covers and pressed anyway; nothing else is.
 *
 * This travels all the way to [SpeakerCoverMotorProtocol.needsEdgeBreak], which spends it - one
 * line per constant, and each line is the sentence below it read as a price.
 */
enum class SpeakerCoverCommandSource {
    /**
     * A panel button. Always moves the motor, forcing an edge if the property already holds the
     * value - the press is the freshest fact in the car, and a button that sometimes does nothing
     * is worse than one that sometimes twitches.
     */
    MANUAL,

    /**
     * The automation's single opening of the boot, spent on the first evidence of playback.
     *
     * It buys the forced pair only against a property it remembers nothing about, which is the
     * start of a trip - the covers are retracted there, so the pair's close is invisible, and the
     * one opening it has promised needs an edge it can count on.
     */
    AUTOMATIC,

    /**
     * Turning the automation off: worth an open, not worth a twitch.
     *
     * Never a forced pair, including against a property it remembers nothing about. The single
     * write either makes the edge and the covers rise, or lands on the value already held and does
     * nothing; a parting gesture can live with either, and neither is movement the driver has to
     * watch after flipping a switch off.
     */
    BEST_EFFORT,
}

data class SpeakerCoverMotorRequest(
    val action: SpeakerCoverMotorAction,
    val reason: String,
    val source: SpeakerCoverCommandSource,
) {
    /**
     * Shorthand for the one source that outranks everything, and no longer the motor's input.
     *
     * The edge rule used to be asked in exactly this bit - manual or not - which is what let the
     * parting open be priced as though it were the automation. It reads [source] now; this stays
     * because "the driver asked for this" is a thing worth saying in one word where the automaton's
     * own contract is being checked.
     */
    val manual: Boolean get() = source == SpeakerCoverCommandSource.MANUAL
}

/**
 * Pure timing and command state for the cover automation.
 *
 * **The automation does one thing per boot: it opens the covers once, on the first sign of
 * playback, and then stands down.** Everything else the driver does by hand.
 *
 * That is a narrowing, and it was earned. The previous shape was a continuous reconciler: it
 * opened on playback, closed after thirty minutes of confirmed silence, and kept both wishes alive
 * for as long as the process ran. Three faults came out of that, all the same fault seen from
 * different sides - an ongoing level was allowed to outrank a deliberate act:
 *
 * - closing the covers by hand during a track was undone about three seconds later by the sound
 *   still playing, which is the automation arguing with the person who just pressed the button;
 * - the manual raise was silently swallowed whenever the automaton already believed the covers
 *   were up, which is exactly the moment it is needed - after the amplifier lowered them itself;
 * - one successful open latched the wish for the life of the process, and only the silence timer
 *   could ever change it, so a dead audio capture wedged the feature entirely.
 *
 * There is nothing here to keep in step with. Cover position is unreadable, the amplifier moves
 * them on its own, and everything this class ever knew came from its own commands. So it no longer
 * pretends to maintain a state: it makes one opening offer per boot and then leaves the covers to
 * the two buttons and to the ignition cycle, which retracts them anyway.
 *
 * [armed] and [driverHasTheWheel] are constructor inputs because they outlive the process: the
 * service restores them from boot-scoped preferences, or a service restart mid-boot would open
 * covers the driver had just closed.
 */
class SpeakerCoverAutomaton(
    private val fallbackSoundMs: Long = FALLBACK_SOUND_MS,
    private val maxConfirmedSampleGapMs: Long = MAX_CONFIRMED_SAMPLE_GAP_MS,
    private val retryGuardMs: Long = RETRY_GUARD_MS,
    armed: Boolean = true,
    driverHasTheWheel: Boolean = false,
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

    /** Whether the one automatic opening of this boot is still unspent. */
    var armed: Boolean = armed
        private set

    /** Set by any button press, and never cleared: only an ignition cycle gives the wheel back. */
    var driverHasTheWheel: Boolean = driverHasTheWheel
        private set

    var consecutiveSoundMs: Long = 0L
        private set

    private var desired: Desire? = null
    private var forcePending = false
    private var lastSampleAtMs: Long? = null
    private var lastSampleHadSignal = false
    private var askedAtMs = 0L
    private var askFailed = false

    /**
     * The one automatic opening, offered by whichever of the three playback layers speaks first.
     *
     * Every later signal is silent, and that silence is the feature working rather than the latch
     * that was reported: the covers are open, the driver can see they are open, and if the
     * amplifier lowers them afterwards the raise button is there and now always moves the motor.
     */
    fun onImmediateOpen(nowMs: Long, reason: String): SpeakerCoverMotorRequest? {
        if (!armed || driverHasTheWheel) return null
        armed = false
        desired = Desire(open = true, reason = reason, source = SpeakerCoverCommandSource.AUTOMATIC)
        return reconcile(nowMs)
    }

    /**
     * The driver moved the covers from the panel, which ends the automation's turn for this boot.
     *
     * Two rules meet here, and both are the same rule. The wheel changes hands: no level, however
     * sustained, may undo a press - closing the covers with music playing used to be reversed by
     * the music three seconds later. And the press always produces a command, even one identical
     * to the last thing asked for, because a repeat is precisely how a driver says "the covers are
     * not where you think they are". [SpeakerCoverMotor] pays for that with a forced edge.
     *
     * With one exception, and it is the same rule again from the driver's side. A press repeated
     * *while the command it repeats is still running its adb call* is not a driver saying anything
     * new - it is a driver saying the same thing again because the button gave no sign of having
     * heard, which takes seconds here. Re-arming [forcePending] on each of those bought a further
     * forced pair per tap, so a mashed button did not answer faster: it queued a physical twitch
     * for every extra press. So an identical press against a manual command in flight is absorbed -
     * the wheel and the run counter are still taken, only the command is not duplicated. The
     * opposite position is a new fact and still replaces the desire; a repeat with *nothing* in
     * flight still forces, because that is the dead-button guarantee this method exists for.
     */
    fun onManualPosition(open: Boolean, nowMs: Long): SpeakerCoverMotorRequest? {
        driverHasTheWheel = true
        armed = false
        consecutiveSoundMs = 0L
        lastSampleAtMs = null
        lastSampleHadSignal = false
        val inFlight = desired
        if (
            pendingAction != null &&
            inFlight != null &&
            inFlight.open == open &&
            inFlight.source == SpeakerCoverCommandSource.MANUAL
        ) {
            return null
        }
        desired = Desire(open = open, reason = "вручную", source = SpeakerCoverCommandSource.MANUAL)
        forcePending = true
        return reconcile(nowMs)
    }

    /**
     * The parting open when the automation is switched off, so nobody is left with shut covers and
     * the stock auto-lift already suppressed.
     *
     * Not manual and not the one-shot: it ignores whether either has been spent, because the covers
     * still have to end up out, but it is an ordinary wish that a matching last command may skip.
     * Forcing an edge here would twitch covers that are already open, which is what a driver
     * turning a feature off is least expecting to see.
     *
     * And it stands down entirely in front of a press. This is the automation's last word, and the
     * one thing it may not do is have the last word over a hand - a «Опустить» still running its
     * adb call, or still queued behind a command in flight, is the freshest fact in the car, and
     * overwriting the desire with an open would both undo it and throw the queued press away.
     * The service refuses the same thing one step later, from the value the property is holding,
     * which is the only version of this the automaton cannot see: a press made in an earlier
     * process. Between them there is no window. What is still allowed through here is a *retry* of
     * that press, which is the same command being carried out rather than a new one.
     */
    fun onBestEffortOpen(nowMs: Long, reason: String): SpeakerCoverMotorRequest? {
        if (desired?.source != SpeakerCoverCommandSource.MANUAL) {
            desired = Desire(
                open = true,
                reason = reason,
                source = SpeakerCoverCommandSource.BEST_EFFORT,
            )
        }
        return reconcile(nowMs)
    }

    /**
     * The source-agnostic ear: three continuous seconds of output mix.
     *
     * Only continuity is counted, never absence. Frames further apart than [maxConfirmedSampleGapMs]
     * are not a run, and a detector that has stopped answering is not silence - it is nothing at
     * all. Silence has no consequence any more; there is no automatic close to time.
     */
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

        consecutiveSoundMs = if (hasSignal && lastSampleHadSignal) {
            consecutiveSoundMs + delta
        } else {
            0L
        }
        lastSampleAtMs = capturedAtMs
        lastSampleHadSignal = hasSignal

        if (hasSignal && consecutiveSoundMs >= fallbackSoundMs) {
            onImmediateOpen(capturedAtMs, "звук ${fallbackSoundMs / 1_000} с")?.let { return it }
        }
        // Still reconcile on a frame that decided nothing: the sampler calls this instead of
        // onTick whenever capture is alive, so it is also the clock a failed command retries on.
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
     * One wish out, and a guard against saying it twice.
     *
     * The audio sampler ticks five times a second and the wish is unchanged between ticks, so
     * without a guard a playing track would send the same command every 200 ms - and a command that
     * failed would be retried at that rate too. So: ask once, ask again only if the wish changes,
     * or if the last attempt failed and [retryGuardMs] has passed. Retries have no ceiling; the
     * dashboard stops promising a recovery long before the automaton stops trying.
     *
     * [forcePending] is the button's exemption, and it is spent by the command it produces rather
     * than living on the wish. A manual desire that stays behind for retries must not re-fire on
     * every tick, but a retry of it is still manual - the driver's press is what is being carried
     * out, however many attempts it takes.
     *
     * A command already in flight defers everything, including a press: the desire waits here and
     * leaves through [onMotorResult] with its source intact.
     */
    private fun reconcile(nowMs: Long): SpeakerCoverMotorRequest? {
        if (pendingAction != null) return null
        val desire = desired ?: return null
        if (desire.open == raised && !forcePending) {
            val retryDue = askFailed && nowMs - askedAtMs >= retryGuardMs
            if (!retryDue) return null
        }
        forcePending = false
        raised = desire.open
        askedAtMs = nowMs
        askFailed = false
        val action = if (desire.open) {
            SpeakerCoverMotorAction.OPEN
        } else {
            SpeakerCoverMotorAction.CLOSE
        }
        pendingAction = action
        return SpeakerCoverMotorRequest(action, desire.reason, desire.source)
    }

    private data class Desire(
        val open: Boolean,
        val reason: String,
        val source: SpeakerCoverCommandSource,
    )

    companion object {
        const val FALLBACK_SOUND_MS = 3_000L
        const val MAX_CONFIRMED_SAMPLE_GAP_MS = 1_000L
        /** How long a failed command waits before the same wish may be sent again. */
        const val RETRY_GUARD_MS = 30_000L
    }
}
