package dev.denza.apps.feature.vehicle.signal

import java.security.MessageDigest
import java.util.Base64

/** Content-addresses and verifies the tiny listener jar; staging failure disables the shadow. */
internal class TurnSignalProxyClasspath(
    jar: () -> ByteArray,
    private val log: (String) -> Unit = {},
) {
    private val artifact by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        val bytes = jar()
        check(bytes.isNotEmpty()) { "the packed vehicle signal proxy is empty" }
        Artifact(bytes, bytes.sha256())
    }

    @Synchronized
    fun entry(shell: (String) -> String): String {
        val local = artifact
        val path = "$DIRECTORY/$PREFIX${local.sha256}.jar"
        if (remoteSha256(shell, path) == local.sha256) return path

        val payload = Base64.getEncoder().encodeToString(local.bytes)
        shell(
            "mkdir -p '$DIRECTORY' && " +
                "printf '%s' '$payload' | base64 -d > '$path' && chmod 644 '$path'",
        )
        check(remoteSha256(shell, path) == local.sha256) {
            "the packed vehicle signal proxy failed SHA-256 verification at $path"
        }
        log("thin vehicle signal proxy staged at $path (${local.bytes.size} bytes)")
        return path
    }

    private fun remoteSha256(shell: (String) -> String, path: String): String? =
        shell("sha256sum '$path' 2>/dev/null").trim().substringBefore(' ')
            .takeIf { it.matches(SHA_256) }

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }

    private data class Artifact(val bytes: ByteArray, val sha256: String)

    internal companion object {
        const val ASSET = "vehicle-signal-proxy.jar"
        private const val DIRECTORY = "/data/local/tmp"
        private const val PREFIX = "denza-vehicle-signal-"
        private val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
