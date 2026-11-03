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

    /** `subscription_id` of each resume answer (`session.detach` names it, protocol §4). */
    private val subscriptions = HashMap<Ref, String>()
    private val chats = HashMap<Ref, Int>()

    /**
     * Marks [id] of [profile] resumed on socket [generation]. False when it already was (or a resume is in flight),
     * and for an older socket than the newest one seen (that caller is about to be cancelled).
     */
    @Synchronized
    fun claim(generation: Long, id: String, profile: String? = null): Boolean = on(generation)?.add(Ref(profile, id)) == true

    /** The resume was refused, or Hermes ended the subscription (`session.replay_gap`): it may be resumed again. */
    @Synchronized
    fun release(generation: Long, id: String, profile: String? = null): Boolean {
        val ref = Ref(profile, id)
        val removed = on(generation)?.remove(ref) == true
        if (removed) subscriptions.remove(ref)
        return removed
    }

    /** The resume answer's `subscription_id` for a claimed session (`gateway/session_controls.py:714-737` detaches by it). */
    @Synchronized
    fun subscribed(generation: Long, id: String, profile: String?, subscriptionId: String?) {
        val ref = Ref(profile, id)
        if (subscriptionId.isNullOrEmpty() || on(generation)?.contains(ref) != true) return
        subscriptions[ref] = subscriptionId
    }

    /**
     * Whether a frame naming session [id] that arrived on socket [generation] is [profile]'s. A Hermes -> client
     * request carries only the session id (`gateway/session_server_requests.py:175`), and an event names no profile
     * either; Hermes writes both only to the sockets attached to that profile's session. So it is [profile]'s exactly
     * when this socket resumed [id] there and in no other profile; otherwise nobody can tell, and it is shown nowhere
     * (T12 review NIT).
     */
    @Synchronized
    fun requestFor(generation: Long, id: String, profile: String? = null): Boolean =
        this.generation == generation && resumed.filter { it.id == id }.singleOrNull() == Ref(profile, id)

    /** [profile]'s sessions resumed on [generation] (the watcher's view). */
    @Synchronized
    fun snapshot(generation: Long, profile: String? = null): Set<String> =
        on(generation)?.filter { it.profile == profile }?.mapTo(HashSet()) { it.id }.orEmpty()

    /**
     * The watcher leaves [profile] (T14 profile switch): its sessions resumed on [generation] that no chat screen
     * shows are forgotten here and returned with their `subscription_id` (null when no resume answer gave one), for
     * `session.detach`. An open chat keeps its subscription and the profile it was opened in.
     */
    @Synchronized
    fun leave(generation: Long, profile: String?): List<Pair<String, String?>> {
        val set = on(generation) ?: return emptyList()
        val leaving = set.filter { it.profile == profile && it !in chats }
        set.removeAll(leaving.toSet())
        return leaving.map { it.id to subscriptions.remove(it) }
    }

    /**
     * A chat screen shows [id] of [profile] and keeps its subscription itself (rebuilds after a replay gap); the
     * watcher leaves that session alone meanwhile. Counted, so two screens of the same session can overlap.
     */
    @Synchronized
    fun openChat(id: String, profile: String? = null) {
        val ref = Ref(profile, id)
        chats[ref] = (chats[ref] ?: 0) + 1
    }

    @Synchronized
    fun closeChat(id: String, profile: String? = null) {
        val ref = Ref(profile, id)
        val left = (chats[ref] ?: return) - 1
        if (left <= 0) chats.remove(ref) else chats[ref] = left
    }

    @Synchronized
    fun chatOpen(id: String, profile: String? = null): Boolean = Ref(profile, id) in chats

    /** The set for [socket]; a newer socket starts empty, an older one gets null. */
    private fun on(socket: Long): MutableSet<Ref>? {
        val current = generation
        if (current != null && socket < current) return null
        if (current != socket) {
            generation = socket
            resumed.clear()
            subscriptions.clear()
        }
        return resumed
    }
}
