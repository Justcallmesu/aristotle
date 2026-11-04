package io.github.justcallmesu.aristotle.ui.settings

import io.github.justcallmesu.aristotle.data.AddressSource
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint

/**
 * One stored PC address as Settings lists it (TS1, Q73). [lastWorked]: its kind's last working address. [inUse]: the
 * link's now. [source] and [lastSuccessAt]: where it came from and when a relay last came up on it. [deletable]: false
 * for the only address left (the PC must stay reachable somehow). [remembered]: deleting it is remembered, because
 * another address of its kind stays (otherwise the PC's list may add it again).
 */
data class AddressRow(
    val endpoint: ServerEndpoint,
    val lastWorked: Boolean,
    val inUse: Boolean,
    val source: AddressSource = AddressSource.Earlier,
    val lastSuccessAt: Long? = null,
    val deletable: Boolean = true,
    val remembered: Boolean = false,
)

/** Pure rules behind the Connection & PCs section (Q60, TS1). */
object ConnectionRows {
    private val KIND_ORDER = listOf(Network.Lan, Network.Tailscale)

    /**
     * The PC's stored addresses grouped as the user thinks of them: Wi-Fi (LAN) first, then Tailscale. Within a kind,
     * the one that worked last leads, then the stored order (newest reported first, [io.github.justcallmesu.aristotle.data.PcAddresses.merge]).
     */
    fun addresses(pc: PcCredentials, link: LinkState): List<AddressRow> {
        val online = (link as? LinkState.Online)?.endpoint
        return KIND_ORDER.flatMap { kind ->
            val last = pc.lastGoodOf(kind)
            pc.addresses.filter { it.network == kind }
                .sortedBy { if (it == last) 0 else 1 }
                .map { endpoint ->
                    val info = pc.infoOf(endpoint)
                    AddressRow(endpoint, endpoint == last, endpoint == online, info.source, info.lastSuccessAt, deletable = pc.addresses.size > 1, remembered = pc.addresses.any { it != endpoint && it.network == endpoint.network })
                }
        }
    }

    /** The address that names the PC: the protocol has no PC name, so the last working address, else the first stored. */
    fun pcLabel(pc: PcCredentials): String = (pc.lastGood ?: pc.addresses.firstOrNull())?.displayAddress.orEmpty()

    /** "worked just now" under a minute, else a relative time (Q73); null when it never worked since Q73. */
    fun workedAgo(lastSuccessAt: Long?, now: Long): Worked? = when {
        lastSuccessAt == null -> null
        now - lastSuccessAt < MINUTE_MILLIS -> Worked.JustNow
        else -> Worked.Ago
    }

    enum class Worked { JustNow, Ago }

    private const val MINUTE_MILLIS = 60_000L

    /** What the user runs on the PC so it forgets this phone too (protocol §11, `devices revoke`). The id is not a secret. */
    fun revokeCommand(deviceId: String): String = "aristotle-server devices revoke $deviceId"
}
