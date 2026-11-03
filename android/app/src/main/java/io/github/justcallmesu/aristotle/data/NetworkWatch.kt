package io.github.justcallmesu.aristotle.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import android.net.Network as AndroidNetwork

/** The phone's network as the link needs it; [AndroidNetworkWatch] in the app, a fake in tests. */
interface NetworkWatch {
    /** LAN addresses first ([EndpointChoice.prefersLan]). */
    fun prefersLan(): Boolean

    /** Emits when the default network changes: another network, none, or Wi-Fi gained or lost. */
    val changes: Flow<Unit>
}

/** The default network and whether it makes LAN addresses the first try. */
data class NetworkSnapshot(val handle: Long?, val prefersLan: Boolean)

/** Follows the default network (`ACCESS_NETWORK_STATE`). Capability updates that change neither field are ignored. */
class AndroidNetworkWatch(context: Context) : NetworkWatch {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val snapshot = MutableStateFlow(snapshotOf(connectivity.activeNetwork))

    override val changes: Flow<Unit> = snapshot.drop(1).map { }

    override fun prefersLan(): Boolean = snapshot.value.prefersLan

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: AndroidNetwork) {
                snapshot.value = snapshotOf(network)
            }

            override fun onCapabilitiesChanged(network: AndroidNetwork, capabilities: NetworkCapabilities) {
                snapshot.value = NetworkSnapshot(network.networkHandle, prefersLan(capabilities))
            }

            override fun onLost(network: AndroidNetwork) {
                snapshot.value = NetworkSnapshot(null, prefersLan = false)
            }
        })
    }

    private fun snapshotOf(network: AndroidNetwork?): NetworkSnapshot {
        val capabilities = network?.let(connectivity::getNetworkCapabilities)
        return NetworkSnapshot(network?.networkHandle, capabilities?.let(::prefersLan) ?: false)
    }

    private fun prefersLan(capabilities: NetworkCapabilities): Boolean = EndpointChoice.prefersLan(
        wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
        ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
        cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        notMetered = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
}
