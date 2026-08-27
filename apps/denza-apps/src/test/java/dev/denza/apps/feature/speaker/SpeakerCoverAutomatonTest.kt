package dev.denza.apps.feature.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerCoverAutomatonTest {

    /** Asked once. A second signal saying the same thing is not a second command. */
    @Test
    fun theSameWishIsOnlyEverAskedForOnce() {
        val automaton = SpeakerCoverAutomaton()

        assertEquals(
            SpeakerCoverMotorAction.OPEN,
            automaton.onImmediateOpen(0L, "known app")?.action,
        )
        assertNull(automaton.onImmediateOpen(1L, "MediaSession"))

        assertNull(
            automaton.onMotorResult(
                SpeakerCoverMotorAction.OPEN,
                success = true,
                nowMs = 2L,
            ),
        )
        assertTrue(automaton.raised == true)
        // A whole track later, still playing, still nothing to say.
        assertNull(automaton.onImmediateOpen(600_000L, "another player"))
        assertNull(automaton.onTick(900_000L))
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
        assertEquals(SpeakerCoverMotorAction.OPEN, automaton.onTick(100L)?.action)
    }

    /** The other direction needs no guard at all: a different wish is always worth sending. */
    @Test
    fun theOppositeWishGoesOutAtOnce() {
        val automaton = SpeakerCoverAutomaton()
        val open = automaton.onImmediateOpen(0L, "app")!!
        automaton.onMotorResult(open.action, success = true, nowMs = 1L)

        val close = automaton.onManualPosition(open = false, nowMs = 2L)
        assertEquals(SpeakerCoverMotorAction.CLOSE, close?.action)
        assertTrue(automaton.raised == false)
    }

    @Test
    fun threeContinuousSecondsOfOutputOpenUnknownCovers() {
        val automaton = SpeakerCoverAutomaton()

        assertNull(automaton.onAudioSample(0L, hasSignal = true))
        assertNull(automaton.onAudioSample(1_000L, hasSignal = true))
        assertNull(automaton.onAudioSample(2_000L, hasSignal = true))
        assertEquals(
            SpeakerCoverMotorAction.OPEN,
            automaton.onAudioSample(3_000L, hasSignal = true)?.action,
        )
    }

    @Test
    fun interruptedOrMissingOutputDoesNotSatisfyTheFallback() {
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

    @Test
    fun evenAShortSoundRestartsTheWholeCloseDelay() {
        val automaton = SpeakerCoverAutomaton(closeSilenceMs = 3_000L)
        val open = automaton.onImmediateOpen(0L, "app")!!
        automaton.onMotorResult(open.action, success = true, nowMs = 1L)

        automaton.onAudioSample(100L, hasSignal = false)
        automaton.onAudioSample(1_100L, hasSignal = false)
        automaton.onAudioSample(1_200L, hasSignal = true)
        automaton.onAudioSample(1_300L, hasSignal = false)
        automaton.onAudioSample(2_300L, hasSignal = false)
        assertNull(automaton.onAudioSample(3_300L, hasSignal = false))
        assertEquals(
            SpeakerCoverMotorAction.CLOSE,
            automaton.onAudioSample(4_300L, hasSignal = false)?.action,
        )
    }

    @Test
    fun unavailableCaptureNeverCountsAsSilence() {
        val automaton = SpeakerCoverAutomaton(closeSilenceMs = 2_000L)
        val open = automaton.onImmediateOpen(0L, "app")!!
        automaton.onMotorResult(open.action, success = true, nowMs = 1L)

        automaton.onAudioSample(100L, hasSignal = false)
        automaton.onAudioSample(1_100L, hasSignal = false)
        automaton.onCaptureUnavailable()
        assertNull(automaton.onTick(20_000L))
        assertNull(automaton.onAudioSample(21_000L, hasSignal = false))
        assertEquals(
            SpeakerCoverMotorAction.CLOSE,
            automaton.onAudioSample(22_000L, hasSignal = false)?.action,
        )
    }

    @Test
    fun aNewOpenRequestWinsWhileCloseIsStillRunning() {
        val automaton = SpeakerCoverAutomaton(closeSilenceMs = 1_000L)
        val open = automaton.onImmediateOpen(0L, "app")!!
        automaton.onMotorResult(open.action, success = true, nowMs = 1L)
        automaton.onAudioSample(100L, hasSignal = false)
        val close = automaton.onAudioSample(1_100L, hasSignal = false)!!

        assertNull(automaton.onImmediateOpen(1_101L, "MediaSession"))
        assertEquals(
            SpeakerCoverMotorAction.OPEN,
            automaton.onMotorResult(close.action, success = true, nowMs = 1_200L)?.action,
        )
    }

    @Test
    fun openingAPlayerStartsAFreshCloseDelayAfterTheCoversWereClosed() {
        val automaton = SpeakerCoverAutomaton(closeSilenceMs = 1_000L)
        automaton.onAudioSample(0L, hasSignal = false)
        val close = automaton.onAudioSample(1_000L, hasSignal = false)!!
        automaton.onMotorResult(close.action, success = true, nowMs = 1_001L)

        val open = automaton.onImmediateOpen(1_002L, "known app")!!
        automaton.onMotorResult(open.action, success = true, nowMs = 1_003L)

        assertNull(automaton.onAudioSample(1_100L, hasSignal = false))
        assertNull(automaton.onAudioSample(2_000L, hasSignal = false))
        assertEquals(
            SpeakerCoverMotorAction.CLOSE,
            automaton.onAudioSample(2_100L, hasSignal = false)?.action,
        )
    }

    @Test
    fun productionDelaysAreThreeSecondsAndThirtyMinutes() {
        assertEquals(3_000L, SpeakerCoverAutomaton.FALLBACK_SOUND_MS)
        assertEquals(30L * 60L * 1_000L, SpeakerCoverAutomaton.CLOSE_SILENCE_MS)
    }

    @Test
    fun productionGuardIsThirtySeconds() {
        assertEquals(30_000L, SpeakerCoverAutomaton.RETRY_GUARD_MS)
    }
}
