package dev.denza.apps.feature.hud

import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The map event as the HUD gets it: `HudNavigationmap{1: Base64 PNG}`, field 1 a length-delimited
 * string and no envelope - the bytes the 2026-09-23 run put on the glass.
 */
class HudMapPayloadTest {

    @Test
    fun aShortImageIsTagLengthAndBase64() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

        val payload = HudSomeIpClient.mapPayload(png)

        // "iVBORw==" is 8 characters: one length byte.
        assertArrayEquals(byteArrayOf(0x0A, 8) + "iVBORw==".toByteArray(Charsets.US_ASCII), payload)
    }

    @Test
    fun aRealSizedFrameCarriesAMultiByteLengthAndNoLineBreaks() {
        val png = ByteArray(40_000) { (it * 31).toByte() }

        val payload = HudSomeIpClient.mapPayload(png)

        assertEquals(0x0A, payload[0].toInt())
        var length = 0
        var shift = 0
        var offset = 1
        while (true) {
            val byte = payload[offset++].toInt() and 0xFF
            length = length or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
        }
        // 53 336 characters: a three-byte varint after the tag.
        assertEquals(4, offset)
        assertEquals(payload.size - offset, length)
        val text = String(payload, offset, length, Charsets.US_ASCII)
        assertEquals(-1, text.indexOf('\n'))
        assertArrayEquals(png, Base64.getDecoder().decode(text))
    }
}
