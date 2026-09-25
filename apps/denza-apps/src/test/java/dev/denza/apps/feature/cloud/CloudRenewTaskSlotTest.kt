package dev.denza.apps.feature.cloud

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudRenewTaskSlotTest {
    @Test fun concurrentStatusAndRenewLeaveExactlyOneSuccessor() {
        val slot = CloudRenewTaskSlot()
        val pool = Executors.newScheduledThreadPool(4)
        val futures = Collections.synchronizedList(mutableListOf<ScheduledFuture<*>>())
        val start = CountDownLatch(1)
        val finished = CountDownLatch(20)
        try {
            repeat(20) {
                pool.execute {
                    try {
                        start.await()
                        slot.replaceIf(current = { true }, schedule = {
                            pool.schedule({}, 1, TimeUnit.HOURS).also(futures::add)
                        })
                    } finally {
                        finished.countDown()
                    }
                }
            }
            start.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(20, futures.size)
            assertEquals(1, futures.count { !it.isCancelled })
            slot.cancel()
            assertEquals(0, futures.count { !it.isCancelled })
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun stoppedOwnerCannotCreateAnotherRenewal() {
        val slot = CloudRenewTaskSlot()
        val pool = Executors.newSingleThreadScheduledExecutor()
        val active = AtomicBoolean(true)
        val futures = mutableListOf<ScheduledFuture<*>>()
        try {
            val schedule = {
                pool.schedule({}, 1, TimeUnit.HOURS).also(futures::add)
            }
            slot.replaceIf(active::get, schedule)
            active.set(false)
            slot.cancel()
            slot.replaceIf(active::get, schedule)
            assertEquals(1, futures.size)
            assertTrue(futures.single().isCancelled)
        } finally {
            pool.shutdownNow()
        }
    }
}
