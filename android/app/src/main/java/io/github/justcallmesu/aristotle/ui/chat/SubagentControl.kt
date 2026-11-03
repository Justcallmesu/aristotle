package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.ChatState
import io.github.justcallmesu.aristotle.data.chat.DelegateStatus
import io.github.justcallmesu.aristotle.data.chat.SubagentFrames
import io.github.justcallmesu.aristotle.data.chat.SubagentSnapshot
import io.github.justcallmesu.aristotle.data.chat.SubagentTail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A one-off line (snackbar) about a subagent's Stop. */
sealed interface SubagentMessage {
    /** Hermes interrupted it; its `subagent.complete` follows (the Desktop's "Stop requested"). */
    data object StopRequested : SubagentMessage

    /** `found: false`: it had already finished, or is no child of this session's live agent. */
    data object Rejected : SubagentMessage

    /** The link was down: nothing reached Hermes. */
    data object NotSent : SubagentMessage
}

/** What the open page shows since [at] (ViewModel clock); [armed] once [SubagentLogic.ARM_MS] passed. */
data class SubagentShown(val key: String, val at: Long, val armed: Boolean = false)

/**
 * The page of one opened child (T17): [open] is its subagent id; [tail] its live transcript, read while it is open.
 * The page goes when the child leaves the roster (a new turn drops the finished ones).
 */
data class SubagentPageState(
    val open: String? = null,
    val shown: SubagentShown? = null,
    val stopping: Boolean = false,
    val tail: SubagentTail? = null,
    val message: SubagentMessage? = null,
)

/** When to ask Hermes about children, and the tap guard of Stop. Pure. */
object SubagentLogic {
    /** The same window as every other control that changes meaning under the thumb (T12b, T13). */
    const val ARM_MS = PromptSheetLogic.ARM_MS

    /** The Desktop's safety-net refresh of `subagent.list` while children run (`use-subagent-snapshot.ts:69-75`). */
    const val LIST_POLL_MS = 5_000L

    /** The Desktop reads an open child's transcript every 2 s (`subagent-transcript.tsx:58-59`). */
    const val TAIL_POLL_MS = 2_000L

    /** After this many failed reads in a row the Desktop stops polling (`use-subagent-snapshot.ts:19-21`, `:44-46`). */
    const val MAX_FAILURES = 3

    /**
     * A roster read is worth it only when children may be running: a live one is known, or the transcript shows a
     * `delegate_task` whose children have not all settled (a chat opened mid-delegation missed their start).
     */
    fun wantsRoster(chat: ChatState): Boolean =
        chat.subagents.anyLive || chat.items.any { item ->
            item is ChatItem.Tool && item.subagents.any { it.status.live || it.status == DelegateStatus.Dispatched }
        }

    /** What Stop on the page means right now: this child in this status. Null when nothing (or nothing known) is open. */
    fun key(page: SubagentPageState, chat: ChatState): String? {
        val id = page.open ?: return null
        val child = chat.subagents.byId(id) ?: return null
        return "$id:${child.status}"
    }

    /** Follows the chat: the page closes when its child is gone; a new meaning restarts the arming time. */
    fun track(page: SubagentPageState, chat: ChatState, now: Long): SubagentPageState {
        if (page.open == null) return page
        val key = key(page, chat) ?: return SubagentPageState()
        return if (page.shown?.key == key) page else page.copy(shown = SubagentShown(key, now))
    }

    /** Stop for [id] counts: its page is open, the child still runs, and that has been on screen [ARM_MS]. */
    fun armed(page: SubagentPageState, chat: ChatState, id: String, now: Long): Boolean {
        val shown = page.shown ?: return false
        val child = chat.subagents.byId(id) ?: return false
        return page.open == id && child.status.live && shown.key == key(page, chat) && now - shown.at >= ARM_MS
    }
}

/**
 * The chat's subagents (T17), the Desktop's way: the roster comes from the parent's `subagent.*` events (no second
 * subscription), hydrated by `subagent.list` when the chat may have missed children and polled while some run; an
 * opened child's transcript is read with `subagent.tail`; Stop is `subagent.interrupt`. [offer] hands a list answer to
 * the feed, which merges it in order with the session's frames. [clock] is the arming clock, [wallClock] the time a
 * child's elapsed counts from.
 */
class ChatSubagents(
    private val sessionId: String,
    private val link: RelayLink,
    private val clock: () -> Long,
    private val wallClock: () -> Long,
    private val work: CoroutineScope,
    private val state: () -> ChatUiState,
    private val change: ((ChatUiState) -> ChatUiState) -> Unit,
    private val offer: (SubagentSnapshot) -> Unit,
) {
    private var hydratedFor: Long? = null
    private var failures = 0
    private var poll: Job? = null
    private var tailJob: Job? = null
    private var tailFor: String? = null

    /** Called after every state change: hydrate once per socket when needed, keep polling while children run, read the open one. */
    fun onState(ui: ChatUiState) {
        val feed = ui.feed
        val generation = feed.confirmedGeneration
        if (feed.loaded && generation != null && generation != hydratedFor) {
            // Once per socket, when the chat is (re)loaded: later children announce themselves on the stream.
            hydratedFor = generation
            if (SubagentLogic.wantsRoster(feed.chat)) {
                failures = 0
                work.launch { fetch() }
            }
        }
        if (feed.chat.subagents.anyLive && poll?.isActive != true && failures < SubagentLogic.MAX_FAILURES) {
            poll = work.launch {
                while (state().feed.chat.subagents.anyLive && failures < SubagentLogic.MAX_FAILURES) {
                    delay(SubagentLogic.LIST_POLL_MS)
                    fetch()
                }
            }
        }
        val open = ui.subagent.open
        if (open != tailFor) {
            tailJob?.cancel()
            tailFor = open
            tailJob = open?.let { id -> work.launch { readTail(id) } }
        }
    }

    /** A row tap: its page opens (only for a child the roster knows). */
    fun open(id: String) {
        if (state().feed.chat.subagents.byId(id) == null) return
        change { it.copy(subagent = SubagentPageState(open = id)) }
    }

    fun close() = change { it.copy(subagent = SubagentPageState()) }

    fun messageShown() = change { it.copy(subagent = it.subagent.copy(message = null)) }

    /** Stop one child, no confirmation (as on the Desktop); only an armed tap on its open page counts. */
    fun stop(id: String) {
        val ui = state()
        if (ui.subagent.stopping || !SubagentLogic.armed(ui.subagent, ui.feed.chat, id, clock())) return
        change { it.copy(subagent = it.subagent.copy(stopping = true, message = null)) }
        val call = SubagentFrames.interrupt(sessionId, id)
        work.launch {
            val message = try {
                if (SubagentFrames.stopped(link.call(call.method, call.params))) SubagentMessage.StopRequested else SubagentMessage.Rejected
            } catch (e: RpcException) {
                if (e.linkClosed || !e.written) SubagentMessage.NotSent else SubagentMessage.Rejected
            }
            change { it.copy(subagent = it.subagent.copy(stopping = false, message = message)) }
        }
    }

    private suspend fun fetch() {
        val base = state().feed.chat.subagents.version
        val call = SubagentFrames.list(sessionId)
        val result = try {
            link.call(call.method, call.params)
        } catch (_: RpcException) {
            failures++
            return
        }
        failures = 0
        offer(SubagentSnapshot(SubagentFrames.children(result), base, wallClock()))
    }

    /**
     * Reads the open child's transcript every [SubagentLogic.TAIL_POLL_MS] while it runs, then once more. A read that
     * finds nothing after the child ended keeps what was shown: Hermes drops the file with the child.
     */
    private suspend fun readTail(id: String) {
        while (true) {
            val running = state().feed.chat.subagents.byId(id)?.status?.live ?: return
            val call = SubagentFrames.tail(sessionId, id)
            val tail = try {
                SubagentFrames.tailOf(link.call(call.method, call.params))
            } catch (_: RpcException) {
                null
            }
            if (tail != null) {
                change { ui ->
                    val page = ui.subagent
                    val keep = !tail.available && page.tail?.available == true
                    if (page.open != id || keep) ui else ui.copy(subagent = page.copy(tail = tail))
                }
            }
            if (!running) return
            delay(SubagentLogic.TAIL_POLL_MS)
        }
    }
}
