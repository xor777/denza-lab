package dev.denza.apps.feature.hud

import java.util.Locale

internal const val YANDEX_NOTIFICATION_ARTWORK_ENABLED = true

internal enum class HudArtworkSource {
    BUILT_IN,
    NOTIFICATION,
}

internal data class HudArtworkDiagnostics(
    val flagEnabled: Boolean,
    val listenerConnected: Boolean,
    val source: HudArtworkSource,
    val detail: String,
    val lastFailure: String?,
)

internal data class HudArtworkCandidate<T>(
    val token: T,
    val resourceName: String,
    val width: Int,
    val height: Int,
    val opaqueRectangularBackground: Boolean,
)

internal object HudArtworkCandidatePolicy {
    fun <T> select(candidates: List<HudArtworkCandidate<T>>): HudArtworkCandidate<T>? {
        val scored = candidates.mapNotNull { candidate ->
            score(candidate)?.let { score -> candidate to score }
        }
        val bestScore = scored.maxOfOrNull { it.second } ?: return null
        val best = scored.filter { it.second == bestScore }
        return best.singleOrNull()?.first
    }

    private fun <T> score(candidate: HudArtworkCandidate<T>): Int? {
        if (candidate.width < 16 || candidate.height < 16) return null
        if (candidate.opaqueRectangularBackground) return null
        val name = candidate.resourceName.lowercase(Locale.ROOT)
        if (
            name.contains("app_icon") ||
            name.contains("application_icon") ||
            name.contains("logo") ||
            name.contains("album") ||
            name.contains("small_icon")
        ) {
            return null
        }
        return when {
            name == "primaryicontinted" -> 100
            name == "nextmaneuver" -> 95
            name.contains("maneuver") -> 90
            name.contains("turn") -> 80
            else -> null
        }
    }
}

/**
 * The maneuver picture from Yandex's notification, for as long as it can only show the current
 * maneuver.
 *
 * An artwork is used only if it was captured no earlier than the data that first showed the
 * current maneuver. Older artwork may be the previous maneuver's picture - Yandex reposts the
 * notification as the distance changes, so the last post before a turn is rarely far behind it -
 * and the Canvas fallback stands in until Yandex posts again. In the background the guidance and
 * the artwork come from the same post and are observed at its time, so that post's own artwork
 * counts.
 */
internal class HudNotificationArtworkStore(
    private val featureEnabled: () -> Boolean,
) {
    private data class Artwork(
        val notificationKey: String,
        val png: ByteArray,
        val capturedAtMs: Long,
    )

    private data class ManeuverIdentity(
        val maneuver: HudManeuver,
        val instruction: String,
        val roundaboutExitNumber: Int?,
    )

    private var artwork: Artwork? = null
    private var currentIdentity: ManeuverIdentity? = null
    private var identityChangedAtMs = 0L
    private var listenerConnected = false
    private var source = HudArtworkSource.BUILT_IN
    private var detail = "no-artwork"
    private var lastFailure: String? = null

    @Synchronized
    fun update(notificationKey: String, png: ByteArray, capturedAtMs: Long) {
        if (png.isEmpty()) {
            clear(notificationKey, "empty-artwork")
            return
        }
        artwork = Artwork(notificationKey, png.copyOf(), capturedAtMs)
        lastFailure = null
    }

    @Synchronized
    fun clear(notificationKey: String?, reason: String) {
        val current = artwork
        if (notificationKey == null || current?.notificationKey == notificationKey) {
            artwork = null
            source = HudArtworkSource.BUILT_IN
            detail = "no-artwork"
        }
        lastFailure = reason
    }

    @Synchronized
    fun reject(notificationKey: String?, reason: String) {
        if (notificationKey == null || artwork?.notificationKey == notificationKey) {
            lastFailure = reason
        }
    }

    @Synchronized
    fun setListenerConnected(connected: Boolean) {
        listenerConnected = connected
        if (!connected) {
            artwork = null
            source = HudArtworkSource.BUILT_IN
            detail = "no-artwork"
        }
    }

    @Synchronized
    fun observe(guidance: HudGuidance, nowMs: Long) {
        if (!featureEnabled() || guidance.maneuver == HudManeuver.UNKNOWN) return
        updateIdentity(guidance, nowMs)
    }

    @Synchronized
    fun resolve(guidance: HudGuidance, nowMs: Long): ByteArray? {
        if (!featureEnabled()) {
            source = HudArtworkSource.BUILT_IN
            detail = "flag-disabled"
            return null
        }
        if (guidance.maneuver == HudManeuver.UNKNOWN) {
            source = HudArtworkSource.BUILT_IN
            detail = "unknown-maneuver"
            return null
        }

        updateIdentity(guidance, nowMs)

        val candidate = artwork
        if (candidate == null) {
            source = HudArtworkSource.BUILT_IN
            detail = "no-artwork"
            return null
        }
        if (candidate.capturedAtMs < identityChangedAtMs) {
            source = HudArtworkSource.BUILT_IN
            detail = "stale-artwork"
            return null
        }

        source = HudArtworkSource.NOTIFICATION
        detail = "notification"
        return candidate.png.copyOf()
    }

    @Synchronized
    fun diagnostics(): HudArtworkDiagnostics = HudArtworkDiagnostics(
        flagEnabled = featureEnabled(),
        listenerConnected = listenerConnected,
        source = source,
        detail = detail,
        lastFailure = lastFailure,
    )

    private fun HudGuidance.identity() = ManeuverIdentity(
        maneuver = maneuver,
        instruction = instruction
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " "),
        roundaboutExitNumber = roundaboutExitNumber,
    )

    private fun updateIdentity(guidance: HudGuidance, nowMs: Long) {
        val identity = guidance.identity()
        if (identity != currentIdentity) {
            currentIdentity = identity
            identityChangedAtMs = nowMs
        }
    }
}

object HudNotificationArtworkRuntime {
    private val store = HudNotificationArtworkStore {
        YANDEX_NOTIFICATION_ARTWORK_ENABLED
    }

    @JvmStatic
    fun isFeatureEnabled(): Boolean = YANDEX_NOTIFICATION_ARTWORK_ENABLED

    @JvmStatic
    fun update(notificationKey: String, png: ByteArray, capturedAtMs: Long) {
        store.update(notificationKey, png, capturedAtMs)
    }

    @JvmStatic
    fun clear(notificationKey: String?, reason: String) {
        store.clear(notificationKey, reason)
    }

    @JvmStatic
    fun reject(notificationKey: String?, reason: String) {
        store.reject(notificationKey, reason)
    }

    @JvmStatic
    fun setListenerConnected(connected: Boolean) {
        store.setListenerConnected(connected)
    }

    @JvmStatic
    fun observe(guidance: HudGuidance, nowMs: Long) {
        store.observe(guidance, nowMs)
    }

    @JvmStatic
    fun resolve(guidance: HudGuidance, nowMs: Long): ByteArray? =
        store.resolve(guidance, nowMs)

    internal fun diagnostics(): HudArtworkDiagnostics = store.diagnostics()
}
