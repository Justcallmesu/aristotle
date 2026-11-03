package io.github.justcallmesu.aristotle.data

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.Socket
import java.net.SocketAddress
import java.net.URI
import javax.net.SocketFactory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** D21: plain HTTP to a tailnet address only over the VPN network; no proxy anywhere. */
class TailnetRouteTest {
    private val tailnet = ServerEndpoint("100.101.102.103", 8760, Network.Tailscale)

    private fun get(client: OkHttpClient, url: String) = client.newCall(Request.Builder().url(url).build()).execute()

    /** A "VPN network" whose sockets reach the local test server, whatever tailnet address they dial. */
    private class LoopbackVpn(private val target: InetSocketAddress) : VpnRoutes {
        val asked = mutableListOf<InetAddress>()
        var sockets = 0

        override fun socketFactoryFor(address: InetAddress): SocketFactory {
            asked += address
            return object : SocketFactory() {
                override fun createSocket(): Socket {
                    sockets++
                    return object : Socket() {
                        override fun connect(endpoint: SocketAddress?, timeout: Int) = super.connect(target, timeout)
                    }
                }

                override fun createSocket(host: String?, port: Int): Socket = unsupported()
                override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = unsupported()
                override fun createSocket(host: InetAddress?, port: Int): Socket = unsupported()
                override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                    unsupported()

                private fun unsupported(): Nothing = throw UnsupportedOperationException("OkHttp connects unconnected sockets")
            }
        }
    }

    @Test
    fun decisionByAddressKindAndVpn() {
        assertEquals(Route.SystemDefault, Routes.decide(Network.Lan, vpnRoutesAddress = false))
        assertEquals(Route.SystemDefault, Routes.decide(Network.Lan, vpnRoutesAddress = true))
        assertEquals(Route.ViaVpn, Routes.decide(Network.Tailscale, vpnRoutesAddress = true))
        assertEquals(Route.TailscaleOff, Routes.decide(Network.Tailscale, vpnRoutesAddress = false))
    }

    @Test
    fun tailnetRequestsGoOutOnTheVpnNetworkOnly() {
        startServer().use { server ->
            server.enqueue(MockResponse.Builder().code(200).body("ok").build())
            val vpn = LoopbackVpn(InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.port))
            get(testClients(vpn).forEndpoint(tailnet, null), "${tailnet.baseUrl}/healthz").use { assertEquals(200, it.code) }
            assertEquals(listOf(tailnet.inetAddress), vpn.asked.distinct())
            assertEquals(1, vpn.sockets)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun withTailscaleOffNothingIsSentAndTheErrorSaysSo() = runBlocking {
        val clients = testClients(NoVpn)
        val error = assertThrows(IOException::class.java) { get(clients.forEndpoint(tailnet, null), "${tailnet.baseUrl}/v1/me") }
        assertEquals(ConnectionProblem.TailscaleOff, connectionProblemOf(error))
        val client = ServerClient(clients.forEndpoint(tailnet, null), tailnet.baseUrl)
        assertEquals(MeResult.Failed(ConnectionProblem.TailscaleOff), client.me(DeviceToken("t")))
        assertEquals(PairResult.Failed(ConnectionProblem.TailscaleOff), client.pair("K7Q2M9XD", ""))
        val relay = RelaySocket(clients.forEndpoint(tailnet, null), tailnet.baseUrl, DeviceToken("t"))
        assertEquals(listOf(RelayEvent.Failed(null, ConnectionProblem.TailscaleOff)), withTimeout(10_000) { relay.events().toList() })
    }

    @Test
    fun lanRequestsNeverAskForTheVpn() {
        val held = lanCertificate()
        startServer(held).use { server ->
            server.enqueue(MockResponse.Builder().code(200).build())
            val vpn = LoopbackVpn(InetSocketAddress(InetAddress.getByName("127.0.0.1"), server.port))
            val lan = localEndpoint(server, Network.Lan)
            get(testClients(vpn).forEndpoint(lan, pinOf(held)), "${lan.baseUrl}/healthz").use { assertEquals(200, it.code) }
            assertTrue(vpn.asked.isEmpty())
        }
    }

    @Test
    fun noClientConsultsTheSystemProxy() {
        val clients = testClients()
        assertEquals(Proxy.NO_PROXY, clients.forEndpoint(tailnet, null).proxy)
        assertEquals(Proxy.NO_PROXY, clients.forEndpoint(ServerEndpoint("192.168.1.23", 8760, Network.Lan), ByteArray(32)).proxy)

        val held = lanCertificate()
        val asked = mutableListOf<URI>()
        val previous = ProxySelector.getDefault()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY).also { asked += uri }
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
        })
        try {
            startServer(held).use { server ->
                server.enqueue(MockResponse.Builder().code(200).build())
                val lan = localEndpoint(server, Network.Lan)
                get(clients.forEndpoint(lan, pinOf(held)), "${lan.baseUrl}/healthz").use { assertEquals(200, it.code) }
            }
        } finally {
            ProxySelector.setDefault(previous)
        }
        assertTrue("proxy selector consulted for $asked", asked.isEmpty())
    }
}
