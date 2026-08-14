package dev.denza.apps.feature.hud

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

private fun normalizeHeading(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

private fun shortestHeadingDelta(from: Double, to: Double): Double =
    ((to - from + 540.0) % 360.0) - 180.0

/** A fresh, course-qualified GNSS fix suitable for the native AR HUD contract. */
data class HudVehiclePose(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    val speedMetersPerSecond: Double,
    val headingDegrees: Double,
    val accuracyMeters: Double,
    val capturedAtElapsedMs: Long,
)

/** Optional fields 19-22 and 30-33 of the stock HudRoadInfoNotifyStruct. */
data class HudArGeometry(
    val vehicleLatitude: Double,
    val vehicleLongitude: Double,
    val vehicleAltitudeMeters: Double,
    val vehicleSpeedMetersPerSecond: Double,
    val vehicleHeadingDegrees: Double,
    val guideLine: String,
    val guidePoint: String,
    val navigatingRatio: Double = 0.0,
)

/**
 * Converts raw GNSS fixes into poses without inventing a course while parked.
 * A previously measured moving course may be held briefly at a light.
 */
class HudArPoseFilter {
    private var courseDegrees: Double? = null
    private var courseCapturedAtMs = 0L

    fun onFix(
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double,
        hasAltitude: Boolean,
        speedMetersPerSecond: Double,
        accuracyMeters: Double,
        bearingDegrees: Double,
        hasBearing: Boolean,
        bearingAccuracyDegrees: Double?,
        capturedAtElapsedMs: Long,
    ): HudVehiclePose? {
        if (!latitude.isFinite() || latitude !in -85.0..85.0) return null
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
        if (!accuracyMeters.isFinite() || accuracyMeters !in 0.0..MAX_ACCURACY_METERS) return null
        if (!speedMetersPerSecond.isFinite() || speedMetersPerSecond < 0.0) return null
        if (capturedAtElapsedMs < 0L) return null
        if (courseCapturedAtMs != 0L && capturedAtElapsedMs < courseCapturedAtMs) return null

        val bearingAccurate = bearingAccuracyDegrees == null ||
            (bearingAccuracyDegrees.isFinite() && bearingAccuracyDegrees <= MAX_BEARING_ACCURACY_DEGREES)
        val movingBearing = speedMetersPerSecond >= COURSE_ACQUIRE_SPEED_MPS &&
            hasBearing && bearingDegrees.isFinite() && bearingAccurate
        if (movingBearing) {
            val measured = normalizeHeading(bearingDegrees)
            courseDegrees = courseDegrees?.let { previous ->
                normalizeHeading(previous + shortestHeadingDelta(previous, measured) * COURSE_SMOOTHING)
            } ?: measured
            courseCapturedAtMs = capturedAtElapsedMs
        }

        val course = courseDegrees ?: return null
        if (!movingBearing && capturedAtElapsedMs - courseCapturedAtMs > COURSE_HOLD_MS) return null
        return HudVehiclePose(
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = if (hasAltitude && altitudeMeters.isFinite()) altitudeMeters else 0.0,
            speedMetersPerSecond = speedMetersPerSecond,
            headingDegrees = course,
            accuracyMeters = accuracyMeters,
            capturedAtElapsedMs = capturedAtElapsedMs,
        )
    }

    fun reset() {
        courseDegrees = null
        courseCapturedAtMs = 0L
    }

    private companion object {
        const val MAX_ACCURACY_METERS = 20.0
        const val MAX_BEARING_ACCURACY_DEGREES = 45.0
        const val COURSE_ACQUIRE_SPEED_MPS = 1.5
        const val COURSE_HOLD_MS = 15_000L
        const val COURSE_SMOOTHING = 0.35
    }
}

/**
 * Builds a deliberately conservative pseudo-route from the current pose and
 * Yandex's distance-to-maneuver. The inferred turn point is anchored when the
 * displayed distance changes, so it does not move forward with every GNSS fix.
 */
class HudArApproximationTracker {
    private data class StepKey(
        val maneuver: HudManeuver,
        val nextRoadName: String,
    )

    private data class GeoPoint(val latitude: Double, val longitude: Double)

    private var stepKey: StepKey? = null
    private var guidePoint: GeoPoint? = null
    private var incomingHeadingDegrees = 0.0
    private var reportedDistanceMeters: Int? = null
    private var anchorPoseCapturedAtMs = 0L
    private var cachedPose: HudVehiclePose? = null
    private var cachedKey: StepKey? = null
    private var cachedDistanceMeters: Int? = null
    private var cachedGuidePoint: GeoPoint? = null
    private var cachedGeometry: HudArGeometry? = null

    fun resolve(
        guidance: HudGuidance,
        pose: HudVehiclePose?,
        nowElapsedMs: Long,
    ): HudArGeometry? {
        val key = StepKey(
            maneuver = guidance.maneuver,
            nextRoadName = guidance.nextRoadName.trim().lowercase(Locale.ROOT),
        )
        val distance = guidance.maneuverDistanceMeters
        if (!supports(guidance.maneuver) || distance !in MIN_MANEUVER_DISTANCE_METERS..MAX_MANEUVER_DISTANCE_METERS) {
            reset()
            return null
        }
        if (!poseIsUsable(pose, nowElapsedMs)) return null
        pose ?: return null

        val previousKey = stepKey
        val previousDistance = reportedDistanceMeters
        if (previousKey != key || guidePoint == null) {
            stepKey = key
            guidePoint = destination(
                GeoPoint(pose.latitude, pose.longitude),
                pose.headingDegrees,
                distance.toDouble(),
            )
            incomingHeadingDegrees = pose.headingDegrees
            reportedDistanceMeters = distance
            anchorPoseCapturedAtMs = pose.capturedAtElapsedMs
        } else if (previousDistance != distance && pose.capturedAtElapsedMs > anchorPoseCapturedAtMs) {
            val candidate = destination(
                GeoPoint(pose.latitude, pose.longitude),
                pose.headingDegrees,
                distance.toDouble(),
            )
            val currentGuidePoint = guidePoint ?: candidate
            val separation = distanceMeters(currentGuidePoint, candidate)
            val distanceJump = distance - (previousDistance ?: distance)
            when {
                separation <= MAX_ANCHOR_BLEND_METERS -> {
                    guidePoint = blend(currentGuidePoint, candidate, ANCHOR_BLEND)
                    incomingHeadingDegrees = blendHeading(
                        incomingHeadingDegrees,
                        pose.headingDegrees,
                        ANCHOR_BLEND,
                    )
                }
                distanceJump >= ROUTE_RESET_DISTANCE_JUMP_METERS -> {
                    guidePoint = candidate
                    incomingHeadingDegrees = pose.headingDegrees
                }
                // A large lateral mutation with a decreasing distance is more
                // likely a noisy bearing than a new route. Preserve the anchor.
            }
            reportedDistanceMeters = distance
            anchorPoseCapturedAtMs = pose.capturedAtElapsedMs
        }

        val turn = guidePoint ?: return null
        val vehicle = GeoPoint(pose.latitude, pose.longitude)
        val remaining = distanceMeters(vehicle, turn)
        if (remaining !in MIN_GUIDE_POINT_DISTANCE_METERS..MAX_GUIDE_POINT_DISTANCE_METERS) return null
        val approachHeading = bearingDegrees(vehicle, turn)
        if (abs(shortestHeadingDelta(pose.headingDegrees, approachHeading)) > MAX_APPROACH_ERROR_DEGREES) {
            return null
        }
        if (cachedPose == pose && cachedKey == key && cachedDistanceMeters == distance &&
            cachedGuidePoint == turn
        ) {
            return cachedGeometry
        }

        val line = buildGuideLine(
            vehicle = vehicle,
            turn = turn,
            approachHeading = approachHeading,
            exitHeading = blendHeading(approachHeading, incomingHeadingDegrees, EXIT_HEADING_ANCHOR_WEIGHT),
            maneuver = guidance.maneuver,
        )
        if (line.size < 3 || line.any { !it.latitude.isFinite() || !it.longitude.isFinite() }) return null
        val geometry = HudArGeometry(
            vehicleLatitude = pose.latitude,
            vehicleLongitude = pose.longitude,
            vehicleAltitudeMeters = pose.altitudeMeters,
            vehicleSpeedMetersPerSecond = pose.speedMetersPerSecond,
            vehicleHeadingDegrees = pose.headingDegrees,
            guideLine = line.toGuideLineJson(),
            guidePoint = turn.toGuidePointString(),
        )
        cachedPose = pose
        cachedKey = key
        cachedDistanceMeters = distance
        cachedGuidePoint = turn
        cachedGeometry = geometry
        return geometry
    }

    fun reset() {
        stepKey = null
        guidePoint = null
        incomingHeadingDegrees = 0.0
        reportedDistanceMeters = null
        anchorPoseCapturedAtMs = 0L
        cachedPose = null
        cachedKey = null
        cachedDistanceMeters = null
        cachedGuidePoint = null
        cachedGeometry = null
    }

    private fun poseIsUsable(pose: HudVehiclePose?, nowElapsedMs: Long): Boolean {
        if (pose == null || nowElapsedMs < pose.capturedAtElapsedMs) return false
        if (nowElapsedMs - pose.capturedAtElapsedMs > MAX_POSE_AGE_MS) return false
        return pose.latitude.isFinite() && pose.latitude in -85.0..85.0 &&
            pose.longitude.isFinite() && pose.longitude in -180.0..180.0 &&
            pose.altitudeMeters.isFinite() &&
            pose.speedMetersPerSecond.isFinite() && pose.speedMetersPerSecond >= 0.0 &&
            pose.headingDegrees.isFinite() && pose.accuracyMeters in 0.0..MAX_POSE_ACCURACY_METERS
    }

    private fun buildGuideLine(
        vehicle: GeoPoint,
        turn: GeoPoint,
        approachHeading: Double,
        exitHeading: Double,
        maneuver: HudManeuver,
    ): List<GeoPoint> {
        val result = ArrayList<GeoPoint>()
        val approachDistance = distanceMeters(vehicle, turn)
        val approachSteps = ceil(approachDistance / APPROACH_POINT_SPACING_METERS)
            .toInt()
            .coerceIn(1, MAX_APPROACH_STEPS)
        for (index in 0..approachSteps) {
            result += destination(
                vehicle,
                approachHeading,
                approachDistance * index / approachSteps,
            )
        }
        // Use the actual anchored coordinate at the seam to avoid accumulated
        // spherical rounding and to match the stock current-segment endpoint.
        result[result.lastIndex] = turn

        val turnAngle = turnAngleDegrees(maneuver)
        var cursor = turn
        val curveSteps = when (maneuver) {
            HudManeuver.SLIGHT_LEFT, HudManeuver.SLIGHT_RIGHT -> 5
            HudManeuver.SHARP_LEFT, HudManeuver.SHARP_RIGHT -> 3
            HudManeuver.LEFT, HudManeuver.RIGHT -> 4
            else -> 1
        }
        for (index in 1..EXIT_STEPS) {
            val progress = min(1.0, (index - 0.5) / curveSteps)
            val eased = progress * progress * (3.0 - 2.0 * progress)
            val segmentHeading = normalizeHeading(exitHeading + turnAngle * eased)
            cursor = destination(cursor, segmentHeading, EXIT_POINT_SPACING_METERS)
            result += cursor
        }
        return result
    }

    private fun List<GeoPoint>.toGuideLineJson(): String = buildString(size * 32) {
        append('[')
        this@toGuideLineJson.forEachIndexed { index, point ->
            if (index > 0) append(',')
            append('[')
            appendCoordinate(point.longitude)
            append(',')
            appendCoordinate(point.latitude)
            append(",0.0]")
        }
        append(']')
    }

    private fun GeoPoint.toGuidePointString(): String = buildString(32) {
        appendCoordinate(longitude)
        append(',')
        appendCoordinate(latitude)
        append(",0")
    }

    private fun StringBuilder.appendCoordinate(value: Double) {
        append(String.format(Locale.ROOT, "%.7f", value))
    }

    private companion object {
        const val EARTH_RADIUS_METERS = 6_371_008.8
        const val MIN_MANEUVER_DISTANCE_METERS = 3
        const val MAX_MANEUVER_DISTANCE_METERS = 150
        const val MIN_GUIDE_POINT_DISTANCE_METERS = 1.5
        const val MAX_GUIDE_POINT_DISTANCE_METERS = 220.0
        const val MAX_POSE_AGE_MS = 2_500L
        const val MAX_POSE_ACCURACY_METERS = 20.0
        const val MAX_APPROACH_ERROR_DEGREES = 70.0
        const val MAX_ANCHOR_BLEND_METERS = 35.0
        const val ROUTE_RESET_DISTANCE_JUMP_METERS = 30
        const val ANCHOR_BLEND = 0.35
        const val EXIT_HEADING_ANCHOR_WEIGHT = 0.5
        const val APPROACH_POINT_SPACING_METERS = 8.0
        const val EXIT_POINT_SPACING_METERS = 6.0
        const val MAX_APPROACH_STEPS = 24
        const val EXIT_STEPS = 8

        fun supports(maneuver: HudManeuver): Boolean = when (maneuver) {
            HudManeuver.STRAIGHT,
            HudManeuver.LEFT,
            HudManeuver.RIGHT,
            HudManeuver.SLIGHT_LEFT,
            HudManeuver.SLIGHT_RIGHT,
            HudManeuver.SHARP_LEFT,
            HudManeuver.SHARP_RIGHT,
            -> true
            else -> false
        }

        fun turnAngleDegrees(maneuver: HudManeuver): Double = when (maneuver) {
            HudManeuver.SLIGHT_LEFT -> -35.0
            HudManeuver.LEFT -> -90.0
            HudManeuver.SHARP_LEFT -> -135.0
            HudManeuver.SLIGHT_RIGHT -> 35.0
            HudManeuver.RIGHT -> 90.0
            HudManeuver.SHARP_RIGHT -> 135.0
            else -> 0.0
        }

        fun normalizeHeading(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

        fun shortestHeadingDelta(from: Double, to: Double): Double =
            ((to - from + 540.0) % 360.0) - 180.0

        fun blendHeading(from: Double, to: Double, ratio: Double): Double =
            normalizeHeading(from + shortestHeadingDelta(from, to) * ratio)

        fun destination(origin: GeoPoint, headingDegrees: Double, distanceMeters: Double): GeoPoint {
            val angularDistance = distanceMeters / EARTH_RADIUS_METERS
            val heading = Math.toRadians(headingDegrees)
            val latitude = Math.toRadians(origin.latitude)
            val longitude = Math.toRadians(origin.longitude)
            val destinationLatitude = asin(
                sin(latitude) * cos(angularDistance) +
                    cos(latitude) * sin(angularDistance) * cos(heading),
            )
            val destinationLongitude = longitude + atan2(
                sin(heading) * sin(angularDistance) * cos(latitude),
                cos(angularDistance) - sin(latitude) * sin(destinationLatitude),
            )
            return GeoPoint(
                latitude = Math.toDegrees(destinationLatitude),
                longitude = normalizeLongitude(Math.toDegrees(destinationLongitude)),
            )
        }

        fun distanceMeters(first: GeoPoint, second: GeoPoint): Double {
            val latitude1 = Math.toRadians(first.latitude)
            val latitude2 = Math.toRadians(second.latitude)
            val latitudeDelta = latitude2 - latitude1
            val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
            val haversine = sin(latitudeDelta / 2).pow(2) +
                cos(latitude1) * cos(latitude2) * sin(longitudeDelta / 2).pow(2)
            return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
        }

        fun bearingDegrees(first: GeoPoint, second: GeoPoint): Double {
            val latitude1 = Math.toRadians(first.latitude)
            val latitude2 = Math.toRadians(second.latitude)
            val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
            val y = sin(longitudeDelta) * cos(latitude2)
            val x = cos(latitude1) * sin(latitude2) -
                sin(latitude1) * cos(latitude2) * cos(longitudeDelta)
            return normalizeHeading(Math.toDegrees(atan2(y, x)))
        }

        fun blend(first: GeoPoint, second: GeoPoint, ratio: Double): GeoPoint = GeoPoint(
            latitude = first.latitude + (second.latitude - first.latitude) * ratio,
            longitude = normalizeLongitude(
                first.longitude + shortestLongitudeDelta(first.longitude, second.longitude) * ratio,
            ),
        )

        fun shortestLongitudeDelta(from: Double, to: Double): Double =
            ((to - from + 540.0) % 360.0) - 180.0

        fun normalizeLongitude(value: Double): Double = ((value + 540.0) % 360.0) - 180.0
    }
}
