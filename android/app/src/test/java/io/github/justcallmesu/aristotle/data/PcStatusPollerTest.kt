package io.github.justcallmesu.aristotle.data

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Home poller (Q78): every 10 s only while Home is visible and the link is online; a fake clock and a manual pause. */
class PcStatusPollerTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val link = MutableStateFlow<LinkState>(LinkState.Connecting)
    private val visible = MutableStateFlow(false)
    private val now = AtomicLong(1_000)
    private val reads = CopyOnWriteArrayList<ServerEndpoint>()
    private val pauses = CopyOnWriteArrayList<Long>()
    private val ticks = Channel<Unit>(Channel.UNLIMITED)

    /** One signal per pause the poller enters: a pause starts only after the read and the view update of its round. */
    private val paused = Channel<Unit>(Channel.UNLIMITED)
    private var answer: () -> PcStatus? = { PcStatus(cpuPercent = reads.size.toDouble()) }

    private val poller = PcStatusPoller(
        scope, link, visible,
        read = { endpoint -> reads += endpoint; answer() },
        intervalMillis = 10_000,
        clock = { now.get() },
        pause = { pauses += it; paused.trySend(Unit); ticks.receive() },
    )

    @After
    fun stop() = scope.cancel()

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(5) }
        } catch (e: Exception) {
            throw AssertionError(what, e)
        }
    }

    /** Waits for the poller's next pause: the round's read is done and [PcStatusPoller.view] holds its result. */
    private fun awaitPause(what: String) = runBlocking {
        try {
            withTimeout(5_000) { paused.receive() }
        } catch (e: Exception) {
            throw AssertionError(what, e)
        }
    }

    private fun settle() = runBlocking { delay(100) }

    @Test
    fun readsEveryTenSecondsWhileVisibleAndOnline() {
        poller.start()
        link.value = LinkState.Online(lan, 1)
        visible.value = true
        awaitPause("first round")
        assertEquals(listOf(10_000L), pauses.toList())
        now.set(11_000)
        ticks.trySend(Unit)
        awaitPause("second round")
        assertEquals(11_000L, poller.view.value.fetchedAtMillis)
        assertEquals(2.0, poller.view.value.status!!.cpuPercent!!, 0.0)
    }

    @Test
    fun nothingIsReadUntilBothHold() {
        poller.start()
        visible.value = true
        settle()
        assertEquals(0, reads.size) // not online
        visible.value = false
        link.value = LinkState.Online(lan, 1)
        settle()
        assertEquals(0, reads.size) // not visible
        assertEquals(PcStatusView.None, poller.view.value)
    }

    @Test
    fun stopsWhenHomeIsHiddenAndKeepsTheLastValue() {
        poller.start()
        link.value = LinkState.Online(lan, 1)
        visible.value = true
        awaitPause("first round")
        visible.value = false
        settle()
        ticks.trySend(Unit)
        settle()
        assertEquals(1, reads.size)
        assertEquals(1.0, poller.view.value.status!!.cpuPercent!!, 0.0) // the first read's value stays
        ticks.tryReceive() // the tick nobody waited for must not trigger a second read on return
        visible.value = true
        waitFor("read on return") { reads.size == 2 } // starts with a read, not after a wait
    }

    @Test
    fun stopsWhenTheLinkGoesOffline() {
        poller.start()
        link.value = LinkState.Online(lan, 1)
        visible.value = true
        awaitPause("first round")
        link.value = LinkState.Connecting
        settle()
        ticks.trySend(Unit)
        settle()
        assertEquals(1, reads.size)
        assertEquals(1_000L, poller.view.value.fetchedAtMillis)
    }

    @Test
    fun aFailedReadKeepsTheLastValueAndTriesAgain() {
        poller.start()
        link.value = LinkState.Online(lan, 1)
        visible.value = true
        awaitPause("first round")
        answer = { null }
        ticks.trySend(Unit)
        waitFor("failed read") { reads.size == 2 }
        waitFor("paused again") { pauses.size == 2 }
        assertEquals(1_000L, poller.view.value.fetchedAtMillis)
        answer = { throw IllegalStateException("boom") }
        ticks.trySend(Unit)
        waitFor("throwing read") { reads.size == 3 }
        waitFor("paused after throw") { pauses.size == 3 }
        assertEquals(1.0, poller.view.value.status!!.cpuPercent!!, 0.0)
    }

    @Test
    fun aNewConnectionReadsAtOnceFromItsAddress() {
        poller.start()
        link.value = LinkState.Online(lan, 1)
        visible.value = true
        awaitPause("first round")
        val tailscale = ServerEndpoint("100.74.61.96", 8760, Network.Tailscale)
        link.value = LinkState.Online(tailscale, 2)
        waitFor("read on the new address") { reads.size == 2 }
        assertEquals(tailscale, reads.last())
    }

    @Test
    fun aReadingWithoutCpuKeepsTheLastValueAsStaleUntilAFreshOne() {
        poller.start()
        link.value = LinkState.Online(lan, 1)
        visible.value = true
        awaitPause("first round")
        answer = { PcStatus(cpuPercent = null, hermesRunning = true) }
        ticks.trySend(Unit)
        waitFor("second read") { reads.size == 2 }
        waitFor("carried") { poller.view.value.status?.cpuStale == true }
        assertEquals(1.0, poller.view.value.status!!.cpuPercent!!, 0.0)
        answer = { PcStatus(cpuPercent = 40.0) }
        ticks.trySend(Unit)
        waitFor("fresh") { poller.view.value.status?.cpuPercent == 40.0 }
        assertEquals(false, poller.view.value.status!!.cpuStale)
    }

    @Test
    fun carryCpuOnlyFillsAGapInAReadingThatHasOtherData() {
        val old = PcStatus(cpuPercent = 30.0)
        assertEquals(PcStatus(cpuPercent = 30.0, hermesRunning = true, cpuStale = true), carryCpu(old, PcStatus(hermesRunning = true)))
        assertEquals(PcStatus(cpuPercent = 5.0), carryCpu(old, PcStatus(cpuPercent = 5.0)))
        assertEquals(PcStatus(), carryCpu(old, PcStatus())) // nothing at all: nothing to carry into
        assertEquals(PcStatus(hermesRunning = true), carryCpu(null, PcStatus(hermesRunning = true)))
        assertEquals(PcStatus(hermesRunning = true), carryCpu(PcStatus(hermesRunning = false), PcStatus(hermesRunning = true)))
    }

    @Test
    fun startsEmpty() {
        assertNull(poller.view.value.status)
    }
}
