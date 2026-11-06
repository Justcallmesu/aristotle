package io.github.justcallmesu.aristotle.ui.stocks

import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.OfflineCause
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.data.stocks.StocksReply
import io.github.justcallmesu.aristotle.data.stocks.StocksRequest
import io.github.justcallmesu.aristotle.data.stocks.StocksTransport
import io.github.justcallmesu.aristotle.data.stocks.StocksParser
import io.github.justcallmesu.aristotle.data.stocks.TrackFilters
import io.github.justcallmesu.aristotle.data.stocks.TrackGroup
import io.github.justcallmesu.aristotle.data.stocks.TrackRecordBody
import io.github.justcallmesu.aristotle.data.stocks.Verdict
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The track record page's states (board "States": no history, only today's, small sample, filters match nothing, PC offline) and its reads. */
class TrackRecordViewModelTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val pc = PcCredentials("pc1", "dev1", listOf(lan), "ff", DeviceToken("t"))
    private val link = MutableStateFlow<LinkState>(LinkState.Connecting)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val sent = CopyOnWriteArrayList<StocksRequest>()
    private val retries = CopyOnWriteArrayList<Unit>()

    @Volatile
    private var reply: StocksReply = StocksReply.Http(200, "{}")

    private val vm = TrackRecordViewModel(pc, link, { _, _, request -> sent += request; reply }, scope = scope, retryLink = { retries += Unit })

    @After
    fun stop() = scope.cancel()

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(5) }
        } catch (e: Exception) {
            throw AssertionError(what, e)
        }
    }

    private fun group(verdict: String, model: String = "opus", trigger: String = "manual", confidence: String = "med", n: Int = 0, win: Int = 0, loss: Int = 0, right: Int = 0, wrong: Int = 0) =
        TrackGroup(verdict = verdict, model = model, trigger = trigger, confidence = confidence, n = n, win = win, loss = loss, right = right, wrong = wrong)

    private fun state(body: TrackRecordBody?, filters: TrackFilters = TrackFilters(), online: Boolean = true, problem: StocksError? = null) =
        TrackUiState(link = if (online) LinkState.Online(lan, 1) else LinkState.Offline(1_000, OfflineCause.Unreachable, "", lan, 1, null), body = body, filters = filters, problem = problem)

    private val body = TrackRecordBody(
        summary = listOf(
            group("buy_today", model = "opus", trigger = "manual", n = 12, win = 5, loss = 5),
            group("avoid", model = "sonnet", trigger = "auto", n = 4, right = 3, wrong = 1),
        ),
        scoredThrough = "2026-11-05",
        pending = 2,
    )

    @Test
    fun `loading, then offline without a cached copy (P14), then the failure`() {
        assertEquals(TrackPageView.Loading, state(null).view())
        assertEquals(TrackPageView.Offline, state(body, online = false).view())
        assertEquals(TrackPageView.Offline, state(null, online = false).view())
        val error = StocksError.Server(500, "state_error")
        assertEquals(TrackPageView.Failed(error), state(null, problem = error).view())
    }

    @Test
    fun `no history and only today's verdicts`() {
        assertEquals(TrackPageView.NoHistory, state(TrackRecordBody()).view())
        assertEquals(TrackPageView.OnlyPending(3), state(TrackRecordBody(pending = 3)).view())
    }

    @Test
    fun `content carries the cards, the totals, the pending count and the options`() {
        val view = state(body).view() as TrackPageView.Content
        assertEquals(listOf(Verdict.BuyToday, Verdict.Avoid), view.records.map { it.verdict })
        assertEquals(16, view.shown)
        assertEquals(16, view.total)
        assertFalse(view.noMatch)
        assertEquals(2, view.pending)
        assertEquals("2026-11-05", view.scoredThrough)
        assertEquals(listOf("opus", "sonnet"), view.options.models)
        // Small sample: the Avoid card is there with its counts, the rate is the card's to withhold.
        assertFalse(view.records[1].hasRate)
        assertTrue(view.records[0].hasRate)
    }

    @Test
    fun `filters keep the groups that match and the header says how many`() {
        val view = state(body, TrackFilters(model = "sonnet")).view() as TrackPageView.Content
        assertEquals(listOf(Verdict.Avoid), view.records.map { it.verdict })
        assertEquals(4, view.shown)
        assertEquals(16, view.total)
    }

    @Test
    fun `filters that match nothing say so and keep the options so they can be cleared`() {
        val view = state(body, TrackFilters(model = "opus", trigger = "auto")).view() as TrackPageView.Content
        assertTrue(view.noMatch)
        assertEquals(0, view.shown)
        assertEquals(listOf("opus", "sonnet"), view.options.models)
    }

    @Test
    fun `the page reads the record when shown and reachable, not before`() {
        vm.pageVisible(true)
        link.value = LinkState.Offline(1_000, OfflineCause.Unreachable, "", lan, 1, null)
        runBlocking { delay(100) }
        assertTrue(sent.isEmpty())
        reply = StocksReply.Http(200, """{"summary":[{"verdict":"avoid","model":"opus","trigger":"manual","confidence":"med","n":2,"right":2}],"calls":[],"scored_through":"2026-11-05","pending":0}""")
        link.value = LinkState.Online(lan, 1)
        waitFor("the record is read") { vm.uiState.value.body != null }
        assertEquals(listOf(StocksRequest("GET", "track-record")), sent.toList())
        assertEquals(2, StocksParser.decode("""{"summary":[{"n":2}]}""", TrackRecordBody.serializer())!!.summary[0].n)
    }

    @Test
    fun `a refused read shows its error, retry reads again, and offline retry goes to the link`() {
        reply = StocksReply.Http(500, """{"error":"state_error"}""")
        link.value = LinkState.Online(lan, 1)
        vm.pageVisible(true)
        waitFor("the failure") { vm.uiState.value.problem != null }
        assertEquals(TrackPageView.Failed(StocksError.Server(500, "state_error")), vm.uiState.value.view())
        reply = StocksReply.Http(200, """{"summary":[],"calls":[],"pending":1}""")
        vm.retry()
        waitFor("the second read") { vm.uiState.value.body != null }
        assertNull(vm.uiState.value.problem)
        assertEquals(TrackPageView.OnlyPending(1), vm.uiState.value.view())
        link.value = LinkState.Offline(1_000, OfflineCause.Unreachable, "", lan, 1, null)
        waitFor("offline seen") { !vm.uiState.value.reachable }
        vm.retry()
        assertEquals(1, retries.size)
    }

    @Test
    fun `filters set and clear, and the sheet opens and closes`() {
        vm.setModel("opus")
        vm.setTrigger("auto")
        vm.setConfidence("high")
        assertEquals(TrackFilters("opus", "auto", "high"), vm.uiState.value.filters)
        assertTrue(vm.uiState.value.filters.any)
        vm.setTrigger(null)
        assertEquals(TrackFilters("opus", null, "high"), vm.uiState.value.filters)
        vm.clearFilters()
        assertFalse(vm.uiState.value.filters.any)
        assertFalse(vm.uiState.value.sheet)
        vm.showSheet(true)
        assertTrue(vm.uiState.value.sheet)
        vm.showSheet(false)
        assertNotNull(vm.uiState.value)
        assertFalse(vm.uiState.value.sheet)
    }
}
