package dev.denza.apps.feature.hud

import java.util.Locale

private const val DEFAULT_BACKGROUND_GUIDANCE_MAX_AGE_MS = 90_000L

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
    fun mergeWith(previous: HudGuidance): HudGuidance {
        val sameManeuver = maneuver == previous.maneuver
        return HudGuidance(
            maneuver = maneuver,
            roundaboutExitNumber = if (maneuver == HudManeuver.ROUNDABOUT) {
                roundaboutExitNumber ?: previous.roundaboutExitNumber.takeIf { sameManeuver }
            } else {
                null
            },
            instruction = instruction,
            nextRoadName = nextRoadName.ifEmpty {
                previous.nextRoadName.takeIf { sameManeuver }.orEmpty()
            },
            maneuverDistanceMeters = maneuverDistanceMeters,
            remainingDistanceMeters = remainingDistanceMeters ?: previous.remainingDistanceMeters,
            remainingTimeSeconds = remainingTimeSeconds ?: previous.remainingTimeSeconds,
            remainingTimeText = remainingTimeText.ifEmpty { previous.remainingTimeText },
            eta = eta.ifEmpty { previous.eta },
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

internal class HudNotificationGuidanceStore(
    private val maxAgeMs: Long = DEFAULT_BACKGROUND_GUIDANCE_MAX_AGE_MS,
) {
    private var patch: YandexNotificationGuidancePatch? = null
    private var capturedAtMs = 0L

    @Synchronized
    fun update(value: YandexNotificationGuidancePatch, capturedAtMs: Long) {
        patch = value
        this.capturedAtMs = capturedAtMs
    }

    @Synchronized
    fun clear() {
        patch = null
        capturedAtMs = 0L
    }

    @Synchronized
    fun resolve(previous: HudGuidance?, nowMs: Long): HudGuidance? {
        val current = patch ?: return null
        val base = previous ?: return null
        if (nowMs < capturedAtMs || nowMs - capturedAtMs > maxAgeMs) {
            return null
        }
        return current.mergeWith(base)
    }
}

object HudNotificationGuidanceRuntime {
    private val store = HudNotificationGuidanceStore()

    internal fun update(
        fields: YandexNotificationGuidanceFields,
        capturedAtMs: Long,
    ): Boolean {
        val patch = YandexNotificationGuidanceParser.parse(fields) ?: return false
        store.update(patch, capturedAtMs)
        return true
    }

    internal fun clear() {
        store.clear()
    }

    @JvmStatic
    fun resolve(previous: HudGuidance?, nowMs: Long): HudGuidance? =
        store.resolve(previous, nowMs)
}
