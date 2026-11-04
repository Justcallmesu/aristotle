package io.github.justcallmesu.aristotle.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.RouteInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import android.net.Network as AndroidNetwork

/**
 * Follows the phone's Wi-Fi network (never a VPN) and its IPv4 address and prefix (`ACCESS_NETWORK_STATE`), for the
 * join rule and the /24 of the manual search. With two Wi-Fi networks up (a hand-over), the one updated last counts.
 * [access] binds that network for discovery ([ScanGate]): every probe and check made through it leaves on that Wi-Fi.
 * When Android refuses the bind under a VPN, the VPN's routes decide whether plain sockets reach the Wi-Fi (TL1e).
 */
class AndroidWifiWatch(context: Context, private val log: (String) -> Unit = {}) : WifiRoutes {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val links = LinkedHashMap<Long, WifiLink>()
    private val state = MutableStateFlow(initial())
    val current: StateFlow<WifiLink?> = state.asStateFlow()

    init {
        // Callbacks arrive on ConnectivityManager's one thread, so [links] needs no lock.
        connectivity.registerNetworkCallback(WIFI, object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: AndroidNetwork, linkProperties: LinkProperties) {
                links.remove(network.networkHandle)
                wifiOf(network, linkProperties)?.let { links[network.networkHandle] = it }
                state.value = links.values.lastOrNull()
            }

            override fun onLost(network: AndroidNetwork) {
                links.remove(network.networkHandle)
                state.value = links.values.lastOrNull()
            }
        })
    }

    private fun initial(): WifiLink? {
        @Suppress("DEPRECATION") // still answers on API 36; the callback reports networks only later
        val networks = connectivity.allNetworks
        return networks.firstNotNullOfOrNull { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@firstNotNullOfOrNull null
            connectivity.getLinkProperties(network)?.let { wifiOf(network, it) }
        }
    }

    /** Decided afresh on each search: the user may have turned a Tailscale exit node on or off meanwhile. */
    override fun access(): WifiAccess =
        ScanGate.access(state.value, ::transportsOf, ::routeOf, ::vpnPath).also { log("lan route: ${ScanGate.mode(it)}") }

    private fun transportsOf(wifi: WifiLink): Transports? {
        val capabilities = connectivity.getNetworkCapabilities(AndroidNetwork.fromNetworkHandle(wifi.handle)) ?: return null
        return Transports(
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }

    /** Binds one throwaway socket first: Android refuses (EPERM) when a VPN may not be bypassed, and then nothing is sent. */
    private fun routeOf(wifi: WifiLink): WifiRoute {
        val network = AndroidNetwork.fromNetworkHandle(wifi.handle)
        Socket(Proxy.NO_PROXY).use { network.bindSocket(it) }
        return WifiRoute(wifi, DirectSocketFactory(bind = network::bindSocket), Dns { host -> network.getAllByName(host).toList() })
    }

    /** The default network when it is a VPN: the transports under it and its routes (excluded ones marked, API 33+). */
    private fun vpnPath(): VpnPath? {
        val active = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(active) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val routes = connectivity.getLinkProperties(active)?.routes ?: return null
        val transports = Transports(
            wifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            ethernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            vpn = true,
        )
        return VpnPath(transports, routes.mapNotNull(::vpnRouteOf))
    }

    /**
     * Before API 33 a VPN can only add routes (`VpnService.Builder.addRoute`), so each one is unicast. From 33 an app
     * targeting 33+ also sees the excluded ones (`excludeRoute`) as RTN_THROW, and RTN_UNREACHABLE drops: neither
     * takes traffic into the tunnel.
     */
    private fun vpnRouteOf(route: RouteInfo): VpnRoute? {
        val destination = route.destination ?: return null
        val host = destination.address?.hostAddress ?: return null
        val unicast = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || route.type == RouteInfo.RTN_UNICAST
        return VpnRoute(host, destination.prefixLength, unicast)
    }

    private fun wifiOf(network: AndroidNetwork, linkProperties: LinkProperties): WifiLink? {
        val ipv4 = linkProperties.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        return WifiLink(network.networkHandle, ipv4.address.hostAddress.orEmpty(), ipv4.prefixLength)
    }

    private companion object {
        val WIFI: NetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            // A Wi-Fi with no internet (the PC's own LAN only) counts too.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
    }
}

/**
 * The multicast lock (`CHANGE_WIFI_MULTICAST_STATE`, a normal permission): some Wi-Fi drivers drop multicast, mDNS
 * answers included, unless an app holds it. Reference counted, so two overlapping searches each hold their own.
 */
class AndroidMulticastGuard(context: Context) : MulticastGuard {
    private val lock = context.applicationContext.getSystemService(WifiManager::class.java)
        ?.createMulticastLock(LOCK_TAG)
        ?.apply { setReferenceCounted(true) }

    override fun acquire() {
        lock?.acquire()
    }

    override fun release() {
        lock?.takeIf { it.isHeld }?.release()
    }

    private companion object {
        const val LOCK_TAG = "aristotle-discovery"
    }
}

/** Plain TCP connect for the /24 probe through the route's sockets; never through a proxy, and nothing is sent. */
class TcpPortProbe : PortProbe {
    override suspend fun open(endpoint: ServerEndpoint, timeoutMillis: Int, route: WifiRoute): Boolean = withContext(Dispatchers.IO) {
        val socket = try {
            route.sockets.createSocket()
        } catch (_: IOException) {
            return@withContext false // the bind failed: the network went, nothing leaves another way
        }
        try {
            runInterruptible { socket.connect(InetSocketAddress(endpoint.inetAddress, endpoint.port), timeoutMillis) }
            true
        } catch (_: IOException) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }
}

/**
 * NSD browsing for `_aristotle._tcp` (protocol §15). Each service found is resolved one at a time: on API 34+ with
 * `registerServiceInfoCallback`, before with `resolveService`, which allows one resolve per app at a time (a second
 * gets FAILURE_ALREADY_ACTIVE), so that one is serialised process-wide. Only IPv4 addresses are passed on.
 * Discovery runs on the Wi-Fi network only on API 33+ (before, NSD takes no network; the on-link filter of [PcFinder]
 * covers it). Discovery is stopped when collection ends.
 */
class AndroidNsdBrowser(context: Context) : ServiceBrowser {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    override fun services(): Flow<FoundService> = browse(null)

    override fun services(wifi: WifiLink): Flow<FoundService> = browse(wifi)

    private fun browse(wifi: WifiLink?): Flow<FoundService> = callbackFlow {
        val found = Channel<NsdServiceInfo>(Channel.UNLIMITED)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                close(IOException("NSD discovery failed: $errorCode"))
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                found.trySend(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }
        if (wifi != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val network = AndroidNetwork.fromNetworkHandle(wifi.handle)
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, network, DIRECT, listener)
        } else {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        }
        val resolver = launch {
            val seen = HashSet<String>()
            for (info in found) {
                if (!seen.add(info.serviceName.orEmpty())) continue
                resolve(info)?.let { send(it) }
            }
        }
        awaitClose {
            resolver.cancel()
            found.close()
            runCatching { nsd.stopServiceDiscovery(listener) } // not registered when the start failed
        }
    }

    private suspend fun resolve(info: NsdServiceInfo): FoundService? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) resolveWithCallback(info) else resolveLegacy(info)

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private suspend fun resolveWithCallback(info: NsdServiceInfo): FoundService? {
        val result = CompletableDeferred<FoundService?>()
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                result.complete(null)
            }

            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                // An update can come before the A record does: wait for one with an IPv4 address.
                serviceOf(serviceInfo.hostAddresses, serviceInfo)?.let { result.complete(it) }
            }

            override fun onServiceLost() = Unit
            override fun onServiceInfoCallbackUnregistered() = Unit
        }
        return try {
            nsd.registerServiceInfoCallback(info, DIRECT, callback)
            withTimeoutOrNull(RESOLVE_MILLIS) { result.await() }
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            runCatching { nsd.unregisterServiceInfoCallback(callback) }
        }
    }

    /**
     * `resolveService` before API 34, in the app-wide [LEGACY_RESOLVE] slot: held at most [RESOLVE_MILLIS]. The slot
     * may come back while an earlier resolve that never answered is still running in the platform (there is no way to
     * stop one before API 34); this one then fails with FAILURE_ALREADY_ACTIVE, and is tried again a few times in
     * case the earlier one answers late.
     */
    private suspend fun resolveLegacy(info: NsdServiceInfo): FoundService? {
        repeat(LEGACY_TRIES) { attempt ->
            if (attempt > 0) delay(LEGACY_RETRY_MILLIS)
            when (val answer = resolveLegacyOnce(info)) {
                is LegacyAnswer.Resolved -> return answer.service
                LegacyAnswer.Busy -> Unit
                null, LegacyAnswer.Failed -> return null
            }
        }
        return null
    }

    private suspend fun resolveLegacyOnce(info: NsdServiceInfo): LegacyAnswer? = LEGACY_RESOLVE.resolve { answer ->
        @Suppress("DEPRECATION")
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) =
                answer(if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) LegacyAnswer.Busy else LegacyAnswer.Failed)

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                answer(LegacyAnswer.Resolved(serviceOf(listOfNotNull(serviceInfo.host), serviceInfo)))
            }
        })
    }

    private sealed interface LegacyAnswer {
        data class Resolved(val service: FoundService?) : LegacyAnswer
        data object Busy : LegacyAnswer
        data object Failed : LegacyAnswer
    }

    private fun serviceOf(addresses: List<InetAddress>, info: NsdServiceInfo): FoundService? {
        val hosts = addresses.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }.distinct()
        if (hosts.isEmpty()) return null
        return FoundService(hosts, info.port, info.attributes.orEmpty())
    }

    private companion object {
        const val SERVICE_TYPE = "_aristotle._tcp"
        const val RESOLVE_MILLIS = 3_000L
        const val LEGACY_TRIES = 3
        const val LEGACY_RETRY_MILLIS = 500L
        val DIRECT = Executor { it.run() }
        val MAIN = Handler(Looper.getMainLooper())
        val LEGACY_RESOLVE = ResolveSlot(RESOLVE_MILLIS) { delayMillis, action -> MAIN.postDelayed(action, delayMillis) }
    }
}
