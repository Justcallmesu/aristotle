package io.github.justcallmesu.aristotle.data

import java.io.IOException
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Real TLS handshakes against a local server with a generated self-signed certificate. */
class PinnedConnectionTest {
    private val held = lanCertificate(san = "192.168.1.23")

    private fun get(client: OkHttpClient, url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun pinnedClientConnectsAlthoughTheCertificateNamesAnotherHost() {
        startServer(held).use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())
            val client = testClients().forEndpoint(localEndpoint(server, Network.Lan), pinOf(held))
            get(client, "https://127.0.0.1:${server.port}/healthz").use { assertEquals(200, it.code) }
        }
    }

    @Test
    fun aDifferentServerKeyIsRefusedBeforeAnyRequest() {
        startServer(held).use { server ->
            server.enqueue(MockResponse.Builder().code(200).build())
            val client = testClients().forEndpoint(localEndpoint(server, Network.Lan), pinOf(lanCertificate()))
            val error = assertThrows(IOException::class.java) { get(client, "https://127.0.0.1:${server.port}/healthz") }
            assertEquals(ConnectionProblem.CertificateChanged, connectionProblemOf(error))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun cleartextToANonTailnetHostIsRefusedBeforeConnecting() {
        startServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).build())
            val client = testClients().forEndpoint(localEndpoint(server, Network.Tailscale), null)
            val error = assertThrows(IOException::class.java) { get(client, "http://127.0.0.1:${server.port}/healthz") }
            assertEquals(ConnectionProblem.CleartextRefused, connectionProblemOf(error))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun lanEndpointWithoutAPinIsAProgrammingError() {
        assertThrows(IllegalArgumentException::class.java) {
            testClients().forEndpoint(ServerEndpoint("192.168.1.23", 8760, Network.Lan), null)
        }
    }

    @Test
    fun probeReadsTheCertificateWithoutSendingARequest() = runBlocking {
        startServer(held).use { server ->
            val result = CertificateProbe().fetch(localEndpoint(server, Network.Lan))
            assertEquals(ProbeResult.Certificate(pinOf(held)), result)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun probeReportsAPlainHttpServerAsNoCertificate() = runBlocking {
        startServer().use { server ->
            assertEquals(ProbeResult.NoCertificate, CertificateProbe(timeoutMillis = 2_000).fetch(localEndpoint(server, Network.Lan)))
        }
    }

    @Test
    fun probeReportsAClosedPortAsUnreachable() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(ProbeResult.Unreachable, CertificateProbe().fetch(ServerEndpoint("127.0.0.1", port, Network.Lan)))
    }
}
