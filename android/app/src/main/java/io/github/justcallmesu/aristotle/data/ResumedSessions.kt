package io.github.justcallmesu.aristotle.data

/**
 * The sessions the current relay socket has resumed (protocol §4): a socket gets a session's events only
 * after it resumed it, and one subscription per session and socket is enough. Shared by [SessionWatcher]
 * and the chat screen (T11), so neither resumes a session the other already did. Keyed by the link's
 * [LinkState.Online.generation]: a new socket starts empty. Thread-safe.
 *
 * Session ids are unique only per profile, and the relay socket is host-scoped (protocol §3): an entry is a
 * (profile, session id) pair, `profile` null for the launch profile (a call without `profile`).
 */
class ResumedSessions {
    private data class Ref(val profile: String?, val id: String)

    private var generation: Long? = null
    private val resumed = HashSet<Ref>()
    private val chats = HashMap<String, Int>()

    /**
     * Marks [id] of [profile] resumed on socket [generation]. False when it already was (or a resume is in flight),
     * and for an older socket than the newest one seen (that caller is about to be cancelled).
     */
    @Synchronized
    fun claim(generation: Long, id: String, profile: String? = null): Boolean = on(generation)?.add(Ref(profile, id)) == true

    /** The resume was refused, or Hermes ended the subscription (`session.replay_gap`): it may be resumed again. */
    @Synchronized
    fun release(generation: Long, id: String, profile: String? = null): Boolean = on(generation)?.remove(Ref(profile, id)) == true

    /**
     * Whether a Hermes -> client request naming session [id] that arrived on socket [generation] is [profile]'s.
     * The frame carries only the session id (`gateway/session_server_requests.py:175`), and Hermes writes it to the
     * sockets attached to that profile's session. So it is [profile]'s exactly when this socket resumed [id] there and
     * in no other profile; otherwise nobody can tell, and it is shown nowhere (T12 review NIT).
     */
    @Synchronized
    fun requestFor(generation: Long, id: String, profile: String? = null): Boolean =
        this.generation == generation && resumed.filter { it.id == id }.singleOrNull() == Ref(profile, id)

    /** The launch profile's sessions resumed on [generation] (the watcher's). */
    @Synchronized
    fun snapshot(generation: Long): Set<String> = on(generation)?.filter { it.profile == null }?.mapTo(HashSet()) { it.id }.orEmpty()

    /**
     * A chat screen shows [id] and keeps its subscription itself (rebuilds after a replay gap); the watcher
     * leaves that session alone meanwhile. Counted, so two screens of the same session can overlap.
     */
    @Synchronized
    fun openChat(id: String) {
        chats[id] = (chats[id] ?: 0) + 1
    }

    @Synchronized
    fun closeChat(id: String) {
        val left = (chats[id] ?: return) - 1
        if (left <= 0) chats.remove(id) else chats[id] = left
    }

    @Synchronized
    fun chatOpen(id: String): Boolean = id in chats

    /** The set for [socket]; a newer socket starts empty, an older one gets null. */
    private fun on(socket: Long): MutableSet<Ref>? {
        val current = generation
        if (current != null && socket < current) return null
        if (current != socket) {
            generation = socket
            resumed.clear()
        }
        return resumed
    }
}
