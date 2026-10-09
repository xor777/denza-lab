package dev.denza.apps.adb

import dev.denza.disharebridge.AdbFailures
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The channel's failures, read by their types - with the exceptions the transport really throws,
 * in the words it really throws them, rather than words chosen to match a rule.
 */
class AdbProblemTest {

    @Test
    fun aKeyTheCarDoesNotTrustIsNoAccess() {
        assertEquals(AdbProblem.NO_ACCESS, AdbProblem.of(AdbFailures.authorizationRequired()))
        assertEquals(AdbProblem.NO_ACCESS, AdbProblem.of(AdbFailures.authorizationPending()))
        assertEquals(AdbProblem.NO_ACCESS, AdbProblem.of(InvalidKeyException("bad key")))
        assertEquals(AdbProblem.NO_ACCESS, AdbProblem.of(GeneralSecurityException()))
    }

    /**
     * The timeouts are the point: "Read timed out" and Android's "failed to connect … after
     * 900ms" were the shapes no copy of the old rule recognised.
     */
    @Test
    fun anAdbdThatDoesNotAnswerIsNoLink() {
        assertEquals(AdbProblem.NO_LINK, AdbProblem.of(SocketTimeoutException("Read timed out")))
        assertEquals(
            AdbProblem.NO_LINK,
            AdbProblem.of(
                SocketTimeoutException(
                    "failed to connect to /127.0.0.1 (port 5555) from /:: (port 0) after 900ms",
                ),
            ),
        )
        assertEquals(
            AdbProblem.NO_LINK,
            AdbProblem.of(ConnectException("failed to connect to /127.0.0.1 (port 5555): ECONNREFUSED (Connection refused)")),
        )
        assertEquals(AdbProblem.NO_LINK, AdbProblem.of(NoRouteToHostException("Host unreachable")))
        assertEquals(AdbProblem.NO_LINK, AdbProblem.of(AdbFailures.noHosts()))
        assertEquals(AdbProblem.NO_LINK, AdbProblem.of(IOException("Connection refused")))
    }

    /** A feature wraps what it caught; the cause is still the channel's. */
    @Test
    fun theCauseIsReadThroughItsWrappers() {
        val wrapped = IllegalStateException("projection failed", IOException("shell", SocketTimeoutException("Read timed out")))
        assertEquals(AdbProblem.NO_LINK, AdbProblem.of(wrapped))
        val access = RuntimeException(IOException(AdbFailures.authorizationRequired()))
        assertEquals(AdbProblem.NO_ACCESS, AdbProblem.of(access))
    }

    /** No access outranks no link: a car that refuses the key also answers nothing useful. */
    @Test
    fun accessOutranksTheLink() {
        val both = IOException(AdbFailures.authorizationRequired()).apply {
            addSuppressed(SocketTimeoutException())
        }
        assertEquals(AdbProblem.NO_ACCESS, AdbProblem.of(ConnectException().apply { initCause(both) }))
    }

    /** What the feature itself failed at stays the feature's to name. */
    @Test
    fun aFailureThatIsNotTheChannelsIsNotOneOfThese() {
        assertNull(AdbProblem.of(null))
        assertNull(AdbProblem.of(IllegalStateException("navigation command returned no result")))
        assertNull(AdbProblem.of(IOException("APK copy stopped at 12 of 40 MiB")))
        assertNull(AdbProblem.of(IOException("the resident helper refused: busy")))
        assertNull(AdbProblem.of(EOFException("ADB interactive shell closed during command")))
    }

    /** The two kinds are told apart for the report and read as one on a tile. */
    @Test
    fun aTileReadsBothKindsTheSame() {
        assertEquals("Нет доступа", AdbProblem.WORDS)
        assertEquals(setOf("нет доступа", "нет связи"), AdbProblem.entries.map { it.report }.toSet())
    }
}
