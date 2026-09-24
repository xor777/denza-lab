package dev.denza.apps.feature.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which internet the cloud link translates for the stock client.
 *
 * Wi-Fi is proven (2026-09-23). Mobile data from a local SIM is built for owners to test and is not
 * proven on any car. The operator code does not establish private APN ownership;
 * actual stock APN state is guarded by the controller and its operations.
 */
class CloudNetworkTest {

    @Test
    fun validatedWifiIsAlwaysUsable() {
        assertEquals(CloudNetworkKind.WIFI, kind(wifi = true, sim = null))
        // This car: the factory China Mobile SIM, dead, and Wi-Fi at home.
        assertEquals(CloudNetworkKind.WIFI, kind(wifi = true, sim = "46013"))
    }

    @Test
    fun mobileDataFromALocalSimIsUsable() {
        assertEquals(CloudNetworkKind.MOBILE, kind(cellular = true, sim = "25001"))
        assertEquals(CloudNetworkKind.MOBILE, kind(cellular = true, sim = "25099"))
        // Missing operator metadata does not invalidate an otherwise validated network.
        assertEquals(CloudNetworkKind.MOBILE, kind(cellular = true, sim = null))
    }

    @Test
    fun operatorCodeCannotDisqualifyValidatedMobileInternet() {
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
