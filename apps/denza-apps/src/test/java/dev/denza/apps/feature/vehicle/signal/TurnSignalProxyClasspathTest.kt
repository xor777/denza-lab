package dev.denza.apps.feature.vehicle.signal

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSignalProxyClasspathTest {
    private val jar = "tiny-listener".toByteArray()
    private val remote = mutableMapOf<String, ByteArray>()
    private val commands = mutableListOf<String>()

    @Test
    fun thinJarIsVerifiedStagedOnceAndReused() {
        val classpath = TurnSignalProxyClasspath(jar = { jar })

        val first = classpath.entry(::shell)
        val uploadsAfterFirstEntry = commands.count { "base64 -d" in it }

        assertEquals(expectedPath(jar), first)
        assertEquals(first, classpath.entry(::shell))
        assertEquals(uploadsAfterFirstEntry, commands.count { "base64 -d" in it })
        assertTrue(jar.contentEquals(remote[first]))
    }

    @Test(expected = IllegalStateException::class)
    fun failedStagingFailsClosedInsteadOfLoadingTheWholeApk() {
        TurnSignalProxyClasspath(jar = { jar }).entry { "" }
    }

    @Test
    fun sameSizeDifferentContentGetsADifferentImmutablePath() {
        val first = "same-size-one".toByteArray()
        val second = "same-size-two".toByteArray()

        val firstPath = TurnSignalProxyClasspath(jar = { first }).entry(::shell)
        val secondPath = TurnSignalProxyClasspath(jar = { second }).entry(::shell)

        assertNotEquals(firstPath, secondPath)
        assertEquals(expectedPath(first), firstPath)
        assertEquals(expectedPath(second), secondPath)
    }

    @Test
    fun corruptedRemoteFileIsReplacedEvenWhenItsSizeMatches() {
        val classpath = TurnSignalProxyClasspath(jar = { jar })
        val path = expectedPath(jar)
        remote[path] = ByteArray(jar.size) { 7 }

        assertEquals(path, classpath.entry(::shell))
        assertTrue(jar.contentEquals(remote[path]))
    }

    private fun shell(command: String): String {
        commands += command
        if (command.startsWith("sha256sum ")) {
            val path = Regex("sha256sum '([^']+)'").find(command)?.groupValues?.get(1)
            val bytes = path?.let(remote::get) ?: return ""
            return "${bytes.sha256()}  $path"
        }
        val path = Regex("> '([^']+)' ").find(command)?.groupValues?.get(1)
        val payload = Regex("printf '%s' '([^']*)'").find(command)?.groupValues?.get(1)
        if (path != null && payload != null) {
            remote[path] = Base64.getDecoder().decode(payload)
        }
        return ""
    }

    private fun expectedPath(bytes: ByteArray) =
        "/data/local/tmp/denza-vehicle-signal-${bytes.sha256()}.jar"

    private fun ByteArray.sha256(): String =
        MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
}
