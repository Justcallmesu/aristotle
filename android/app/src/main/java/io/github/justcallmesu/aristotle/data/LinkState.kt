package io.github.justcallmesu.aristotle.data

/** Where the one link to the paired PC stands (T09). [LinkManager] owns it; screens observe it. */
sealed interface LinkState {
    /** No paired PC. */
    data object Idle : LinkState

    /** The first attempt since start, pairing or a network switch; nothing has failed yet. */
    data object Connecting : LinkState

    /** The relay is open and Hermes sent `gateway.ready`. [generation] changes with every new socket. */
    data class Online(val endpoint: ServerEndpoint, val generation: Long) : LinkState

    /**
     * No relay since [sinceMillis]. [address] is the address the cause is about (the first one tried, or the
     * one with a different certificate). [nextAttemptAtMillis] is when the next round starts; null while a
     * round is running ([trying]). [details] is protocol data for the mono block, never translated.
     * [waiting]: a superseded socket (D36): no quick retry; the next round starts on a network change, the app
     * coming to the front or Retry now, else at [nextAttemptAtMillis], the slow backstop (T13b, D39).
     */
    data class Offline(
        val sinceMillis: Long,
        val cause: OfflineCause,
        val details: String,
        val address: ServerEndpoint?,
        val attempt: Int,
        val nextAttemptAtMillis: Long?,
        val waiting: Boolean = false,
    ) : LinkState {
        val trying: Boolean get() = nextAttemptAtMillis == null && !waiting
    }

    /** The user stopped the link from the background notification; it starts again when the app is opened. */
    data object Stopped : LinkState

    /** The device token was refused (`4401`, HTTP 401): the pairing is wiped, no retry. */
    data object Revoked : LinkState

    /**
     * `4503`: aristotle-server answered on [address], Hermes is not running. Retried quietly every 10 s. The server
     * itself still serves what needs no Hermes there (stocks, protocol §20).
     */
    data class HermesDown(val details: String, val address: ServerEndpoint) : LinkState

    /** `4505`: Hermes speaks a protocol this server (on [address]) does not know. No retry until the user checks again. */
    data class HermesUpdated(val details: String, val address: ServerEndpoint) : LinkState
}

/**
 * Where aristotle-server answered last, for calls that need only the server and the device token, not Hermes (stocks,
 * protocol §20): the relay's address while online, and the address that closed with `4503` / `4505` while Hermes is
 * down or updated. Null in every other state (nothing answered, a changed certificate, no pairing).
 */
val LinkState.serverEndpoint: ServerEndpoint?
    get() = when (this) {
        is LinkState.Online -> endpoint
        is LinkState.HermesDown -> address
        is LinkState.HermesUpdated -> address
        else -> null
    }

enum class OfflineCause {
    /** No address answered. */
    Unreachable,

    /** Only tailnet addresses could help (not on Wi-Fi, or the PC has no LAN address) and none answered. */
    TailscaleMayBeOff,

    /** An address answered with a certificate other than the pinned one, and no address worked. */
    CertificateChanged,

    /** The PC answered, then the relay ended (a close code, or no `gateway.ready`). */
    ConnectionLost,

    /** Something in the app failed unexpectedly (a bug, a store that throws); the link tries again. */
    AppError,

    /**
     * `4409`: this phone opened a newer relay on the PC's other listener (LAN vs Tailscale), and the server closed
     * this one. Only the slow backstop retries it by itself, so two of the device's sockets never take turns closing
     * each other quickly (D36, T13b).
     */
    Superseded,
}

/** How one round over a PC's addresses ended. */
sealed interface RoundEnd {
    data object Revoked : RoundEnd
    data class HermesDown(val details: String, val address: ServerEndpoint) : RoundEnd
    data class HermesUpdated(val details: String, val address: ServerEndpoint) : RoundEnd

    /** The phone's network changed while online: connect again at once, best address first. */
    data object Switch : RoundEnd
    data class Lost(val cause: OfflineCause, val details: String, val address: ServerEndpoint?) : RoundEnd

    /** `4409`: a newer socket of this device replaced this one; do not reconnect this socket (protocol §12). */
    data class Superseded(val details: String, val address: ServerEndpoint?) : RoundEnd
}

/** Why one address did not lead to the PC (the round goes on to the next address). */
enum class MissKind { Unreachable, CertificateChanged, Refused }

data class Miss(val endpoint: ServerEndpoint, val kind: MissKind, val details: String)

/** What relay closes and failures mean for the link (protocol §12 close codes). Pure. */
object LinkEnds {
    const val CLOSE_REVOKED = 4401
    const val CLOSE_HERMES_NOT_RUNNING = 4503
    const val CLOSE_HERMES_PROTOCOL_UNSUPPORTED = 4505
    const val CLOSE_SUPERSEDED = 4409
    const val HTTP_UNAUTHORIZED = 401

    /**
     * A close frame from the server: `4401` ends the pairing, `4503` and `4505` have their own pages, `4409` is not
     * reconnected by itself (D36), every other code (`1000`, `1001`, `1011`, `1013`, `4502`, …) is a lost connection
     * retried with backoff.
     */
    fun ofClose(code: Int, reason: String, address: ServerEndpoint): RoundEnd {
        val details = closeDetails(code, reason)
        return when (code) {
            CLOSE_REVOKED -> RoundEnd.Revoked
            CLOSE_HERMES_NOT_RUNNING -> RoundEnd.HermesDown(details, address)
            CLOSE_HERMES_PROTOCOL_UNSUPPORTED -> RoundEnd.HermesUpdated(details, address)
            CLOSE_SUPERSEDED -> RoundEnd.Superseded(details, address)
            else -> RoundEnd.Lost(OfflineCause.ConnectionLost, details, address)
        }
    }

    /** The socket failed without a close frame after it was open. */
    fun ofFailure(httpStatus: Int?, problem: ConnectionProblem, address: ServerEndpoint?): RoundEnd = when {
        httpStatus == HTTP_UNAUTHORIZED -> RoundEnd.Revoked
        else -> RoundEnd.Lost(OfflineCause.ConnectionLost, failureDetails(httpStatus, problem), address)
    }

    /** A failure before the relay opened: this address is a miss, or (401) the token is gone. */
    fun missOf(endpoint: ServerEndpoint, httpStatus: Int?, problem: ConnectionProblem): Miss? = when {
        httpStatus == HTTP_UNAUTHORIZED -> null
        httpStatus != null -> Miss(endpoint, MissKind.Refused, "/v1/ws HTTP $httpStatus")
        problem == ConnectionProblem.CertificateChanged -> Miss(endpoint, MissKind.CertificateChanged, CERT_DETAILS)
        else -> Miss(endpoint, MissKind.Unreachable, "unreachable")
    }

    /**
     * The round ended without reaching the PC on any address. A certificate mismatch wins (the user must
     * decide); "Tailscale may be off" when only tailnet addresses could help, none answered and no VPN is up
     * on the phone (with one up, Tailscale is most likely on and the laptop itself does not answer).
     */
    fun offline(misses: List<Miss>, pcHasLan: Boolean, preferLan: Boolean, vpnUp: Boolean): RoundEnd.Lost {
        val certificate = misses.firstOrNull { it.kind == MissKind.CertificateChanged }
        val tailnet = misses.filter { it.endpoint.network == Network.Tailscale }
        val onlyTailnetCouldHelp = !pcHasLan || !preferLan
        val cause = when {
            certificate != null -> OfflineCause.CertificateChanged
            !vpnUp && onlyTailnetCouldHelp && tailnet.isNotEmpty() && tailnet.all { it.kind == MissKind.Unreachable } ->
                OfflineCause.TailscaleMayBeOff
            else -> OfflineCause.Unreachable
        }
        val details = misses.joinToString("\n") { "${it.endpoint.displayAddress} ${it.details}" }.ifEmpty { "no stored address" }
        return RoundEnd.Lost(cause, details, certificate?.endpoint ?: misses.firstOrNull()?.endpoint)
    }

    /** A round failed with an error nobody expected; only its type goes into the details (no message text). */
    fun unexpected(error: Exception): RoundEnd.Lost =
        RoundEnd.Lost(OfflineCause.AppError, "unexpected ${error.javaClass.simpleName}", address = null)

    fun closeDetails(code: Int, reason: String): String = if (reason.isEmpty()) "close $code" else "close $code $reason"

    private fun failureDetails(httpStatus: Int?, problem: ConnectionProblem): String = when {
        httpStatus != null -> "/v1/ws HTTP $httpStatus"
        problem == ConnectionProblem.CertificateChanged -> CERT_DETAILS
        else -> "connection lost"
    }

    const val CERT_DETAILS = "certificate SPKI SHA-256 differs from the pinned fingerprint"
}
