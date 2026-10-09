package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What «Сервис» reads of the map window: one transition per change, counts by code, the frame cost. */
class HudMapRuntimeTest {
    @Before
    fun reset() = HudMapRuntime.resetForTest()

    @Test
    fun aStateReportsAChangeOnceAndAnotherCoverIsAChange() {
        assertTrue(HudMapRuntime.onState(HudMapState.SENDING))
        assertFalse(HudMapRuntime.onState(HudMapState.SENDING))
        assertTrue(HudMapRuntime.onState(HudMapState.COVERED, "камера"))
        assertFalse(HudMapRuntime.onState(HudMapState.COVERED, "камера"))
        assertTrue(HudMapRuntime.onState(HudMapState.COVERED, "диалог"))
        assertEquals("диалог", HudMapRuntime.snapshot().coveredBy)

        assertTrue(HudMapRuntime.onState(HudMapState.NOT_ON_SCREEN))
        assertNull(HudMapRuntime.snapshot().coveredBy)
    }

    @Test
    fun onlyAcceptedFramesCountAsSent() {
        HudMapRuntime.onFireResult(0)
        HudMapRuntime.onFireResult(0)
        HudMapRuntime.onFireResult(-200)

        val snapshot = HudMapRuntime.snapshot()
        assertEquals(2L, snapshot.framesSent)
        assertEquals(-200, snapshot.lastFireResult)
    }

    @Test
    fun captureFailuresAreCountedByCode() {
        HudMapRuntime.onCaptureFailure(3)
        HudMapRuntime.onCaptureFailure(3)
        HudMapRuntime.onCaptureFailure(4)

        assertEquals(mapOf(3 to 2L, 4 to 1L), HudMapRuntime.snapshot().captureFailures)
    }

    @Test
    fun theFrameCostKeepsTheLastAndARunningMean() {
        HudMapRuntime.onFrame(20, HudMapAnchorSource.DETECTED)
        HudMapRuntime.onFrame(40, HudMapAnchorSource.REMEMBERED)

        val snapshot = HudMapRuntime.snapshot()
        assertEquals(40L, snapshot.lastFrameMs)
        // 20 + (40 - 20) × 0.05
        assertEquals(21L, snapshot.meanFrameMs)
        assertEquals(HudMapAnchorSource.REMEMBERED, snapshot.anchor)
    }

    @Test
    fun warmUpFramesShowAsTheLastButStayOutOfTheMean() {
        HudMapRuntime.onFrame(900, HudMapAnchorSource.DEFAULT, warmingUp = true)
        assertEquals(900L, HudMapRuntime.snapshot().lastFrameMs)
        assertNull(HudMapRuntime.snapshot().meanFrameMs)

        HudMapRuntime.onFrame(25, HudMapAnchorSource.DETECTED)

        assertEquals(25L, HudMapRuntime.snapshot().meanFrameMs)
    }
}
