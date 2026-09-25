package dev.denza.apps.feature.cloud

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class CloudMarkerPublicationTest {
    @Test fun blockedStorageDoesNotBlockOffAndStaleSnapshotCannotAuthorizeStart() {
        val lock = Any()
        val publisher = CloudMarkerPublication(lock)
        val writing = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        var generation = 1
        try {
            val publish = threads.submit<Boolean> {
                try {
                    publisher.publish({ generation }, {
                        writing.countDown()
                        check(finishWrite.await(5, TimeUnit.SECONDS))
                    }, { it == generation })
                    true
                } catch (_: IllegalStateException) { false }
            }
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            // UI can enter the settings monitor and enqueue/record OFF while FUSE is blocked.
            val off = threads.submit { synchronized(lock) { generation = 2 } }
            off.get(2, TimeUnit.SECONDS)
            finishWrite.countDown()
            assertFalse(publish.get(2, TimeUnit.SECONDS))
        } finally {
            finishWrite.countDown()
            threads.shutdownNow()
        }
    }
}
