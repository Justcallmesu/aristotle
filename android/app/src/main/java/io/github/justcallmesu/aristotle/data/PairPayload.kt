package io.github.justcallmesu.aristotle.data

import java.net.URLDecoder

/** What a valid pairing QR code holds (protocol §11.4): the one-time code, the PC's pin and where to reach it. */
class PairPayload(val code: String, pin: ByteArray, val addresses: List<ServerEndpoint>) {
    private val pinBytes = pin.copyOf()
    val pin: ByteArray get() = pinBytes.copyOf()
}

/** Why a scanned code was refused; each has its own message. */
enum class PayloadError {
    /** Not an `aristotle://pair?` URI at all (another app's QR code, a website). */
    NotPairing,

    /** `v` is not 1: a server newer (or older) than this app. */
    Version,
    Code,
    Fingerprint,

    /** An `a` that is not `https://<LAN or tailnet IP>:<port>`. */
    Address,

    /** Too long, a missing, repeated or unknown key, more than 8 addresses, a broken escape. */
    Malformed,
}

sealed interface PayloadCheck {
    class Ok(val payload: PairPayload) : PayloadCheck
    data class Refused(val error: PayloadError) : PayloadCheck
}

/** Parses the pairing QR payload strictly: anything but exactly what `aristotle-server pair` writes is refused. */
object PairPayloads {
    const val PREFIX = "aristotle://pair?"
    const val VERSION = "1"
    const val MAX_ADDRESSES = 8
    const val MAX_LENGTH = 4096

    private const val HTTPS = "https://"
    private const val UTF_8 = "UTF-8"
    private val KEYS = setOf("v", "c", "fp", "a")
    private val HEX = Regex("[0-9A-Fa-f]{${Fingerprint.BYTES * 2}}")

    fun parse(text: String): PayloadCheck {
        if (text.length > MAX_LENGTH) return refused(PayloadError.Malformed)
        if (!text.startsWith(PREFIX)) return refused(PayloadError.NotPairing)
        val fields = fields(text.substring(PREFIX.length)) ?: return refused(PayloadError.Malformed)
        // The version first: a later version may carry keys this one does not know.
        val version = fields["v"]?.singleOrNull() ?: return refused(PayloadError.Malformed)
        if (version != VERSION) return refused(PayloadError.Version)
        if (!KEYS.containsAll(fields.keys)) return refused(PayloadError.Malformed)
        val code = fields["c"]?.singleOrNull() ?: return refused(PayloadError.Malformed)
        val fp = fields["fp"]?.singleOrNull() ?: return refused(PayloadError.Malformed)
        val urls = fields["a"].orEmpty()
        if (urls.isEmpty() || urls.size > MAX_ADDRESSES) return refused(PayloadError.Malformed)
        if (!PairingCode.isIssued(code)) return refused(PayloadError.Code)
        val pin = fp.takeIf { HEX.matches(it) }?.let(Fingerprint::parse) ?: return refused(PayloadError.Fingerprint)
        val endpoints = urls.map { endpointOf(it) ?: return refused(PayloadError.Address) }
        return PayloadCheck.Ok(PairPayload(code, pin, endpoints.distinct()))
    }

    /** `k=v&k=v…` decoded, values per key in order; null for an empty part, a part without `=` or a bad escape. */
    private fun fields(query: String): Map<String, List<String>>? {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (part in query.split('&')) {
            val eq = part.indexOf('=')
            if (eq <= 0) return null
            val value = decode(part.substring(eq + 1)) ?: return null
            out.getOrPut(part.substring(0, eq)) { mutableListOf() } += value
        }
        return out
    }

    private fun decode(value: String): String? = try {
        URLDecoder.decode(value, UTF_8) // the Charset overload needs API 33
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * `https://<ip>:<port>` and nothing more (no path, query or user info, an explicit port), then the same
     * address rule as typed and stored addresses ([ServerAddress.parse]: an RFC1918 or tailnet IP literal).
     */
    private fun endpointOf(url: String): ServerEndpoint? {
        if (!url.startsWith(HTTPS)) return null
        val authority = url.substring(HTTPS.length)
        if (authority.any { it in "/?#@\\" } || !hasPort(authority)) return null
        return (ServerAddress.parse(url) as? AddressCheck.Ok)?.endpoint
    }

    /** `[v6]:port` or `v4:port`; a bare IPv6 literal has no port (its last group is not one). */
    private fun hasPort(authority: String): Boolean =
        if (authority.startsWith("[")) "]:" in authority else authority.count { it == ':' } == 1

    private fun refused(error: PayloadError) = PayloadCheck.Refused(error)
}
