package dev.denza.apps.feature.cloud

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudCustomHistoryQueueTest {
    @Test fun continuousEventsYieldWriterToQueuedReportOffCommit() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstSave = CountDownLatch(1)
            val release = CountDownLatch(1)
            val offCommitted = CountDownLatch(1)
            val secondSave = CountDownLatch(1)
            val count = AtomicInteger()
            val committed = AtomicBoolean()
            lateinit var history: CloudCustomHistoryQueue
            history = CloudCustomHistoryQueue(executor, load = { "" }, save = {
                val index = count.incrementAndGet()
                if (index == 1) {
                    firstSave.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
                if (index <= 3) history.addAll(listOf("at" to "next-$index"))
                if (index == 2) {
                    assertTrue("history monopolized the report writer", committed.get())
                    secondSave.countDown()
                }
            })
            history.addAll(listOf("at" to "first"))
            assertTrue(firstSave.await(5, TimeUnit.SECONDS))
            val report = CloudReportQueue(executor)
            assertTrue(report.change(
                commit = { committed.set(true); offCommitted.countDown(); true },
                settled = {},
            ))
            release.countDown()
            assertTrue(offCommitted.await(5, TimeUnit.SECONDS))
            assertTrue(secondSave.await(5, TimeUnit.SECONDS))
            assertTrue(history.snapshot().contains("next-1"))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun blockedStorageDoesNotHoldControllerAndEventsAreCoalesced() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val done = CountDownLatch(1)
            val saves = AtomicInteger()
            val written = mutableListOf<String>()
            val queue = CloudCustomHistoryQueue(
                executor = executor,
                load = { "old entry" },
                save = { text ->
                    if (saves.incrementAndGet() == 1) {
                        started.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                    }
                    written += text
                    if (text.contains("latest")) done.countDown()
                },
            )
            queue.addAll(listOf("at" to "first"))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            val returned = CountDownLatch(1)
            Thread {
                repeat(100) { queue.addAll(listOf("at" to "event-$it")) }
                queue.addAll(listOf("at" to "latest"))
                returned.countDown()
            }.start()
            assertTrue("controller waited for AtomicFile", returned.await(1, TimeUnit.SECONDS))
            assertTrue(queue.snapshot().contains("latest"))
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(2, saves.get())
            assertTrue(written.last().contains("old entry"))
            assertTrue(written.last().contains("first"))
            assertTrue(written.last().contains("latest"))
        } finally {
            executor.shutdownNow()
        }
    }
}
