package io.github.justcallmesu.aristotle.data

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PIN = "7E54 D87B 19C0 A4F2 5B3E 0D96 C1A8 72FE 3E09 B6D4 8A15 F0C3 62D9 4E7A BCA7 EAA7"
private const val FP = "7e54d87b19c0a4f2" // protocol §15: first 16 hex digits, lowercase

private fun txt(vararg pairs: Pair<String, String?>): Map<String, ByteArray?> = pairs.associate { (k, v) -> k to v?.toByteArray() }

class DiscoveryTxtTest {
    @Test
    fun acceptsVersionOneAndAPrefixOfThePin() {
        assertTrue(DiscoveryTxt.accepts(txt("v" to "1", "fp" to FP, "proto" to "3"), PIN))
        assertTrue(DiscoveryTxt.accepts(txt("v" to "1", "fp" to FP.uppercase()), PIN))
        assertTrue(DiscoveryTxt.accepts(txt("v" to "1", "fp" to FP), PIN.lowercase().replace(" ", "")))
        assertTrue(DiscoveryTxt.accepts(txt("V" to "1", "FP" to FP), PIN)) // DNS-SD keys are case-insensitive
        assertTrue(DiscoveryTxt.accepts(txt("v" to "1", "fp" to FP, "future" to "x", "flag" to null), PIN))
        assertTrue(DiscoveryTxt.accepts(txt("v" to "1", "fp" to PIN.replace(" ", "").lowercase()), PIN)) // the whole digest
    }

    @Test
    fun refusesAnythingElse() {
        assertFalse(DiscoveryTxt.accepts(txt("v" to "1", "fp" to "0000000000000000"), PIN)) // another PC
        assertFalse(DiscoveryTxt.accepts(txt("v" to "2", "fp" to FP), PIN))
        assertFalse(DiscoveryTxt.accepts(txt("fp" to FP), PIN))
        assertFalse(DiscoveryTxt.accepts(txt("v" to "1"), PIN))
        assertFalse(DiscoveryTxt.accepts(txt("v" to null, "fp" to FP), PIN))
        assertFalse(DiscoveryTxt.accepts(txt("v" to "1", "fp" to null), PIN))
        assertFalse(DiscoveryTxt.accepts(txt("v" to "1", "fp" to ""), PIN)) // a prefix of everything
        assertFalse(DiscoveryTxt.accepts(txt("v" to "1", "fp" to "7e54d87b"), PIN)) // too short to tell PCs apart
        assertFalse(DiscoveryTxt.accepts(txt("v" to "1", "fp" to "7e54 d87b 19c0 a4f2"), PIN))
        assertFalse(DiscoveryTxt.accepts(txt("v" to "1", "fp" to PIN.replace(" ", "") + "00"), PIN))
        assertFalse(DiscoveryTxt.accepts(emptyMap(), PIN))
    }
}

class SubnetScanTest {
    @Test
    fun aSlash24IsEveryOtherHost() {
        val hosts = SubnetScan.candidates("192.168.1.37", 24)
        assertEquals(253, hosts.size)
        assertEquals("192.168.1.1", hosts.first())
        assertEquals("192.168.1.254", hosts.last())
        assertFalse("192.168.1.37" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
    }

    @Test
    fun prefixRules() {
        assertEquals(emptyList<String>(), SubnetScan.candidates("10.1.2.3", 23)) // never wider than a /24
        assertEquals(emptyList<String>(), SubnetScan.candidates("10.1.2.3", 16))
        assertEquals(125, SubnetScan.candidates("10.1.2.3", 25).size) // 10.1.2.1-126 without itself
        assertEquals(listOf("172.16.0.5"), SubnetScan.candidates("172.16.0.6", 30))
        assertEquals(listOf("172.16.0.7"), SubnetScan.candidates("172.16.0.6", 31))
        assertEquals(emptyList<String>(), SubnetScan.candidates("172.16.0.6", 32))
        assertEquals(emptyList<String>(), SubnetScan.candidates("172.16.0.6", 33))
    }

    @Test
    fun onlyPrivateIpv4IsScanned() {
        assertEquals(emptyList<String>(), SubnetScan.candidates("100.74.61.96", 24)) // tailnet
        assertEquals(emptyList<String>(), SubnetScan.candidates("8.8.8.8", 24))
        assertEquals(emptyList<String>(), SubnetScan.candidates("169.254.3.4", 24))
        assertEquals(emptyList<String>(), SubnetScan.candidates("fe80::1", 64))
        assertEquals(emptyList<String>(), SubnetScan.candidates("not an address", 24))
        assertFalse(SubnetScan.scannable(WifiLink(1, "192.168.1.2", 16)))
        assertTrue(SubnetScan.scannable(WifiLink(1, "192.168.1.2", 24)))
    }

    @Test
    fun the8760PlusAtMostTheTypedPort() {
        val many = (1..8).map { ServerEndpoint("10.0.0.$it", 9000 + it, Network.Lan) }
        assertEquals(listOf(8760), SubnetScan.ports(pcWith(*many.toTypedArray()))) // stored ports are not probed
        assertEquals(listOf(8760, 9001), SubnetScan.ports(pcWith(*many.toTypedArray()).copy(typed = many.first())))
        assertEquals(listOf(8760, 8761), SubnetScan.ports(pcWith().copy(typed = ServerEndpoint("100.74.61.96", 8761, Network.Tailscale)))) // one port per PC
        assertEquals(listOf(8760), SubnetScan.ports(pcWith().copy(typed = ServerEndpoint("192.168.1.2", 8760, Network.Lan))))
    }

    @Test
    fun onLinkFollowsThePrefix() {
        val wifi = WifiLink(1, "192.168.1.37", 24)
        assertTrue(SubnetScan.onLink("192.168.1.61", wifi))
        assertFalse(SubnetScan.onLink("192.168.2.61", wifi))
        assertTrue(SubnetScan.onLink("192.168.2.61", wifi.copy(prefixLength = 16)))
    }
}

class DiscoveryRuleTest {
    private val wifi = WifiLink(7, "192.168.1.37", 24)
    private val here = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val oldLan = ServerEndpoint("10.0.0.61", 8760, Network.Lan)
    private val tailnet = ServerEndpoint("100.74.61.96", 8760, Network.Tailscale)
    private fun offline(trying: Boolean) = LinkState.Offline(0, OfflineCause.Unreachable, "", null, 1, if (trying) null else 1_000)

    @Test
    fun aJoinIsANewWifiOrANewAddress() {
        assertTrue(DiscoveryRule.joined(null, wifi)) // the first one seen counts
        assertTrue(DiscoveryRule.joined(wifi, wifi.copy(handle = 8)))
        assertTrue(DiscoveryRule.joined(wifi, wifi.copy(address = "192.168.1.38")))
        assertFalse(DiscoveryRule.joined(wifi, wifi))
        assertFalse(DiscoveryRule.joined(wifi, null)) // Wi-Fi lost
    }

    @Test
    fun discoverUnlessTheLinkWorksOnThisLan() {
        assertFalse(DiscoveryRule.shouldDiscover(LinkState.Online(here, 1), wifi))
        assertTrue(DiscoveryRule.shouldDiscover(LinkState.Online(oldLan, 1), wifi)) // the old network's address
        assertTrue(DiscoveryRule.shouldDiscover(LinkState.Online(tailnet, 1), wifi))
        assertTrue(DiscoveryRule.shouldDiscover(offline(trying = false), wifi))
        assertTrue(DiscoveryRule.shouldDiscover(LinkState.Connecting, wifi))
        assertTrue(DiscoveryRule.shouldDiscover(LinkState.HermesDown(""), wifi))
        assertFalse(DiscoveryRule.shouldDiscover(LinkState.Idle, wifi))
        assertFalse(DiscoveryRule.shouldDiscover(LinkState.Stopped, wifi))
        assertFalse(DiscoveryRule.shouldDiscover(LinkState.Revoked, wifi))
    }

    @Test
    fun theLinkHasSettledOnceItIsOnlineHereOrBetweenRounds() {
        assertFalse(DiscoveryRule.settled(LinkState.Connecting, wifi))
        assertFalse(DiscoveryRule.settled(offline(trying = true), wifi))
        assertFalse(DiscoveryRule.settled(LinkState.Online(oldLan, 1), wifi)) // about to switch
        assertTrue(DiscoveryRule.settled(LinkState.Online(here, 2), wifi))
        assertTrue(DiscoveryRule.settled(LinkState.Online(tailnet, 2), wifi))
        assertTrue(DiscoveryRule.settled(offline(trying = false), wifi))
        assertTrue(DiscoveryRule.settled(LinkState.Stopped, wifi))
    }
}

class FirstHitTest {
    @Test
    fun neverMoreThanTheCapAtOnce() = runBlocking(Dispatchers.Default) {
        val running = AtomicInteger()
        val most = AtomicInteger()
        val result = firstHit((1..100).toList(), parallelism = 32) {
            most.accumulateAndGet(running.incrementAndGet(), ::maxOf)
            delay(50)
            running.decrementAndGet()
            null as Int?
        }
        assertNull(result)
        assertEquals(32, most.get())
    }

    @Test
    fun stopsAtTheFirstHitAndCancelsTheRest() = runBlocking(Dispatchers.Default) {
        val started = AtomicInteger()
        val cancelled = AtomicInteger()
        val finished = AtomicInteger()
        val result = firstHit((1..300).toList(), parallelism = 4) { n ->
            started.incrementAndGet()
            if (n == 10) return@firstHit "hit $n"
            try {
                delay(if (n < 10) 1 else 10_000)
            } catch (e: kotlinx.coroutines.CancellationException) {
                cancelled.incrementAndGet()
                throw e
            }
            finished.incrementAndGet()
            null
        }
        assertEquals("hit 10", result)
        assertTrue("nothing past the hit plus the slots: ${started.get()}", started.get() <= 13)
        assertEquals(started.get() - 1, finished.get() + cancelled.get())
    }

    @Test
    fun aThrowingProbeIsAMiss() = runBlocking(Dispatchers.Default) {
        val result = firstHit(listOf(1, 2, 3), parallelism = 2) { n -> if (n < 3) throw IOException("refused") else n }
        assertEquals(3, result)
    }

    @Test
    fun cancellingTheCallerCancelsEveryProbe() = runBlocking(Dispatchers.Default) {
        val running = AtomicInteger()
        val cancelled = AtomicInteger()
        val search = async {
            firstHit((1..10).toList(), parallelism = 5) {
                running.incrementAndGet()
                try {
                    awaitCancellation()
                } finally {
                    cancelled.incrementAndGet()
                }
            }
        }
        withTimeout(5_000) { while (running.get() < 5) delay(5) }
        search.cancelAndJoin()
        assertEquals(5, running.get())
        assertEquals(5, cancelled.get())
    }

    @Test
    fun nothingToProbe() = runBlocking {
        assertNull(firstHit(emptyList<Int>(), parallelism = 32) { it })
    }
}

/** A route on [link] whose sockets are never used by the fakes; [bound] counts the sockets bound through it. */
private fun routeOn(link: WifiLink, bound: AtomicInteger = AtomicInteger()) =
    WifiRoute(link, DirectSocketFactory(bind = { bound.incrementAndGet() }), okhttp3.Dns.SYSTEM)

/** TL1c MAJOR-1: discovery connects only through the Wi-Fi network itself. */
class ScanGateTest {
    private val wifi = WifiLink(7, "192.168.1.37", 24)
    private val binds = CopyOnWriteArrayList<WifiLink>()
    private val bindOk: (WifiLink) -> WifiRoute = { binds += it; routeOn(it) }
    private val refused: (WifiLink) -> WifiRoute = { binds += it; throw java.net.SocketException("Binding socket to network 7: EPERM") }

    /** The phone's networks by handle: what ConnectivityManager reports for each. */
    private fun phone(vararg networks: Pair<Long, Transports>): (WifiLink) -> Transports? = { link -> networks.toMap()[link.handle] }

    @Test
    fun noWifiNoScan() {
        assertEquals(WifiAccess.Unavailable, ScanGate.access(null, phone(1L to Transports(cellular = true)), bindOk))
        assertEquals(WifiAccess.Unavailable, ScanGate.access(wifi, phone(1L to Transports(cellular = true)), bindOk)) // the Wi-Fi went
        assertTrue(binds.isEmpty())
    }

    @Test
    fun aVpnIsNeverTheWifiEvenThoughItCarriesItsTransport() {
        assertEquals(WifiAccess.Unavailable, ScanGate.access(wifi, phone(7L to Transports(wifi = true, vpn = true)), bindOk))
        assertTrue(binds.isEmpty())
    }

    @Test
    fun aVpnDefaultWithNoLanAccessRefusesTheBindSoNothingIsScanned() {
        val vpnDefault = phone(9L to Transports(wifi = true, vpn = true), 7L to Transports(wifi = true))
        assertEquals(WifiAccess.Unavailable, ScanGate.access(wifi, vpnDefault, refused))
        assertEquals(listOf(wifi), binds) // tried on the Wi-Fi itself, never the VPN
    }

    @Test
    fun aVpnDefaultThatLetsTheBindThroughScansTheWifiNotTheTunnel() {
        val vpnDefault = phone(9L to Transports(wifi = true, vpn = true), 7L to Transports(wifi = true))
        val access = ScanGate.access(wifi, vpnDefault, bindOk) as WifiAccess.Bound
        assertEquals(wifi, access.route.link)
    }

    @Test
    fun cellularDefaultBesideAWifiWithoutInternetStillScansBound() {
        val cellularDefault = phone(3L to Transports(cellular = true), 7L to Transports(wifi = true))
        val access = ScanGate.access(wifi, cellularDefault, bindOk)
        assertTrue(access is WifiAccess.Bound)
        assertEquals(listOf(wifi), binds)
    }

    @Test
    fun anyBindFailureMeansNoScan() {
        val here = phone(7L to Transports(wifi = true))
        assertEquals(WifiAccess.Unavailable, ScanGate.access(wifi, here, refused))
        assertEquals(WifiAccess.Unavailable, ScanGate.access(wifi, here, bind = { throw IllegalArgumentException("unknown handle") }))
        assertEquals(WifiAccess.Unavailable, ScanGate.access(wifi, here, bind = { throw SecurityException("no") }))
    }

    // TL1e (MAJOR-2): Android refuses the bind to every app under Tailscale; its routes decide whether plain sockets reach the Wi-Fi.
    private val tailscaleRoutes = listOf(
        VpnRoute("100.64.0.0", 10),
        VpnRoute("100.100.100.100", 32),
        VpnRoute("fd7a:115c:a1e0::", 48),
    )
    private val overWifi = Transports(wifi = true, vpn = true)
    private val here = phone(7L to Transports(wifi = true))

    @Test
    fun tailscaleWithoutAnExitNodeGivesAnUnboundRouteOnTheWifi() {
        val access = ScanGate.access(wifi, here, refused) { VpnPath(overWifi, tailscaleRoutes) }
        val route = (access as WifiAccess.Unbound).route
        assertEquals(wifi, route.link)
        assertEquals(tailscaleRoutes, route.vpnRoutes)
        assertEquals(listOf(wifi), binds) // the bind was tried first
        assertTrue(route.reaches("192.168.1.61"))
        assertFalse("off the Wi-Fi's subnet", route.reaches("192.168.2.61"))
        assertFalse("a tailnet address is the VPN's", route.reaches("100.74.61.96"))
        route.sockets.createSocket().close() // plain sockets, nothing to bind
        assertEquals("192.168.1.61", LiteralDns.lookup("192.168.1.61").single().hostAddress)
        assertThrows(java.net.UnknownHostException::class.java) { route.dns.lookup("pc.local") } // names never resolve
        assertEquals("unbound-vpn-no-route", ScanGate.mode(access))
    }

    @Test
    fun aVpnRouteOverTheWifiSubnetKeepsCantScan() {
        val covering = listOf(
            listOf(VpnRoute("0.0.0.0", 0)), // exit node without "Allow LAN access", any full tunnel
            tailscaleRoutes + VpnRoute("192.168.0.0", 16), // a subnet router for this LAN
            tailscaleRoutes + VpnRoute("192.168.1.128", 25), // half of it: the broadcast is the VPN's
        )
        for (routes in covering) {
            assertEquals(routes.toString(), WifiAccess.Unavailable, ScanGate.access(wifi, here, refused) { VpnPath(overWifi, routes) })
        }
    }

    // TH1 (TL1e review MINOR-C): the VPN's routes are read again for each target, so an exit node turned on mid-search
    // (or the VPN going away) stops the rest of it.
    @Test
    fun theVpnRoutesAreReadAgainForEachTarget() {
        var path: VpnPath? = VpnPath(overWifi, tailscaleRoutes)
        val route = (ScanGate.access(wifi, here, refused) { path } as WifiAccess.Unbound).route
        assertTrue(route.reaches("192.168.1.61"))
        path = VpnPath(overWifi, listOf(VpnRoute("0.0.0.0", 0))) // exit node on, no LAN access
        assertFalse(route.reaches("192.168.1.61"))
        path = VpnPath(overWifi, tailscaleRoutes)
        assertTrue(route.reaches("192.168.1.61"))
        path = null // the VPN went: the default network is something else now
        assertFalse(route.reaches("192.168.1.61"))
        path = VpnPath(Transports(cellular = true, vpn = true), tailscaleRoutes)
        assertFalse("now over cellular", route.reaches("192.168.1.61"))
    }

    // TH1 (TL1e review MINOR-B): an unbound socket must come up on the Wi-Fi's own address, else it is closed before
    // a byte is sent and the target is a miss. Loopback stands in for "another network": its address is not the Wi-Fi's.
    @Test
    fun anUnboundSocketOnAnotherNetworkIsClosedBeforeAnyByte() = runBlocking {
        java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val target = ServerEndpoint("127.0.0.1", server.localPort, Network.Lan)
            val elsewhere = (ScanGate.access(wifi, here, refused) { VpnPath(overWifi, tailscaleRoutes) } as WifiAccess.Unbound).route
            val socket = elsewhere.sockets.createSocket()
            assertThrows(IOException::class.java) { socket.connect(java.net.InetSocketAddress(target.inetAddress, target.port), 1_000) }
            assertTrue(socket.isClosed)
            server.soTimeout = 1_000
            server.accept().use { accepted -> assertEquals("nothing was sent", -1, accepted.getInputStream().read()) }
            assertFalse("the probe counts it as a miss", TcpPortProbe().open(target, 1_000, elsewhere))

            val onWifi = WifiLink(7, "127.0.0.1", 8) // the socket's own address is this "Wi-Fi"'s
            val onIt = (ScanGate.access(onWifi, phone(7L to Transports(wifi = true)), refused) { VpnPath(overWifi, tailscaleRoutes) } as WifiAccess.Unbound).route
            assertTrue(TcpPortProbe().open(target, 1_000, onIt))
        }
    }

    @Test
    fun theLocalAddressCheckComparesAddressesNotText() {
        assertTrue(LocalAddressCheck.sameAddress("192.168.1.37", "192.168.1.37"))
        assertFalse(LocalAddressCheck.sameAddress("10.0.0.37", "192.168.1.37"))
        assertFalse(LocalAddressCheck.sameAddress(null, "192.168.1.37"))
        assertFalse(LocalAddressCheck.sameAddress("0.0.0.0", "192.168.1.37"))
    }

    @Test
    fun excludedRoutesLeaveTheLanOut() {
        val exitNodeWithLanAccess = listOf(VpnRoute("0.0.0.0", 0), VpnRoute("192.168.0.0", 16, unicast = false))
        val route = (ScanGate.access(wifi, here, refused) { VpnPath(overWifi, exitNodeWithLanAccess) } as WifiAccess.Unbound).route
        assertTrue(route.reaches("192.168.1.61"))
    }

    @Test
    fun withoutAVpnOverThisWifiARefusedBindStillMeansNoScan() {
        val cases = listOf<() -> VpnPath?>(
            { null }, // no VPN: the network went, or something else refused
            { VpnPath(Transports(cellular = true, vpn = true), tailscaleRoutes) }, // the tunnel runs over cellular
            { VpnPath(Transports(wifi = true, cellular = true, vpn = true), tailscaleRoutes) },
            { VpnPath(Transports(wifi = true), tailscaleRoutes) }, // not a VPN
            { VpnPath(overWifi, emptyList()) }, // routes unknown
            { VpnPath(overWifi, listOf(VpnRoute("fd7a:115c:a1e0::", 48))) }, // no IPv4 route to judge by
            { throw SecurityException("no") },
        )
        for ((i, vpn) in cases.withIndex()) assertEquals("case $i", WifiAccess.Unavailable, ScanGate.access(wifi, here, refused, vpn))
    }

    @Test
    fun aBindThatWorksStaysBoundWhateverTheVpnRoutes() {
        val access = ScanGate.access(wifi, here, bindOk) { VpnPath(overWifi, listOf(VpnRoute("0.0.0.0", 0))) }
        assertTrue(access is WifiAccess.Bound)
        assertEquals("bound", ScanGate.mode(access))
        assertEquals("unavailable", ScanGate.mode(WifiAccess.Unavailable))
    }

    @Test
    fun anUnboundRouteChecksEachTargetAgainstTheVpnRoutes() {
        val routes = tailscaleRoutes + VpnRoute("192.168.1.200", 32) // one host of the LAN goes into the tunnel
        val route = (ScanGate.access(wifi, here, refused) { VpnPath(overWifi, routes) } as WifiAccess.Unbound).route
        assertTrue(route.reaches("192.168.1.61"))
        assertFalse(route.reaches("192.168.1.200"))
    }

    @Test
    fun aBoundSocketFactoryNeverHandsOutAnUnboundSocket() {
        val bound = AtomicInteger()
        val ok = DirectSocketFactory(bind = { bound.incrementAndGet() })
        ok.createSocket().close()
        assertEquals(1, bound.get())
        val failing = DirectSocketFactory(bind = { throw java.net.SocketException("EPERM") })
        assertThrows(java.net.SocketException::class.java) { failing.createSocket() }
        assertThrows(java.net.SocketException::class.java) { failing.createSocket("127.0.0.1", 9) }
    }
}

/** TL1e: which addresses a VPN takes into its tunnel, by longest-prefix match over its routes. */
class VpnRoutingTest {
    @Test
    fun theMostSpecificRouteDecides() {
        val routes = listOf(VpnRoute("0.0.0.0", 0), VpnRoute("192.168.0.0", 16, unicast = false), VpnRoute("192.168.1.5", 32))
        assertTrue(VpnRouting.covers(routes, "8.8.8.8"))
        assertFalse(VpnRouting.covers(routes, "192.168.1.6"))
        assertTrue(VpnRouting.covers(routes, "192.168.1.5"))
        assertFalse("no route matches", VpnRouting.covers(listOf(VpnRoute("100.64.0.0", 10)), "192.168.1.6"))
        assertTrue("100.64/10 holds the tailnet", VpnRouting.covers(listOf(VpnRoute("100.64.0.0", 10)), "100.127.255.1"))
    }

    @Test
    fun aTieCountsAsCoveredAndIpv6RoutesNeverMatchIpv4() {
        assertTrue(VpnRouting.covers(listOf(VpnRoute("192.168.1.0", 24, unicast = false), VpnRoute("192.168.1.0", 24)), "192.168.1.9"))
        assertFalse(VpnRouting.covers(listOf(VpnRoute("::", 0)), "192.168.1.9"))
        assertTrue("only IPv4 hosts can be judged", VpnRouting.covers(emptyList(), "fe80::1"))
    }

    @Test
    fun theSamplesAreTheNetworkTheBroadcastAndOneHost() {
        assertEquals(listOf("192.168.1.0", "192.168.1.255", "192.168.1.1"), VpnRouting.samples(WifiLink(1, "192.168.1.10", 24)))
        assertEquals(listOf("192.168.1.0", "192.168.1.255", "192.168.1.2"), VpnRouting.samples(WifiLink(1, "192.168.1.1", 24)))
        assertEquals(listOf("10.1.2.3"), VpnRouting.samples(WifiLink(1, "10.1.2.3", 32)))
        assertTrue(VpnRouting.samples(WifiLink(1, "nope", 24)).isEmpty())
        assertTrue("nothing to judge by: covered", VpnRouting.coversSubnet(emptyList(), WifiLink(1, "nope", 24)))
    }
}

/** TL1c MINOR-3: the pre-34 resolve slot is held at most its cap, so one silent announcement cannot stall a window. */
class ResolveSlotTest {
    private val timers = CopyOnWriteArrayList<Pair<Long, () -> Unit>>()
    private val slot = ResolveSlot(holdMillis = 200) { delay, action -> timers += delay to action }

    @Test
    fun anAnswerFreesTheSlotAtOnce() = runBlocking {
        assertEquals("a", slot.resolve<String> { answer -> answer("a") })
        assertEquals("b", withTimeout(100) { slot.resolve<String> { answer -> answer("b") } })
        assertEquals(listOf(200L, 200L), timers.map { it.first })
    }

    @Test
    fun aResolveThatNeverAnswersFreesTheSlotAfterTheCap() = runBlocking {
        val started = System.nanoTime()
        assertNull(slot.resolve<String> { /* Android never calls back */ })
        val waited = (System.nanoTime() - started) / 1_000_000
        assertTrue("waited $waited ms", waited in 150..1_000)
        assertEquals("next", withTimeout(100) { slot.resolve<String> { answer -> answer("next") } })
    }

    @Test
    fun aCallerThatGivesUpEarlyHoldsTheSlotOnlyUntilTheTimer() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val first = scope.async { slot.resolve<String> { } }
        withTimeout(5_000) { while (timers.isEmpty()) delay(5) }
        first.cancelAndJoin() // the window ended; Android may still be resolving
        val next = scope.async { slot.resolve<String> { answer -> answer("next") } }
        delay(50)
        assertFalse("still held: the old resolve may be active", next.isCompleted)
        timers.first().second() // the cap's timer fires
        assertEquals("next", withTimeout(1_000) { next.await() })
        scope.cancel()
    }

    @Test
    fun aRefusedStartFreesTheSlot() = runBlocking {
        assertNull(slot.resolve<String> { throw IllegalArgumentException("listener already in use") })
        assertEquals("ok", withTimeout(100) { slot.resolve<String> { answer -> answer("ok") } })
    }
}

/** The finder against fakes: NSD services, multicast lock, pinned check and TCP probe, all on the Wi-Fi route. */
class PcFinderTest {
    private val stored = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val pc = pcWith(stored, ServerEndpoint("100.74.61.96", 8760, Network.Tailscale))
    private val good = txt("v" to "1", "fp" to FP)
    private val home = WifiLink(1, "192.168.1.37", 24)
    private val route = routeOn(home)

    private class FakeLock : MulticastGuard {
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        override fun acquire() {
            acquired.incrementAndGet()
        }
        override fun release() {
            released.incrementAndGet()
        }
        val held get() = acquired.get() - released.get()
    }

    private val lock = FakeLock()
    private val used = CopyOnWriteArrayList<ServerEndpoint>()
    private val checked = CopyOnWriteArrayList<ServerEndpoint>()
    private val probed = AtomicInteger()
    private val probedHosts = CopyOnWriteArrayList<String>()
    private val routesSeen = CopyOnWriteArrayList<WifiRoute>()
    private val logs = CopyOnWriteArrayList<String>()

    private fun finder(
        services: () -> Flow<FoundService> = { emptyFlow() },
        pcAt: Set<String> = emptySet(),
        open: Set<String> = emptySet(),
        health: PinnedHealth? = null,
    ) = PcFinder(
        browser = ServiceBrowser(services),
        multicast = lock,
        health = health ?: PinnedHealth { _, endpoint, via -> routesSeen += via; checked += endpoint; endpoint.host in pcAt },
        ports = PortProbe { endpoint, timeout, via ->
            assertEquals(300, timeout)
            routesSeen += via
            probed.incrementAndGet()
            probedHosts += endpoint.host
            endpoint.host in open
        },
        onFound = { used += it },
        log = { logs += it },
        timings = FinderTimings(autoWindowMillis = 300, manualWindowMillis = 200),
        events = { recorded += it },
    )

    /** What the finder told the connection history (Q73). */
    private val recorded = CopyOnWriteArrayList<ConnectionEvent>()

    private fun bound(vararg links: WifiLink): WifiRoutes {
        val queue = ArrayDeque(links.map { routeOn(it) })
        return WifiRoutes { WifiAccess.Bound(if (queue.size > 1) queue.removeFirst() else queue.first()) }
    }

    @Test
    fun aVerifiedAnnouncementIsHandedToTheLinkNotStored() = runBlocking {
        val found = finder({ flowOf(FoundService(listOf("192.168.1.80"), 8760, good)) }, pcAt = setOf("192.168.1.80")).discover(pc, route)
        val expected = ServerEndpoint("192.168.1.80", 8760, Network.Lan)
        assertEquals(expected, found)
        assertEquals(listOf(expected), used) // a one-shot candidate; the link stores it once a relay connects
        assertEquals(listOf(route), routesSeen) // the check went through the Wi-Fi route
        assertEquals(0, lock.held)
        assertEquals(1, lock.acquired.get())
    }

    @Test
    fun aWrongOrUnpinnedPcIsIgnoredSilently() = runBlocking {
        val services = flowOf(
            FoundService(listOf("192.168.1.81"), 8760, txt("v" to "1", "fp" to "0000000000000000")), // other PC: not even checked
            FoundService(listOf("192.168.1.82"), 8760, good), // claims our fp, fails the pin
            FoundService(listOf("100.74.61.96", "8.8.8.8"), 8760, good), // not a LAN address
        )
        assertNull(finder({ services }).discover(pc, route))
        assertEquals(listOf(ServerEndpoint("192.168.1.82", 8760, Network.Lan)), checked)
        assertTrue(used.isEmpty())
        assertEquals(listOf("lan discovery: discovered=3 verified=0 rejected=3"), logs) // counts only, no address
    }

    // TL1c MINOR-1: a hostile TXT/A set cannot send the phone to addresses off this Wi-Fi.
    @Test
    fun announcedAddressesOffThisWifiAreDroppedBeforeAnyConnection() = runBlocking {
        val offLink = listOf("10.0.0.5", "192.168.2.80", "172.16.0.9")
        val services = flowOf(FoundService(offLink + "192.168.1.80", 8760, good))
        val found = finder({ services }, pcAt = (offLink + "192.168.1.80").toSet()).discover(pc, route)
        assertEquals("192.168.1.80", found?.host)
        assertEquals(listOf(ServerEndpoint("192.168.1.80", 8760, Network.Lan)), checked) // no other address was even tried
        checked.clear()
        assertNull(finder({ flowOf(FoundService(offLink, 8760, good)) }, pcAt = offLink.toSet()).discover(pc, route))
        assertTrue(checked.isEmpty())
    }

    @Test
    fun theFirstVerifiedServiceEndsDiscovery() = runBlocking {
        val emitted = AtomicInteger()
        val services = flow {
            for (host in listOf("192.168.1.90", "192.168.1.91", "192.168.1.92")) {
                emitted.incrementAndGet()
                emit(FoundService(listOf(host), 8760, good))
            }
        }
        val found = finder({ services }, pcAt = setOf("192.168.1.91", "192.168.1.92")).discover(pc, route)
        assertEquals("192.168.1.91", found?.host)
        assertEquals(2, emitted.get())
    }

    @Test
    fun theMulticastLockIsReleasedOnEveryPath() = runBlocking {
        val paths = listOf<suspend () -> Unit>(
            { finder({ flowOf(FoundService(listOf("192.168.1.80"), 8760, good)) }, pcAt = setOf("192.168.1.80")).discover(pc, route) },
            { finder({ emptyFlow() }).discover(pc, route) },
            { finder({ flow { awaitCancellation() } }).discover(pc, route) }, // nothing within the window
            { finder({ flow { throw IOException("discovery failed: 0") } }).discover(pc, route) },
            { finder({ flowOf(FoundService(listOf("192.168.1.80"), 8760, good)) }, health = PinnedHealth { _, _, _ -> throw IllegalStateException("boom") }).discover(pc, route) },
        )
        for ((i, path) in paths.withIndex()) {
            path()
            assertEquals("path $i", 0, lock.held)
            assertEquals("path $i", i + 1, lock.acquired.get())
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val running = scope.async { finder({ flow { awaitCancellation() } }).discover(pc, route, windowMillis = 60_000) }
        withTimeout(5_000) { while (lock.acquired.get() < paths.size + 1) delay(5) }
        running.cancelAndJoin()
        scope.cancel()
        assertEquals("cancelled", 0, lock.held)
    }

    @Test
    fun manualSearchProbesTheSubnetWhenNothingAnnounces() = runBlocking {
        val phases = CopyOnWriteArrayList<FindPhase>()
        val result = finder(open = setOf("192.168.1.10", "192.168.1.70"), pcAt = setOf("192.168.1.70"))
            .findOnWifi(pc, bound(home), phases::add)
        val expected = ServerEndpoint("192.168.1.70", 8760, Network.Lan)
        assertEquals(FindState.Found(expected), result)
        assertEquals(listOf(FindPhase.Announcement, FindPhase.Scan), phases)
        assertEquals(listOf(ServerEndpoint("192.168.1.10", 8760, Network.Lan), expected), checked.sortedBy { it.host }) // only open ports are checked
        assertEquals(listOf(expected), used)
        assertTrue("every probe and check on the Wi-Fi route", routesSeen.all { it.link == home })
        assertTrue(logs.none { "192.168" in it })
    }

    @Test
    fun theScanTakesTheWifiAsItIsWhenTheScanStarts() = runBlocking {
        val moved = WifiLink(2, "10.9.8.7", 24)
        finder(open = setOf("10.9.8.1"), pcAt = setOf("10.9.8.1")).findOnWifi(pc, bound(home, moved))
        assertTrue(routesSeen.isNotEmpty())
        assertTrue(routesSeen.all { it.link == moved })
        assertEquals("10.9.8.1", used.single().host)
    }

    @Test
    fun manualSearchStopsAtTheAnnouncementWhenItAnswers() = runBlocking {
        val phases = CopyOnWriteArrayList<FindPhase>()
        val result = finder({ flowOf(FoundService(listOf("192.168.1.80"), 8760, good)) }, pcAt = setOf("192.168.1.80"), open = setOf("192.168.1.80"))
            .findOnWifi(pc, bound(home), phases::add)
        assertEquals("192.168.1.80", (result as FindState.Found).endpoint.host)
        assertEquals(listOf(FindPhase.Announcement), phases)
        assertEquals(0, probed.get())
    }

    // TL1c MAJOR-1: no Wi-Fi to bind to (none, or a VPN that refuses the bind) means nothing is sent at all.
    @Test
    fun withoutABoundWifiNothingIsLookedFor() = runBlocking {
        val phases = CopyOnWriteArrayList<FindPhase>()
        val nsd = AtomicInteger()
        val result = finder({ flow { nsd.incrementAndGet() } }, open = setOf("192.168.1.70"), pcAt = setOf("192.168.1.70"))
            .findOnWifi(pc, { WifiAccess.Unavailable }, phases::add)
        assertEquals(FindState.CantScan, result)
        assertTrue(phases.isEmpty())
        assertEquals(0, nsd.get())
        assertEquals(0, probed.get())
        assertTrue(checked.isEmpty())
        assertEquals(0, lock.acquired.get())
    }

    @Test
    fun aWifiLostDuringTheAnnouncementPhaseIsNotScanned() = runBlocking {
        var calls = 0
        val routes = WifiRoutes { if (calls++ == 0) WifiAccess.Bound(route) else WifiAccess.Unavailable }
        assertEquals(FindState.CantScan, finder(open = setOf("192.168.1.70"), pcAt = setOf("192.168.1.70")).findOnWifi(pc, routes))
        assertEquals(0, probed.get())
    }

    @Test
    fun noScanOnAWideSubnet() = runBlocking {
        val phases = CopyOnWriteArrayList<FindPhase>()
        val result = finder(open = setOf("10.0.5.6"), pcAt = setOf("10.0.5.6")).findOnWifi(pc, bound(WifiLink(1, "10.0.5.5", 16)), phases::add)
        assertEquals(FindState.NotFound, result)
        assertEquals(listOf(FindPhase.Announcement), phases)
        assertEquals(0, probed.get())
    }

    @Test
    fun theScanProbesOnlyTheProbePorts() = runBlocking {
        val many = pcWith(*(1..8).map { ServerEndpoint("10.0.0.$it", 9000 + it, Network.Lan) }.toTypedArray())
        finder().scan(many, route)
        assertEquals(253, probed.get())
        probed.set(0)
        finder().scan(many.copy(typed = ServerEndpoint("192.168.1.61", 9443, Network.Lan)), route)
        assertEquals(2 * 253, probed.get())
    }

    // TL1e: under Tailscale the route is unbound; no probe or check goes to a host the VPN would take into its tunnel.
    @Test
    fun anUnboundRouteSkipsEveryTargetTheVpnRoutes() = runBlocking {
        val vpnRoutes = listOf(VpnRoute("100.64.0.0", 10), VpnRoute("192.168.1.70", 32), VpnRoute("192.168.1.80", 32))
        val unbound = WifiRoute(home, DirectSocketFactory(), LiteralDns, vpnRoutes)
        val services = flowOf(FoundService(listOf("192.168.1.80"), 8760, good))
        val search = finder({ services }, open = setOf("192.168.1.70", "192.168.1.90"), pcAt = setOf("192.168.1.70", "192.168.1.80", "192.168.1.90"))
        val result = search.findOnWifi(pc, { WifiAccess.Unbound(unbound) })
        assertEquals(FindState.Found(ServerEndpoint("192.168.1.90", 8760, Network.Lan)), result)
        assertTrue("never checked the VPN's hosts", checked.none { it.host == "192.168.1.70" || it.host == "192.168.1.80" })
        assertFalse("never probed either", "192.168.1.70" in probedHosts)
        assertTrue(routesSeen.all { it === unbound })
    }

    // Q73: the automatic find and every manual result go to the connection history; a quiet automatic search does not.
    @Test
    fun findsAndSearchResultsGoToTheHistory() = runBlocking {
        val at = ServerEndpoint("192.168.1.80", 8760, Network.Lan)
        val announced = finder({ flowOf(FoundService(listOf("192.168.1.80"), 8760, good)) }, pcAt = setOf("192.168.1.80"))
        announced.discover(pc, route)
        assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Found(at, auto = true)), recorded.toList())
        recorded.clear()
        finder().discover(pc, route)
        assertTrue("nothing found by itself: no line", recorded.isEmpty())
        announced.findOnWifi(pc, bound(home))
        finder().findOnWifi(pc, { WifiAccess.Unavailable })
        finder().findOnWifi(pc, bound(home))
        val expected = listOf(ConnectionEvent.Found(at, auto = false), ConnectionEvent.CantScan, ConnectionEvent.NotFound)
        assertEquals(expected, recorded.toList())
    }

    @Test
    fun anUnboundRouteFindsTheAnnouncedPc() = runBlocking {
        val unbound = WifiRoute(home, DirectSocketFactory(), LiteralDns, listOf(VpnRoute("100.64.0.0", 10)))
        val result = finder({ flowOf(FoundService(listOf("192.168.1.80"), 8760, good)) }, pcAt = setOf("192.168.1.80"))
            .findOnWifi(pc, { WifiAccess.Unbound(unbound) })
        assertEquals("192.168.1.80", (result as FindState.Found).endpoint.host)
        assertEquals(listOf(unbound), routesSeen)
    }
}

class LanAutoFindTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pc = pcWith(ServerEndpoint("192.168.1.61", 8760, Network.Lan))
    private val home = WifiLink(7, "192.168.1.37", 24)
    private val searches = AtomicInteger()

    @After
    fun stop() = scope.cancel()

    private fun start(
        wifi: MutableStateFlow<WifiLink?>,
        link: MutableStateFlow<LinkState>,
        settleMillis: Long = 200,
        routes: WifiRoutes = WifiRoutes { wifi.value?.let { WifiAccess.Bound(routeOn(it)) } ?: WifiAccess.Unavailable },
    ) {
        val finder = PcFinder(
            browser = { flow { searches.incrementAndGet() } },
            multicast = object : MulticastGuard {
                override fun acquire() = Unit
                override fun release() = Unit
            },
            health = { _, _, _ -> false },
            ports = { _, _, _ -> false },
            onFound = {},
            timings = FinderTimings(autoWindowMillis = 50),
        )
        LanAutoFind(scope, wifi, routes, link, { pc }, finder, settleMillis).start()
    }

    private fun settle() = runBlocking { delay(400) }

    @Test
    fun aJoinWithTheLinkOfflineDiscovers() {
        val link = MutableStateFlow<LinkState>(LinkState.Offline(0, OfflineCause.Unreachable, "", null, 1, 5_000))
        start(MutableStateFlow(home), link)
        settle()
        assertEquals(1, searches.get())
    }

    @Test
    fun noBoundWifiNoDiscovery() {
        val link = MutableStateFlow<LinkState>(LinkState.Offline(0, OfflineCause.Unreachable, "", null, 1, 5_000))
        start(MutableStateFlow(home), link, routes = { WifiAccess.Unavailable })
        settle()
        assertEquals(0, searches.get())
    }

    @Test
    fun anUnboundRouteUnderTailscaleDiscoversToo() {
        val link = MutableStateFlow<LinkState>(LinkState.Online(ServerEndpoint("100.74.61.96", 8760, Network.Tailscale), 1))
        val unbound = WifiRoutes { WifiAccess.Unbound(WifiRoute(home, DirectSocketFactory(), LiteralDns, listOf(VpnRoute("100.64.0.0", 10)))) }
        start(MutableStateFlow(home), link, routes = unbound)
        settle()
        assertEquals(1, searches.get())
    }

    @Test
    fun aLinkWorkingOnThisLanSkipsDiscovery() {
        start(MutableStateFlow(home), MutableStateFlow(LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), 1)))
        settle()
        assertEquals(0, searches.get())
    }

    @Test
    fun theLinkIsGivenItsRoundFirst() {
        val link = MutableStateFlow<LinkState>(LinkState.Connecting)
        start(MutableStateFlow(home), link, settleMillis = 5_000)
        runBlocking { delay(100) }
        link.value = LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), 1)
        settle()
        assertEquals(0, searches.get())
    }

    @Test
    fun onlyAJoinDiscoversAndNeverWhileStopped() {
        val wifi = MutableStateFlow<WifiLink?>(null)
        val link = MutableStateFlow<LinkState>(LinkState.Stopped)
        start(wifi, link)
        wifi.value = home
        settle()
        assertEquals(0, searches.get())
        link.value = LinkState.Online(ServerEndpoint("100.74.61.96", 8760, Network.Tailscale), 1)
        settle()
        assertEquals("no new Wi-Fi, no search", 0, searches.get())
        wifi.value = home.copy(handle = 8, address = "192.168.9.4")
        settle()
        assertEquals(1, searches.get())
    }
}

/** TL1c MINOR-2: a found address is a one-shot candidate; it is stored through the link's own merge once a relay connects. */
class FoundMergeTest {
    private val key = softwareKey()
    private val codec = CredentialCodec(AesGcmTokenCipher { key })
    private val typed = ServerEndpoint("192.168.1.23", 8760, Network.Lan)
    private val tailnet = ServerEndpoint("100.101.102.103", 8760, Network.Tailscale)
    private val found = ServerEndpoint("192.168.1.99", 8760, Network.Lan)

    @Test
    fun theLinkTriesTheFoundAddressFirstStoredOrNot() {
        val order = listOf(typed, tailnet, found)
        assertEquals(listOf(found, typed, tailnet), EndpointChoice.withFound(order, found))
        assertEquals(order, EndpointChoice.withFound(order, null))
        val fresh = ServerEndpoint("192.168.7.7", 8760, Network.Lan)
        assertEquals(listOf(fresh) + order, EndpointChoice.withFound(order, fresh)) // not stored yet: still tried first
    }

    @Test
    fun onceConnectedTheFoundAddressLeadsAndNothingProtectedIsDropped() = runBlocking {
        val others = (30..35).map { ServerEndpoint("10.0.0.$it", 8760, Network.Lan) }
        val creds = pcWith(*(listOf(typed, tailnet) + others).toTypedArray()).copy(lastGood = tailnet, typed = typed, lastGoodByKind = mapOf(Network.Tailscale to tailnet))
        val storage = MemoryStore()
        val store = CredentialStore(storage, codec, io = Dispatchers.Unconfined)
        store.save(creds)
        store.remember(creds.pcId, listOf(found), found, found) // what the link stores after a relay came up on it
        val now = (store.state.value as CredentialState.Paired).credentials
        assertEquals(PcAddresses.MAX_STORED, now.addresses.size)
        assertEquals(found, now.addresses.first())
        assertTrue(typed in now.addresses)
        assertTrue(tailnet in now.addresses)
        assertFalse("the oldest unprotected one goes", others.last() in now.addresses)
        assertEquals(found, now.lastGood)
        assertEquals(typed, now.typed)
        assertEquals(mapOf(Network.Tailscale to tailnet, Network.Lan to found), now.lastGoodByKind)
        assertEquals(AddressSource.FoundOnWifi, now.infoOf(found).source) // Q73
        val reloaded = CredentialStore(storage, codec, io = Dispatchers.Unconfined).also { it.load() }.state.value as CredentialState.Paired
        assertEquals(now.addresses, reloaded.credentials.addresses)
    }
}

/** `GET /healthz` through the pinned client: the check that turns a discovery hint into trust. */
class HealthzTest {
    private val held = lanCertificate()
    private val server = startServer(held)

    @After
    fun stop() = server.close()

    private fun respond(code: Int, body: String) = server.enqueue(mockwebserver3.MockResponse.Builder().code(code).body(body).build())

    private fun client(pin: ByteArray) = ServerClient(testClients().pinnedTo(pin, Network.Lan), localEndpoint(server).baseUrl)

    @Test
    fun thePinnedPcAnswersOk() = runBlocking {
        respond(200, """{"status":"ok","version":"1.0.0","protocol":3}""")
        assertTrue(client(pinOf(held)).healthy())
        val request = server.takeRequest()
        assertEquals("/healthz", request.url.encodedPath)
        assertNull(request.headers["Authorization"]) // no token to a PC that is not proven yet
    }

    @Test
    fun anotherCertificateIsNotThePc() = runBlocking {
        respond(200, """{"status":"ok"}""")
        assertFalse(client(pinOf(lanCertificate())).healthy())
    }

    @Test
    fun anythingButOkIsNotHealthy() = runBlocking {
        respond(404, """{"error":"not found"}""")
        respond(200, """{"status":"starting"}""")
        respond(200, "not json")
        repeat(3) { assertFalse(client(pinOf(held)).healthy()) }
    }

    @Test
    fun theLinkDialerUsesThePcsPinOnTheWifiRoute() = runBlocking {
        respond(200, """{"status":"ok"}""")
        val pc = pcWith(localEndpoint(server)).copy(fingerprint = Fingerprint.format(pinOf(held)))
        val bound = AtomicInteger()
        assertTrue(HttpLinkDialer(testClients()).healthy(pc, localEndpoint(server), routeOn(WifiLink(1, "127.0.0.2", 8), bound)))
        assertEquals("the check's socket was bound to the Wi-Fi", 1, bound.get())
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun aRefusedBindMeansNoCheckLeavesAnotherWay() = runBlocking {
        respond(200, """{"status":"ok"}""")
        val pc = pcWith(localEndpoint(server)).copy(fingerprint = Fingerprint.format(pinOf(held)))
        val refused = WifiRoute(WifiLink(1, "127.0.0.2", 8), DirectSocketFactory(bind = { throw java.net.SocketException("EPERM") }), okhttp3.Dns.SYSTEM)
        assertFalse(HttpLinkDialer(testClients()).healthy(pc, localEndpoint(server), refused))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun theCheckNeverReusesAPooledRelayConnection() = runBlocking {
        repeat(3) { respond(200, """{"status":"ok"}""") }
        val pc = pcWith(localEndpoint(server)).copy(fingerprint = Fingerprint.format(pinOf(held)))
        val dialer = HttpLinkDialer(testClients())
        dialer.addresses(pc, localEndpoint(server)) // the link's own pooled connection, unbound
        val bound = AtomicInteger()
        repeat(2) { assertTrue(dialer.healthy(pc, localEndpoint(server), routeOn(WifiLink(1, "127.0.0.2", 8), bound))) }
        assertEquals("a fresh bound socket for every check", 2, bound.get())
    }
}

private fun pcWith(vararg addresses: ServerEndpoint) = PcCredentials(
    pcId = "dev_0123456789ab",
    deviceId = "dev_0123456789ab",
    addresses = addresses.toList(),
    fingerprint = PIN,
    token = DeviceToken("t"),
)
