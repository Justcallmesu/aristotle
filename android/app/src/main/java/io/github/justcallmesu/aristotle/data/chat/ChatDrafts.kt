package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.KeyValueStore
import java.time.Clock
import java.time.Instant

/**
 * What the user typed but did not send, per session (T13). Kept on this phone only, in its own preferences file
 * (excluded from every backup by `backup_rules.xml` and `data_extraction_rules.xml`), and never sent to the PC. An empty draft is removed, so
 * the file holds only the sessions with words waiting.
 */
class ChatDrafts(private val store: KeyValueStore, private val clock: () -> Instant = { Instant.now() }) {
    fun get(sessionId: String): String = store.get(key(sessionId)).orEmpty()

    fun put(sessionId: String, text: String) {
        if (text.isEmpty()) {
            store.remove(key(sessionId))
            store.remove(timestampKey(sessionId))
        } else {
            store.put(key(sessionId), text)
            store.put(timestampKey(sessionId), clock().toEpochMilli().toString())
        }
    }

    /** Prunes drafts untouched for 30 days or more, regardless of session list membership. */
    fun prune() {
        val now = clock()
        val thirtyDaysAgo = now.minusSeconds(30L * 24 * 60 * 60)
        store.keys().filter { it.startsWith(PREFIX) }.forEach { draftKey ->
            val timestampKey = draftKey.replace(PREFIX, TIMESTAMP_PREFIX)
            val timestamp = store.get(timestampKey)?.toLongOrNull()
                ?.let { Instant.ofEpochMilli(it) }
                ?: return@forEach // No timestamp, keep it (shouldn't happen, but be safe)
            if (timestamp.isBefore(thirtyDaysAgo)) {
                val sessionId = draftKey.removePrefix(PREFIX)
                store.remove(draftKey)
                store.remove(timestampKey)
            }
        }
    }

    private fun key(sessionId: String) = "$PREFIX$sessionId"
    private fun timestampKey(sessionId: String) = "$TIMESTAMP_PREFIX$sessionId"

    private companion object {
        const val PREFIX = "draft:"
        const val TIMESTAMP_PREFIX = "draft_time:"
    }
}
