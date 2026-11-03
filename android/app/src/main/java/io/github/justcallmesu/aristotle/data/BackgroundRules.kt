package io.github.justcallmesu.aristotle.data

/** When the background link service (T10) runs. Pure. */
object BackgroundPolicy {
    /**
     * True while the service should keep the link alive: the user wants it, has not pressed Stop, and the
     * paired PC can still be reached by retrying. A process restarted by the system is still reading the
     * pairing ([CredentialState.Loading]); that keeps the service until the answer is known.
     */
    fun keepRunning(credentials: CredentialState, link: LinkState, keepConnected: Boolean, stoppedByUser: Boolean): Boolean =
        when {
            !keepConnected || stoppedByUser -> false
            credentials is CredentialState.Loading -> true
            credentials !is CredentialState.Paired -> false
            // Revoked wipes the pairing, Hermes updated waits for the user, Stopped is the user's: none retries.
            else -> link != LinkState.Revoked && link !is LinkState.HermesUpdated && link != LinkState.Stopped
        }
}

/** What the service's persistent notification says (T10). [pc] is the PC's address: the protocol has no PC name. */
sealed interface ConnectionNotice {
    data class Connected(val pc: String, val network: Network) : ConnectionNotice
    data class Reconnecting(val pc: String) : ConnectionNotice
    data class OfflineSince(val sinceMillis: Long) : ConnectionNotice
    data class HermesDown(val pc: String) : ConnectionNotice

    companion object {
        /** Null for the states that end the service ([BackgroundPolicy]). */
        fun of(state: LinkState, pc: String): ConnectionNotice? = when (state) {
            is LinkState.Online -> Connected(state.endpoint.hostForUrl, state.endpoint.network)
            LinkState.Idle, LinkState.Connecting -> Reconnecting(pc)
            is LinkState.Offline -> OfflineSince(state.sinceMillis)
            is LinkState.HermesDown -> HermesDown(pc)
            LinkState.Revoked, LinkState.Stopped, is LinkState.HermesUpdated -> null
        }

        /** The address the notification names before the link has reported one. */
        fun pcLabel(pc: PcCredentials): String = (pc.lastGood ?: pc.addresses.firstOrNull())?.hostForUrl.orEmpty()
    }
}

/** Xiaomi, Redmi and POCO phones run MIUI/HyperOS, which stops background apps unless told not to (spec Q27). */
object DeviceMaker {
    private val XIAOMI_FAMILY = setOf("xiaomi", "redmi", "poco")

    /** From `Build.MANUFACTURER` and `Build.BRAND` (a POCO reports manufacturer `Xiaomi`, brand `POCO`). */
    fun isXiaomiFamily(manufacturer: String?, brand: String?): Boolean =
        listOf(manufacturer, brand).any { it?.trim()?.lowercase() in XIAOMI_FAMILY }
}
