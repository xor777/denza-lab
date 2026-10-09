package dev.denza.apps.feature.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which internet the cloud link translates for the stock client: validated Wi-Fi, and nothing else.
 *
 * Wi-Fi is proven (2026-09-23). The cloud takes the car only by a Chinese SIM, which has no internet
 * outside China, so mobile data is never the link's network: on a local SIM the cloud refuses the
 * car, and a Chinese SIM with service is the stock framework's own APN.
 */
class CloudNetworkTest {

    @Test
    fun validatedWifiIsAlwaysUsable() {
        assertEquals(CloudNetworkKind.WIFI, kind(wifi = true, sim = null))
        // This car: the factory China Mobile SIM, dead, and Wi-Fi at home.
        assertEquals(CloudNetworkKind.WIFI, kind(wifi = true, sim = "46013"))
        // A local SIM does not take Wi-Fi away; the cloud refusing it is the cloud's answer.
        assertEquals(CloudNetworkKind.WIFI, kind(wifi = true, cellular = true, sim = "25001"))
    }

    @Test
    fun mobileDataIsNoNetwork() {
        assertEquals(CloudNetworkKind.NONE, kind(cellular = true, sim = "25001"))
        assertEquals(CloudNetworkKind.NONE, kind(cellular = true, sim = "46013"))
        assertEquals(CloudNetworkKind.NONE, kind(cellular = true, sim = null))
    }

    @Test
    fun theOperatorCodeIsOnlyForTheReport() {
        assertTrue(CloudNetwork.chineseSim("46011"))
        assertFalse(CloudNetwork.chineseSim("25002"))
        assertFalse(CloudNetwork.chineseSim(null))
    }

    /** Connected is not enough: a network the system has not validated has no internet to give. */
    @Test
    fun anUnvalidatedNetworkIsNoNetwork() {
        assertEquals(CloudNetworkKind.NONE, kind(validated = false, wifi = true, sim = null))
        assertEquals(CloudNetworkKind.NONE, kind(validated = false, cellular = true, sim = "25001"))
        assertEquals(CloudNetworkKind.NONE, kind(sim = "25001"))
    }

    private fun kind(
        validated: Boolean = true,
        wifi: Boolean = false,
        cellular: Boolean = false,
        sim: String?,
    ) = CloudNetworkReading(validated, wifi, cellular, sim).kind
}
