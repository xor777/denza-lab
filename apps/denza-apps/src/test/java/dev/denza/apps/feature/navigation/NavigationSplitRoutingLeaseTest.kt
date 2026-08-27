package dev.denza.apps.feature.navigation

import dev.denza.apps.TaskMoveOwner
import dev.denza.apps.TaskMoveOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationSplitRoutingLeaseTest {

    private var now = 0L
    private val ownership = TaskMoveOwnership { now }

    @Test
    fun `acquire and release are idempotent`() {
        val lease = NavigationSplitRoutingLease(ownership)

        lease.acquire()
        lease.acquire()
        assertEquals(TaskMoveOwner.NAVIGATION, ownership.holder())

        lease.release()
        lease.release()
        assertNull(ownership.holder())
    }

    @Test
    fun `lease can be acquired again after release`() {
        val lease = NavigationSplitRoutingLease(ownership)

        lease.acquire()
        lease.release()
        lease.acquire()

        assertEquals(TaskMoveOwner.NAVIGATION, ownership.holder())
    }

    /**
     * Отпускает владение колбэк чужой подсистемы, и колбэк может не прийти. Прежний `hold` срока не
     * имел вовсе: потерянный `release` выключал фоновую сверку до конца процесса.
     */
    @Test
    fun `a release that never comes expires instead of holding for ever`() {
        NavigationSplitRoutingLease(ownership).acquire()

        now += TaskMoveOwnership.HANDOFF_MS

        assertNull(ownership.holder())
    }
}
