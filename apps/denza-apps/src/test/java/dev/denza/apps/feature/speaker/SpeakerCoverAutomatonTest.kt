package dev.denza.apps.feature.speaker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeakerCoverAutomatonTest {

    @Test
    fun unknownPositionAsksForOpenLikeAnyOtherPosition() {
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
        assertEquals(SpeakerCoverPosition.OPEN, automaton.position)
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
    fun aFailedMotorCommandRetriesAfterItsCooldown() {
        val automaton = SpeakerCoverAutomaton(retryCooldownMs = 100L)
        val first = automaton.onImmediateOpen(0L, "app")!!

        assertNull(automaton.onMotorResult(first.action, success = false, nowMs = 10L))
        assertNull(automaton.onTick(109L))
        assertEquals(SpeakerCoverMotorAction.OPEN, automaton.onTick(110L)?.action)
    }
}
