package dev.denza.gateway

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

class AdbProbe(
    private val connectTimeoutMillis: Int = 900,
    private val readTimeoutMillis: Int = 1_200,
) {
    suspend fun detect(config: GatewayConfig): Result<AdbEndpoint> = withContext(Dispatchers.IO) {
        runCatching {
            config.probeCandidates().firstNotNullOfOrNull { candidate ->
                when (candidate.kind) {
                    AdbEndpointKind.SmartSocket -> probeSmartSocket(candidate.host, candidate.port)
                    AdbEndpointKind.RawAdbd -> probeRawAdbd(candidate.host, candidate.port)
                }
            } ?: when (config.endpointMode) {
                EndpointMode.Auto -> error("No ADB server at ${config.adbServerHost}:${config.adbServerPort} and no raw adbd at ${config.rawAdbdHost}:${config.rawAdbdPort}")
                EndpointMode.AdbServer -> error("ADB server did not answer at ${config.adbServerHost}:${config.adbServerPort}")
                EndpointMode.RawAdbd -> error("raw adbd TCP port is not reachable at ${config.rawAdbdHost}:${config.rawAdbdPort}")
            }
        }
    }

    fun probeSmartSocket(host: String, port: Int): AdbEndpoint? {
        return runCatching {
            val version = sendSmartSocketCommand(host, port, "host:version").ifBlank { "unknown" }
            val devices = sendSmartSocketCommand(host, port, "host:devices-l")
            val deviceSummary = devices.lineSequence()
                .filter { it.isNotBlank() }
                .take(2)
                .joinToString("; ")
                .ifBlank { "server answered; no devices listed" }
            AdbEndpoint(
                kind = AdbEndpointKind.SmartSocket,
                host = host,
                port = port,
                detail = "version=$version, $deviceSummary",
            )
        }.getOrNull()
    }

    fun probeRawAdbd(host: String, port: Int): AdbEndpoint? {
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            }
            AdbEndpoint(
                kind = AdbEndpointKind.RawAdbd,
                host = host,
                port = port,
                detail = "TCP connect succeeded; desktop adb will perform the ADB handshake",
            )
        }.getOrNull()
    }

    private fun sendSmartSocketCommand(host: String, port: Int, command: String): String {
        Socket().use { socket ->
            socket.soTimeout = readTimeoutMillis
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            socket.getOutputStream().write(AdbProtocol.frameSmartSocketRequest(command))
            socket.getOutputStream().flush()
            return AdbProtocol.parseOkayPayload(socket.getInputStream()).trim()
        }
    }
}
