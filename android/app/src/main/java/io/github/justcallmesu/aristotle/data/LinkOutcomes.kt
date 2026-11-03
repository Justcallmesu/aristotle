package io.github.justcallmesu.aristotle.data

/**
 * Why the link to the PC ended, as a connection page (design.md §5). [details] is protocol data for
 * the mono block, never translated.
 */
sealed interface ConnectionIssue {
    val details: String

    data class HermesNotRunning(override val details: String) : ConnectionIssue
    data class HermesUpdated(override val details: String) : ConnectionIssue
    data class CertificateChanged(override val details: String) : ConnectionIssue
    data class Generic(override val details: String) : ConnectionIssue
}

/** What the app does after the link to the PC ends or fails. */
sealed interface LinkOutcome {
    /** The device token is no longer valid: wipe credentials, back to pairing. */
    data object Revoked : LinkOutcome
    data class Show(val issue: ConnectionIssue) : LinkOutcome
}

object LinkOutcomes {
    const val CLOSE_REVOKED = 4401
    const val CLOSE_HERMES_NOT_RUNNING = 4503
    const val CLOSE_HERMES_PROTOCOL_UNSUPPORTED = 4505
    private const val HTTP_UNAUTHORIZED = 401

    /** Relay close codes (protocol §12). Anything without its own page is the generic retry page. */
    fun ofClose(code: Int, reason: String): LinkOutcome {
        val details = closeDetails(code, reason)
        return when (code) {
            CLOSE_REVOKED -> LinkOutcome.Revoked
            CLOSE_HERMES_NOT_RUNNING -> LinkOutcome.Show(ConnectionIssue.HermesNotRunning(details))
            CLOSE_HERMES_PROTOCOL_UNSUPPORTED -> LinkOutcome.Show(ConnectionIssue.HermesUpdated(details))
            else -> LinkOutcome.Show(ConnectionIssue.Generic(details))
        }
    }

    /** The socket failed without a close frame; [httpStatus] is set when the upgrade was refused. */
    fun ofFailure(httpStatus: Int?, problem: ConnectionProblem): LinkOutcome = when {
        httpStatus == HTTP_UNAUTHORIZED -> LinkOutcome.Revoked
        problem == ConnectionProblem.CertificateChanged -> LinkOutcome.Show(ConnectionIssue.CertificateChanged(CERT_DETAILS))
        httpStatus != null -> LinkOutcome.Show(ConnectionIssue.Generic("/v1/ws HTTP $httpStatus"))
        else -> LinkOutcome.Show(ConnectionIssue.Generic(problemDetails(problem)))
    }

    /** `GET /v1/me` before opening the socket; null means go on. */
    fun ofMe(result: MeResult): LinkOutcome? = when (result) {
        is MeResult.Ok -> null
        MeResult.Unauthorized -> LinkOutcome.Revoked
        MeResult.Forbidden -> LinkOutcome.Show(ConnectionIssue.Generic("/v1/me HTTP 403"))
        is MeResult.Rejected -> LinkOutcome.Show(ConnectionIssue.Generic("/v1/me HTTP ${result.status}"))
        is MeResult.Failed -> ofFailure(null, result.problem)
    }

    fun closeDetails(code: Int, reason: String): String = if (reason.isEmpty()) "close $code" else "close $code $reason"

    private fun problemDetails(problem: ConnectionProblem): String = when (problem) {
        ConnectionProblem.Unreachable -> "no connection"
        ConnectionProblem.CertificateChanged -> CERT_DETAILS
    }

    private const val CERT_DETAILS = "certificate SPKI SHA-256 differs from the pinned fingerprint"
}
