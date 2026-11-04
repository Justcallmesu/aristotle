package io.github.justcallmesu.aristotle.data

import io.github.justcallmesu.aristotle.data.chat.PlanLimitsLogic
import io.github.justcallmesu.aristotle.data.chat.PlanUsage
import io.github.justcallmesu.aristotle.data.chat.PlanWindow
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TY7 (Q83): the Home limits row's call, refresh rule (fake clock) and poller lifecycle. */
class HomeLimitsTest {
    private val minute = 60_000L

    private fun usage(percent: Double = 40.0) =
        PlanUsage(true, listOf(PlanWindow("Current session", percent, null, null)))

    // ── the call and the pure rule ────────────────────────────────────────────────────────────────

    @Test
    fun `the call has no session_id, and a profile only when it is not the launch one`() {
        assertEquals(emptySet<String>(), HomeLimitsFrames.options(null).keys)
        assertEquals(setOf("profile"), HomeLimitsFrames.options("pm").keys)
        assertEquals("model.options", HomeLimitsFrames.METHOD)
    }

    @Test
    fun `due when never read, five minutes after the last read, or after the clock went back`() {
        assertTrue(LimitsRefresh.due(null, 0))
        assertFalse(LimitsRefresh.due(1_000, 1_000 + 5 * minute - 1))
        assertTrue(LimitsRefresh.due(1_000, 1_000 + 5 * minute))
        assertTrue(LimitsRefresh.due(10_000, 5_000))
    }

    @Test
    fun `the wait runs down to the next due time`() {
        assertEquals(0, LimitsRefresh.waitMillis(null, 5))
        assertEquals(5 * minute, LimitsRefresh.waitMillis(1_000, 1_000))
        assertEquals(minute, LimitsRefresh.waitMillis(1_000, 1_000 + 4 * minute))
        assertEquals(0, LimitsRefresh.waitMillis(1_000, 1_000 + 6 * minute))
    }

    @Test
    fun `a reading without the Anthropic provider clears, a cold cache keeps the last numbers`() {
        val shown = HomeLimits(usage(), 100)
        assertEquals(HomeLimits.None, LimitsRefresh.merge(shown, PlanUsage.NONE, 200))
        assertEquals(shown, LimitsRefresh.merge(shown, PlanUsage(present = true, windows = emptyList()), 200))
        assertEquals(HomeLimits(usage(55.0), 200), LimitsRefresh.merge(shown, usage(55.0), 200))
    }

    @Test
    fun `the row is absent without data and hides after 15 minutes`() {
        assertFalse(HomeLimits.None.shown(0))
        assertFalse(HomeLimits(PlanUsage(true, emptyList()), 0).shown(0))
        assertTrue(HomeLimits(usage(), 0).shown(PlanUsage.STALE_MS - 1))
        assertFalse(HomeLimits(usage(), 0).shown(PlanUsage.STALE_MS))
    }

    @Test
    fun `red from 90 percent`() {
        assertEquals(90.0, PlanLimitsLogic.RED_AT, 0.0)
    }

    // ── the poller ────────────────────────────────────────────────────────────────────────────────

    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val link = MutableStateFlow<LinkState>(LinkState.Connecting)
    private val visible = MutableStateFlow(false)
    private val profile = MutableStateFlow<String?>(null)
    private val now = AtomicLong(1_000)
    private val reads = CopyOnWriteArrayList<String?>()
    private val pauses = CopyOnWriteArrayList<Long>()
    private val ticks = Channel<Unit>(Channel.UNLIMITED)
    private var answer: () -> PlanUsage = { usage(reads.size.toDouble()) }

    private val poller = HomeLimitsPoller(
        scope, link, visible, profile,
        read = { p -> reads += p; answer() },
        clock = { now.get() },
        pause = { pauses += it; ticks.receive() },
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

    private fun settle() = runBlocking { delay(100) }

    private fun show() {
        poller.start()
        link.value = LinkState.Online(lan, 1)
        visible.value = true
    }

    @Test
    fun `reads when Home shows, then waits five minutes`() {
        show()
        waitFor("first read") { reads.size == 1 }
        waitFor("pause") { pauses.isNotEmpty() }
        assertEquals(5 * minute, pauses.single())
        assertEquals(1.0, poller.view.value.usage.windows.single().usedPercent, 0.0)
        assertEquals(1_000L, poller.view.value.fetchedAtMillis)
    }

    @Test
    fun `a wake before five minutes reads nothing, one after reads`() {
        show()
        waitFor("first read") { reads.size == 1 }
        now.set(1_000 + 2 * minute) // woke early (a spurious wake)
        ticks.trySend(Unit)
        waitFor("second pause") { pauses.size == 2 }
        assertEquals(1, reads.size)
        assertEquals(3 * minute, pauses[1]) // only the rest of the five minutes
        now.set(1_000 + 5 * minute)
        ticks.trySend(Unit)
        waitFor("second read") { reads.size == 2 }
    }

    @Test
    fun `hiding Home stops it, showing again within five minutes does not read`() {
        show()
        waitFor("first read") { reads.size == 1 }
        visible.value = false
        settle()
        now.set(1_000 + minute)
        visible.value = true
        settle()
        assertEquals(1, reads.size)
        assertEquals(4 * minute, pauses.last())
    }

    @Test
    fun `showing again after five minutes reads at once`() {
        show()
        waitFor("first read") { reads.size == 1 }
        visible.value = false
        settle()
        now.set(1_000 + 6 * minute)
        visible.value = true
        waitFor("read on return") { reads.size == 2 }
    }

    @Test
    fun `nothing is read while offline or hidden`() {
        poller.start()
        visible.value = true // offline
        settle()
        assertEquals(0, reads.size)
        visible.value = false
        link.value = LinkState.Online(lan, 1) // online
        settle()
        assertEquals(0, reads.size)
    }

    @Test
    fun `pull to refresh forces a read inside the five minutes`() {
        show()
        waitFor("first read") { reads.size == 1 }
        waitFor("pause") { pauses.isNotEmpty() }
        poller.force()
        waitFor("forced read") { reads.size == 2 }
    }

    @Test
    fun `a pull while hidden is not a read when Home shows`() {
        poller.start()
        link.value = LinkState.Online(lan, 1)
        poller.force()
        settle()
        visible.value = true
        waitFor("first read") { reads.size == 1 }
        settle()
        assertEquals(1, reads.size)
    }

    @Test
    fun `a failed read changes nothing and is not retried before the next tick`() {
        show()
        waitFor("first read") { reads.size == 1 }
        answer = { throw RpcException(null, "model.options failed: 5000") }
        now.set(1_000 + 5 * minute)
        ticks.trySend(Unit)
        waitFor("second read") { reads.size == 2 }
        waitFor("second pause") { pauses.size == 2 }
        assertEquals(5 * minute, pauses[1])
        assertEquals(1.0, poller.view.value.usage.windows.single().usedPercent, 0.0)
    }

    @Test
    fun `another profile drops the old numbers and reads`() {
        show()
        waitFor("first read") { reads.size == 1 }
        answer = { PlanUsage(present = true, windows = emptyList()) }
        profile.value = "pm"
        waitFor("pm read") { reads.size == 2 }
        assertEquals(listOf<String?>(null, "pm"), reads.toList())
        assertEquals(HomeLimits.None, poller.view.value)
    }
}
