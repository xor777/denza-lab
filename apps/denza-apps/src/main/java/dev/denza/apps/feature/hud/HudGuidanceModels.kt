package dev.denza.apps.feature.hud

import java.util.Locale
import kotlin.math.roundToInt

enum class HudManeuver(val stockId: Int) {
    UNKNOWN(0),
    STRAIGHT(3),
    LEFT(1),
    RIGHT(2),
    SLIGHT_LEFT(5),
    SLIGHT_RIGHT(7),
    SHARP_LEFT(9),
    SHARP_RIGHT(11),
    U_TURN_LEFT(45),
    U_TURN_RIGHT(13),
    ROUNDABOUT_LEFT(46),
    ROUNDABOUT_RIGHT(47),
}

data class HudGuidance(
    val maneuver: HudManeuver,
    val instruction: String,
    val nextRoadName: String,
    val maneuverDistanceMeters: Int,
    val remainingDistanceMeters: Int?,
    val remainingTimeSeconds: Int?,
    val remainingTimeText: String,
    val eta: String,
)

/** Pure conversion from Yandex's accessible labels into the stock HUD model. */
object YandexGuidanceParser {
    @JvmStatic
    fun parse(
        instruction: String?,
        nextRoadName: String?,
        maneuverDistance: String?,
        maneuverUnit: String?,
        remainingDistance: String?,
        remainingTime: String?,
        eta: String?,
    ): HudGuidance? {
        val cleanInstruction = instruction.clean()
        if (cleanInstruction.isEmpty()) return null
        val distanceMeters = parseDistance(maneuverDistance.clean(), maneuverUnit.clean())
            ?: return null
        return HudGuidance(
            maneuver = parseManeuver(cleanInstruction),
            instruction = cleanInstruction,
            nextRoadName = nextRoadName.clean(),
            maneuverDistanceMeters = distanceMeters,
            remainingDistanceMeters = parseDistance(remainingDistance.clean(), ""),
            remainingTimeSeconds = parseDurationSeconds(remainingTime.clean()),
            remainingTimeText = remainingTime.clean(),
            eta = eta.clean(),
        )
    }

    @JvmStatic
    fun parseManeuver(instruction: String): HudManeuver {
        val value = instruction.lowercase(Locale.ROOT).replace('ё', 'е')
        val left = value.contains("налево") || value.contains("влево") || value.contains("левее") ||
            value.contains("left")
        val right = value.contains("направо") || value.contains("вправо") || value.contains("правее") ||
            value.contains("right")
        val uTurn = value.contains("разворот") || value.contains("u-turn") || value.contains("u turn") ||
            value.contains("turn around")
        val roundabout = value.contains("круг") || value.contains("кольц") || value.contains("roundabout")
        val sharp = value.contains("крут") || value.contains("резк") || value.contains("sharp")
        val slight = value.contains("плавн") || value.contains("держитесь") || value.contains("левее") ||
            value.contains("правее") || value.contains("slight") || value.contains("keep ") ||
            value.contains("bear ") || value.contains("fork") || value.contains("merge")

        return when {
            roundabout && left -> HudManeuver.ROUNDABOUT_LEFT
            roundabout -> HudManeuver.ROUNDABOUT_RIGHT
            uTurn && right -> HudManeuver.U_TURN_RIGHT
            uTurn -> HudManeuver.U_TURN_LEFT
            sharp && left -> HudManeuver.SHARP_LEFT
            sharp && right -> HudManeuver.SHARP_RIGHT
            slight && left -> HudManeuver.SLIGHT_LEFT
            slight && right -> HudManeuver.SLIGHT_RIGHT
            left -> HudManeuver.LEFT
            right -> HudManeuver.RIGHT
            value.contains("прямо") || value.contains("straight") || value.contains("continue ahead") ->
                HudManeuver.STRAIGHT
            else -> HudManeuver.UNKNOWN
        }
    }

    @JvmStatic
    fun parseDistance(value: String, separateUnit: String): Int? {
        val combined = "$value $separateUnit".clean().lowercase(Locale.ROOT)
        val number = NUMBER.find(combined)?.value?.replace(',', '.')?.toDoubleOrNull() ?: return null
        val multiplier = when {
            combined.contains("км") || combined.contains("km") -> 1000.0
            Regex("(?:^|\\s)mi(?:\\s|$)").containsMatchIn(combined) -> 1609.344
            combined.contains("ft") -> 0.3048
            else -> 1.0
        }
        return (number * multiplier).roundToInt().coerceAtLeast(0)
    }

    @JvmStatic
    fun parseDurationSeconds(value: String): Int? {
        if (value.isBlank()) return null
        val normalized = value.lowercase(Locale.ROOT)
        val hours = HOURS.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = MINUTES.find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        return if (hours == 0 && minutes == 0) null else hours * 3600 + minutes * 60
    }

    private fun String?.clean(): String = this.orEmpty()
        .replace('\u00a0', ' ')
        .trim()
        .replace(WHITESPACE, " ")

    private val NUMBER = Regex("[0-9]+(?:[.,][0-9]+)?")
    private val HOURS = Regex("([0-9]+)\\s*(?:ч|час(?:а|ов)?|h|hr|hrs|hour|hours)(?=\\s|$)")
    private val MINUTES = Regex("([0-9]+)\\s*(?:мин(?:ут[аы]?)?|min|mins|minute|minutes|m)(?=\\s|$)")
    private val WHITESPACE = Regex("\\s+")
}

object HudGuidanceRuntime {
    @Volatile
    private var active = false

    @Volatile
    private var details = "Ожидаю маршрут Яндекса"

    @JvmStatic
    fun onGuidance(guidance: HudGuidance) {
        active = true
        details = buildList {
            add(guidance.nextRoadName.ifEmpty { guidance.instruction })
            add("${guidance.maneuverDistanceMeters} м")
            guidance.remainingDistanceMeters?.let { add("осталось ${it / 1000f} км") }
            if (guidance.eta.isNotEmpty()) add("прибытие ${guidance.eta}")
        }.joinToString(" · ")
    }

    @JvmStatic
    fun onWaiting() {
        active = false
        details = "Ожидаю маршрут Яндекса"
    }

    @JvmStatic
    fun onStopped() {
        active = false
        details = "Выключено"
    }

    @JvmStatic
    fun isActive(): Boolean = active

    @JvmStatic
    fun details(): String = details
}
