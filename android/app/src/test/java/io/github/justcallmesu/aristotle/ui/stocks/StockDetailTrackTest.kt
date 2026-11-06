package io.github.justcallmesu.aristotle.ui.stocks

import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.stocks.RecordLine
import io.github.justcallmesu.aristotle.data.stocks.StocksReply
import io.github.justcallmesu.aristotle.data.stocks.StocksRequest
import io.github.justcallmesu.aristotle.data.stocks.StocksTransport
import io.github.justcallmesu.aristotle.data.stocks.Verdict
import java.util.concurrent.CopyOnWriteArrayList
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TR2: a stock's page reads `track-record?code=` once per showing; the card line and Past calls come from it. */
class StockDetailTrackTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val pc = PcCredentials("pc1", "dev1", listOf(lan), "ff", DeviceToken("t"))
    private val link = MutableStateFlow<LinkState>(LinkState.Connecting)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val sent = CopyOnWriteArrayList<StocksRequest>()
    private val ticks = Channel<Unit>(Channel.UNLIMITED)

    @Volatile
    private var track: StocksReply = StocksReply.Http(200, TRACK)

    private val transport = StocksTransport { _, _, request ->
        sent += request
        when (request.path) {
            "track-record" -> track
            "quotes" -> StocksReply.Http(200, """{"at":"2026-11-05T10:00:00+07:00","market":{"state":"closed"},"quotes":[{"code":"BBCA","price":4930}],"errors":[]}""")
            else -> StocksReply.Http(404, """{"error":"not_found"}""")
        }
    }

    private val vm = StockDetailViewModel("BBCA", pc, link, transport, scope = scope, pause = { ticks.receive() }, retryPause = { ticks.receive() }, analysisPause = { ticks.receive() })

    @After
    fun stop() = scope.cancel()

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(5) }
        } catch (e: Exception) {
            throw AssertionError("$what; sent ${sent.map { "${it.path}${it.query}" }}", e)
        }
    }

    private fun show() {
        link.value = LinkState.Online(lan, 1)
        vm.visible(true)
        waitFor("the record is read") { vm.uiState.value.track != null }
    }

    @Test
    fun `showing reads the record of this code and builds the card line from the whole summary`() {
        show()
        assertEquals(StocksRequest("GET", "track-record", mapOf("code" to "BBCA")), sent.first { it.path == "track-record" })
        assertEquals(RecordLine.Hits(Verdict.BuyToday, hit = 8, decided = 24), vm.uiState.value.recordLine(Verdict.BuyToday))
        assertEquals(RecordLine.None, vm.uiState.value.recordLine(Verdict.Sell))
        assertNull(vm.uiState.value.recordLine(null))
    }

    @Test
    fun `before the record is read there is no line and no list`() {
        assertNull(vm.uiState.value.recordLine(Verdict.BuyToday))
        assertNull(vm.uiState.value.pastCalls)
    }

    @Test
    fun `past calls collapse to three and Show more keeps its state in the ViewModel`() {
        show()
        val view = vm.uiState.value.pastCalls!!
        assertEquals(listOf("vr_5", "vr_4", "vr_3"), view.shown.map { it.id })
        assertEquals(2, view.hidden)
        vm.togglePast()
        assertEquals(5, vm.uiState.value.pastCalls!!.shown.size)
        assertTrue(vm.uiState.value.pastExpanded)
        vm.togglePast()
        assertFalse(vm.uiState.value.pastExpanded)
        assertEquals(3, vm.uiState.value.pastCalls!!.shown.size)
    }

    @Test
    fun `a failed record read leaves the page without a line instead of failing it`() {
        track = StocksReply.Http(404, """{"error":"not_found"}""")
        link.value = LinkState.Online(lan, 1)
        vm.visible(true)
        waitFor("the read was tried") { sent.any { it.path == "track-record" } }
        runBlocking { delay(100) }
        assertNull(vm.uiState.value.track)
        assertNull(vm.uiState.value.recordLine(Verdict.BuyToday))
    }

    private companion object {
        const val TRACK = """{"summary":[
          {"verdict":"buy_today","model":"opus","trigger":"manual","confidence":"med","n":14,"win":3,"loss":4,"closed":1,"not_triggered":6,"result_n":8,"result_sum":2.0},
          {"verdict":"buy_today","model":"sonnet","trigger":"auto","confidence":"low","n":22,"win":5,"loss":6,"closed":5,"not_triggered":6,"result_n":16,"result_sum":1.0}],
          "calls":[{"id":"vr_5","code":"BBCA","verdict":"buy_today"},{"id":"vr_4","code":"BBCA","verdict":"wait"},{"id":"vr_3","code":"BBCA","verdict":"avoid"},{"id":"vr_2","code":"BBCA","verdict":"avoid"},{"id":"vr_1","code":"BBCA","verdict":"avoid"}],
          "scored_through":"2026-11-05","pending":0}"""
    }
}
