package io.github.justcallmesu.aristotle.data

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

enum class PromptKind { Approval, Clarify }

/** What a watched session did that may deserve a notification (T10). */
sealed interface SessionAlert {
    val sessionId: String

    /** `message.complete` (one per turn, protocol §2); [text] is the final assistant text, never tool output. */
    data class TurnFinished(override val sessionId: String, val title: String?, val text: String) : SessionAlert

    /** `approval.request` / `clarify.request`, or one still open in the resume snapshot's `prompts` (§6). */
    data class NeedsYou(
        override val sessionId: String,
        val title: String?,
        val promptId: String,
        val kind: PromptKind,
        val question: String?,
        /** An approval for a shell command (not a file write or other action); only decides the wording. */
        val command: Boolean = false,
    ) : SessionAlert

    /** After a resume: the prompts the snapshot says are still open; a notification for another one is stale. */
    data class PromptsOpen(override val sessionId: String, val promptIds: Set<String>) : SessionAlert

    /** `approval.settled` / `clarify.settled`: answered somewhere, timed out or the turn ended. */
    data class Settled(override val sessionId: String, val promptId: String) : SessionAlert
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
 * Watches the paired PC's running sessions over the process-wide link and reports [SessionAlert]s.
 * Only the launch profile's sessions (`session.list` without `profile`, protocol §3). Event collection
 * never waits for a call, so frames keep flowing while a resume is in flight.
 */
class SessionWatcher(private val link: RelayLink, private val timings: FeedTimings = FeedTimings()) {

    /** Runs until cancelled; starts over on every new relay. */
    fun alerts(): Flow<SessionAlert> = channelFlow {
        link.state
            .map { (it as? LinkState.Online)?.generation }
            .distinctUntilChanged()
            .collectLatest { generation -> if (generation != null) Connection(channel).run() }
    }

    private inner class Connection(private val out: SendChannel<SessionAlert>) {
        private val resumed = ConcurrentHashMap.newKeySet<String>()
        private val titles = ConcurrentHashMap<String, String>()
        private val resuming = ConcurrentHashMap.newKeySet<String>()
        private val relist = Channel<Unit>(Channel.CONFLATED)

        suspend fun run() = coroutineScope {
            launch(start = CoroutineStart.UNDISPATCHED) {
                link.events.collect { frame -> if (frame is InboundFrame.Event) onEvent(frame, this@coroutineScope) }
            }
            resumeRunning()
            while (true) {
                relist.receive()
                delay(timings.refreshDebounceMillis)
                relist.tryReceive()
                resumeRunning()
            }
        }

        private suspend fun onEvent(event: InboundFrame.Event, scope: CoroutineScope) {
            val payload = event.payload ?: JsonObject(emptyMap())
            if (event.type == RpcFrames.SESSIONS_CHANGED) {
                relist.trySend(Unit)
                return
            }
            val id = event.sessionId.ifEmpty { return }
            when (event.type) {
                // The subscription ended but the socket is up: resume again (snapshot rebuild), without blocking frames.
                SESSION_REPLAY_GAP -> if (resumed.remove(id)) scope.launch { resume(id) }
                SESSION_INFO -> payload.text("title")?.takeIf { it.isNotBlank() }?.let { titles[id] = it }
                MESSAGE_COMPLETE -> if (payload.text("outcome") != OUTCOME_CANCELLED) {
                    out.send(SessionAlert.TurnFinished(id, titles[id], payload.text("text").orEmpty()))
                }
                APPROVAL_REQUEST, CLARIFY_REQUEST -> promptOf(id, payload)?.let { out.send(it) }
                APPROVAL_SETTLED, CLARIFY_SETTLED -> payload.text("prompt_id")?.let { out.send(SessionAlert.Settled(id, it)) }
            }
        }

        /** Lists the sessions and resumes the running ones not resumed yet; a closed link ends the pass. */
        private suspend fun resumeRunning() {
            val sessions = try {
                val result = link.call("session.list", buildJsonObject { put("limit", SESSION_LIMIT) })
                HermesJson.decodeFromJsonElement(SessionListResult.serializer(), result).sessions
            } catch (_: RpcException) {
                return
            } catch (_: IllegalArgumentException) {
                return // SerializationException: an answer of another shape
            }
            sessions.forEach { if (it.title.isNotBlank()) titles[it.key] = it.title }
            for (id in Subscriptions.toResume(sessions, resumed)) {
                if (!resume(id)) return
            }
        }

        /** False when the link closed. A refusal leaves the session for the next pass; a timeout may have subscribed, so it counts. */
        private suspend fun resume(id: String): Boolean {
            if (!resuming.add(id)) return true
            try {
                return resumeOnce(id)
            } finally {
                resuming.remove(id)
            }
        }

        private suspend fun resumeOnce(id: String): Boolean {
            val result = try {
                link.call("session.resume", buildJsonObject { put("session_id", id); put("omit_messages", true) })
            } catch (e: RpcException) {
                if (e.linkClosed) return false
                if (e.error == null) resumed += id
                return true
            }
            resumed += id
            val snapshot = result as? JsonObject ?: return true
            (snapshot["info"] as? JsonObject)?.text("title")?.takeIf { it.isNotBlank() }?.let { titles[id] = it }
            val prompts = snapshot["prompts"] as? JsonArray ?: return true
            val open = mutableSetOf<String>()
            prompts.forEach { prompt ->
                (prompt as? JsonObject)?.let { promptOf(id, it) }?.let {
                    open += it.promptId
                    out.send(it)
                }
            }
            out.send(SessionAlert.PromptsOpen(id, open))
            return true
        }

        /** A request payload or snapshot prompt (same dict in Hermes, `session_pending_controls.py:53-64`). */
        private fun promptOf(id: String, prompt: JsonObject): SessionAlert.NeedsYou? {
            val promptId = prompt.text("prompt_id") ?: return null
            val kind = when (prompt.text("kind")) {
                "approval" -> PromptKind.Approval
                "clarify" -> PromptKind.Clarify
                else -> return null
            }
            val question = if (kind == PromptKind.Clarify) prompt.text("question") else null
            // Read only to pick the wording, never shown: a file write arrives as "<write to AGENTS.md>".
            val command = kind == PromptKind.Approval && prompt.text("command")?.let { it.isNotBlank() && !(it.startsWith("<") && it.endsWith(">")) } == true
            return SessionAlert.NeedsYou(id, titles[id], promptId, kind, question, command)
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
        const val OUTCOME_CANCELLED = "cancelled"

        fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
