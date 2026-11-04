package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The Hermes profile the phone lists and watches (spec Q25, T14), remembered per PC. null = the relay's launch
 * profile (calls without `profile`); otherwise the profile's name, as calls send it. Thread-safe.
 */
class ProfileSelection(private val store: KeyValueStore) {
    private var pcId: String? = null
    private val current = MutableStateFlow<String?>(null)
    val selected: StateFlow<String?> = current.asStateFlow()
    private val unserved = MutableStateFlow<String?>(null)

    /** The remembered profile the PC stopped serving, until the user has been told ([noticeShown]); null otherwise. */
    val fellBackFrom: StateFlow<String?> = unserved.asStateFlow()

    /** The active PC changed (or none is paired): its remembered profile becomes the selection. */
    @Synchronized
    fun follow(pcId: String?) {
        if (pcId == this.pcId) return
        this.pcId = pcId
        current.value = pcId?.let { store.get(key(it)) }?.takeIf { it.isNotEmpty() }
    }

    /** The user picked [profile] on the active PC; remembered for the next start. */
    @Synchronized
    fun select(profile: String?) {
        val pc = pcId ?: return
        if (profile == null) store.remove(key(pc)) else store.put(key(pc), profile)
        current.value = profile
    }

    /**
     * The PC no longer serves [profile] (`profile_mismatch`): back to the launch profile, forgotten, so the listing and
     * the background watcher work again. Only if [profile] is still the selection; false when it was not.
     */
    @Synchronized
    fun fallBack(profile: String): Boolean {
        if (current.value != profile) return false
        select(null)
        unserved.value = profile
        return true
    }

    /** [pcId] was unpaired or replaced (TS1): its remembered profile goes ([durable]: on disk before it returns); the active one falls back to the launch profile. */
    @Synchronized
    fun forget(pcId: String, durable: Boolean = false) {
        store.edit(mapOf(key(pcId) to null), durable)
        if (pcId == this.pcId) current.value = null
    }

    /** The user was told about [fellBackFrom]. */
    fun noticeShown() {
        unserved.value = null
    }

    private fun key(pcId: String) = "profile.$pcId"
}

/**
 * Pins kept on this phone only (T14). Hermes has a pin (`session.mutate sidebar {pinned}`,
 * `hermes_state_mutations.py:49-55`), but the relay cannot read it back: `session.list` rows carry no `pinned`
 * (`tui_gateway/contracts/canonical_sessions.py:171-181`), and the Desktop itself keeps its pins locally and mirrors
 * them over REST (`apps/desktop/src/store/session-pin-sync.ts:1-21`), which the relay does not carry. So a pin here is
 * this phone's, per PC and profile, and says so in the UI.
 */
class SessionPins(private val store: KeyValueStore) {
    fun of(pcId: String, profile: String?): Set<String> = read(key(pcId, profile))

    /** Pins or unpins [sessionId]; returns the profile's pins after the change. */
    @Synchronized
    fun set(pcId: String, profile: String?, sessionId: String, pinned: Boolean): Set<String> {
        val key = key(pcId, profile)
        val next = if (pinned) read(key) + sessionId else read(key) - sessionId
        if (next.isEmpty()) store.remove(key) else store.put(key, HermesJson.encodeToString(IDS, next.toList()))
        return next
    }

    /** [pcId] was unpaired or replaced (TS1): every profile's pins for it go, in one write ([durable]: on disk first). */
    @Synchronized
    fun forget(pcId: String, durable: Boolean = false) {
        val prefix = "pins.$pcId."
        store.edit(store.keys().filter { it.startsWith(prefix) }.associateWith { null }, durable)
    }

    private fun read(key: String): Set<String> =
        store.get(key)?.let { runCatching { HermesJson.decodeFromString(IDS, it) }.getOrNull() }?.toCollection(LinkedHashSet()).orEmpty()

    // The launch profile has no name here (null); "\u0000" cannot be a profile name.
    private fun key(pcId: String, profile: String?) = "pins.$pcId.${profile ?: "\u0000"}"

    private companion object {
        val IDS = ListSerializer(String.serializer())
    }
}
