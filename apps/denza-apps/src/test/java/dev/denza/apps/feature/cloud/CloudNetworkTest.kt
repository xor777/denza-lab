package dev.denza.apps.feature.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which internet the cloud link translates for the stock client.
 *
 * Wi-Fi is proven (2026-09-23). Mobile data from a local SIM is built for owners to test and is not
 * proven on any car. A Chinese SIM's mobile data is never used: a Chinese SIM with service is a
 * roaming SIM on BYD's private APN, and the adapter must not move that car off it.
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
        // An operator code the car will not give is not taken for a Chinese one.
        assertEquals(CloudNetworkKind.MOBILE, kind(cellular = true, sim = null))
    }

    /** A roaming Chinese SIM - an «rSIM» - is the stock client's own network; it is left alone. */
    @Test
    fun mobileDataFromAChineseSimIsNotOurs() {
        assertEquals(CloudNetworkKind.NONE, kind(cellular = true, sim = "46013"))
        assertEquals(CloudNetworkKind.NONE, kind(cellular = true, sim = "46000"))
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
    ) = CloudNetwork.kindOf(validated, wifi, cellular, sim)
}
