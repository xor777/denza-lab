package dev.denza.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceInstanceHopTest {
    private class FakeService

    /** The service's main thread: calls wait here until the test lets it run. */
    private val mainQueue = ArrayDeque<Runnable>()
    private val poster = ServiceInstanceHop.Poster<FakeService> { _, call -> mainQueue.add(call) }
    private var bound: FakeService? = null

    private fun drainMain() {
        while (mainQueue.isNotEmpty()) mainQueue.removeFirst().run()
    }

    @Test
    fun `a call from another thread runs on the service thread, not in place`() {
        val service = FakeService().also { bound = it }
        val ran = mutableListOf<FakeService>()

        assertTrue(ServiceInstanceHop.post({ bound }, poster) { ran += it })

        assertTrue("the caller's thread must not touch the monitor", ran.isEmpty())
        drainMain()
        assertEquals(listOf(service), ran)
    }

    @Test
    fun `a call for an instance that was unbound before it ran is dropped`() {
        bound = FakeService()
        val ran = mutableListOf<FakeService>()
        ServiceInstanceHop.post({ bound }, poster) { ran += it }

        bound = null
        drainMain()

        assertTrue(ran.isEmpty())
    }

    @Test
    fun `a call for an instance that was replaced does not reach the new one`() {
        bound = FakeService()
        val ran = mutableListOf<FakeService>()
        ServiceInstanceHop.post({ bound }, poster) { ran += it }

        bound = FakeService()
        drainMain()

        assertTrue(ran.isEmpty())
    }

    @Test
    fun `with no instance bound nothing is queued`() {
        bound = null

        assertFalse(ServiceInstanceHop.post({ bound }, poster) { error("must not run") })
        assertTrue(mainQueue.isEmpty())
    }
}
