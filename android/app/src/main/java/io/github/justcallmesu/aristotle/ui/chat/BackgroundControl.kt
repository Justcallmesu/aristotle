package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.chat.BackgroundProcess
import io.github.justcallmesu.aristotle.data.chat.ChatFeedState
import io.github.justcallmesu.aristotle.data.chat.ProcessFrames
import io.github.justcallmesu.aristotle.data.chat.ProcessKill
import io.github.justcallmesu.aristotle.data.chat.Subagent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The Stop dialog of one process (Q77): what it shows is taken when it opens, [at] on the ViewModel's arming clock. */
data class StopConfirm(val process: BackgroundProcess, val polledAt: Long, val at: Long)

/**
 * The chat's background processes (TB1, Q77): the running rows of the last `process.list` answer, [polledAt] the wall
 * clock of that answer (an uptime ticks on from it), the Stop dialog, the kill on its way, and the result line.
 */
data class BackgroundState(
    val processes: List<BackgroundProcess> = emptyList(),
    val polledAt: Long = 0,
    val confirm: StopConfirm? = null,
    val stopping: String? = null,
    val result: ProcessKill? = null,
)

/** [collapsed]: headers only. [maxHeight]: the stack never grows past it (px) and scrolls inside. */
data class StackLayout(val collapsed: Boolean, val maxHeight: Int)

/** When the chat asks Hermes about background processes, and what the status stack shows. Pure. */
object BackgroundLogic {
    /** The Desktop's poll of `process.list` (`apps/desktop/src/app/chat/composer/status-stack/index.tsx:191-200`). */
    const val POLL_MS = 5_000L

    /** The Stop dialog's red button ignores taps this long after it appeared (the T12b window). */
    const val ARM_MS = PromptSheetLogic.ARM_MS

    /**
     * Poll only while the chat is on screen ([visible]), live (loaded, subscribed, link online) and Hermes has not
     * said the session is gone on this subscription ([goneFor] is the subscription that got `4001`/`4044`; a new one,
     * after a reconnect, tries again, as the Desktop resets its latch on a rebind).
     */
    fun polls(visible: Boolean, feed: ChatFeedState, link: LinkState, goneFor: Long?): Boolean {
        val generation = feed.confirmedGeneration ?: return false
        return visible && ChatLive.of(feed, link) && generation != goneFor
    }

    /** The running children of the session, as the stack's "Subagents" rows (the Desktop's filter, `composer-status.ts:239`). */
    fun subagentRows(children: List<Subagent>): List<Subagent> = children.filter { it.status.live }

    /** The stack shows when either section has a row. */
    fun stackVisible(subagents: List<Subagent>, processes: List<BackgroundProcess>): Boolean = subagents.isNotEmpty() || processes.isNotEmpty()

    /** [process]'s uptime now: what Hermes said at [polledAt], plus the time since (wall clock, ms). */
    fun uptime(process: BackgroundProcess, polledAt: Long, now: Long): Long = process.uptimeSeconds + ((now - polledAt).coerceAtLeast(0) / 1000)

    /** Stop in the dialog counts: the dialog is open and has been for [ARM_MS], and no other kill is on its way. */
    /** Poll on a timer only while a row runs (the Desktop's gate); otherwise the list is read on events only. */
    fun timerRuns(processes: List<BackgroundProcess>): Boolean = processes.any { it.running }

    /**
     * The stack's layout: while the keyboard is up it folds to its headers so the composer keeps its room; otherwise it
     * is at most [MAX_SHARE] of [spaceLeft], the height left after the top bar and the composer, never of the window.
     */
    fun stackLayout(imeVisible: Boolean, spaceLeft: Int): StackLayout =
        StackLayout(collapsed = imeVisible, maxHeight = (spaceLeft.coerceAtLeast(0) * MAX_SHARE).toInt())

    const val MAX_SHARE = 0.4f

    fun confirmable(state: BackgroundState, now: Long): Boolean {
        val confirm = state.confirm ?: return false
        return state.stopping == null && now - confirm.at >= ARM_MS
    }
}

/**
 * Polls `process.list` (TB1): at once when the chat becomes visible and live (opening, reconnect), then every [pollMs] only while a process runs, and at once after a
 * frame that may have changed the list ([ProcessFrames.refreshes]). It stops on `4001`/`4044` until the chat is
 * subscribed again, while the chat is not on screen ([onVisible]), and with the ViewModel (leaving the chat). The
 * background service never polls. Stop goes through [askStop] (the dialog) and only [confirmStop] sends `process.kill`.
 */
class ChatBackground(
    private val sessionId: String,
    private val link: RelayLink,
    private val clock: () -> Long,
    private val wallClock: () -> Long,
    private val work: CoroutineScope,
    private val state: () -> ChatUiState,
    private val change: ((ChatUiState) -> ChatUiState) -> Unit,
    private val pollMs: Long = BackgroundLogic.POLL_MS,
) {
    private val gate = Any()
    private var visible = false
    private var goneFor: Long? = null
    private var loop: Job? = null
    private val kick = Channel<Unit>(Channel.CONFLATED)

    init {
        work.launch {
            link.events.collect { frame ->
                if (frame is InboundFrame.Event && frame.sessionId == sessionId && ProcessFrames.refreshes(frame)) kick.trySend(Unit)
            }
        }
    }

    /** The chat screen is on screen (ON_START) or not (ON_STOP, left, hidden by the app lock). */
    fun onVisible(shown: Boolean) {
        synchronized(gate) { visible = shown }
        onState(state())
    }

    /** Called after every state change: starts or stops the poll. */
    fun onState(ui: ChatUiState) {
        // Started outside the lock and only once it is stored: its first read changes the state, which comes back here.
        val started = synchronized(gate) {
            val wanted = BackgroundLogic.polls(visible, ui.feed, ui.link, goneFor)
            val running = loop?.isActive == true
            when {
                wanted && !running -> {
                    kick.tryReceive()
                    work.launch(start = CoroutineStart.LAZY) { poll() }.also { loop = it }
                }
                !wanted && running -> {
                    loop?.cancel()
                    loop = null
                    null
                }
                else -> null
            }
        }
        started?.start()
    }

    private suspend fun poll() {
        while (fetch()) {
            // The Desktop's gate: the timer runs only while a process runs; an idle list wakes on events alone.
            if (BackgroundLogic.timerRuns(state().background.processes)) withTimeoutOrNull(pollMs) { kick.receive() } else kick.receive()
        }
    }

    /** One read; false when Hermes said the session is gone (the poll ends). */
    private suspend fun fetch(): Boolean {
        val generation = state().feed.confirmedGeneration
        val call = ProcessFrames.list(sessionId)
        val result = try {
            link.call(call.method, call.params)
        } catch (e: RpcException) {
            if (!ProcessFrames.stopsPolling(e)) return true // a lost link or no answer: the next tick or event tries again
            synchronized(gate) { goneFor = generation }
            change { it.copy(background = it.background.copy(processes = emptyList())) }
            return false
        }
        val fresh = ProcessFrames.processes(result)
        val at = wallClock()
        change {
            val rows = ProcessFrames.reconcile(it.background.processes, fresh)
            // A dialog whose process left the list closes itself; a kill on its way keeps its own state.
            val confirm = it.background.confirm?.takeIf { c -> rows.any { p -> p.id == c.process.id } }
            it.copy(background = it.background.copy(processes = rows, polledAt = at, confirm = confirm))
        }
        return true
    }

    /** Stop on a row: only the dialog opens; nothing is sent. */
    fun askStop(processId: String) {
        val ui = state()
        val process = ui.background.processes.firstOrNull { it.id == processId } ?: return
        if (ui.background.stopping != null) return
        change { it.copy(background = it.background.copy(confirm = StopConfirm(process, it.background.polledAt, clock()), result = null)) }
    }

    /** Cancel, back or a tap outside the dialog: it closes and nothing is sent. */
    fun cancelStop() = change { it.copy(background = it.background.copy(confirm = null)) }

    /** The dialog's red Stop: the one place `process.kill` is sent; the list is read again right after. */
    fun confirmStop() {
        val bg = state().background
        val confirm = bg.confirm ?: return
        if (!BackgroundLogic.confirmable(bg, clock())) return
        val processId = confirm.process.id
        change { it.copy(background = it.background.copy(confirm = null, stopping = processId, result = null)) }
        val call = ProcessFrames.kill(sessionId, processId)
        work.launch {
            val outcome = try {
                ProcessFrames.killOutcome(link.call(call.method, call.params))
            } catch (e: RpcException) {
                ProcessFrames.killFailure(e)
            }
            change { it.copy(background = it.background.copy(stopping = null, result = outcome)) }
            kick.trySend(Unit)
        }
    }

    fun resultShown() = change { it.copy(background = it.background.copy(result = null)) }
}
