package dev.denza.apps.feature.hud

import java.util.Locale

internal data class YandexNotificationGuidanceFields(
    val maneuverResourceName: String = "",
    val maneuverDescription: String = "",
    val title: String = "",
    val description: String = "",
    val remainingDistance: String = "",
    val remainingTime: String = "",
    val arrivalTime: String = "",
)

internal data class YandexNotificationGuidancePatch(
    val maneuver: HudManeuver,
    val instruction: String,
    val roundaboutExitNumber: Int?,
    val nextRoadName: String,
    val maneuverDistanceMeters: Int,
    val remainingDistanceMeters: Int?,
    val remainingTimeSeconds: Int?,
    val remainingTimeText: String,
    val eta: String,
) {
    /**
     * The notification's route, with whatever it leaves out taken from the last guidance the HUD
     * showed. Without one (the HUD lost the route, or never had it) the notification stands alone:
     * it names the maneuver and its distance, which is all a road packet needs.
     */
    fun mergeWith(previous: HudGuidance?): HudGuidance {
        val sameManeuver = maneuver == previous?.maneuver
        return HudGuidance(
            maneuver = maneuver,
            roundaboutExitNumber = if (maneuver == HudManeuver.ROUNDABOUT) {
                roundaboutExitNumber ?: previous?.roundaboutExitNumber.takeIf { sameManeuver }
            } else {
                null
            },
            instruction = instruction,
            nextRoadName = nextRoadName.ifEmpty {
                previous?.nextRoadName.takeIf { sameManeuver }.orEmpty()
            },
            maneuverDistanceMeters = maneuverDistanceMeters,
            remainingDistanceMeters = remainingDistanceMeters ?: previous?.remainingDistanceMeters,
            remainingTimeSeconds = remainingTimeSeconds ?: previous?.remainingTimeSeconds,
            remainingTimeText = remainingTimeText.ifEmpty { previous?.remainingTimeText.orEmpty() },
            eta = eta.ifEmpty { previous?.eta.orEmpty() },
        )
    }
}

internal object YandexNotificationGuidanceParser {
    fun parse(fields: YandexNotificationGuidanceFields): YandexNotificationGuidancePatch? {
        val maneuverFromDescription = YandexGuidanceParser.parseManeuver(
            fields.maneuverDescription,
        )
        val maneuver = maneuverFromDescription.takeUnless { it == HudManeuver.UNKNOWN }
            ?: maneuverFromResource(fields.maneuverResourceName)
        if (maneuver == HudManeuver.UNKNOWN) return null

        val maneuverDistance = YandexGuidanceParser.parseDistance(fields.title, "")
            ?: return null
        val combinedInstruction = listOf(
            fields.maneuverDescription,
            fields.title,
            fields.description,
        ).joinToString(" ")
        val exitNumber = if (maneuver == HudManeuver.ROUNDABOUT) {
            YandexGuidanceParser.parseRoundaboutExitNumber("", combinedInstruction)
        } else {
            null
        }

        return YandexNotificationGuidancePatch(
            maneuver = maneuver,
            instruction = canonicalInstruction(maneuver, exitNumber),
            roundaboutExitNumber = exitNumber,
            nextRoadName = roadName(fields),
            maneuverDistanceMeters = maneuverDistance,
            remainingDistanceMeters = YandexGuidanceParser.parseDistance(
                fields.remainingDistance,
                "",
            ),
            remainingTimeSeconds = YandexGuidanceParser.parseDurationSeconds(
                fields.remainingTime,
            ),
            remainingTimeText = fields.remainingTime.clean(),
            eta = fields.arrivalTime.clean(),
        )
    }

    private fun maneuverFromResource(resourceName: String): HudManeuver {
        val value = resourceName.lowercase(Locale.ROOT)
        return when {
            value.contains("uturn_right") || value.contains("right_uturn") ->
                HudManeuver.U_TURN_RIGHT
            value.contains("uturn") -> HudManeuver.U_TURN_LEFT
            value.contains("hard_left") -> HudManeuver.SHARP_LEFT
            value.contains("hard_right") -> HudManeuver.SHARP_RIGHT
            value.contains("slight_left") || value.contains("fork_left") ||
                value.contains("exit_left") -> HudManeuver.SLIGHT_LEFT
            value.contains("slight_right") || value.contains("fork_right") ||
                value.contains("exit_right") -> HudManeuver.SLIGHT_RIGHT
            value.contains("roundabout") -> HudManeuver.ROUNDABOUT
            value.contains("straight") || value.contains("go_ahead") ->
                HudManeuver.STRAIGHT
            value.contains("left") -> HudManeuver.LEFT
            value.contains("right") -> HudManeuver.RIGHT
            else -> HudManeuver.UNKNOWN
        }
    }

    private fun roadName(fields: YandexNotificationGuidanceFields): String {
        val description = fields.description.clean()
        if (
            description.isNotEmpty() &&
            description != "Навигатор запущен" &&
            YandexGuidanceParser.parseManeuver(description) == HudManeuver.UNKNOWN &&
            YandexGuidanceParser.parseDurationSeconds(description) == null
        ) {
            return description
        }
        return fields.title
            .substringAfter('·', "")
            .clean()
    }

    private fun canonicalInstruction(
        maneuver: HudManeuver,
        roundaboutExitNumber: Int?,
    ): String = when (maneuver) {
        HudManeuver.STRAIGHT -> "Продолжайте прямо"
        HudManeuver.LEFT -> "Поверните налево"
        HudManeuver.RIGHT -> "Поверните направо"
        HudManeuver.SLIGHT_LEFT -> "Держитесь левее"
        HudManeuver.SLIGHT_RIGHT -> "Держитесь правее"
        HudManeuver.SHARP_LEFT -> "Резкий поворот налево"
        HudManeuver.SHARP_RIGHT -> "Резкий поворот направо"
        HudManeuver.U_TURN_LEFT -> "Развернитесь налево"
        HudManeuver.U_TURN_RIGHT -> "Развернитесь направо"
        HudManeuver.ROUNDABOUT -> roundaboutExitNumber?.let { "На кольце $it-й съезд" }
            ?: "Въезжайте на круговое движение"
        HudManeuver.UNKNOWN -> ""
    }

    private fun String.clean(): String = replace('\u00a0', ' ')
        .trim()
        .replace(Regex("\\s+"), " ")
}

/**
 * The route Yandex's navigation notification describes, for while Yandex has no visible window.
 *
 * It is kept for as long as the notification that carried it still says so. Each post of that
 * notification replaces it, a post of the same notification that no longer reads as a route (the
 * plain `Навигатор запущен` after arrival, a collapsed or unreadable layout) ends it, and so does
 * the notification's removal. Other Yandex notifications are not about the route and leave it
 * alone.
 *
 * Beyond that it ages from the moment Yandex posted it, never from the moment it was read or sent
 * again: past [maxAgeMs] it is gone even if the notification is still up and unchanged. How often
 * Yandex reposts while driving is not recorded, so the age is the HUD's own lost-route grace, the
 * time a route that vanished from the screen is kept ([HUD_LOST_ROUTE_GRACE_MS]).
 */
internal class HudNotificationGuidanceStore(
    private val maxAgeMs: Long = HUD_LOST_ROUTE_GRACE_MS,
) {
    private var notificationKey: String? = null
    private var patch: YandexNotificationGuidancePatch? = null
    private var capturedAtMs = 0L

    /**
     * A post of Yandex notification [key], with the fields read from it (null when nothing could
     * be read). True when it describes a route.
     */
    @Synchronized
    fun post(key: String, fields: YandexNotificationGuidanceFields?, capturedAtMs: Long): Boolean {
        val route = fields?.let(YandexNotificationGuidanceParser::parse)
        if (route == null) {
            remove(key)
            return false
        }
        notificationKey = key
        patch = route
        this.capturedAtMs = capturedAtMs
        return true
    }

    /** Notification [key] is gone; null means every Yandex notification is (listener lost). */
    @Synchronized
    fun remove(key: String?) {
        if (key == null || key == notificationKey) {
            notificationKey = null
            patch = null
            capturedAtMs = 0L
        }
    }

    @Synchronized
    fun resolve(previous: HudGuidance?, nowMs: Long): HudGuidanceSample? {
        val current = patch ?: return null
        if (nowMs < capturedAtMs || nowMs - capturedAtMs > maxAgeMs) {
            return null
        }
        return HudGuidanceSample(current.mergeWith(previous), capturedAtMs)
    }
}

object HudNotificationGuidanceRuntime {
    private val store = HudNotificationGuidanceStore()

    internal fun post(
        key: String,
        fields: YandexNotificationGuidanceFields?,
        capturedAtMs: Long,
    ): Boolean = store.post(key, fields, capturedAtMs)

    internal fun remove(key: String?) {
        store.remove(key)
    }

    @JvmStatic
    fun resolve(previous: HudGuidance?, nowMs: Long): HudGuidanceSample? =
        store.resolve(previous, nowMs)
}

/**
 * When Yandex posted a notification, on the uptime clock the HUD runs on. [postTimeMs] is the
 * wall-clock `StatusBarNotification.postTime`, stamped on every post: a live post is now, while one
 * re-read when the listener connects is as old as it is. A wall clock that stepped back cannot make
 * a post younger than now.
 */
internal fun notificationCapturedAtMs(uptimeNowMs: Long, wallNowMs: Long, postTimeMs: Long): Long =
    uptimeNowMs - (wallNowMs - postTimeMs).coerceAtLeast(0L)
