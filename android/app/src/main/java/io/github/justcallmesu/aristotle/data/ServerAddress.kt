package io.github.justcallmesu.aristotle.data

import java.net.InetAddress

/** Which aristotle-server listener an address belongs to (docs/protocol.md §11.1). */
enum class Network { Lan, Tailscale }

/**
 * A validated server address. [host] is an IP literal (IPv6 without brackets).
 * LAN means HTTPS with a pinned certificate; Tailscale means plain HTTP inside WireGuard.
 */
data class ServerEndpoint(val host: String, val port: Int, val network: Network) {
    val scheme: String get() = if (network == Network.Tailscale) "http" else "https"
    val hostForUrl: String get() = if (':' in host) "[$host]" else host
    val baseUrl: String get() = "$scheme://$hostForUrl:$port"
    val displayAddress: String get() = "$hostForUrl:$port"

    /** The address as an [InetAddress], built from the literal's bytes (no lookup). */
    val inetAddress: InetAddress
        get() = InetAddress.getByAddress(requireNotNull(ServerAddress.parseIpLiteral(host)) { "not an IP literal" })
}

enum class AddressError {
    Empty,

    /** A name instead of an IP: the scheme rule needs the address itself. */
    NotAnIpAddress,
    BadPort,

    /** Public, loopback or any other range: spec §2 allows only the home/office LAN and the tailnet. */
    NotPrivate,
}

sealed interface AddressCheck {
    data class Ok(val endpoint: ServerEndpoint) : AddressCheck
    data class Refused(val error: AddressError) : AddressCheck
}

object ServerAddress {
    const val DEFAULT_PORT = 8760

    /**
     * Parses `host`, `host:port`, `[v6]:port` or a pasted URL (its scheme is ignored; the
     * address range decides). Never resolves names.
     */
    fun parse(input: String): AddressCheck {
        val text = stripUrlParts(input.trim())
        if (text.isEmpty()) return AddressCheck.Refused(AddressError.Empty)
        val (hostText, portText) = splitHostPort(text) ?: return AddressCheck.Refused(AddressError.NotAnIpAddress)
        val port = parsePort(portText) ?: return AddressCheck.Refused(AddressError.BadPort)
        val bytes = parseIpLiteral(hostText) ?: return AddressCheck.Refused(AddressError.NotAnIpAddress)
        val network = networkOf(bytes) ?: return AddressCheck.Refused(AddressError.NotPrivate)
        return AddressCheck.Ok(ServerEndpoint(canonicalHost(bytes), port, network))
    }

    /** Network of a URL host (IP literal) or null when it is in neither allowed range. */
    fun networkOfHost(host: String): Network? = parseIpLiteral(host.removePrefix("[").removeSuffix("]"))?.let(::networkOf)

    fun isTailnetHost(host: String): Boolean = networkOfHost(host) == Network.Tailscale

    internal fun networkOf(address: ByteArray): Network? = when {
        isTailscale(address) -> Network.Tailscale
        isRfc1918(address) -> Network.Lan
        else -> null
    }

    private fun stripUrlParts(text: String): String {
        val noScheme = text.substringAfter("://", text)
        return noScheme.substringBefore('/')
    }

    private fun splitHostPort(text: String): Pair<String, String?>? {
        if (text.startsWith("[")) {
            val close = text.indexOf(']')
            if (close < 0) return null
            val rest = text.substring(close + 1)
            val port = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
            return text.substring(1, close) to port
        }
        return when (text.count { it == ':' }) {
            0 -> text to null
            1 -> text.substringBefore(':') to text.substringAfter(':')
            else -> text to null
        }
    }

    private fun parsePort(text: String?): Int? {
        if (text == null) return DEFAULT_PORT
        if (text.isEmpty() || text.length > 5 || !text.all { it in '0'..'9' }) return null
        return text.toInt().takeIf { it in 1..65535 }
    }

    internal fun parseIpLiteral(text: String): ByteArray? = when {
        text.isEmpty() -> null
        ':' in text -> parseIpv6(text)
        else -> parseIpv4(text)
    }

    private fun parseIpv4(text: String): ByteArray? {
        val parts = text.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty() || part.length > 3 || !part.all { it in '0'..'9' }) return null
            if (part.length > 1 && part[0] == '0') return null
            val value = part.toInt()
            if (value > 255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    /**
     * Pure RFC 4291 §2.2 text parser: up to 8 groups of 1-4 hex digits, at most one `::`, optionally a
     * dotted quad as the last two groups. Zone ids (%wlan0) are refused. No platform call, so nothing
     * can fall through to a DNS lookup (Android's InetAddress.getByName resolves anything it cannot parse).
     */
    private fun parseIpv6(text: String): ByteArray? {
        if (text.length > MAX_IPV6_TEXT) return null
        val gap = text.indexOf("::")
        if (gap >= 0 && text.indexOf("::", gap + 1) >= 0) return null
        val groups = if (gap < 0) {
            ipv6Groups(text)?.takeIf { it.size == IPV6_GROUPS }
        } else {
            val head = ipv6Groups(text.substring(0, gap), dottedQuadAllowed = false) ?: return null
            val tail = ipv6Groups(text.substring(gap + 2)) ?: return null
            val missing = IPV6_GROUPS - head.size - tail.size
            if (missing < 1) null else head + List(missing) { 0 } + tail
        } ?: return null
        return ByteArray(16) { i -> (groups[i / 2] shr (if (i % 2 == 0) 8 else 0)).toByte() }
    }

    /** "a:b:c" as 16-bit values; a trailing dotted quad counts as two groups; "" is no groups. */
    private fun ipv6Groups(text: String, dottedQuadAllowed: Boolean = true): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val parts = text.split(':')
        val groups = ArrayList<Int>(IPV6_GROUPS)
        for ((i, part) in parts.withIndex()) {
            if (dottedQuadAllowed && i == parts.lastIndex && '.' in part) {
                val v4 = parseIpv4(part) ?: return null
                groups += (u(v4[0]) shl 8) or u(v4[1])
                groups += (u(v4[2]) shl 8) or u(v4[3])
            } else {
                if (part.isEmpty() || part.length > 4 || !part.all(::isHexDigit)) return null
                groups += part.toInt(16)
            }
        }
        return groups
    }

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun isTailscale(a: ByteArray): Boolean = when (a.size) {
        4 -> u(a[0]) == 100 && (u(a[1]) and 0xC0) == 64
        16 -> u(a[0]) == 0xFD && u(a[1]) == 0x7A && u(a[2]) == 0x11 && u(a[3]) == 0x5C &&
            u(a[4]) == 0xA1 && u(a[5]) == 0xE0
        else -> false
    }

    private fun isRfc1918(a: ByteArray): Boolean = a.size == 4 && when (u(a[0])) {
        10 -> true
        172 -> (u(a[1]) and 0xF0) == 16
        192 -> u(a[1]) == 168
        else -> false
    }

    private fun canonicalHost(bytes: ByteArray): String =
        if (bytes.size == 4) bytes.joinToString(".") { u(it).toString() }
        else InetAddress.getByAddress(bytes).hostAddress.orEmpty()

    private fun u(b: Byte): Int = b.toInt() and 0xFF

    private const val IPV6_GROUPS = 8
    private const val MAX_IPV6_TEXT = 45
}
