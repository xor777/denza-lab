package dev.denza.gateway

import java.io.EOFException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

object AdbProtocol {
    fun frameSmartSocketRequest(command: String): ByteArray {
        val payload = command.toByteArray(StandardCharsets.UTF_8)
        val length = payload.size.toString(16).padStart(4, '0')
            .uppercase(Locale.US)
            .toByteArray(StandardCharsets.US_ASCII)
        return length + payload
    }

    fun parseOkayPayload(input: InputStream): String {
        val status = input.readAscii(4)
        if (status == "OKAY") {
            val length = input.readAscii(4).toInt(16)
            return input.readBytesExact(length).toString(StandardCharsets.UTF_8)
        }

        val detail = runCatching {
            val length = input.readAscii(4).toInt(16)
            input.readBytesExact(length).toString(StandardCharsets.UTF_8)
        }.getOrDefault("")
        throw AdbProtocolException("ADB replied $status${detail.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}")
    }
}

class AdbProtocolException(message: String) : Exception(message)

private fun InputStream.readAscii(byteCount: Int): String =
    readBytesExact(byteCount).toString(StandardCharsets.US_ASCII)

private fun InputStream.readBytesExact(byteCount: Int): ByteArray {
    val buffer = ByteArray(byteCount)
    var offset = 0
    while (offset < byteCount) {
        val read = read(buffer, offset, byteCount - offset)
        if (read == -1) {
            throw EOFException("Expected $byteCount bytes, got $offset")
        }
        offset += read
    }
    return buffer
}
