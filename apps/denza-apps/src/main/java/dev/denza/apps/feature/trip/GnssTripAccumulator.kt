package dev.denza.apps.feature.trip

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Derives the visible trip distance and altitude figures from ~1 Hz GNSS fixes.
 *
 * Parked fixes cannot add distance or climb. Altitude is accuracy-gated and held
 * while stopped; resuming movement re-anchors the smoother so parked GPS drift
 * cannot become phantom climb. Nothing is persisted.
 */
class GnssTripAccumulator(
    private val altitudeTau: Double = 4.0,
    private val varioTau: Double = 3.0,
    private val stopSpeed: Double = 0.5,
    private val climbFreezeSpeed: Double = 1.0,
    private val varioFreezeTau: Double = 1.5,
    private val climbStepMeters: Double = 3.0,
    private val maxVerticalAccuracyMeters: Double = 20.0,
    private val seedConsistencyFixes: Int = 3,
    private val seedConsistencyBandMeters: Double = 15.0,
) {
    private var hasPreviousFix = false
    private var previousLatitude = 0.0
    private var previousLongitude = 0.0

    var distanceMeters: Double = 0.0
        private set
    var tripClimbMeters: Double = 0.0
        private set

    private var altitudeSeeded = false
    var smoothedAltitude: Double = 0.0
        private set
    val hasAltitude: Boolean get() = altitudeSeeded
    var variometer: Double = 0.0
        private set

    private var restPointAltitude = 0.0
    private var pendingReanchor = false
    private var pendingSeedAltitude = 0.0
    private var pendingSeedCount = 0

    fun onFix(
        latitude: Double,
        longitude: Double,
        altitude: Double,
        hasAltitudeFix: Boolean,
        verticalAccuracyMeters: Double,
        hasVerticalAccuracy: Boolean,
        speed: Double,
        dt: Double,
        accumulate: Boolean,
    ) {
        val step = dt.coerceIn(0.0, 5.0)
        val movingForClimb = speed >= climbFreezeSpeed

        if (accumulate && hasPreviousFix && speed >= stopSpeed) {
            val distance = haversine(previousLatitude, previousLongitude, latitude, longitude)
            if (distance < 400.0) distanceMeters += distance
        }
        previousLatitude = latitude
        previousLongitude = longitude
        hasPreviousFix = true

        if (!movingForClimb) {
            variometer *= exp(-step / varioFreezeTau)
            pendingReanchor = true
        }
        if (hasAltitudeFix && altitudeAcceptable(hasVerticalAccuracy, verticalAccuracyMeters)) {
            if (!altitudeSeeded) {
                maybeSeed(altitude, hasVerticalAccuracy)
            } else if (movingForClimb) {
                if (pendingReanchor) {
                    smoothedAltitude = altitude
                    restPointAltitude = altitude
                    pendingReanchor = false
                } else {
                    val previousAltitude = smoothedAltitude
                    val altitudeFactor = 1.0 - exp(-step / altitudeTau)
                    smoothedAltitude += (altitude - smoothedAltitude) * altitudeFactor
                    val rate = if (step > 0) (smoothedAltitude - previousAltitude) / step else 0.0
                    val varioFactor = 1.0 - exp(-step / varioTau)
                    variometer += (rate - variometer) * varioFactor
                    stairStep(accumulate)
                }
            }
        }
    }

    private fun altitudeAcceptable(hasAccuracy: Boolean, accuracy: Double): Boolean =
        !hasAccuracy || accuracy <= maxVerticalAccuracyMeters

    private fun maybeSeed(altitude: Double, hasAccuracy: Boolean) {
        if (!hasAccuracy) {
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

    private fun stairStep(accumulate: Boolean) {
        if (smoothedAltitude < restPointAltitude) {
            restPointAltitude = smoothedAltitude
        } else if (smoothedAltitude - restPointAltitude >= climbStepMeters) {
            val gain = smoothedAltitude - restPointAltitude
            restPointAltitude = smoothedAltitude
            if (accumulate) tripClimbMeters += gain
        }
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_000.0

        fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val phi1 = Math.toRadians(lat1)
            val phi2 = Math.toRadians(lat2)
            val deltaPhi = Math.toRadians(lat2 - lat1)
            val deltaLambda = Math.toRadians(lon2 - lon1)
            val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return EARTH_RADIUS_METERS * c
        }
    }
}
