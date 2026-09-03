package dev.denza.apps.feature.hud

import java.util.Locale
import kotlin.math.roundToInt

/**
 * [stockId] is field 28 (`recommendedDrivingDirectionsId`) of the stock road
 * packet. The values follow the HUD icon table recorded in
 * docs/instrument-display-findings.md: 1 left, 2 right, 3 slight left,
 * 5 slight right, 7 sharp left, 8 sharp right, 9/10 U-turn left/right,
 * 11 straight, 25 counter-clockwise roundabout. Roundabouts do not encode
 * the exit number here.
 */
enum class HudManeuver(val stockId: Int) {
    UNKNOWN(0),
    STRAIGHT(11),
    LEFT(1),
    RIGHT(2),
    SLIGHT_LEFT(3),
    SLIGHT_RIGHT(5),
    SHARP_LEFT(7),
    SHARP_RIGHT(8),
    U_TURN_LEFT(9),
    U_TURN_RIGHT(10),
    ROUNDABOUT_LEFT(25),
    ROUNDABOUT_RIGHT(25),
}

data class HudGuidance(
    val maneuver: HudManeuver,
    val roundaboutExitNumber: Int?,
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
        roundaboutExitNumber: String? = null,
    ): HudGuidance? {
        val cleanInstruction = instruction.clean()
        if (cleanInstruction.isEmpty()) return null
        val distanceMeters = parseDistance(maneuverDistance.clean(), maneuverUnit.clean())
            ?: return null
        val maneuver = parseManeuver(cleanInstruction)
        return HudGuidance(
            maneuver = maneuver,
            roundaboutExitNumber = if (maneuver.isRoundabout()) {
                parseRoundaboutExitNumber(roundaboutExitNumber.clean(), cleanInstruction)
            } else {
                null
            },
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
        val value = instruction.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(UNICODE_DASHES, "-")
        val left = value.contains("налево") || value.contains("влево") || value.contains("левее") ||
            value.contains("слева") || LEFT_ADJECTIVE.containsMatchIn(value) ||
            value.contains("left")
        val right = value.contains("направо") || value.contains("вправо") || value.contains("правее") ||
            value.contains("справа") || RIGHT_ADJECTIVE.containsMatchIn(value) ||
            value.contains("right")
        val uTurn = value.contains("разворот") || value.contains("развернит") ||
            value.contains("u-turn") || value.contains("u turn") || value.contains("turn around")
        val roundabout = ROUNDABOUT_RUSSIAN.containsMatchIn(value) ||
            value.contains("roundabout") || value.contains("traffic circle")
        val sharp = value.contains("крут") || value.contains("резк") || value.contains("sharp")
        val slight = value.contains("плавн") || value.contains("держитесь") || value.contains("левее") ||
            value.contains("правее") || value.contains("slight") || value.contains("keep ") ||
            value.contains("bear ") || value.contains("fork") || value.contains("merge")

        return when {
            roundabout && right -> HudManeuver.ROUNDABOUT_RIGHT
            roundabout -> HudManeuver.ROUNDABOUT_LEFT
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

    @JvmStatic
    fun parseRoundaboutExitNumber(explicitValue: String, instruction: String): Int? {
        POSITIVE_INTEGER.find(explicitValue.clean())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?.let { return it }

        val value = instruction.lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(UNICODE_DASHES, "-")
        ROUNDABOUT_EXIT_NUMBER_PATTERNS.forEach { pattern ->
            pattern.find(value)
                ?.groupValues
                ?.drop(1)
                ?.firstOrNull { it.isNotEmpty() }
                ?.toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let { return it }
        }
        ROUNDABOUT_EXIT_WORDS.forEach { (pattern, number) ->
            if (pattern.containsMatchIn(value)) {
                return number
            }
        }
        return null
    }

    private fun HudManeuver.isRoundabout(): Boolean =
        this == HudManeuver.ROUNDABOUT_LEFT || this == HudManeuver.ROUNDABOUT_RIGHT

    private fun String?.clean(): String = this.orEmpty()
        .replace('\u00a0', ' ')
        .trim()
        .replace(WHITESPACE, " ")

    private val NUMBER = Regex("[0-9]+(?:[.,][0-9]+)?")
    private val POSITIVE_INTEGER = Regex("(?:^|[^0-9])([0-9]+)(?=$|[^0-9])")
    private val HOURS = Regex("([0-9]+)\\s*(?:ч|час(?:а|ов)?|h|hr|hrs|hour|hours)(?=\\s|$)")
    private val MINUTES = Regex("([0-9]+)\\s*(?:мин(?:ут[аы]?)?|min|mins|minute|minutes|m)(?=\\s|$)")
    private val WHITESPACE = Regex("\\s+")
    private val UNICODE_DASHES = Regex("[\\u2010-\\u2015\\u2212]")
    private val LEFT_ADJECTIVE = Regex("(?:^|[^\\p{L}])лев(?:ый|ая|ое|ые|ого|ой|ую|ом|ых|ым|ыми)(?=$|[^\\p{L}])")
    private val RIGHT_ADJECTIVE = Regex("(?:^|[^\\p{L}])прав(?:ый|ая|ое|ые|ого|ой|ую|ом|ых|ым|ыми)(?=$|[^\\p{L}])")
    private val ROUNDABOUT_RUSSIAN = Regex(
        "(?:^|[^\\p{L}])(?:круг(?:ов\\p{L}*|а|е|у|ом)?|кольц(?:о|а|е|у|ом)|кольцев\\p{L}*\\s+движен\\p{L}*)(?=$|[^\\p{L}])",
    )
    private val ROUNDABOUT_EXIT_NUMBER_PATTERNS = listOf(
        Regex("([0-9]+)(?:-й|-ый|-ой|й|ый|ой)?\\s*(?:съезд|выезд)"),
        Regex("(?:съезд|выезд)\\s*(?:номер\\s*|#\\s*)?([0-9]+)"),
        Regex("([0-9]+)(?:st|nd|rd|th)?\\s+exit"),
        Regex("exit\\s*(?:number\\s*|#\\s*)?([0-9]+)"),
    )
    private val ROUNDABOUT_EXIT_WORDS = listOf(
        Regex("перв(?:ый|ом|ого|ому|ым)\\s+(?:съезд|выезд)|first\\s+exit") to 1,
        Regex("втор(?:ой|ом|ого|ому|ым)\\s+(?:съезд|выезд)|second\\s+exit") to 2,
        Regex("трет(?:ий|ьем|ьего|ьему|ьим)\\s+(?:съезд|выезд)|third\\s+exit") to 3,
        Regex("четверт(?:ый|ом|ого|ому|ым)\\s+(?:съезд|выезд)|fourth\\s+exit") to 4,
        Regex("пят(?:ый|ом|ого|ому|ым)\\s+(?:съезд|выезд)|fifth\\s+exit") to 5,
        Regex("шест(?:ой|ом|ого|ому|ым)\\s+(?:съезд|выезд)|sixth\\s+exit") to 6,
        Regex("седьм(?:ой|ом|ого|ому|ым)\\s+(?:съезд|выезд)|seventh\\s+exit") to 7,
        Regex("восьм(?:ой|ом|ого|ому|ым)\\s+(?:съезд|выезд)|eighth\\s+exit") to 8,
        Regex("девят(?:ый|ом|ого|ому|ым)\\s+(?:съезд|выезд)|ninth\\s+exit") to 9,
        Regex("десят(?:ый|ом|ого|ому|ым)\\s+(?:съезд|выезд)|tenth\\s+exit") to 10,
    )
}

/** Fail-closed remaining-route figures handed to the trip panel. */
data class HudRemainingGuidance(val distanceMeters: Int?, val timeSeconds: Int?)

object HudGuidanceRuntime {
    private const val REMAINING_STALE_MS = 4_000L

    @Volatile
    private var active = false

    @Volatile
    private var details = "Ожидаю маршрут Яндекса"

    @Volatile
    private var remainingDistanceMeters = -1

    @Volatile
    private var remainingTimeSeconds = -1

    @Volatile
    private var updatedAtMs = 0L

    @JvmStatic
    fun onGuidance(guidance: HudGuidance, capturedAtMs: Long) {
        active = true
        remainingDistanceMeters = guidance.remainingDistanceMeters ?: -1
        remainingTimeSeconds = guidance.remainingTimeSeconds ?: -1
        updatedAtMs = capturedAtMs
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
        clearRemaining()
        details = "Ожидаю маршрут Яндекса"
    }

    @JvmStatic
    fun onStopped() {
        active = false
        clearRemaining()
        details = "Выключено"
    }

    @JvmStatic
    fun isActive(): Boolean = active

    @JvmStatic
    fun details(): String = details

    /**
     * Remaining distance/time from the validated Yandex guidance, or null when
     * guidance is absent or stale. Respects the HUD feature's own fail-closed
     * rules (the accessibility monitor clears on a lost route); this adds a hard
     * staleness guard so the panel never shows an invented or frozen figure.
     * [nowMs] must be on the same clock the monitor publishes with
     * (SystemClock.uptimeMillis()).
     */
    @JvmStatic
    fun remaining(nowMs: Long): HudRemainingGuidance? {
        if (!active) return null
        val at = updatedAtMs
        if (at == 0L || nowMs < at || nowMs - at > REMAINING_STALE_MS) return null
        val dist = remainingDistanceMeters
        val time = remainingTimeSeconds
        if (dist < 0 && time < 0) return null
        return HudRemainingGuidance(
            distanceMeters = if (dist >= 0) dist else null,
            timeSeconds = if (time >= 0) time else null,
        )
    }

    private fun clearRemaining() {
        remainingDistanceMeters = -1
        remainingTimeSeconds = -1
        updatedAtMs = 0L
    }
}
