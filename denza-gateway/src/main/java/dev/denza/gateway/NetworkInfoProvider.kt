package dev.denza.gateway

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.InetAddress

class NetworkInfoProvider(
    private val context: Context,
) {
    fun currentWifiBinding(): WifiBinding? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return null
        }
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return null
        val linkAddress = linkProperties.linkAddresses
            .firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?: return null

        return WifiBinding(
            address = linkAddress.address as Inet4Address,
            prefixLength = linkAddress.prefixLength.takeIf { it in 1..32 } ?: 24,
        )
    }
}

data class Ipv4Subnet(
    val localAddress: Inet4Address,
    val prefixLength: Int,
) {
    private val safePrefix = prefixLength.coerceIn(0, 32)
    private val mask: Int = if (safePrefix == 0) 0 else (-1 shl (32 - safePrefix))
    private val network: Int = localAddress.toInt() and mask

    fun contains(address: InetAddress?): Boolean {
        if (address !is Inet4Address) return false
        return (address.toInt() and mask) == network
    }

    override fun toString(): String = "${localAddress.hostAddress}/$safePrefix"
}

fun Inet4Address.toInt(): Int {
    val bytes = address
    return ((bytes[0].toInt() and 0xff) shl 24) or
        ((bytes[1].toInt() and 0xff) shl 16) or
        ((bytes[2].toInt() and 0xff) shl 8) or
        (bytes[3].toInt() and 0xff)
}
