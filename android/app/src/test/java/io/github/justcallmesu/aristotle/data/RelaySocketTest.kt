package io.github.justcallmesu.aristotle.data

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RelaySocketTest {
    private val held = lanCertificate()

    private fun relay(server: mockwebserver3.MockWebServer, token: String = "tok"): RelaySocket {
        val endpoint = localEndpoint(server)
        return RelaySocket(testClients().pinnedTo(pinOf(held), Network.Lan), endpoint.baseUrl, DeviceToken(token))
    }

    @Test
    fun deliversFramesThenTheServersCloseCode() = runBlocking {
        startServer(held).use { server ->
            server.enqueue(
                MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.send("""{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{}}}""")
                        webSocket.close(4503, "hermes_not_running")
                    }
                }).build(),
            )
            val events = withTimeout(10_000) { relay(server, "secret").events().toList() }
            assertEquals(RelayEvent.Opened, events[0])
            assertEquals(InboundFrame.Event("gateway.ready", "", kotlinx.serialization.json.JsonObject(emptyMap())),
                RpcFrames.parse((events[1] as RelayEvent.Text).text))
            assertEquals(RelayEvent.Closed(4503, "hermes_not_running"), events.last())
            val upgrade = server.takeRequest()
            assertEquals("/v1/ws", upgrade.url.encodedPath)
            assertEquals("Bearer secret", upgrade.headers["Authorization"])
            assertEquals(null, upgrade.headers["Origin"])
        }
    }

    // T07 review: the upgrade itself is pinned. A server with another key gets no request, so no Bearer token.
    @Test
    fun wssUpgradeToAnotherKeyIsRefusedBeforeAnyRequest() = runBlocking {
        startServer(held).use { server ->
            server.enqueue(MockResponse.Builder().code(101).build())
            val endpoint = localEndpoint(server)
            val relay = RelaySocket(testClients().pinnedTo(pinOf(lanCertificate()), Network.Lan), endpoint.baseUrl, DeviceToken("secret"))
            val events = withTimeout(10_000) { relay.events().toList() }
            assertEquals(listOf(RelayEvent.Failed(null, ConnectionProblem.CertificateChanged)), events)
            assertEquals(0, server.requestCount)
        }
    }

    // Q49: the relay to a tailnet address is wss:// with the PC's pin, like the LAN.
    @Test
    fun relayToATailnetAddressIsPinnedWss() = runBlocking {
        startServer(held).use { server ->
            server.enqueue(
                MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocket.close(1000, "")
                    }
                }).build(),
            )
            val tailnet = ServerEndpoint("100.101.102.103", 8760, Network.Tailscale)
            val sockets = RedirectingSockets(InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.port))
            val relay = RelaySocket(testClients(sockets).pinnedTo(pinOf(held), Network.Lan), tailnet.baseUrl, DeviceToken("secret"))
            val events = withTimeout(10_000) { relay.events().toList() }
            assertEquals(RelayEvent.Opened, events.first())
            assertEquals(listOf<SocketAddress?>(InetSocketAddress(tailnet.inetAddress, 8760)), sockets.dialed)
            assertEquals("Bearer secret", server.takeRequest().headers["Authorization"])
        }
    }

    // T09 review 3: a socket still connecting is dropped when its collector goes, not closed with a handshake
    // that keeps OkHttp connecting; the PC sees the connection end at once.
    @Test
    fun aSocketStillConnectingIsDroppedAtOnce() = runBlocking {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { silent ->
            val accepted = CompletableDeferred<Socket>()
            thread(isDaemon = true) { runCatching { accepted.complete(silent.accept()) } }
            val relay = RelaySocket(testClients().pinnedTo(pinOf(held), Network.Lan), "https://127.0.0.1:${silent.localPort}", DeviceToken("t"))
            val collector = launch(Dispatchers.Default) { relay.events().collect {} }
            withTimeout(5_000) { accepted.await() }.use { peer ->
                awaitClientHello(peer)
                collector.cancelAndJoin()
                assertTrue("the phone kept connecting", endsWithin(peer, millis = 3_000))
            }
        }
    }

    // T09 review 3: cancel() drops an open socket without a close frame (its network is gone).
    @Test
    fun cancelDropsAnOpenSocketWithoutACloseFrame() = runBlocking {
        startServer(held).use { server ->
            val serverSide = CompletableDeferred<String>()
            server.enqueue(
                MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        serverSide.complete("close frame $code")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        serverSide.complete("dropped")
                    }
                }).build(),
            )
            val relay = relay(server)
            val events = Channel<RelayEvent>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { relay.events().collect { events.send(it) } }
            assertEquals(RelayEvent.Opened, withTimeout(10_000) { events.receive() })
            relay.cancel()
            assertEquals("dropped", withTimeout(10_000) { serverSide.await() })
            collector.cancelAndJoin()
        }
    }

    // D33: when the link moves on from an open socket (a switch, a timeout), the collector goes and the PC gets
    // a 1000 close frame at once, not a socket left for its ping timeout to reap.
    @Test
    fun anOpenSocketWhoseCollectorGoesIsClosedWith1000() = runBlocking {
        startServer(held).use { server ->
            val serverSide = CompletableDeferred<String>()
            server.enqueue(
                MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(1000, null) // completes the handshake, so the server shuts down at once
                        serverSide.complete("close frame $code")
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        serverSide.complete("dropped")
                    }
                }).build(),
            )
            val relay = relay(server)
            val events = Channel<RelayEvent>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { relay.events().collect { events.send(it) } }
            assertEquals(RelayEvent.Opened, withTimeout(10_000) { events.receive() })
            collector.cancelAndJoin()
            assertEquals("close frame 1000", withTimeout(10_000) { serverSide.await() })
        }
    }

    @Test
    fun refusedUpgradeReportsTheHttpStatus() = runBlocking {
        startServer(held).use { server ->
            server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
            val events = withTimeout(10_000) { relay(server).events().toList() }
            assertEquals(listOf(RelayEvent.Failed(401, ConnectionProblem.Unreachable)), events)
        }
    }
}
