package dev.denza.apps

import dev.denza.apps.feature.cluster.CameraRuntimePhase
import dev.denza.apps.feature.cluster.CameraRuntimeSnapshot
import dev.denza.apps.feature.mirrors.MirrorSide
import dev.denza.apps.feature.mirrors.SideCameraDetection
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
}
