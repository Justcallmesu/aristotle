package io.github.justcallmesu.aristotle.data

import kotlinx.serialization.Serializable

/** One PC address as the server sends it (protocol §11.2 `addresses`) and as the phone stores it. */
@Serializable
data class WireAddress(val url: String, val kind: String, val iface: String? = null)

/** Turns address lists from the server or the store into endpoints the app may dial. */
object PcAddresses {
    /**
     * The usable endpoints of [wire], in order, without duplicates: `https://` URLs whose host is an IP
     * literal in the range that [WireAddress.kind] names (RFC1918 for `lan`, the tailnet for `tailscale`).
     * Anything else (another scheme, a name, a public address, a kind that does not match) is dropped.
     */
    fun endpointsOf(wire: List<WireAddress>): List<ServerEndpoint> = wire.mapNotNull(::endpointOf).distinct()

    fun wireOf(endpoint: ServerEndpoint): WireAddress = WireAddress(endpoint.baseUrl, endpoint.network.wire)

    private fun endpointOf(address: WireAddress): ServerEndpoint? {
        if (!address.url.startsWith(HTTPS, ignoreCase = true)) return null
        val endpoint = (ServerAddress.parse(address.url) as? AddressCheck.Ok)?.endpoint ?: return null
        return endpoint.takeIf { it.network.wire == address.kind }
    }

    private const val HTTPS = "https://"
}
