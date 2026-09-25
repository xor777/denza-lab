package dev.denza.apps.feature.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which internet the cloud link translates for the stock client.
 *
 * Wi-Fi is proven (2026-09-23). Mobile transport is reported but never used by the link.
 */
class CloudNetworkTest {

    @Test
    fun validatedWifiIsAlwaysUsable() {
        assertEquals(CloudNetworkKind.WIFI, kind(wifi = true, sim = null))
        // This car: the factory China Mobile SIM, dead, and Wi-Fi at home.
        assertEquals(CloudNetworkKind.WIFI, kind(wifi = true, sim = "46013"))
    }

    @Test
    fun mobileDataIsRecognizedButNotUsable() {
        assertEquals(CloudNetworkKind.MOBILE, kind(cellular = true, sim = "25001"))
        assertFalse(CloudNetwork.usable(CloudNetworkReading(true, false, true, "25001")))
        assertTrue(CloudNetwork.usable(CloudNetworkReading(true, true, false, null)))
        assertFalse(CloudNetwork.usable(CloudNetworkReading(true, false, true, "25001"), CloudSimMode.FACTORY))
        assertTrue(CloudNetwork.usable(CloudNetworkReading(true, false, true, "25001"), CloudSimMode.CUSTOM))
        assertFalse(CloudNetwork.usable(CloudNetworkReading(false, false, true, "25001"), CloudSimMode.CUSTOM))
    }

    @Test
    fun operatorCodeDoesNotChangeNetworkClassification() {
        assertEquals(CloudNetworkKind.MOBILE, kind(cellular = true, sim = "46013"))
        assertEquals(CloudNetworkKind.MOBILE, kind(cellular = true, sim = "46000"))
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
