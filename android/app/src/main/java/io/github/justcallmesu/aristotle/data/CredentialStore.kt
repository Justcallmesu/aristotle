package io.github.justcallmesu.aristotle.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Minimal key-value seam so the store can be tested without Android. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)

    /** Every key held. */
    fun keys(): Set<String>

    /**
     * Puts (a value) and removes (null) [changes] as one write: a file-backed store writes them in one commit, so a
     * kill cannot leave half of them (TS1b). [durable]: on disk before this returns; call it off the main thread then.
     */
    fun edit(changes: Map<String, String?>, durable: Boolean = false) {
        changes.forEach { (key, value) -> if (value == null) remove(key) else put(key, value) }
    }
}

/** Commits synchronously: the store only calls it off the main thread. */
class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit(commit = true) { putString(key, value) }
    override fun remove(key: String) = prefs.edit(commit = true) { remove(key) }
    override fun keys(): Set<String> = prefs.all.keys.toSet()
    override fun edit(changes: Map<String, String?>, durable: Boolean) = prefs.edit(commit = true) {
        changes.forEach { (key, value) -> if (value == null) remove(key) else putString(key, value) }
    }
}

/** What the app knows about its pairing; drives the top-level route. */
sealed interface CredentialState {
    data object Loading : CredentialState
    data object Unpaired : CredentialState
    data class Paired(val credentials: PcCredentials) : CredentialState

    /** A pairing is stored but the Keystore could not open it right now; it is kept for a retry. */
    data object Unavailable : CredentialState
}

/**
 * Paired PCs, keyed per PC (Q25), and which one is active. The token is sealed by [codec]. All file
 * and Keystore work runs on [io], never on the caller's (main) thread. Addresses saved or deleted go to [events]
 * (the connection history, Q73); [clock] stamps an address's last success.
 */
class CredentialStore(
    private val storage: KeyValueStore,
    private val codec: CredentialCodec,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val events: ConnectionEvents = ConnectionEvents.None,
    private val clock: () -> Long = System::currentTimeMillis,
) : LinkMemory {
    private val mutex = Mutex()
    private val current = MutableStateFlow<CredentialState>(CredentialState.Loading)
    val state: StateFlow<CredentialState> = current.asStateFlow()

    /**
     * Reads the active PC. Call at start and again to retry after [CredentialState.Unavailable]. Returns true
     * when a stored pairing could never be used again and was removed, so the pair screen can say why (D28).
     */
    suspend fun load(): Boolean = locked {
        val (state, wiped) = readActive()
        current.value = state
        wiped
    }

    /**
     * Throws when the Keystore cannot seal the token; nothing is stored or removed then. [replacing]: the PC entry a
     * re-pair (TS1) replaces. It stays stored and active until the new pairing is written, and is removed in the same
     * commit, so a kill leaves either pairing, never an orphaned sealed token (TS1b). [commitIf] is asked last, under
     * the lock: false (the re-pair was cancelled meanwhile) writes nothing. Returns whether it was written.
     */
    suspend fun save(credentials: PcCredentials, replacing: String? = null, commitIf: () -> Boolean = { true }): Boolean = locked {
        val sealed = codec.encode(credentials)
        if (!commitIf()) return@locked false
        val changes = linkedMapOf<String, String?>(entryKey(credentials.pcId) to sealed, ACTIVE_KEY to credentials.pcId)
        if (replacing != null && replacing != credentials.pcId) changes[entryKey(replacing)] = null
        storage.edit(changes)
        current.value = CredentialState.Paired(credentials)
        true
    }

    /** Removes the active PC's entry (revoked, or the user chose to pair again). */
    suspend fun wipeActive() = locked {
        removeActive()
        current.value = CredentialState.Unpaired
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(io) { mutex.withLock { block() } }

    /**
     * Stores what the link learnt (T09): [fresh] merged into the stored addresses (T09b,
     * [CredentialCodec.withLink]), the one that worked and when (Q73). No Keystore call; the sealed token is kept.
     * Written only when something changed; ignored when [pcId] is no longer stored. Each new address is reported as
     * saved.
     */
    override suspend fun remember(pcId: String, fresh: List<ServerEndpoint>, lastGood: ServerEndpoint, found: ServerEndpoint?) = locked {
        val key = entryKey(pcId)
        val stored = storage.get(key) ?: return@locked
        val linked = codec.withLink(stored, fresh, lastGood, found, clock()) ?: return@locked
        if (linked.text != stored) storage.put(key, linked.text)
        publish(pcId, linked)
        linked.added.forEach { (endpoint, source) -> events.record(ConnectionEvent.Saved(endpoint, source)) }
    }

    /**
     * Deletes [endpoint] from [pcId]'s stored addresses (Q73, [CredentialCodec.withoutAddress]). False, and nothing
     * written, when it is not stored, is the only address left, or the PC is not stored.
     */
    suspend fun forgetAddress(pcId: String, endpoint: ServerEndpoint): Boolean = locked {
        val key = entryKey(pcId)
        val stored = storage.get(key) ?: return@locked false
        val linked = codec.withoutAddress(stored, endpoint) ?: return@locked false
        storage.put(key, linked.text)
        publish(pcId, linked)
        events.record(ConnectionEvent.Deleted(endpoint))
        true
    }

    private fun publish(pcId: String, linked: LinkedEntry) {
        current.update { state ->
            val paired = (state as? CredentialState.Paired)?.credentials
            if (paired?.pcId == pcId) {
                CredentialState.Paired(
                    paired.copy(
                        addresses = linked.addresses,
                        lastGood = linked.lastGood,
                        typed = linked.typed,
                        lastGoodByKind = linked.lastGoodByKind,
                        addressInfo = linked.addressInfo,
                    ),
                )
            } else {
                state
            }
        }
    }

    /**
     * The active PC and whether an entry was removed. An entry from an earlier build is upgraded in memory and
     * written back only once it decodes, so a pairing that must be wiped is never rewritten first (D28).
     */
    private fun readActive(): Pair<CredentialState, Boolean> {
        val pcId = storage.get(ACTIVE_KEY) ?: return CredentialState.Unpaired to false
        val key = entryKey(pcId)
        val stored = storage.get(key)
        val upgraded = stored?.let(codec::upgrade)
        val decoded = (upgraded ?: stored)?.let(codec::decode) ?: DecodeResult.Unreadable
        return when (decoded) {
            is DecodeResult.Ok -> {
                upgraded?.let { storage.put(key, it) }
                CredentialState.Paired(decoded.credentials) to false
            }
            DecodeResult.Unavailable -> CredentialState.Unavailable to false
            DecodeResult.Unreadable -> {
                removeActive()
                CredentialState.Unpaired to (stored != null)
            }
        }
    }

    /** The entry and the active pointer in one commit. */
    private fun removeActive() {
        val changes = linkedMapOf<String, String?>()
        storage.get(ACTIVE_KEY)?.let { changes[entryKey(it)] = null }
        changes[ACTIVE_KEY] = null
        storage.edit(changes)
    }

    private fun entryKey(pcId: String) = "pc.$pcId"

    private companion object {
        const val ACTIVE_KEY = "active_pc"
    }
}
