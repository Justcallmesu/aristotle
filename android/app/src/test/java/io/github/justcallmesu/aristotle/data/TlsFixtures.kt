package io.github.justcallmesu.aristotle.data

import java.net.InetAddress
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

/** Endpoint for a local test server; the network label decides HTTPS (Lan) vs HTTP (Tailscale). */
fun localEndpoint(server: MockWebServer, network: Network): ServerEndpoint = ServerEndpoint("127.0.0.1", server.port, network)
