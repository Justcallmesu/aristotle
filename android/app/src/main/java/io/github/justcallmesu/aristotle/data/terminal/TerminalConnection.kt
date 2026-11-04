package io.github.justcallmesu.aristotle.data.terminal

import io.github.justcallmesu.aristotle.data.ConnectionProblem
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update

/** Where one terminal stands. */
sealed interface TerminalState {
    data object Connecting : TerminalState
    data object Open : TerminalState
    data class Ended(val end: TerminalEnd) : TerminalState
}

/**
 * One remote terminal, from open to its end (protocol §14). Output frames go into a bounded [OutputQueue] on the
 * reading thread and [outputReady] wakes the emulator's side, which drains it; input is split into ≤ 16 KiB frames;
 * sizes are debounced by [ResizeGate]. **No reattach, no reconnect (Q55):** once [state] is [TerminalState.Ended] the
 * channel is never used again; Reopen makes a new connection. Thread-safe: the socket calls in on OkHttp's thread.
 */
class TerminalConnection(
    private val channel: TerminalChannel,
    val output: OutputQueue = OutputQueue(),
    private val resizeGate: ResizeGate = ResizeGate(),
    private val clock: () -> Long = System::currentTimeMillis,
) : TerminalEvents {
    private val current = MutableStateFlow<TerminalState>(TerminalState.Connecting)
    val state: StateFlow<TerminalState> = current.asStateFlow()

    private val ready = Channel<Unit>(Channel.CONFLATED)

    /** Emits (conflated) when output is waiting in [output]. */
    val outputReady: Flow<Unit> = ready.receiveAsFlow()

    private val exitCode = AtomicReference<Int?>(null)

    @Volatile
    private var size: TermSize = TerminalFrames.DEFAULT_SIZE

    private var started = false

    /** Opens the socket; once only. */
    @Synchronized
    fun start() {
        check(!started) { "a terminal connection starts once" }
        started = true
        channel.open(this)
    }

    /** Typed keys or a paste, split into frames; false when the terminal is not open (nothing is queued for later). */
    fun send(bytes: ByteArray): Boolean {
        if (current.value != TerminalState.Open) return false
        return TerminalFrames.chunks(bytes).all(channel::send)
    }

    /** The view's size now; it goes out once it has held for the quiet period ([flushResize]). */
    @Synchronized
    fun requestResize(newSize: TermSize) {
        size = newSize
        resizeGate.request(newSize, clock())
    }

    /** How long until [flushResize] has something to send; null when nothing waits. */
    @Synchronized
    fun resizeWaitMillis(): Long? = if (current.value == TerminalState.Open) resizeGate.waitMillis(clock()) else null

    /** Sends the debounced size if it is due; true when a frame went out. */
    @Synchronized
    fun flushResize(): Boolean {
        if (current.value != TerminalState.Open) return false
        val due = resizeGate.due(clock()) ?: return false
        return channel.sendText(TerminalFrames.resize(due))
    }

    /** The user closed it or left the screen: `1000`, and the server ends the shell. */
    fun close() {
        if (finish(TerminalEnd.ClosedHere)) channel.close()
    }

    /** The phone's network changed: the socket leads nowhere now, and the shell on the PC is ended by the server. */
    fun networkChanged() {
        if (finish(TerminalEnd.ConnectionChanged)) channel.cancel()
    }

    @Synchronized
    override fun onOpen() {
        if (current.value != TerminalState.Connecting) return
        current.value = TerminalState.Open
        // The size goes first, right after the upgrade (protocol §14), not after the quiet period.
        channel.sendText(TerminalFrames.resize(size))
        resizeGate.sentNow(size)
    }

    override fun onOutput(bytes: ByteArray) {
        if (current.value is TerminalState.Ended) return
        output.offer(bytes)
        ready.trySend(Unit)
    }

    override fun onControl(control: TerminalControl) {
        if (control is TerminalControl.Exit) exitCode.set(control.code)
    }

    override fun onClosed(code: Int, reason: String) {
        finish(TerminalEnd.ofClose(code, reason, exitCode.get()))
    }

    override fun onFailed(opened: Boolean, httpStatus: Int?, errorBody: String?, problem: ConnectionProblem) {
        finish(TerminalEnd.ofFailure(opened, httpStatus, errorBody, problem))
    }

    /** The first end wins; later ones (the socket's own failure after a cancel) change nothing. */
    private fun finish(end: TerminalEnd): Boolean {
        var won = false
        current.update { state ->
            won = state !is TerminalState.Ended
            if (won) TerminalState.Ended(end) else state
        }
        if (won) ready.trySend(Unit)
        return won
    }
}
