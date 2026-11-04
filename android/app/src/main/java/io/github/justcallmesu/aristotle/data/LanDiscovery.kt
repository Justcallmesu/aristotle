package io.github.justcallmesu.aristotle.data

import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns

/** The phone's current Wi-Fi network as discovery needs it: its handle and its IPv4 address with the prefix length. */
data class WifiLink(val handle: Long, val address: String, val prefixLength: Int)

/**
 * The phone's Wi-Fi network as a route. Bound (TL1c, [vpnRoutes] null): [sockets] are bound to that network before
 * they connect and [dns] resolves on it, so whatever goes through the route leaves on that Wi-Fi whatever the default
 * network is (a VPN, or cellular beside a Wi-Fi without internet). Unbound (TL1e, [vpnRoutes] = the default VPN's
 * routes): plain sockets that leave on the Wi-Fi only because the VPN routes none of its subnet, the way the relay
 * reaches the LAN; [dns] then takes IP literals only. Every discovery probe and check uses one, and only to a host
 * the route [reaches].
 */
class WifiRoute(val link: WifiLink, val sockets: SocketFactory, val dns: Dns, val vpnRoutes: List<VpnRoute>? = null) {
    /** [host] is on this Wi-Fi's subnet and, on an unbound route, no VPN route takes it into the tunnel. */
    fun reaches(host: String): Boolean = SubnetScan.onLink(host, link) && (vpnRoutes == null || !VpnRouting.covers(vpnRoutes, host))
}

/** Whether discovery may run now, and through which route ([ScanGate.access]). */
sealed interface WifiAccess {
    data class Bound(val route: WifiRoute) : WifiAccess

    /**
     * The bind was refused under a VPN apps may not bypass (Tailscale), and that VPN routes none of the Wi-Fi's
     * subnet: plain sockets reach it over the Wi-Fi ([WifiRoute.vpnRoutes]).
     */
    data class Unbound(val route: WifiRoute) : WifiAccess

    /** No Wi-Fi network, or none discovery can reach without a tunnel: nothing is sent ("Can't scan this Wi-Fi"). */
    data object Unavailable : WifiAccess
}

/** The route discovery may use, or null when it may not run. */
fun WifiAccess.routeOrNull(): WifiRoute? = when (this) {
    is WifiAccess.Bound -> route
    is WifiAccess.Unbound -> route
    WifiAccess.Unavailable -> null
}

/** The phone's Wi-Fi for discovery, asked when a search starts; [AndroidWifiWatch] in the app. */
fun interface WifiRoutes {
    fun access(): WifiAccess
}

/** One IPv4 or IPv6 route of a VPN network ([android.net.RouteInfo]); [unicast] false for an excluded (throw/unreachable) one. */
data class VpnRoute(val destination: String, val prefixLength: Int, val unicast: Boolean = true)

/** The default network when it is a VPN: what it carries (the transports of the network under it) and its routes. */
data class VpnPath(val transports: Transports, val routes: List<VpnRoute>)

/** Whether a VPN sends an IPv4 address into its tunnel, from its routes. Pure. */
object VpnRouting {
    /**
     * True when the most specific IPv4 route of [routes] matching [host] is a unicast one (longest prefix wins, as in
     * the kernel; a tie counts as covered). An excluded route (RTN_THROW/RTN_UNREACHABLE, `excludeRoute`) more specific
     * than the covering one leaves the host out. No matching route: not covered. A host that is not IPv4: covered.
     */
    fun covers(routes: List<VpnRoute>, host: String): Boolean {
        val address = ipv4(host) ?: return true
        val matching = routes.filter { route -> ipv4(route.destination)?.let { matches(it, route.prefixLength, address) } == true }
        val longest = matching.maxOfOrNull { it.prefixLength } ?: return false
        return matching.any { it.prefixLength == longest && it.unicast }
    }

    /** The VPN takes any of [wifi]'s sample addresses: its network address, its broadcast and one host. */
    fun coversSubnet(routes: List<VpnRoute>, wifi: WifiLink): Boolean = samples(wifi).let { it.isEmpty() || it.any { host -> covers(routes, host) } }

    /** The network address, the broadcast and the first host other than the phone's own; empty when [wifi] does not parse. */
    fun samples(wifi: WifiLink): List<String> {
        val own = ipv4(wifi.address) ?: return emptyList()
        if (wifi.prefixLength !in 0..32) return emptyList()
        val mask = if (wifi.prefixLength == 0) 0 else -1 shl (32 - wifi.prefixLength)
        val network = own and mask
        val broadcast = network or mask.inv()
        val host = (network + 1).takeIf { it != own && it != broadcast } ?: (network + 2)
        return listOf(network, broadcast, host).filter { SubnetScan.onLink(text(it), wifi) }.distinct().map(::text)
    }

    private fun matches(prefix: Int, length: Int, address: Int): Boolean {
        if (length !in 0..32) return false
        val mask = if (length == 0) 0 else -1 shl (32 - length)
        return (prefix and mask) == (address and mask)
    }

    private fun ipv4(host: String): Int? = ServerAddress.parseIpLiteral(host)?.takeIf { it.size == 4 }?.let { b ->
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    private fun text(v: Int): String = "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
}

/** Names never leave an unbound route: discovery connects to IP literals only, and a DNS query could go into the VPN. */
object LiteralDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val bytes = ServerAddress.parseIpLiteral(hostname) ?: throw UnknownHostException("not an IP literal")
        return listOf(InetAddress.getByAddress(hostname, bytes))
    }
}

/** When discovery may connect anywhere (TL1c, TL1e, Q65): only where it reaches the Wi-Fi itself. Pure. */
object ScanGate {
    /**
     * [wifi] is the Wi-Fi network last reported; [transports] reads what ConnectivityManager says that network is now
     * (null: gone); [bind] binds a socket to it and returns the route, throwing when Android refuses; [vpn] reads the
     * default network when it is a VPN (null otherwise). Only a network that is Wi-Fi and not a VPN counts (a VPN
     * carries the Wi-Fi transport of the network under it). Bound when the bind works, whatever the default network is.
     * A refused bind (Android refuses it to an app under a VPN that does not allow bypass, whatever its routes) gives
     * an unbound route only when the default VPN runs over Wi-Fi alone and none of its routes takes the Wi-Fi's subnet
     * ([VpnRouting.coversSubnet]; a VPN without IPv4 routes cannot be judged). Anything else, a full tunnel, an exit
     * node without LAN access, a VPN over cellular or no VPN at all, means no search. Decided afresh on each call.
     */
    fun access(
        wifi: WifiLink?,
        transports: (WifiLink) -> Transports?,
        bind: (WifiLink) -> WifiRoute,
        vpn: () -> VpnPath? = { null },
    ): WifiAccess {
        if (wifi == null) return WifiAccess.Unavailable
        val kind = transports(wifi) ?: return WifiAccess.Unavailable
        if (!kind.wifi || kind.vpn) return WifiAccess.Unavailable
        return try {
            WifiAccess.Bound(bind(wifi))
        } catch (_: IOException) {
            unbound(wifi, vpn)
        } catch (_: RuntimeException) {
            unbound(wifi, vpn) // an unknown handle, a SecurityException
        }
    }

    private fun unbound(wifi: WifiLink, vpn: () -> VpnPath?): WifiAccess {
        val path = try {
            vpn()
        } catch (_: RuntimeException) {
            null
        } ?: return WifiAccess.Unavailable
        val over = path.transports
        if (!over.vpn || !over.wifi || over.cellular || over.ethernet) return WifiAccess.Unavailable
        if (path.routes.none { ServerAddress.parseIpLiteral(it.destination)?.size == 4 }) return WifiAccess.Unavailable
        if (VpnRouting.coversSubnet(path.routes, wifi)) return WifiAccess.Unavailable
        return WifiAccess.Unbound(WifiRoute(wifi, DirectSocketFactory(), LiteralDns, path.routes))
    }

    /** The mode for the debug log: `bound`, `unbound-vpn-no-route` or `unavailable`. */
    fun mode(access: WifiAccess): String = when (access) {
        is WifiAccess.Bound -> "bound"
        is WifiAccess.Unbound -> "unbound-vpn-no-route"
        WifiAccess.Unavailable -> "unavailable"
    }
}

/** One `_aristotle._tcp` service as NSD resolved it: its IPv4 literals, port and TXT keys (protocol §15). */
data class FoundService(val hosts: List<String>, val port: Int, val txt: Map<String, ByteArray?>)

/** NSD browsing for `_aristotle._tcp`, resolving each service it finds; [AndroidNsdBrowser] in the app. */
fun interface ServiceBrowser {
    /** Runs discovery while collected; stops it when collection ends. May end with an error (discovery failed). */
    fun services(): Flow<FoundService>

    /** [services] on [wifi]'s network only, where the platform can be told (API 33+); else on every network. */
    fun services(wifi: WifiLink): Flow<FoundService> = services()
}

/** `WifiManager.MulticastLock`: held only while discovery runs ([held]). */
interface MulticastGuard {
    fun acquire()
    fun release()
}

/** Holds the lock around [block] and releases it on every path: result, error or cancellation. */
suspend fun <T> MulticastGuard.held(block: suspend () -> T): T {
    acquire()
    try {
        return block()
    } finally {
        release()
    }
}

/**
 * `GET /healthz` through the PC's pinned client (protocol §11.2), its sockets on [route]: true only when that PC,
 * with that pin, answers on the Wi-Fi.
 */
fun interface PinnedHealth {
    suspend fun healthy(pc: PcCredentials, endpoint: ServerEndpoint, route: WifiRoute): Boolean
}

/** A plain TCP connect to [endpoint] within [timeoutMillis] through [route]: true when something accepts it. */
fun interface PortProbe {
    suspend fun open(endpoint: ServerEndpoint, timeoutMillis: Int, route: WifiRoute): Boolean
}

/** The manual search's phase, for the progress line. */
enum class FindPhase { Announcement, Scan }

/** Settings' "Find PC on this Wi-Fi" (Q65): idle, looking, or the last result ([FindResult]). */
sealed interface FindState {
    data object Idle : FindState
    data class Looking(val phase: FindPhase) : FindState
    data class Found(val endpoint: ServerEndpoint) : FindResult
    data object NotFound : FindResult

    /** No Wi-Fi network discovery may reach ([ScanGate]): nothing was looked for. */
    data object CantScan : FindResult
}

/** How a manual search ended. */
sealed interface FindResult : FindState

/** The manual search as Settings sees it. */
fun interface PcSearch {
    suspend fun find(onPhase: (FindPhase) -> Unit): FindResult
}

/** The TXT check of protocol §15: a cheap filter to skip other PCs' announcements. Never trust: the pin is. */
object DiscoveryTxt {
    /** The `fp` key is 16 hex digits; shorter would let nearly any announcement through, longer is a whole digest at most. */
    private const val MIN_FP = 16
    private const val MAX_FP = Fingerprint.BYTES * 2

    /**
     * True when [txt] has `v=1` and an `fp` that is a prefix of [fingerprint] (the stored pin, any case, spaces
     * allowed), compared case-insensitively. Keys are matched case-insensitively (DNS-SD); unknown keys are ignored.
     */
    fun accepts(txt: Map<String, ByteArray?>, fingerprint: String): Boolean {
        val version = value(txt, "v") ?: return false
        if (version != "1") return false
        val fp = value(txt, "fp")?.lowercase() ?: return false
        if (fp.length !in MIN_FP..MAX_FP || !fp.all { it in '0'..'9' || it in 'a'..'f' }) return false
        return fingerprint.filterNot { it == ' ' }.lowercase().startsWith(fp)
    }

    private fun value(txt: Map<String, ByteArray?>, key: String): String? =
        txt.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.toString(Charsets.US_ASCII)
}

/** The /24 the manual search probes (Q65). Pure: addresses and prefix in, candidate list out. */
object SubnetScan {
    private const val MIN_PREFIX = 24
    private const val MAX_HOST_PREFIX = 30

    /**
     * Every other host of [own]'s on-link subnet when that subnet is a /24 or smaller, in address order; [own] itself,
     * the network and the broadcast address are left out (a /31 has neither). Empty when the prefix is shorter than
     * /24 (never a wider scan), [own] is not an RFC1918 IPv4 address (the PC listens on those only, protocol §11.1)
     * or the input does not parse.
     */
    fun candidates(own: String, prefixLength: Int): List<String> {
        if (prefixLength !in MIN_PREFIX..32) return emptyList()
        val bytes = ServerAddress.parseIpLiteral(own)?.takeIf { it.size == 4 } ?: return emptyList()
        if (ServerAddress.networkOf(bytes) != Network.Lan) return emptyList()
        val address = toInt(bytes)
        val hostBits = 32 - prefixLength
        val mask = if (hostBits == 0) -1 else -1 shl hostBits
        val network = address and mask
        val size = 1 shl hostBits
        val hosts = if (prefixLength <= MAX_HOST_PREFIX) 1 until size - 1 else 0 until size
        return hosts.map { network + it }.filter { it != address }.map(::text)
    }

    /** Whether [wifi] can be scanned at all: a /24 or smaller around an RFC1918 address. */
    fun scannable(wifi: WifiLink): Boolean = candidates(wifi.address, wifi.prefixLength).isNotEmpty()

    /**
     * The ports the /24 probe tries (Q65): [ServerAddress.DEFAULT_PORT], plus the typed address's port when it differs
     * (every listener of a PC shares one port, protocol §11.1). Never more than two.
     */
    fun ports(pc: PcCredentials): List<Int> = listOfNotNull(ServerAddress.DEFAULT_PORT, pc.typed?.port).distinct()

    /** True when [host] (an IPv4 literal) lies in [wifi]'s on-link subnet. */
    fun onLink(host: String, wifi: WifiLink): Boolean {
        val a = ServerAddress.parseIpLiteral(host)?.takeIf { it.size == 4 } ?: return false
        val b = ServerAddress.parseIpLiteral(wifi.address)?.takeIf { it.size == 4 } ?: return false
        if (wifi.prefixLength !in 0..32) return false
        val mask = if (wifi.prefixLength == 0) 0 else -1 shl (32 - wifi.prefixLength)
        return (toInt(a) and mask) == (toInt(b) and mask)
    }

    private fun toInt(b: ByteArray): Int =
        ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)

    private fun text(v: Int): String = "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"
}

/** When the phone looks for the PC by itself (Q65): on joining a Wi-Fi network, unless the link already works on it. */
object DiscoveryRule {
    /** A Wi-Fi network is new: none before, another network, or the same one with another address. The first one seen counts. */
    fun joined(previous: WifiLink?, now: WifiLink?): Boolean = now != null && now != previous

    /** The link is up on a LAN address of this Wi-Fi's own subnet: nothing to find. */
    fun onWorkingLan(link: LinkState, wifi: WifiLink): Boolean =
        link is LinkState.Online && link.endpoint.network == Network.Lan && SubnetScan.onLink(link.endpoint.host, wifi)

    /**
     * The link has had its say about [wifi]: online (a LAN address of another subnet is the old network's, about to be
     * switched), offline between rounds, or stopped. Connecting or a round in progress is not settled.
     */
    fun settled(link: LinkState, wifi: WifiLink): Boolean = when (link) {
        LinkState.Idle, LinkState.Connecting -> false
        is LinkState.Online -> link.endpoint.network != Network.Lan || SubnetScan.onLink(link.endpoint.host, wifi)
        is LinkState.Offline -> !link.trying
        is LinkState.HermesDown, is LinkState.HermesUpdated, LinkState.Stopped, LinkState.Revoked -> true
    }

    /** Discover after a join unless the link works on this Wi-Fi's LAN, or there is no link to help (unpaired, stopped, revoked). */
    fun shouldDiscover(link: LinkState, wifi: WifiLink): Boolean = when (link) {
        LinkState.Idle, LinkState.Stopped, LinkState.Revoked -> false
        else -> !onWorkingLan(link, wifi)
    }
}

/**
 * Runs [probe] over [candidates] with at most [parallelism] at a time, in order, and returns the first non-null
 * result; the moment one hits, every other probe is cancelled and no further one starts. A probe that throws (other
 * than cancellation) counts as a miss. Cancelling the caller cancels every probe.
 */
suspend fun <C, T : Any> firstHit(candidates: List<C>, parallelism: Int, probe: suspend (C) -> T?): T? {
    if (candidates.isEmpty() || parallelism < 1) return null
    return coroutineScope {
        val queue = Channel<C>(Channel.UNLIMITED)
        candidates.forEach { queue.trySend(it) }
        queue.close()
        val hit = CompletableDeferred<T?>()
        val workers = List(min(parallelism, candidates.size)) {
            launch {
                for (candidate in queue) {
                    val result = try {
                        probe(candidate)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (result != null) {
                        hit.complete(result)
                        return@launch
                    }
                }
            }
        }
        launch {
            workers.joinAll()
            hit.complete(null)
        }
        val result = hit.await()
        coroutineContext.cancelChildren()
        result
    }
}

data class FinderTimings(
    /** The automatic discovery after a Wi-Fi join. */
    val autoWindowMillis: Long = 10_000,
    /** The manual search's NSD phase before it probes the /24. */
    val manualWindowMillis: Long = 5_000,
    val connectTimeoutMillis: Int = 300,
    val parallelism: Int = 32,
    /** One pinned `/healthz` check, connect and TLS included. */
    val verifyMillis: Long = 5_000,
)

/**
 * Finds the paired PC on the phone's Wi-Fi (spec Q65, protocol §15). Discovery is a hint; trust is the pin: an
 * address is used only after a `/healthz` call through the PC's pinned client answered, and then the link gets it as
 * a one-shot LAN candidate ([onFound]); it is stored only once a relay connects through it ([LinkManager.useFound]).
 * Every probe and check goes to the Wi-Fi network itself ([WifiRoute]); with no such route nothing is sent. A
 * wrong or unpinned PC is dropped without a trace; only counts are logged.
 */
class PcFinder(
    private val browser: ServiceBrowser,
    private val multicast: MulticastGuard,
    private val health: PinnedHealth,
    private val ports: PortProbe,
    private val onFound: (ServerEndpoint) -> Unit,
    private val log: (String) -> Unit = {},
    private val timings: FinderTimings = FinderTimings(),
) {
    /**
     * NSD for up to [windowMillis] with the multicast lock held; the first verified announcement wins. Only addresses
     * on [route]'s own subnet are checked: anything else is dropped before a connection is made.
     */
    suspend fun discover(pc: PcCredentials, route: WifiRoute, windowMillis: Long = timings.autoWindowMillis): ServerEndpoint? {
        var discovered = 0
        var rejected = 0
        val found = try {
            multicast.held {
                withTimeoutOrNull(windowMillis) {
                    browser.services(route.link).mapNotNull { service ->
                        discovered++
                        verifiedOf(pc, service, route).also { if (it == null) rejected++ }
                    }.firstOrNull()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null // discovery failed to start or broke off: nothing found
        }
        log("lan discovery: discovered=$discovered verified=${if (found != null) 1 else 0} rejected=$rejected")
        found?.let(onFound)
        return found
    }

    /**
     * The manual search: NSD first, then, if nothing answered, the /24 of the Wi-Fi. The route is taken again when the
     * scan starts (the Wi-Fi, or the VPN's routes, may have changed meanwhile). [FindState.CantScan] when there is none.
     */
    suspend fun findOnWifi(pc: PcCredentials, routes: WifiRoutes, onPhase: (FindPhase) -> Unit = {}): FindResult {
        val first = routes.access().routeOrNull() ?: return FindState.CantScan
        onPhase(FindPhase.Announcement)
        discover(pc, first, timings.manualWindowMillis)?.let { return FindState.Found(it) }
        val now = routes.access().routeOrNull() ?: return FindState.CantScan
        if (!SubnetScan.scannable(now.link)) return FindState.NotFound
        onPhase(FindPhase.Scan)
        return scan(pc, now)?.let { FindState.Found(it) } ?: FindState.NotFound
    }

    /** TCP connect to every /24 host on the probe port(s), then the pinned check on hits; stops at the first verified PC. */
    suspend fun scan(pc: PcCredentials, route: WifiRoute): ServerEndpoint? {
        val hosts = SubnetScan.candidates(route.link.address, route.link.prefixLength)
        val targets = SubnetScan.ports(pc).flatMap { port -> hosts.map { ServerEndpoint(it, port, Network.Lan) } }
        val open = AtomicInteger()
        val rejected = AtomicInteger()
        val found = firstHit(targets, timings.parallelism) { endpoint ->
            if (!route.reaches(endpoint.host)) return@firstHit null
            if (!ports.open(endpoint, timings.connectTimeoutMillis, route)) return@firstHit null
            open.incrementAndGet()
            endpoint.takeIf { verified(pc, it, route) }.also { if (it == null) rejected.incrementAndGet() }
        }
        log("lan scan: probed=${targets.size} open=${open.get()} verified=${if (found != null) 1 else 0} rejected=${rejected.get()}")
        found?.let(onFound)
        return found
    }

    /** The first address of [service] that passes the TXT filter, is a LAN address on [route]'s subnet and answers with the pin. */
    private suspend fun verifiedOf(pc: PcCredentials, service: FoundService, route: WifiRoute): ServerEndpoint? {
        if (!DiscoveryTxt.accepts(service.txt, pc.fingerprint)) return null
        return service.hosts.asSequence()
            .mapNotNull { (ServerAddress.parse("$it:${service.port}") as? AddressCheck.Ok)?.endpoint }
            .filter { it.network == Network.Lan && SubnetScan.onLink(it.host, route.link) }
            .firstOrNull { verified(pc, it, route) }
    }

    /** The pinned check, only to a host [route] reaches (on the Wi-Fi's subnet, and not into a VPN when unbound). */
    private suspend fun verified(pc: PcCredentials, endpoint: ServerEndpoint, route: WifiRoute): Boolean = if (!route.reaches(endpoint.host)) false else try {
        withTimeoutOrNull(timings.verifyMillis) { health.healthy(pc, endpoint, route) } == true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}

/**
 * The app-wide slot `resolveService` needs before API 34: one resolve per app at a time (a second gets
 * FAILURE_ALREADY_ACTIVE). [resolve] waits for the slot, starts the resolve with [start] (which gets the callback to
 * answer through) and returns its answer, or null when none came within [holdMillis]. The slot is given back on the
 * answer or [holdMillis] after the start, whichever comes first, even when the caller gave up sooner, so one
 * announcement that never resolves costs a discovery window at most [holdMillis].
 */
class ResolveSlot(
    private val holdMillis: Long,
    /** Runs the action after the delay (a main-thread Handler in the app). */
    private val schedule: (delayMillis: Long, action: () -> Unit) -> Unit,
) {
    private val permit = Semaphore(1)

    suspend fun <T : Any> resolve(start: (answer: (T?) -> Unit) -> Unit): T? {
        permit.acquire()
        val freed = AtomicBoolean(false)
        val free = { if (freed.compareAndSet(false, true)) permit.release() }
        val result = CompletableDeferred<T?>()
        try {
            start { value ->
                free()
                result.complete(value)
            }
        } catch (e: RuntimeException) {
            free()
            return null
        }
        schedule(holdMillis) { free() }
        val answer = withTimeoutOrNull(holdMillis) { result.await() }
        if (answer == null) free() // no answer in time: the slot goes back now, not only on the timer
        return answer
    }
}

/**
 * The automatic half of Q65: each time the phone joins a Wi-Fi network ([DiscoveryRule.joined]) it gives the link up to
 * [settleMillis] to come up on that network, then discovers through that Wi-Fi ([routes]) unless the link already works
 * on its LAN. A newer Wi-Fi cancels a search still running for the old one. Never probes the /24: that is the manual
 * button only.
 */
class LanAutoFind(
    private val scope: CoroutineScope,
    private val wifi: StateFlow<WifiLink?>,
    private val routes: WifiRoutes,
    private val link: StateFlow<LinkState>,
    private val paired: () -> PcCredentials?,
    private val finder: PcFinder,
    private val settleMillis: Long = 8_000,
) {
    fun start(): Job = scope.launch {
        var previous: WifiLink? = null
        wifi.collectLatest { now ->
            val joined = DiscoveryRule.joined(previous, now)
            previous = now
            if (!joined || now == null) return@collectLatest
            val state = withTimeoutOrNull(settleMillis) { link.first { DiscoveryRule.settled(it, now) } } ?: link.value
            val pc = paired() ?: return@collectLatest
            if (!DiscoveryRule.shouldDiscover(state, now)) return@collectLatest
            val route = routes.access().routeOrNull() ?: return@collectLatest
            finder.discover(pc, route)
        }
    }
}
