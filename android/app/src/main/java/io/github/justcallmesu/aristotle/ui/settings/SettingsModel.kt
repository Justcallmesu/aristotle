package io.github.justcallmesu.aristotle.ui.settings

import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint

/** One stored PC address as Settings lists it (TS1). [lastWorked]: its kind's last working address. [inUse]: the link's now. */
data class AddressRow(val endpoint: ServerEndpoint, val lastWorked: Boolean, val inUse: Boolean)

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
                .map { AddressRow(it, lastWorked = it == last, inUse = it == online) }
        }
    }

    /** The address that names the PC: the protocol has no PC name, so the last working address, else the first stored. */
    fun pcLabel(pc: PcCredentials): String = (pc.lastGood ?: pc.addresses.firstOrNull())?.displayAddress.orEmpty()

    /** What the user runs on the PC so it forgets this phone too (protocol §11, `devices revoke`). The id is not a secret. */
    fun revokeCommand(deviceId: String): String = "aristotle-server devices revoke $deviceId"
}
