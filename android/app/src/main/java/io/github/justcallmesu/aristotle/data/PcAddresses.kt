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

    /** At most this many addresses are stored per PC. */
    const val MAX_STORED = 8

    /**
     * The stored list after a refresh (T09b): [fresh] (what the PC serves now) first, then every [known]
     * address it did not report, in their stored order, one entry per URL (the fresh entry wins). An address
     * is never dropped because the PC does not serve it right now: the laptop moves between home and office.
     * Past [cap] the last entries go first, which are the ones unseen longest; [keep] (the typed and the
     * last good address) always stay.
     */
    fun merge(
        fresh: List<ServerEndpoint>,
        known: List<ServerEndpoint>,
        keep: Collection<ServerEndpoint?>,
        cap: Int = MAX_STORED,
    ): List<ServerEndpoint> {
        val merged = (fresh + known).distinctBy { it.baseUrl }.toMutableList()
        val kept = keep.mapNotNullTo(HashSet()) { it?.baseUrl }
        while (merged.size > cap) {
            val last = merged.indexOfLast { it.baseUrl !in kept }
            if (last < 0) break
            merged.removeAt(last)
        }
        return merged
    }

    private const val HTTPS = "https://"
}
