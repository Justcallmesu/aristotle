package io.github.justcallmesu.aristotle

/**
 * How a notification opens a session's chat (T12 review MINOR-2). Its PendingIntent targets [ALIAS], a
 * non-exported activity-alias of [MainActivity] (manifest), so no other app can send it: another app reaches only
 * the exported launcher entry, whose intents name [MainActivity] itself and are ignored here. Pure.
 */
object OpenSession {
    const val ACTION = "io.github.justcallmesu.aristotle.OPEN_SESSION"

    /** The manifest's `<activity-alias android:name=".OpenSessionAlias">` (`exported="false"`). */
    const val ALIAS = "io.github.justcallmesu.aristotle.OpenSessionAlias"

    const val EXTRA_SESSION = "session"
    const val EXTRA_TITLE = "title"
    const val EXTRA_PROMPT = "prompt"
    private const val MAX_ID = 256

    /** The chat to open for an intent with [action] that reached the activity through [component] (its class name). */
    fun read(action: String?, component: String?, session: String?, title: String?, prompt: Boolean): OpenChat? {
        if (action != ACTION || component != ALIAS) return null
        val id = session?.takeIf { it.isNotEmpty() && it.length <= MAX_ID } ?: return null
        return OpenChat(id, title, prompt)
    }
}
