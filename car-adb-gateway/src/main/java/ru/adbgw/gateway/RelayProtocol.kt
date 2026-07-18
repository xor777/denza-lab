package ru.adbgw.gateway

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

object RelayProtocol {
    fun enrollmentCommand(
        code: String,
        label: String,
        tunnelPublicKey: String,
        innerHostKey: String,
        endpoint: AdbEndpoint,
    ): String {
        val payload = JSONObject()
            .put("label", label)
            .put("tunnel_public_key", tunnelPublicKey)
            .put("inner_host_key", innerHostKey)
            .put("endpoint_mode", endpoint.kind.relayValue)
            .put("endpoint_host", endpoint.host)
        return "enroll ${normalizeCode(code)} ${encode(payload)}"
    }

    fun parseRegistration(response: String): RelayRegistration {
        val json = parseOk(response)
        val deviceId = json.getString("device_id")
        require(deviceId.matches(Regex("[a-f0-9]{16}"))) { "Relay returned an invalid device ID" }
        val relayHost = json.getString("relay_host")
        val relayPort = json.getInt("relay_ssh_port")
        require(relayHost == RELAY_HOST && relayPort == RELAY_SSH_PORT) {
            "Relay returned a different fixed identity"
        }
        return RelayRegistration(
            deviceId = deviceId,
            deviceLabel = json.optString("device_label", "Автомобиль"),
            relayDevicePort = json.getInt("relay_device_port").also {
                require(it in 1024..65_535) { "Relay returned an invalid device port" }
            },
            innerHostKey = json.getString("inner_host_key"),
            endpointKind = endpointKind(json.optString("endpoint_mode")),
            endpointHost = json.optString("endpoint_host").takeIf { it.isNotBlank() && it != "null" },
            enabled = json.optBoolean("enabled", true),
        )
    }

    fun parsePairingWindow(response: String): PairingWindow {
        val json = parseOk(response)
        return PairingWindow(
            requestId = json.getString("request_id"),
            code = normalizeCode(json.getString("code")),
            expiresAtEpochSeconds = json.getLong("expires_at"),
        )
    }

    fun parseOk(response: String): JSONObject {
        val line = response.lineSequence().firstOrNull { it.startsWith("OK ") }
            ?: error("Relay returned an invalid response")
        val encoded = line.substringAfter("OK ").trim()
        val decoded = Base64.getUrlDecoder().decode(encoded)
        return JSONObject(String(decoded, StandardCharsets.UTF_8))
    }

    fun normalizeCode(value: String): String {
        val compact = value.filter(Char::isLetterOrDigit).uppercase()
        require(compact.length == 8) { "Код должен содержать восемь символов" }
        require(compact.all { it in "23456789ABCDEFGHJKLMNPQRSTUVWXYZ" }) {
            "Код содержит недопустимый символ"
        }
        return "${compact.take(4)}-${compact.drop(4)}"
    }

    private fun encode(value: JSONObject): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(value.toString().toByteArray(StandardCharsets.UTF_8))

    private fun endpointKind(value: String): AdbEndpointKind? = when (value) {
        "smart" -> AdbEndpointKind.SmartSocket
        "raw" -> AdbEndpointKind.RawAdbd
        else -> null
    }
}
