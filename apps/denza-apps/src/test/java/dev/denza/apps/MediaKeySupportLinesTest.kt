package dev.denza.apps

import dev.denza.apps.feature.cluster.CameraRuntimePhase
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import dev.denza.apps.feature.media.MediaKeyPress
import dev.denza.apps.feature.media.MediaKeyReport
import dev.denza.apps.feature.media.MediaKeySnapshot
import dev.denza.apps.feature.media.MediaKeyState
import dev.denza.apps.feature.mirrors.MirrorSide
import dev.denza.apps.feature.mirrors.SideCameraDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The media-key lines as the support report actually prints them.
 *
 * Sixty lines of one feature's log were taken out of this report once already; the key gets three
 * lines and keeps them, whatever the ring holds.
 */
class MediaKeySupportLinesTest {
    @Test
    fun `the media key lines reach the report verbatim`() {
        val report = SupportDiagnostics.render(header(), MediaKeyReport.lines(lostAccess(), STAMP))

        assertTrue(report, report.contains("Кнопка play/pause=нет доступа к сессиям"))
        assertTrue(report, report.contains("Запомненная сессия=ru.yandex.music"))
        assertTrue(
            report,
            report.contains(
                "Последние нажатия=12:05:10 334 ✗ not-media; 12:05:11 386 ✗ stream-mute",
            ),
        )
    }

    /** However many presses the ring holds, the report grows by exactly three lines. */
    @Test
    fun `a full ring adds three lines to the report, not thirteen`() {
        val quiet = SupportDiagnostics.render(header(), emptyList())
        val busy = SupportDiagnostics.render(header(), MediaKeyReport.lines(fullRing(), STAMP))

        assertEquals(3, busy.lines().size - quiet.lines().size)
        assertEquals(1, busy.lines().count { it.startsWith("Последние нажатия=") })
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

    private fun header() = SupportDiagnosticsHeader(
        versionName = "0.6.1",
        sdkLevel = 33,
        fingerprint = "denza/test/fingerprint",
        cameraRuntime = CameraRuntimeSnapshot(
            phase = CameraRuntimePhase.READY,
            side = MirrorSide.RIGHT,
            generation = 1,
            details = "",
        ),
        mirrorDetection = SideCameraDetection(
            recognizedSide = MirrorSide.RIGHT,
            avcCandidateBlocks = 0,
            unrecognizedCandidates = 0,
        ),
        simulcastRuntime = SimulcastRuntimeSnapshot(),
    )

    private companion object {
        val STAMP: (Long) -> String = { millis -> "12:05:%02d".format(millis + 9) }
    }
}
