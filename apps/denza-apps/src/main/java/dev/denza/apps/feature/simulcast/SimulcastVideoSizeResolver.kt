package dev.denza.apps.feature.simulcast

import android.content.Context
import android.view.Display
import dev.denza.apps.feature.cluster.ClusterDisplayDescriptor
import dev.denza.apps.feature.cluster.ClusterDisplayResolver
import java.util.Locale

/**
 * Picks the share video size for a DiShare receiver by matching the receiver to
 * the physical Android display behind it and copying that display's aspect
 * ratio. DiShare letterboxes the stream when the requested video aspect differs
 * from the receiver panel, so an aspect-matched size removes the black bars.
 *
 * The receiver-to-display link is a name heuristic: DiShare only reports
 * `screenId`/`deviceId`, never a display id, and this firmware family names its
 * displays with recognizable fragments (`fse_screen`, `rse`, `rear`,
 * `overhead`). Every unmatched, ambiguous, or out-of-range case falls back to
 * the proven 2560x1440 share size, so a wrong guess can never be worse than the
 * previous hard-coded behavior.
 */
object SimulcastVideoSizeResolver {
    /** Proven share size on the test car; also the fallback for every unmatched receiver. */
    const val DEFAULT_VIDEO_WIDTH = 2560
    const val DEFAULT_VIDEO_HEIGHT = 1440

    // DiShareProjectionBridge.sanitizeVideoDimension accepts 180..4096 and
    // silently degrades anything else to the legacy 1024x576 path, so the
    // resolver must never emit an out-of-range dimension.
    private const val MIN_VIDEO_DIMENSION = 180
    private const val MAX_VIDEO_DIMENSION = 4096

    // The proven pipeline encodes a 2560-long side; keep it and derive the
    // other dimension from the display aspect, aligned for the video encoder.
    private const val LONG_SIDE = DEFAULT_VIDEO_WIDTH
    private const val DIMENSION_ALIGNMENT = 8

    // Displays that must never be treated as a receiver panel: the IVI source,
    // DiShare's own BYD-Mirror virtual display, our virtual displays, and the
    // instrument-cluster fission projections.
    private val excludedNameTokens = setOf("ivi", "dishare", "mirror", "fission")
    private const val EXCLUDED_TOKEN_PREFIX = "xdja"

    private class ReceiverRule(val baseTokens: Set<String>, val sideTokens: Set<String> = emptySet())

    private val receiverRules = mapOf(
        "screen_hud" to ReceiverRule(setOf("hud")),
        "screen_fse" to ReceiverRule(setOf("fse")),
        "screen_rse_l" to ReceiverRule(setOf("rse", "rear"), setOf("l", "left")),
        "screen_rse_r" to ReceiverRule(setOf("rse", "rear"), setOf("r", "right")),
        "screen_overhead" to ReceiverRule(setOf("overhead")),
        "screen_tv" to ReceiverRule(setOf("tv", "rear", "overhead")),
    )

    data class Resolution(
        val videoWidth: Int,
        val videoHeight: Int,
        val matched: Boolean,
        val details: String,
        val viewportWidth: Int,
        val viewportHeight: Int,
    )

    @JvmStatic
    fun resolve(context: Context, receiverId: String?): Resolution =
        resolve(receiverId, ClusterDisplayResolver.candidates(context))

    @JvmStatic
    fun resolve(receiverId: String?, displays: List<ClusterDisplayDescriptor>): Resolution {
        val rule = receiverRules[receiverId]
            ?: return fallback("receiver has no display mapping", displays)
        val matches = matchDisplays(rule, displays)
        if (matches.isEmpty()) {
            return fallback("no matching display", displays)
        }
        val sized = matches.mapNotNull { display ->
            videoSizeFor(display)?.let { size -> display to size }
        }
        if (sized.isEmpty()) {
            return fallback("matched ${describe(matches)} but sizes are unusable", displays)
        }
        val sizes = sized.map { (_, size) -> size }.distinct()
        if (sizes.size > 1) {
            return fallback("ambiguous displays ${describe(sized.map { it.first })}", displays)
        }
        val (display, size) = sized.first()
        return Resolution(
            videoWidth = size.first,
            videoHeight = size.second,
            matched = true,
            details = "display '${display.name}' ${display.width}x${display.height}",
            viewportWidth = display.width,
            viewportHeight = display.height,
        )
    }

    private fun matchDisplays(
        rule: ReceiverRule,
        displays: List<ClusterDisplayDescriptor>,
    ): List<ClusterDisplayDescriptor> {
        val candidates = displays.filterNot(::mustExclude)
        val tokenized = candidates.associateWith { tokens(it.name) }
        val baseMatches = candidates.filter { display ->
            tokenized.getValue(display).any(rule.baseTokens::contains)
        }
        if (rule.sideTokens.isEmpty()) {
            return baseMatches
        }
        val sideMatches = baseMatches.filter { display ->
            tokenized.getValue(display).any(rule.sideTokens::contains)
        }
        // A single side-less rear panel is still the best aspect evidence for
        // either rear receiver, so only narrow when the side marker exists.
        return sideMatches.ifEmpty { baseMatches }
    }

    private fun mustExclude(display: ClusterDisplayDescriptor): Boolean {
        if (display.id == Display.DEFAULT_DISPLAY || display.isOwnVirtualDisplay) {
            return true
        }
        if (display.width <= 0 || display.height <= 0) {
            return true
        }
        return tokens(display.name).any { token ->
            token in excludedNameTokens || token.startsWith(EXCLUDED_TOKEN_PREFIX)
        }
    }

    private fun tokens(name: String): List<String> =
        name.lowercase(Locale.ROOT).split(NON_ALPHANUMERIC).filter(String::isNotEmpty)

    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

    private fun videoSizeFor(display: ClusterDisplayDescriptor): Pair<Int, Int>? {
        val size = if (display.width >= display.height) {
            LONG_SIDE to alignedScale(display.height, display.width)
        } else {
            alignedScale(display.width, display.height) to LONG_SIDE
        }
        val valid = size.first in MIN_VIDEO_DIMENSION..MAX_VIDEO_DIMENSION &&
            size.second in MIN_VIDEO_DIMENSION..MAX_VIDEO_DIMENSION
        return if (valid) size else null
    }

    private fun alignedScale(numerator: Int, denominator: Int): Int {
        val raw = LONG_SIDE.toDouble() * numerator / denominator
        return (Math.round(raw / DIMENSION_ALIGNMENT) * DIMENSION_ALIGNMENT).toInt()
    }

    private fun fallback(
        reason: String,
        displays: List<ClusterDisplayDescriptor>,
    ): Resolution {
        val defaultViewport = displays.firstOrNull { display ->
            display.id == Display.DEFAULT_DISPLAY && display.width > 0 && display.height > 0
        }
        return Resolution(
            videoWidth = DEFAULT_VIDEO_WIDTH,
            videoHeight = DEFAULT_VIDEO_HEIGHT,
            matched = false,
            details = "$reason, fallback ${DEFAULT_VIDEO_WIDTH}x$DEFAULT_VIDEO_HEIGHT",
            viewportWidth = defaultViewport?.width ?: DEFAULT_VIDEO_WIDTH,
            viewportHeight = defaultViewport?.height ?: DEFAULT_VIDEO_HEIGHT,
        )
    }

    private fun describe(displays: List<ClusterDisplayDescriptor>): String =
        displays.joinToString(prefix = "[", postfix = "]") { display ->
            "'${display.name}' ${display.width}x${display.height}"
        }
}
