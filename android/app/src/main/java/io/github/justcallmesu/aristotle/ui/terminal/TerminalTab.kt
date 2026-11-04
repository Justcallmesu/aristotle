package io.github.justcallmesu.aristotle.ui.terminal

import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import io.github.justcallmesu.aristotle.data.terminal.Answerback
import io.github.justcallmesu.aristotle.data.terminal.KeyBytes
import io.github.justcallmesu.aristotle.data.terminal.TermSize
import io.github.justcallmesu.aristotle.data.terminal.TerminalConnection
import io.github.justcallmesu.aristotle.data.terminal.TerminalState
import java.util.Properties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * One terminal tab: a [TerminalConnection] and the Termux [TerminalEmulator] it feeds, kept in the ViewModel so a
 * rotation keeps the screen. Main thread only (the emulator is not thread-safe); the socket's thread only fills the
 * bounded [io.github.justcallmesu.aristotle.data.terminal.OutputQueue].
 *
 * **How bytes reach the emulator without a local process:** Termux's `TerminalSession` always forks a local shell
 * through JNI, so it is not used. The emulator is built directly, `TerminalEmulator(output, cols, rows, …, client)`,
 * where `output` is [RemoteOutput] (a `TerminalOutput`: what the emulator writes "to the process" goes to the socket)
 * and `client` is [SilentClient] (a `TerminalSessionClient` whose log calls drop their text: Termux's logger puts
 * escape-sequence contents in its messages). Socket bytes go in with [TerminalEmulator.append]; [RemoteTerminalView]
 * draws the emulator's screen with Termux's `TerminalRenderer`.
 */
class TerminalTab(
    val id: Int,
    val connection: TerminalConnection,
    private val scope: CoroutineScope,
    private val skippedMarker: (Long) -> String,
) {
    val state: StateFlow<TerminalState> get() = connection.state

    var emulator: TerminalEmulator? = null
        private set

    /** Rows scrolled back into the transcript: 0 = the live screen, negative = history. */
    var topRow: Int = 0

    /** The attached view redraws; null while no view shows this tab. */
    var onScreenUpdated: (() -> Unit)? = null

    private val output = RemoteOutput()
    private var palette: Map<String, String> = emptyMap()
    private var resizeJob: Job? = null

    /** Starts the socket and the drain loop; once. */
    fun start() {
        scope.launch { connection.outputReady.collect { drain() } }
        connection.start()
    }

    /** The view measured [size] with cells of [cellWidth] x [cellHeight] px: (re)size the emulator, tell the PC soon. */
    fun sizeChanged(size: TermSize, cellWidth: Int, cellHeight: Int) {
        val current = emulator
        if (current == null) {
            emulator = TerminalEmulator(output, size.cols, size.rows, cellWidth, cellHeight, TRANSCRIPT_ROWS, SilentClient).also { applyPalette(it) }
            scope.launch { drain() }
        } else if (current.mColumns != size.cols || current.mRows != size.rows) {
            current.resize(size.cols, size.rows, cellWidth, cellHeight)
        }
        connection.requestResize(size)
        resizeJob?.cancel()
        resizeJob = scope.launch {
            while (true) {
                val wait = connection.resizeWaitMillis() ?: break
                delay(wait)
                connection.flushResize()
            }
        }
    }

    /** Light or dark colours (design.md palette A); the emulator resets to them (also on the PC's OSC colour reset). */
    fun setPalette(colours: Map<String, String>) {
        if (colours == palette) return
        palette = colours
        emulator?.let(::applyPalette)
        onScreenUpdated?.invoke()
    }

    /** Keys from the keyboard or the extra-keys row; jumps back to the live screen. */
    fun type(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        topRow = 0
        connection.send(bytes)
    }

    /** A paste the user asked for: through the emulator, so bracketed-paste mode wraps it and ESC/C1 are stripped. */
    fun paste(text: String) {
        val term = emulator
        topRow = 0
        if (term != null) term.paste(text) else connection.send(KeyBytes.text(text))
    }

    /** The rows on screen now, for an explicit Copy. */
    fun visibleText(): String? {
        val term = emulator ?: return null
        return term.screen.getSelectedText(0, topRow, term.mColumns - 1, topRow + term.mRows - 1).trimEnd()
    }

    /** Applies whatever output is queued, in steps, so the UI thread keeps drawing (and `yes` cannot starve it). */
    private suspend fun drain() {
        val term = emulator ?: return
        while (true) {
            val batch = connection.output.drain(DRAIN_STEP)
            if (batch.isEmpty) return
            if (batch.skippedBytes > 0) feed(term, marker(batch.skippedBytes))
            batch.frames.forEach { feed(term, it) }
            followOutput(term)
            onScreenUpdated?.invoke()
            yield()
        }
    }

    private fun feed(term: TerminalEmulator, bytes: ByteArray) {
        output.answering = true
        try {
            term.append(bytes, bytes.size)
        } finally {
            output.answering = false
        }
    }

    /** CAN ends any half-read escape sequence, so the marker always shows; reverse video sets it apart. */
    private fun marker(skipped: Long): ByteArray = "\u0018\r\n\u001b[7m ${skippedMarker(skipped)} \u001b[27m\r\n".toByteArray(Charsets.UTF_8)

    /** Scrolled back: stay on the same text while output scrolls; near the bottom: follow the output. */
    private fun followOutput(term: TerminalEmulator) {
        val history = term.screen.activeTranscriptRows
        topRow = if (topRow < -FOLLOW_ROWS) (topRow - term.scrollCounter).coerceIn(-history, 0) else 0
        term.clearScrollCounter()
    }

    private fun applyPalette(term: TerminalEmulator) {
        if (palette.isEmpty()) return
        TerminalColors.COLOR_SCHEME.updateWith(Properties().apply { putAll(palette) })
        term.mColors.reset()
    }

    /**
     * What the emulator sends "to the process". While [answering] (inside [TerminalEmulator.append]) a write is the
     * emulator replying to a query in the PC's output: only [Answerback.allowed] replies go out. Outside it, a write is
     * the user's own (paste, mouse wheel). OSC 52 clipboard writes and clipboard reads are ignored: the PC never
     * reaches the phone's clipboard. The title and bell are ignored too.
     */
    private inner class RemoteOutput : TerminalOutput() {
        var answering = false

        override fun write(data: ByteArray, offset: Int, count: Int) {
            if (count <= 0) return
            if (answering && !Answerback.allowed(data, offset, count)) return
            connection.send(data.copyOfRange(offset, offset + count))
        }

        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() {
            onScreenUpdated?.invoke()
        }
    }

    private companion object {
        /** Scrollback kept on the phone (Termux's default); bounded memory per tab. */
        const val TRANSCRIPT_ROWS = 2000
        const val DRAIN_STEP = 64 * 1024
        const val FOLLOW_ROWS = 3
    }
}

/**
 * The emulator's [TerminalSessionClient]: nothing is logged (Termux's messages can quote terminal output), no session
 * callbacks (there is no `TerminalSession`), default cursor style.
 */
private object SilentClient : TerminalSessionClient {
    override fun onTextChanged(changedSession: TerminalSession) = Unit
    override fun onTitleChanged(changedSession: TerminalSession) = Unit
    override fun onSessionFinished(finishedSession: TerminalSession) = Unit
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) = Unit
    override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
    override fun onBell(session: TerminalSession) = Unit
    override fun onColorsChanged(session: TerminalSession) = Unit
    override fun onTerminalCursorStateChange(state: Boolean) = Unit
    override fun getTerminalCursorStyle(): Int? = null
    override fun logError(tag: String?, message: String?) = Unit
    override fun logWarn(tag: String?, message: String?) = Unit
    override fun logInfo(tag: String?, message: String?) = Unit
    override fun logDebug(tag: String?, message: String?) = Unit
    override fun logVerbose(tag: String?, message: String?) = Unit
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
    override fun logStackTrace(tag: String?, e: Exception?) = Unit
}
