package io.github.justcallmesu.aristotle.data

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
        val endpoint = localEndpoint(server, Network.Lan)
        return RelaySocket(HttpClients().forEndpoint(endpoint, pinOf(held)), endpoint.baseUrl, DeviceToken(token))
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

    @Test
    fun refusedUpgradeReportsTheHttpStatus() = runBlocking {
        startServer(held).use { server ->
            server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
            val events = withTimeout(10_000) { relay(server).events().toList() }
            assertEquals(listOf(RelayEvent.Failed(401, ConnectionProblem.Unreachable)), events)
        }
    }
}
