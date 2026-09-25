package dev.denza.apps.feature.cloud

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudReportQueueTest {
    @Test fun optInCapturesFreshCloudOffAndCustomOffWithoutAnyPriorHistory() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val queue = CloudReportQueue(executor)
            val writes = mutableListOf<String>()
            for (state in listOf("clean-off", "custom-off")) {
                val saved = CountDownLatch(1)
                assertTrue(queue.capture(queue.ticket(), enabled = true, changing = false) {
                    { writes += state; saved.countDown() }
                })
                assertTrue(saved.await(5, TimeUnit.SECONDS))
            }
            assertEquals(listOf("clean-off", "custom-off"), writes)
            assertFalse(queue.capture(queue.ticket(), enabled = false, changing = false) {
                { writes += "report-off" }
            })
            assertEquals(listOf("clean-off", "custom-off"), writes)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun offReturnsImmediatelyThenWaitsForInFlightWriteAndRevokesQueuedWork() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val queue = CloudReportQueue(executor)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val settled = CountDownLatch(1)
            val writes = AtomicInteger()
            val commits = AtomicInteger()
            val oldTicket = queue.ticket()
            assertTrue(queue.submit(oldTicket) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                writes.incrementAndGet()
            })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertTrue(queue.submit(oldTicket) { writes.incrementAndGet() })
            val callReturned = CountDownLatch(1)
            val caller = Thread {
                assertTrue(queue.change(
                    commit = { commits.incrementAndGet(); true },
                    settled = { settled.countDown() },
                ))
                callReturned.countDown()
            }
            caller.start()
            assertTrue("UI call waited for blocked MediaStore write", callReturned.await(1, TimeUnit.SECONDS))
            assertFalse("OFF was confirmed before the in-flight write finished", settled.await(100, TimeUnit.MILLISECONDS))
            assertFalse(queue.submit(oldTicket) { writes.incrementAndGet() })
            release.countDown()
            assertTrue(settled.await(5, TimeUnit.SECONDS))
            assertEquals(1, writes.get())
            assertEquals(1, commits.get())
            assertFalse(queue.submit(oldTicket) { writes.incrementAndGet() })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun frequentUpdatesCoalesceToOneLatestPendingSnapshot() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val queue = CloudReportQueue(executor)
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val second = CountDownLatch(1)
            val writes = mutableListOf<Int>()
            val ticket = queue.ticket()
            queue.submit(ticket) { started.countDown(); release.await(5, TimeUnit.SECONDS); writes += 0 }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            for (index in 1..100) queue.submit(ticket) { writes += index; second.countDown() }
            release.countDown()
            assertTrue(second.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(0, 100), writes)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun completedOrFailedWritePublishesFinalStatusWithoutAnotherCloudEvent() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val visible = AtomicReference("ожидает записи")
            val status = AtomicReference("ожидает записи")
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val published = CountDownLatch(1)
            val queue = CloudReportQueue(
                executor,
                onWriteFailure = { status.set("Не сохранён") },
                onWriteComplete = { visible.set(status.get()); published.countDown() },
            )
            assertTrue(queue.submit(queue.ticket()) {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                throw IllegalStateException("write failed")
            })
            assertTrue(started.await(5, TimeUnit.SECONDS))
            assertEquals("ожидает записи", visible.get())
            release.countDown()
            assertTrue(published.await(5, TimeUnit.SECONDS))
            assertEquals("Не сохранён", visible.get())
            val saved = CountDownLatch(1)
            val successQueue = CloudReportQueue(executor,
                onWriteComplete = { visible.set(status.get()); saved.countDown() },
            )
            assertTrue(successQueue.submit(successQueue.ticket()) { status.set("Download/Denza Apps/denza-cloud-report.txt") })
            assertTrue(saved.await(5, TimeUnit.SECONDS))
            assertEquals("Download/Denza Apps/denza-cloud-report.txt", visible.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
