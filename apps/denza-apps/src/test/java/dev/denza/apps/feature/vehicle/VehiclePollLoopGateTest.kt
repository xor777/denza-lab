package dev.denza.apps.feature.vehicle

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VehiclePollLoopGateTest {

    @Test
    fun replacementWaitsForCancelledBlockingLoopToLeave() = runBlocking {
        val gate = VehiclePollLoopGate()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondAttempted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)

        val first = launch(Dispatchers.IO) {
            gate.run {
                firstEntered.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            }
        }
        assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
        first.cancel()

        val replacement = launch(Dispatchers.IO) {
            secondAttempted.countDown()
            gate.run { secondEntered.countDown() }
        }
        assertTrue(secondAttempted.await(1, TimeUnit.SECONDS))

        try {
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseFirst.countDown()
        }

        assertTrue(secondEntered.await(1, TimeUnit.SECONDS))
        joinAll(first, replacement)
    }
}
