package dev.denza.disharebridge

import java.io.IOException

/**
 * The library's own ADB failures, built the way the library builds them.
 *
 * Their constructors are the library's alone; a test that wants the real thing rather than a look-
 * alike with the same words reaches them from the library's package, which is what this is for.
 */
object AdbFailures {
    fun authorizationRequired(): IOException = LocalAdbClient.AuthorizationRequiredException()

    fun authorizationPending(): IOException = LocalAdbClient.authorizationPending()

    fun noHosts(): IOException = LocalAdbClient.NoHostsException()
}
