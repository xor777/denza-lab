package dev.denza.gateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class AdbProtocolTest {
    @Test
    fun framesSmartSocketRequestWithHexLength() {
        val framed = AdbProtocol.frameSmartSocketRequest("host:version")
            .toString(StandardCharsets.US_ASCII)

        assertEquals("000Chost:version", framed)
    }

    @Test
    fun parsesOkayPayload() {
        val input = ByteArrayInputStream("OKAY00040029".toByteArray(StandardCharsets.US_ASCII))

        assertEquals("0029", AdbProtocol.parseOkayPayload(input))
    }

    @Test
    fun throwsOnFailPayload() {
        val input = ByteArrayInputStream("FAIL0004nope".toByteArray(StandardCharsets.US_ASCII))

        assertThrows(AdbProtocolException::class.java) {
            AdbProtocol.parseOkayPayload(input)
        }
    }
}
