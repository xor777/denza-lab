package dev.denza.apps.feature.split

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitLaunchOverlayTest {
    @Test
    fun overlappingLauncherRequestsKeepOverlayUntilLastLeaseFinishes() {
        val first = SplitLaunchOverlayState().begin(1)
        val second = first.begin(2)

        assertTrue(second.shouldShow)
        assertTrue(second.finish(2).shouldShow)
        assertFalse(second.finish(2).finish(1).shouldShow)
    }

    @Test
    fun unknownOrRepeatedCompletionCannotHideAnActiveLaunch() {
        val active = SplitLaunchOverlayState().begin(7)

        assertTrue(active.finish(99).shouldShow)
        assertFalse(active.finish(7).finish(7).shouldShow)
    }
}
