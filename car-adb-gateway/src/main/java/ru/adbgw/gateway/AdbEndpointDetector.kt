package ru.adbgw.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class AdbCandidate(val kind: AdbEndpointKind, val host: String, val port: Int)

object AdbCandidatePlan {
    fun build(localIpv4: List<String>): List<AdbCandidate> {
        val hosts = buildList {
            add("127.0.0.1")
            localIpv4.filter { it != "127.0.0.1" }.distinct().forEach(::add)
        }
        return buildList {
            hosts.forEach { host ->
                add(AdbCandidate(AdbEndpointKind.SmartSocket, host, 5037))
                add(AdbCandidate(AdbEndpointKind.RawAdbd, host, 5555))
            }
        }
    }
}

class AdbEndpointDetector(
    private val connectTimeoutMillis: Int = 900,
    private val readTimeoutMillis: Int = 1_200,
) {
    suspend fun detect(): Result<AdbEndpoint> = withContext(Dispatchers.IO) {
        runCatching {
            candidates().firstNotNullOfOrNull { candidate ->
                when (candidate.kind) {
                    AdbEndpointKind.SmartSocket -> probeSmart(candidate)
                    AdbEndpointKind.RawAdbd -> probeRaw(candidate)
                }
            } ?: error("ADB is not reachable on this Android system")
        }
    }

    fun candidates(): List<AdbCandidate> = AdbCandidatePlan.build(localIpv4Addresses())

    private fun probeSmart(candidate: AdbCandidate): AdbEndpoint? = runCatching {
        Socket().use { socket ->
            socket.soTimeout = readTimeoutMillis
            socket.connect(InetSocketAddress(candidate.host, candidate.port), connectTimeoutMillis)
            socket.getOutputStream().apply {
                write(AdbProtocol.frameSmartSocketRequest("host:version"))
                flush()
            }
            val version = AdbProtocol.parseOkayPayload(socket.getInputStream()).trim()
            AdbEndpoint(candidate.kind, candidate.host, candidate.port, "ADB server $version")
        }
    }.getOrNull()

    private fun probeRaw(candidate: AdbCandidate): AdbEndpoint? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(candidate.host, candidate.port), connectTimeoutMillis)
        }
        AdbEndpoint(candidate.kind, candidate.host, candidate.port, "raw adbd is reachable")
    }.getOrNull()

    private fun localIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }
    }.getOrDefault(emptyList())
}
