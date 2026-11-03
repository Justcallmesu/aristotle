package io.github.justcallmesu.aristotle.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import io.github.justcallmesu.aristotle.data.chat.Prompt
import io.github.justcallmesu.aristotle.data.chat.Prompts
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** [Secret]: a sudo password or a secret value (Q51: answered on the phone). [OnPc]: a vault prompt, answered on the PC. */
enum class PromptKind { Approval, Clarify, Secret, OnPc }

/** What a watched session did that may deserve a notification (T10). [profile]: the session's (null = launch). */
sealed interface SessionAlert {
    val sessionId: String

    /** `message.complete` (one per turn, protocol §2); [text] is the final assistant text, never tool output. */
    data class TurnFinished(override val sessionId: String, val title: String?, val text: String, val profile: String? = null) : SessionAlert

    /**
     * `approval.request` / `clarify.request`, a `sudo` / `secret` / batch `clarify` / vault server request, or one
     * still open in the resume snapshot's `prompts` / `open_requests` (§6). Never carries a command or value.
     */
    data class NeedsYou(
        override val sessionId: String,
        val title: String?,
        val promptId: String,
        val kind: PromptKind,
        val question: String?,
        /** An approval for a shell command (not a file write or other action); only decides the wording. */
        val command: Boolean = false,
        val profile: String? = null,
    ) : SessionAlert

    /** After a resume: the prompts the snapshot says are still open; a notification for another one is stale. */
    data class PromptsOpen(override val sessionId: String, val promptIds: Set<String>) : SessionAlert

    /** `approval.settled` / `clarify.settled` / `request.cancel`: answered somewhere, timed out or the turn ended. */
    data class Settled(override val sessionId: String, val promptId: String) : SessionAlert

    /** The watcher left this session (profile switch, T14): its settles no longer arrive, so its prompt is not shown. */
    data class Unwatched(override val sessionId: String) : SessionAlert
}

/**
 * Background subscription rule (protocol §2 note): a socket gets a session's events only after it resumed
 * it (§4). So on every new relay the link resumes each session that `session.list` reports `running`, and
 * after `sessions.changed` it lists again and resumes the ones that started since. A resumed session stays
 * resumed until a `session.replay_gap` (Hermes dropped this viewer's subscription: resume it again, same socket)
 * or the socket ends: idle sessions send nothing, and its next turn must not be missed. Pure.
 */
object Subscriptions {
    fun toResume(sessions: List<SessionSummary>, resumed: Set<String>): List<String> =
        sessions.asSequence().filter { it.running }.map { it.key }.filter { it.isNotEmpty() && it !in resumed }.distinct().toList()
}

/**
 * Watches the paired PC's running sessions of the selected [profile] (T14; null = the launch profile, calls without
 * `profile`, protocol §3) over the process-wide link and reports [SessionAlert]s. Event collection never waits for a
 * call, so frames keep flowing while a resume is in flight. [resumed] is shared with the chat screen: a session
 * either of them resumed on this socket is not resumed again. Switching the profile on the same socket detaches
 * the old profile's sessions the watcher holds (`session.detach`), and only the new profile's sessions alert.
 */
class SessionWatcher(
    private val link: RelayLink,
    private val timings: FeedTimings = FeedTimings(),
    private val resumed: ResumedSessions = ResumedSessions(),
    private val profile: StateFlow<String?> = MutableStateFlow(null),
) {
    private data class Watch(val generation: Long, val profile: String?)

    /** Runs until cancelled; starts over on every new relay and every profile switch. */
    fun alerts(): Flow<SessionAlert> = channelFlow {
        var watching: Watch? = null
        combine(link.state.map { (it as? LinkState.Online)?.generation }.distinctUntilChanged(), profile) { generation, profile ->
            generation?.let { Watch(it, profile) }
        }
            .distinctUntilChanged()
            .collectLatest { watch ->
                if (watch == null) return@collectLatest
                watching?.takeIf { it.generation == watch.generation && it.profile != watch.profile }?.let { leave(it, channel) }
                watching = watch
                Connection(channel, watch.generation, watch.profile).run()
            }
    }

    /** Detaches the sessions the watcher holds in [old]'s profile on that socket; an open chat keeps its own. */
    private suspend fun leave(old: Watch, out: SendChannel<SessionAlert>) {
        for ((id, subscription) in resumed.leave(old.generation, old.profile)) {
            out.send(SessionAlert.Unwatched(id))
            if (subscription == null) continue
            try {
                link.call("session.detach", SessionFrames.detach(id, subscription, old.profile))
            } catch (e: RpcException) {
                if (e.linkClosed) return
            }
        }
    }

    private inner class Connection(private val out: SendChannel<SessionAlert>, private val generation: Long, private val profile: String?) {
        private val calls = ProfileLink(link, profile)
        private val titles = ConcurrentHashMap<String, String>()
        private val relist = Channel<Unit>(Channel.CONFLATED)

        suspend fun run() = coroutineScope {
            launch(start = CoroutineStart.UNDISPATCHED) {
                link.events.collect { frame ->
                    when (frame) {
                        is InboundFrame.Event -> onEvent(frame, this@coroutineScope)
                        // D32: sudo, secret, batch clarify and vault requests need the user too.
                        // Only this profile's own (session ids repeat across profiles on this host-scoped socket).
                        is InboundFrame.ServerRequest -> frame.sessionId.takeIf { it.isNotEmpty() && ours(it) }?.let { id ->
                            Prompts.fromRequest(frame)?.let { out.send(needsYou(id, it)) }
                        }
                        else -> Unit
                    }
                }
            }
            resumeRunning()
            while (true) {
                relist.receive()
                delay(timings.refreshDebounceMillis)
                relist.tryReceive()
                resumeRunning()
            }
        }

        /** The session is this profile's on this socket (T12b: matched by profile and session id). */
        private fun ours(id: String): Boolean = resumed.requestFor(generation, id, profile)

        private suspend fun onEvent(event: InboundFrame.Event, scope: CoroutineScope) {
            val payload = event.payload ?: JsonObject(emptyMap())
            if (event.type == RpcFrames.SESSIONS_CHANGED) {
                relist.trySend(Unit)
                return
            }
            val id = event.sessionId.ifEmpty { return }
            // A chat of another profile keeps its own subscription on this socket; its events are not this watch's.
            if (!ours(id)) return
            when (event.type) {
                // The subscription ended but the socket is up: resume again (snapshot rebuild), without blocking
                // frames. An open chat rebuilds its own session (with the transcript), so it is left to it.
                SESSION_REPLAY_GAP -> if (!resumed.chatOpen(id, profile) && resumed.release(generation, id, profile)) scope.launch { resume(id) }
                SESSION_INFO -> payload.text("title")?.takeIf { it.isNotBlank() }?.let { titles[id] = it }
                MESSAGE_COMPLETE -> if (payload.text("outcome") != OUTCOME_CANCELLED) {
                    out.send(SessionAlert.TurnFinished(id, titles[id], payload.text("text").orEmpty(), profile))
                }
                APPROVAL_REQUEST, CLARIFY_REQUEST -> Prompts.fromEvent(payload, at = null)?.let { out.send(needsYou(id, it)) }
                APPROVAL_SETTLED, CLARIFY_SETTLED -> payload.text("prompt_id")?.let { out.send(SessionAlert.Settled(id, it)) }
                REQUEST_CANCEL -> payload.text("id")?.let { out.send(SessionAlert.Settled(id, it)) }
            }
        }

        /** Lists the sessions and resumes the running ones not resumed yet; a closed link ends the pass. */
        private suspend fun resumeRunning() {
            val sessions = try {
                val result = calls.call("session.list", buildJsonObject { put("limit", SESSION_LIMIT) })
                HermesJson.decodeFromJsonElement(SessionListResult.serializer(), result).sessions
            } catch (_: RpcException) {
                return
            } catch (_: IllegalArgumentException) {
                return // SerializationException: an answer of another shape
            }
            sessions.forEach { if (it.title.isNotBlank()) titles[it.key] = it.title }
            for (id in Subscriptions.toResume(sessions, resumed.snapshot(generation, profile))) {
                if (!resume(id)) return
            }
        }

        /**
         * False when the link closed. Claimed before the call, so a resume in flight (here or in the chat) is
         * never doubled. A refusal leaves the session for the next pass; a timeout may have subscribed, so it counts.
         */
        private suspend fun resume(id: String): Boolean {
            if (!resumed.claim(generation, id, profile)) return true
            val result = try {
                calls.call("session.resume", buildJsonObject { put("session_id", id); put("omit_messages", true) })
            } catch (e: RpcException) {
                if (e.linkClosed || e.error != null) resumed.release(generation, id, profile)
                return !e.linkClosed
            }
            val snapshot = result as? JsonObject ?: return true
            resumed.subscribed(generation, id, profile, snapshot.text("subscription_id"))
            (snapshot["info"] as? JsonObject)?.text("title")?.takeIf { it.isNotBlank() }?.let { titles[id] = it }
            val prompts = (snapshot["prompts"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let { p -> Prompts.fromEvent(p, at = null) } } +
                (snapshot["open_requests"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(Prompts::fromOpenRequest) }
            prompts.forEach { out.send(needsYou(id, it)) }
            out.send(SessionAlert.PromptsOpen(id, prompts.mapTo(mutableSetOf()) { it.key }))
            return true
        }

        /** The notification's facts about a prompt: its kind and, for a clarify, the question. */
        private fun needsYou(id: String, prompt: Prompt): SessionAlert.NeedsYou = when (prompt) {
            // The command is read only to pick the wording, never shown: a file write arrives as "<write to AGENTS.md>".
            is Prompt.Approval -> SessionAlert.NeedsYou(
                id, titles[id], prompt.key, PromptKind.Approval, null,
                command = prompt.command.isNotBlank() && !(prompt.command.startsWith("<") && prompt.command.endsWith(">")),
                profile = profile,
            )
            is Prompt.Clarify -> SessionAlert.NeedsYou(id, titles[id], prompt.key, PromptKind.Clarify, prompt.questions.firstOrNull()?.question, profile = profile)
            is Prompt.Secret -> SessionAlert.NeedsYou(id, titles[id], prompt.key, PromptKind.Secret, null, profile = profile)
            is Prompt.OnPc -> SessionAlert.NeedsYou(id, titles[id], prompt.key, PromptKind.OnPc, null, profile = profile)
        }
    }

    private companion object {
        const val SESSION_LIMIT = 50
        const val SESSION_REPLAY_GAP = "session.replay_gap"
        const val SESSION_INFO = "session.info"
        const val MESSAGE_COMPLETE = "message.complete"
        const val APPROVAL_REQUEST = "approval.request"
        const val CLARIFY_REQUEST = "clarify.request"
        const val APPROVAL_SETTLED = "approval.settled"
        const val CLARIFY_SETTLED = "clarify.settled"
        const val REQUEST_CANCEL = "request.cancel"
        const val OUTCOME_CANCELLED = "cancelled"

        fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
