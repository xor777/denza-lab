package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AccessibilityRepairSingleFlightTest {
    @Test
    fun settingsMutationsFromDifferentOwnersNeverOverlap() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val first = thread(start = true) {
            AccessibilitySettingsMutationLock.withLock {
                firstEntered.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
        }
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))

        val second = thread(start = true) {
            AccessibilitySettingsMutationLock.withLock {
                secondEntered.countDown()
            }
        }
        try {
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
        }
        assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
        first.join(1_000L)
        second.join(1_000L)
        assertFalse(first.isAlive)
        assertFalse(second.isAlive)
    }

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
