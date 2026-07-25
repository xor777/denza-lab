package dev.denza.apps.feature.trip

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * The single shared data engine behind all three renderers.
 *
 * Everything is fed from product-usable inputs only (standard IMU, standard
 * GNSS, offline sun math, and the app's existing validated Yandex guidance).
 * The engine is pure Kotlin with no Android imports so the derived logic can be
 * unit-tested on the JVM; the Android adapters ([TripSensorHub]) push samples in
 * and the renderers read the derived state out, all on the main thread.
 *
 * Dynamics come from physics, not IMU statistics (first live drive finding):
 *  - lateral = GNSS ground speed x gyro yaw rate (v*w, centripetal). Positive =
 *    left turn. Always available, no calibration.
 *  - longitudinal = derivative of smoothed GNSS ground speed ([SpeedDynamics]).
 *    Positive = accelerating, negative = braking.
 *  - vertical = gravity-projected IMU residual ([AxisCalibrator]) — unchanged.
 *  - agitation = |lateral| (+) |longitudinal| (+) weighted |vertical|, blended
 *    as a vector magnitude.
 *
 * Trip semantics (revised after the first live drive): the engine lives for the
 * whole process ([TripSession]), so reopening the panel never resets the trip.
 * The trip CLOCK starts at the first sustained movement (speed >=
 * [TRIP_START_SPEED] held for [TRIP_START_SUSTAIN_SECONDS]); before that
 * «В пути» shows 0:00 and distance/climb/route/event accumulation waits.
 * Sensors stop while the panel is hidden — the resulting data gaps are honest —
 * but elapsed time is wall-clock from the trip start. Stops longer than 15
 * minutes only add a label; they never end the trip. Nothing is persisted.
 *
 * Deterministic event thresholds (documented once, here):
 *  - CLIMB  ("набор/подъём +N м"): positive climb within the last 40 s >= 40 m.
 *  - TURN   ("вираж"): |yaw rate| >= 0.35 rad/s sustained >= 2.5 s at > 5 m/s.
 *  - CALM   ("ровный участок"): agitation < 0.9 m/s^2, |vario| < 0.25 m/s and
 *           speed > 3 m/s throughout a 20 s window (never while standing).
 *  - CREST  ("гребень N м"): smoothed variometer crosses +0.2 -> -0.2 m/s.
 *  - STOP   ("остановка N мин"): ground speed < 0.5 m/s for >= 15 min (from GNSS).
 *  - SERPENTINE ("серпантин"): >= 4 yaw-sign reversals (|yaw| >= 0.2) within 30 s while moving.
 */
class TripEngine {

    private val axis = AxisCalibrator()
    private val speedDynamics = SpeedDynamics()
    private val agitation = AgitationTracker()
    private val buckets = RmsBucketAggregator()
    private val gnss = GnssTripAccumulator()
    private val course = CourseTracker()

    private var lastImuMs = 0L
    private var haveImu = false
    private var lastFixMs = 0L
    private var haveFix = false

    // Movement-gated trip clock.
    var tripStarted: Boolean = false
        private set
    private var tripStartElapsedMs = 0L
    private var movementCandidateMs = -1L

    // Latest instantaneous derived signals (read per frame by the renderers).
    var elapsedSeconds: Double = 0.0
        private set

    /** Centripetal lateral acceleration v*w, m/s^2. Positive = left turn. */
    var lateralAccel: Double = 0.0
        private set

    /** GNSS-derived longitudinal acceleration, m/s^2. Negative = braking. */
    val longitudinalAccel: Double get() = speedDynamics.longitudinalAccel
    var verticalAccel: Double = 0.0
        private set

    /** Yaw rate about the vertical axis, rad/s. Positive = left turn. */
    var yawRate: Double = 0.0
        private set
    var latestAgitation: Double = 0.0
        private set
    var currentSpeed: Double = 0.0
        private set

    // Guidance (validated Yandex only; fail-closed). The Android adapter decides
    // validity via HudGuidanceRuntime.remaining(); the engine just holds the last
    // valid figures and drops them the moment the adapter reports invalid.
    private var guidanceDistance: Int? = null
    private var guidanceTime: Int? = null
    private var guidanceValidFlag = false

    // Sun / daylight.
    private var sun = SunInfo(false, 0.0, 0.0, true, "", -1L)
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastTz = 0
    private var lastWallMs = 0L
    private var haveSun = false

    private val events = ArrayDeque<TripEvent>()
    private var eventLane = 0

    // Thread shaping: a free-running yaw integral and its slow average. Only the
    // difference is used (how hard we are turning relative to the road's general
    // direction), so no absolute heading reference is needed.
    private var headingIntegral = 0.0
    private var headingSlow = 0.0

    // Turn / serpentine / calm / crest detection state.
    private var turnSustain = 0.0
    private var turnArmed = true
    private var calmFlatSeconds = 0.0
    private var calmFlatArmed = true
    private var prevVarioSign = 0
    private val yawReversals = ArrayDeque<Double>()
    private var lastYawSign = 0

    // ---- inputs -------------------------------------------------------------

    fun onImu(
        nowElapsedMs: Long,
        ax: Double, ay: Double, az: Double,
        gravX: Double, gravY: Double, gravZ: Double,
        gyroX: Double, gyroY: Double, gyroZ: Double,
    ) {
        val dt = if (haveImu) (nowElapsedMs - lastImuMs) / 1000.0 else 1.0 / 30.0
        lastImuMs = nowElapsedMs
        haveImu = true
        advance(nowElapsedMs)

        speedDynamics.onIdle(dt)
        val reading = axis.update(
            ax, ay, az,
            gravX, gravY, gravZ,
            gyroX, gyroY, gyroZ,
            dt,
        )
        verticalAccel = reading.vertical
        yawRate = reading.yawRate
        // Physics lateral: centripetal v*w. Uses the smoothed (stale-decaying)
        // GNSS speed, so with no fix there is honestly no claimed lateral.
        lateralAccel = speedDynamics.smoothedSpeed * reading.yawRate
        val a = agitationMagnitude(lateralAccel, longitudinalAccel, reading.vertical)
        latestAgitation = a
        agitation.update(a, abs(reading.vertical), dt)
        buckets.add(a, dt)

        if (tripStarted) {
            detectTurn(reading.yawRate, dt)
            detectSerpentine(reading.yawRate)
            detectCalm(a, dt)
        }
        // Feed yaw into the held course so parking turns rotate the compass tape.
        course.onYaw(reading.yawRate, dt)

        headingIntegral += reading.yawRate * dt
        headingSlow += (headingIntegral - headingSlow) * (1.0 - exp(-dt / HEADING_SLOW_TAU))
    }

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
        bearing: Double,
        hasBearing: Boolean,
        speed: Double,
        accuracyMeters: Double,
    ) {
        val dt = if (haveFix) (nowElapsedMs - lastFixMs) / 1000.0 else 1.0
        lastFixMs = nowElapsedMs
        haveFix = true
        currentSpeed = speed.coerceAtLeast(0.0)
        maybeStartTrip(nowElapsedMs)
        advance(nowElapsedMs)
        speedDynamics.onFix(currentSpeed, dt)

        val prevAlt = gnss.smoothedAltitude
        val hadAlt = gnss.hasAltitude
        val crossedStop = gnss.onFix(
            latitude, longitude,
            altitude, hasAltitude, verticalAccuracyMeters, hasVerticalAccuracy,
            currentSpeed, elapsedSeconds, dt,
            accumulate = tripStarted,
        )
        course.onFix(hasBearing, bearing, speed, dt)

        lastLat = latitude
        lastLon = longitude
        lastTz = tzOffsetMinutes
        lastWallMs = wallMs
        updateSun()

        if (tripStarted) {
            val timeColor = timeColorKey()
            gnss.maybeAddRoutePoint(
                elapsedSeconds, latitude, longitude,
                timeColor, energy01(), headingIntegral - headingSlow, calm01(),
            )

            // Deterministic elevation events.
            if (gnss.windowClimbMeters >= CLIMB_EVENT_METERS) {
                emit(TripEventKind.CLIMB, gnss.windowClimbMeters)
                gnss.consumeWindowClimb()
            }
            if (hadAlt && gnss.hasAltitude) {
                val newSign = when {
                    gnss.variometer > CREST_VARIO -> 1
                    gnss.variometer < -CREST_VARIO -> -1
                    else -> 0
                }
                if (prevVarioSign > 0 && newSign < 0 && gnss.smoothedAltitude >= prevAlt - 2.0) {
                    emit(TripEventKind.CREST, gnss.smoothedAltitude)
                }
                if (newSign != 0) prevVarioSign = newSign
            }
            if (crossedStop) {
                emit(TripEventKind.STOP, gnss.currentStopSeconds / 60.0)
            }
        }
    }

    fun onGuidance(distanceMeters: Int?, timeSeconds: Int?, valid: Boolean, nowElapsedMs: Long) {
        advance(nowElapsedMs)
        if (valid && (distanceMeters != null || timeSeconds != null)) {
            guidanceDistance = distanceMeters
            guidanceTime = timeSeconds
            guidanceValidFlag = true
        } else {
            guidanceValidFlag = false
        }
    }

    /** Advance timers / countdown without new samples (called each frame). */
    fun onTick(nowElapsedMs: Long) {
        advance(nowElapsedMs)
        if (haveSun) updateSunCountdown(nowElapsedMs)
    }

    /**
     * The trip clock starts at the first sustained movement — reading gear/Drive
     * is impossible for a normal APK (BYDAUTO permissions), so movement is the
     * honest proxy. The clock is credited from the moment movement began, not
     * from when the sustain gate confirmed it.
     */
    private fun maybeStartTrip(nowElapsedMs: Long) {
        if (tripStarted) return
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

    // ---- event detection ----------------------------------------------------

    private fun detectTurn(yawRate: Double, dt: Double) {
        if (abs(yawRate) >= TURN_YAW && currentSpeed > TURN_MIN_SPEED) {
            turnSustain += dt
            if (turnArmed && turnSustain >= TURN_SUSTAIN_SECONDS) {
                emit(TripEventKind.TURN, 0.0)
                turnArmed = false
            }
        } else {
            turnSustain = 0.0
            turnArmed = true
        }
    }

    private fun detectSerpentine(yawRate: Double) {
        if (currentSpeed <= 2.0) return
        val s = if (abs(yawRate) >= SERPENTINE_YAW) sign(yawRate).toInt() else 0
        if (s != 0 && lastYawSign != 0 && s != lastYawSign) {
            yawReversals.addLast(elapsedSeconds)
            while (yawReversals.isNotEmpty() &&
                elapsedSeconds - yawReversals.first() > SERPENTINE_WINDOW_SECONDS
            ) {
                yawReversals.removeFirst()
            }
            if (yawReversals.size >= SERPENTINE_REVERSALS) {
                emit(TripEventKind.SERPENTINE, yawReversals.size.toDouble())
                yawReversals.clear()
            }
        }
        if (s != 0) lastYawSign = s
    }

    private fun detectCalm(agitationNow: Double, dt: Double) {
        // A light-stop must never count as a "ровный участок": the whole calm
        // window has to be driven (speed > CALM_MIN_SPEED throughout).
        val flat = agitationNow < CALM_AGITATION &&
            abs(gnss.variometer) < CALM_VARIO &&
            currentSpeed > CALM_MIN_SPEED
        if (flat) {
            calmFlatSeconds += dt
            if (calmFlatArmed && calmFlatSeconds >= CALM_FLAT_SECONDS) {
                emit(TripEventKind.CALM, 0.0)
                calmFlatArmed = false
            }
        } else {
            calmFlatSeconds = 0.0
            calmFlatArmed = true
        }
    }

    private fun emit(kind: TripEventKind, value: Double) {
        events.addLast(TripEvent(kind, value, elapsedSeconds, eventLane))
        eventLane = eventLane xor 1
        while (events.size > EVENT_CAPACITY) events.removeFirst()
    }

    // ---- sun ----------------------------------------------------------------

    private fun updateSun() {
        haveSun = true
        val local = SolarMath.toLocalTime(lastWallMs, lastTz)
        val (az, elev) = SolarMath.position(local, lastLat, lastLon, lastTz)
        val boundary = solarBoundary(local)
        sun = SunInfo(
            hasPosition = true,
            azimuthDeg = az,
            elevationDeg = elev,
            nextIsSunset = boundary.nextIsSunset,
            nextEventLabel = boundary.label,
            countdownSeconds = boundary.countdownSeconds,
        )
    }

    private fun updateSunCountdown(nowElapsedMs: Long) {
        if (!haveSun) return
        // Roll the wall clock forward by elapsed real time since the last fix so
        // the countdown keeps ticking between GNSS updates without new math.
        val approxWall = lastWallMs + (nowElapsedMs - lastFixMs).coerceAtLeast(0L)
        val local = SolarMath.toLocalTime(approxWall, lastTz)
        val boundary = solarBoundary(local)
        sun = sun.copy(
            nextIsSunset = boundary.nextIsSunset,
            nextEventLabel = boundary.label,
            countdownSeconds = boundary.countdownSeconds,
        )
    }

    private data class SolarBoundary(val nextIsSunset: Boolean, val label: String, val countdownSeconds: Long)

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
                    lastLat, lastLon, lastTz,
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
        // Days since epoch for the local date (midnight), reused for "tomorrow".
        var y = local.date.year.toLong()
        val m = local.date.month.toLong()
        val d = local.date.day.toLong()
        if (m <= 2) y -= 1
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097 + doe - 719468
    }

    /**
     * Time-of-day colour key 0..1 for the mode-3 thread: dawn(0) -> day -> golden
     * -> evening -> night(1). Uses sun times when available, else the clock only.
     */
    private fun timeColorKey(): Double {
        if (!haveSun) return 0.25
        val local = SolarMath.toLocalTime(lastWallMs, lastTz)
        val day = SolarMath.daylight(local.date, lastLat, lastLon, lastTz)
        if (!day.hasEvents) return if (day.alwaysUp) 0.25 else 0.95
        val t = local.minutesOfDay
        val sr = day.sunriseMinutes
        val ss = day.sunsetMinutes
        return when {
            t < sr - 40 -> 0.95
            t < sr + 50 -> lerp(0.0, 0.15, (t - (sr - 40)) / 90.0)
            t < ss - 90 -> lerp(0.15, 0.28, (t - (sr + 50)) / (ss - 90 - (sr + 50)).coerceAtLeast(1.0))
            t < ss -> lerp(0.28, 0.5, (t - (ss - 90)) / 90.0)
            t < ss + 45 -> lerp(0.5, 0.75, (t - ss) / 45.0)
            else -> lerp(0.75, 0.95, ((t - (ss + 45)) / 60.0).coerceAtMost(1.0))
        }.coerceIn(0.0, 1.0)
    }

    // ---- read surface for renderers ----------------------------------------

    fun agitationScore(): Int = agitation.smoothnessScore
    fun calmSeconds(): Double = agitation.calmSeconds
    fun verticalEnergy(): Double = agitation.verticalEnergy
    fun smoothedAltitude(): Double = gnss.smoothedAltitude
    fun hasAltitude(): Boolean = gnss.hasAltitude
    fun variometer(): Double = gnss.variometer
    fun tripClimbMeters(): Double = gnss.tripClimbMeters
    fun distanceMeters(): Double = gnss.distanceMeters
    fun headingDeg(): Double = course.courseDeg
    fun hasHeading(): Boolean = course.hasCourse

    /** True when the course is a stale held value at a standstill (dim the tape). */
    fun courseDimmed(): Boolean = course.held
    fun hasFix(): Boolean = haveFix
    fun sunInfo(): SunInfo = sun

    fun guidance(): GuidanceRemaining? {
        if (!guidanceValidFlag) return null
        return GuidanceRemaining(guidanceDistance, guidanceTime)
    }

    fun bucketCount(): Int = buckets.count
    fun bucketPartial(): Double = buckets.partialRms
    fun copyBucketsInto(out: FloatArray): Int = buckets.copyInto(out)

    fun elevationCount(): Int = gnss.elevationCount()
    fun copyElevationInto(out: FloatArray): Int = gnss.copyElevationInto(out)
    fun elevationSamples(): List<ElevationSample> = gnss.elevationSamples()

    /** 0..1 calmness from the smoothed agitation; 1 on a smooth road. */
    fun calm01(): Double = (1.0 - agitation.smoothedAgitation / CALM_FULL_SCALE).coerceIn(0.0, 1.0)

    fun routePointCount(): Int = gnss.routePointCount()
    fun routePoints(): List<RoutePoint> = gnss.routePoints()
    fun copyRouteInto(
        elapsed: FloatArray,
        distance: FloatArray,
        altitude: FloatArray,
        color: FloatArray,
        energy: FloatArray,
        turn: FloatArray,
        calm: FloatArray,
    ): Int = gnss.copyRouteInto(elapsed, distance, altitude, color, energy, turn, calm)

    /** Read-only, allocation-free event access for the renderers. */
    fun eventCount(): Int = events.size
    fun eventAt(index: Int): TripEvent = events[index]

    /** Snapshot copy for tests. */
    fun events(): List<TripEvent> = events.toList()

    private fun energy01(): Double = (latestAgitation / ENERGY_FULL_SCALE).coerceIn(0.0, 1.0)

    private fun agitationMagnitude(lateral: Double, longitudinal: Double, vertical: Double): Double =
        hypot(hypot(lateral, longitudinal), vertical * VERTICAL_WEIGHT)

    companion object {
        private const val VERTICAL_WEIGHT = 1.3

        /** Route-thread glow full scale, m/s^2 (a hard corner/brake maxes the glow). */
        private const val ENERGY_FULL_SCALE = 6.0

        /** Smoothed agitation (m/s^2) at which the thread reads as fully unsettled. */
        private const val CALM_FULL_SCALE = 2.2

        /** Time constant of the slow heading average the thread's turns lean against. */
        private const val HEADING_SLOW_TAU = 22.0
        const val TRIP_START_SPEED = 2.0
        const val TRIP_START_SUSTAIN_SECONDS = 3.0
        const val CLIMB_EVENT_METERS = 40.0
        const val CREST_VARIO = 0.2
        const val TURN_YAW = 0.35
        const val TURN_SUSTAIN_SECONDS = 2.5
        const val TURN_MIN_SPEED = 5.0
        const val CALM_AGITATION = 0.9
        const val CALM_VARIO = 0.25
        const val CALM_MIN_SPEED = 3.0
        const val CALM_FLAT_SECONDS = 20.0
        const val SERPENTINE_YAW = 0.2
        const val SERPENTINE_WINDOW_SECONDS = 30.0
        const val SERPENTINE_REVERSALS = 4
        const val EVENT_CAPACITY = 64

        private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t.coerceIn(0.0, 1.0)

        fun minutesToClock(minutes: Double): String {
            var m = ((minutes.roundToInt() % 1440) + 1440) % 1440
            val h = m / 60
            m %= 60
            return "%d:%02d".format(h, m)
        }
    }
}
