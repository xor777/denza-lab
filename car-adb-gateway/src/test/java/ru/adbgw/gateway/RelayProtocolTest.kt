package ru.adbgw.gateway

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.Base64

class RelayProtocolTest {
    @Test
    fun `normalizes human code`() {
        assertEquals("2345-6789", RelayProtocol.normalizeCode("2345 6789"))
        assertThrows(IllegalArgumentException::class.java) {
            RelayProtocol.normalizeCode("O0II-1111")
        }
    }

    @Test
    fun `parses pinned registration bundle`() {
        val json = JSONObject()
            .put("device_id", "0123456789abcdef")
            .put("device_label", "Test car")
            .put("relay_host", RELAY_HOST)
            .put("relay_ssh_port", RELAY_SSH_PORT)
            .put("relay_device_port", 20_000)
            .put("inner_host_key", "ssh-rsa test")
            .put("endpoint_mode", "raw")
            .put("endpoint_host", "192.168.1.9")
            .put("enabled", true)
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray())
        val registration = RelayProtocol.parseRegistration("OK $encoded")
        assertEquals("0123456789abcdef", registration.deviceId)
        assertEquals(AdbEndpointKind.RawAdbd, registration.endpointKind)
        assertEquals("192.168.1.9", registration.endpointHost)
    }
}
