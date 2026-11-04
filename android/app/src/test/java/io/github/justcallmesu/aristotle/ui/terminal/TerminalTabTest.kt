package io.github.justcallmesu.aristotle.ui.terminal

import io.github.justcallmesu.aristotle.data.terminal.OutputQueue
import io.github.justcallmesu.aristotle.data.terminal.TermSize
import io.github.justcallmesu.aristotle.data.terminal.TerminalChannel
import io.github.justcallmesu.aristotle.data.terminal.TerminalConnection
import io.github.justcallmesu.aristotle.data.terminal.TerminalEvents
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Socket bytes through Termux's real TerminalEmulator (no TerminalSession, no local process): output lands on the
 * screen, only numeric answer-backs go back to the PC, OSC 52 never reaches anything, and a full queue shows a marker.
 */
class TerminalTabTest {
    private class FakeChannel : TerminalChannel {
        var events: TerminalEvents? = null
        val sent = CopyOnWriteArrayList<String>()
        override fun open(events: TerminalEvents) {
            this.events = events
        }

        override fun send(bytes: ByteArray): Boolean {
            sent += String(bytes, Charsets.ISO_8859_1)
            return true
        }

        override fun sendText(text: String): Boolean = true
        override fun close() = Unit
        override fun cancel() = Unit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val channel = FakeChannel()

    @After
    fun stop() = scope.cancel()

    private fun tab(queue: OutputQueue = OutputQueue()): TerminalTab {
        val tab = TerminalTab(1, TerminalConnection(channel, queue), scope) { "skipped $it" }
        tab.start()
        channel.events!!.onOpen()
        tab.sizeChanged(TermSize(40, 10), cellWidth = 10, cellHeight = 20)
        return tab
    }

    private fun output(text: String) = channel.events!!.onOutput(text.toByteArray(Charsets.UTF_8))

    private fun screen(tab: TerminalTab): String = tab.emulator!!.screen.transcriptText

    @Test
    fun socketBytesReachTheScreen() {
        val tab = tab()
        output("Aristotle terminal\r\n$ ")
        val cafe = "echo caf\u00e9\r\n".toByteArray(Charsets.UTF_8)
        // A UTF-8 sequence split across two frames (protocol §14) still reads as one character.
        channel.events!!.onOutput(cafe.copyOfRange(0, 9))
        channel.events!!.onOutput(cafe.copyOfRange(9, cafe.size))
        assertTrue(screen(tab), screen(tab).contains("Aristotle terminal"))
        assertTrue(screen(tab), screen(tab).contains("echo caf\u00e9"))
        assertEquals(40, tab.emulator!!.mColumns)
    }

    @Test
    fun outputQueuedBeforeTheFirstLayoutIsShownOnceMeasured() {
        val tab = TerminalTab(1, TerminalConnection(channel), scope) { "skipped $it" }
        tab.start()
        channel.events!!.onOpen()
        output("early banner\r\n")
        tab.sizeChanged(TermSize(40, 10), 10, 20)
        assertTrue(screen(tab).contains("early banner"))
    }

    @Test
    fun onlyNumericAnswerBacksGoToThePc() {
        tab()
        output("\u001b[c") // DA1
        output("\u001b[6n") // cursor position
        output("\u001bP\$q\"p\u001b\\") // DECRQSS: echoes its query
        output("\u001bP+q544e\u001b\\") // XTGETTCAP: echoes the name
        output("\u001b[21t") // window label
        assertEquals(2, channel.sent.size)
        assertTrue(channel.sent[0].endsWith("c"))
        assertTrue(channel.sent[1].matches(Regex("\u001b\\[\\d+;\\d+R")))
    }

    @Test
    fun osc52ClipboardWritesAreIgnored() {
        val tab = tab()
        output("\u001b]52;c;cm0gLXJmIH4K\u0007after\r\n")
        assertTrue(channel.sent.isEmpty())
        assertTrue(screen(tab).contains("after"))
    }

    @Test
    fun aPasteIsTheUsersInputAndIsBracketedWhenAsked() {
        val tab = tab()
        output("\u001b[?2004h")
        tab.paste("ls\n\u001b[201~rm")
        assertEquals(listOf("\u001b[200~", "ls\r[201~rm", "\u001b[201~"), channel.sent)
    }

    @Test
    fun skippedOutputShowsAMarker() {
        val tab = TerminalTab(1, TerminalConnection(channel, OutputQueue(capacityBytes = 8)), scope) { "skipped $it" }
        tab.start()
        channel.events!!.onOpen()
        output("0123456789ABCDEF")
        tab.sizeChanged(TermSize(40, 10), 10, 20)
        val text = screen(tab)
        assertTrue(text, text.contains("skipped 8"))
        assertTrue(text, text.contains("89ABCDEF"))
    }

    @Test
    fun typedKeysGoOutAndReturnToTheLiveScreen() {
        val tab = tab()
        tab.topRow = -3
        tab.type("q".toByteArray())
        assertEquals(0, tab.topRow)
        assertEquals(listOf("q"), channel.sent)
    }
}
