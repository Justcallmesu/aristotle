package io.github.justcallmesu.aristotle.data

/** Which of a PC's addresses to try, in what order (T09). Pure, so the rule is unit-tested. */
object EndpointChoice {
    /**
     * The kind that fits the phone's network goes first: LAN addresses on Wi-Fi or another unmetered
     * network ([preferLan]), Tailscale addresses otherwise; the other kind always follows as the fallback.
     * Within a kind the address that worked last ([lastGood]) goes first, the rest keep their stored order.
     */
    fun order(addresses: List<ServerEndpoint>, lastGood: ServerEndpoint?, preferLan: Boolean): List<ServerEndpoint> {
        val first = if (preferLan) Network.Lan else Network.Tailscale
        return addresses.distinct().sortedWith(compareBy({ it.network != first }, { it != lastGood }))
    }

    /**
     * True when the phone's network makes LAN addresses the better first try: Wi-Fi or Ethernet, or another
     * unmetered network that is not cellular. A VPN (Tailscale) carries the transports of the network under it.
     */
    fun prefersLan(wifi: Boolean, ethernet: Boolean, cellular: Boolean, notMetered: Boolean): Boolean =
        wifi || ethernet || (notMetered && !cellular)
}
