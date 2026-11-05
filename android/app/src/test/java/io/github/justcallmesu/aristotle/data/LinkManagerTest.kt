package io.github.justcallmesu.aristotle.data

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The process-wide link against scripted sockets: real coroutines, short timings, no network. */
class LinkManagerTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val tailnet = ServerEndpoint("100.74.61.96", 8760, Network.Tailscale)
    private val pc = PcCredentials(
        pcId = "dev_1",
        deviceId = "dev_1",
        addresses = listOf(lan, tailnet),
        fingerprint = "7E54 D87B 19C0 A4F2 5B3E 0D96 C1A8 72FE 3E09 B6D4 8A15 F0C3 62D9 4E7A BCA7 EAA7",
        token = DeviceToken("t"),
    )
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> uncaught += e })

    @After
    fun stop() = scope.cancel()

    private fun stopLinks() = scope.coroutineContext[Job]!!.children.forEach { it.cancel() }

    /** [onCollect]: runs when the link starts reading the socket (before any frame is handed over). */
    private class FakeSocket(private val onCollect: () -> Unit = {}) : RelayChannel {
        private val incoming = Channel<RelayEvent>(Channel.UNLIMITED)
        val sent = CopyOnWriteArrayList<String>()

        @Volatile
        var closedByPhone = false

        /** Dropped without a close handshake ([RelayChannel.cancel]). */
        @Volatile
        var cancelled = false

        /** Collected and not ended: the socket is open or connecting ([RelaySocket] closes or drops it on cancel). */
        @Volatile
        var active = false

        override fun events(): Flow<RelayEvent> = incoming.receiveAsFlow()
            .onStart {
                active = true
                onCollect()
            }
            .onCompletion {
                active = false
                closedByPhone = true
            }

        override fun cancel() {
            cancelled = true
        }

        override fun send(text: String): Boolean {
            sent += text
            return true
        }

        fun ready() = apply {
            incoming.trySend(RelayEvent.Opened)
            push("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","session_id":"","payload":{}}}""")
        }

        fun push(text: String) = incoming.trySend(RelayEvent.Text(text))

        fun close(code: Int, reason: String = "") = apply {
            incoming.trySend(RelayEvent.Closed(code, reason))
            incoming.close()
        }

        fun fail(httpStatus: Int?, problem: ConnectionProblem = ConnectionProblem.Unreachable) = apply {
            incoming.trySend(RelayEvent.Failed(httpStatus, problem))
            incoming.close()
        }
    }

    /** Answers each `openRelay` with [script] (endpoint, how many were opened before). */
    private class FakeDialer(private val script: (ServerEndpoint, Int) -> FakeSocket) : LinkDialer {
        val opened = CopyOnWriteArrayList<ServerEndpoint>()
        val sockets = CopyOnWriteArrayList<FakeSocket>()

        @Volatile
        var list: List<ServerEndpoint>? = null

        @Volatile
        var networkChanges = 0

        /** How long `GET /v1/addresses` blocks its thread, like a call that ignores cancellation. */
        @Volatile
        var refreshBlocksMillis = 0L

        /** The most sockets still open or connecting when another one was opened (D33: must stay 0). */
        @Volatile
        var mostActiveAtOpen = 0

        override fun openRelay(pc: PcCredentials, endpoint: ServerEndpoint): RelayChannel = synchronized(this) {
            mostActiveAtOpen = maxOf(mostActiveAtOpen, sockets.count { it.active })
            opened += endpoint
            script(endpoint, opened.size - 1).also { sockets += it }
        }

        override suspend fun addresses(pc: PcCredentials, endpoint: ServerEndpoint): List<ServerEndpoint>? {
            if (refreshBlocksMillis > 0) Thread.sleep(refreshBlocksMillis)
            return list
        }

        override fun networkChanged() {
            networkChanges++
        }
    }

    private class FakeNetwork(@Volatile var lan: Boolean, @Volatile var vpn: Boolean = false) : NetworkWatch {
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        /** The Wi-Fi the phone is on, as [NetworkWatch.localNetworks] reports it. */
        @Volatile
        var wifi: Set<Long> = setOf(1)

        override fun prefersLan() = lan
        override fun vpnUp() = vpn
        override fun localNetworks() = wifi
        fun change(preferLan: Boolean) {
            lan = preferLan
            changes.tryEmit(Unit)
        }
    }

    private class Remembered(val addresses: List<ServerEndpoint>, val lastGood: ServerEndpoint)

    private val remembered = CopyOnWriteArrayList<Remembered>()
    private val credentials = MutableStateFlow<CredentialState>(CredentialState.Paired(pc))

    private fun link(
        dialer: FakeDialer,
        network: FakeNetwork = FakeNetwork(lan = true),
        backoff: Backoff = Backoff(baseMillis = 40, capMillis = 200, random = { 1.0 }),
        credentials: StateFlow<CredentialState> = this.credentials,
        memory: LinkMemory = LinkMemory { _, addresses, lastGood, _ -> remembered += Remembered(addresses, lastGood) },
        readyTimeoutMillis: Long = 2_000,
        supersededRetryMillis: Long = 60_000,
        clock: () -> Long = { System.currentTimeMillis() + clockAhead },
    ): LinkManager = LinkManager(
        scope = scope,
        credentials = credentials,
        dialer = dialer,
        network = network,
        memory = memory,
        timings = LinkTimings(readyTimeoutMillis = readyTimeoutMillis, hermesDownRetryMillis = 150, rpcTimeoutMillis = 2_000, backoff = backoff, supersededRetryMillis = supersededRetryMillis),
        clock = clock,
        history = { recorded += it },
    ).also { it.start() }

    /** What the link told the connection history (Q73). */
    private val recorded = CopyOnWriteArrayList<ConnectionEvent>()

    /** How far the link's clock runs ahead of the real one: moves it past the flap window (TL1e). */
    @Volatile
    private var clockAhead = 0L

    private fun LinkManager.await(what: String, predicate: (LinkState) -> Boolean): LinkState = runBlocking {
        try {
            withTimeout(5_000) { state.first(predicate) }
        } catch (e: Exception) {
            fail("$what: still ${state.value}")
            throw e
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        withTimeout(5_000) { while (!condition()) delay(5) }
        assertTrue(what, condition())
    }

    @Test
    fun onWifiLanGoesFirstAndTailscaleIsTheFallback() {
        val dialer = FakeDialer { endpoint, _ -> if (endpoint == lan) FakeSocket().fail(null) else FakeSocket().ready() }
        dialer.list = listOf(lan, tailnet, ServerEndpoint("10.0.0.2", 8760, Network.Lan))
        val online = link(dialer).await("online") { it is LinkState.Online } as LinkState.Online
        assertEquals(tailnet, online.endpoint)
        assertEquals(listOf(lan, tailnet), dialer.opened)
        waitFor("remembered") { remembered.isNotEmpty() }
        assertEquals(tailnet, remembered.single().lastGood)
        assertEquals(dialer.list, remembered.single().addresses)
    }

    @Test
    fun offWifiTailscaleGoesFirst() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val online = link(dialer, FakeNetwork(lan = false)).await("online") { it is LinkState.Online } as LinkState.Online
        assertEquals(tailnet, online.endpoint)
        assertEquals(listOf(tailnet), dialer.opened)
    }

    @Test
    fun theLastGoodAddressGoesFirstWithinItsKind() {
        val eth = ServerEndpoint("10.0.0.2", 8760, Network.Lan)
        credentials.value = CredentialState.Paired(pc.copy(addresses = listOf(lan, eth, tailnet), lastGood = eth))
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        link(dialer).await("online") { it is LinkState.Online }
        assertEquals(listOf(eth), dialer.opened)
    }

    @Test
    fun revokedStopsWithoutRetrying() {
        for (socket in listOf({ FakeSocket().close(4401, "revoked") }, { FakeSocket().fail(401) })) {
            val dialer = FakeDialer { _, _ -> socket() }
            val manager = link(dialer)
            manager.await("revoked") { it == LinkState.Revoked }
            runBlocking { delay(300) }
            assertEquals(1, dialer.opened.size)
            assertEquals(LinkState.Revoked, manager.state.value)
            stopLinks()
        }
    }

    @Test
    fun aRevokedCloseWhileOnlineStopsToo() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val manager = link(dialer)
        manager.await("online") { it is LinkState.Online }
        dialer.sockets.single().close(4401, "revoked")
        manager.await("revoked") { it == LinkState.Revoked }
        runBlocking { delay(300) }
        assertEquals(1, dialer.opened.size)
    }

    @Test
    fun hermesUpdatedWaitsForTheUserOnly() {
        val dialer = FakeDialer { _, _ -> FakeSocket().close(4505, "hermes_protocol_unsupported") }
        val network = FakeNetwork(lan = true)
        val manager = link(dialer, network)
        val updated = manager.await("updated") { it is LinkState.HermesUpdated } as LinkState.HermesUpdated
        assertEquals("close 4505 hermes_protocol_unsupported", updated.details)
        assertEquals("the server answered here (stocks still use it)", lan, updated.serverEndpoint)
        network.change(preferLan = true)
        manager.onForeground()
        runBlocking { delay(300) }
        assertEquals(1, dialer.opened.size)
        manager.retryNow()
        waitFor("checked again") { dialer.opened.size == 2 }
    }

    // D36: 4409 = this device's newer socket on the PC's other listener replaced this one. Reconnecting on a timer
    // would supersede that one in turn (flip-flop); only a network change, the foreground or the user starts a round.
    @Test
    fun aSupersededSocketIsNotReconnectedByItself() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val network = FakeNetwork(lan = true)
        val manager = link(dialer, network)
        val first = manager.await("online") { it is LinkState.Online } as LinkState.Online
        dialer.sockets.single().close(4409, "superseded by a newer connection")
        val offline = manager.await("superseded") { it is LinkState.Offline } as LinkState.Offline
        assertEquals(OfflineCause.Superseded, offline.cause)
        assertEquals("close 4409 superseded by a newer connection", offline.details)
        assertTrue(offline.waiting)
        assertFalse(offline.trying)
        // D39: the slow backstop's time, so the banner can count down to it.
        assertEquals(offline.sinceMillis + 60_000, offline.nextAttemptAtMillis)
        runBlocking { delay(500) } // well past the 40 ms backoff a lost connection would take
        assertEquals(1, dialer.opened.size)
        assertEquals(offline, manager.state.value)

        network.change(preferLan = true)
        val again = manager.await("back after a network change") { it is LinkState.Online && it.generation != first.generation } as LinkState.Online
        assertEquals(2, dialer.opened.size)
        // A second supersede waits again; Retry now (or the foreground) is what brings it back.
        dialer.sockets.last().close(4409, "superseded by a newer connection")
        manager.await("superseded again") { it is LinkState.Offline && it.waiting }
        runBlocking { delay(300) }
        assertEquals(2, dialer.opened.size)
        manager.retryNow()
        manager.await("back after Retry now") { it is LinkState.Online && it.generation != again.generation }
        assertEquals(3, dialer.opened.size)
    }

    // T13b: a backgrounded phone on a stable network gets no wake; a slow backstop round brings the link back.
    @Test
    fun aSupersededSocketIsRetriedByTheSlowBackstop() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val manager = link(dialer, supersededRetryMillis = 400)
        val first = manager.await("online") { it is LinkState.Online } as LinkState.Online
        dialer.sockets.single().close(4409, "superseded by a newer connection")
        val waiting = manager.await("superseded") { it is LinkState.Offline && it.waiting } as LinkState.Offline
        assertEquals(waiting.sinceMillis + 400, waiting.nextAttemptAtMillis)
        runBlocking { delay(150) }
        assertEquals(1, dialer.opened.size)
        manager.await("back by itself") { it is LinkState.Online && it.generation != first.generation }
        assertEquals(2, dialer.opened.size)
    }

    @Test
    fun hermesDownRetriesQuietlyEveryFewSeconds() {
        val dialer = FakeDialer { _, n -> if (n < 2) FakeSocket().close(4503, "hermes_not_running") else FakeSocket().ready() }
        val manager = link(dialer)
        val seen = CopyOnWriteArrayList<LinkState>()
        scope.launch { manager.state.collect { seen += it } }
        manager.await("down") { it is LinkState.HermesDown }
        manager.await("online") { it is LinkState.Online }
        assertEquals(3, dialer.opened.size)
        val between = seen.dropWhile { it !is LinkState.HermesDown }.takeWhile { it !is LinkState.Online }
        assertTrue("quiet retry showed $between", between.all { it is LinkState.HermesDown })
    }

    @Test
    fun lostConnectionsBackOffThenReconnect() {
        for (code in listOf(1000, 1001, 1011, 1013, 4502, 4429)) {
            val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
            val manager = link(dialer)
            val first = manager.await("$code online") { it is LinkState.Online } as LinkState.Online
            dialer.sockets.last().close(code, "x")
            val offline = manager.await("$code offline") { it is LinkState.Offline } as LinkState.Offline
            assertEquals(OfflineCause.ConnectionLost, offline.cause)
            assertEquals("close $code x", offline.details)
            assertEquals(1, offline.attempt)
            val again = manager.await("$code back") { it is LinkState.Online && it.generation != first.generation }
            assertTrue(again is LinkState.Online)
            stopLinks()
        }
    }

    @Test
    fun failuresCountUpAndAConnectionResetsThem() {
        credentials.value = CredentialState.Paired(pc.copy(addresses = listOf(lan)))
        val dialer = FakeDialer { _, n -> if (n in 1..4) FakeSocket().fail(null) else FakeSocket().ready() }
        val manager = link(dialer)
        val first = manager.await("online") { it is LinkState.Online } as LinkState.Online
        val attempts = CopyOnWriteArrayList<Int>()
        scope.launch { manager.state.collect { if (it is LinkState.Offline && !it.trying) attempts += it.attempt } }
        dialer.sockets.last().close(1001, "going_away")
        manager.await("offline") { it is LinkState.Offline }
        // TL1e: once that drop is out of the flap window, the relay coming up on the address again resets the count.
        clockAhead = 6 * 60_000L
        manager.await("back") { it is LinkState.Online && it.generation != first.generation }
        assertEquals(listOf(1, 2, 3, 4, 5), attempts.distinct())
        dialer.sockets.last().close(1001, "going_away")
        val offline = manager.await("offline again") { it is LinkState.Offline } as LinkState.Offline
        assertEquals(1, offline.attempt)
    }

    @Test
    fun aNetworkChangeRetriesAtOnceInsteadOfWaiting() {
        val network = FakeNetwork(lan = true)
        val slow = Backoff(baseMillis = 60_000, capMillis = 60_000, random = { 1.0 })
        val dialer = FakeDialer { _, n -> if (n < 2) FakeSocket().fail(null) else FakeSocket().ready() }
        val manager = link(dialer, network, slow)
        val offline = manager.await("offline") { it is LinkState.Offline && !it.trying } as LinkState.Offline
        assertEquals(OfflineCause.Unreachable, offline.cause)
        network.change(preferLan = false)
        val online = manager.await("online at once") { it is LinkState.Online } as LinkState.Online
        assertEquals(tailnet, online.endpoint)
        assertEquals(1, dialer.networkChanges)
    }

    @Test
    fun theForegroundRetriesAtOnceToo() {
        val slow = Backoff(baseMillis = 60_000, capMillis = 60_000, random = { 1.0 })
        val dialer = FakeDialer { _, n -> if (n < 2) FakeSocket().fail(null) else FakeSocket().ready() }
        val manager = link(dialer, backoff = slow)
        manager.await("offline") { it is LinkState.Offline && !it.trying }
        manager.onForeground()
        manager.await("online") { it is LinkState.Online }
    }

    @Test
    fun aNetworkChangeWhileOnlineSwitchesToTheBetterAddress() {
        val network = FakeNetwork(lan = true)
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val manager = link(dialer, network)
        val onLan = manager.await("on lan") { it is LinkState.Online } as LinkState.Online
        assertEquals(lan, onLan.endpoint)
        network.change(preferLan = false) // Wi-Fi gone, cellular with Tailscale
        val onTailnet = manager.await("on tailnet") { it is LinkState.Online && it.endpoint == tailnet }
        assertTrue(onTailnet is LinkState.Online)
        waitFor("old socket closed") { dialer.sockets.first().closedByPhone }
        // D33: the open socket is closed (1000, by RelaySocket when its collector goes), not dropped silently for
        // the server to reap after its ping timeout; and that happens before the next socket opens.
        assertFalse("the old socket is closed with 1000, not cancelled", dialer.sockets.first().cancelled)
        assertEquals(0, dialer.mostActiveAtOpen)
    }

    // D33: LAN <-> Tailscale back and forth: every switch closes the socket it leaves before it opens the next.
    @Test
    fun repeatedSwitchesKeepOneSocketAtATime() {
        val network = FakeNetwork(lan = true)
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val manager = link(dialer, network)
        manager.await("on lan") { it is LinkState.Online && it.endpoint == lan }
        for (preferLan in listOf(false, true, false, true)) {
            val expected = if (preferLan) lan else tailnet
            val before = dialer.sockets.size
            network.change(preferLan)
            manager.await("on $expected") { it is LinkState.Online && it.endpoint == expected && dialer.sockets.size > before }
        }
        waitFor("only the live socket is open") { dialer.sockets.count { it.active } == 1 }
        assertEquals(0, dialer.mostActiveAtOpen)
        assertEquals(listOf(lan, tailnet, lan, tailnet, lan), dialer.opened)
        assertTrue("an open socket is closed, never cancelled", dialer.sockets.none { it.cancelled })
        assertTrue(dialer.sockets.dropLast(1).all { it.closedByPhone })
    }

    // D33: a network change while a socket is still connecting drops it and starts over at once, instead of
    // waiting out the ready timeout and then switching away from a socket that has just opened.
    @Test
    fun aNetworkChangeWhileConnectingDropsThatSocketAndStartsOver() {
        val network = FakeNetwork(lan = true)
        val dialer = FakeDialer { endpoint, _ -> if (endpoint == lan) FakeSocket() else FakeSocket().ready() } // LAN never answers
        val manager = link(dialer, network, readyTimeoutMillis = 30_000)
        waitFor("connecting on lan") { dialer.sockets.singleOrNull()?.active == true }
        network.change(preferLan = false)
        manager.await("on tailnet") { it is LinkState.Online && it.endpoint == tailnet }
        assertTrue(dialer.sockets.first().closedByPhone)
        assertEquals(0, dialer.mostActiveAtOpen)
        assertEquals(listOf(lan, tailnet), dialer.opened)
    }

    // D29: a network change raised while the connect was in flight but taken after `gateway.ready` won the race is
    // about the network this relay came up on: it is cleared, so it does not end the relay at once (one wasted
    // reconnect after every Wi-Fi move). Unconfined makes the order exact: the change is raised as the link starts
    // reading the socket, and its wake lands after the start wait chose `gateway.ready`, before the relay is online.
    @Test
    fun aNetworkChangeRaisedDuringTheConnectDoesNotEndTheRelayThatCameUp() {
        val network = FakeNetwork(lan = true)
        val exact = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> uncaught += e })
        val dialer = FakeDialer { _, n -> FakeSocket(onCollect = { if (n == 0) network.changes.tryEmit(Unit) }).ready() }
        try {
            val manager = LinkManager(
                scope = exact,
                credentials = credentials,
                dialer = dialer,
                network = network,
                memory = { _, _, _, _ -> },
                timings = LinkTimings(readyTimeoutMillis = 2_000, rpcTimeoutMillis = 2_000, backoff = Backoff(baseMillis = 40, capMillis = 200, random = { 1.0 })),
            ).also { it.start() }
            val online = manager.await("online") { it is LinkState.Online } as LinkState.Online
            waitFor("the change was seen") { dialer.networkChanges == 1 }
            runBlocking { delay(200) }
            assertEquals(online, manager.state.value)
            assertEquals(listOf(lan), dialer.opened)
        } finally {
            exact.cancel()
        }
        assertTrue(uncaught.isEmpty())
    }

    // T09b: a socket that ends the usual way (the server closed it) is not cancelled on top.
    @Test
    fun aRelayTheServerClosedIsNotCancelled() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val manager = link(dialer)
        manager.await("online") { it is LinkState.Online }
        dialer.sockets.single().close(1001, "going_away")
        manager.await("offline") { it is LinkState.Offline }
        assertFalse(dialer.sockets.single().cancelled)
    }

    // T09 review 1: the office/home scenario. At the office the phone reaches the laptop over Tailscale and the
    // PC serves the office LAN and the tailnet; the home address the user typed must stay, so back home on
    // Wi-Fi with Tailscale off the phone still finds the laptop.
    @Test
    fun anAddressThePcDoesNotServeRightNowIsKeptForLater() {
        val home = lan
        val office = ServerEndpoint("10.20.0.15", 8760, Network.Lan)
        val key = softwareKey()
        val store = CredentialStore(MemoryStore(), CredentialCodec(AesGcmTokenCipher { key }), Dispatchers.Unconfined)
        runBlocking { store.save(pc.copy(addresses = listOf(home, tailnet), typed = home, lastGood = home)) }

        val atHome = AtomicBoolean(false)
        val dialer = FakeDialer { endpoint, _ ->
            val reachable = if (atHome.get()) endpoint == home else endpoint == tailnet
            if (reachable) FakeSocket().ready() else FakeSocket().fail(null)
        }
        dialer.list = listOf(office, tailnet)
        val network = FakeNetwork(lan = false, vpn = true) // at the office: cellular, Tailscale on
        val manager = link(dialer, network, credentials = store.state, memory = store)
        manager.await("online at the office") { it is LinkState.Online && it.endpoint == tailnet }
        fun stored() = (store.state.value as CredentialState.Paired).credentials
        waitFor("office list merged") { office in stored().addresses }
        assertEquals(listOf(office, tailnet, home), stored().addresses)

        atHome.set(true) // home Wi-Fi, Tailscale off; the laptop is on the home LAN again
        dialer.list = listOf(home, tailnet)
        network.vpn = false
        network.change(preferLan = true)
        manager.await("online at home") { it is LinkState.Online && it.endpoint == home }
        waitFor("home list merged") { stored().addresses.first() == home }
        assertEquals(listOf(home, tailnet, office), stored().addresses)
    }

    // T09 review 2: `GET /v1/addresses` never holds the link Online after the relay ended.
    @Test
    fun aSlowAddressRefreshDoesNotDelayTheLoss() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        dialer.refreshBlocksMillis = 4_000
        val manager = link(dialer)
        manager.await("online") { it is LinkState.Online }
        val closedAt = System.nanoTime()
        dialer.sockets.single().close(1001, "going_away")
        manager.await("offline") { it is LinkState.Offline }
        val millis = (System.nanoTime() - closedAt) / 1_000_000
        assertTrue("offline after $millis ms", millis < 1_000)
        val error = runBlocking { runCatching { manager.call("session.list") }.exceptionOrNull() }
        assertTrue(error is RpcException && error.linkClosed)
    }

    // T09 review 4: a store that throws neither crashes the app nor stops the link; the relay stays up.
    @Test
    fun aFailingStoreNeitherCrashesNorEndsTheLink() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        dialer.list = listOf(lan, tailnet, ServerEndpoint("10.0.0.2", 8760, Network.Lan))
        val manager = link(dialer, memory = { _, _, _, _ -> throw IllegalStateException("disk full") })
        val first = manager.await("online") { it is LinkState.Online } as LinkState.Online
        runBlocking { delay(200) }
        assertEquals(first, manager.state.value)
        dialer.sockets.single().close(1001, "going_away")
        manager.await("back") { it is LinkState.Online && it.generation != first.generation }
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    // T09 review 4: an unexpected error in a round shows as offline with its reason and the next round runs.
    @Test
    fun anUnexpectedErrorGoesOfflineAndTheNextRoundRuns() {
        credentials.value = CredentialState.Paired(pc.copy(addresses = listOf(lan)))
        val dialer = FakeDialer { _, n -> if (n == 0) throw IllegalStateException("bug") else FakeSocket().ready() }
        val manager = link(dialer)
        val offline = manager.await("offline") { it is LinkState.Offline } as LinkState.Offline
        assertEquals(OfflineCause.AppError, offline.cause)
        assertEquals("unexpected IllegalStateException", offline.details)
        manager.await("online") { it is LinkState.Online }
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    // T09 review 11: once the PC answered (4503 here), a later loss starts the backoff from the first step.
    @Test
    fun hermesDownInBetweenResetsTheFailureCount() {
        credentials.value = CredentialState.Paired(pc.copy(addresses = listOf(lan)))
        val dialer = FakeDialer { _, n ->
            when (n) {
                0 -> FakeSocket().ready()
                2 -> FakeSocket().close(4503, "hermes_not_running")
                1, 3 -> FakeSocket().fail(null)
                else -> FakeSocket().ready()
            }
        }
        val manager = link(dialer)
        manager.await("online") { it is LinkState.Online }
        dialer.sockets.single().close(1001, "going_away")
        manager.await("second failure") { it is LinkState.Offline && it.attempt == 2 }
        manager.await("down") { it is LinkState.HermesDown }
        val offline = manager.await("offline again") { it is LinkState.Offline } as LinkState.Offline
        assertEquals(1, offline.attempt)
    }

    @Test
    fun onlyTailnetUnreachableOffWifiSaysTailscaleMayBeOff() {
        val dialer = FakeDialer { _, _ -> FakeSocket().fail(null) }
        val offline = link(dialer, FakeNetwork(lan = false)).await("offline") { it is LinkState.Offline } as LinkState.Offline
        assertEquals(OfflineCause.TailscaleMayBeOff, offline.cause)
    }

    // T09 review 7: with a VPN up, Tailscale is not the likely cause; the laptop itself does not answer.
    @Test
    fun withAVpnUpTheTailnetMissIsJustUnreachable() {
        val dialer = FakeDialer { _, _ -> FakeSocket().fail(null) }
        val network = FakeNetwork(lan = false, vpn = true)
        val offline = link(dialer, network).await("offline") { it is LinkState.Offline } as LinkState.Offline
        assertEquals(OfflineCause.Unreachable, offline.cause)
    }

    @Test
    fun callsUseTheLiveRelayAndFailOnceItIsGone() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val manager = link(dialer)
        manager.await("online") { it is LinkState.Online }
        val socket = dialer.sockets.single()
        val result = runBlocking {
            val answer = async(Dispatchers.Default) { manager.call("session.list") }
            waitFor("sent") { socket.sent.isNotEmpty() }
            val id = HermesJson.parseToJsonElement(socket.sent.single()).jsonObject["id"]!!.jsonPrimitive.content
            socket.push("""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""")
            answer.await()
        }
        assertEquals(JsonPrimitive(true), result.jsonObject["ok"])
        socket.close(1001, "going_away")
        manager.await("offline") { it is LinkState.Offline }
        val error = runBlocking { runCatching { manager.call("session.list") }.exceptionOrNull() }
        assertTrue(error is RpcException && error.linkClosed)
    }

    @Test
    fun forgettingThePcClosesTheLink() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val manager = link(dialer)
        manager.await("online") { it is LinkState.Online }
        credentials.value = CredentialState.Unpaired
        manager.await("idle") { it == LinkState.Idle }
        waitFor("socket closed") { dialer.sockets.single().closedByPhone }
        assertFalse(dialer.sockets.size > 1)
    }

    @Test
    fun stopClosesTheLinkUntilTheAppIsInFrontAgain() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val network = FakeNetwork(lan = true)
        val manager = link(dialer, network)
        manager.await("online") { it is LinkState.Online }
        manager.stop()
        manager.await("stopped") { it == LinkState.Stopped }
        waitFor("socket closed") { dialer.sockets.single().closedByPhone }
        network.change(preferLan = true)
        manager.retryNow()
        runBlocking { delay(300) }
        assertEquals(1, dialer.opened.size)
        assertEquals(LinkState.Stopped, manager.state.value)
        manager.onForeground()
        manager.await("online again") { it is LinkState.Online }
        assertEquals(2, dialer.opened.size)
    }

    @Test
    fun anAddressUpdateDoesNotRestartALiveLink() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val manager = link(dialer)
        manager.await("online") { it is LinkState.Online }
        credentials.value = CredentialState.Paired(pc.copy(lastGood = lan))
        runBlocking { delay(200) }
        assertEquals(1, dialer.opened.size)
        assertTrue(manager.state.value is LinkState.Online)
    }

    // TL1 (Q65) + TL1c MINOR-2: a found address is a one-shot candidate tried beside a working Tailscale relay; it takes
    // over only once ready, and is stored only then.
    private val found = ServerEndpoint("192.168.5.20", 8760, Network.Lan)

    @Test
    fun aFoundLanAddressTakesOverFromTailscaleOnceItIsReady() {
        val dialer = FakeDialer { endpoint, _ -> if (endpoint == lan) FakeSocket().fail(null) else FakeSocket().ready() }
        val manager = link(dialer)
        val before = manager.await("online over Tailscale") { it is LinkState.Online && it.endpoint == tailnet } as LinkState.Online
        assertTrue("nothing stored yet", remembered.none { found in it.addresses })
        manager.useFound(found)
        val after = manager.await("online on the found address") { it is LinkState.Online && it.endpoint == found } as LinkState.Online
        assertTrue(after.generation > before.generation)
        assertEquals(listOf(lan, tailnet, found), dialer.opened)
        waitFor("the Tailscale relay closed") { dialer.sockets[1].closedByPhone }
        waitFor("stored once connected") { remembered.any { it.lastGood == found } }
        assertEquals(found, remembered.last { it.lastGood == found }.addresses.first())
    }

    @Test
    fun aFoundAddressThatFailsLeavesTheTailscaleLinkAlone() {
        val dialer = FakeDialer { endpoint, _ ->
            when (endpoint) {
                lan -> FakeSocket().fail(null)
                found -> FakeSocket().fail(null, ConnectionProblem.CertificateChanged) // a relay that serves another cert
                else -> FakeSocket().ready()
            }
        }
        val manager = link(dialer)
        val before = manager.await("online over Tailscale") { it is LinkState.Online && it.endpoint == tailnet }
        manager.useFound(found)
        waitFor("the candidate was tried") { dialer.opened.size == 3 }
        waitFor("and closed") { !dialer.sockets[2].active }
        runBlocking { delay(200) }
        assertEquals(before, manager.state.value) // same relay, same generation
        assertTrue(dialer.sockets[1].active)
        assertTrue("never stored", remembered.none { it.lastGood == found || found in it.addresses })
    }

    @Test
    fun theServerSupersedingTailscaleForACandidateThatThenFailsStartsANewRoundNotAStandDown() {
        lateinit var dialer: FakeDialer
        dialer = FakeDialer { endpoint, _ ->
            when (endpoint) {
                lan -> FakeSocket().fail(null)
                // Admitted on the LAN listener: the server closes the Tailscale relay (4409), then the candidate fails.
                found -> FakeSocket(onCollect = { dialer.sockets[1].close(4409, "superseded by a newer connection") })
                else -> FakeSocket().ready()
            }
        }
        val manager = link(dialer)
        val before = manager.await("online over Tailscale") { it is LinkState.Online && it.endpoint == tailnet } as LinkState.Online
        manager.useFound(found)
        waitFor("the Tailscale relay was superseded") { dialer.opened.size == 3 && !dialer.sockets[1].active }
        assertEquals("waits for the candidate", before, manager.state.value)
        dialer.sockets[2].fail(null)
        val again = manager.await("back on Tailscale") { it is LinkState.Online && it.generation > before.generation } as LinkState.Online
        assertEquals(tailnet, again.endpoint)
        assertEquals(listOf(lan, tailnet, found, lan, tailnet), dialer.opened)
    }

    @Test
    fun aFoundAddressIsTriedFirstByTheNextRoundStoredOrNot() {
        val dialer = FakeDialer { endpoint, _ -> if (endpoint == found) FakeSocket().ready() else FakeSocket().fail(null) }
        val manager = link(dialer)
        manager.await("offline") { it is LinkState.Offline }
        manager.useFound(found)
        manager.await("online on the found address") { it is LinkState.Online && it.endpoint == found }
        waitFor("stored once connected") { remembered.any { it.lastGood == found && it.addresses.first() == found } }
    }

    @Test
    fun aFoundAddressLeavesALanLinkAlone() {
        credentials.value = CredentialState.Paired(pc.copy(addresses = listOf(lan, found, tailnet)))
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val manager = link(dialer)
        manager.await("online") { it is LinkState.Online && it.endpoint == lan }
        manager.useFound(found)
        runBlocking { delay(300) }
        assertEquals(listOf(lan), dialer.opened)
    }

    // TL1e (MINOR-A.1): the candidate fails before the server's 4409 for the Tailscale relay arrives: still a new round.
    @Test
    fun aCandidateThatFailsBeforeTheSupersedeArrivesStillStartsANewRound() {
        val dialer = FakeDialer { endpoint, _ ->
            when (endpoint) {
                lan -> FakeSocket().fail(null)
                found -> FakeSocket().fail(null) // a forwarder that resets right after the upgrade
                else -> FakeSocket().ready()
            }
        }
        val manager = link(dialer)
        val before = manager.await("online over Tailscale") { it is LinkState.Online && it.endpoint == tailnet } as LinkState.Online
        manager.useFound(found)
        waitFor("the candidate was tried and failed") { dialer.opened.size == 3 && !dialer.sockets[2].active }
        runBlocking { delay(200) }
        assertEquals(before, manager.state.value)
        dialer.sockets[1].close(4409, "superseded by a newer connection") // the server's close arrives late
        val again = manager.await("back on Tailscale") { it is LinkState.Online && it.generation > before.generation } as LinkState.Online
        assertEquals(tailnet, again.endpoint)
        assertEquals(listOf(lan, tailnet, found, lan, tailnet), dialer.opened)
    }

    // TL1e (NIT): a network change while the candidate is still pending after the 4409 starts the next round at once.
    @Test
    fun aNetworkChangeWhileTheCandidateIsPendingDoesNotWaitForIt() {
        lateinit var dialer: FakeDialer
        dialer = FakeDialer { endpoint, _ ->
            when (endpoint) {
                lan -> FakeSocket().fail(null)
                found -> FakeSocket(onCollect = { dialer.sockets[1].close(4409, "superseded by a newer connection") }) // never ready
                else -> FakeSocket().ready()
            }
        }
        val network = FakeNetwork(lan = true)
        val manager = link(dialer, network, readyTimeoutMillis = 30_000)
        val before = manager.await("online over Tailscale") { it is LinkState.Online && it.endpoint == tailnet } as LinkState.Online
        manager.useFound(found)
        waitFor("the Tailscale relay was superseded") { dialer.opened.size == 3 && !dialer.sockets[1].active }
        val started = System.nanoTime()
        network.change(preferLan = true)
        manager.await("a new round") { it is LinkState.Online && it.generation > before.generation }
        assertTrue("not after the 30 s ready timeout", (System.nanoTime() - started) / 1_000_000 < 3_000)
        waitFor("the pending candidate was dropped") { !dialer.sockets[2].active }
    }

    // TL1e (MINOR-A.2): a LAN address that keeps dropping after gateway.ready goes below Tailscale on that Wi-Fi.
    @Test
    fun aLanAddressThatKeepsDroppingAfterReadyIsDemotedBelowTailscale() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val network = FakeNetwork(lan = true)
        val manager = link(dialer, network)
        val attempts = CopyOnWriteArrayList<Int>()
        scope.launch { manager.state.collect { if (it is LinkState.Offline && !it.trying) attempts += it.attempt } }
        var generation = 0L
        repeat(3) { i ->
            val online = manager.await("online on the LAN ${i + 1}") { it is LinkState.Online && it.generation > generation } as LinkState.Online
            assertEquals(lan, online.endpoint)
            generation = online.generation
            dialer.sockets.last().close(1001, "going_away")
        }
        val demoted = manager.await("online over Tailscale") { it is LinkState.Online && it.generation > generation } as LinkState.Online
        assertEquals(tailnet, demoted.endpoint)
        assertEquals(listOf(lan, lan, lan, tailnet), dialer.opened)
        assertEquals("the backoff grew instead of resetting on each ready", listOf(1, 2, 3), attempts.distinct())
        assertEquals("one history line for the demotion (Q73)", 1, recorded.count { it == ConnectionEvent.Demoted(lan) })

        network.wifi = setOf(2) // another Wi-Fi: the LAN address goes first again there
        network.change(preferLan = true)
        val elsewhere = manager.await("online on the LAN elsewhere") { it is LinkState.Online && it.generation > demoted.generation } as LinkState.Online
        assertEquals(lan, elsewhere.endpoint)

        network.wifi = setOf(1)
        network.change(preferLan = true)
        val back = manager.await("still demoted on the first Wi-Fi") { it is LinkState.Online && it.generation > elsewhere.generation } as LinkState.Online
        assertEquals(tailnet, back.endpoint)

        clockAhead = 16 * 60_000L // 15 min later the demotion is over
        network.change(preferLan = true)
        val restored = manager.await("the LAN first again") { it is LinkState.Online && it.generation > back.generation } as LinkState.Online
        assertEquals(lan, restored.endpoint)
    }

    @Test
    fun flapsAreCountedPerAddressAndWifiWithinTheWindow() {
        val flaps = LanFlaps(drops = 3, windowMillis = 1_000, demoteMillis = 5_000)
        val wifi = setOf(1L)
        flaps.dropped(lan, wifi, 0)
        flaps.dropped(lan, wifi, 500)
        flaps.dropped(lan, wifi, 1_200) // the first is out of the window: two in it
        assertFalse(flaps.demoted(lan, wifi, 1_200))
        assertTrue(flaps.unsettled(lan, wifi, 1_200))
        flaps.dropped(lan, wifi, 1_400)
        assertTrue(flaps.demoted(lan, wifi, 1_400))
        assertFalse("another Wi-Fi", flaps.demoted(lan, setOf(2L), 1_400))
        assertEquals(listOf(tailnet, lan), flaps.order(listOf(lan, tailnet), wifi, 1_500))
        assertFalse("over after the demotion", flaps.demoted(lan, wifi, 6_400))
        assertFalse(flaps.unsettled(lan, wifi, 6_400))
        repeat(5) { flaps.dropped(tailnet, wifi, 7_000L + it) }
        assertFalse("only LAN addresses", flaps.demoted(tailnet, wifi, 7_010))
    }

    // Q73: the history gets one line per connect, a switch names both addresses, and an offline stretch is told once.
    @Test
    fun theHistoryTellsConnectsSwitchesAndEachOfflineStretchOnce() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val network = FakeNetwork(lan = true)
        val manager = link(dialer, network)
        val first = manager.await("online on the LAN") { it is LinkState.Online } as LinkState.Online
        network.change(preferLan = false)
        manager.await("over Tailscale") { it is LinkState.Online && it.generation > first.generation }
        waitFor("two lines") { recorded.size == 2 }
        assertEquals(listOf(ConnectionEvent.Connected(lan), ConnectionEvent.Switched(lan, tailnet)), recorded.toList())
        stopLinks()

        recorded.clear()
        val down = FakeDialer { _, _ -> FakeSocket().fail(null) }
        val offline = link(down)
        offline.await("third try") { it is LinkState.Offline && it.attempt >= 3 }
        assertEquals(listOf<ConnectionEvent>(ConnectionEvent.Offline(OfflineReason.PcUnreachable)), recorded.toList())
    }

    // Q73: deleting the address in use moves the link to another one at once: a switch, never the 4409 stand-down.
    @Test
    fun deletingTheAddressInUseReconnectsThroughAnotherAtOnce() {
        val dialer = FakeDialer { _, _ -> FakeSocket().ready() }
        val manager = link(dialer, supersededRetryMillis = 60_000)
        val online = manager.await("online on the LAN") { it is LinkState.Online } as LinkState.Online
        assertEquals(lan, online.endpoint)
        val started = System.nanoTime()
        credentials.value = CredentialState.Paired(pc.copy(addresses = listOf(tailnet))) // the store has it gone first
        manager.addressRemoved(lan)
        val moved = manager.await("online elsewhere") { it is LinkState.Online && it.generation > online.generation } as LinkState.Online
        assertEquals(tailnet, moved.endpoint)
        assertTrue("at once", (System.nanoTime() - started) / 1_000_000 < 3_000)
        assertEquals(listOf(lan, tailnet), dialer.opened)
        waitFor("the old relay was closed") { !dialer.sockets[0].active }
        assertTrue(recorded.none { it is ConnectionEvent.Offline })
        manager.addressRemoved(ServerEndpoint("10.9.9.9", 8760, Network.Lan)) // not in use: the relay stays
        runBlocking { delay(200) }
        assertEquals(moved, manager.state.value)
    }

    @Test
    fun eachRoundEndHasItsHistoryReason() {
        assertEquals(OfflineReason.Revoked, LinkHistory.reasonOf(RoundEnd.Revoked))
        assertEquals(OfflineReason.HermesNotRunning, LinkHistory.reasonOf(RoundEnd.HermesDown("close 4503 hermes_not_running", lan)))
        assertEquals(OfflineReason.Superseded, LinkHistory.reasonOf(RoundEnd.Superseded("close 4409", lan)))
        assertEquals(OfflineReason.TailscaleMayBeOff, LinkHistory.reasonOf(RoundEnd.Lost(OfflineCause.TailscaleMayBeOff, "x", null)))
        assertNull("a switch is not offline", LinkHistory.reasonOf(RoundEnd.Switch))
    }

    @Test
    fun theDropThatDemotesSaysSo() {
        val flaps = LanFlaps(drops = 2, windowMillis = 1_000, demoteMillis = 5_000)
        assertFalse(flaps.dropped(lan, setOf(1L), 0))
        assertTrue(flaps.dropped(lan, setOf(1L), 10))
        assertFalse(flaps.dropped(tailnet, setOf(1L), 20))
    }
}
