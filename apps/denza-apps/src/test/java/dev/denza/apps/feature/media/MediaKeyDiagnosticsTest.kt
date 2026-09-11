package dev.denza.apps.feature.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The support report is the only witness a remote car has for the wheel's Play/Pause key.
 *
 * `Log.i` under `DenzaMediaResume` does not reach that owner - this firmware ships a global
 * `log.tag=M` - and an owner without host ADB cannot read logcat anyway. Every press the filter
 * refuses goes back to stock routing, whose Play fallback opens the stock local player, and that
 * refusal currently leaves no trace at all. These lines are that trace.
 */
class MediaKeyDiagnosticsTest {
    @Before
    fun reset() {
        MediaKeyDiagnostics.clearForTest()
    }

    /** Twelve is what fits on one readable line; the thirteenth press must push the first out. */
    @Test
    fun `the ring keeps the last twelve entries oldest first`() {
        val ring = MediaKeyRing(MediaKeyDiagnostics.CAPACITY)
        (1..20).forEach { index ->
            ring.add(MediaKeyPress(index.toLong(), 386, true, "p$index"))
        }

        val kept = ring.snapshot()

        assertEquals(12, kept.size)
        assertEquals("p9", kept.first().detail)
        assertEquals("p20", kept.last().detail)
        assertEquals((9..20).map { "p$it" }, kept.map { it.detail })
    }

    /** A report that scrolls is a log. The whole ring is one line, however full it is. */
    @Test
    fun `a full ring is still a single line`() {
        val ring = MediaKeyRing(MediaKeyDiagnostics.CAPACITY)
        (1..20).forEach { index ->
            ring.add(MediaKeyPress(index.toLong(), 386, false, "no-target"))
        }

        val line = MediaKeyReport.presses(ring.snapshot(), STAMP)

        assertFalse(line, line.contains('\n'))
        assertEquals(12, line.split("; ").size)
    }

    @Test
    fun `an entry names the time, the code, the verdict and the outcome`() {
        val rendered = MediaKeyReport.presses(
            listOf(
                MediaKeyPress(1L, 386, true, "ru.yandex.music play"),
                MediaKeyPress(2L, 386, false, "no-target"),
                MediaKeyPress(3L, 334, false, "not-media"),
                MediaKeyPress(4L, null, false, "pause-preparation"),
            ),
            STAMP,
        )

        assertEquals(
            "12:03:41 386 ✓ ru.yandex.music play; " +
                "12:05:02 386 ✗ no-target; " +
                "12:05:10 334 ✗ not-media; " +
                "12:05:11 ✗ pause-preparation",
            rendered,
        )
    }

    @Test
    fun `a car whose wheel button never reached us says so instead of printing nothing`() {
        assertEquals("нет", MediaKeyReport.presses(emptyList(), STAMP))
    }

    @Test
    fun `the report is three lines and no more`() {
        val lines = MediaKeyReport.lines(
            MediaKeySnapshot(
                state = MediaKeyState.LISTENING,
                rememberedPackage = "ru.yandex.music",
                presses = listOf(MediaKeyPress(1L, 386, true, "ru.yandex.music play")),
            ),
            STAMP,
        )

        assertEquals(
            listOf(
                "Кнопка play/pause=слушает",
                "Запомненная сессия=ru.yandex.music",
                "Последние нажатия=12:03:41 386 ✓ ru.yandex.music play",
            ),
            lines,
        )
    }

    @Test
    fun `nothing remembered is a word, not an empty value`() {
        val lines = MediaKeyReport.lines(
            MediaKeySnapshot(MediaKeyState.SERVICE_ABSENT, null, emptyList()),
            STAMP,
        )

        assertEquals("Запомненная сессия=нет", lines[1])
    }

    /**
     * The three answers the first line has to tell apart: we are filtering the key, we are bound
     * but the session read failed, and nothing of ours is running at all.
     */
    @Test
    fun `the first line distinguishes listening from lost access from no service`() {
        assertEquals("слушает", MediaKeyState.LISTENING.label)
        assertEquals("нет доступа к сессиям", MediaKeyState.NO_SESSION_ACCESS.label)
        assertEquals("сервис не подключён", MediaKeyState.SERVICE_ABSENT.label)
        assertEquals(3, MediaKeyState.entries.map { it.label }.toSet().size)
    }

    @Test
    fun `the state comes from the controller's own flag, not from a second one`() {
        assertEquals(MediaKeyState.SERVICE_ABSENT, MediaKeyDiagnostics.snapshot(null, null).state)
        assertEquals(MediaKeyState.LISTENING, MediaKeyDiagnostics.snapshot(true, null).state)
        assertEquals(
            MediaKeyState.NO_SESSION_ACCESS,
            MediaKeyDiagnostics.snapshot(false, null).state,
        )
    }

    /**
     * The N9 case the lines exist for: that wheel may emit a code we never intercept, and a ring
     * that only held 85/126/127/386 would look exactly like a car whose button emits nothing.
     */
    @Test
    fun `a code we do not intercept is recorded as such`() {
        assertEquals(
            "not-media",
            MediaKeyDetail.press(null, null, media = false, allowed = true, listening = true),
        )
    }

    @Test
    fun `a refused press names the guard that refused it`() {
        val refused = MediaKeyGuard.entries.filter { it != MediaKeyGuard.ALLOWED }

        refused.forEach { guard ->
            assertEquals(
                guard.name,
                guard.label,
                MediaKeyDetail.press(null, guard, media = true, allowed = false, listening = true),
            )
        }
        assertEquals(
            listOf("audio-mode", "stream-mute", "vendor-mute", "in-call", "unavailable"),
            refused.map { it.label },
        )
    }

    @Test
    fun `a press the filter never acted on says which step dropped it`() {
        assertEquals(
            "not-listening",
            MediaKeyDetail.press(
                null,
                MediaKeyGuard.ALLOWED,
                media = true,
                allowed = true,
                listening = false,
            ),
        )
        assertEquals(
            "already-down",
            MediaKeyDetail.press(
                null,
                MediaKeyGuard.ALLOWED,
                media = true,
                allowed = true,
                listening = true,
            ),
        )
        assertEquals(
            "no-target",
            MediaKeyDetail.press(
                "no-target",
                MediaKeyGuard.ALLOWED,
                media = true,
                allowed = true,
                listening = true,
            ),
        )
    }

    /** The outcome noted at a decision point belongs to that press and to no later one. */
    @Test
    fun `an outcome does not leak into the next press`() {
        MediaKeyDiagnostics.noteGuard(MediaKeyGuard.ALLOWED)
        MediaKeyDiagnostics.note("ru.yandex.music play")
        MediaKeyDiagnostics.recordPress(386, media = true, allowed = true, listening = true, consumed = true)
        MediaKeyDiagnostics.noteGuard(MediaKeyGuard.ALLOWED)
        MediaKeyDiagnostics.recordPress(386, media = true, allowed = true, listening = true, consumed = false)

        val presses = MediaKeyDiagnostics.snapshot(true, "ru.yandex.music").presses

        assertEquals(listOf("ru.yandex.music play", "already-down"), presses.map { it.detail })
        assertEquals(listOf(true, false), presses.map { it.handled })
        assertEquals(listOf(386, 386), presses.map { it.keyCode })
    }

    @Test
    fun `a refusing guard reaches the press it refused`() {
        MediaKeyDiagnostics.noteGuard(MediaKeyGuard.IN_CALL)
        MediaKeyDiagnostics.recordPress(386, media = true, allowed = false, listening = true, consumed = false)

        assertEquals("in-call", MediaKeyDiagnostics.snapshot(true, null).presses.single().detail)
    }

    /** The pause that finishes after its press has its own entry: there is no key code to name. */
    @Test
    fun `a deferred pause reports its own ending without a key code`() {
        MediaKeyDiagnostics.note("com.vk.vkvideo pause")
        MediaKeyDiagnostics.recordCompletion(null)
        MediaKeyDiagnostics.recordCompletion("pause-preparation")

        val presses = MediaKeyDiagnostics.snapshot(true, null).presses

        assertEquals(listOf(null, null), presses.map { it.keyCode })
        assertEquals(listOf("com.vk.vkvideo pause", "pause-preparation"), presses.map { it.detail })
        assertEquals(listOf(true, false), presses.map { it.handled })
    }

    @Test
    fun `the singleton ring holds the same twelve as any other`() {
        (1..20).forEach { index ->
            MediaKeyDiagnostics.note("p$index")
            MediaKeyDiagnostics.recordPress(386, media = true, allowed = true, listening = true, consumed = true)
        }

        val presses = MediaKeyDiagnostics.snapshot(true, null).presses

        assertEquals(12, presses.size)
        assertEquals((9..20).map { "p$it" }, presses.map { it.detail })
    }

    @Test
    fun `the stamp is the car's wall clock with no date`() {
        val stamp = MediaKeyReport.wallClock(1_757_000_000_000L)

        assertTrue(stamp, Regex("""\d{2}:\d{2}:\d{2}""").matches(stamp))
    }

    private companion object {
        /** Four fixed readings, so the rendering test does not depend on where the car is. */
        val STAMP: (Long) -> String = { millis ->
            listOf("12:03:41", "12:05:02", "12:05:10", "12:05:11")[(millis - 1).toInt() % 4]
        }
    }
}
