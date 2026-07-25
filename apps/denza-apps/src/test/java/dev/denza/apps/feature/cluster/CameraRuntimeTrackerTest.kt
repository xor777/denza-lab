package dev.denza.apps.feature.cluster

import dev.denza.apps.feature.mirrors.MirrorSide
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraRuntimeTrackerTest {
    @Test
    fun publishesMonotonicStartingReadyStoppingFailureAndIdleSnapshots() {
        val tracker = CameraRuntimeTracker()

        assertEquals(CameraRuntimeSnapshot(), tracker.snapshot())

        val starting = tracker.starting(MirrorSide.LEFT)
        assertEquals(CameraRuntimePhase.STARTING, starting.phase)
        assertEquals(MirrorSide.LEFT, starting.side)
        assertEquals(1L, starting.generation)

        val ready = tracker.ready(MirrorSide.LEFT, "initialized")
        assertEquals(CameraRuntimePhase.READY, ready.phase)
        assertEquals(2L, ready.generation)
        assertEquals("initialized", ready.details)

        val stopping = tracker.stopping("closing camera surface")
        assertEquals(CameraRuntimePhase.STOPPING, stopping.phase)
        assertEquals(MirrorSide.LEFT, stopping.side)
        assertEquals(3L, stopping.generation)
        assertEquals("closing camera surface", stopping.details)

        val failed = tracker.failed("binder died")
        assertEquals(CameraRuntimePhase.FAILED, failed.phase)
        assertEquals(MirrorSide.LEFT, failed.side)
        assertEquals(4L, failed.generation)

        val idle = tracker.idle("hidden")
        assertEquals(CameraRuntimePhase.IDLE, idle.phase)
        assertEquals(null, idle.side)
        assertEquals(5L, idle.generation)
    }
}
