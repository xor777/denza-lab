package dev.denza.apps.feature.cluster

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraReadyNotificationTest {
    @Test fun firstFrameDoesNotPerformNotificationInline() {
        val f = Fixture()
        f.subject.afterFirstFrame(1L) { f.published++ }
        assertEquals(0, f.published)
        assertEquals(1, f.queue.size)
        f.queue.removeAt(0).invoke()
        assertEquals(1, f.published)
    }

    @Test fun hideOrNewShowDropsTheOldNotification() {
        val f = Fixture()
        f.subject.afterFirstFrame(1L) { f.published++ }
        f.generation = 2L
        f.queue.removeAt(0).invoke()
        assertEquals(0, f.published)
    }

    @Test fun failureBeforeTheQueuedWorkDropsItEvenWithoutGenerationChange() {
        val f = Fixture()
        f.subject.afterFirstFrame(1L) { f.published++ }
        f.ready = false
        f.queue.removeAt(0).invoke()
        assertEquals(0, f.published)
    }

    @Test fun aNotificationExceptionCannotFailTheCameraCallback() {
        val f = Fixture()
        f.subject.afterFirstFrame(1L) { throw SecurityException("fixture") }
        f.queue.removeAt(0).invoke()
        assertEquals(1, f.failures)
    }

    private class Fixture {
        val queue = mutableListOf<() -> Unit>()
        var generation = 1L
        var ready = true
        var published = 0
        var failures = 0
        val subject = CameraReadyNotification(
            post = { queue.add(it) },
            isCurrentReady = { it == generation && ready },
            onFailure = { failures++ },
        )
    }
}
