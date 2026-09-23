package dev.denza.apps.feature.mirrors

import java.util.concurrent.atomic.AtomicReference

data class SideCameraDetection(
    val recognizedSide: MirrorSide? = null,
    val avcCandidateBlocks: Int = 0,
    val unrecognizedCandidates: Int = 0,
    /** `PIP2MeterActivity` on the camera-overlay display: the stock left card on the meter. */
    val meterPipWindow: Boolean = false,
    /** The stock `PIP2HostAlert` on the head unit. It is the right card, and the left one too
     *  when the owner keeps both images on the head unit: its geometry does not name a side. */
    val hostPipWindow: Boolean = false,
)

object SideCameraWindowDetector {
    private val blockStart = Regex("\\n(?=\\s*Window #[0-9]+ Window\\{)")

    fun analyze(windows: String, clusterDisplayId: Int): SideCameraDetection {
        val candidates = candidateBlocks(windows)
        val recognized = mutableSetOf<MirrorSide>()
        var unrecognized = 0
        candidates.forEach { block ->
            val left = isLeftBlock(block, clusterDisplayId)
            val right = isRightBlock(block)
            when {
                left && !right -> recognized += MirrorSide.LEFT
                right && !left -> recognized += MirrorSide.RIGHT
                else -> unrecognized += 1
            }
        }
        return SideCameraDetection(
            recognizedSide = recognized.singleOrNull(),
            avcCandidateBlocks = candidates.size,
            unrecognizedCandidates = unrecognized,
            meterPipWindow = MirrorSide.LEFT in recognized,
            hostPipWindow = MirrorSide.RIGHT in recognized,
        )
    }

    private fun candidateBlocks(windows: String): List<String> =
        blockStart.split(windows).filter { it.contains("package=com.byd.avc") }

    private fun isLeftBlock(block: String, clusterDisplayId: Int): Boolean =
        block.contains("com.byd.avc/com.byd.avc.PIP2MeterActivity") &&
            block.contains("mDisplayId=$clusterDisplayId") &&
            block.contains("package=com.byd.avc")

    private fun isRightBlock(block: String): Boolean =
        block.contains("com.byd.avc") &&
            block.contains("mDisplayId=0") &&
            block.contains("package=com.byd.avc") &&
            block.contains("ty=SYSTEM_ALERT") &&
            block.contains("(720x450)")
}

/**
 * The stock turn-signal PIP that a Denza camera may take over, or null.
 *
 * AVC's own mode names the side ([AvcStockMode.turnSide]); a window only says the stock has built
 * the card, so the renderer it binds is ready to be taken. A mode without its window is a card
 * still being built, and a window without a turn mode is the radar, CMS or full-screen view.
 * Which window a left card uses follows the owner's stock choice: the meter activity by default,
 * the head-unit alert when both images stay on the head unit. When AVC did not answer, the
 * window geometry of the stock default is the only evidence left, and it is used alone.
 */
object MirrorStockPip {
    fun side(
        detection: SideCameraDetection,
        mode: Int?,
        choice: AvcTurnCameraChoice?,
    ): MirrorSide? {
        if (mode == null) return legacySide(detection)
        return when (AvcStockMode.turnSide(mode)) {
            MirrorSide.RIGHT -> MirrorSide.RIGHT.takeIf { detection.hostPipWindow }
            MirrorSide.LEFT -> MirrorSide.LEFT.takeIf {
                when (choice) {
                    AvcTurnCameraChoice.PIP_LEFT_ON_METER -> detection.meterPipWindow
                    AvcTurnCameraChoice.PIP_ON_HEAD_UNIT -> detection.hostPipWindow
                    else -> detection.meterPipWindow || detection.hostPipWindow
                }
            }
            null -> null
        }
    }

    private fun legacySide(detection: SideCameraDetection): MirrorSide? =
        detection.recognizedSide.takeIf { detection.unrecognizedCandidates == 0 }
}

/** Last process-local detector result for the support report. */
object MirrorWindowDiagnostics {
    private val latest = AtomicReference(SideCameraDetection())

    fun record(result: SideCameraDetection) {
        latest.set(result)
    }

    fun snapshot(): SideCameraDetection = latest.get()
}
