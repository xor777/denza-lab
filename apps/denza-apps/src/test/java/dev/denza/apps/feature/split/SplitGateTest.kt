package dev.denza.apps.feature.split

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guards of the gate's transitions (`SplitGate.kt`) that no scenario holds on its own.
 *
 * The signal scenarios drive the core's close ahead of the area and its check a second later
 * through a real Home, so each of them has one cover and no operation in the way. The questions
 * the guards ask - is the cover already recorded, is an operation that uses the gate pending, is
 * the scene covered right now - are put to them here one at a time, with everything else held.
 */
class SplitGateTest {
    private val clock = FakeSplitClock()
    private val flips = mutableListOf<Boolean>()
    private var area: Int? = 3
    private var coverRecorded = false
    private var keepersPending = false

    private val ahead = SplitGateAhead(
        gate = SplitGateSwitch { open -> flips += open },
        gateLeaseStore = FakeGateLease(owned = true),
        clock = clock,
        readArea = { area },
        log = SplitDiagnosticLog { _, _ -> },
        coverRecorded = { coverRecorded },
        keepersPending = { keepersPending },
    )

    /** The control the three below are measured against: a swallowed Home is undone. */
    @Test
    fun aCloseAheadThatNoCoverFollowsIsUndoneASecondLater() {
        ahead.close("homekey")
        assertEquals(listOf(false), flips)
        assertEquals("one check armed", 1, clock.pendingTimers())

        clock.advance(SplitGateAhead.CHECK_MS)

        assertEquals(listOf(false, true), flips)
    }

    /** A cover already recorded has suspended the gate: a second key or push changes nothing. */
    @Test
    fun aCloseAheadOverACoverAlreadyRecordedFlipsNothingAndArmsNothing() {
        coverRecorded = true

        ahead.close("homekey")

        assertEquals(emptyList<Boolean>(), flips)
        assertEquals(0, clock.pendingTimers())
    }

    /**
     * Home closed the gate ahead and its own operation has recorded the cover since; the area
     * reads visible again by the check. Reopening it is the reconcile's right, not the check's.
     */
    @Test
    fun theCheckLeavesTheGateClosedOnceTheCoverIsRecorded() {
        ahead.close("homekey")
        coverRecorded = true

        clock.advance(SplitGateAhead.CHECK_MS)

        assertEquals(listOf(false), flips)
    }

    /** An open, a navigation return or an off queued within the second keeps the gate as it is. */
    @Test
    fun theCheckLeavesTheGateToAnOperationThatUsesIt() {
        ahead.close("homekey")
        keepersPending = true

        clock.advance(SplitGateAhead.CHECK_MS)

        assertEquals(listOf(false), flips)
    }

    /** Over a covered scene the resumption reads the area and sends nothing (1.9.2). */
    @Test
    fun aResumptionOverACoveredSceneSendsNothing() {
        listOf(0, 4).forEach { covered ->
            val fake = FakeShell().apply { area = covered }

            assertFalse("area $covered", gate(fake).resumeOwnedGateIfVisible())

            assertEquals("area $covered", listOf("service call activity_task 30"), fake.commands)
            assertEquals(emptyList<String>(), fake.refused.toList())
        }
    }

    /** The control: over a visible scene the gate this product owns is reopened. */
    @Test
    fun aResumptionOverAVisibleSceneReopensOurGate() {
        val fake = FakeShell().apply { area = 3 }

        assertTrue(gate(fake).resumeOwnedGateIfVisible())

        assertEquals(
            listOf("service call activity_task 30", "service call activity_task 126 i32 1"),
            fake.commands,
        )
        assertTrue(fake.isGateOpen())
    }

    private fun gate(fake: FakeShell) = SplitGate(
        world = SplitWorld(fake::shell, settle = {}, topology = SplitTopologyCache(), parsed = {}),
        gateLeaseStore = FakeGateLease(owned = true),
    )
}
