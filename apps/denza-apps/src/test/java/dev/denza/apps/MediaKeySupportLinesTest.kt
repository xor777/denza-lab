package dev.denza.apps

import dev.denza.apps.feature.media.MediaKeyPress
import dev.denza.apps.feature.media.MediaKeyReport
import dev.denza.apps.feature.media.MediaKeySnapshot
import dev.denza.apps.feature.media.MediaKeyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media-key lines as the support report actually prints them.
 *
 * Sixty lines of one feature's log were taken out of this report once already; the key gets four
 * lines and keeps them, whatever the ring holds.
 */
class MediaKeySupportLinesTest {
    @Test
    fun `the media key lines reach the report verbatim`() {
        val report = TechnicalReadings.render(
            listOf(
                SupportDiagnostics.mediaKeySection(
                    MediaKeyReport.lines(lostAccess(), STAMP, mode = "без правки фокуса (ступень 1)"),
                ),
            ),
        )

        assertTrue(report, report.contains("Кнопка play/pause=нет доступа к сессиям"))
        assertTrue(report, report.contains("Режим медиакнопки=без правки фокуса (ступень 1)"))
        assertTrue(report, report.contains("Запомненная сессия=ru.yandex.music"))
        assertTrue(
            report,
            report.contains(
                "Последние нажатия=12:05:10 334 ✗ not-media; 12:05:11 386 ✗ stream-mute",
            ),
        )
    }

    /** However many presses the ring holds, the key's section is exactly four readings. */
    @Test
    fun `a full ring is four readings in the report, not fourteen`() {
        val rows = SupportDiagnostics.mediaKeySection(MediaKeyReport.lines(fullRing(), STAMP)).rows

        assertEquals(4, rows.size)
        assertEquals(1, rows.count { it.key == "Последние нажатия" })
    }

    private fun lostAccess() = MediaKeySnapshot(
        state = MediaKeyState.NO_SESSION_ACCESS,
        rememberedPackage = "ru.yandex.music",
        presses = listOf(
            MediaKeyPress(1L, 334, false, "not-media"),
            MediaKeyPress(2L, 386, false, "stream-mute"),
        ),
    )

    private fun fullRing() = MediaKeySnapshot(
        state = MediaKeyState.LISTENING,
        rememberedPackage = "ru.yandex.music",
        presses = (1..12).map { MediaKeyPress(it.toLong(), 386, true, "ru.yandex.music play") },
    )

    private companion object {
        val STAMP: (Long) -> String = { millis -> "12:05:%02d".format(millis + 9) }
    }
}
