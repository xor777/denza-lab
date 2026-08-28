package dev.denza.apps.feature.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The contract in one line: **automation never overrides a hand, and it only ever acts once.**
 *
 * Everything below is that sentence read from a different side. The automaton opens the covers on
 * the first sign of playback in a boot and then has nothing more to say; the two panel buttons
 * always reach the motor; and a press ends the automation's turn until the car is restarted.
 */
class SpeakerCoverAutomatonTest {

    /** Asked once a boot, by whichever layer speaks first, and never again by any of them. */
    @Test
    fun theOpeningOfTheBootIsTheOnlyOneAnySignalGets() {
        val automaton = SpeakerCoverAutomaton()

        val opening = automaton.onImmediateOpen(0L, "known app")
        assertEquals(SpeakerCoverMotorAction.OPEN, opening?.action)
        assertEquals(SpeakerCoverCommandSource.AUTOMATIC, opening?.source)
        assertNull(automaton.onImmediateOpen(1L, "MediaSession"))

        assertNull(
            automaton.onMotorResult(
                SpeakerCoverMotorAction.OPEN,
                success = true,
                nowMs = 2L,
            ),
        )
        assertTrue(automaton.raised == true)
        assertFalse(automaton.armed)

        // A whole track later, from all three layers at once, still nothing to say.
        assertNull(automaton.onImmediateOpen(600_000L, "another player"))
        assertNull(automaton.onImmediateOpen(600_100L, "MediaSession"))
        var at = 601_000L
        repeat(50) {
            assertNull(automaton.onAudioSample(at, hasSignal = true))
            at += 200L
        }
        assertNull(automaton.onTick(900_000L))
    }

    /**
     * The reported fault, kept as a test because it is the whole reason for the redesign.
     *
     * Covers closed by hand during a track came back out about three seconds later: the sustained
     * sound was still there, the fallback fired again, and the automation overruled the freshest
     * thing that had happened in the car. A level cannot outrank a press. Nothing here is timing -
     * twenty seconds of unbroken music is simply not an argument any more.
     */
    @Test
    fun musicStillPlayingNeverReopensCoversClosedByHand() {
        val automaton = SpeakerCoverAutomaton()
        val opening = automaton.onImmediateOpen(0L, "known app")!!
        automaton.onMotorResult(opening.action, success = true, nowMs = 1L)

        val closed = automaton.onManualPosition(open = false, nowMs = 2L)!!
        assertEquals(SpeakerCoverMotorAction.CLOSE, closed.action)
        assertTrue(closed.manual)
        automaton.onMotorResult(closed.action, success = true, nowMs = 3L)

        var at = 100L
        repeat(100) {
            assertNull(automaton.onAudioSample(at, hasSignal = true))
            at += 200L
        }
        assertNull(automaton.onTick(at))
        assertTrue(automaton.raised == false)
    }

    /**
     * The dead button.
     *
     * «Поднять» used to be swallowed whenever the automaton already believed the covers were up -
     * which is exactly the moment it exists for, because the amplifier lowers them on its own and
     * the app cannot see that it happened. The press now always leaves as a command, and it is
     * marked manual so [SpeakerCoverMotor] forces the edge that makes it move.
     */
    @Test
    fun theRaiseButtonCommandsTheMotorEvenWhenTheCoversAreAlreadyBelievedUp() {
        val automaton = SpeakerCoverAutomaton()
        val opening = automaton.onImmediateOpen(0L, "app")!!
        automaton.onMotorResult(opening.action, success = true, nowMs = 1L)
        assertNull(automaton.onImmediateOpen(2L, "the same app again"))

        val press = automaton.onManualPosition(open = true, nowMs = 3L)
        assertEquals(SpeakerCoverMotorAction.OPEN, press?.action)
        assertTrue(press!!.manual)

        // The exemption belongs to the press, not to the wish: once it has produced its command,
        // the sampler's ticks go back to being silent.
        automaton.onMotorResult(press.action, success = true, nowMs = 4L)
        assertNull(automaton.onTick(5L))
        assertNull(automaton.onTick(1_000_000L))
    }

    /** A hand on the buttons before any playback keeps the opener off for the rest of the boot. */
    @Test
    fun aButtonPressedBeforeTheOpeningEverHappensCancelsIt() {
        val automaton = SpeakerCoverAutomaton()

        val closed = automaton.onManualPosition(open = false, nowMs = 0L)!!
        assertEquals(SpeakerCoverMotorAction.CLOSE, closed.action)
        automaton.onMotorResult(closed.action, success = true, nowMs = 1L)
        assertFalse(automaton.armed)
        assertTrue(automaton.driverHasTheWheel)

        assertNull(automaton.onImmediateOpen(2L, "known app"))
        assertNull(automaton.onImmediateOpen(3L, "MediaSession"))
        assertNull(automaton.onAudioSample(1_000L, hasSignal = true))
        assertNull(automaton.onAudioSample(2_000L, hasSignal = true))
        assertNull(automaton.onAudioSample(3_000L, hasSignal = true))
        assertNull(automaton.onAudioSample(4_000L, hasSignal = true))
    }

    /**
     * A service restarted while the car is still running picks up where the boot left off.
     *
     * Both flags come in through the constructor for this reason alone: the process is restartable
     * and the car is not. Without them, killing and reviving the service would hand the automation
     * a second opening, and covers deliberately shut a minute earlier would come back out.
     */
    @Test
    fun theBootScopedFlagsSurviveTheProcessTheyWereSetIn() {
        val spent = SpeakerCoverAutomaton(armed = false)
        assertNull(spent.onImmediateOpen(0L, "known app"))
        assertNull(spent.onAudioSample(1_000L, hasSignal = true))
        assertNull(spent.onAudioSample(2_000L, hasSignal = true))
        assertNull(spent.onAudioSample(3_000L, hasSignal = true))
        assertNull(spent.onAudioSample(4_000L, hasSignal = true))

        val handedOver = SpeakerCoverAutomaton(driverHasTheWheel = true)
        assertNull(handedOver.onImmediateOpen(0L, "known app"))
        assertNull(handedOver.onImmediateOpen(1L, "MediaSession"))
    }

    /**
     * Switching the automation off: worth an open, not worth a twitch.
     *
     * It ignores both flags - the covers still have to be left out, or the driver keeps a
     * suppressed stock auto-lift and nothing to raise them with - but it is an ordinary wish, so a
     * matching one already asked for skips it silently.
     */
    @Test
    fun theToggleOffOpenIsBestEffortAndNotAButton() {
        val fresh = SpeakerCoverAutomaton()
        val parting = fresh.onBestEffortOpen(0L, "автоматика выключена")
        assertEquals(SpeakerCoverMotorAction.OPEN, parting?.action)
        assertEquals(SpeakerCoverCommandSource.BEST_EFFORT, parting?.source)
        assertFalse(parting!!.manual)

        val alreadyOpen = SpeakerCoverAutomaton()
        val opening = alreadyOpen.onImmediateOpen(0L, "app")!!
        alreadyOpen.onMotorResult(opening.action, success = true, nowMs = 1L)
        assertNull(alreadyOpen.onBestEffortOpen(2L, "автоматика выключена"))

        // Over a press it does not fire at all, however the press is doing: this one has finished.
        // The service refuses the same thing one step later, reading the value the property holds -
        // a boot-scoped `2` can only have come from the «Опустить» button - which is the version of
        // this that survives a restarted process, the one case the automaton cannot see.
        val closedByHand = SpeakerCoverAutomaton()
        closedByHand.onManualPosition(open = false, nowMs = 0L)
        closedByHand.onMotorResult(SpeakerCoverMotorAction.CLOSE, success = true, nowMs = 1L)
        assertNull(closedByHand.onBestEffortOpen(2L, "автоматика выключена"))
        assertNull(closedByHand.onTick(60_000L))
        assertTrue(closedByHand.raised == false)
    }

    /**
     * Switched off while «Опустить» is still running its adb call.
     *
     * The parting open used to replace the desire outright, so the close finished and the open went
     * out straight after it - the automation undoing a press on its way out the door, which is the
     * one thing this contract forbids. Nothing leaves here now, and the service's wind-down waits
     * on the command already in flight and then stops without opening anything.
     */
    @Test
    fun theTogglesPartingOpenStandsDownBeforeACloseInFlight() {
        val automaton = SpeakerCoverAutomaton()

        val close = automaton.onManualPosition(open = false, nowMs = 0L)!!
        assertEquals(SpeakerCoverMotorAction.CLOSE, close.action)
        assertNull(automaton.onBestEffortOpen(1L, "автоматика выключена"))

        assertNull(automaton.onMotorResult(close.action, success = true, nowMs = 2L))
        assertNull(automaton.onTick(3L))
        assertNull(automaton.onTick(60_000L))
        assertTrue(automaton.raised == false)
    }

    /**
     * Switched off while a press is queued behind a command already in flight.
     *
     * Worse than the previous one, and the same fault: replacing the desire threw the queued press
     * away entirely, so the covers ended up open and the button that had asked for the opposite
     * never reached the motor at all.
     */
    @Test
    fun aQueuedPressSurvivesTheAutomationBeingSwitchedOff() {
        val automaton = SpeakerCoverAutomaton()

        val opening = automaton.onImmediateOpen(0L, "app")!!
        assertNull(automaton.onManualPosition(open = false, nowMs = 1L))
        assertNull(automaton.onBestEffortOpen(2L, "автоматика выключена"))

        val queued = automaton.onMotorResult(opening.action, success = true, nowMs = 3L)!!
        assertEquals(SpeakerCoverMotorAction.CLOSE, queued.action)
        assertTrue(queued.manual)

        assertNull(automaton.onMotorResult(queued.action, success = true, nowMs = 4L))
        assertNull(automaton.onTick(60_000L))
        assertTrue(automaton.raised == false)
    }

    /** Standing down is not abandoning: a press that failed still gets its retry afterwards. */
    @Test
    fun aPressThatFailedIsStillRetriedAfterTheTogglesPartingOpen() {
        val automaton = SpeakerCoverAutomaton(retryGuardMs = 100L)

        val close = automaton.onManualPosition(open = false, nowMs = 0L)!!
        assertNull(automaton.onMotorResult(close.action, success = false, nowMs = 5L))
        assertNull(automaton.onBestEffortOpen(10L, "автоматика выключена"))

        val retry = automaton.onTick(100L)!!
        assertEquals(SpeakerCoverMotorAction.CLOSE, retry.action)
        assertTrue(retry.manual)
    }

    @Test
    fun threeContinuousSecondsOfOutputOpenTheCovers() {
        val automaton = SpeakerCoverAutomaton()

        assertNull(automaton.onAudioSample(0L, hasSignal = true))
        assertNull(automaton.onAudioSample(1_000L, hasSignal = true))
        assertNull(automaton.onAudioSample(2_000L, hasSignal = true))
        val opened = automaton.onAudioSample(3_000L, hasSignal = true)
        assertEquals(SpeakerCoverMotorAction.OPEN, opened?.action)
        assertEquals(SpeakerCoverCommandSource.AUTOMATIC, opened?.source)
    }

    /** Frames further apart than the gap are two runs, not one, however loud both of them are. */
    @Test
    fun framesTooFarApartAreNotAContinuousRun() {
        val automaton = SpeakerCoverAutomaton()

        var at = 0L
        repeat(20) {
            assertNull(automaton.onAudioSample(at, hasSignal = true))
            at += 1_200L
        }
        assertEquals(0L, automaton.consecutiveSoundMs)
    }

    /**
     * The run length is a live reading, not a leftover.
     *
     * Nothing acts on it - the two events below both end any chance of an automatic open, one by
     * cutting the detector off and one by handing the wheel over - so a stale count would change no
     * command. It is still wrong to leave one lying about: the number says how much unbroken sound
     * has been heard *so far*, and after the ear is cut off or the driver takes over, the honest
     * answer to that is none.
     */
    @Test
    fun theRunLengthIsCountedFromNothingAgainAfterTheEarIsCutOff() {
        val lostDetector = SpeakerCoverAutomaton()
        lostDetector.onAudioSample(0L, hasSignal = true)
        lostDetector.onAudioSample(1_000L, hasSignal = true)
        assertEquals(1_000L, lostDetector.consecutiveSoundMs)
        lostDetector.onCaptureUnavailable()
        assertEquals(0L, lostDetector.consecutiveSoundMs)

        val takenOver = SpeakerCoverAutomaton()
        takenOver.onAudioSample(0L, hasSignal = true)
        takenOver.onAudioSample(1_000L, hasSignal = true)
        assertEquals(1_000L, takenOver.consecutiveSoundMs)
        takenOver.onManualPosition(open = true, nowMs = 1_100L)
        assertEquals(0L, takenOver.consecutiveSoundMs)
    }

    /**
     * A frame stamped earlier than the one before it is a clock, not a rewind.
     *
     * The sampler's timestamps come from the capture buffer, and the seconds already heard are not
     * unheard because two of them arrived out of order. Such a frame breaks the run's arithmetic
     * without breaking the run: it adds nothing, and takes nothing away.
     */
    @Test
    fun aFrameStampedBackwardsTakesNothingOffTheRunAlreadyHeard() {
        val automaton = SpeakerCoverAutomaton()

        assertNull(automaton.onAudioSample(0L, hasSignal = true))
        assertNull(automaton.onAudioSample(1_000L, hasSignal = true))
        assertNull(automaton.onAudioSample(500L, hasSignal = true))
        assertEquals(1_000L, automaton.consecutiveSoundMs)

        assertNull(automaton.onAudioSample(1_500L, hasSignal = true))
        assertEquals(
            SpeakerCoverMotorAction.OPEN,
            automaton.onAudioSample(2_500L, hasSignal = true)?.action,
        )
    }

    /** A detector that stopped answering is not silence, and it is not a run either. */
    @Test
    fun unavailableCaptureBreaksTheRunWithoutMeaningAnything() {
        val automaton = SpeakerCoverAutomaton()

        automaton.onAudioSample(0L, hasSignal = true)
        automaton.onAudioSample(1_000L, hasSignal = true)
        automaton.onCaptureUnavailable()
        assertNull(automaton.onAudioSample(2_000L, hasSignal = true))
        assertNull(automaton.onAudioSample(3_000L, hasSignal = true))
        assertNull(automaton.onAudioSample(4_000L, hasSignal = true))
        assertEquals(
            SpeakerCoverMotorAction.OPEN,
            automaton.onAudioSample(5_000L, hasSignal = true)?.action,
        )
    }

    /** A moment of quiet in the middle is the same break: the three seconds start again. */
    @Test
    fun aSilentFrameStartsTheThreeSecondsOver() {
        val automaton = SpeakerCoverAutomaton()

        automaton.onAudioSample(0L, hasSignal = true)
        automaton.onAudioSample(1_000L, hasSignal = true)
        assertNull(automaton.onAudioSample(2_000L, hasSignal = false))
        assertNull(automaton.onAudioSample(3_000L, hasSignal = true))
        assertNull(automaton.onAudioSample(4_000L, hasSignal = true))
        assertNull(automaton.onAudioSample(5_000L, hasSignal = true))
        assertEquals(
            SpeakerCoverMotorAction.OPEN,
            automaton.onAudioSample(6_000L, hasSignal = true)?.action,
        )
    }

    /**
     * The guard the sampler makes necessary.
     *
     * Audio frames arrive five times a second and the wish does not change between them, so a
     * command that fails would be retried at that rate without this - which is the storm the guard
     * exists for, not a model of the car.
     */
    @Test
    fun aFailedCommandWaitsOutTheGuardBeforeTheSameWishIsSentAgain() {
        val automaton = SpeakerCoverAutomaton(retryGuardMs = 100L)

        val first = automaton.onImmediateOpen(0L, "app")!!
        assertEquals(SpeakerCoverMotorAction.OPEN, first.action)
        assertNull(automaton.onMotorResult(first.action, success = false, nowMs = 10L))
        assertNull(automaton.onTick(50L))
        assertNull(automaton.onTick(99L))
        val retry = automaton.onTick(100L)
        assertEquals(SpeakerCoverMotorAction.OPEN, retry?.action)
        assertEquals(SpeakerCoverCommandSource.AUTOMATIC, retry?.source)
    }

    /**
     * While the sensor is alive the sampler calls nothing but [SpeakerCoverAutomaton.onAudioSample],
     * so that is where a failed command has to find its next chance.
     *
     * The service reaches for `onTick` only on a frame that never arrived. A command that failed
     * during playback would otherwise wait for the music to stop before it was retried, which is
     * the one moment nobody is waiting for the covers.
     */
    @Test
    fun anAudioFrameIsAlsoTheClockAFailedCommandRetriesOn() {
        val automaton = SpeakerCoverAutomaton(retryGuardMs = 100L)

        val press = automaton.onManualPosition(open = false, nowMs = 0L)!!
        assertNull(automaton.onMotorResult(press.action, success = false, nowMs = 5L))

        val retry = automaton.onAudioSample(200L, hasSignal = true)!!
        assertEquals(SpeakerCoverMotorAction.CLOSE, retry.action)
        assertTrue(retry.manual)
    }

    /**
     * Every attempt starts the guard again, which is the whole of what the guard is for.
     *
     * Timed from the first attempt instead, the wait would be paid once and never again: the second
     * failure and every one after it would be retried on the next frame, five times a second, for
     * as long as the covers kept refusing - the storm, arriving a single failure later than before.
     */
    @Test
    fun eachFailedAttemptRestartsTheGuardRatherThanTheFirstOne() {
        val automaton = SpeakerCoverAutomaton(retryGuardMs = 100L)

        val press = automaton.onManualPosition(open = false, nowMs = 1_000L)!!
        assertNull(automaton.onMotorResult(press.action, success = false, nowMs = 1_005L))

        val retry = automaton.onTick(1_100L)!!
        assertEquals(SpeakerCoverMotorAction.CLOSE, retry.action)

        assertNull(automaton.onMotorResult(retry.action, success = false, nowMs = 1_105L))
        assertNull(automaton.onTick(1_150L))
        assertEquals(SpeakerCoverMotorAction.CLOSE, automaton.onTick(1_200L)?.action)
    }

    /** A press that failed to reach the motor is still the driver's press when it is retried. */
    @Test
    fun aRetriedButtonIsStillAButton() {
        val automaton = SpeakerCoverAutomaton(retryGuardMs = 100L)

        val press = automaton.onManualPosition(open = true, nowMs = 0L)!!
        assertTrue(press.manual)
        assertNull(automaton.onMotorResult(press.action, success = false, nowMs = 5L))
        assertNull(automaton.onTick(50L))
        val retry = automaton.onTick(100L)!!
        assertEquals(SpeakerCoverMotorAction.OPEN, retry.action)
        assertTrue(retry.manual)
    }

    /** Pressed while a command is in flight, the desire waits and leaves with the result. */
    @Test
    fun aButtonPressedMidCommandLeavesWithTheResult() {
        val automaton = SpeakerCoverAutomaton()

        val opening = automaton.onImmediateOpen(0L, "app")!!
        assertNull(automaton.onManualPosition(open = false, nowMs = 1L))

        val queued = automaton.onMotorResult(opening.action, success = true, nowMs = 2L)!!
        assertEquals(SpeakerCoverMotorAction.CLOSE, queued.action)
        assertTrue(queued.manual)
    }

    /**
     * The mashed button.
     *
     * A cover command takes seconds - an adb session, sometimes a forced pair - and while it runs
     * the button gives no sign of having been heard, so it gets pressed again. Every repeat used to
     * re-arm the forced edge, and each one was spent after the first command finished: the covers
     * twitched once per impatient tap. A repeat of a command already in flight asks for nothing the
     * car is not already doing, so it is absorbed - on the result, and on every tick after it, there
     * is nothing left to send.
     */
    @Test
    fun mashingTheSameButtonWhileItsCommandRunsBuysNoSecondMovement() {
        val automaton = SpeakerCoverAutomaton()

        val press = automaton.onManualPosition(open = true, nowMs = 0L)!!
        assertEquals(SpeakerCoverMotorAction.OPEN, press.action)
        assertNull(automaton.onManualPosition(open = true, nowMs = 100L))
        assertNull(automaton.onManualPosition(open = true, nowMs = 200L))
        assertNull(automaton.onManualPosition(open = true, nowMs = 300L))

        assertNull(automaton.onMotorResult(press.action, success = true, nowMs = 400L))
        assertNull(automaton.onTick(500L))
        assertNull(automaton.onTick(60_000L))
        assertTrue(automaton.raised == true)
    }

    /**
     * Absorbed is not ignored: the press is still a press, it is only not a second command.
     *
     * Everything a press does to the automaton other than reaching the motor still happens on the
     * repeat - the wheel stays with the driver, the one automatic opening stays spent, and the run
     * of sound heard so far is counted from nothing again. The last of those is the one that could
     * quietly rot: audio frames keep arriving while a command is in flight, so a repeat that
     * returned before touching the counter would leave a run half-heard across a press.
     */
    @Test
    fun anAbsorbedRepeatStillTakesTheWheelAndResetsTheRun() {
        val automaton = SpeakerCoverAutomaton()

        val press = automaton.onManualPosition(open = false, nowMs = 0L)!!
        assertEquals(SpeakerCoverMotorAction.CLOSE, press.action)

        // The music does not stop for the adb call, and none of it is an argument any more.
        assertNull(automaton.onAudioSample(100L, hasSignal = true))
        assertNull(automaton.onAudioSample(1_100L, hasSignal = true))
        assertEquals(1_000L, automaton.consecutiveSoundMs)

        assertNull(automaton.onManualPosition(open = false, nowMs = 1_200L))
        assertTrue(automaton.driverHasTheWheel)
        assertFalse(automaton.armed)
        assertEquals(0L, automaton.consecutiveSoundMs)

        assertNull(automaton.onMotorResult(press.action, success = true, nowMs = 1_300L))
        assertTrue(automaton.raised == false)
    }

    /**
     * The other button mid-command is a change of mind, not a repeat, and it still turns around.
     *
     * The absorb above is only ever allowed to swallow the identical press. Asking for the opposite
     * position while a press is running is the newest fact in the car by the same argument that
     * gives a button the forced edge at all, so the desire is replaced and leaves with the result.
     */
    @Test
    fun theOppositeButtonMidPressStillLeavesWithTheResult() {
        val automaton = SpeakerCoverAutomaton()

        val raise = automaton.onManualPosition(open = true, nowMs = 0L)!!
        assertEquals(SpeakerCoverMotorAction.OPEN, raise.action)
        assertNull(automaton.onManualPosition(open = false, nowMs = 10L))

        val turnedAround = automaton.onMotorResult(raise.action, success = true, nowMs = 20L)!!
        assertEquals(SpeakerCoverMotorAction.CLOSE, turnedAround.action)
        assertTrue(turnedAround.manual)
    }

    /**
     * With nothing in flight, a repeat is the dead-button case and still forces.
     *
     * The absorb is scoped to a command that is actually running. A press that arrives after the
     * previous one has finished is the driver saying the covers are not where the app thinks they
     * are - which, with an amplifier that lowers them unannounced, is the only thing that can ever
     * say it - and swallowing that would put back the exact defect the buttons were fixed for.
     */
    @Test
    fun aRepeatWithNothingInFlightIsStillTheDeadButtonGuarantee() {
        val automaton = SpeakerCoverAutomaton()

        val first = automaton.onManualPosition(open = true, nowMs = 0L)!!
        automaton.onMotorResult(first.action, success = true, nowMs = 10L)

        val again = automaton.onManualPosition(open = true, nowMs = 20L)
        assertEquals(SpeakerCoverMotorAction.OPEN, again?.action)
        assertTrue(again!!.manual)
    }

    /**
     * A result answers the command it was asked for, and nothing else answers for it.
     *
     * The command in flight is what defers every other wish, so anything that clears it early lets
     * a second command out on top of the first - a queued press leaving for the motor while the
     * open it is waiting behind is still running its adb call. Only the action that was sent can
     * say that the motor is free again.
     */
    @Test
    fun aResultForAnotherActionDoesNotFreeTheCommandInFlight() {
        val automaton = SpeakerCoverAutomaton()

        val opening = automaton.onImmediateOpen(0L, "app")!!
        assertNull(automaton.onManualPosition(open = false, nowMs = 1L))

        assertNull(
            automaton.onMotorResult(SpeakerCoverMotorAction.CLOSE, success = true, nowMs = 2L),
        )
        assertEquals(SpeakerCoverMotorAction.OPEN, automaton.pendingAction)

        val queued = automaton.onMotorResult(opening.action, success = true, nowMs = 3L)!!
        assertEquals(SpeakerCoverMotorAction.CLOSE, queued.action)
        assertTrue(queued.manual)
    }

    @Test
    fun theProductionFallbackIsThreeSecondsOfSound() {
        assertEquals(3_000L, SpeakerCoverAutomaton.FALLBACK_SOUND_MS)
    }

    @Test
    fun productionGuardIsThirtySeconds() {
        assertEquals(30_000L, SpeakerCoverAutomaton.RETRY_GUARD_MS)
    }
}
