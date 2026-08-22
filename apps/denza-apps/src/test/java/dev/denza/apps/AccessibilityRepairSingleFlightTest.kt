package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityRepairSingleFlightTest {
    @Test
    fun concurrentOwnersJoinOneRepairAndReceiveItsResult() {
        val repair = AccessibilityRepairSingleFlight()
        val received = mutableListOf<Throwable?>()
        val failure = IllegalStateException("repair failed")

        assertTrue(repair.join { received += it })
        assertFalse(repair.join { received += it })
        assertTrue(repair.isRunning())

        repair.complete(failure)

        assertFalse(repair.isRunning())
        assertEquals(2, received.size)
        assertSame(failure, received[0])
        assertSame(failure, received[1])
        assertTrue(repair.join { received += it })
    }

    @Test
    fun failingOwnerCallbackDoesNotHideCompletionFromOtherOwners() {
        val repair = AccessibilityRepairSingleFlight()
        var completed = false

        repair.join { error("callback failed") }
        repair.join { completed = true }
        repair.complete(null)

        assertTrue(completed)
        assertFalse(repair.isRunning())
    }
}
