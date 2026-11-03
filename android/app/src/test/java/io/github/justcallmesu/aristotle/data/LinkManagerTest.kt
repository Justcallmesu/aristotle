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

    private class FakeSocket : RelayChannel {
        private val incoming = Channel<RelayEvent>(Channel.UNLIMITED)
        val sent = CopyOnWriteArrayList<String>()

        @Volatile
        var closedByPhone = false

        /** Dropped without a close handshake ([RelayChannel.cancel]). */
        @Volatile
        var cancelled = false

        override fun events(): Flow<RelayEvent> = incoming.receiveAsFlow().onCompletion { closedByPhone = true }

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

        override fun openRelay(pc: PcCredentials, endpoint: ServerEndpoint): RelayChannel = synchronized(this) {
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
        override fun prefersLan() = lan
        override fun vpnUp() = vpn
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
        memory: LinkMemory = LinkMemory { _, addresses, lastGood -> remembered += Remembered(addresses, lastGood) },
    ): LinkManager = LinkManager(
        scope = scope,
        credentials = credentials,
        dialer = dialer,
        network = network,
        memory = memory,
        timings = LinkTimings(readyTimeoutMillis = 2_000, hermesDownRetryMillis = 150, rpcTimeoutMillis = 2_000, backoff = backoff),
    ).also { it.start() }

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
        network.change(preferLan = true)
        manager.onForeground()
        runBlocking { delay(300) }
        assertEquals(1, dialer.opened.size)
        manager.retryNow()
        waitFor("checked again") { dialer.opened.size == 2 }
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
        assertTrue("the old network's socket is dropped, not closed with a handshake", dialer.sockets.first().cancelled)
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
        val manager = link(dialer, memory = { _, _, _ -> throw IllegalStateException("disk full") })
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
}
