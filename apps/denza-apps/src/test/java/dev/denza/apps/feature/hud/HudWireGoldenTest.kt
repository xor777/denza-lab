package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The exact bytes this app sends the HUD, frozen. Field order and every byte are compared, not
 * only the decoded fields, so moving the encoder into `:hud-link` cannot change what reaches the
 * HUD. The values were taken from the encoder in `HudSomeIpClient` before that move.
 */
class HudWireGoldenTest {

    @Test
    fun theFullRoadPacketWithArIsByteForByteTheSame() {
        val guidance = HudGuidance(
            maneuver = HudManeuver.SLIGHT_RIGHT,
            roundaboutExitNumber = null,
            instruction = "Держитесь правее",
            nextRoadName = "Тверская",
            maneuverDistanceMeters = 250,
            remainingDistanceMeters = 12_345,
            remainingTimeSeconds = 1_234,
            remainingTimeText = "21 мин",
            eta = "12:00",
            speedLimitKmh = 60,
        )
        val ar = HudArGeometry(
            vehicleLatitude = 55.7558,
            vehicleLongitude = 37.6173,
            vehicleAltitudeMeters = 144.6,
            vehicleSpeedMetersPerSecond = 13.4,
            vehicleHeadingDegrees = 87.5,
            guideLine = "[[37.6173,55.7558,0],[37.6181,55.7559,0]]",
            guidePoint = "37.6181,55.7559,0",
            navigatingRatio = 0.25,
        )

        assertEquals(FULL_ROAD, hex(HudSomeIpClient.buildPayloadForTest(guidance, ar)))
    }

    @Test
    fun theClearIsByteForByteTheSame() {
        assertEquals(CLEAR_ROAD, hex(HudSomeIpClient.buildClearPayloadForTest()))
    }

    @Test
    fun theMapFrameIsByteForByteTheSame() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 0, 1, 2)
        assertEquals(MAP_FRAME, hex(HudSomeIpClient.mapPayload(png)))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val FULL_ROAD =
            "0aaa01100280010218b96020d20948fa015210d0a2d0b2d0b5d180d181d0bad0b0d18f583cd20107" +
            "313220d0bad0bcda0109323120d0bcd0b8d0bde00105990110e9b7af03cf4240a1018d28ed0dbee0" +
            "4b40a8010db0019101f201295b5b33372e363137332c35352e373535382c305d2c5b33372e363138" +
            "312c35352e373535392c305d5dfa011133372e363138312c35352e373535392c3081020000000000" +
            "e055408902000000000000d03f"
        const val CLEAR_ROAD = "0a051002800101"
        const val MAP_FRAME = "0a08695641414151493d"
    }
}
