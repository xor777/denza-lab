package dev.denza.apps.feature.mirrors

import org.junit.Assert.assertEquals
import org.junit.Test

class SideCameraWindowDetectorTest {
    @Test
    fun leftCameraUsesTheResolvedClusterId() {
        val windows = """
              Window #1 Window{abc com.byd.avc/com.byd.avc.PIP2MeterActivity}
                mDisplayId=7 package=com.byd.avc
        """.trimIndent()
        assertEquals(MirrorSide.LEFT, SideCameraWindowDetector.analyze(windows, 7).recognizedSide)
        assertEquals(null, SideCameraWindowDetector.analyze(windows, 4).recognizedSide)
    }

    @Test
    fun rightCameraKeepsTheVerifiedCompactAlertSignature() {
        val windows = """
              Window #2 Window{def com.byd.avc/Alert}
                mDisplayId=0 package=com.byd.avc ty=SYSTEM_ALERT frame=(720x450)
        """.trimIndent()
        assertEquals(MirrorSide.RIGHT, SideCameraWindowDetector.analyze(windows, 7).recognizedSide)
    }

    @Test
    fun `analysis reports recognized side and candidate counts`() {
        val windows = """
              Window #1 Window{abc com.byd.avc/com.byd.avc.PIP2MeterActivity}
                mDisplayId=7 package=com.byd.avc
              Window #2 Window{def com.byd.avc/Unknown}
                mDisplayId=0 package=com.byd.avc frame=(1200x600)
              Window #3 Window{ghi com.example/Other}
                mDisplayId=0 package=com.example
        """.trimIndent()

        val result = SideCameraWindowDetector.analyze(windows, 7)

        assertEquals(MirrorSide.LEFT, result.recognizedSide)
        assertEquals(2, result.avcCandidateBlocks)
        assertEquals(1, result.unrecognizedCandidates)
    }

    @Test
    fun `simultaneous left and right signatures are ambiguous`() {
        val windows = """
              Window #1 Window{abc com.byd.avc/com.byd.avc.PIP2MeterActivity}
                mDisplayId=7 package=com.byd.avc
              Window #2 Window{def com.byd.avc/Alert}
                mDisplayId=0 package=com.byd.avc ty=SYSTEM_ALERT frame=(720x450)
        """.trimIndent()

        val result = SideCameraWindowDetector.analyze(windows, 7)

        assertEquals(null, result.recognizedSide)
        assertEquals(2, result.avcCandidateBlocks)
        assertEquals(0, result.unrecognizedCandidates)
    }

    @Test
    fun `global focus tail does not create an AVC candidate in a foreign window`() {
        val windows = """
            WINDOW MANAGER WINDOWS (dumpsys window visible)
              Window #1 Window{abc com.byd.avc/com.byd.avc.PIP2MeterActivity}
                mDisplayId=7 package=com.byd.avc
              Window #2 Window{def com.example/.MainActivity}
                mDisplayId=0 package=com.example
              mCurrentFocus=Window{999 u0 com.byd.avc/com.byd.avc.PIP2MeterActivity}
              mInputMethodTarget=Window{def u0 com.example/.MainActivity}
        """.trimIndent()

        val result = SideCameraWindowDetector.analyze(windows, 7)

        assertEquals(MirrorSide.LEFT, result.recognizedSide)
        assertEquals(1, result.avcCandidateBlocks)
        assertEquals(0, result.unrecognizedCandidates)
    }

    @Test
    fun theStockModeNamesTheSideAndTheWindowOnlySaysTheCardIsBuilt() {
        val meter = SideCameraDetection(meterPipWindow = true, recognizedSide = MirrorSide.LEFT)
        val host = SideCameraDetection(hostPipWindow = true, recognizedSide = MirrorSide.RIGHT)
        val both = SideCameraDetection(meterPipWindow = true, hostPipWindow = true)
        val onMeter = AvcTurnCameraChoice.PIP_LEFT_ON_METER
        val onHeadUnit = AvcTurnCameraChoice.PIP_ON_HEAD_UNIT

        assertEquals(MirrorSide.LEFT, MirrorStockPip.side(meter, AvcStockMode.PIP_LEFT, onMeter))
        // Right-to-left on the meter: the old head-unit card is not the new left card.
        assertEquals(null, MirrorStockPip.side(host, AvcStockMode.PIP_LEFT, onMeter))
        // Both images on the head unit: the same 720x450 alert is the left card too.
        assertEquals(MirrorSide.LEFT, MirrorStockPip.side(host, AvcStockMode.PIP_LEFT, onHeadUnit))
        assertEquals(MirrorSide.RIGHT, MirrorStockPip.side(both, AvcStockMode.PIP_RIGHT, onMeter))
        assertEquals(MirrorSide.RIGHT, MirrorStockPip.side(host, AvcStockMode.PIP_RIGHT_PORTRAIT, null))
        // A mode without its window is a card still being built.
        assertEquals(null, MirrorStockPip.side(meter, AvcStockMode.PIP_RIGHT, onMeter))
        assertEquals(null, MirrorStockPip.side(SideCameraDetection(), AvcStockMode.PIP_LEFT, onMeter))
        // Idle, the CMS double view and the radar view are not turn cards.
        listOf(AvcStockMode.IDLE, 5097, 5098, 5030).forEach { mode ->
            assertEquals("mode $mode", null, MirrorStockPip.side(both, mode, onMeter))
        }
    }

    @Test
    fun withoutAnAnswerFromAvcOnlyTheStockDefaultGeometryIsLeft() {
        val meter = SideCameraDetection(meterPipWindow = true, recognizedSide = MirrorSide.LEFT)
        val host = SideCameraDetection(hostPipWindow = true, recognizedSide = MirrorSide.RIGHT)
        val unknownToo = host.copy(unrecognizedCandidates = 1)
        assertEquals(MirrorSide.LEFT, MirrorStockPip.side(meter, null, null))
        assertEquals(MirrorSide.RIGHT, MirrorStockPip.side(host, null, null))
        assertEquals(null, MirrorStockPip.side(unknownToo, null, null))
        assertEquals(null, MirrorStockPip.side(SideCameraDetection(), null, null))
    }

    @Test
    fun detectionFlagsBothCardKinds() {
        val windows = """
              Window #1 Window{abc com.byd.avc/com.byd.avc.PIP2MeterActivity}
                mDisplayId=7 package=com.byd.avc
              Window #2 Window{def com.byd.avc/Alert}
                mDisplayId=0 package=com.byd.avc ty=SYSTEM_ALERT frame=(720x450)
        """.trimIndent()
        val result = SideCameraWindowDetector.analyze(windows, 7)
        assertEquals(true, result.meterPipWindow)
        assertEquals(true, result.hostPipWindow)
    }
}
