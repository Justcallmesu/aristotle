package io.github.justcallmesu.aristotle.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Minimal key-value seam so the store can be tested without Android. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

/** Commits synchronously: the store only calls it off the main thread. */
class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit(commit = true) { putString(key, value) }
    override fun remove(key: String) = prefs.edit(commit = true) { remove(key) }
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
 * and Keystore work runs on [io], never on the caller's (main) thread.
 */
class CredentialStore(
    private val storage: KeyValueStore,
    private val codec: CredentialCodec,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private val current = MutableStateFlow<CredentialState>(CredentialState.Loading)
    val state: StateFlow<CredentialState> = current.asStateFlow()

    /** Reads the active PC. Call at start and again to retry after [CredentialState.Unavailable]. */
    suspend fun load() = locked { current.value = readActive() }

    /** Throws when the Keystore cannot seal the token; nothing is stored then. */
    suspend fun save(credentials: PcCredentials) = locked {
        val sealed = codec.encode(credentials)
        storage.put(entryKey(credentials.pcId), sealed)
        storage.put(ACTIVE_KEY, credentials.pcId)
        current.value = CredentialState.Paired(credentials)
    }

    /** Removes the active PC's entry (revoked, or the user chose to pair again). */
    suspend fun wipeActive() = locked {
        removeActive()
        current.value = CredentialState.Unpaired
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(io) { mutex.withLock { block() } }

    private fun readActive(): CredentialState {
        val pcId = storage.get(ACTIVE_KEY) ?: return CredentialState.Unpaired
        val entry = storage.get(entryKey(pcId))
        val decoded = if (entry == null) DecodeResult.Unreadable else codec.decode(entry)
        return when (decoded) {
            is DecodeResult.Ok -> CredentialState.Paired(decoded.credentials)
            DecodeResult.Unavailable -> CredentialState.Unavailable
            DecodeResult.Unreadable -> {
                removeActive()
                CredentialState.Unpaired
            }
        }
    }

    private fun removeActive() {
        storage.get(ACTIVE_KEY)?.let { storage.remove(entryKey(it)) }
        storage.remove(ACTIVE_KEY)
    }

    private fun entryKey(pcId: String) = "pc.$pcId"

    private companion object {
        const val ACTIVE_KEY = "active_pc"
    }
}
