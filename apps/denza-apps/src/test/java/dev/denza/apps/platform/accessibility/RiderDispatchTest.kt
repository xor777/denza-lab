package dev.denza.apps.platform.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared service's dispatch on a fake host: which rider gets which event, in which order a key
 * is offered and who ends the round, that a rider that throws costs only itself, and connect and
 * going.
 */
class RiderDispatchTest {
    private data class Event(val type: Int, val pkg: String?)
    private data class Key(val code: Int, val action: Int, val repeat: Int = 0)

    private val heard = mutableListOf<String>()
    private val failures = mutableListOf<String>()

    private open inner class Fake(
        override val name: String,
        override val eventTypes: Int = 0,
        override val eventPackages: Set<String>? = null,
        override val takesKeys: Boolean = false,
        private val consumes: (Key) -> Boolean = { false },
    ) : Rider<String, Event> {
        override fun onConnected(service: String) {
            heard += "$name connect $service"
        }

        override fun onEvent(event: Event) {
            heard += "$name event ${event.type}"
        }

        override fun onKeyEvent(keyCode: Int, action: Int, repeatCount: Int): Boolean {
            heard += "$name key $keyCode/$action"
            return consumes(Key(keyCode, action, repeatCount))
        }

        override fun onDisconnected(service: String) {
            heard += "$name gone $service"
        }
    }

    /** Owns a whole press once it took its DOWN, as the wheel's interceptors do. */
    private inner class Pressing(name: String, private val code: Int, private val accepts: () -> Boolean) :
        Fake(name, takesKeys = true) {
        private var owned = false

        override fun onKeyEvent(keyCode: Int, action: Int, repeatCount: Int): Boolean {
            heard += "$name key $keyCode/$action"
            if (keyCode != code) return false
            if (action == DOWN && repeatCount == 0) owned = accepts()
            val consume = owned
            if (action == UP) owned = false
            return consume
        }
    }

    private inner class Throwing(name: String, eventTypes: Int = ALL, takesKeys: Boolean = false) :
        Fake(name, eventTypes = eventTypes, takesKeys = takesKeys) {
        override fun onConnected(service: String) = error("$name broke")
        override fun onEvent(event: Event) = error("$name broke")
        override fun onKeyEvent(keyCode: Int, action: Int, repeatCount: Int): Boolean = error("$name broke")
        override fun onDisconnected(service: String) = error("$name broke")
    }

    private fun dispatch(vararg riders: Rider<String, Event>) =
        RiderDispatch(riders.toList()) { rider, call, _ -> failures += "$rider $call" }

    private fun RiderDispatch<String, Event>.event(event: Event) = event(event, event.type, event.pkg)

    private fun RiderDispatch<String, Event>.key(key: Key) = key(key.code, key.action, key.repeat)

    @Test
    fun `an event goes to the riders that take its type and package, in their order`() {
        val dispatch = dispatch(
            Fake("state", eventTypes = STATE),
            Fake("weather", eventTypes = ALL, eventPackages = setOf("com.byd.weatherdata")),
            Fake("all", eventTypes = ALL),
            Fake("keys only", takesKeys = true),
            Fake("windows", eventTypes = STATE or WINDOWS or CONTENT),
        )

        dispatch.event(Event(STATE, "com.byd.weatherdata"))
        dispatch.event(Event(CONTENT, "ru.yandex.yandexnavi"))
        dispatch.event(Event(WINDOWS, null))
        dispatch.event(Event(CLICKED, "com.byd.weatherdata"))

        assertEquals(
            listOf(
                "state event $STATE", "weather event $STATE", "all event $STATE", "windows event $STATE",
                "all event $CONTENT", "windows event $CONTENT",
                "all event $WINDOWS", "windows event $WINDOWS",
                "weather event $CLICKED", "all event $CLICKED",
            ),
            heard,
        )
    }

    @Test
    fun `a package filter never takes an event with no package`() {
        val weather = Fake("weather", eventTypes = ALL, eventPackages = setOf("com.byd.weatherdata"))

        assertFalse(RiderDispatch.wants(weather, STATE, null))
        assertFalse(RiderDispatch.wants(weather, STATE, "com.byd.weatherdatax"))
        assertTrue(RiderDispatch.wants(weather, STATE, "com.byd.weatherdata"))
        assertFalse(RiderDispatch.wants(Fake("none"), STATE, "com.byd.weatherdata"))
    }

    @Test
    fun `a key goes to the key riders in order and the first that consumes it ends the round`() {
        val dispatch = dispatch(
            Fake("events", eventTypes = ALL),
            Fake("first", takesKeys = true) { it.code == 386 },
            Fake("second", takesKeys = true) { it.code == 321 || it.code == 386 },
        )

        assertTrue(dispatch.key(Key(386, DOWN)))
        assertTrue(dispatch.key(Key(321, DOWN)))
        assertFalse(dispatch.key(Key(87, DOWN)))

        assertEquals(
            listOf(
                "first key 386/$DOWN",
                "first key 321/$DOWN", "second key 321/$DOWN",
                "first key 87/$DOWN", "second key 87/$DOWN",
            ),
            heard,
        )
    }

    /** As today: a consumed DOWN's UP is consumed too, by the same rider, and no later rider sees either half. */
    @Test
    fun `a consumed DOWN also consumes its UP and the later rider sees neither`() {
        var mediaAccepts = true
        val dispatch = dispatch(
            Pressing("media", 386) { mediaAccepts },
            Pressing("star", 321) { true },
        )

        assertTrue(dispatch.key(Key(386, DOWN)))
        assertTrue(dispatch.key(Key(386, DOWN, repeat = 1)))
        mediaAccepts = false // the guard changing mid-press does not let the UP through
        assertTrue(dispatch.key(Key(386, UP)))
        assertEquals(listOf("media key 386/$DOWN", "media key 386/$DOWN", "media key 386/$UP"), heard)

        heard.clear()
        assertFalse("a refused press goes on, both halves", dispatch.key(Key(386, DOWN)))
        assertFalse(dispatch.key(Key(386, UP)))
        assertEquals(
            listOf("media key 386/$DOWN", "star key 386/$DOWN", "media key 386/$UP", "star key 386/$UP"),
            heard,
        )

        heard.clear()
        assertTrue(dispatch.key(Key(321, DOWN)))
        assertTrue(dispatch.key(Key(321, UP)))
        assertEquals(
            listOf("media key 321/$DOWN", "star key 321/$DOWN", "media key 321/$UP", "star key 321/$UP"),
            heard,
        )
    }

    @Test
    fun `a rider that throws costs only itself`() {
        val dispatch = dispatch(
            Fake("before", eventTypes = ALL, takesKeys = true),
            Throwing("broken", takesKeys = true),
            Fake("after", eventTypes = ALL, takesKeys = true) { it.code == 321 },
        )

        dispatch.connected("svc")
        dispatch.event(Event(STATE, "x"))
        val consumed = dispatch.key(Key(321, DOWN))
        dispatch.disconnected("svc")

        assertTrue("the key went on past the broken rider", consumed)
        assertEquals(
            listOf(
                "before connect svc", "after connect svc",
                "before event $STATE", "after event $STATE",
                "before key 321/$DOWN", "after key 321/$DOWN",
                "before gone svc", "after gone svc",
            ),
            heard,
        )
        assertEquals(listOf("broken connect", "broken event", "broken key", "broken disconnect"), failures)
    }

    @Test
    fun `connect and going reach every rider in order, with the service`() {
        val dispatch = dispatch(Fake("a"), Fake("b", takesKeys = true), Fake("c", eventTypes = ALL))

        dispatch.connected("svc")
        dispatch.disconnected("svc")
        dispatch.disconnected("svc")

        assertEquals(
            listOf(
                "a connect svc", "b connect svc", "c connect svc",
                "a gone svc", "b gone svc", "c gone svc",
                "a gone svc", "b gone svc", "c gone svc",
            ),
            heard,
        )
        assertTrue(failures.isEmpty())
    }

    @Test
    fun `a rider is found by its type`() {
        val pressing = Pressing("media", 386) { true }
        val dispatch = dispatch(Fake("a"), pressing)

        assertSame(pressing, dispatch.rider(Pressing::class.java))
        assertNull(dispatch.rider(String::class.java))
    }

    private companion object {
        // AccessibilityEvent's bits, by value: the test runs without Android.
        const val STATE = 0x20
        const val CONTENT = 0x800
        const val WINDOWS = 0x400000
        const val CLICKED = 0x1
        const val ALL = -1
        const val DOWN = 0
        const val UP = 1
    }
}
