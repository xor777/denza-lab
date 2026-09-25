package dev.denza.apps.feature.cloud

import java.security.SecureRandom

enum class CloudSimMode { FACTORY, CUSTOM }

/** Kept out of logs and generated reports. The two values are used only by a custom backend. */
class CloudIdentity(val iccid: String, val imsi: String) {
    fun valid(): Boolean = decimal(iccid, 20) && decimal(imsi, 15)

    override fun toString(): String = "CloudIdentity(redacted)"

    companion object {
        private val random = SecureRandom()

        fun generate(): CloudIdentity = CloudIdentity(
            "898607" + digits(14),
            "46001" + digits(10),
        )

        private fun digits(count: Int): String = buildString(count) {
            repeat(count) { append(('0'.code + random.nextInt(10)).toChar()) }
        }

        private fun decimal(value: String, length: Int): Boolean =
            value.length == length && value.all { it in '0'..'9' }
    }
}
