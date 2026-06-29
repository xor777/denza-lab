package dev.denza.gateway

import java.net.Inet4Address

const val SSH_USER = "denza"
const val SSH_PORT = 2222

enum class GatewayStatus(val title: String) {
    Stopped("Stopped"),
    Starting("Starting"),
    NoWifi("No Wi-Fi"),
    AdbUnavailable("ADB unavailable"),
    Ready("Ready"),
    Running("Running"),
    ClientConnected("Client connected"),
    BlockedPeer("Blocked peer"),
    Error("Error")
}

enum class EndpointMode(val title: String) {
    Auto("Auto"),
    AdbServer("ADB server"),
    RawAdbd("Raw adbd")
}

enum class AdbEndpointKind(val title: String) {
    SmartSocket("ADB server smart socket"),
    RawAdbd("Raw adbd")
}

data class GatewayConfig(
    val endpointMode: EndpointMode = EndpointMode.Auto,
    val adbServerHost: String = "127.0.0.1",
    val adbServerPort: Int = 5037,
    val rawAdbdHost: String = "127.0.0.1",
    val rawAdbdPort: Int = 5555,
    val sshPort: Int = SSH_PORT,
)

data class AdbEndpoint(
    val kind: AdbEndpointKind,
    val host: String,
    val port: Int,
    val detail: String,
)

data class WifiBinding(
    val address: Inet4Address,
    val prefixLength: Int,
) {
    val subnet: Ipv4Subnet = Ipv4Subnet(address, prefixLength)
    val hostAddress: String = address.hostAddress.orEmpty()
}

enum class LogLevel {
    Info,
    Warn,
    Error
}

data class LogEntry(
    val timestampMillis: Long,
    val level: LogLevel,
    val message: String,
)

data class GatewayUiState(
    val status: GatewayStatus = GatewayStatus.Stopped,
    val config: GatewayConfig = GatewayConfig(),
    val pairingCode: String = AccessCodeGenerator.generate(),
    val wifiBinding: WifiBinding? = null,
    val activeEndpoint: AdbEndpoint? = null,
    val hostFingerprint: String = "",
    val gatewayActive: Boolean = false,
    val isBusy: Boolean = false,
    val lastError: String? = null,
    val logs: List<LogEntry> = emptyList(),
) {
    val isRunning: Boolean
        get() = gatewayActive

    val isBlocked: Boolean
        get() = status == GatewayStatus.BlockedPeer
}

data class CommandSet(
    val sshTunnel: String,
    val adbCommand: String,
    val extraAdbCommand: String? = null,
)

fun GatewayUiState.commands(): CommandSet? {
    val ip = wifiBinding?.hostAddress?.takeIf { it.isNotBlank() } ?: return null
    val endpoint = activeEndpoint ?: return null
    return when (endpoint.kind) {
        AdbEndpointKind.SmartSocket -> CommandSet(
            sshTunnel = "ssh -p ${config.sshPort} -N -L 5038:${endpoint.host}:${endpoint.port} $SSH_USER@$ip",
            adbCommand = "adb -H 127.0.0.1 -P 5038 devices",
        )
        AdbEndpointKind.RawAdbd -> CommandSet(
            sshTunnel = "ssh -p ${config.sshPort} -N -L 5555:${endpoint.host}:${endpoint.port} $SSH_USER@$ip",
            adbCommand = "adb connect 127.0.0.1:5555",
            extraAdbCommand = "adb devices",
        )
    }
}
