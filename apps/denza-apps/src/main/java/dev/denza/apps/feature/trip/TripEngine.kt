package dev.denza.apps.feature.trip

import kotlin.math.roundToInt

/**
 * Process-lifetime state behind the visible trip panel.
 *
 * The engine keeps only values the active spectrum layout renders: the
 * movement-gated trip clock, GNSS distance/altitude/climb, validated Yandex
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

    private var sun = SunInfo(true, "", -1L)
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastTz = 0
    private var lastWallMs = 0L
    private var haveSun = false

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
        updateSun()
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

    fun onTick(nowElapsedMs: Long) {
        advance(nowElapsedMs)
        if (haveSun) updateSunCountdown(nowElapsedMs)
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

    private fun updateSun() {
        haveSun = true
        val local = SolarMath.toLocalTime(lastWallMs, lastTz)
        val boundary = solarBoundary(local)
        sun = SunInfo(
            nextIsSunset = boundary.nextIsSunset,
            nextEventLabel = boundary.label,
            countdownSeconds = boundary.countdownSeconds,
        )
    }

    private fun updateSunCountdown(nowElapsedMs: Long) {
        val approxWall = lastWallMs + (nowElapsedMs - lastFixMs).coerceAtLeast(0L)
        val local = SolarMath.toLocalTime(approxWall, lastTz)
        val boundary = solarBoundary(local)
        sun = sun.copy(
            nextIsSunset = boundary.nextIsSunset,
            nextEventLabel = boundary.label,
            countdownSeconds = boundary.countdownSeconds,
        )
    }

    private data class SolarBoundary(
        val nextIsSunset: Boolean,
        val label: String,
        val countdownSeconds: Long,
    )

    private fun solarBoundary(local: SolarMath.LocalTime): SolarBoundary {
        val today = SolarMath.daylight(local.date, lastLat, lastLon, lastTz)
        if (!today.hasEvents) {
            return SolarBoundary(nextIsSunset = today.alwaysUp, label = "", countdownSeconds = -1L)
        }
        val now = local.minutesOfDay
        return when {
            now < today.sunriseMinutes -> SolarBoundary(
                nextIsSunset = false,
                label = minutesToClock(today.sunriseMinutes),
                countdownSeconds = ((today.sunriseMinutes - now) * 60.0).toLong(),
            )
            now < today.sunsetMinutes -> SolarBoundary(
                nextIsSunset = true,
                label = minutesToClock(today.sunsetMinutes),
                countdownSeconds = ((today.sunsetMinutes - now) * 60.0).toLong(),
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
                    countdownSeconds = (((1440.0 - now) + target) * 60.0).toLong(),
                )
            }
        }
    }

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
    fun tripClimbMeters(): Double = gnss.tripClimbMeters
    fun distanceMeters(): Double = gnss.distanceMeters
    fun sunInfo(): SunInfo = sun

    fun guidance(): GuidanceRemaining? {
        if (!guidanceValid) return null
        return GuidanceRemaining(guidanceDistance, guidanceTime)
    }

    companion object {
        const val TRIP_START_SPEED = 2.0
        const val TRIP_START_SUSTAIN_SECONDS = 3.0

        fun minutesToClock(minutes: Double): String {
            var normalized = ((minutes.roundToInt() % 1440) + 1440) % 1440
            val hours = normalized / 60
            normalized %= 60
            return "%d:%02d".format(hours, normalized)
        }
    }
}
