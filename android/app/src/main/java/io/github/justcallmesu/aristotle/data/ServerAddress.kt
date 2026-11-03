package io.github.justcallmesu.aristotle.data

import java.net.Inet6Address
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

    private fun parseIpv6(text: String): ByteArray? {
        // Only hex digits, colons and an embedded dotted quad: InetAddress then parses a literal
        // and never does a DNS lookup. Zone ids (%wlan0) are refused.
        if (!text.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }) return null
        val address = runCatching { InetAddress.getByName(text) }.getOrNull() ?: return null
        return if (address is Inet6Address) address.address else null
    }

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
}
