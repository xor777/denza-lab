package dev.denza.apps.feature.trip

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Derives journey geometry from ~1 Hz standard-Android GNSS fixes.
 *
 * Nothing here is persisted. Ground speed is used internally only (distance
 * gating, stop detection, altitude-integrity gating) and is deliberately never
 * exposed for display — the cluster/HUD/navigator already show speed.
 *
 * Altitude integrity (first live drive findings):
 *  - A fix seeds or updates the altitude channel only when its reported vertical
 *    accuracy is decent (<= [maxVerticalAccuracyMeters]). Without a reported
 *    accuracy, seeding additionally requires [seedConsistencyFixes] consecutive
 *    fixes that agree within [seedConsistencyBandMeters] — one cold-fix garbage
 *    altitude must never seed the smoother.
 *  - While ground speed < [climbFreezeSpeed] the smoother HOLDS its value, the
 *    variometer decays to zero, and trip climb is frozen: parked GPS altitude
 *    wander (+-12 m observed) must not read as climbing.
 *  - On the parked->moving transition the smoother re-seeds at the current fix
 *    and the climb rest point re-anchors there, so baseline drift across a stop
 *    can never turn into phantom climb.
 *  - Trip climb accumulates via a rest-point stair-step: only when the smoothed
 *    altitude clears the last rest point by >= [climbStepMeters] does the gain
 *    count (and the rest point follows the altitude down through descents), so
 *    a random walk cannot inflate the total while real hills still count.
 *
 * Pure and JVM-testable.
 */
class GnssTripAccumulator(
    /** Altitude low-pass time constant, seconds. */
    private val altitudeTau: Double = 4.0,
    /** Variometer low-pass time constant, seconds. */
    private val varioTau: Double = 3.0,
    /** Below this ground speed (m/s) the car is treated as stopped. */
    private val stopSpeed: Double = 0.5,
    /** Below this ground speed (m/s) the altitude channel freezes (no climb, vario decays). */
    private val climbFreezeSpeed: Double = 1.0,
    /** Variometer decay time constant while frozen, seconds. */
    private val varioFreezeTau: Double = 1.5,
    /** Stair-step: smoothed altitude must clear the rest point by this much to count. */
    private val climbStepMeters: Double = 3.0,
    /** Worst acceptable reported vertical accuracy for the altitude channel, meters. */
    private val maxVerticalAccuracyMeters: Double = 20.0,
    /** Without reported accuracy: consecutive agreeing fixes required to seed. */
    private val seedConsistencyFixes: Int = 3,
    /** Without reported accuracy: max spread between consecutive seeding fixes, meters. */
    private val seedConsistencyBandMeters: Double = 15.0,
    /** Minimum spacing between retained thread points (meters or seconds). */
    private val routeMinMeters: Double = 25.0,
    private val routeMinSeconds: Double = 4.0,
    /** Thread points retained before the spacing doubles (whole trip, bounded). */
    private val routeCapacity: Int = 1200,
) {
    private var hasPrev = false
    private var prevLat = 0.0
    private var prevLon = 0.0

    var distanceMeters: Double = 0.0
        private set
    var tripClimbMeters: Double = 0.0
        private set

    private var altitudeSeeded = false
    var smoothedAltitude: Double = 0.0
        private set

    /** True only once the altitude channel has been (accuracy-gated) seeded. */
    val hasAltitude: Boolean get() = altitudeSeeded
    var variometer: Double = 0.0
        private set

    /** Seconds continuously stopped; never ends the trip, only labels it. */
    var currentStopSeconds: Double = 0.0
        private set
    private var stopLabelArmed = true

    /** Positive climb over the current climb window (drives "набор +N м"). */
    var windowClimbMeters: Double = 0.0
        private set

    // Stair-step climb state.
    private var restPointAltitude = 0.0
    private var pendingReanchor = false
    private var wasClimbMoving = false

    // Seeding-without-accuracy consistency state.
    private var pendingSeedAltitude = 0.0
    private var pendingSeedCount = 0

    private val routePoints = ArrayList<RoutePoint>()
    private var lastRouteElapsed = -1.0e9
    private var lastRouteLat = 0.0
    private var lastRouteLon = 0.0
    private var routeSpacingMeters = routeMinMeters
    private var routeSpacingSeconds = routeMinSeconds

    /**
     * Feed one fix. [accumulate] is false before the trip clock has started
     * (movement-gated in the engine): the altitude smoother still warms up and
     * seeds, but distance, climb, the thread, and stop labelling wait.
     *
     * @return true when a new 15-minute stop threshold was crossed this update.
     */
    fun onFix(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        hasAltitudeFix: Boolean,
        verticalAccuracyMeters: Double,
        hasVerticalAccuracy: Boolean,
        speed: Double,
        elapsedSeconds: Double,
        dt: Double,
        accumulate: Boolean,
    ): Boolean {
        val step = dt.coerceIn(0.0, 5.0)
        val climbMoving = speed >= climbFreezeSpeed

        // Distance: only in-trip and only while moving so GPS jitter never drifts.
        if (accumulate && hasPrev && speed >= stopSpeed) {
            val d = haversine(prevLat, prevLon, latitude, longitude)
            if (d < 400.0) distanceMeters += d // reject single-fix GPS glitches
        }
        prevLat = latitude
        prevLon = longitude
        hasPrev = true

        // Altitude channel.
        if (!climbMoving) {
            // Parked/creeping: the variometer decays to zero and the smoother
            // holds below; re-anchor everything at the next real movement.
            variometer *= exp(-step / varioFreezeTau)
            pendingReanchor = true
        }
        if (hasAltitudeFix && altitudeAcceptable(hasVerticalAccuracy, verticalAccuracyMeters)) {
            if (!altitudeSeeded) {
                maybeSeed(altitude, hasVerticalAccuracy)
            } else if (climbMoving) {
                if (pendingReanchor) {
                    // Resuming movement: re-seed at the current fix so parked GPS
                    // baseline drift can never surface as climb or vario.
                    smoothedAltitude = altitude
                    restPointAltitude = altitude
                    pendingReanchor = false
                } else {
                    val prev = smoothedAltitude
                    val a = 1.0 - exp(-step / altitudeTau)
                    smoothedAltitude += (altitude - smoothedAltitude) * a
                    val rate = if (step > 0) (smoothedAltitude - prev) / step else 0.0
                    val av = 1.0 - exp(-step / varioTau)
                    variometer += (rate - variometer) * av
                    stairStep(elapsedSeconds, accumulate)
                }
            }
        }
        pruneClimbWindow(elapsedSeconds)
        wasClimbMoving = climbMoving

        // Stop detection: label crossings at 15 minutes, re-arm on movement.
        // Waits for the trip like every other accumulation.
        val crossed: Boolean
        if (!accumulate) {
            currentStopSeconds = 0.0
            stopLabelArmed = true
            crossed = false
        } else if (speed < stopSpeed) {
            val before = currentStopSeconds
            currentStopSeconds += step
            crossed = stopLabelArmed && before < STOP_THRESHOLD_SECONDS &&
                currentStopSeconds >= STOP_THRESHOLD_SECONDS
            if (crossed) stopLabelArmed = false
        } else {
            currentStopSeconds = 0.0
            stopLabelArmed = true
            crossed = false
        }

        return crossed
    }

    /** Accuracy gate for the altitude channel (seeded or not). */
    private fun altitudeAcceptable(hasVacc: Boolean, vacc: Double): Boolean =
        !hasVacc || vacc <= maxVerticalAccuracyMeters

    /** Seed the smoother; without reported accuracy, demand consistent fixes. */
    private fun maybeSeed(altitude: Double, hasVacc: Boolean) {
        if (!hasVacc) {
            pendingSeedCount =
                if (pendingSeedCount > 0 && abs(altitude - pendingSeedAltitude) <= seedConsistencyBandMeters) {
                    pendingSeedCount + 1
                } else {
                    1
                }
            pendingSeedAltitude = altitude
            if (pendingSeedCount < seedConsistencyFixes) return
        }
        altitudeSeeded = true
        smoothedAltitude = altitude
        restPointAltitude = altitude
        pendingReanchor = false
    }

    /**
     * Rest-point stair-step: descents drag the rest point down; a climb counts
     * only once it clears the rest point by [climbStepMeters], then the rest
     * point moves up. Random-walk noise below the step never accumulates.
     */
    private fun stairStep(elapsedSeconds: Double, accumulate: Boolean) {
        if (smoothedAltitude < restPointAltitude) {
            restPointAltitude = smoothedAltitude
        } else if (smoothedAltitude - restPointAltitude >= climbStepMeters) {
            val gain = smoothedAltitude - restPointAltitude
            restPointAltitude = smoothedAltitude
            if (accumulate) {
                tripClimbMeters += gain
                climbWindow.addLast(ElevationSample(elapsedSeconds, gain))
            }
        }
    }

    /**
     * Append a thread point (called by the engine with fused colour/energy/turn).
     *
     * Like the elevation series, the thread keeps the WHOLE trip: when the buffer
     * fills, every second point is dropped and the spacing doubles, so a long
     * drive thins out evenly instead of losing where it began.
     */
    fun maybeAddRoutePoint(
        elapsedSeconds: Double,
        latitude: Double,
        longitude: Double,
        timeColor: Double,
        energy: Double,
        turn: Double,
        calm: Double,
    ) {
        val movedEnough = routePoints.isEmpty() ||
            haversine(lastRouteLat, lastRouteLon, latitude, longitude) >= routeSpacingMeters ||
            elapsedSeconds - lastRouteElapsed >= routeSpacingSeconds
        if (!movedEnough) return
        routePoints.add(
            RoutePoint(
                elapsedSeconds = elapsedSeconds,
                distanceMeters = distanceMeters,
                altitudeMeters = if (altitudeSeeded) smoothedAltitude else 0.0,
                timeColor = timeColor,
                energy = energy,
                turn = turn,
                calm = calm,
            ),
        )
        lastRouteElapsed = elapsedSeconds
        lastRouteLat = latitude
        lastRouteLon = longitude
        if (routePoints.size >= routeCapacity) {
            var write = 0
            var read = 0
            while (read < routePoints.size) {
                routePoints[write] = routePoints[read]
                write++
                read += 2
            }
            while (routePoints.size > write) routePoints.removeAt(routePoints.size - 1)
            routeSpacingMeters *= 2.0
            routeSpacingSeconds *= 2.0
        }
    }

    private val climbWindow = ArrayDeque<ElevationSample>()

    private fun pruneClimbWindow(elapsedSeconds: Double) {
        while (climbWindow.isNotEmpty() &&
            elapsedSeconds - climbWindow.first().elapsedSeconds > CLIMB_WINDOW_SECONDS
        ) {
            climbWindow.removeFirst()
        }
        var sum = 0.0
        for (s in climbWindow) sum += s.altitudeMeters
        windowClimbMeters = sum
    }

    /** Reset the rolling window climb once its event has fired. */
    fun consumeWindowClimb() {
        climbWindow.clear()
        windowClimbMeters = 0.0
    }

    fun routePointCount(): Int = routePoints.size
    fun routePoints(): List<RoutePoint> = routePoints.toList()

    /**
     * Copy the retained route points into caller-owned buffers (no allocation in
     * the draw path). Returns the number written: travelled distance (m), raw
     * altitude (m), time-of-day colour key, IMU energy, turn (rad) and calm.
     */
    fun copyRouteInto(
        elapsedOut: FloatArray,
        distanceOut: FloatArray,
        altitudeOut: FloatArray,
        colorOut: FloatArray,
        energyOut: FloatArray,
        turnOut: FloatArray,
        calmOut: FloatArray,
    ): Int {
        val n = minOf(
            routePoints.size,
            elapsedOut.size, distanceOut.size, altitudeOut.size, colorOut.size,
            energyOut.size, turnOut.size, calmOut.size,
        )
        val start = routePoints.size - n
        for (i in 0 until n) {
            val p = routePoints[start + i]
            elapsedOut[i] = p.elapsedSeconds.toFloat()
            distanceOut[i] = p.distanceMeters.toFloat()
            altitudeOut[i] = p.altitudeMeters.toFloat()
            colorOut[i] = p.timeColor.toFloat()
            energyOut[i] = p.energy.toFloat()
            turnOut[i] = p.turn.toFloat()
            calmOut[i] = p.calm.toFloat()
        }
        return n
    }

    companion object {
        const val STOP_THRESHOLD_SECONDS = 15.0 * 60.0
        const val CLIMB_WINDOW_SECONDS = 40.0
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val dPhi = Math.toRadians(lat2 - lat1)
            val dLambda = Math.toRadians(lon2 - lon1)
            val a = sin(dPhi / 2) * sin(dPhi / 2) +
                cos(phi1) * cos(phi2) * sin(dLambda / 2) * sin(dLambda / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return EARTH_RADIUS_M * c
        }
    }
}
