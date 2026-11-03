package io.github.justcallmesu.aristotle.data

/** The device token. Never printed: [toString] is redacted so it cannot reach a log by accident. */
class DeviceToken(val value: String) {
    override fun toString(): String = "DeviceToken(redacted)"
    override fun equals(other: Any?): Boolean = other is DeviceToken && other.value == value
    override fun hashCode(): Int = value.hashCode()
}

/**
 * What the phone keeps per PC (spec Q25: keyed per PC; v1 holds one). [pcId] is the device id
 * this PC issued, which is unique per PC and pairing. [fingerprint] is the PC's one pinned key, used for
 * every address in [baseUrls], LAN and Tailscale alike (spec Q49).
 */
data class PcCredentials(
    val pcId: String,
    val deviceId: String,
    val baseUrls: List<String>,
    val fingerprint: String,
    val token: DeviceToken,
)
