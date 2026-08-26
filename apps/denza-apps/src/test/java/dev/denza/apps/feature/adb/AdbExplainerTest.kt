package dev.denza.apps.feature.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The explainer's copy, and the counter that opens the service screen from inside it.
 *
 * What this cannot check is the half that matters most - that a finger reaches the title while the
 * gate's shield is up. That is a windowing fact and there is no Compose in these tests: the sheet is
 * a `Dialog`, so it is a window of its own above the activity, and the shield is a `Box` inside the
 * activity's window. What is checked here is the half that a mistake could plausibly break later:
 * that the door is offered by every state that blocks, and that seven taps are seven taps.
 */
class AdbExplainerTest {

    @Test
    fun `the door and the window it opens are called the same thing`() {
        // The label on the gate is the title of the window it opens, and the title is the surface
        // the seven taps live on. A driver who pressed "Что такое ADB" should be looking at "Что
        // такое ADB"; renaming one of the two is how a window quietly becomes somewhere else.
        assertEquals("Что такое ADB", AdbExplainer.OPEN_LABEL)
        assertEquals(AdbExplainer.OPEN_LABEL, AdbExplainer.TITLE)
    }

    @Test
    fun `neither paragraph blames anyone or reports an error`() {
        // The state is lawful: the app is honestly powerless until a person does something at the
        // car. An "ошибка"/"не удалось"/"сбой" here would be an unfinished recipe dressed as news.
        listOf(AdbExplainer.WHAT_IT_IS, AdbExplainer.HOW_TO_OPEN).forEach { paragraph ->
            listOf("ошибк", "сбой", "не удалось", "к сожалению").forEach { word ->
                assertFalse(
                    "\"$paragraph\" contains \"$word\"",
                    paragraph.contains(word, ignoreCase = true),
                )
            }
        }
    }

    @Test
    fun `the second paragraph says where the channel is actually opened`() {
        // The one actionable sentence in the window. If it ever stops naming the service, the
        // window has become decoration.
        assertTrue(AdbExplainer.HOW_TO_OPEN.contains("сервис", ignoreCase = true))
        assertTrue(AdbExplainer.HOW_TO_OPEN.contains("диагностическ", ignoreCase = true))
    }

    @Test
    fun `it is seven taps, and they are quick`() {
        // Written out rather than read off the constant, because every other test here is phrased
        // in terms of the constant and would happily follow it to six. Seven within three seconds
        // is the gesture the owner asked for; it is a specification, not an implementation detail.
        assertEquals(7, AdbExplainer.SERVICE_TAPS)
        assertEquals(3_000L, AdbExplainer.SERVICE_TAP_WINDOW_MS)

        val taps = ServiceEntryTaps()
        val opened = (0..6).map { taps.tap(10_000L + it * 400L) }

        assertEquals(
            listOf(false, false, false, false, false, false, true),
            opened,
        )
    }

    @Test
    fun `six taps are not seven`() {
        val taps = ServiceEntryTaps()

        repeat(AdbExplainer.SERVICE_TAPS - 1) { index ->
            assertFalse("tap ${index + 1} opened the door", taps.tap(1_000L + index * 100L))
        }
    }

    @Test
    fun `the seventh tap opens the door`() {
        val taps = ServiceEntryTaps()

        val opened = (0 until AdbExplainer.SERVICE_TAPS).map { taps.tap(1_000L + it * 100L) }

        assertEquals(List(AdbExplainer.SERVICE_TAPS - 1) { false } + true, opened)
    }

    @Test
    fun `the run starts over after it opens`() {
        // Otherwise every further tap on the title reopens the service screen, which on a car means
        // a thumb resting on the header reopens it as fast as it can be dismissed.
        val taps = ServiceEntryTaps()
        repeat(AdbExplainer.SERVICE_TAPS) { taps.tap(1_000L + it * 100L) }

        assertFalse(taps.tap(1_700L))
    }

    @Test
    fun `a slow tap starts a new run rather than continuing the old one`() {
        val taps = ServiceEntryTaps()
        repeat(AdbExplainer.SERVICE_TAPS - 1) { taps.tap(1_000L + it * 100L) }

        val late = 1_000L + (AdbExplainer.SERVICE_TAPS - 2) * 100L +
            AdbExplainer.SERVICE_TAP_WINDOW_MS + 1L
        assertFalse("a tap outside the window continued the run", taps.tap(late))

        // ...and that late tap is the first of the new run, not a discarded one.
        val opened = (1 until AdbExplainer.SERVICE_TAPS).map { taps.tap(late + it * 100L) }
        assertEquals(List(AdbExplainer.SERVICE_TAPS - 2) { false } + true, opened)
    }

    @Test
    fun `a gate is fresh whatever the clock says`() {
        // The counter's last-tap time starts at zero, so a clock reading near the epoch is the one
        // input where "is this tap part of the run before it" is asked with no run before it. It
        // still takes the full count.
        val taps = ServiceEntryTaps(required = 2)

        assertFalse(taps.tap(0L))
        assertTrue(taps.tap(1L))
    }

    @Test
    fun `a tap exactly at the edge of the window still belongs to the run`() {
        // The window is a grace period, not a race: the tap that arrives at the last millisecond of
        // it counts. Without this the boundary is whatever the comparison happens to say.
        val taps = ServiceEntryTaps(required = 2)
        taps.tap(1_000L)

        assertTrue(taps.tap(1_000L + AdbExplainer.SERVICE_TAP_WINDOW_MS))
    }
}
