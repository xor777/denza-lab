package dev.denza.apps.feature.cluster

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCommandFenceTest {
    @Test
    fun preemptionInvalidatesAnAlreadyDispatchedShow() {
        val fence = CameraCommandFence()
        val show = fence.issueShow()

        fence.invalidate()

        assertFalse(fence.isCurrent(show))
    }

    @Test
    fun onlyTheNewestShowCanRun() {
        val fence = CameraCommandFence()
        val first = fence.issueShow()
        val second = fence.issueShow()

        assertFalse(fence.isCurrent(first))
        assertTrue(fence.isCurrent(second))
    }
}
