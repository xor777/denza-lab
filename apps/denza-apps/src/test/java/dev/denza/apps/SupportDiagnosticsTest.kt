package dev.denza.apps

import dev.denza.apps.feature.cluster.CameraRuntimePhase
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import dev.denza.apps.feature.mirrors.MirrorSide
import dev.denza.apps.feature.mirrors.SideCameraDetection
import dev.denza.apps.feature.trip.SpectrumSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportDiagnosticsTest {
    @Test
    fun `renders injected package build runtime and detector values`() {
        val report = SupportDiagnostics.render(
            SupportDiagnosticsHeader(
                versionName = "9.8.7-test",
                sdkLevel = 33,
                fingerprint = "denza/test/fingerprint",
                cameraRuntime = CameraRuntimeSnapshot(
                    phase = CameraRuntimePhase.READY,
                    side = MirrorSide.RIGHT,
                    generation = 12,
                    details = "avc ready",
                ),
                mirrorDetection = SideCameraDetection(
                    recognizedSide = MirrorSide.RIGHT,
                    avcCandidateBlocks = 4,
                    unrecognizedCandidates = 2,
                ),
                simulcastRuntime = SimulcastRuntimeSnapshot(
                    rootsFound = 10,
                    rootsMissing = 3,
                    geometryParseMisses = 2,
                    unstableSamples = 7,
                    appliedRelayouts = 5,
                    semanticWindowRebuilds = 1,
                ),
            ),
            bodyLines = listOf("Проверка=готова"),
        )

        assertTrue(report.contains("Версия=9.8.7-test"))
        assertTrue(report.contains("SDK=33"))
        assertTrue(report.contains("Fingerprint=denza/test/fingerprint"))
        assertTrue(report.contains("AVC runtime=phase=READY; side=RIGHT; generation=12; details=avc ready"))
        assertTrue(report.contains("AVC detector=side=RIGHT; candidates=4; unrecognized=2"))
        assertTrue(report.contains("Simulcast counters=roots found=10; roots missing=3"))
        assertTrue(report.contains("relayouts=5; semantic rebuilds=1"))
    }

    /**
     * Три состояния анализатора, которые снаружи выглядят одинаково - неподвижные столбики, - и
     * ради различения которых строка вообще существует.
     *
     * Живой случай 27.08.2026 - третий: захват запрошен, эффект создан, но включить его нам не дали,
     * потому что управлять общим эффектом сессии 0 может только тот, кто создал его первым. Отличить
     * это от тишины в машине было нечем, и диагноз пришлось ставить дампами `media.audio_flinger`.
     */
    @Test
    fun `the spectrum line tells the three silences apart`() {
        val quietCar = SupportDiagnostics.spectrumLabel(
            spectrum(effectEnabled = true, sinceLastFrameMs = 40L),
        )
        val takenEffect = SupportDiagnostics.spectrumLabel(
            spectrum(effectEnabled = false, lastFailure = "эффект сессии 0 занят другим владельцем"),
        )
        val neverDelivered = SupportDiagnostics.spectrumLabel(
            spectrum(effectEnabled = true, sinceLastFrameMs = null),
        )

        assertTrue(quietCar, quietCar.contains("эффект=включён") && quietCar.contains("40 мс назад"))
        assertTrue(takenEffect, takenEffect.contains("эффект=ВЫКЛЮЧЕН"))
        assertTrue(takenEffect, takenEffect.contains("занят другим владельцем"))
        assertTrue(neverDelivered, neverDelivered.contains("кадр=не приходил"))
        assertEquals(
            "три разные причины молчания - три разные строки",
            3,
            setOf(quietCar, takenEffect, neverDelivered).size,
        )
    }

    /** Отчёт спрашивает хаб, а не строит его: панель могли ни разу не открыть. */
    @Test
    fun `the spectrum line says so when the panel was never opened`() {
        assertEquals("панель не открывалась", SupportDiagnostics.spectrumLabel(null))
    }

    private fun spectrum(
        granted: Boolean = true,
        running: Boolean = true,
        attached: Boolean = true,
        effectEnabled: Boolean?,
        sinceLastFrameMs: Long? = 40L,
        lastFailure: String? = null,
    ) = SpectrumSource.Diagnostics(
        granted = granted,
        running = running,
        attached = attached,
        effectEnabled = effectEnabled,
        sinceLastFrameMs = sinceLastFrameMs,
        lastFailure = lastFailure,
    )
}
