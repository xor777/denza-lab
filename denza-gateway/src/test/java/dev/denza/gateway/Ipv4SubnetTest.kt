package dev.denza.gateway

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class Ipv4SubnetTest {
    @Test
    fun acceptsAddressesInsidePrefix() {
        val subnet = Ipv4Subnet(ipv4("192.168.10.22"), 24)

        assertTrue(subnet.contains(ipv4("192.168.10.1")))
        assertTrue(subnet.contains(ipv4("192.168.10.250")))
    }

    @Test
    fun rejectsAddressesOutsidePrefix() {
        val subnet = Ipv4Subnet(ipv4("192.168.10.22"), 24)

        assertFalse(subnet.contains(ipv4("192.168.11.1")))
        assertFalse(subnet.contains(ipv4("10.0.0.1")))
    }

    private fun ipv4(value: String): Inet4Address =
        InetAddress.getByName(value) as Inet4Address
}
