package dev.denza.apps.feature.hud

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            assertNotNull(HudArApproximationTracker().resolve(guidance(distance = distance), pose(), NOW))
        }
        listOf(0, 2, 151, 500).forEach { distance ->
            assertNull(HudArApproximationTracker().resolve(guidance(distance = distance), pose(), NOW))
        }
        listOf(
            HudManeuver.UNKNOWN,
            HudManeuver.ROUNDABOUT_LEFT,
            HudManeuver.ROUNDABOUT_RIGHT,
        ).forEach { maneuver ->
            assertNull(HudArApproximationTracker().resolve(guidance(maneuver), pose(), NOW))
        }
    }

    @Test
    fun `stale future and imprecise pose mutations fail closed`() {
        assertNull(
            HudArApproximationTracker().resolve(
                guidance(),
                pose(capturedAtElapsedMs = NOW - 2_501L),
                NOW,
            ),
        )
        assertNull(
            HudArApproximationTracker().resolve(
                guidance(),
                pose(capturedAtElapsedMs = NOW + 1L),
                NOW,
            ),
        )
        assertNull(
            HudArApproximationTracker().resolve(
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
    fun `same displayed distance does not drag the anchored turn point with the car`() {
        val tracker = HudArApproximationTracker()
        val first = tracker.resolve(guidance(distance = 100), pose(latitude = LATITUDE), NOW)!!
        val movedPose = pose(latitude = LATITUDE + metersToLatitude(10.0), capturedAtElapsedMs = NOW + 500L)
        val sameDistance = tracker.resolve(guidance(distance = 100), movedPose, NOW + 500L)!!
        val updatedDistance = tracker.resolve(guidance(distance = 90), movedPose, NOW + 700L)!!

        assertEquals(first.guidePoint, sameDistance.guidePoint)
        assertTrue(
            distanceMeters(parseGuidePoint(first.guidePoint), parseGuidePoint(updatedDistance.guidePoint)) < 1.0,
        )
    }

    @Test
    fun `instruction text mutation does not create a new anchor for the same maneuver`() {
        val tracker = HudArApproximationTracker()
        val firstGuidance = guidance(distance = 100)
        val first = tracker.resolve(firstGuidance, pose(), NOW)!!
        val movedPose = pose(latitude = LATITUDE + metersToLatitude(10.0), capturedAtElapsedMs = NOW + 500L)
        val mutated = tracker.resolve(
            firstGuidance.copy(instruction = "Через 100 метров поверните"),
            movedPose,
            NOW + 500L,
        )!!

        assertEquals(first.guidePoint, mutated.guidePoint)
    }

    @Test
    fun `leaving activation range clears the previous anchor before reentry`() {
        val tracker = HudArApproximationTracker()
        val first = tracker.resolve(guidance(distance = 100), pose(), NOW)!!
        assertNull(tracker.resolve(guidance(distance = 151), pose(), NOW + 100L))
        val movedPose = pose(latitude = LATITUDE + metersToLatitude(20.0), capturedAtElapsedMs = NOW + 500L)
        val reentered = tracker.resolve(guidance(distance = 100), movedPose, NOW + 500L)!!

        assertTrue(
            distanceMeters(parseGuidePoint(first.guidePoint), parseGuidePoint(reentered.guidePoint)) > 19.0,
        )
    }

    @Test
    fun `large increasing distance mutation resets a recalculated route anchor`() {
        val tracker = HudArApproximationTracker()
        val first = tracker.resolve(guidance(distance = 80), pose(), NOW)!!
        val movedPose = pose(latitude = LATITUDE + metersToLatitude(10.0), capturedAtElapsedMs = NOW + 500L)
        val recalculated = tracker.resolve(guidance(distance = 130), movedPose, NOW + 500L)!!

        assertTrue(
            distanceMeters(parseGuidePoint(first.guidePoint), parseGuidePoint(recalculated.guidePoint)) > 50.0,
        )
    }

    @Test
    fun `large lateral course mutation is suppressed instead of rotating the route`() {
        val tracker = HudArApproximationTracker()
        assertNotNull(tracker.resolve(guidance(distance = 100), pose(headingDegrees = 0.0), NOW))
        assertNull(
            tracker.resolve(
                guidance(distance = 90),
                pose(headingDegrees = 90.0, capturedAtElapsedMs = NOW + 500L),
                NOW + 500L,
            ),
        )
    }

    @Test
    fun `stale incoming course cannot turn a right instruction into a reversal`() {
        val tracker = HudArApproximationTracker()
        assertNotNull(
            tracker.resolve(
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
            tracker.resolve(
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
            tracker.resolve(
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

        val geometry = tracker.resolve(
            guidance(maneuver = HudManeuver.RIGHT, distance = 100),
            curvedApproach,
            NOW + 500L,
        )!!

        assertEquals(90.0, terminalDeflection(geometry), 2.0)
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
                val geometry = HudArApproximationTracker().resolve(
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
            }
        }
    }

    @Test
    fun `rebased lateral mutation skips one sample then recovers without stale geometry`() {
        val tracker = HudArApproximationTracker()
        assertNotNull(tracker.resolve(guidance(distance = 100), pose(headingDegrees = 0.0), NOW))
        val changedCourse = pose(
            headingDegrees = 90.0,
            capturedAtElapsedMs = NOW + 500L,
        )

        assertNull(tracker.resolve(guidance(distance = 90), changedCourse, NOW + 500L))
        assertNotNull(tracker.resolve(guidance(distance = 90), changedCourse, NOW + 600L))
    }

    @Test
    fun `longitude wrap mutation keeps every coordinate finite and normalized`() {
        val geometry = HudArApproximationTracker().resolve(
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
    fun `some ip payload uses verified AR field numbers and wire types`() {
        val ar = geometry(HudManeuver.LEFT)
        val fields = decodeEmbeddedMessage(HudSomeIpClient.buildPayloadForTest(guidance(), ar))

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
        val fields = decodeEmbeddedMessage(HudSomeIpClient.buildPayloadForTest(guidance(), null))
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
        capturedAtElapsedMs: Long = NOW,
    ) = HudVehiclePose(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = 120.0,
        speedMetersPerSecond = 8.0,
        headingDegrees = headingDegrees,
        accuracyMeters = accuracyMeters,
        capturedAtElapsedMs = capturedAtElapsedMs,
    )

    private fun geometry(maneuver: HudManeuver): HudArGeometry =
        HudArApproximationTracker().resolve(guidance(maneuver), pose(), NOW)!!

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

    private data class WireField(val wireType: Int, val bytes: ByteArray) {
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
            val bytes = when (wireType) {
                0 -> {
                    val value = readVarint(payload, offset)
                    offset = value.nextOffset
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
            result[field] = WireField(wireType, bytes)
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
        val TRIPLE = Regex("\\[(-?[0-9.]+),(-?[0-9.]+),0(?:\\.0)?]")
    }
}
