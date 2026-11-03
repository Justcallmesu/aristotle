package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.ResumedSessions
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.RpcFrames
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the chat screen gets: the transcript, whether it has loaded once, and why the last load failed. */
data class ChatFeedState(
    val chat: ChatState,
    val loaded: Boolean = false,
    val problem: String? = null,
    /** The relay socket (link generation) whose subscription last gave this chat a snapshot or catch-up (D38). */
    val confirmedGeneration: Long? = null,
)

/**
 * One session's chat over the process-wide link (T11). On every relay socket it subscribes the session once
 * ([ResumedSessions], shared with [io.github.justcallmesu.aristotle.data.SessionWatcher]) and keeps the
 * transcript current:
 * - first open, or a `session.replay_gap`: `session.resume` with the stored transcript, rebuilt from the
 *   snapshot (protocol §4). If the watcher already subscribed the session on this socket, this read is a
 *   snapshot poll on the same attachment (Hermes reuses the subscription, `session_authority.py:359-371`).
 * - a later socket: `session.events.since` from the last applied `seq` when Hermes still has it (not
 *   `truncated`, no `snapshot_required`), then `session.resume {omit_messages}` only if nobody resumed it on the
 *   new socket yet; otherwise the full resume above.
 * Frames are queued the moment they arrive (they keep flowing during a call) and reduced where the flow is
 * collected; the caller runs it on a background dispatcher (protocol §12). The session is the launch profile's
 * (calls without `profile`; [ResumedSessions] entries with profile null).
 */
class ChatFeed(
    private val link: RelayLink,
    private val resumed: ResumedSessions,
    private val sessionId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * [from]: the state a retry starts from. A chat already loaded keeps its transcript and catches up, instead
     * of going back to "Loading".
     */
    fun states(from: ChatFeedState? = null): Flow<ChatFeedState> = channelFlow {
        val feed = Feed(channel, from ?: ChatFeedState(ChatState(sessionId)))
        resumed.openChat(sessionId)
        try {
            link.state
                .map { (it as? LinkState.Online)?.generation }
                .distinctUntilChanged()
                .collectLatest { generation -> if (generation != null) feed.connection(generation) }
        } finally {
            resumed.closeChat(sessionId)
        }
    }

    private inner class Feed(private val out: SendChannel<ChatFeedState>, private var current: ChatFeedState) {

        suspend fun connection(generation: Long) = coroutineScope {
            val inbox = Channel<Inbound>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                link.events.collect { frame ->
                    when {
                        frame is InboundFrame.Event && frame.sessionId.isNotEmpty() -> inbox.send(Inbound.Event(ChatFrame(frame, clock())))
                        // Matched by profile and session id: ids repeat across profiles on the host-scoped socket.
                        frame is InboundFrame.ServerRequest && frame.sessionId == sessionId && resumed.requestFor(generation, sessionId) ->
                            inbox.send(Inbound.Request(frame))
                    }
                }
            }
            var rebuild = false
            while (subscribe(generation, rebuild)) {
                applyUntilGap(inbox)
                rebuild = true
            }
            collector.cancel()
        }

        /** Applies queued and live frames until Hermes ends this session's subscription (a replay gap). */
        private suspend fun applyUntilGap(inbox: ReceiveChannel<Inbound>) {
            for (item in inbox) {
                val next = when (item) {
                    is Inbound.Event -> {
                        if (item.frame.event.type == REPLAY_GAP && item.frame.event.sessionId == current.chat.sessionId) return
                        ChatReducer.reduce(current.chat, item.frame)
                    }
                    is Inbound.Request -> ChatReducer.request(current.chat, item.request)
                }
                if (next !== current.chat) publish(current.copy(chat = next))
            }
        }

        /** True once the session is subscribed on this socket; false when the link closed or Hermes refused. */
        private suspend fun subscribe(generation: Long, rebuild: Boolean): Boolean {
            if (rebuild) resumed.release(generation, sessionId)
            val fresh = resumed.claim(generation, sessionId)
            val caughtUp = if (rebuild || !current.loaded) null else catchUp(current.chat)
            if (caughtUp != null && !fresh) {
                publish(current.copy(chat = caughtUp, problem = null, confirmedGeneration = generation)) // the watcher holds the subscription already
                return true
            }
            val params = buildJsonObject {
                put("session_id", sessionId)
                if (caughtUp != null) put("omit_messages", true)
            }
            val snapshot = try {
                link.call("session.resume", params) as? JsonObject ?: throw RpcException(null, "session.resume: unexpected result")
            } catch (e: RpcException) {
                if (fresh && (e.linkClosed || e.error != null)) resumed.release(generation, sessionId)
                if (!e.linkClosed) publish(current.copy(problem = e.message.orEmpty()))
                return false
            }
            if (caughtUp != null && snapshot.text("replay_epoch").let { it != null && it != caughtUp.replayEpoch }) {
                // Hermes restarted its event log between the catch-up and the resume: only a full snapshot can tell.
                return subscribe(generation, rebuild = true)
            }
            publish(ChatFeedState(ChatReducer.fromSnapshot(sessionId, snapshot, caughtUp ?: current.chat), loaded = true, confirmedGeneration = generation))
            return true
        }

        /** The events missed while the link was down, or null when only a snapshot can tell (protocol §4). */
        private suspend fun catchUp(state: ChatState): ChatState? {
            val last = state.lastSeq ?: return null
            val epoch = state.replayEpoch ?: return null
            val result = try {
                link.call("session.events.since", buildJsonObject {
                    put("session_id", state.sessionId)
                    put("last_sequence", last)
                    put("replay_epoch", epoch)
                }) as? JsonObject
            } catch (_: RpcException) {
                null
            } ?: return null
            if (result.flag("truncated") == true || result.flag("snapshot_required") == true) return null
            val at = clock()
            val replayed = (result["events"] as? JsonArray).orEmpty()
                .mapNotNull { element -> element.asObject()?.let { it["params"].asObject() ?: it }?.let(RpcFrames::eventOf) }
                .fold(state) { chat, event -> ChatReducer.reduce(chat, ChatFrame(event, at)) }
            return ChatReducer.withOpenRequests(replayed, result["open_requests"] as? JsonArray)
        }

        private suspend fun publish(state: ChatFeedState) {
            current = state
            out.send(state)
        }
    }

    /** What the feed queues: a session event, or a Hermes -> client request of this session (no `seq`). */
    private sealed interface Inbound {
        data class Event(val frame: ChatFrame) : Inbound
        data class Request(val request: InboundFrame.ServerRequest) : Inbound
    }

    private companion object {
        const val REPLAY_GAP = "session.replay_gap"
    }
}
