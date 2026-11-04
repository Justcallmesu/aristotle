package io.github.justcallmesu.aristotle.data

/**
 * LAN addresses whose relay keeps dropping after `gateway.ready` (TL1e, TL1c review MINOR-A.2). An on-link host that
 * forwards the PC's port passes the pin (it only carries ciphertext) and can then drop each relay right after it came
 * up; without this the link would go back to it at once every time and never try Tailscale on that Wi-Fi. [drops]
 * drops within [windowMillis] on the same Wi-Fi demote the address below the Tailscale ones for [demoteMillis], and a
 * relay coming up on an address that dropped within the window no longer resets the backoff. Pure; the caller passes
 * the time and the Wi-Fi ([NetworkWatch.localNetworks]). Thread-safe.
 */
class LanFlaps(
    private val drops: Int = 3,
    private val windowMillis: Long = 5 * 60_000L,
    private val demoteMillis: Long = 15 * 60_000L,
) {
    private data class Key(val endpoint: ServerEndpoint, val wifi: Set<Long>)

    private val recent = HashMap<Key, ArrayDeque<Long>>()
    private val demotedUntil = HashMap<Key, Long>()

    /** A relay on [endpoint] reached `gateway.ready` on [wifi] and was then lost at [now]. Only LAN addresses count. */
    @Synchronized
    fun dropped(endpoint: ServerEndpoint, wifi: Set<Long>, now: Long) {
        if (endpoint.network != Network.Lan) return
        prune(now)
        val key = Key(endpoint, wifi)
        val times = recent.getOrPut(key) { ArrayDeque() }.apply { addLast(now) }
        if (times.size >= drops) {
            demotedUntil[key] = now + demoteMillis
            times.clear()
        }
    }

    /** [endpoint] dropped after `gateway.ready` on [wifi] within the window, or is demoted: its relay coming up proves little. */
    @Synchronized
    fun unsettled(endpoint: ServerEndpoint, wifi: Set<Long>, now: Long): Boolean {
        prune(now)
        val key = Key(endpoint, wifi)
        return recent[key]?.isNotEmpty() == true || key in demotedUntil
    }

    @Synchronized
    fun demoted(endpoint: ServerEndpoint, wifi: Set<Long>, now: Long): Boolean {
        prune(now)
        return Key(endpoint, wifi) in demotedUntil
    }

    /** [order] with every demoted address moved to the end, below the Tailscale ones; the rest keep their order. */
    @Synchronized
    fun order(order: List<ServerEndpoint>, wifi: Set<Long>, now: Long): List<ServerEndpoint> {
        prune(now)
        val (down, up) = order.partition { Key(it, wifi) in demotedUntil }
        return up + down
    }

    private fun prune(now: Long) {
        demotedUntil.values.removeAll { it <= now }
        val iterator = recent.values.iterator()
        while (iterator.hasNext()) {
            val times = iterator.next()
            while (times.isNotEmpty() && now - times.first() >= windowMillis) times.removeFirst()
            if (times.isEmpty()) iterator.remove()
        }
    }
}
