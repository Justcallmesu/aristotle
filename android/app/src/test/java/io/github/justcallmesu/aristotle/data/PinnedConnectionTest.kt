package io.github.justcallmesu.aristotle.data

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.net.UnknownServiceException
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real TLS handshakes against a local server with a generated self-signed certificate. */
class PinnedConnectionTest {
    private val held = lanCertificate(san = "192.168.1.23")

    private fun get(client: OkHttpClient, url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    private fun redirectTo(server: MockWebServer) = RedirectingSockets(InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.port))

    @Test
    fun pinnedClientConnectsAlthoughTheCertificateNamesAnotherHost() {
        startServer(held).use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())
            val client = testClients().pinnedTo(pinOf(held), Network.Lan)
            get(client, "https://127.0.0.1:${server.port}/healthz").use { assertEquals(200, it.code) }
        }
    }

    @Test
    fun aDifferentServerKeyIsRefusedBeforeAnyRequest() {
        startServer(held).use { server ->
            server.enqueue(MockResponse.Builder().code(200).build())
            val client = testClients().pinnedTo(pinOf(lanCertificate()), Network.Lan)
            val error = assertThrows(IOException::class.java) { get(client, "https://127.0.0.1:${server.port}/healthz") }
            assertEquals(ConnectionProblem.CertificateChanged, connectionProblemOf(error))
            assertEquals(0, server.requestCount)
        }
    }

    // Q49: the PC's one pin applies to its Tailscale address too; nothing is trusted there without it.
    @Test
    fun thePinAppliesToATailnetUrl() {
        val tailnet = (ServerAddress.parse("100.101.102.103:8760") as AddressCheck.Ok).endpoint
        assertEquals("https://100.101.102.103:8760", tailnet.baseUrl)
        startServer(held).use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())
            val sockets = redirectTo(server)
            get(testClients(sockets).pinnedTo(pinOf(held), Network.Tailscale), "${tailnet.baseUrl}/healthz").use { assertEquals(200, it.code) }
            assertEquals(listOf<SocketAddress?>(InetSocketAddress(tailnet.inetAddress, 8760)), sockets.dialed)

            val wrongPin = testClients(redirectTo(server)).pinnedTo(pinOf(lanCertificate()), Network.Tailscale)
            val error = assertThrows(IOException::class.java) { get(wrongPin, "${tailnet.baseUrl}/v1/me") }
            assertEquals(ConnectionProblem.CertificateChanged, connectionProblemOf(error))
            assertEquals(1, server.requestCount)
        }
    }

    // Q49: no client speaks cleartext. An http:// URL (a tailnet one included) fails before a socket opens.
    @Test
    fun plainHttpIsRefusedBeforeConnecting() {
        startServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).build())
            val sockets = redirectTo(server)
            val client = testClients(sockets).pinnedTo(pinOf(held), Network.Lan)
            for (url in listOf("http://127.0.0.1:${server.port}/healthz", "http://100.101.102.103:8760/v1/me")) {
                assertThrows(UnknownServiceException::class.java) { get(client, url) }
            }
            assertTrue(sockets.dialed.isEmpty())
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun noClientConsultsTheSystemProxy() {
        val clients = testClients()
        assertEquals(Proxy.NO_PROXY, clients.pinnedTo(ByteArray(32), Network.Lan).proxy)
        val asked = mutableListOf<URI>()
        val previous = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY).also { asked += uri }
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        })
        try {
            startServer(held).use { server ->
                server.enqueue(MockResponse.Builder().code(200).build())
                get(clients.pinnedTo(pinOf(held), Network.Lan), "${localEndpoint(server).baseUrl}/healthz").use { assertEquals(200, it.code) }
            }
        } finally {
            ProxySelector.setDefault(previous)
        }
        assertTrue("proxy selector consulted for $asked", asked.isEmpty())
    }

    @Test
    fun probeReadsTheCertificateWithoutSendingARequest() = runBlocking {
        startServer(held).use { server ->
            val result = CertificateProbe().fetch(localEndpoint(server))
            assertEquals(ProbeResult.Certificate(pinOf(held)), result)
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun probeReportsAPlainHttpServerAsNoCertificate() = runBlocking {
        startServer().use { server ->
            assertEquals(ProbeResult.NoCertificate, CertificateProbe(timeoutMillis = 2_000).fetch(localEndpoint(server)))
        }
    }

    @Test
    fun probeReportsAClosedPortAsUnreachable() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(ProbeResult.Unreachable, CertificateProbe().fetch(ServerEndpoint("127.0.0.1", port, Network.Lan)))
    }

    // D26 + T09: LAN and Tailscale clients never share a pool; connecting gives up after 3 s; no client ping.
    @Test
    fun eachAddressKindHasItsOwnPoolAndNoClientPings() {
        val clients = testClients()
        val lan = clients.pinnedTo(ByteArray(32), Network.Lan)
        val tailnet = clients.pinnedTo(ByteArray(32), Network.Tailscale)
        assertTrue(lan.connectionPool !== tailnet.connectionPool)
        assertTrue(lan.connectionPool === clients.pinnedTo(ByteArray(32) { 1 }, Network.Lan).connectionPool)
        for (client in listOf(lan, tailnet)) {
            assertEquals(3_000, client.connectTimeoutMillis)
            assertEquals(0, client.pingIntervalMillis)
        }
    }
}
