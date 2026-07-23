package dev.denza.apps.feature.mirrors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SideCameraWindowDetectorTest {
    @Test
    fun leftCameraUsesTheResolvedClusterId() {
        val windows = """
              Window #1 Window{abc com.byd.avc/com.byd.avc.PIP2MeterActivity}
                mDisplayId=7 package=com.byd.avc
        """.trimIndent()
        assertTrue(SideCameraWindowDetector.isLeftVisible(windows, 7))
        assertFalse(SideCameraWindowDetector.isLeftVisible(windows, 4))
    }

    @Test
    fun rightCameraKeepsTheVerifiedCompactAlertSignature() {
        val windows = """
              Window #2 Window{def com.byd.avc/Alert}
                mDisplayId=0 package=com.byd.avc ty=SYSTEM_ALERT frame=(720x450)
        """.trimIndent()
        assertTrue(SideCameraWindowDetector.isRightVisible(windows))
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
}
