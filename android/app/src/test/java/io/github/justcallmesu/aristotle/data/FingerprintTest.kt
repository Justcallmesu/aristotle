package io.github.justcallmesu.aristotle.data

import java.security.MessageDigest
import java.security.cert.CertificateException
import javax.net.ssl.SSLSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintTest {
    @Test
    fun formatIsUppercaseHexInSixteenGroupsOfFour() {
        val digest = ByteArray(32) { it.toByte() }
        val text = Fingerprint.format(digest)
        assertEquals("0001 0203 0405 0607 0809 0A0B 0C0D 0E0F 1011 1213 1415 1617 1819 1A1B 1C1D 1E1F", text)
        assertEquals(16, Fingerprint.groups(digest).size)
        assertEquals("0001 0203 … 1C1D 1E1F", Fingerprint.abbreviated(digest))
    }

    @Test
    fun parseRoundTripsAndRejectsGarbage() {
        val digest = ByteArray(32) { (255 - it).toByte() }
        assertArrayEquals(digest, Fingerprint.parse(Fingerprint.format(digest)))
        assertArrayEquals(digest, Fingerprint.parse(Fingerprint.format(digest).lowercase().replace(" ", "")))
        assertNull(Fingerprint.parse("7E54 D87B"))
        assertNull(Fingerprint.parse("Z".repeat(64)))
        // Arabic-Indic and fullwidth digits pass isDigit() and toInt(16), but are not hex.
        assertNull(Fingerprint.parse("\u0663".repeat(64)))
        assertNull(Fingerprint.parse("\uFF11".repeat(64)))
    }

    @Test
    fun fingerprintIsSha256OfTheSubjectPublicKeyInfo() {
        val held = lanCertificate()
        val expected = MessageDigest.getInstance("SHA-256").digest(held.certificate.publicKey.encoded)
        assertArrayEquals(expected, Fingerprint.spkiSha256(held.certificate))
        assertTrue(Fingerprint.matches(held.certificate, expected))
        assertFalse(Fingerprint.matches(lanCertificate().certificate, expected))
    }
}

class PinnedTrustManagerTest {
    private val pinned = lanCertificate()
    private val other = lanCertificate()
    private val trustManager = PinnedTrustManager(pinOf(pinned))

    @Test
    fun acceptsExactlyThePinnedKey() {
        trustManager.checkServerTrusted(arrayOf(pinned.certificate), "ECDHE_ECDSA")
    }

    @Test
    fun rejectsAnotherKeyAsPinMismatch() {
        assertThrows(PinMismatchException::class.java) {
            trustManager.checkServerTrusted(arrayOf(other.certificate), "ECDHE_ECDSA")
        }
    }

    @Test
    fun rejectsAChainWhoseLeafIsNotPinnedEvenIfAnIssuerIs() {
        assertThrows(PinMismatchException::class.java) {
            trustManager.checkServerTrusted(arrayOf(other.certificate, pinned.certificate), "ECDHE_ECDSA")
        }
    }

    @Test
    fun rejectsAnEmptyChainAndClientCertificates() {
        assertThrows(CertificateException::class.java) { trustManager.checkServerTrusted(emptyArray(), "ECDHE_ECDSA") }
        assertThrows(CertificateException::class.java) { trustManager.checkServerTrusted(null, "ECDHE_ECDSA") }
        assertThrows(CertificateException::class.java) { trustManager.checkClientTrusted(arrayOf(pinned.certificate), "EC") }
        assertEquals(0, trustManager.acceptedIssuers.size)
    }

    @Test
    fun pinMustBeASha256Digest() {
        assertThrows(IllegalArgumentException::class.java) { PinnedTrustManager(ByteArray(20)) }
    }

    @Test
    fun hostnameVerifierChecksThePinNotTheName() {
        val verifier = PinnedHostnameVerifier(pinOf(pinned))
        assertTrue(verifier.verify("10.9.9.9", sessionWith(pinned.certificate)))
        assertFalse(verifier.verify("192.168.1.23", sessionWith(other.certificate)))
        assertFalse(verifier.verify("192.168.1.23", null))
    }

    private fun sessionWith(cert: java.security.cert.X509Certificate): SSLSession =
        java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SSLSession::class.java)) { _, method, _ ->
            when (method.name) {
                "getPeerCertificates" -> arrayOf<java.security.cert.Certificate>(cert)
                else -> null
            }
        } as SSLSession
}
