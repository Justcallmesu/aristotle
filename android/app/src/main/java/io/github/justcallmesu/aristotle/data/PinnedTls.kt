package io.github.justcallmesu.aristotle.data

import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/** The server presented a certificate whose public key is not the pinned one. */
class PinMismatchException : CertificateException("server certificate does not match the pinned fingerprint")

/**
 * Trusts exactly one server public key (SPKI SHA-256). The server certificate is self-signed, so
 * chain validation cannot be used; expiry is not checked either, since the pin is the identity.
 */
class PinnedTrustManager(pin: ByteArray) : X509TrustManager {
    private val pin = pin.copyOf()

    init {
        require(pin.size == Fingerprint.BYTES) { "pin must be a SHA-256 digest" }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("server sent no certificate")
        if (!Fingerprint.matches(leaf, pin)) throw PinMismatchException()
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not accepted")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/**
 * Hostname check for the pinned connection only: the certificate's IP SANs can be stale
 * (protocol §11.3), so instead of matching the host it re-checks that the peer key is the pin.
 */
class PinnedHostnameVerifier(pin: ByteArray) : HostnameVerifier {
    private val pin = pin.copyOf()

    override fun verify(hostname: String?, session: SSLSession?): Boolean {
        val leaf = try {
            session?.peerCertificates?.firstOrNull() as? X509Certificate
        } catch (_: SSLPeerUnverifiedException) {
            null
        }
        return leaf != null && Fingerprint.matches(leaf, pin)
    }
}
