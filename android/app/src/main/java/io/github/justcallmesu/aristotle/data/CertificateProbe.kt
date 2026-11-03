package io.github.justcallmesu.aristotle.data

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface ProbeResult {
    data class Certificate(val fingerprint: ByteArray) : ProbeResult {
        override fun equals(other: Any?) = other is Certificate && fingerprint.contentEquals(other.fingerprint)
        override fun hashCode() = fingerprint.contentHashCode()
    }

    /** No TCP connection (wrong address, server down, other network). */
    data object Unreachable : ProbeResult

    /** Connected, but no TLS certificate came back (not an aristotle-server LAN listener). */
    data object NoCertificate : ProbeResult
}

/** Where the pair screen gets the certificate to compare; [CertificateProbe] in the app. */
fun interface CertificateSource {
    suspend fun fetch(endpoint: ServerEndpoint): ProbeResult
}

/**
 * Reads the server certificate for the user to compare, without trusting it: the handshake is
 * always aborted after the certificate is captured, so no request is ever sent on this socket.
 */
class CertificateProbe(private val timeoutMillis: Int = 5_000) : CertificateSource {

    override suspend fun fetch(endpoint: ServerEndpoint): ProbeResult = withContext(Dispatchers.IO) {
        val capture = CapturingTrustManager()
        val raw = Socket(Proxy.NO_PROXY)
        try {
            raw.connect(InetSocketAddress(endpoint.inetAddress, endpoint.port), timeoutMillis)
            raw.soTimeout = timeoutMillis
        } catch (_: IOException) {
            raw.close()
            return@withContext ProbeResult.Unreachable
        }
        handshake(raw, endpoint, capture)
        capture.leaf?.let { ProbeResult.Certificate(Fingerprint.spkiSha256(it)) } ?: ProbeResult.NoCertificate
    }

    private fun handshake(raw: Socket, endpoint: ServerEndpoint, capture: CapturingTrustManager) {
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
        val socket = context.socketFactory.createSocket(raw, endpoint.host, endpoint.port, true) as SSLSocket
        try {
            socket.startHandshake()
        } catch (_: SSLException) {
            // Expected: CapturingTrustManager always refuses.
        } catch (_: IOException) {
            // Not TLS, or the peer hung up; capture.leaf stays null.
        } finally {
            socket.close()
        }
    }
}

private class CapturingTrustManager : X509TrustManager {
    @Volatile
    var leaf: X509Certificate? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        leaf = chain?.firstOrNull()
        throw CertificateException("certificate captured for comparison; connection not trusted")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
