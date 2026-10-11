package dev.denza.apps.feature.alice

import dev.denza.apps.platform.accessibility.AccessibilityKeyEvent
import org.junit.Assert.*
import org.junit.Test

class AliceVoiceKeyControllerTest {
    private var now = 1_000L
    private var visibleWindow: Int? = 295
    private var buttonAvailable = true
    private var clicks = 0
    private var postsThrow = false
    private var readsThrow = false
    private var reveals = 0
    private var revealAccepted = true
    private var gestureCompleted: ((Boolean) -> Unit)? = null
    private val delayed = mutableListOf<Pair<Long, () -> Unit>>()
    private val queued = mutableListOf<() -> Unit>()
    private val controller = AliceVoiceKeyController(
        clock = { now },
        visibleNavigator = { if (readsThrow) error("no windows") else visibleWindow },
        post = { if (postsThrow) error("looper gone") else queued.add(it) },
        clickIfVisible = { window ->
            (visibleWindow == window && buttonAvailable).also { if (it) clicks++ }
        },
        report = { error("diagnostics must not change key ownership") },
        postDelayed = { delay, work -> delayed.add(now + delay to work) },
        revealIfVisible = { window, complete ->
            if (visibleWindow == window && revealAccepted) {
                reveals++
                gestureCompleted = complete
                true
            } else false
        },
    )

    private fun key(action: Int, repeat: Int = 0, down: Long = 1_000L, device: Int = 2,
                    canceled: Boolean = false, at: Long = now, code: Int = 320) =
        controller.onKeyEvent(AccessibilityKeyEvent(code, action, repeat, device, down, at, canceled))

    private fun drain() {
        val work = queued.toList()
        queued.clear()
        work.forEach { it() }
    }

    private fun advance(ms: Long) {
        now += ms
        val due = delayed.filter { it.first <= now }
        delayed.removeAll(due.toSet())
        due.forEach { it.second() }
        drain()
    }

    @Test fun `hidden route trace reveals once then clicks when microphone appears after 205 ms`() {
        // Car trace: speedview_guidance touch -> guidance_open_voice_search after 205 ms,
        // session 393 recording MIC at +0.3, +1.5 and +3.5 seconds.
        buttonAvailable = false
        key(0); key(1); drain()
        assertEquals(1, reveals)
        assertEquals(0, clicks)
        advance(205)
        buttonAvailable = true
        gestureCompleted!!(true)
        drain()
        advance(50)
        assertEquals(1, clicks)
        advance(2_000)
        assertEquals(1, clicks)
    }

    @Test fun `second press during reveal is consumed without another gesture or click`() {
        buttonAvailable = false
        key(0); key(1); drain()
        advance(40)
        assertTrue(key(0, down = now)); assertTrue(key(1, down = now)); drain()
        assertEquals(1, reveals)
        buttonAvailable = true
        gestureCompleted!!(true); drain(); advance(50)
        assertEquals(1, clicks)
    }

    @Test fun `gesture cancellation or rejected dispatch does not click or retry gesture`() {
        buttonAvailable = false
        key(0); key(1); drain()
        gestureCompleted!!(false)
        buttonAvailable = true
        advance(2_000)
        assertEquals(0, clicks)
        buttonAvailable = false
        revealAccepted = false
        key(0, down = now); key(1, down = now); drain()
        assertEquals(1, reveals)
    }

    @Test fun `missing control after reveal times out and allows a later fresh press`() {
        buttonAvailable = false
        key(0); key(1); drain()
        gestureCompleted!!(true)
        repeat(31) { advance(50) }
        assertEquals(0, clicks)
        assertEquals(1, reveals)
        buttonAvailable = true
        key(0, down = now); key(1, down = now); drain()
        assertEquals(1, clicks)
    }

    @Test fun `lost gesture callback times out and late completion cannot click`() {
        buttonAvailable = false
        key(0); key(1); drain()
        advance(1_600)
        buttonAvailable = true
        gestureCompleted!!(true); drain()
        assertEquals(0, clicks)
        key(0, down = now); key(1, down = now); drain()
        assertEquals(1, clicks)
    }

    @Test fun `Navigator hidden or detach while revealing prevents the delayed microphone click`() {
        buttonAvailable = false
        key(0); key(1); drain()
        visibleWindow = null
        buttonAvailable = true
        gestureCompleted!!(true); drain(); advance(50)
        assertEquals(0, clicks)
        visibleWindow = 295
        buttonAvailable = false
        key(0, down = now); key(1, down = now); drain()
        controller.close()
        buttonAvailable = true
        gestureCompleted!!(true); drain(); advance(2_000)
        assertEquals(0, clicks)
    }

    @Test fun `Navigator disappears and returns during gesture but old invocation stays canceled`() {
        buttonAvailable = false
        key(0); key(1); drain()
        visibleWindow = null
        controller.onWindowChanged()
        visibleWindow = 295
        buttonAvailable = true
        gestureCompleted!!(true); drain(); advance(50)
        assertEquals(0, clicks)
        key(0, down = now); key(1, down = now); drain()
        assertEquals(1, clicks)
    }

    @Test fun `physical 320 consumes both halves and clicks only after callback returns`() {
        assertTrue(key(0))
        assertTrue(key(0, repeat = 1))
        assertTrue(key(1))
        assertEquals(0, clicks)
        drain()
        assertEquals(1, clicks)
    }

    @Test fun `hidden Navigator keeps stock DOWN and UP`() {
        visibleWindow = null
        assertFalse(key(0))
        visibleWindow = 295
        assertFalse(key(1))
        drain()
        assertEquals(0, clicks)
    }

    @Test fun `Navigator hidden during held press consumes UP but cancels Alice`() {
        assertTrue(key(0))
        visibleWindow = null
        assertTrue(key(1))
        drain()
        assertEquals(0, clicks)
    }

    @Test fun `window replacement after UP but before queued click cancels it`() {
        key(0); key(1)
        visibleWindow = 99
        drain()
        assertEquals(0, clicks)
    }

    @Test fun `missing microphone inside Navigator does not start BYD`() {
        buttonAvailable = false
        assertTrue(key(0)); assertTrue(key(1))
        drain()
        assertEquals(0, clicks)
    }

    @Test fun `successive presses can invoke Alice again without rearming`() {
        key(0); key(1); drain()
        now += 2_000
        assertTrue(key(0, down = now)); assertTrue(key(1, down = now)); drain()
        assertEquals(2, clicks)
    }

    @Test fun `rapid second press cannot queue a second click before first completes`() {
        key(0); key(1)
        now++
        assertTrue(key(0, down = now)); assertTrue(key(1, down = now))
        drain()
        assertEquals(1, clicks)
    }

    @Test fun `another device cannot release an owned physical press`() {
        assertTrue(key(0))
        assertFalse(key(1, device = 3))
        assertTrue(key(1))
        drain()
        assertEquals(1, clicks)
    }

    @Test fun `canceled release stays consumed without a click`() {
        key(0)
        assertTrue(key(1, canceled = true))
        drain()
        assertEquals(0, clicks)
    }

    @Test fun `detach fences queued clicks and stops interception`() {
        key(0); key(1)
        controller.close()
        drain()
        assertEquals(0, clicks)
        assertFalse(key(0))
    }

    @Test fun `broken posting cannot leak a consumed UP to stock`() {
        key(0)
        postsThrow = true
        assertTrue(key(1))
        assertEquals(0, clicks)
    }

    @Test fun `unavailable window list leaves a new press to stock`() {
        readsThrow = true
        assertFalse(key(0)); assertFalse(key(1))
    }

    @Test fun `late events and queued work cannot race firmware timeout`() {
        assertFalse(key(0, at = now - 501))
        assertFalse(key(1))
        key(0); key(1)
        now += 501
        drain()
        assertEquals(0, clicks)
    }

    @Test fun `late owned UP is consumed but does not launch Alice`() {
        key(0)
        assertTrue(key(1, at = now - 501))
        drain()
        assertEquals(0, clicks)
    }

    @Test fun `star media and Android assist codes remain untouched`() {
        for (code in listOf(321, 328, 386, 85, 126, 127, 219, 231)) {
            assertFalse(key(0, code = code)); assertFalse(key(1, code = code))
        }
        assertEquals(0, clicks)
    }
}
