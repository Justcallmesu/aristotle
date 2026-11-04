package io.github.justcallmesu.aristotle.data

/** Which of a PC's addresses to try, in what order (T09). Pure, so the rule is unit-tested. */
object EndpointChoice {
    /**
     * The kind that fits the phone's network goes first: LAN addresses on Wi-Fi or another unmetered
     * network ([preferLan]), Tailscale addresses otherwise; the other kind always follows as the fallback.
     * D29: known-good addresses lead. [kindGood], the address of the preferred kind that worked last, goes first; then
     * [lastGood], the one that worked last of any kind, so a stale address of the preferred kind (an office LAN seen
     * from home) is never tried before the one known to work. The rest keep their stored order within their kind,
     * the fallback kind's own last good one ([otherGood]) first.
     */
    fun order(
        addresses: List<ServerEndpoint>,
        lastGood: ServerEndpoint?,
        preferLan: Boolean,
        kindGood: ServerEndpoint? = null,
        otherGood: ServerEndpoint? = null,
    ): List<ServerEndpoint> {
        val first = if (preferLan) Network.Lan else Network.Tailscale
        val known = addresses.distinct()
        val ownKind = (kindGood ?: lastGood)?.takeIf { it.network == first }
        val head = listOfNotNull(ownKind, lastGood).distinct().filter { it in known }
        return head + known.filter { it !in head }.sortedWith(compareBy({ it.network != first }, { it != otherGood }))
    }

    /** [order] for [pc]'s stored addresses, last-good records included. */
    fun order(pc: PcCredentials, preferLan: Boolean): List<ServerEndpoint> {
        val first = if (preferLan) Network.Lan else Network.Tailscale
        val other = if (preferLan) Network.Tailscale else Network.Lan
        return order(pc.addresses, pc.lastGood, preferLan, pc.lastGoodOf(first), pc.lastGoodOf(other))
    }

    /**
     * [order] with [found] (an address LAN discovery just verified with the pin, Q65) at the front, whether stored or
     * not: a one-shot candidate, stored only once a relay connects through it. Unchanged when there is none.
     */
    fun withFound(order: List<ServerEndpoint>, found: ServerEndpoint?): List<ServerEndpoint> =
        if (found == null) order else listOf(found) + order.filter { it != found }

    /**
     * True when the phone's network makes LAN addresses the better first try: Wi-Fi or Ethernet, or another
     * unmetered network that is not cellular. A VPN (Tailscale) carries the transports of the network under it.
     */
    fun prefersLan(wifi: Boolean, ethernet: Boolean, cellular: Boolean, notMetered: Boolean): Boolean =
        wifi || ethernet || (notMetered && !cellular)
}
