package dev.denza.gateway

data class ProbeCandidate(
    val kind: AdbEndpointKind,
    val host: String,
    val port: Int,
)

fun GatewayConfig.probeCandidates(): List<ProbeCandidate> =
    when (endpointMode) {
        EndpointMode.Auto -> listOf(
            ProbeCandidate(AdbEndpointKind.SmartSocket, adbServerHost, adbServerPort),
            ProbeCandidate(AdbEndpointKind.RawAdbd, rawAdbdHost, rawAdbdPort),
        )
        EndpointMode.AdbServer -> listOf(
            ProbeCandidate(AdbEndpointKind.SmartSocket, adbServerHost, adbServerPort),
        )
        EndpointMode.RawAdbd -> listOf(
            ProbeCandidate(AdbEndpointKind.RawAdbd, rawAdbdHost, rawAdbdPort),
        )
    }
