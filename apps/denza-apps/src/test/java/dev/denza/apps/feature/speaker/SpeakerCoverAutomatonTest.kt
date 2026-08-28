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

    @Test
    fun theProductionFallbackIsThreeSecondsOfSound() {
        assertEquals(3_000L, SpeakerCoverAutomaton.FALLBACK_SOUND_MS)
    }

    @Test
    fun productionGuardIsThirtySeconds() {
        assertEquals(30_000L, SpeakerCoverAutomaton.RETRY_GUARD_MS)
    }
}
