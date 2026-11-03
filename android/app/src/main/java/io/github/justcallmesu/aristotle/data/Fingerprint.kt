package io.github.justcallmesu.aristotle.data

import java.security.MessageDigest
import java.security.cert.X509Certificate

/** SPKI SHA-256 certificate fingerprints (docs/protocol.md §11.3). */
object Fingerprint {
    const val BYTES = 32
    private const val GROUP = 4

    fun spkiSha256(cert: X509Certificate): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)

    /** Uppercase hex in space-separated groups of 4: "7E54 D87B … BCA7 EAA7" (16 groups). */
    fun format(digest: ByteArray): String = groups(digest).joinToString(" ")

    fun groups(digest: ByteArray): List<String> = hex(digest).chunked(GROUP)

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }

    private const val HEX = "0123456789ABCDEF"

    /** Parses [format]'s output (spaces optional, any case) back to the 32 digest bytes. */
    fun parse(text: String): ByteArray? {
        val hex = text.filterNot { it == ' ' }
        if (hex.length != BYTES * 2 || !hex.all { it.isDigit() || it.uppercaseChar() in 'A'..'F' }) return null
        return ByteArray(BYTES) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Short form for the confirmed card: first two and last two groups. */
    fun abbreviated(digest: ByteArray): String {
        val g = groups(digest)
        return "${g[0]} ${g[1]} … ${g[g.size - 2]} ${g[g.size - 1]}"
    }

    fun matches(cert: X509Certificate, pin: ByteArray): Boolean =
        MessageDigest.isEqual(spkiSha256(cert), pin)
}
