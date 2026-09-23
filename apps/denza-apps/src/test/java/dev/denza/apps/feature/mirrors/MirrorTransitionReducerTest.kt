package dev.denza.apps.feature.mirrors

import dev.denza.apps.feature.cluster.CameraRuntimePhase
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The camera follows AVC's own card and the lamps AVC itself reads. The rules are the firmware's
 * (com.byd.avc from the OTA image, 2026-09-23); the timings are the 2026-09-04 live captures.
 */
class MirrorTransitionReducerTest {
    @Test
    fun anOrdinaryTurnOpensOnTheFirstPollOfTheCardWithItsLamps() {
        MirrorSide.entries.forEach { side ->
            val started = reduce(MirrorTransitionState(), side, lamp(side), idle(), 100L)
            assertEquals(MirrorTransitionCommand.Show(side), started.command)
            assertEquals(MirrorTransitionPhase.STARTING, started.state.phase)
            val shown = reduce(started.state, side, lamp(side), ready(side), 400L)
            assertEquals(MirrorTransitionPhase.SHOWING, shown.state.phase)
            assertEquals(MirrorTransitionCommand.None, shown.command)
        }
    }

    @Test
    fun theStockTailAfterTheLampsGoOffNeverOpensACamera() {
        // AVC keeps its card 2000 ms after the lamps stop (LightUtil.onLightOff). Replay past it.
        MirrorSide.entries.forEach { side ->
            var state = MirrorTransitionState()
            repeat(30) { poll ->
                val result = reduce(state, side, MirrorLamp.OFF, idle(), 100L + poll * 100L, lampAtMs = 50L)
                assertEquals(MirrorTransitionCommand.None, result.command)
                state = result.state
            }
        }
    }

    @Test
    fun lampsOfTheOtherSideNeverOpenTheCardOnScreen() {
        val result = reduce(MirrorTransitionState(), MirrorSide.LEFT, MirrorLamp.RIGHT, idle(), 100L)
        assertEquals(MirrorTransitionCommand.None, result.command)
    }

    @Test
    fun withTheLampFeedDownTheCardAloneStillOpensTheCamera() {
        val result = reduce(MirrorTransitionState(), MirrorSide.RIGHT, MirrorLamp.UNKNOWN, idle(), 100L, lampAtMs = -1L)
        assertEquals(MirrorTransitionCommand.Show(MirrorSide.RIGHT), result.command)
    }

    @Test
    fun noCardMeansNoCameraWhateverTheLamps() {
        MirrorSide.entries.forEach { side ->
            val result = reduce(MirrorTransitionState(), null, lamp(side), idle(), 100L)
            assertEquals(MirrorTransitionCommand.None, result.command)
        }
    }

    @Test
    fun theCameraClosesWhenTheLampsLeaveItsSide() {
        listOf(MirrorLamp.OFF, MirrorLamp.RIGHT).forEach { lamp ->
            val result = reduce(showing(MirrorSide.LEFT), MirrorSide.LEFT, lamp, ready(MirrorSide.LEFT), 900L, lampAtMs = 850L)
            assertEquals(MirrorTransitionCommand.Hide, result.command)
            assertEquals(MirrorTransitionPhase.IDLE, result.state.phase)
            assertNull("an ordinary ending is not a failure", result.state.failedSide)
        }
    }

    @Test
    fun theCameraClosesWithTheStockCard() {
        val closed = reduce(showing(MirrorSide.RIGHT), null, MirrorLamp.UNKNOWN, ready(MirrorSide.RIGHT), 900L, lampAtMs = -1L)
        assertEquals(MirrorTransitionCommand.Hide, closed.command)
        val switched = reduce(showing(MirrorSide.RIGHT), MirrorSide.LEFT, MirrorLamp.UNKNOWN, ready(MirrorSide.RIGHT), 900L, lampAtMs = -1L)
        assertEquals(MirrorTransitionCommand.Hide, switched.command)
    }

    @Test
    fun aLeverReEngagedInsideTheStockTailReopensOnTheSameCard() {
        // Lamps off closed the camera; AVC kept its card (onLightOn cancels its exit timer).
        val closed = MirrorTransitionReducer.lampsLeft(showing(MirrorSide.LEFT), idle(), 1_000L, "lamps off")
        val tail = reduce(closed, MirrorSide.LEFT, MirrorLamp.OFF, idle(), 1_100L, lampAtMs = 1_000L)
        assertEquals(MirrorTransitionCommand.None, tail.command)
        val first = reduce(tail.state, MirrorSide.LEFT, MirrorLamp.LEFT, idle(), 1_200L, lampAtMs = 1_150L)
        assertEquals("settles first", MirrorTransitionCommand.None, first.command)
        val second = reduce(first.state, MirrorSide.LEFT, MirrorLamp.LEFT, idle(), 1_300L, lampAtMs = 1_150L)
        assertEquals(MirrorTransitionCommand.Show(MirrorSide.LEFT), second.command)
    }

    @Test
    fun aCancellationOnsetNeverReopensTheSurvivingCard() {
        // 2026-09-04: every cancellation crossed the opposite onset; the stock card then survived
        // 2.18–3.06 s. The lamps went off 0.18–1.06 s after the onset.
        MirrorSide.entries.forEach { side ->
            var state = MirrorTransitionReducer.preempted(showing(side), idle(), 1_000L, side, "lever moved")
            for (poll in 1..35) {
                val now = 1_000L + poll * 100L
                val lamp = if (now < 2_060L) lamp(side) else MirrorLamp.OFF
                val lampAt = if (now < 2_060L) 100L else 2_060L
                val result = reduce(state, side, lamp, idle(), now, lampAtMs = lampAt)
                assertEquals("poll $poll", MirrorTransitionCommand.None, result.command)
                state = result.state
            }
        }
    }

    @Test
    fun aBumpedLeverWithTheLampsStillOnReopensAfterTheBumpWindow() {
        val preempted = MirrorTransitionReducer.preempted(showing(MirrorSide.RIGHT), idle(), 1_000L, MirrorSide.RIGHT, "lever moved")
        var state = preempted
        var shownAt = -1L
        for (poll in 1..25) {
            val now = 1_000L + poll * 100L
            val result = reduce(state, MirrorSide.RIGHT, MirrorLamp.RIGHT, idle(), now, lampAtMs = 100L)
            if (result.command == MirrorTransitionCommand.Show(MirrorSide.RIGHT)) {
                shownAt = now
                break
            }
            state = result.state
        }
        assertEquals(1_000L + MirrorTransitionReducer.LEVER_BUMP_SETTLE_MS + 100L, shownAt)
    }

    @Test
    fun aSwitchOpensTheOtherSideOnlyAfterTeardownAndTwoCleanPolls() {
        val preempted = MirrorTransitionReducer.preempted(showing(MirrorSide.LEFT), idle(), 1_000L, MirrorSide.LEFT, "lever moved right")
        val stopping = reduce(preempted, MirrorSide.RIGHT, MirrorLamp.RIGHT, runtime(CameraRuntimePhase.STOPPING), 1_100L, lampAtMs = 1_060L)
        assertEquals(MirrorTransitionCommand.None, stopping.command)
        val inFlight = reduce(stopping.state, MirrorSide.RIGHT, MirrorLamp.RIGHT, idle(), 1_200L, lampAtMs = 1_060L, preempting = true)
        assertEquals(MirrorTransitionCommand.None, inFlight.command)
        val first = reduce(inFlight.state, MirrorSide.RIGHT, MirrorLamp.RIGHT, idle(), 1_300L, lampAtMs = 1_060L)
        assertEquals(MirrorTransitionCommand.None, first.command)
        val second = reduce(first.state, MirrorSide.RIGHT, MirrorLamp.RIGHT, idle(), 1_400L, lampAtMs = 1_060L)
        assertEquals(MirrorTransitionCommand.Show(MirrorSide.RIGHT), second.command)
    }

    @Test
    fun aSettleRunRestartsWhenTheSideChangesOrTheRuntimeIsBusy() {
        val closed = MirrorTransitionReducer.lampsLeft(showing(MirrorSide.LEFT), idle(), 1_000L, "lamps off")
        val left = reduce(closed, MirrorSide.LEFT, MirrorLamp.LEFT, idle(), 1_100L, lampAtMs = 1_050L)
        val right = reduce(left.state, MirrorSide.RIGHT, MirrorLamp.RIGHT, idle(), 1_200L, lampAtMs = 1_150L)
        assertEquals(MirrorTransitionCommand.None, right.command)
        val busy = reduce(right.state, MirrorSide.RIGHT, MirrorLamp.RIGHT, runtime(CameraRuntimePhase.STOPPING), 1_300L, lampAtMs = 1_150L)
        assertEquals(MirrorTransitionCommand.None, busy.command)
        val again = reduce(busy.state, MirrorSide.RIGHT, MirrorLamp.RIGHT, idle(), 1_400L, lampAtMs = 1_150L)
        assertEquals(MirrorTransitionCommand.None, again.command)
        val shown = reduce(again.state, MirrorSide.RIGHT, MirrorLamp.RIGHT, idle(), 1_500L, lampAtMs = 1_150L)
        assertEquals(MirrorTransitionCommand.Show(MirrorSide.RIGHT), shown.command)
    }

    @Test
    fun aPollWithoutAnyCardEndsTheSettleSoTheNextTurnOpensAtOnce() {
        val closed = MirrorTransitionReducer.lampsLeft(showing(MirrorSide.LEFT), idle(), 1_000L, "lamps off")
        val gone = reduce(closed, null, MirrorLamp.OFF, idle(), 3_100L, lampAtMs = 1_000L)
        val next = reduce(gone.state, MirrorSide.LEFT, MirrorLamp.LEFT, idle(), 9_000L, lampAtMs = 8_900L)
        assertEquals(MirrorTransitionCommand.Show(MirrorSide.LEFT), next.command)
    }

    @Test
    fun ourOwnFailureNeverLoopsWhileTheCardSurvives() {
        val started = reduce(MirrorTransitionState(), MirrorSide.LEFT, MirrorLamp.LEFT, idle(), 100L)
        val timedOut = reduce(started.state, MirrorSide.LEFT, MirrorLamp.LEFT, runtime(CameraRuntimePhase.STARTING, MirrorSide.LEFT), 100L + MirrorTransitionReducer.START_ACK_TIMEOUT_MS)
        assertEquals(MirrorTransitionCommand.Hide, timedOut.command)
        assertEquals(MirrorSide.LEFT, timedOut.state.failedSide)
        var state = timedOut.state
        repeat(40) { poll ->
            val result = reduce(state, MirrorSide.LEFT, MirrorLamp.LEFT, runtime(CameraRuntimePhase.FAILED, MirrorSide.LEFT), 2_000L + poll * 100L)
            assertEquals(MirrorTransitionCommand.None, result.command)
            state = result.state
        }
        val cardGone = reduce(state, null, MirrorLamp.OFF, idle(), 7_000L, lampAtMs = 6_900L)
        assertNull(cardGone.state.failedSide)
        val next = reduce(cardGone.state, MirrorSide.LEFT, MirrorLamp.LEFT, idle(), 9_000L, lampAtMs = 8_900L)
        assertEquals(MirrorTransitionCommand.Show(MirrorSide.LEFT), next.command)
    }

    @Test
    fun startingFailuresAreOurs() {
        listOf(
            runtime(CameraRuntimePhase.FAILED, MirrorSide.RIGHT),
            runtime(CameraRuntimePhase.READY, MirrorSide.LEFT),
        ).forEach { runtime ->
            val started = starting(MirrorSide.RIGHT)
            val result = reduce(started, MirrorSide.RIGHT, MirrorLamp.RIGHT, runtime, 300L)
            assertEquals(MirrorTransitionCommand.Hide, result.command)
            assertEquals(MirrorSide.RIGHT, result.state.failedSide)
        }
    }

    @Test
    fun framesStoppingMeansAvcTookItsRendererBack() {
        val shown = showing(MirrorSide.RIGHT)
        val flowing = reduce(shown, MirrorSide.RIGHT, MirrorLamp.RIGHT, ready(MirrorSide.RIGHT), 1_000L, frameAgeMs = 40L)
        assertEquals(MirrorTransitionCommand.None, flowing.command)
        val stalled = reduce(shown, MirrorSide.RIGHT, MirrorLamp.RIGHT, ready(MirrorSide.RIGHT), 1_000L, frameAgeMs = MirrorFrameWatch.STALL_MS)
        assertEquals(MirrorTransitionCommand.Hide, stalled.command)
        assertEquals(MirrorSide.RIGHT, stalled.state.failedSide)
    }

    @Test
    fun readyWithoutAnyPictureIsAFailureAfterItsTimeout() {
        val shown = showing(MirrorSide.LEFT)
        val early = reduce(shown, MirrorSide.LEFT, MirrorLamp.LEFT, ready(MirrorSide.LEFT), 500L + 1_000L)
        assertEquals(MirrorTransitionCommand.None, early.command)
        val late = reduce(shown, MirrorSide.LEFT, MirrorLamp.LEFT, ready(MirrorSide.LEFT), 500L + MirrorTransitionReducer.FIRST_FRAME_TIMEOUT_MS)
        assertEquals(MirrorTransitionCommand.Hide, late.command)
    }

    @Test
    fun showingLosesItsRuntimeOrSideAsFailures() {
        listOf(
            runtime(CameraRuntimePhase.IDLE),
            runtime(CameraRuntimePhase.READY, MirrorSide.RIGHT),
        ).forEach { runtime ->
            val result = reduce(showing(MirrorSide.LEFT), MirrorSide.LEFT, MirrorLamp.LEFT, runtime, 900L, frameAgeMs = 20L)
            assertEquals(MirrorTransitionCommand.Hide, result.command)
            assertEquals(MirrorSide.LEFT, result.state.failedSide)
        }
    }

    @Test
    fun theLampRuleMatchesTheStockReading() {
        assertEquals(false, MirrorTransitionReducer.lampsLeftSide(MirrorLamp.LEFT, MirrorSide.LEFT))
        assertEquals(true, MirrorTransitionReducer.lampsLeftSide(MirrorLamp.OFF, MirrorSide.LEFT))
        assertEquals(true, MirrorTransitionReducer.lampsLeftSide(MirrorLamp.RIGHT, MirrorSide.LEFT))
        assertEquals("a feed outage never closes a camera", false, MirrorTransitionReducer.lampsLeftSide(MirrorLamp.UNKNOWN, MirrorSide.LEFT))
        assertEquals(false, MirrorTransitionReducer.lampsLeftSide(MirrorLamp.OFF, null))
    }

    private fun lamp(side: MirrorSide) = when (side) {
        MirrorSide.LEFT -> MirrorLamp.LEFT
        MirrorSide.RIGHT -> MirrorLamp.RIGHT
    }

    private fun starting(side: MirrorSide) = MirrorTransitionState(
        phase = MirrorTransitionPhase.STARTING,
        side = side,
        phaseStartedAtMs = 100L,
    )

    private fun showing(side: MirrorSide) = MirrorTransitionState(
        phase = MirrorTransitionPhase.SHOWING,
        side = side,
        phaseStartedAtMs = 500L,
    )

    private fun reduce(
        state: MirrorTransitionState,
        stock: MirrorSide?,
        lamp: MirrorLamp,
        runtime: CameraRuntimeSnapshot,
        nowMs: Long,
        lampAtMs: Long = 50L,
        preempting: Boolean = false,
        frameAgeMs: Long? = null,
    ) = MirrorTransitionReducer.reduce(
        state,
        MirrorTransitionObservation(
            stockSide = stock,
            lamp = lamp,
            lampObservedAtMs = lampAtMs,
            runtime = runtime,
            nowMs = nowMs,
            preemptionInFlight = preempting,
            frameAgeMs = frameAgeMs,
        ),
    )

    private fun idle() = runtime(CameraRuntimePhase.IDLE)

    private fun ready(side: MirrorSide) = runtime(CameraRuntimePhase.READY, side)

    private fun runtime(phase: CameraRuntimePhase, side: MirrorSide? = null) = CameraRuntimeSnapshot(
        phase = phase,
        side = side,
        generation = 1L,
        details = phase.name.lowercase(),
    )
}
