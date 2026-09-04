package dev.denza.apps.feature.cluster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraTeardownBarrierTest {
    @Test
    fun newShowRemainsBlockedUntilTheRegisteredVendorTeardownCompletes() {
        val barrier = CameraTeardownBarrier()
        val token = barrier.begin()
        var completionCallbacks = 0
        barrier.whenClear { completionCallbacks += 1 }

        assertFalse(barrier.isClear)
        assertEquals(0, completionCallbacks)
        assertTrue(barrier.complete(token))
        assertTrue(barrier.isClear)
        assertEquals(1, completionCallbacks)
    }

    @Test
    fun lateCompletionFromAnOldGenerationCannotClearANewerBarrier() {
        val barrier = CameraTeardownBarrier()
        val old = barrier.begin()
        assertTrue(barrier.complete(old))
        val current = barrier.begin()

        assertFalse(barrier.complete(old))
        assertFalse(barrier.isClear)
        assertTrue(barrier.complete(current))
    }
}
