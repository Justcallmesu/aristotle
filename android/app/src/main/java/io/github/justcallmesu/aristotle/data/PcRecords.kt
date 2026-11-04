package io.github.justcallmesu.aristotle.data

import io.github.justcallmesu.aristotle.data.chat.ChatDrafts

/**
 * Everything this phone keeps about a paired PC, ended together (Q60, TS1): the credential entry (token, pin,
 * addresses), the PC's settings (Hermes profile, pins) and the drafts typed for its sessions.
 */
class PcRecords(
    private val credentials: CredentialStore,
    private val selection: ProfileSelection,
    private val pins: SessionPins,
    private val drafts: ChatDrafts,
) {
    /** Unpair: the active PC's entry, its settings and every draft. The PC keeps this phone until revoked there. */
    suspend fun unpair(pcId: String) {
        credentials.wipeActive()
        forgetSettings(pcId)
        drafts.clear()
    }

    /**
     * Re-pair succeeded with [paired]: it is stored and made active, and only then are [old]'s entry and settings
     * removed. A failed save throws and leaves [old] as it was. Drafts stay: they belong to sessions, most likely
     * on the same PC.
     */
    suspend fun replace(old: String, paired: PcCredentials) {
        credentials.save(paired, replacing = old)
        if (old != paired.pcId) forgetSettings(old)
    }

    private fun forgetSettings(pcId: String) {
        selection.forget(pcId)
        pins.forget(pcId)
    }
}
