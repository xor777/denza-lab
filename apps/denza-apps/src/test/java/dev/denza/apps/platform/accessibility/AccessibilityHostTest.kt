package dev.denza.apps.platform.accessibility

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityHostTest {
    private class Rider(val id: String) {
        val calls = mutableListOf<String>()
    }

    /** A bound service: its main thread is a queue the test drains. */
    private class Host(val rider: Rider) : RiderHost {
        val main = ArrayDeque<Runnable>()

        override fun <R : Any> rider(type: Class<R>): R? = if (type.isInstance(rider)) type.cast(rider) else null

        override fun post(call: Runnable) {
            main.add(call)
        }

        fun drain() {
            while (main.isNotEmpty()) main.removeFirst().run()
        }
    }

    private val first = Host(Rider("first"))
    private val second = Host(Rider("second"))

    @After
    fun unbindAll() {
        AccessibilityHost.unbind(first)
        AccessibilityHost.unbind(second)
    }

    @Test
    fun `connected while bound, and only the bound instance unbinds itself`() {
        assertFalse(AccessibilityHost.isConnected())
        AccessibilityHost.bind(first)
        assertTrue(AccessibilityHost.isConnected())

        AccessibilityHost.bind(second)
        AccessibilityHost.unbind(first) // the old instance destroyed late
        assertTrue("the newer, bound instance is still connected", AccessibilityHost.isConnected())
        assertSame(second.rider, AccessibilityHost.rider(Rider::class.java))

        AccessibilityHost.unbind(second)
        assertFalse(AccessibilityHost.isConnected())
        assertNull(AccessibilityHost.rider(Rider::class.java))
    }

    @Test
    fun `an instance the system created is on its way until it connects or goes`() {
        assertNull(AccessibilityHost.bindingForMs(10_000))

        AccessibilityHost.created(first, atMs = 10_000)
        assertEquals(400L, AccessibilityHost.bindingForMs(10_400))
        AccessibilityHost.bind(first)
        assertNull("connected: nothing is on its way", AccessibilityHost.bindingForMs(10_500))

        AccessibilityHost.created(second, atMs = 20_000)
        AccessibilityHost.unbind(first) // the old instance's going does not settle the new one
        assertEquals(100L, AccessibilityHost.bindingForMs(20_100))
        AccessibilityHost.unbind(second) // destroyed before it connected
        assertNull(AccessibilityHost.bindingForMs(20_200))
    }

    @Test
    fun `a call is carried to the main thread and runs with the rider`() {
        AccessibilityHost.bind(first)

        assertTrue(AccessibilityHost.post(Rider::class.java) { it.calls += "refresh" })
        assertTrue("not in place", first.rider.calls.isEmpty())
        first.drain()

        assertEquals(listOf("refresh"), first.rider.calls)
    }

    @Test
    fun `a call queued for a service that went meanwhile does not run`() {
        AccessibilityHost.bind(first)
        AccessibilityHost.post(Rider::class.java) { it.calls += "refresh" }
        AccessibilityHost.unbind(first)
        AccessibilityHost.bind(second)

        first.drain()

        assertTrue(first.rider.calls.isEmpty())
        assertTrue(second.rider.calls.isEmpty())
    }

    @Test
    fun `nothing is queued with no service bound`() {
        assertFalse(AccessibilityHost.post(Rider::class.java) { error("must not run") })
    }
}
