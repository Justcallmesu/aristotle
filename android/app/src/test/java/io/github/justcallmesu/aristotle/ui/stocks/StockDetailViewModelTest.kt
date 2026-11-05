package io.github.justcallmesu.aristotle.ui.stocks

import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.OfflineCause
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.stocks.ChartRange
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.data.stocks.StocksReply
import io.github.justcallmesu.aristotle.data.stocks.StocksRequest
import io.github.justcallmesu.aristotle.data.stocks.StocksTransport
import java.time.OffsetDateTime
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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A stock's page over a fake PC: what is read on showing, per range change and on each 60 s tick (the Stocks home's
 * rules, [io.github.justcallmesu.aristotle.data.stocks.QuoteRefresh]), and how a refusal shows.
 */
class StockDetailViewModelTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val pc = PcCredentials("pc1", "dev1", listOf(lan), "ff", DeviceToken("t"))
    private val link = MutableStateFlow<LinkState>(LinkState.Connecting)
    // One thread, like the main thread the page runs on.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val now = AtomicLong(OffsetDateTime.parse("2026-11-05T10:00:00+07:00").toInstant().toEpochMilli())
    private val sent = CopyOnWriteArrayList<StocksRequest>()
    private val pauses = CopyOnWriteArrayList<Long>()
    private val ticks = Channel<Unit>(Channel.UNLIMITED)

    @Volatile
    private var market = """{"state":"open","session":1,"closes_at":"2026-11-05T12:00:00+07:00"}"""

    @Volatile
    private var blockSixMonths = false

    @Volatile
    private var staleSixMonths = false

    private fun fixture(name: String): String = javaClass.classLoader!!.getResource("stocks/$name")!!.readText()

    private val transport = StocksTransport { _, _, request ->
        sent += request
        when {
            request.path == "quotes" -> StocksReply.Http(200, """{"at":"2026-11-05T10:00:00+07:00","market":$market,"quotes":[{"code":"BBCA","price":4930,"prev_close":4840,"change":90}],"errors":[]}""")
            request.path == "chart/BBCA" && request.query["range"] == "6mo" && blockSixMonths ->
                StocksReply.Http(503, fixture("error_upstream_blocked.json"), retryAfterSeconds = 60)
            request.path == "chart/BBCA" && request.query["range"] == "6mo" && staleSixMonths ->
                StocksReply.Http(200, fixture("chart_5d.json").replace("\"stale\":false", "\"stale\":true"))
            request.path == "chart/BBCA" -> StocksReply.Http(200, fixture("chart_5d.json"))
            request.path == "news" -> StocksReply.Http(200, fixture("news_antm.json"))
            else -> StocksReply.Http(404, """{"error":"not_found"}""")
        }
    }

    private val retries = CopyOnWriteArrayList<Long>()
    private val retryGate = Channel<Unit>(Channel.UNLIMITED)

    private val vm = StockDetailViewModel(
        "BBCA", pc, link, transport, scope = scope, clock = { now.get() },
        pause = { pauses += it; ticks.receive() },
        retryPause = { retries += it; retryGate.receive() },
    )

    @After
    fun stop() = scope.cancel()

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(5) }
        } catch (e: Exception) {
            throw AssertionError("$what; sent ${sent.map { "${it.path}${it.query}" }}", e)
        }
    }

    private fun settle() = runBlocking { delay(100) }

    private fun charts(range: String) = sent.count { it.path == "chart/BBCA" && it.query["range"] == range }
    private fun quotes() = sent.count { it.path == "quotes" }

    private fun show() {
        link.value = LinkState.Online(lan, 1)
        vm.visible(true)
        waitFor("the first reads") { quotes() == 1 && charts("1d") == 1 && charts("1mo") == 1 && sent.any { it.path == "news" } }
    }

    @Test
    fun nothingIsReadUntilShownAndReachable() {
        vm.visible(true)
        link.value = LinkState.Offline(1_000, OfflineCause.Unreachable, "", lan, 1, null)
        settle()
        assertTrue(sent.isEmpty())
        link.value = LinkState.HermesDown("close 4503", lan)
        waitFor("reads once the server answers, Hermes or not") { quotes() == 1 && charts("1mo") == 1 }
    }

    @Test
    fun showingReadsTheQuoteTodayTheDefaultRangeAndItsNews() {
        show()
        assertEquals(listOf("BBCA"), sent.first { it.path == "quotes" }.query["codes"]?.split(","))
        assertEquals(mapOf("code" to "BBCA", "limit" to "5"), sent.first { it.path == "news" }.query)
        val state = vm.uiState.value
        assertEquals(ChartRange.Month, state.range)
        assertEquals(34, state.chart.body!!.bars.size)
        assertEquals(4930L, state.quote!!.price)
        assertTrue(state.news!!.isNotEmpty())
    }

    @Test
    fun aRangeIsReadOnceAndKeptWhileThePageLives() {
        show()
        vm.selectRange(ChartRange.SixMonths)
        waitFor("6M read") { charts("6mo") == 1 }
        vm.selectRange(ChartRange.Month)
        vm.selectRange(ChartRange.SixMonths)
        settle()
        assertEquals("cached: no second read", 1, charts("6mo"))
        assertEquals(1, charts("1mo"))
        vm.selectType(ChartType.Line)
        assertEquals(ChartType.Line, vm.uiState.value.type)
    }

    @Test
    fun aStaleRangeIsReadAgainOnPickAndRetryAndThenKept() {
        staleSixMonths = true
        show()
        vm.selectRange(ChartRange.SixMonths)
        waitFor("6M read stale") { vm.uiState.value.chart.body?.stale == true }
        assertEquals(1, charts("6mo"))
        // Not cached for the page's life: picking it again reads it again.
        vm.selectRange(ChartRange.Month)
        vm.selectRange(ChartRange.SixMonths)
        waitFor("a second 6M read") { charts("6mo") == 2 }
        // Retry (the stale line's button) reads again and the fresh answer replaces it.
        staleSixMonths = false
        vm.retry()
        waitFor("fresh 6M") { vm.uiState.value.chart.body?.stale == false }
        assertEquals(3, charts("6mo"))
        vm.selectRange(ChartRange.Month)
        vm.selectRange(ChartRange.SixMonths)
        settle()
        assertEquals("fresh bars are cached again", 3, charts("6mo"))
    }

    @Test
    fun everyMinuteWhileOpenTheQuoteAndTodayAreReadAgain() {
        show()
        waitFor("waits a minute") { pauses.toList() == listOf(60_000L) }
        now.addAndGet(60_000)
        ticks.trySend(Unit)
        waitFor("second tick") { quotes() == 2 && vm.uiState.value.charts[ChartRange.Day]?.fetchedAt == now.get() }
        assertEquals(2, charts("1d"))
        assertEquals("other ranges stay cached", 1, charts("1mo"))
        // Switching to 1D within the minute uses what the tick read.
        vm.selectRange(ChartRange.Day)
        settle()
        assertEquals(2, charts("1d"))
        // Hidden: the next tick reads nothing.
        vm.visible(false)
        settle()
        ticks.trySend(Unit)
        settle()
        assertEquals(2, quotes())
    }

    @Test
    fun outsideASessionItWaitsForTheNextOpen() {
        market = """{"state":"closed","next_open":"2026-11-06T09:00:00+07:00"}"""
        show()
        waitFor("one wait until 09:00 tomorrow") { pauses.size == 1 }
        assertEquals(23L * 3_600_000, pauses.single())
    }

    @Test
    fun aBlockedRangeSaysSoAndRetries() {
        blockSixMonths = true
        show()
        vm.selectRange(ChartRange.SixMonths)
        waitFor("6M refused") { vm.uiState.value.chart.problem != null }
        val slot = vm.uiState.value.chart
        assertEquals(StocksError.Server(503, StocksError.UPSTREAM_BLOCKED, 60), slot.problem)
        assertNull(slot.body)
        waitFor("a retry after retry_after_s") { retries.toList() == listOf(60_000L) }
        blockSixMonths = false
        vm.retry()
        waitFor("6M after Retry now") { vm.uiState.value.chart.body != null }
        assertNull(vm.uiState.value.chart.problem)
    }

    @Test
    fun aBlockedRangeIsReadAgainOnItsOwn() {
        blockSixMonths = true
        show()
        vm.selectRange(ChartRange.SixMonths)
        waitFor("waits retry_after_s") { retries.size == 1 }
        blockSixMonths = false
        retryGate.trySend(Unit)
        waitFor("read again") { vm.uiState.value.chart.body != null }
        assertEquals(2, charts("6mo"))
    }
}
