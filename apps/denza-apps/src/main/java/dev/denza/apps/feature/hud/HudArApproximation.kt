package dev.denza.apps.feature.hud

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
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

/** What one road packet carries: [guidance] with the step's held distance, and AR fields or none. */
data class HudArFrame(
    val guidance: HudGuidance,
    val geometry: HudArGeometry?,
)

/**
 * Builds a deliberately conservative pseudo-route from the current pose and
 * the navigator's distance-to-maneuver, held fixed for one step (a run of the
 * same maneuver and next road).
 *
 * The car's AR engine restarts its turn scene whenever field 31 differs from
 * the value it began with or field 9 grows. So within a step the distance only
 * shrinks, the turn point is placed once and never moves, and AR that has been
 * shown and then lost stays off until the next step instead of flapping.
 *
 * AR starts at [AR_START_DISTANCE_METERS], or earlier when the car is fast
 * enough that the firmware's four-second entry would not finish before the
 * turn. Inside [TURN_HOLD_DISTANCE_METERS] AR already shown holds until the
 * navigator moves on: turning, the car leaves the course the point was
 * projected along, which the guards read as a wrong projection, so there the
 * line and point stay as last drawn and only the vehicle fields follow the car.
 */
class HudArApproximationTracker {
    private data class StepKey(
        val maneuver: HudManeuver,
        val nextRoadName: String,
    )

    private data class GeoPoint(val latitude: Double, val longitude: Double)

    private var stepKey: StepKey? = null
    private var heldDistanceMeters = 0
    private var guidePoint: GeoPoint? = null
    private var incomingHeadingDegrees = 0.0
    private var shownGeometry: HudArGeometry? = null
    private var arEnded = false
    private var cachedPose: HudVehiclePose? = null
    private var cachedGeometry: HudArGeometry? = null

    fun resolve(
        guidance: HudGuidance,
        pose: HudVehiclePose?,
        nowElapsedMs: Long,
    ): HudArFrame {
        val key = StepKey(
            maneuver = guidance.maneuver,
            nextRoadName = guidance.nextRoadName.trim().lowercase(Locale.ROOT),
        )
        val reported = guidance.maneuverDistanceMeters
        // A distance well above the held one is a recalculated route, not
        // noise: it starts a new step like a new maneuver does.
        if (key != stepKey || reported - heldDistanceMeters >= ROUTE_RESET_DISTANCE_JUMP_METERS) {
            reset()
            stepKey = key
            heldDistanceMeters = reported
        } else {
            heldDistanceMeters = min(heldDistanceMeters, reported)
        }
        val held = if (reported == heldDistanceMeters) {
            guidance
        } else {
            guidance.copy(maneuverDistanceMeters = heldDistanceMeters)
        }
        if (arEnded) return HudArFrame(held, null)
        val shown = shownGeometry
        val geometry = if (shown != null && heldDistanceMeters <= TURN_HOLD_DISTANCE_METERS) {
            pose?.takeIf { poseIsUsable(it, nowElapsedMs) }?.let { shown.following(it) } ?: shown
        } else {
            geometry(guidance.maneuver, pose, nowElapsedMs)
        }
        if (geometry != null) {
            shownGeometry = geometry
        } else if (shown != null) {
            arEnded = true
        }
        return HudArFrame(held, geometry)
    }

    fun reset() {
        stepKey = null
        heldDistanceMeters = 0
        guidePoint = null
        incomingHeadingDegrees = 0.0
        shownGeometry = null
        arEnded = false
        cachedPose = null
        cachedGeometry = null
    }

    private fun geometry(
        maneuver: HudManeuver,
        pose: HudVehiclePose?,
        nowElapsedMs: Long,
    ): HudArGeometry? {
        val distance = heldDistanceMeters
        if (!supports(maneuver) || distance !in MIN_MANEUVER_DISTANCE_METERS..MAX_MANEUVER_DISTANCE_METERS) {
            return null
        }
        if (pose == null || !poseIsUsable(pose, nowElapsedMs)) return null
        if (guidePoint == null && distance > startDistanceMeters(pose)) return null
        if (pose == cachedPose) return cachedGeometry

        val vehicle = GeoPoint(pose.latitude, pose.longitude)
        // The first eligible sample of the step places the turn point; it is
        // kept only once a line through it has passed every guard.
        val anchored = guidePoint
        val turn = anchored ?: destination(vehicle, pose.headingDegrees, distance.toDouble())
        val incomingHeading = if (anchored != null) incomingHeadingDegrees else pose.headingDegrees
        val remaining = distanceMeters(vehicle, turn)
        if (remaining !in MIN_GUIDE_POINT_DISTANCE_METERS..MAX_GUIDE_POINT_DISTANCE_METERS) return null
        val approachHeading = bearingDegrees(vehicle, turn)
        // The firmware projects the polyline against the live vehicle pose, so
        // the driver reads the bend relative to the nose, not relative to the
        // approach leg. A slight bend survives less frame divergence than a
        // sharp one before its side visually flips.
        if (abs(shortestHeadingDelta(pose.headingDegrees, approachHeading)) >
            maxNoseApproachErrorDegrees(maneuver)
        ) {
            return null
        }
        // The point is only an approximation derived from the course captured
        // at the beginning of this step. Once the live approach no longer
        // agrees with that course, omitting AR is safer than drawing a
        // geometrically plausible but semantically wrong turn.
        if (abs(shortestHeadingDelta(incomingHeading, approachHeading)) > MAX_ANCHOR_HEADING_ERROR_DEGREES) {
            return null
        }

        val line = buildGuideLine(
            vehicle = vehicle,
            turn = turn,
            approachHeading = approachHeading,
            exitHeading = approachHeading,
            maneuver = maneuver,
        )
        if (!guideLineIsSafe(line, approachHeading, pose.headingDegrees, maneuver)) return null
        if (anchored == null) {
            guidePoint = turn
            incomingHeadingDegrees = pose.headingDegrees
        }
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
        cachedGeometry = geometry
        return geometry
    }

    private fun HudArGeometry.following(pose: HudVehiclePose): HudArGeometry = copy(
        vehicleLatitude = pose.latitude,
        vehicleLongitude = pose.longitude,
        vehicleAltitudeMeters = pose.altitudeMeters,
        vehicleSpeedMetersPerSecond = pose.speedMetersPerSecond,
        vehicleHeadingDegrees = pose.headingDegrees,
    )

    // The firmware needs fly-in, twist and rotate, four seconds, before the arrow points at the turn.
    private fun startDistanceMeters(pose: HudVehiclePose): Double = min(
        MAX_MANEUVER_DISTANCE_METERS.toDouble(),
        max(AR_START_DISTANCE_METERS, pose.speedMetersPerSecond * AR_START_LEAD_SECONDS),
    )

    private fun poseIsUsable(pose: HudVehiclePose, nowElapsedMs: Long): Boolean {
        if (nowElapsedMs < pose.capturedAtElapsedMs) return false
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
            HudManeuver.U_TURN_LEFT, HudManeuver.U_TURN_RIGHT -> 6
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

    /**
     * Final semantic guard at the wire boundary: a right command must only bend
     * right, both against its own approach leg and against the vehicle nose the
     * firmware renders the polyline for.
     */
    private fun guideLineIsSafe(
        line: List<GeoPoint>,
        approachHeading: Double,
        noseHeadingDegrees: Double,
        maneuver: HudManeuver,
    ): Boolean {
        if (line.size < 3 || line.any { !it.latitude.isFinite() || !it.longitude.isFinite() }) {
            return false
        }
        val expected = turnAngleDegrees(maneuver)
        val maximumDeflection = if (maneuver.isUTurn()) {
            MAX_U_TURN_DEFLECTION_DEGREES
        } else {
            MAX_REGULAR_EXIT_DEFLECTION_DEGREES
        }
        val exitSegments = line.zipWithNext().takeLast(EXIT_STEPS)
        if (exitSegments.size != EXIT_STEPS) return false
        val deflections = exitSegments.map { (from, to) ->
            if (distanceMeters(from, to) < MIN_VALID_SEGMENT_METERS) return false
            shortestHeadingDelta(approachHeading, bearingDegrees(from, to))
        }
        val terminalNose = shortestHeadingDelta(
            noseHeadingDegrees,
            bearingDegrees(line[line.lastIndex - 1], line.last()),
        )
        if (expected == 0.0) {
            return deflections.all { abs(it) <= STRAIGHT_DEFLECTION_TOLERANCE_DEGREES } &&
                abs(terminalNose) <= STRAIGHT_NOSE_ERROR_DEGREES + STRAIGHT_DEFLECTION_TOLERANCE_DEGREES
        }
        val direction = if (expected > 0.0) 1.0 else -1.0
        val directed = deflections.map { it * direction }
        if (directed.any { it < -DIRECTION_TOLERANCE_DEGREES || it > maximumDeflection }) {
            return false
        }
        if (directed.zipWithNext().any { (before, after) -> after + PROGRESSION_TOLERANCE_DEGREES < before }) {
            return false
        }
        // Near 180 degrees the shortest-angle sign aliases, so a U-turn's tip
        // cannot be side-checked against the nose; its cone and progression
        // checks above stay authoritative.
        if (!maneuver.isUTurn() &&
            terminalNose * direction < abs(expected) / 2.0 - TERMINAL_DEFLECTION_TOLERANCE_DEGREES
        ) {
            return false
        }
        return abs(directed.last() - abs(expected)) <= TERMINAL_DEFLECTION_TOLERANCE_DEGREES
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
        const val AR_START_DISTANCE_METERS = 50.0
        const val AR_START_LEAD_SECONDS = 4.0
        const val TURN_HOLD_DISTANCE_METERS = 25
        const val MIN_GUIDE_POINT_DISTANCE_METERS = 1.5
        const val MAX_GUIDE_POINT_DISTANCE_METERS = 220.0
        const val MAX_POSE_AGE_MS = 2_500L
        const val MAX_POSE_ACCURACY_METERS = 20.0
        const val MAX_APPROACH_ERROR_DEGREES = 70.0
        const val STRAIGHT_NOSE_ERROR_DEGREES = 15.0
        const val MAX_ANCHOR_HEADING_ERROR_DEGREES = 45.0
        const val ROUTE_RESET_DISTANCE_JUMP_METERS = 30
        const val APPROACH_POINT_SPACING_METERS = 8.0
        const val EXIT_POINT_SPACING_METERS = 6.0
        const val MAX_APPROACH_STEPS = 24
        const val EXIT_STEPS = 8
        const val MIN_VALID_SEGMENT_METERS = 4.0
        const val STRAIGHT_DEFLECTION_TOLERANCE_DEGREES = 8.0
        const val DIRECTION_TOLERANCE_DEGREES = 2.0
        const val PROGRESSION_TOLERANCE_DEGREES = 3.0
        const val TERMINAL_DEFLECTION_TOLERANCE_DEGREES = 6.0
        const val MAX_REGULAR_EXIT_DEFLECTION_DEGREES = 165.0
        const val MAX_U_TURN_DEFLECTION_DEGREES = 179.0
        // Exact 180 aliases left and right in shortest-angle arithmetic. Stop
        // five degrees short so the requested U-turn direction stays explicit.
        const val U_TURN_DEFLECTION_DEGREES = 175.0

        fun supports(maneuver: HudManeuver): Boolean = when (maneuver) {
            HudManeuver.STRAIGHT,
            HudManeuver.LEFT,
            HudManeuver.RIGHT,
            HudManeuver.SLIGHT_LEFT,
            HudManeuver.SLIGHT_RIGHT,
            HudManeuver.SHARP_LEFT,
            HudManeuver.SHARP_RIGHT,
            HudManeuver.U_TURN_LEFT,
            HudManeuver.U_TURN_RIGHT,
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
            HudManeuver.U_TURN_LEFT -> -U_TURN_DEFLECTION_DEGREES
            HudManeuver.U_TURN_RIGHT -> U_TURN_DEFLECTION_DEGREES
            else -> 0.0
        }

        fun HudManeuver.isUTurn(): Boolean =
            this == HudManeuver.U_TURN_LEFT || this == HudManeuver.U_TURN_RIGHT

        /**
         * How far the vehicle nose may diverge from the bearing to the guide
         * point before the rendered arrow stops reading as the commanded side.
         * Half the commanded angle keeps both the sign and a visible share of
         * the bend in the frame the driver sees.
         */
        fun maxNoseApproachErrorDegrees(maneuver: HudManeuver): Double = min(
            MAX_APPROACH_ERROR_DEGREES,
            max(STRAIGHT_NOSE_ERROR_DEGREES, abs(turnAngleDegrees(maneuver)) / 2.0),
        )

        fun normalizeHeading(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

        fun shortestHeadingDelta(from: Double, to: Double): Double =
            ((to - from + 540.0) % 360.0) - 180.0

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

        fun normalizeLongitude(value: Double): Double = ((value + 540.0) % 360.0) - 180.0
    }
}
