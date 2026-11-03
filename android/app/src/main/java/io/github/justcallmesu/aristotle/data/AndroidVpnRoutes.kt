package io.github.justcallmesu.aristotle.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress
import javax.net.SocketFactory

/**
 * The VPN network (Tailscale) whose routes cover an address. The active network comes first: a VPN that
 * applies to this app is its default network. Needs ACCESS_NETWORK_STATE.
 */
class AndroidVpnRoutes(context: Context) : VpnRoutes {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun socketFactoryFor(address: InetAddress): SocketFactory? =
        candidates().firstOrNull { routesThroughVpn(it, address) }?.let { vpn -> DirectSocketFactory(vpn::bindSocket) }

    private fun candidates(): List<android.net.Network> {
        val active = connectivity.activeNetwork
        // allNetworks is deprecated in favour of NetworkCallback; this is a one-shot lookup per socket.
        @Suppress("DEPRECATION")
        val all = connectivity.allNetworks.toList()
        return listOfNotNull(active) + all.filter { it != active }
    }

    private fun routesThroughVpn(network: android.net.Network, address: InetAddress): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        val routes = connectivity.getLinkProperties(network)?.routes ?: return false
        return routes.any { it.matches(address) }
    }
}
