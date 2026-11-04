package io.github.justcallmesu.aristotle.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import android.net.Network as AndroidNetwork

/** The phone's network as the link needs it; [AndroidNetworkWatch] in the app, a fake in tests. */
interface NetworkWatch {
    /** LAN addresses first ([EndpointChoice.prefersLan]). */
    fun prefersLan(): Boolean

    /** The default network is a VPN (Tailscale, most likely), so a tailnet miss is not "Tailscale may be off". */
    fun vpnUp(): Boolean

    /**
     * The Wi-Fi and Ethernet networks up now, by handle: what "the same Wi-Fi" means to [LanFlaps] (a new connection
     * gets a new handle). Empty when unknown.
     */
    fun localNetworks(): Set<Long> = emptySet()

    /**
     * Emits when the phone's network changes: another default network, none, Wi-Fi gained or lost, or a
     * Wi-Fi or Ethernet link under the default network changed (Wi-Fi to Wi-Fi under a VPN keeps the VPN).
     */
    val changes: Flow<Unit>
}

/** What one network carries, from its [NetworkCapabilities]. A VPN carries the transports of the network under it. */
data class Transports(
    val wifi: Boolean = false,
    val ethernet: Boolean = false,
    val cellular: Boolean = false,
    val vpn: Boolean = false,
    val notMetered: Boolean = false,
) {
    val prefersLan: Boolean get() = EndpointChoice.prefersLan(wifi, ethernet, cellular, notMetered)
}

/**
 * The phone's networks as far as the link cares; every difference is a change. [handle], [prefersLan] and
 * [vpn] describe the default network. [localLinks] holds every Wi-Fi or Ethernet network that is up (never a
 * VPN) with its IPv4 addresses: under a VPN the default network stays the same when the Wi-Fi under it is
 * replaced, so only these show the move (T09 review 6). IPv6 addresses are left out: privacy addresses
 * rotate on their own, and the PC's LAN addresses are IPv4.
 */
data class NetworkSnapshot(
    val handle: Long? = null,
    val prefersLan: Boolean = false,
    val vpn: Boolean = false,
    val localLinks: Map<Long, Set<String>> = emptyMap(),
) {
    fun withDefault(handle: Long, transports: Transports): NetworkSnapshot =
        copy(handle = handle, prefersLan = transports.prefersLan, vpn = transports.vpn)

    fun withoutDefault(): NetworkSnapshot = copy(handle = null, prefersLan = false, vpn = false)

    fun withLocalLink(handle: Long, addresses: List<InetAddress>): NetworkSnapshot {
        val ipv4 = addresses.filterIsInstance<Inet4Address>().mapTo(sortedSetOf()) { it.hostAddress.orEmpty() }
        return copy(localLinks = localLinks + (handle to ipv4))
    }

    fun withoutLocalLink(handle: Long): NetworkSnapshot = copy(localLinks = localLinks - handle)
}

/**
 * Follows the default network and the phone's Wi-Fi and Ethernet links (`ACCESS_NETWORK_STATE`). Updates
 * that change nothing in the [NetworkSnapshot] (signal strength, bandwidth) are ignored.
 */
class AndroidNetworkWatch(context: Context) : NetworkWatch {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val snapshot = MutableStateFlow(initialSnapshot())

    override val changes: Flow<Unit> = snapshot.drop(1).map { }

    override fun prefersLan(): Boolean = snapshot.value.prefersLan

    override fun vpnUp(): Boolean = snapshot.value.vpn

    override fun localNetworks(): Set<Long> = snapshot.value.localLinks.keys

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: AndroidNetwork) {
                val transports = connectivity.getNetworkCapabilities(network)?.let(::transportsOf) ?: Transports()
                snapshot.update { it.withDefault(network.networkHandle, transports) }
            }

            override fun onCapabilitiesChanged(network: AndroidNetwork, capabilities: NetworkCapabilities) {
                snapshot.update { it.withDefault(network.networkHandle, transportsOf(capabilities)) }
            }

            override fun onLost(network: AndroidNetwork) {
                snapshot.update { it.withoutDefault() }
            }
        })
        connectivity.registerNetworkCallback(LOCAL_LINKS, object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: AndroidNetwork, linkProperties: LinkProperties) {
                snapshot.update { it.withLocalLink(network.networkHandle, addressesOf(linkProperties)) }
            }

            override fun onLost(network: AndroidNetwork) {
                snapshot.update { it.withoutLocalLink(network.networkHandle) }
            }
        })
    }

    /**
     * The networks as they are now, so the callbacks' first reports (the same values) are no change. Without
     * the local links here, the current Wi-Fi would arrive as a change right after start and end a fresh relay.
     */
    private fun initialSnapshot(): NetworkSnapshot {
        val active = connectivity.activeNetwork
        val transports = active?.let(connectivity::getNetworkCapabilities)?.let(::transportsOf)
        val start = if (active != null && transports != null) NetworkSnapshot().withDefault(active.networkHandle, transports) else NetworkSnapshot()
        @Suppress("DEPRECATION") // still answers on API 36; the replacement (a callback) reports networks only later
        val networks = connectivity.allNetworks
        return networks.filter(::isLocalLink).fold(start) { snapshot, network ->
            connectivity.getLinkProperties(network)?.let { snapshot.withLocalLink(network.networkHandle, addressesOf(it)) } ?: snapshot
        }
    }

    private fun isLocalLink(network: AndroidNetwork): Boolean {
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return local && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    private fun addressesOf(linkProperties: LinkProperties): List<InetAddress> = linkProperties.linkAddresses.map { it.address }

    private fun transportsOf(capabilities: NetworkCapabilities) = Transports(
        wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        notMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )

    private companion object {
        /** Wi-Fi or Ethernet, never a VPN (the builder's default NOT_VPN, made explicit). */
        val LOCAL_LINKS: NetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
    }
}
