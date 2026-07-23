package dev.denza.apps.feature.mirrors

import java.util.concurrent.atomic.AtomicReference

data class SideCameraDetection(
    val recognizedSide: MirrorSide? = null,
    val avcCandidateBlocks: Int = 0,
    val unrecognizedCandidates: Int = 0,
)

object SideCameraWindowDetector {
    private val blockStart = Regex("\\n(?=\\s*Window #[0-9]+ Window\\{)")

    fun isLeftVisible(windows: String, clusterDisplayId: Int): Boolean =
        candidateBlocks(windows).any { isLeftBlock(it, clusterDisplayId) }

    fun isRightVisible(windows: String): Boolean = candidateBlocks(windows).any(::isRightBlock)

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
        )
    }

    private fun candidateBlocks(windows: String): List<String> =
        blockStart.split(windows).filter { it.contains("com.byd.avc") }

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

/** Last process-local detector result for the support report. */
object MirrorWindowDiagnostics {
    private val latest = AtomicReference(SideCameraDetection())

    fun record(result: SideCameraDetection) {
        latest.set(result)
    }

    fun snapshot(): SideCameraDetection = latest.get()
}
