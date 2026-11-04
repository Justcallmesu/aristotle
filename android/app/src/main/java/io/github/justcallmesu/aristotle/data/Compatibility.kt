package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The protocol this app speaks (docs/protocol.md §0). Bump with the server's `ProtocolVersion` on a breaking change. */
const val PHONE_PROTOCOL = 1

/** The `server` block of `GET /v1/me`; [version] is null for an old server that does not send one. */
data class ServerInfo(val version: String?, val protocol: Int, val minPhoneProtocol: Int) {
    companion object {
        /** A server without the block: version unknown, protocol 1. */
        val OLD = ServerInfo(version = null, protocol = 1, minPhoneProtocol = 1)
    }
}

enum class Compatibility { Compatible, ServerTooOld, AppTooOld }

/** Q63: a pure rule, so the sessions banner and the tests agree. */
fun compatibilityOf(server: ServerInfo, phoneProtocol: Int = PHONE_PROTOCOL): Compatibility = when {
    server.protocol < phoneProtocol -> Compatibility.ServerTooOld
    phoneProtocol < server.minPhoneProtocol -> Compatibility.AppTooOld
    else -> Compatibility.Compatible
}

/** The last server block read from `/v1/me` (on every connect); null before first contact. */
object ServerVersions {
    private val latest = MutableStateFlow<ServerInfo?>(null)
    val current: StateFlow<ServerInfo?> = latest.asStateFlow()

    fun record(info: ServerInfo) {
        latest.value = info
    }

    /** Unpair and re-pair (D48: the old PC's version must not linger), and tests. */
    fun clear() {
        latest.value = null
    }
}
