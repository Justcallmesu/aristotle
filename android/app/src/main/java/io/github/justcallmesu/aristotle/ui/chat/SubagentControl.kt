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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

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

/** How often the two read loops run; the defaults are the Desktop's. Tests pass short ones. */
data class SubagentPacing(
    val listMs: Long = SubagentLogic.LIST_POLL_MS,
    val tailMs: Long = SubagentLogic.TAIL_POLL_MS,
    val tailMaxMs: Long = SubagentLogic.TAIL_BACKOFF_MAX_MS,
)

/** When to ask Hermes about children, and the tap guard of Stop. Pure. */
object SubagentLogic {
    /** The same window as every other control that changes meaning under the thumb (T12b, T13). */
    const val ARM_MS = PromptSheetLogic.ARM_MS

    /** The Desktop's safety-net refresh of `subagent.list` while children run (`use-subagent-snapshot.ts:69-75`). */
    const val LIST_POLL_MS = 5_000L

    /** The Desktop reads an open child's transcript every 2 s (`subagent-transcript.tsx:58-59`). */
    const val TAIL_POLL_MS = 2_000L

    /** A tail read that fails (link down) waits twice as long each time, up to this. */
    const val TAIL_BACKOFF_MAX_MS = 30_000L

    /** After this many failed reads in a row the Desktop stops polling (`use-subagent-snapshot.ts:19-21`, `:44-46`). */
    const val MAX_FAILURES = 3

    /** The wait before the next tail read after [failures] failed ones in a row: 2 s, 4 s, … capped at 30 s. */
    fun tailDelay(failures: Int, pacing: SubagentPacing = SubagentPacing()): Long {
        var wait = pacing.tailMs
        repeat(failures) { if (wait < pacing.tailMaxMs) wait = minOf(wait * 2, pacing.tailMaxMs) }
        return wait
    }

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
    private val pacing: SubagentPacing = SubagentPacing(),
) {
    // Between the activity's ON_STOP and ON_START both read loops sleep (the Desktop polls only while visible):
    // [resumed] is open while stopped and completes on ON_START; [stopSignal] wakes a loop that is mid-wait on ON_STOP.
    private val gate = Any()
    private var resumed: CompletableDeferred<Unit>? = null
    private var stopSignal = CompletableDeferred<Unit>()
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
                    pace(pacing.listMs)
                    if (!state().feed.chat.subagents.anyLive) break
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

    /** The activity left the screen: no reads until it is back. */
    fun onStop() = synchronized(gate) {
        if (resumed == null) {
            resumed = CompletableDeferred()
            stopSignal.complete(Unit)
        }
    }

    /** The activity is back: the loops read once now, then go on at their pace. Nothing happens if it was never stopped. */
    fun onStart() = synchronized(gate) {
        resumed?.let {
            resumed = null
            stopSignal = CompletableDeferred()
            it.complete(Unit)
        }
    }

    /**
     * Waits [ms] while visible. When the app is stopped before the time is up, or already is, waits for ON_START
     * instead and returns then, so the caller's next read is the immediate one on coming back.
     */
    private suspend fun pace(ms: Long) {
        val (stopped, asleep) = synchronized(gate) { stopSignal to resumed }
        if (asleep == null) withTimeoutOrNull(ms) { stopped.await() }
        synchronized(gate) { resumed }?.await()
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
     * Reads the open child's transcript every [SubagentLogic.TAIL_POLL_MS] while it runs, then once more; a failed read
     * (link down) backs off to [SubagentLogic.TAIL_BACKOFF_MAX_MS]. A read that finds nothing after the child ended
     * keeps what was shown: Hermes drops the file with the child.
     */
    private suspend fun readTail(id: String) {
        var failed = 0
        while (true) {
            val running = state().feed.chat.subagents.byId(id)?.status?.live ?: return
            val call = SubagentFrames.tail(sessionId, id)
            val tail = try {
                SubagentFrames.tailOf(link.call(call.method, call.params))
            } catch (_: RpcException) {
                null
            }
            failed = if (tail == null) failed + 1 else 0
            if (tail != null) {
                change { ui ->
                    val page = ui.subagent
                    val keep = !tail.available && page.tail?.available == true
                    if (page.open != id || keep) ui else ui.copy(subagent = page.copy(tail = tail))
                }
            }
            if (!running) return
            pace(SubagentLogic.tailDelay(failed, pacing))
        }
    }
}
