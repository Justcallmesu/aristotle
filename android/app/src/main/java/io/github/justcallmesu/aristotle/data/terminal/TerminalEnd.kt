package io.github.justcallmesu.aristotle.data.terminal

import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.HermesJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** How one terminal ended, as the screen says it (docs/protocol.md §14 close codes). The phone never reopens by itself. */
sealed interface TerminalEnd {
    /** The shell exited; [code] is its status from the server's `exit` message, null when that message did not come. */
    data class Exited(val code: Int?) : TerminalEnd

    /** `4401` or HTTP 401: the phone's pairing is gone. */
    data object Revoked : TerminalEnd

    /** `4403` or HTTP 403 `terminal_disabled`: switched off on the PC. The entry hides until `/v1/me` says otherwise. */
    data object TurnedOff : TerminalEnd

    /** `4403` or HTTP 403 `terminal_not_granted`: this phone's grant was removed (or never given). */
    data object NotGranted : TerminalEnd

    /** `1006`, `1011 pong_timeout`, a network switch: the shell is gone (Q55, no reattach). */
    data object ConnectionChanged : TerminalEnd

    /** `4408 idle_timeout`. */
    data object Idle : TerminalEnd

    /** `4408 max_session`. */
    data object MaxLength : TerminalEnd

    /** `4430`: two terminals already open on this phone, or four on the PC. */
    data object TooMany : TerminalEnd

    /** `1001`: aristotle-server is stopping. */
    data object ServerStopping : TerminalEnd

    /** `1011 shell_start_failed`. */
    data object ShellFailed : TerminalEnd

    /** The PC refused before the upgrade for another reason, or the certificate changed; [details] is protocol text. */
    data class Refused(val details: String) : TerminalEnd

    /** Not reached at all. */
    data object Unreachable : TerminalEnd

    /** The user closed it, or left the screen. */
    data object ClosedHere : TerminalEnd

    /** Any other close; [details] is the code and reason (protocol data, never terminal content). */
    data class Other(val details: String) : TerminalEnd

    /** Hide the terminal entry after this end (4403). */
    val hidesEntry: Boolean get() = this == TurnedOff || this == NotGranted

    /** A new terminal may be offered (Reopen); not after a revoke or a switch-off. */
    val canReopen: Boolean get() = this != Revoked && !hidesEntry

    companion object {
        const val NORMAL = 1000
        const val GOING_AWAY = 1001
        const val ABNORMAL = 1006
        const val INTERNAL = 1011
        const val REVOKED = 4401
        const val FORBIDDEN = 4403
        const val TIME_LIMIT = 4408
        const val TOO_MANY = 4430
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403

        /** A close frame from the server; [exitCode] is from the `exit` message that came before it, if any. */
        fun ofClose(code: Int, reason: String, exitCode: Int?): TerminalEnd = when (code) {
            NORMAL -> Exited(exitCode)
            GOING_AWAY -> ServerStopping
            ABNORMAL -> ConnectionChanged
            INTERNAL -> if (reason == "shell_start_failed") ShellFailed else ConnectionChanged
            REVOKED -> Revoked
            FORBIDDEN -> if (reason == "terminal_not_granted") NotGranted else TurnedOff
            TIME_LIMIT -> if (reason == "max_session") MaxLength else Idle
            TOO_MANY -> TooMany
            else -> Other(if (reason.isEmpty()) "close $code" else "close $code $reason")
        }

        /**
         * No close frame. Before the upgrade: 401, the 403s with a JSON `error` ([errorBody]; the listener's peer gate
         * answers 403 with the text `forbidden`), other statuses; after it ([opened]): the connection broke (1006).
         */
        fun ofFailure(opened: Boolean, httpStatus: Int?, errorBody: String?, problem: ConnectionProblem): TerminalEnd = when {
            httpStatus == HTTP_UNAUTHORIZED -> Revoked
            httpStatus == HTTP_FORBIDDEN && errorOf(errorBody) == "terminal_disabled" -> TurnedOff
            httpStatus == HTTP_FORBIDDEN && errorOf(errorBody) == "terminal_not_granted" -> NotGranted
            httpStatus != null -> Refused("/v1/terminal HTTP $httpStatus")
            problem == ConnectionProblem.CertificateChanged -> Refused("certificate SPKI SHA-256 differs from the pinned fingerprint")
            opened -> ConnectionChanged
            else -> Unreachable
        }

        /** `{"error":"…"}` (protocol §11.2 error bodies); null for any other body, the peer gate's `forbidden` included. */
        private fun errorOf(body: String?): String? = body?.let {
            runCatching { (HermesJson.parseToJsonElement(it).jsonObject["error"] as? JsonPrimitive)?.content }.getOrNull()
        }
    }
}
