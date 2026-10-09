package dev.denza.apps.feature.hud

import dev.denza.apps.feature.hud.HudMapGrantAction.ALLOW_OP_THEN_REQUEST
import dev.denza.apps.feature.hud.HudMapGrantAction.NOTHING
import dev.denza.apps.feature.hud.HudMapGrantAction.RELEASE
import dev.denza.apps.feature.hud.HudMapGrantAction.REQUEST
import dev.denza.apps.feature.hud.HudMapGrantTrigger.RESUME
import dev.denza.apps.feature.hud.HudMapGrantTrigger.SWITCH
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the map's screen capture is asked for, released or left alone: from our Activity only, with
 * op 46 allowed or allowed first, never in a loop of resumes, and gone the moment the map or the
 * hints go off.
 */
class HudMapGrantPolicyTest {
    private val now = 1_000_000L

    @Test
    fun theMapOnWithNoCaptureAsksAndAllowsTheOpFirstWhenItIsNotAllowed() {
        assertEquals(REQUEST, decide(opAllowed = true))
        assertEquals(ALLOW_OP_THEN_REQUEST, decide(opAllowed = false))
        assertEquals(REQUEST, decide(trigger = SWITCH))
    }

    @Test
    fun aLiveCaptureOrOneUnderWayIsLeftAlone() {
        assertEquals(NOTHING, decide(live = true))
        assertEquals(NOTHING, decide(inFlight = true, lastAttemptMs = now - 1_000L))
        assertEquals(NOTHING, decide(trigger = SWITCH, inFlight = true, lastAttemptMs = now - 5_000L))
    }

    @Test
    fun aRequestWhoseAnswerNeverCameIsAskedAgainAfterItsRetry() {
        val stuckAt = now - HudMapGrantPolicy.IN_FLIGHT_MS
        assertEquals(REQUEST, decide(trigger = SWITCH, inFlight = true, lastAttemptMs = stuckAt))
        assertEquals(NOTHING, decide(trigger = SWITCH, inFlight = true, lastAttemptMs = stuckAt + 1))
        // A resume still keeps its minute from that attempt.
        assertEquals(NOTHING, decide(inFlight = true, lastAttemptMs = stuckAt))
        assertEquals(REQUEST, decide(inFlight = true, lastAttemptMs = now - HudMapGrantPolicy.RESUME_RETRY_MS))
    }

    @Test
    fun theMapOrTheHintsOffReleasesWhatIsHeldOrAsked() {
        assertEquals(RELEASE, decide(mapOn = false, live = true))
        assertEquals(RELEASE, decide(hintsOn = false, live = true))
        assertEquals(RELEASE, decide(mapOn = false, inFlight = true))
        assertEquals(NOTHING, decide(mapOn = false))
        assertEquals(NOTHING, decide(hintsOn = false, trigger = SWITCH))
    }

    @Test
    fun resumesDoNotRepeatAFailedAttemptForAMinute() {
        val failedAt = now - 30_000L
        assertEquals(NOTHING, decide(lastAttemptMs = failedAt))
        assertEquals(NOTHING, decide(lastAttemptMs = now - HudMapGrantPolicy.RESUME_RETRY_MS + 1))
        assertEquals(REQUEST, decide(lastAttemptMs = now - HudMapGrantPolicy.RESUME_RETRY_MS))
    }

    @Test
    fun aPressOfTheSwitchAsksAgainSoonButNotInTheSameBreath() {
        assertEquals(REQUEST, decide(trigger = SWITCH, lastAttemptMs = now - 30_000L))
        assertEquals(NOTHING, decide(trigger = SWITCH, lastAttemptMs = now - 1_000L))
    }

    @Test
    fun theIdleReasonSaysOffWaitingOrKeepsWhatWentWrong() {
        assertEquals(HudMapGrantReason.OFF, idle(mapOn = false, current = HudMapGrantReason.NO_ADB))
        assertEquals(HudMapGrantReason.LIVE, idle(live = true))
        assertEquals(HudMapGrantReason.REQUESTING, idle(inFlight = true))
        assertEquals(HudMapGrantReason.WAITING_APP, idle(activityResumed = false, current = HudMapGrantReason.OFF))
        // A refusal stands until the next attempt, with or without our Activity.
        assertEquals(HudMapGrantReason.REFUSED, idle(activityResumed = false, current = HudMapGrantReason.REFUSED))
        assertEquals(HudMapGrantReason.STOPPED, idle(current = HudMapGrantReason.STOPPED))
    }

    private fun decide(
        trigger: HudMapGrantTrigger = RESUME,
        hintsOn: Boolean = true,
        mapOn: Boolean = true,
        live: Boolean = false,
        inFlight: Boolean = false,
        opAllowed: Boolean = true,
        lastAttemptMs: Long? = null,
    ) = HudMapGrantPolicy.decide(trigger, hintsOn, mapOn, live, inFlight, opAllowed, lastAttemptMs, now)

    private fun idle(
        hintsOn: Boolean = true,
        mapOn: Boolean = true,
        live: Boolean = false,
        inFlight: Boolean = false,
        activityResumed: Boolean = true,
        current: HudMapGrantReason = HudMapGrantReason.OFF,
    ) = HudMapGrantPolicy.idleReason(hintsOn, mapOn, live, inFlight, activityResumed, current)
}
