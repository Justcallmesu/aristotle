package io.github.justcallmesu.aristotle.ui.running

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.RpcFrames
import io.github.justcallmesu.aristotle.data.RunningStatus
import io.github.justcallmesu.aristotle.data.RunningTimings
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.ui.PairedPage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TY1: the Running page's poll lifecycle (visible AND online only, sessions.changed, pull to refresh) and its state. */
class RunningViewModelTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    private class FakeLink(@Volatile var answer: (String) -> JsonElement) : RelayLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Connecting)
        override val events = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
        val calls = CopyOnWriteArrayList<String>()
        val retries = AtomicInteger()

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            calls += method
            return answer(method)
        }

        override fun respond(requestId: String, result: JsonObject) = false
        override fun retryNow() {
            retries.incrementAndGet()
        }

        val reads: Int get() = calls.count { it == "profiles.list" }
    }

    private fun json(text: String): JsonElement = HermesJson.parseToJsonElement(text)

    @Volatile
    private var status = "working"

    private fun hermes(method: String): JsonElement = when (method) {
        "profiles.list" -> json("""{"profiles":[{"name":"default","display_name":"Default"}]}""")
        "session.active_list" -> json("""{"sessions":[{"id":"s1","session_key":"s1","title":"Build","status":"$status","started_at":1}]}""")
        "subagent.roster" -> json("""{"sessions":[]}""")
        else -> error(method)
    }

    private fun link() = FakeLink(::hermes)

    private fun viewModel(link: FakeLink, timings: RunningTimings = RunningTimings(pollMillis = 150, changeWindowMillis = 40)) =
        RunningViewModel(link, timings, scope)

    private fun eventually(timeoutMillis: Long = 3_000, what: String = "condition", check: () -> Boolean) {
        val until = System.currentTimeMillis() + timeoutMillis
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun online(link: FakeLink, generation: Long = 1) {
        link.state.value = LinkState.Online(lan, generation)
    }

    @Test
    fun `nothing is read while the page is not visible, even online`() {
        val link = link()
        viewModel(link)
        online(link)
        Thread.sleep(400) // more than two poll periods
        assertEquals(0, link.calls.size)
    }

    @Test
    fun `nothing is read while visible but offline, going online reads at once`() {
        val link = link()
        val vm = viewModel(link, RunningTimings(pollMillis = 60_000, changeWindowMillis = 40))
        vm.setVisible(true)
        Thread.sleep(200)
        assertEquals(0, link.calls.size)
        online(link)
        eventually(what = "first read") { vm.uiState.value.snapshot != null }
        assertEquals(1, link.reads)
        assertEquals("s1", vm.uiState.value.snapshot!!.groups.single().rows.single().sessionId)
    }

    @Test
    fun `visible and online polls every period, hiding stops it`() {
        val link = link()
        val vm = viewModel(link)
        online(link)
        vm.setVisible(true)
        eventually(what = "three reads") { link.reads >= 3 }
        vm.setVisible(false)
        Thread.sleep(100) // a read under way finishes or is cancelled
        val stopped = link.reads
        Thread.sleep(500)
        assertEquals(stopped, link.reads)
    }

    @Test
    fun `showing the page again reads at once, not a period later`() {
        val link = link()
        val vm = viewModel(link, RunningTimings(pollMillis = 60_000, changeWindowMillis = 40))
        online(link)
        vm.setVisible(true)
        eventually(what = "first read") { link.reads == 1 }
        vm.setVisible(false)
        Thread.sleep(50)
        vm.setVisible(true)
        eventually(what = "read on return") { link.reads == 2 }
    }

    @Test
    fun `going offline stops the poll`() {
        val link = link()
        val vm = viewModel(link)
        online(link)
        vm.setVisible(true)
        eventually(what = "first read") { vm.uiState.value.snapshot != null }
        link.state.value = LinkState.Connecting
        Thread.sleep(100)
        val stopped = link.reads
        Thread.sleep(500)
        assertEquals(stopped, link.reads)
        assertFalse(vm.uiState.value.online)
        assertTrue(vm.uiState.value.snapshot != null) // the last read stays on screen
    }

    @Test
    fun `sessions_changed reads again within its window, coalesced`() {
        val link = link()
        val vm = viewModel(link, RunningTimings(pollMillis = 60_000, changeWindowMillis = 80))
        online(link)
        vm.setVisible(true)
        eventually(what = "first read") { link.reads == 1 }
        status = "waiting"
        repeat(5) { link.events.tryEmit(InboundFrame.Event(RpcFrames.SESSIONS_CHANGED, "", null)) }
        eventually(what = "change read") { link.reads == 2 }
        Thread.sleep(300)
        assertEquals(2, link.reads) // five events in one window: one read
        eventually(what = "new status") {
            vm.uiState.value.snapshot?.groups?.single()?.rows?.single()?.status == RunningStatus.Waiting
        }
    }

    @Test
    fun `a sessions_changed read waits out the window since the last read`() {
        val link = link()
        // Poll 150 ms, window 400 ms: the poll reads at 0 and 150; an event right after must not read before 150 + 400.
        val vm = viewModel(link, RunningTimings(pollMillis = 150, changeWindowMillis = 400))
        online(link)
        vm.setVisible(true)
        eventually(what = "first read") { link.reads == 1 }
        eventually(what = "second read") { link.reads == 2 }
        val secondAt = System.currentTimeMillis()
        link.events.tryEmit(InboundFrame.Event(RpcFrames.SESSIONS_CHANGED, "", null))
        eventually(what = "third read") { link.reads >= 3 }
        // the poll's own cadence (150 ms) is the only thing that may read sooner than the window
        assertTrue(System.currentTimeMillis() - secondAt >= 100)
    }

    @Test
    fun `the default change window is the Desktop's 10 s`() {
        assertEquals(10_000L, RunningTimings().changeWindowMillis)
        assertEquals(10_000L, RunningTimings().pollMillis)
    }

    @Test
    fun `sessions_changed while hidden reads nothing`() {
        val link = link()
        viewModel(link, RunningTimings(pollMillis = 60_000, changeWindowMillis = 20))
        online(link)
        repeat(3) { link.events.tryEmit(InboundFrame.Event(RpcFrames.SESSIONS_CHANGED, "", null)) }
        Thread.sleep(200)
        assertEquals(0, link.calls.size)
    }

    @Test
    fun `pull to refresh reads at once, offline it retries the link instead`() {
        val link = link()
        val vm = viewModel(link, RunningTimings(pollMillis = 60_000, changeWindowMillis = 40))
        vm.setVisible(true)
        vm.refresh()
        assertEquals(1, link.retries.get())
        assertFalse(vm.uiState.value.refreshing)
        online(link)
        eventually(what = "first read") { link.reads == 1 }
        vm.refresh()
        eventually(what = "refresh read") { link.reads == 2 && !vm.uiState.value.refreshing }
    }

    @Test
    fun `a profiles_list failure is the page's problem, the next good read clears it`() {
        val link = link()
        link.answer = { throw RpcException(null, "profiles.list failed: 5000") }
        val vm = viewModel(link, RunningTimings(pollMillis = 60_000, changeWindowMillis = 40))
        online(link)
        vm.setVisible(true)
        eventually(what = "problem") { vm.uiState.value.problem != null }
        assertEquals("profiles.list failed: 5000", vm.uiState.value.problem)
        assertNull(vm.uiState.value.snapshot)
        link.answer = ::hermes
        vm.retry()
        eventually(what = "recovered") { vm.uiState.value.problem == null && vm.uiState.value.snapshot != null }
    }

    @Test
    fun `a closed link is not a page problem`() {
        val link = link()
        link.answer = { throw RpcException(null, "socket not open", linkClosed = true) }
        val vm = viewModel(link, RunningTimings(pollMillis = 60_000, changeWindowMillis = 40))
        online(link)
        vm.setVisible(true)
        eventually(what = "a read") { link.reads == 1 }
        Thread.sleep(50)
        assertNull(vm.uiState.value.problem)
    }

    @Test
    fun `the Running page goes back to Home`() {
        assertEquals(PairedPage.Sessions, PairedPage.Running.back)
    }
}
