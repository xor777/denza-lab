package dev.denza.apps.feature.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The road packet's plain fields, read back off the wire as the HUD gets them. */
class HudRoadPacketTest {
    @Test
    fun fieldTwoIsTheStockConstantNotASequence() {
        val first = varints(HudSomeIpClient.buildPayloadForTest(guidance(), null))
        val second = varints(HudSomeIpClient.buildPayloadForTest(guidance(distance = 90), null))

        assertEquals(2L, first.getValue(2))
        assertEquals(2L, second.getValue(2))
        assertEquals(2L, first.getValue(16))
    }

    @Test
    fun theClearIsNavigationOffWithTheSameFieldTwo() {
        assertEquals(mapOf(2 to 2L, 16 to 1L), varints(HudSomeIpClient.buildClearPayloadForTest()))
    }

    @Test
    fun aSpeedSignGoesToFieldElevenInKilometresPerHour() {
        val fields = varints(HudSomeIpClient.buildPayloadForTest(guidance(speedLimit = 60), null))

        assertEquals(60L, fields.getValue(11))
    }

    @Test
    fun noSignLeavesFieldElevenOut() {
        val fields = varints(HudSomeIpClient.buildPayloadForTest(guidance(), null))

        assertFalse(fields.containsKey(11))
    }

    private fun guidance(distance: Int = 100, speedLimit: Int? = null) = HudGuidance(
        maneuver = HudManeuver.LEFT,
        roundaboutExitNumber = null,
        instruction = "Поверните налево",
        nextRoadName = "Тестовая улица",
        maneuverDistanceMeters = distance,
        remainingDistanceMeters = 5_000,
        remainingTimeSeconds = 600,
        remainingTimeText = "10 мин",
        eta = "12:00",
        speedLimitKmh = speedLimit,
    )

    /** Every varint field of the message embedded as field 1, by number. */
    private fun varints(payload: ByteArray): Map<Int, Long> {
        val reader = Reader(payload)
        assertEquals((1L shl 3) or 2L, reader.varint())
        val end = reader.varint().toInt() + reader.offset
        val result = LinkedHashMap<Int, Long>()
        while (reader.offset < end) {
            val tag = reader.varint()
            when ((tag and 7).toInt()) {
                0 -> result[(tag ushr 3).toInt()] = reader.varint()
                1 -> reader.offset += 8
                2 -> {
                    val length = reader.varint().toInt()
                    reader.offset += length
                }
                else -> error("wire type ${tag and 7}")
            }
        }
        return result
    }

    private class Reader(private val bytes: ByteArray) {
        var offset = 0

        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                val byte = bytes[offset++].toLong() and 0xff
                value = value or ((byte and 0x7f) shl shift)
                if (byte and 0x80 == 0L) return value
                shift += 7
            }
        }
    }
}
