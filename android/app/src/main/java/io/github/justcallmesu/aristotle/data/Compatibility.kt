package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

/**
 * The last server block read from `/v1/me` (on every connect), per PC record id. A reply is written under the PC it
 * was asked of and readers look up the active PC, so a late reply from an old PC, or a clear that lands after the
 * new PC's first reply, cannot show the wrong version (batch-46 review).
 */
object ServerVersions {
    private val byPc = MutableStateFlow<Map<String, ServerInfo>>(emptyMap())
    val all: StateFlow<Map<String, ServerInfo>> = byPc.asStateFlow()

    fun record(pcId: String, info: ServerInfo) {
        byPc.update { it + (pcId to info) }
    }

    fun of(pcId: String): ServerInfo? = byPc.value[pcId]

    /** Unpair, or re-pair to another PC (D48): only that PC's entry goes. */
    fun clear(pcId: String) {
        byPc.update { it - pcId }
    }

    /** Every entry: forgetting the active PC with no id at hand, and tests. */
    fun clearAll() {
        byPc.value = emptyMap()
    }
}
