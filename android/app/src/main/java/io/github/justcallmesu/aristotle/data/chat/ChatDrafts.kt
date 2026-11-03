package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.KeyValueStore

/**
 * What the user typed but did not send, per session (T13). Kept on this phone only, in its own preferences file
 * (excluded from every backup by `backup_rules.xml` and `data_extraction_rules.xml`), and never sent to the PC. An empty draft is removed, so
 * the file holds only the sessions with words waiting.
 */
class ChatDrafts(private val store: KeyValueStore) {
    fun get(sessionId: String): String = store.get(key(sessionId)).orEmpty()

    fun put(sessionId: String, text: String) {
        if (text.isEmpty()) store.remove(key(sessionId)) else store.put(key(sessionId), text)
    }

    private fun key(sessionId: String) = "draft:$sessionId"
}
