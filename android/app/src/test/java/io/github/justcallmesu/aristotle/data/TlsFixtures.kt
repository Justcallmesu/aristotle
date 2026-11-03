package io.github.justcallmesu.aristotle.data

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/** A self-signed server certificate like aristotle-server's: ECDSA P-256, IP SAN that is NOT the test host. */
fun lanCertificate(san: String = "192.168.1.23"): HeldCertificate =
    HeldCertificate.Builder().ecdsa256().commonName("aristotle-server").addSubjectAlternativeName(san).build()

fun pinOf(held: HeldCertificate): ByteArray = Fingerprint.spkiSha256(held.certificate)

/** MockWebServer on 127.0.0.1, HTTPS with [held] when given. */
fun startServer(held: HeldCertificate? = null): MockWebServer = MockWebServer().apply {
    if (held != null) useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory())
    start(InetAddress.getByName("127.0.0.1"), 0)
}

/** Endpoint for a local test server. */
fun localEndpoint(server: MockWebServer): ServerEndpoint = ServerEndpoint("127.0.0.1", server.port, Network.Lan)

fun testClients(sockets: SocketFactory = DirectSocketFactory()): HttpClients = HttpClients(sockets)

/**
 * Sockets that reach [target] whatever address OkHttp dials, so a URL with a tailnet IP is served by a local
 * test server. [dialed] records the addresses asked for.
 */
class RedirectingSockets(private val target: InetSocketAddress) : SocketFactory() {
    val dialed = mutableListOf<SocketAddress?>()

    override fun createSocket(): Socket = object : Socket(Proxy.NO_PROXY) {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            dialed += endpoint
            super.connect(target, timeout)
        }
    }

    override fun createSocket(host: String?, port: Int): Socket = unsupported()
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = unsupported()
    override fun createSocket(host: InetAddress?, port: Int): Socket = unsupported()
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket = unsupported()

    private fun unsupported(): Nothing = throw UnsupportedOperationException("OkHttp connects unconnected sockets")
}
