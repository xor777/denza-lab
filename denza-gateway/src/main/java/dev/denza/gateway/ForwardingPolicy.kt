package dev.denza.gateway

import java.util.Locale

object ForwardingPolicy {
    fun isAllowedDestination(endpoint: AdbEndpoint, requestedHost: String, requestedPort: Int): Boolean {
        if (requestedPort != endpoint.port) return false
        val requested = requestedHost.lowercase(Locale.US)
        val configured = endpoint.host.lowercase(Locale.US)
        if (requested == configured) return true
        return configured == "127.0.0.1" && requested == "localhost"
    }
}
