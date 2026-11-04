package io.github.justcallmesu.aristotle.data

/** The device token. Never printed: [toString] is redacted so it cannot reach a log by accident. */
class DeviceToken(val value: String) {
    override fun toString(): String = "DeviceToken(redacted)"
    override fun equals(other: Any?): Boolean = other is DeviceToken && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

/**
 * What the phone keeps per PC (spec Q25: keyed per PC; v1 holds one). [pcId] is the device id this PC
 * issued, which is unique per PC and pairing. [addresses] are every listener the PC reported (LAN and
 * Tailscale, T09), never empty, the ones it reported last first ([PcAddresses.merge]); [lastGood] is the one
 * the last relay used, and [lastGoodByKind] the last that worked of each kind (D29); [typed] is the one the user
 * typed at pairing (for a pairing from a T09 build, the first last-good address stands in, D29), which the stored
 * list never drops. [fingerprint] is the PC's one pinned key, used for every address alike (spec Q49).
 * [addressInfo] says where each address came from and when it last worked (Q73); an address it does not name is
 * [AddressInfo.EARLIER] ([infoOf]), so the map holds only what differs from that.
 */
data class PcCredentials(
    val pcId: String,
    val deviceId: String,
    val addresses: List<ServerEndpoint>,
    val fingerprint: String,
    val token: DeviceToken,
    val lastGood: ServerEndpoint? = null,
    val typed: ServerEndpoint? = null,
    val lastGoodByKind: Map<Network, ServerEndpoint> = emptyMap(),
    val addressInfo: Map<ServerEndpoint, AddressInfo> = emptyMap(),
) {
    fun infoOf(endpoint: ServerEndpoint): AddressInfo = addressInfo[endpoint] ?: AddressInfo.EARLIER

    /** The address of [kind] that worked last: the kind's own record, else [lastGood] when it is of that kind. */
    fun lastGoodOf(kind: Network): ServerEndpoint? = lastGoodByKind[kind] ?: lastGood?.takeIf { it.network == kind }

    /** The pin as bytes; [fingerprint] is checked when the entry is read or paired. */
    val pin: ByteArray get() = requireNotNull(Fingerprint.parse(fingerprint)) { "stored fingerprint is not a pin" }
}

/** Where a stored address came from (Q73). [wire] is its stored name. */
enum class AddressSource(val wire: String) {
    /** Typed by the user at pairing. */
    Typed("typed"),

    /** From the QR code or the PC's answer to the pairing. */
    Pairing("pairing"),

    /** From the PC's own list (`GET /v1/addresses`). */
    ServerList("server"),

    /** Found by LAN discovery and verified with the pin (Q65). */
    FoundOnWifi("found"),

    /** Stored by a build before Q73, which did not record a source. */
    Earlier("earlier"),
    ;

    companion object {
        fun ofWire(wire: String?): AddressSource = entries.firstOrNull { it.wire == wire } ?: Earlier
    }
}

/** What the phone knows about one stored address: [source], and [lastSuccessAt] when a relay last came up on it (ms). */
data class AddressInfo(val source: AddressSource, val lastSuccessAt: Long? = null) {
    companion object {
        val EARLIER = AddressInfo(AddressSource.Earlier)
    }
}
