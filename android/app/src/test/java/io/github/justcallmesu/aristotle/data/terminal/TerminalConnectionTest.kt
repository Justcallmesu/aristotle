package io.github.justcallmesu.aristotle.data.terminal

import io.github.justcallmesu.aristotle.data.ConnectionProblem
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One terminal over a fake socket: first resize, input chunks, debounce, ends, and no reconnect (Q55). */
class TerminalConnectionTest {
    private class FakeChannel : TerminalChannel {
        var opens = 0
        var events: TerminalEvents? = null
        val binary = CopyOnWriteArrayList<ByteArray>()
        val text = CopyOnWriteArrayList<String>()
        var closes = 0
        var cancels = 0

        override fun open(events: TerminalEvents) {
            opens++
            this.events = events
        }

        override fun send(bytes: ByteArray): Boolean {
            binary += bytes
            return true
        }

        override fun sendText(text: String): Boolean {
            this.text += text
            return true
        }

        override fun close() {
            closes++
        }

        override fun cancel() {
            cancels++
        }
    }

    private var now = 0L
    private val channel = FakeChannel()
    private val connection = TerminalConnection(channel, OutputQueue(1024), ResizeGate(quietMillis = 150), clock = { now })

    private fun opened(): TerminalEvents {
        connection.start()
        return channel.events!!.also { it.onOpen() }
    }

    @Test
    fun theSizeGoesFirstRightAfterTheUpgrade() {
        connection.requestResize(TermSize(100, 30))
        opened()
        assertEquals(TerminalState.Open, connection.state.value)
        assertEquals(listOf("""{"type":"resize","cols":100,"rows":30}"""), channel.text)
        // Already sent: the debounce has nothing more to send for that size.
        now += 1_000
        assertFalse(connection.flushResize())
    }

    @Test
    fun withoutAMeasuredSizeTheDefaultGoes() {
        opened()
        assertEquals(listOf("""{"type":"resize","cols":80,"rows":24}"""), channel.text)
    }

    @Test
    fun resizesAreDebouncedAndSentOncePerNewSize() {
        opened()
        connection.requestResize(TermSize(60, 20))
        now += 50
        connection.requestResize(TermSize(61, 20)) // keyboard still animating
        assertEquals(150L, connection.resizeWaitMillis())
        now += 100
        assertFalse(connection.flushResize())
        now += 50
        assertTrue(connection.flushResize())
        assertEquals("""{"type":"resize","cols":61,"rows":20}""", channel.text.last())
        assertEquals(2, channel.text.size)
        connection.requestResize(TermSize(61, 20))
        assertNull(connection.resizeWaitMillis())
    }

    @Test
    fun inputIsSentInChunksOnlyWhileOpen() {
        connection.start()
        assertFalse(connection.send(byteArrayOf(1)))
        channel.events!!.onOpen()
        assertTrue(connection.send(ByteArray(20_000)))
        assertEquals(listOf(16_384, 3_616), channel.binary.map { it.size })
    }

    @Test
    fun outputLandsInTheBoundedQueue() {
        val events = opened()
        events.onOutput("hello".toByteArray())
        assertEquals("hello", String(connection.output.drain(1_000).frames.single()))
    }

    @Test
    fun theExitCodeFromTheMessageIsShownWithTheNormalClose() {
        val events = opened()
        events.onControl(TerminalControl.Exit(42))
        events.onClosed(1000, "exit")
        assertEquals(TerminalState.Ended(TerminalEnd.Exited(42)), connection.state.value)
    }

    @Test
    fun theFirstEndWinsAndNothingReconnects() {
        val events = opened()
        events.onClosed(4403, "terminal_disabled")
        events.onFailed(true, null, null, ConnectionProblem.Unreachable)
        connection.close()
        connection.networkChanged()
        assertEquals(TerminalState.Ended(TerminalEnd.TurnedOff), connection.state.value)
        assertEquals(1, channel.opens)
        assertEquals(0, channel.closes)
        assertEquals(0, channel.cancels)
        assertFalse(connection.send(byteArrayOf(1)))
        assertFalse(connection.flushResize())
    }

    @Test
    fun aConnectionStartsOnce() {
        connection.start()
        val again = runCatching { connection.start() }
        assertTrue(again.isFailure)
        assertEquals(1, channel.opens)
    }

    @Test
    fun aNetworkChangeDropsTheSocketAndSaysSo() {
        opened()
        connection.networkChanged()
        assertEquals(1, channel.cancels)
        assertEquals(TerminalState.Ended(TerminalEnd.ConnectionChanged), connection.state.value)
    }

    @Test
    fun closeEndsTheShellWithANormalCloseAndDropsLaterOutput() {
        val events = opened()
        connection.close()
        assertEquals(1, channel.closes)
        assertEquals(TerminalState.Ended(TerminalEnd.ClosedHere), connection.state.value)
        events.onOutput("late".toByteArray())
        assertTrue(connection.output.drain(1_000).isEmpty)
        events.onClosed(1000, "")
        assertEquals(TerminalState.Ended(TerminalEnd.ClosedHere), connection.state.value)
    }

    @Test
    fun aRefusedUpgradeEndsWithoutOpening() {
        connection.start()
        channel.events!!.onFailed(false, 403, """{"error":"terminal_not_granted"}""", ConnectionProblem.Unreachable)
        assertEquals(TerminalState.Ended(TerminalEnd.NotGranted), connection.state.value)
        assertTrue(channel.text.isEmpty())
    }

    @Test
    fun theGateOnItsOwn() {
        val gate = ResizeGate(quietMillis = 100)
        assertNull(gate.waitMillis(0))
        gate.request(TermSize(80, 24), 0)
        assertNull(gate.due(99))
        assertEquals(TermSize(80, 24), gate.due(100))
        assertNull(gate.due(500))
        gate.request(TermSize(80, 24), 600)
        assertNull(gate.waitMillis(600))
        gate.sentNow(TermSize(90, 24))
        gate.request(TermSize(90, 24), 700)
        assertNull(gate.due(10_000))
    }
}
