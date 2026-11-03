package io.github.justcallmesu.aristotle.data

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
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

    @Test
    fun refusedUpgradeReportsTheHttpStatus() = runBlocking {
        startServer(held).use { server ->
            server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
            val events = withTimeout(10_000) { relay(server).events().toList() }
            assertEquals(listOf(RelayEvent.Failed(401, ConnectionProblem.Unreachable)), events)
        }
    }
}
