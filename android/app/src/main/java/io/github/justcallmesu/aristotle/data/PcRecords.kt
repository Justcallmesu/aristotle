package io.github.justcallmesu.aristotle.data

import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Everything this phone keeps about a paired PC, ended together (Q60, TS1): the credential entry (token, pin,
 * addresses), the PC's settings (Hermes profile, pins) and the drafts typed for its sessions. Disk writes run on [io].
 */
class PcRecords(
    private val credentials: CredentialStore,
    private val selection: ProfileSelection,
    private val pins: SessionPins,
    private val drafts: ChatDrafts,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /**
     * Unpair: the PC's settings and every draft first, on disk before the credential entry goes (one commit), so a
     * kill in between leaves the pairing without its drafts, never drafts waiting for a re-pair (TS1b). The PC keeps
     * this phone until revoked there.
     */
    suspend fun unpair(pcId: String) {
        withContext(io) {
            forgetSettings(pcId, durable = true)
            drafts.clear(durable = true)
        }
        credentials.wipeActive()
    }

    /**
     * Re-pair succeeded with [paired]: it is stored and made active and [old]'s entry removed in one commit, and only
     * then are [old]'s settings removed. A failed save throws and leaves [old] as it was; [wanted] false (the re-pair
     * was cancelled) writes nothing. Drafts stay: they belong to sessions, most likely on the same PC. Returns
     * whether [paired] was stored.
     */
    suspend fun replace(old: String, paired: PcCredentials, wanted: () -> Boolean = { true }): Boolean {
        if (!credentials.save(paired, replacing = old, commitIf = wanted)) return false
        if (old != paired.pcId) withContext(io) { forgetSettings(old, durable = true) }
        return true
    }

    private fun forgetSettings(pcId: String, durable: Boolean) {
        selection.forget(pcId, durable)
        pins.forget(pcId, durable)
    }
}
