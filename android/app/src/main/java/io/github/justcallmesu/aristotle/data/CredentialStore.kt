package io.github.justcallmesu.aristotle.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Minimal key-value seam so the store can be tested without Android. */
interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class SharedPreferencesStore(private val prefs: SharedPreferences) : KeyValueStore {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.edit { putString(key, value) }
    override fun remove(key: String) = prefs.edit { remove(key) }
}

/**
 * Paired PCs, keyed per PC (Q25), and which one is active. The token is sealed by [codec];
 * [active] drives the app's top-level route (paired -> sessions, none -> pair).
 */
class CredentialStore(private val storage: KeyValueStore, private val codec: CredentialCodec) {
    private val activeState = MutableStateFlow(loadActive())
    val active: StateFlow<PcCredentials?> = activeState.asStateFlow()

    @Synchronized
    fun save(credentials: PcCredentials) {
        storage.put(entryKey(credentials.pcId), codec.encode(credentials))
        storage.put(ACTIVE_KEY, credentials.pcId)
        activeState.value = credentials
    }

    /** Removes the active PC's entry (revoked, or the user chose to pair again). */
    @Synchronized
    fun wipeActive() {
        storage.get(ACTIVE_KEY)?.let { storage.remove(entryKey(it)) }
        storage.remove(ACTIVE_KEY)
        activeState.value = null
    }

    private fun loadActive(): PcCredentials? {
        val pcId = storage.get(ACTIVE_KEY) ?: return null
        val decoded = storage.get(entryKey(pcId))?.let(codec::decode)
        if (decoded == null) {
            storage.remove(entryKey(pcId))
            storage.remove(ACTIVE_KEY)
        }
        return decoded
    }

    private fun entryKey(pcId: String) = "pc.$pcId"

    private companion object {
        const val ACTIVE_KEY = "active_pc"
    }
}
