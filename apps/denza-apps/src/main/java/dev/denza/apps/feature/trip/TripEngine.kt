package dev.denza.apps.feature.trip

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Process-lifetime state behind the visible trip panel.
 *
 * The engine keeps only values the active spectrum layout renders: the
 * movement-gated trip clock, GNSS distance/altitude/variometer, validated Yandex
 * guidance, and offline sun facts. Android adapters push samples in on the main
 * thread and the renderer reads the state on that same thread.
 */
class TripEngine {

    private var gnss = GnssTripAccumulator()
    private var lastFixMs = 0L
    private var haveFix = false

    var tripStarted: Boolean = false
        private set
    var parked: Boolean? = null
        private set
    private var tripStartElapsedMs = 0L
    private var movementCandidateMs = -1L

    var elapsedSeconds: Double = 0.0
        private set
    private var currentSpeed = 0.0

    private var guidanceDistance: Int? = null
    private var guidanceTime: Int? = null
    private var guidanceValid = false

    private var sun = SunInfo(true, "")
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastTz = 0
    private var lastWallMs = 0L
    private var haveSun = false

    /** The frame-clock instant before which [sun] cannot change; see [solarBoundary]. */
    private var sunValidUntilElapsedMs = Long.MAX_VALUE

    fun onLocation(
        nowElapsedMs: Long,
        wallMs: Long,
        tzOffsetMinutes: Int,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        hasAltitude: Boolean,
        verticalAccuracyMeters: Double,
        hasVerticalAccuracy: Boolean,
        speed: Double,
    ) {
        val dt = if (haveFix) (nowElapsedMs - lastFixMs) / 1000.0 else 1.0
        lastFixMs = nowElapsedMs
        haveFix = true
        currentSpeed = speed.coerceAtLeast(0.0)
        maybeStartTrip(nowElapsedMs)
        advance(nowElapsedMs)

        gnss.onFix(
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            hasAltitudeFix = hasAltitude,
            verticalAccuracyMeters = verticalAccuracyMeters,
            hasVerticalAccuracy = hasVerticalAccuracy,
            speed = currentSpeed,
            dt = dt,
            accumulate = tripStarted,
        )

        lastLat = latitude
        lastLon = longitude
        lastTz = tzOffsetMinutes
        lastWallMs = wallMs
        updateSun(nowElapsedMs)
    }

    fun onGuidance(distanceMeters: Int?, timeSeconds: Int?, valid: Boolean, nowElapsedMs: Long) {
        advance(nowElapsedMs)
        if (valid && (distanceMeters != null || timeSeconds != null)) {
            guidanceDistance = distanceMeters
            guidanceTime = timeSeconds
            guidanceValid = true
        } else {
            guidanceValid = false
        }
    }

    /**
     * Every rendered frame. The sun is recomputed only once its next event, or local midnight, has
     * come: between those the label cannot change, and the strip asks thirty times a second.
     */
    fun onTick(nowElapsedMs: Long) {
        advance(nowElapsedMs)
        if (haveSun && sunClock(nowElapsedMs) >= sunValidUntilElapsedMs) updateSun(nowElapsedMs)
    }

    /**
     * Live-proven gearbox park switch. Entering P ends the in-memory trip immediately; an
     * unavailable read leaves the existing GNSS fallback in charge.
     */
    fun onParkState(value: Boolean?, nowElapsedMs: Long) {
        if (value == null) {
            parked = null
            return
        }
        if (parked == value) return
        parked = value
        if (value) resetTrip(nowElapsedMs)
    }

    /**
     * The shell-readable fact says only P versus not-P, not which drive gear is selected, so
     * sustained movement remains the honest trip-start proxy. Credit begins when movement
     * began, not when the sustain gate confirms it.
     */
    private fun maybeStartTrip(nowElapsedMs: Long) {
        if (tripStarted) return
        if (parked == true) {
            movementCandidateMs = -1L
            return
        }
        if (currentSpeed >= TRIP_START_SPEED) {
            if (movementCandidateMs < 0) movementCandidateMs = nowElapsedMs
            if ((nowElapsedMs - movementCandidateMs) / 1000.0 >= TRIP_START_SUSTAIN_SECONDS) {
                tripStarted = true
                tripStartElapsedMs = movementCandidateMs
            }
        } else {
            movementCandidateMs = -1L
        }
    }

    private fun advance(nowElapsedMs: Long) {
        elapsedSeconds = if (tripStarted) {
            (nowElapsedMs - tripStartElapsedMs).coerceAtLeast(0L) / 1000.0
        } else {
            0.0
        }
    }

    private fun resetTrip(nowElapsedMs: Long) {
        tripStarted = false
        tripStartElapsedMs = nowElapsedMs
        movementCandidateMs = -1L
        elapsedSeconds = 0.0
        gnss = GnssTripAccumulator()
    }

    /**
     * The sun at [nowElapsedMs], read off the last fix's wall clock carried forward on the frame
     * clock; a frame stamped before that fix counts as the fix's own moment.
     */
    private fun updateSun(nowElapsedMs: Long) {
        haveSun = true
        val at = sunClock(nowElapsedMs)
        val boundary = solarBoundary(SolarMath.toLocalTime(lastWallMs + (at - lastFixMs), lastTz))
        sun = SunInfo(nextIsSunset = boundary.nextIsSunset, nextEventLabel = boundary.label)
        sunValidUntilElapsedMs = at + boundary.validForMs
    }

    private fun sunClock(nowElapsedMs: Long): Long = maxOf(nowElapsedMs, lastFixMs)

    private data class SolarBoundary(
        val nextIsSunset: Boolean,
        val label: String,
        /**
         * How long this answer stands. Within one local day it can change only when the clock
         * passes the sunrise or the sunset ahead of it, and the day itself ends at midnight.
         */
        val validForMs: Long,
    )

    private fun solarBoundary(local: SolarMath.LocalTime): SolarBoundary {
        val today = SolarMath.daylight(local.date, lastLat, lastLon, lastTz)
        val now = local.minutesOfDay
        val untilMidnight = millisUntil(MINUTES_PER_DAY, now)
        if (!today.hasEvents) {
            return SolarBoundary(nextIsSunset = today.alwaysUp, label = "", validForMs = untilMidnight)
        }
        return when {
            now < today.sunriseMinutes -> SolarBoundary(
                nextIsSunset = false,
                label = minutesToClock(today.sunriseMinutes),
                validForMs = minOf(millisUntil(today.sunriseMinutes, now), untilMidnight),
            )
            now < today.sunsetMinutes -> SolarBoundary(
                nextIsSunset = true,
                label = minutesToClock(today.sunsetMinutes),
                validForMs = minOf(millisUntil(today.sunsetMinutes, now), untilMidnight),
            )
            else -> {
                val tomorrow = SolarMath.daylight(
                    SolarMath.civilFromDays(daysOf(local) + 1),
                    lastLat,
                    lastLon,
                    lastTz,
                )
                val target = if (tomorrow.hasEvents) tomorrow.sunriseMinutes else today.sunriseMinutes
                SolarBoundary(
                    nextIsSunset = false,
                    label = minutesToClock(target),
                    validForMs = untilMidnight,
                )
            }
        }
    }

    /** Whole milliseconds from [now] to the first one at or past [target], both in minutes of day. */
    private fun millisUntil(target: Double, now: Double): Long =
        (ceil(target * MILLIS_PER_MINUTE).toLong() - (now * MILLIS_PER_MINUTE).roundToLong())
            .coerceAtLeast(1L)

    private fun daysOf(local: SolarMath.LocalTime): Long {
        var year = local.date.year.toLong()
        val month = local.date.month.toLong()
        val day = local.date.day.toLong()
        if (month <= 2) year -= 1
        val era = (if (year >= 0) year else year - 399) / 400
        val yearOfEra = year - era * 400
        val dayOfYear = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146097 + dayOfEra - 719468
    }

    fun smoothedAltitude(): Double = gnss.smoothedAltitude
    fun hasAltitude(): Boolean = gnss.hasAltitude
    fun variometer(): Double = gnss.variometer
    fun distanceMeters(): Double = gnss.distanceMeters
    fun sunInfo(): SunInfo = sun

    /**
     * The two halves of a route the strip prints, read as plain numbers: the strip asks thirty
     * times a second and an object per frame is garbage for an answer that changes once a minute.
     * `-1` is a half the route does not have, or no route at all.
     */
    fun remainingMeters(): Int = if (guidanceValid) guidanceDistance ?: -1 else -1

    fun remainingSeconds(): Int = if (guidanceValid) guidanceTime ?: -1 else -1

    val guiding: Boolean get() = guidanceValid

    companion object {
        const val TRIP_START_SPEED = 2.0
        const val TRIP_START_SUSTAIN_SECONDS = 3.0
        private const val MINUTES_PER_DAY = 1440.0
        private const val MILLIS_PER_MINUTE = 60_000.0

        fun minutesToClock(minutes: Double): String {
            var normalized = ((minutes.roundToInt() % 1440) + 1440) % 1440
            val hours = normalized / 60
            normalized %= 60
            return "%d:%02d".format(hours, normalized)
        }
    }
}
