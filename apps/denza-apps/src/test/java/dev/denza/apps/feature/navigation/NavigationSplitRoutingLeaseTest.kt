package dev.denza.apps.feature.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationSplitRoutingLeaseTest {
    @Test
    fun `acquire and release are idempotent`() {
        var holds = 0
        var releases = 0
        val lease = NavigationSplitRoutingLease(
            hold = { holds += 1 },
            release = { releases += 1 },
        )

        lease.acquire()
        lease.acquire()
        lease.release()
        lease.release()

        assertEquals(1, holds)
        assertEquals(1, releases)
    }

    @Test
    fun `lease can be acquired again after release`() {
        var holds = 0
        var releases = 0
        val lease = NavigationSplitRoutingLease(
            hold = { holds += 1 },
            release = { releases += 1 },
        )

        lease.acquire()
        lease.release()
        lease.acquire()
        lease.release()

        assertEquals(2, holds)
        assertEquals(2, releases)
    }
}
