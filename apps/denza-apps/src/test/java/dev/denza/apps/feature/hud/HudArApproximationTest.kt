package dev.denza.apps.feature.hud

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudArApproximationTest {
    @Test
    fun `pose filter acquires moving course and only holds it briefly while stopped`() {
        val filter = HudArPoseFilter()

        assertNull(filter.onFix(speedMetersPerSecond = 0.2, bearingDegrees = 80.0, capturedAtElapsedMs = 1_000L))
        val moving = filter.onFix(
            speedMetersPerSecond = 8.0,
            bearingDegrees = 80.0,
            capturedAtElapsedMs = 2_000L,
        )
        assertNotNull(moving)
        assertEquals(80.0, moving!!.headingDegrees, 1e-9)

        val stopped = filter.onFix(
            speedMetersPerSecond = 0.0,
            hasBearing = false,
            bearingDegrees = 0.0,
            capturedAtElapsedMs = 16_000L,
        )
        assertEquals(80.0, stopped!!.headingDegrees, 1e-9)
        assertNull(
            filter.onFix(
                speedMetersPerSecond = 0.0,
                hasBearing = false,
                bearingDegrees = 0.0,
                capturedAtElapsedMs = 17_001L,
            ),
        )
        assertNull(
            filter.onFix(
                speedMetersPerSecond = 8.0,
                bearingDegrees = 90.0,
                capturedAtElapsedMs = 1_999L,
            ),
        )
    }

    @Test
    fun `pose filter rejects accuracy mutations and smooths north on the short arc`() {
        val filter = HudArPoseFilter()
        assertNull(filter.onFix(accuracyMeters = 20.1, capturedAtElapsedMs = 1_000L))
        assertNull(
            filter.onFix(
                speedMetersPerSecond = 8.0,
                bearingDegrees = 90.0,
                bearingAccuracyDegrees = 45.1,
                capturedAtElapsedMs = 2_000L,
            ),
        )

        val first = filter.onFix(
            speedMetersPerSecond = 8.0,
            bearingDegrees = 350.0,
            capturedAtElapsedMs = 3_000L,
        )!!
        val second = filter.onFix(
            speedMetersPerSecond = 8.0,
            bearingDegrees = 10.0,
            capturedAtElapsedMs = 4_000L,
        )!!
        assertEquals(350.0, first.headingDegrees, 1e-9)
        assertTrue(second.headingDegrees > 350.0 || second.headingDegrees < 10.0)
    }

    @Test
    fun `distance support boundary and unsupported maneuver mutations fail closed`() {
        listOf(3, 150).forEach { distance ->
            assertNotNull(HudArApproximationTracker().arGeometry(guidance(distance = distance), pose(), NOW))
        }
        listOf(0, 2, 151, 500).forEach { distance ->
            assertNull(HudArApproximationTracker().arGeometry(guidance(distance = distance), pose(), NOW))
        }
        listOf(
            HudManeuver.UNKNOWN,
            HudManeuver.ROUNDABOUT,
        ).forEach { maneuver ->
            assertNull(HudArApproximationTracker().arGeometry(guidance(maneuver), pose(), NOW))
        }
    }

    @Test
    fun `stale future and imprecise pose mutations fail closed`() {
        assertNull(
            HudArApproximationTracker().arGeometry(
                guidance(),
                pose(capturedAtElapsedMs = NOW - 2_501L),
                NOW,
            ),
        )
        assertNull(
            HudArApproximationTracker().arGeometry(
                guidance(),
                pose(capturedAtElapsedMs = NOW + 1L),
                NOW,
            ),
        )
        assertNull(
            HudArApproximationTracker().arGeometry(
                guidance(),
                pose(accuracyMeters = 20.1),
                NOW,
            ),
        )
    }

    @Test
    fun `left and right paths mirror while straight remains centered`() {
        val left = geometry(HudManeuver.LEFT)
        val right = geometry(HudManeuver.RIGHT)
        val straight = geometry(HudManeuver.STRAIGHT)
        val leftPoint = parseGuidePoint(left.guidePoint)
        val rightPoint = parseGuidePoint(right.guidePoint)
        val straightPoint = parseGuidePoint(straight.guidePoint)
        val leftLast = parseGuideLine(left.guideLine).last()
        val rightLast = parseGuideLine(right.guideLine).last()
        val straightLast = parseGuideLine(straight.guideLine).last()

        assertTrue(leftLast.longitude < leftPoint.longitude)
        assertTrue(rightLast.longitude > rightPoint.longitude)
        assertEquals(
            abs(leftLast.longitude - leftPoint.longitude),
            abs(rightLast.longitude - rightPoint.longitude),
            2e-6,
        )
        assertEquals(straightPoint.longitude, straightLast.longitude, 2e-6)
    }

    @Test
    fun `slight normal and sharp mutations produce increasing exit deflection`() {
        val slight = exitDeflection(HudManeuver.SLIGHT_RIGHT)
        val normal = exitDeflection(HudManeuver.RIGHT)
        val sharp = exitDeflection(HudManeuver.SHARP_RIGHT)

        assertTrue("slight=$slight normal=$normal", slight in 10.0..<normal)
        assertTrue("normal=$normal sharp=$sharp", normal < sharp)
        assertTrue("sharp=$sharp", sharp < 170.0)
    }

    @Test
    fun `left and right u turns mirror and finish on the return heading`() {
        val left = geometry(HudManeuver.U_TURN_LEFT)
        val right = geometry(HudManeuver.U_TURN_RIGHT)
        val leftPoint = parseGuidePoint(left.guidePoint)
        val rightPoint = parseGuidePoint(right.guidePoint)
        val leftLine = parseGuideLine(left.guideLine)
        val rightLine = parseGuideLine(right.guideLine)

        assertEquals(-175.0, terminalDeflection(left), 2.0)
        assertEquals(175.0, terminalDeflection(right), 2.0)
        assertTrue(leftLine.last().longitude < leftPoint.longitude)
        assertTrue(rightLine.last().longitude > rightPoint.longitude)
        assertEquals(
            abs(leftLine.last().longitude - leftPoint.longitude),
            abs(rightLine.last().longitude - rightPoint.longitude),
            2e-6,
        )
        assertTrue(leftLine.last().latitude < leftPoint.latitude)
        assertTrue(rightLine.last().latitude < rightPoint.latitude)
    }

    @Test
    fun `distance updates never move the guide point within a step`() {
        val tracker = HudArApproximationTracker()
        val first = tracker.arGeometry(guidance(distance = 100), pose(latitude = LATITUDE), NOW)!!
        val movedPose = pose(latitude = LATITUDE + metersToLatitude(10.0), capturedAtElapsedMs = NOW + 500L)
        val sameDistance = tracker.arGeometry(guidance(distance = 100), movedPose, NOW + 500L)!!
        // The navigator's distance disagrees with the GNSS track by a few metres.
        val updatedDistance = tracker.arGeometry(guidance(distance = 93), movedPose, NOW + 700L)!!
        val furtherPose = pose(latitude = LATITUDE + metersToLatitude(25.0), capturedAtElapsedMs = NOW + 2_000L)
        val further = tracker.arGeometry(guidance(distance = 71), furtherPose, NOW + 2_000L)!!

        assertEquals(first.guidePoint, sameDistance.guidePoint)
        assertEquals(first.guidePoint, updatedDistance.guidePoint)
        assertEquals(first.guidePoint, further.guidePoint)
        assertEquals(furtherPose.latitude, further.vehicleLatitude, 0.0)
    }

    @Test
    fun `instruction text mutation does not create a new anchor for the same maneuver`() {
        val tracker = HudArApproximationTracker()
        val firstGuidance = guidance(distance = 100)
        val first = tracker.arGeometry(firstGuidance, pose(), NOW)!!
        val movedPose = pose(latitude = LATITUDE + metersToLatitude(10.0), capturedAtElapsedMs = NOW + 500L)
        val mutated = tracker.arGeometry(
            firstGuidance.copy(instruction = "Через 100 метров поверните"),
            movedPose,
            NOW + 500L,
        )!!

        assertEquals(first.guidePoint, mutated.guidePoint)
    }

    @Test
    fun `a reroute out of the activation range starts a new step that anchors afresh`() {
        val tracker = HudArApproximationTracker()
        val first = tracker.arGeometry(guidance(distance = 100), pose(), NOW)!!
        val rerouted = tracker.resolve(guidance(distance = 151), pose(), NOW + 100L)
        assertEquals(151, rerouted.guidance.maneuverDistanceMeters)
        assertNull(rerouted.geometry)
        val movedPose = pose(latitude = LATITUDE + metersToLatitude(20.0), capturedAtElapsedMs = NOW + 500L)
        val reentered = tracker.arGeometry(guidance(distance = 100), movedPose, NOW + 500L)!!

        assertTrue(
            distanceMeters(parseGuidePoint(first.guidePoint), parseGuidePoint(reentered.guidePoint)) > 19.0,
        )
    }

    @Test
    fun `large increasing distance mutation resets a recalculated route anchor`() {
        val tracker = HudArApproximationTracker()
        val first = tracker.arGeometry(guidance(distance = 80), pose(), NOW)!!
        val movedPose = pose(latitude = LATITUDE + metersToLatitude(10.0), capturedAtElapsedMs = NOW + 500L)
        val recalculated = tracker.arGeometry(guidance(distance = 130), movedPose, NOW + 500L)!!

        assertTrue(
            distanceMeters(parseGuidePoint(first.guidePoint), parseGuidePoint(recalculated.guidePoint)) > 50.0,
        )
    }

    @Test
    fun `large lateral course mutation ends AR for the step instead of rotating the route`() {
        val tracker = HudArApproximationTracker()
        assertNotNull(tracker.arGeometry(guidance(distance = 100), pose(headingDegrees = 0.0), NOW))
        val changedCourse = pose(headingDegrees = 90.0, capturedAtElapsedMs = NOW + 500L)

        assertNull(tracker.arGeometry(guidance(distance = 90), changedCourse, NOW + 500L))
        assertNull(tracker.arGeometry(guidance(distance = 90), changedCourse, NOW + 600L))
        assertNull(
            tracker.arGeometry(
                guidance(distance = 89),
                pose(headingDegrees = 0.0, capturedAtElapsedMs = NOW + 700L),
                NOW + 700L,
            ),
        )
    }

    @Test
    fun `stale incoming course cannot turn a right instruction into a reversal`() {
        val tracker = HudArApproximationTracker()
        assertNotNull(
            tracker.arGeometry(
                guidance(maneuver = HudManeuver.RIGHT, distance = 100),
                pose(headingDegrees = 0.0),
                NOW,
            ),
        )

        val foldedApproach = pose(
            latitude = LATITUDE + metersToLatitude(150.0),
            longitude = LONGITUDE + metersToLongitude(42.0),
            headingDegrees = 220.0,
            capturedAtElapsedMs = NOW + 500L,
        )
        assertNull(
            tracker.arGeometry(
                guidance(maneuver = HudManeuver.RIGHT, distance = 100),
                foldedApproach,
                NOW + 500L,
            ),
        )
    }

    @Test
    fun `moderately curved approach keeps terminal direction tied to the maneuver`() {
        val tracker = HudArApproximationTracker()
        assertNotNull(
            tracker.arGeometry(
                guidance(maneuver = HudManeuver.RIGHT, distance = 100),
                pose(headingDegrees = 0.0),
                NOW,
            ),
        )
        val curvedApproach = pose(
            latitude = LATITUDE + metersToLatitude(61.7),
            longitude = LONGITUDE - metersToLongitude(32.1),
            headingDegrees = 40.0,
            capturedAtElapsedMs = NOW + 500L,
        )

        val geometry = tracker.arGeometry(
            guidance(maneuver = HudManeuver.RIGHT, distance = 100),
            curvedApproach,
            NOW + 500L,
        )!!

        assertEquals(90.0, terminalDeflection(geometry), 2.0)
    }

    @Test
    fun `left-curving approach cannot slant a slight left onto the right of the nose`() {
        val tracker = HudArApproximationTracker()
        assertNotNull(
            tracker.arGeometry(
                guidance(maneuver = HudManeuver.SLIGHT_LEFT, distance = 100),
                pose(headingDegrees = 0.0),
                NOW,
            ),
        )
        // The car bears left toward the fork while Yandex's displayed distance
        // is unchanged, so the world-anchored point ends far right of the nose
        // and the drawn arrow would read as a right turn.
        val curved = pose(
            latitude = LATITUDE + metersToLatitude(55.0),
            longitude = LONGITUDE - metersToLongitude(10.0),
            headingDegrees = 325.0,
            capturedAtElapsedMs = NOW + 500L,
        )
        assertNull(
            tracker.arGeometry(
                guidance(maneuver = HudManeuver.SLIGHT_LEFT, distance = 100),
                curved,
                NOW + 500L,
            ),
        )
    }

    @Test
    fun `gentle drift inside the slight cone keeps the tip left of the nose`() {
        val tracker = HudArApproximationTracker()
        assertNotNull(
            tracker.arGeometry(
                guidance(maneuver = HudManeuver.SLIGHT_LEFT, distance = 100),
                pose(headingDegrees = 0.0),
                NOW,
            ),
        )
        val drifted = pose(
            latitude = LATITUDE + metersToLatitude(30.0),
            longitude = LONGITUDE - metersToLongitude(3.0),
            headingDegrees = 350.0,
            capturedAtElapsedMs = NOW + 500L,
        )

        val geometry = tracker.arGeometry(
            guidance(maneuver = HudManeuver.SLIGHT_LEFT, distance = 100),
            drifted,
            NOW + 500L,
        )!!

        assertTrue(terminalNoseDeflection(geometry) < -10.0)
    }

    @Test
    fun `straight guidance far off the nose fails closed for the rest of the step`() {
        val tracker = HudArApproximationTracker()
        assertNotNull(
            tracker.arGeometry(
                guidance(maneuver = HudManeuver.STRAIGHT, distance = 100),
                pose(headingDegrees = 0.0),
                NOW,
            ),
        )
        assertNull(
            tracker.arGeometry(
                guidance(maneuver = HudManeuver.STRAIGHT, distance = 100),
                pose(headingDegrees = 20.0, capturedAtElapsedMs = NOW + 500L),
                NOW + 500L,
            ),
        )
        // Back inside the cone, AR still stays off: returning would restart the car's scene.
        assertNull(
            tracker.arGeometry(
                guidance(maneuver = HudManeuver.STRAIGHT, distance = 100),
                pose(headingDegrees = 10.0, capturedAtElapsedMs = NOW + 600L),
                NOW + 600L,
            ),
        )
    }

    @Test
    fun `every generated maneuver keeps its semantic direction across compass headings`() {
        val expectedDeflections = mapOf(
            HudManeuver.STRAIGHT to 0.0,
            HudManeuver.SLIGHT_LEFT to -35.0,
            HudManeuver.LEFT to -90.0,
            HudManeuver.SHARP_LEFT to -135.0,
            HudManeuver.SLIGHT_RIGHT to 35.0,
            HudManeuver.RIGHT to 90.0,
            HudManeuver.SHARP_RIGHT to 135.0,
            HudManeuver.U_TURN_LEFT to -175.0,
            HudManeuver.U_TURN_RIGHT to 175.0,
        )

        expectedDeflections.forEach { (maneuver, expected) ->
            for (heading in 0 until 360 step 15) {
                val geometry = HudArApproximationTracker().arGeometry(
                    guidance(maneuver = maneuver, distance = 100),
                    pose(headingDegrees = heading.toDouble()),
                    NOW,
                )
                assertNotNull("maneuver=$maneuver heading=$heading", geometry)
                assertEquals(
                    "maneuver=$maneuver heading=$heading",
                    expected,
                    terminalDeflection(checkNotNull(geometry)),
                    2.0,
                )
                assertEquals(
                    "maneuver=$maneuver heading=$heading",
                    expected,
                    terminalNoseDeflection(geometry),
                    2.0,
                )
            }
        }
    }

    @Test
    fun `longitude wrap mutation keeps every coordinate finite and normalized`() {
        val geometry = HudArApproximationTracker().arGeometry(
            guidance(maneuver = HudManeuver.RIGHT, distance = 150),
            pose(latitude = 80.0, longitude = 179.9999, headingDegrees = 90.0),
            NOW,
        )!!

        (parseGuideLine(geometry.guideLine) + parseGuidePoint(geometry.guidePoint)).forEach { point ->
            assertTrue(point.latitude.isFinite())
            assertTrue(point.longitude.isFinite())
            assertTrue(point.longitude in -180.0..180.0)
        }
        assertFalse(geometry.guideLine.contains("NaN"))
        assertFalse(geometry.guideLine.contains("Infinity"))
    }

    @Test
    fun `simulated turn shows AR from fifty metres through the corner with one guide point`() {
        val tracker = HudArApproximationTracker()
        val turnAheadMeters = 100.0
        val arcRadius = 6.0
        val arcLength = PI / 2.0 * arcRadius
        val straight = turnAheadMeters - arcRadius
        var travelled = 0.0
        var pose: HudVehiclePose? = null
        var reported = 0
        var reports = 0
        var speed = 10.0
        val guidePoints = LinkedHashSet<String>()
        val heldLines = LinkedHashSet<String>()
        var firstArDistance: Int? = null
        var firstInRange: Int? = null
        var last: HudArFrame? = null
        var tick = 0
        // The approach, a right turn on a 6 m arc that starts 6 m before the node, and 10 m of
        // the new street while the navigator still reports 0 and has not moved on.
        while (travelled < straight + arcLength + 10.0) {
            val now = NOW + tick * POLL_MS
            val (north, east, heading) = when {
                travelled <= straight -> Triple(travelled, 0.0, 0.0)
                travelled <= straight + arcLength -> {
                    val angle = (travelled - straight) / arcRadius
                    Triple(straight + arcRadius * sin(angle), arcRadius * (1.0 - cos(angle)), Math.toDegrees(angle))
                }
                else -> Triple(turnAheadMeters, arcRadius + travelled - straight - arcLength, 90.0)
            }
            // A GNSS fix and the navigator's distance each about once a second, out of phase,
            // with a few metres of disagreement and a degree or so of course jitter.
            if (tick % 3 == 0) {
                val fix = tick / 3
                pose = pose(
                    latitude = LATITUDE + metersToLatitude(north + 0.5 * cos(fix * 0.9)),
                    longitude = LONGITUDE + metersToLongitude(east + 0.4 * sin(fix * 1.3 + 0.3)),
                    headingDegrees = (360.0 + heading + 1.2 * sin(fix * 2.1 + 0.5)) % 360.0,
                    speedMetersPerSecond = speed,
                    capturedAtElapsedMs = now,
                )
            }
            if (tick == 0 || tick % 3 == 2) {
                val remaining = turnAheadMeters - travelled
                reported = max(0, (remaining + 3.0 * sin(reports * 1.7 + 1.0)).roundToInt())
                reports++
            }

            val frame = tracker.resolve(guidance(HudManeuver.RIGHT, reported), pose, now)
            val held = frame.guidance.maneuverDistanceMeters

            if (held > 50) assertNull("tick=$tick held=$held", frame.geometry)
            if (firstInRange == null && held <= 50) firstInRange = held
            if (firstArDistance == null && frame.geometry != null) firstArDistance = held
            if (firstArDistance != null) assertNotNull("tick=$tick held=$held", frame.geometry)
            frame.geometry?.let {
                guidePoints += it.guidePoint
                if (held <= 25) heldLines += it.guideLine
            }
            last = frame
            // 36 km/h, braking from 40 m out toward 14 km/h at the corner.
            val remaining = turnAheadMeters - travelled
            speed = if (remaining > 40.0) 10.0 else max(4.0, 4.0 + 6.0 * remaining / 40.0)
            travelled += speed * POLL_MS / 1000.0
            tick++
        }

        // The navigator reports once a second, so AR starts at the first report inside 50 m.
        assertEquals(firstInRange, checkNotNull(firstArDistance))
        assertEquals(guidePoints.toString(), 1, guidePoints.size)
        assertEquals(1, heldLines.size)
        val exit = checkNotNull(checkNotNull(last).geometry)
        assertEquals(0, checkNotNull(last).guidance.maneuverDistanceMeters)
        assertEquals(90.0, exit.vehicleHeadingDegrees, 3.0)
    }

    @Test
    fun `AR starts at fifty metres at turning speed and earlier when faster`() {
        listOf(8.0 to 50, 20.0 to 80, 60.0 to 150).forEach { (speed, start) ->
            val tracker = HudArApproximationTracker()
            assertNull("speed=$speed", tracker.arGeometry(guidance(distance = start + 1), pose(speedMetersPerSecond = speed), NOW))
            assertNotNull("speed=$speed", tracker.arGeometry(guidance(distance = start), pose(speedMetersPerSecond = speed), NOW))
        }
    }

    @Test
    fun `inside the turn hold AR follows the car and ends only with the step`() {
        val tracker = HudArApproximationTracker()
        val approach = tracker.arGeometry(guidance(HudManeuver.RIGHT, 40), pose(), NOW)!!
        // Turning into the corner: a nose 60 degrees off the approach fails every guard.
        val turning = pose(
            latitude = LATITUDE + metersToLatitude(36.0),
            longitude = LONGITUDE + metersToLongitude(3.0),
            headingDegrees = 60.0,
            capturedAtElapsedMs = NOW + 3_000L,
        )
        val outside = HudArApproximationTracker()
        outside.arGeometry(guidance(HudManeuver.RIGHT, 40), pose(), NOW)!!
        assertNull(outside.arGeometry(guidance(HudManeuver.RIGHT, 30), turning, NOW + 3_000L))
        val held = tracker.arGeometry(guidance(HudManeuver.RIGHT, 4), turning, NOW + 3_000L)!!
        assertEquals(approach.guidePoint, held.guidePoint)
        assertEquals(approach.guideLine, held.guideLine)
        assertEquals(60.0, held.vehicleHeadingDegrees, 0.0)
        assertEquals(turning.latitude, held.vehicleLatitude, 0.0)

        // A fix that has gone stale keeps the last frame instead of dropping AR mid-turn.
        assertEquals(held, tracker.arGeometry(guidance(HudManeuver.RIGHT, 0), turning, NOW + 6_000L))
        assertNull(
            tracker.arGeometry(
                guidance(HudManeuver.LEFT, 300).copy(nextRoadName = "Другая улица"),
                pose(capturedAtElapsedMs = NOW + 6_000L),
                NOW + 6_000L,
            ),
        )
    }

    @Test
    fun `the turn hold does not bring back AR lost before it`() {
        val tracker = HudArApproximationTracker()
        assertNotNull(tracker.arGeometry(guidance(distance = 45), pose(), NOW))
        assertNull(tracker.arGeometry(guidance(distance = 40), pose(), NOW + 2_501L))
        val fresh = pose(latitude = LATITUDE + metersToLatitude(25.0), capturedAtElapsedMs = NOW + 3_000L)
        assertNull(tracker.arGeometry(guidance(distance = 20), fresh, NOW + 3_000L))
    }

    @Test
    fun `held distance never grows within a step and a reroute jump starts a new one`() {
        val tracker = HudArApproximationTracker()
        val carried = ArrayList<Int>()
        val guidePoints = ArrayList<String>()
        listOf(100, 98, 99, 97, 120).forEachIndexed { index, distance ->
            val now = NOW + index * 1_000L
            val frame = tracker.resolve(
                guidance(maneuver = HudManeuver.RIGHT, distance = distance),
                pose(latitude = LATITUDE + metersToLatitude(index * 2.0), capturedAtElapsedMs = now),
                now,
            )
            carried += frame.guidance.maneuverDistanceMeters
            guidePoints += checkNotNull(frame.geometry).guidePoint
        }
        assertEquals(listOf(100, 98, 98, 97, 97), carried)
        assertEquals(1, guidePoints.toSet().size)

        val now = NOW + 5_000L
        val rerouted = tracker.resolve(
            guidance(maneuver = HudManeuver.RIGHT, distance = 140),
            pose(latitude = LATITUDE + metersToLatitude(10.0), capturedAtElapsedMs = now),
            now,
        )
        assertEquals(140, rerouted.guidance.maneuverDistanceMeters)
        val newPoint = checkNotNull(rerouted.geometry).guidePoint
        assertTrue(
            distanceMeters(parseGuidePoint(guidePoints.first()), parseGuidePoint(newPoint)) > 45.0,
        )
    }

    @Test
    fun `one lost AR sample ends AR for the step and a new step starts it again`() {
        val tracker = HudArApproximationTracker()
        val first = tracker.arGeometry(guidance(distance = 100), pose(), NOW)!!
        assertNull(
            tracker.arGeometry(
                guidance(distance = 95),
                pose(capturedAtElapsedMs = NOW),
                NOW + 2_501L,
            ),
        )
        val recovered = pose(latitude = LATITUDE + metersToLatitude(10.0), capturedAtElapsedMs = NOW + 2_600L)
        assertNull(tracker.arGeometry(guidance(distance = 90), recovered, NOW + 2_600L))
        assertNull(tracker.arGeometry(guidance(distance = 89), recovered, NOW + 3_000L))

        val nextRoad = tracker.arGeometry(
            guidance(distance = 70).copy(nextRoadName = "Другая улица"),
            recovered,
            NOW + 3_100L,
        )!!
        assertNotEquals(first.guidePoint, nextRoad.guidePoint)

        val nextManeuver = pose(latitude = LATITUDE + metersToLatitude(30.0), capturedAtElapsedMs = NOW + 5_000L)
        val right = tracker.arGeometry(
            guidance(maneuver = HudManeuver.RIGHT, distance = 60).copy(nextRoadName = "Другая улица"),
            nextManeuver,
            NOW + 5_000L,
        )!!
        assertNotEquals(nextRoad.guidePoint, right.guidePoint)
    }

    @Test
    fun `a step that has not shown AR yet may still start it`() {
        val tracker = HudArApproximationTracker()
        assertNull(tracker.arGeometry(guidance(distance = 120), null, NOW))
        assertNull(tracker.arGeometry(guidance(distance = 118), pose(accuracyMeters = 20.1), NOW))
        assertNotNull(tracker.arGeometry(guidance(distance = 116), pose(), NOW))
    }

    @Test
    fun `compact payload carries the held distance in field nine`() {
        listOf(HudManeuver.ROUNDABOUT, HudManeuver.LEFT).forEach { maneuver ->
            val tracker = HudArApproximationTracker()
            tracker.resolve(guidance(maneuver = maneuver, distance = 100), null, NOW)
            val frame = tracker.resolve(guidance(maneuver = maneuver, distance = 104), null, NOW + 1_000L)
            assertNull(frame.geometry)

            val fields = decodeEmbeddedMessage(HudCaptureSource.roadPacketForTest(frame.guidance, frame.geometry))

            assertEquals("maneuver=$maneuver", 100, fields.getValue(9).varint)
            (30..33).forEach { assertFalse(fields.containsKey(it)) }
        }
    }

    @Test
    fun `some ip payload uses verified AR field numbers and wire types`() {
        val ar = geometry(HudManeuver.LEFT)
        val fields = decodeEmbeddedMessage(HudCaptureSource.roadPacketForTest(guidance(), ar))

        assertEquals(1, fields.getValue(19).wireType)
        assertEquals(1, fields.getValue(20).wireType)
        assertEquals(0, fields.getValue(21).wireType)
        assertEquals(0, fields.getValue(22).wireType)
        assertEquals(2, fields.getValue(30).wireType)
        assertEquals(2, fields.getValue(31).wireType)
        assertEquals(1, fields.getValue(32).wireType)
        assertEquals(1, fields.getValue(33).wireType)
        assertEquals(ar.vehicleLongitude, fields.getValue(19).doubleValue(), 0.0)
        assertEquals(ar.vehicleLatitude, fields.getValue(20).doubleValue(), 0.0)
        assertEquals(ar.guideLine, fields.getValue(30).stringValue())
        assertEquals(ar.guidePoint, fields.getValue(31).stringValue())
    }

    @Test
    fun `compact payload omits every AR field when approximation is unavailable`() {
        val fields = decodeEmbeddedMessage(HudCaptureSource.roadPacketForTest(guidance(), null))
        (19..22).forEach { assertFalse(fields.containsKey(it)) }
        (30..33).forEach { assertFalse(fields.containsKey(it)) }
    }

    private fun HudArPoseFilter.onFix(
        latitude: Double = LATITUDE,
        longitude: Double = LONGITUDE,
        altitudeMeters: Double = 120.0,
        hasAltitude: Boolean = true,
        speedMetersPerSecond: Double = 8.0,
        accuracyMeters: Double = 4.0,
        bearingDegrees: Double = 0.0,
        hasBearing: Boolean = true,
        bearingAccuracyDegrees: Double? = 5.0,
        capturedAtElapsedMs: Long,
    ) = onFix(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = altitudeMeters,
        hasAltitude = hasAltitude,
        speedMetersPerSecond = speedMetersPerSecond,
        accuracyMeters = accuracyMeters,
        bearingDegrees = bearingDegrees,
        hasBearing = hasBearing,
        bearingAccuracyDegrees = bearingAccuracyDegrees,
        capturedAtElapsedMs = capturedAtElapsedMs,
    )

    private fun guidance(
        maneuver: HudManeuver = HudManeuver.LEFT,
        distance: Int = 100,
    ) = HudGuidance(
        maneuver = maneuver,
        roundaboutExitNumber = null,
        instruction = "Поверните",
        nextRoadName = "Тестовая улица",
        maneuverDistanceMeters = distance,
        remainingDistanceMeters = 5_000,
        remainingTimeSeconds = 600,
        remainingTimeText = "10 мин",
        eta = "12:00",
    )

    private fun pose(
        latitude: Double = LATITUDE,
        longitude: Double = LONGITUDE,
        headingDegrees: Double = 0.0,
        accuracyMeters: Double = 4.0,
        // Fast enough that AR may start anywhere in the 150 m window; the start distance has its own tests.
        speedMetersPerSecond: Double = 40.0,
        capturedAtElapsedMs: Long = NOW,
    ) = HudVehiclePose(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = 120.0,
        speedMetersPerSecond = speedMetersPerSecond,
        headingDegrees = headingDegrees,
        accuracyMeters = accuracyMeters,
        capturedAtElapsedMs = capturedAtElapsedMs,
    )

    private fun geometry(maneuver: HudManeuver): HudArGeometry =
        HudArApproximationTracker().arGeometry(guidance(maneuver), pose(), NOW)!!

    private fun HudArApproximationTracker.arGeometry(
        guidance: HudGuidance,
        pose: HudVehiclePose?,
        nowElapsedMs: Long,
    ): HudArGeometry? = resolve(guidance, pose, nowElapsedMs).geometry

    private fun exitDeflection(maneuver: HudManeuver): Double {
        val geometry = geometry(maneuver)
        val guidePoint = parseGuidePoint(geometry.guidePoint)
        val last = parseGuideLine(geometry.guideLine).last()
        return abs(bearingDegrees(guidePoint, last))
    }

    private fun terminalDeflection(geometry: HudArGeometry): Double {
        val line = parseGuideLine(geometry.guideLine)
        val approach = bearingDegrees(line.first(), parseGuidePoint(geometry.guidePoint))
        val terminal = bearingDegrees(line[line.lastIndex - 1], line.last())
        return ((terminal - approach + 540.0) % 360.0) - 180.0
    }

    private fun terminalNoseDeflection(geometry: HudArGeometry): Double {
        val line = parseGuideLine(geometry.guideLine)
        val terminal = bearingDegrees(line[line.lastIndex - 1], line.last())
        return ((terminal - geometry.vehicleHeadingDegrees + 540.0) % 360.0) - 180.0
    }

    private data class Point(val latitude: Double, val longitude: Double)

    private fun parseGuidePoint(value: String): Point {
        val fields = value.split(',')
        return Point(latitude = fields[1].toDouble(), longitude = fields[0].toDouble())
    }

    private fun parseGuideLine(value: String): List<Point> = TRIPLE.findAll(value).map { match ->
        Point(
            latitude = match.groupValues[2].toDouble(),
            longitude = match.groupValues[1].toDouble(),
        )
    }.toList()

    private fun bearingDegrees(first: Point, second: Point): Double {
        val lat1 = Math.toRadians(first.latitude)
        val lat2 = Math.toRadians(second.latitude)
        val deltaLon = Math.toRadians(second.longitude - first.longitude)
        val value = Math.toDegrees(
            atan2(
                sin(deltaLon) * cos(lat2),
                cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon),
            ),
        )
        return (value + 360.0) % 360.0
    }

    private fun distanceMeters(first: Point, second: Point): Double {
        val north = (second.latitude - first.latitude) * 111_195.0
        val east = (second.longitude - first.longitude) * 111_195.0 *
            cos(Math.toRadians((first.latitude + second.latitude) / 2.0))
        return kotlin.math.hypot(north, east)
    }

    private fun metersToLatitude(value: Double): Double = value / 111_195.0

    private fun metersToLongitude(value: Double): Double =
        value / (111_195.0 * cos(Math.toRadians(LATITUDE)))

    private data class WireField(val wireType: Int, val bytes: ByteArray, val varint: Int = 0) {
        fun doubleValue(): Double = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).double
        fun stringValue(): String = bytes.toString(Charsets.UTF_8)
    }

    private fun decodeEmbeddedMessage(payload: ByteArray): Map<Int, WireField> {
        var outerOffset = 0
        val outerTag = readVarint(payload, outerOffset)
        outerOffset = outerTag.nextOffset
        assertEquals(1, outerTag.value ushr 3)
        assertEquals(2, outerTag.value and 7)
        val length = readVarint(payload, outerOffset)
        outerOffset = length.nextOffset
        val end = outerOffset + length.value
        val result = LinkedHashMap<Int, WireField>()
        var offset = outerOffset
        while (offset < end) {
            val tag = readVarint(payload, offset)
            offset = tag.nextOffset
            val field = tag.value ushr 3
            val wireType = tag.value and 7
            var varint = 0
            val bytes = when (wireType) {
                0 -> {
                    val value = readVarint(payload, offset)
                    offset = value.nextOffset
                    varint = value.value
                    byteArrayOf()
                }
                1 -> payload.copyOfRange(offset, offset + 8).also { offset += 8 }
                2 -> {
                    val size = readVarint(payload, offset)
                    offset = size.nextOffset
                    payload.copyOfRange(offset, offset + size.value).also { offset += size.value }
                }
                else -> error("Unsupported wire type $wireType")
            }
            result[field] = WireField(wireType, bytes, varint)
        }
        return result
    }

    private data class Varint(val value: Int, val nextOffset: Int)

    private fun readVarint(bytes: ByteArray, start: Int): Varint {
        var value = 0
        var shift = 0
        var offset = start
        while (true) {
            val byte = bytes[offset++].toInt() and 0xff
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) return Varint(value, offset)
            shift += 7
        }
    }

    private companion object {
        const val LATITUDE = 55.75
        const val LONGITUDE = 37.61
        const val NOW = 100_000L
        const val POLL_MS = 350L
        val TRIPLE = Regex("\\[(-?[0-9.]+),(-?[0-9.]+),0(?:\\.0)?]")
    }
}
