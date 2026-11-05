package io.github.justcallmesu.aristotle.ui.stocks

import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.OfflineCause
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.stocks.SearchResult
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.data.stocks.StocksReply
import io.github.justcallmesu.aristotle.data.stocks.StocksRequest
import io.github.justcallmesu.aristotle.data.stocks.StocksTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stocks pages' ViewModel over a fake PC: calls only while online, reorder shown at once and put back on refusal. */
class StocksViewModelTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val pc = PcCredentials("pc1", "dev1", listOf(lan), "ff", DeviceToken("t"))
    private val link = MutableStateFlow<LinkState>(LinkState.Connecting)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = mutableListOf<StocksRequest>()
    private val to = mutableListOf<ServerEndpoint>()
    private var watch = listOf("BBCA", "BBRI", "TLKM")
    private var refuseReorder = false

    private fun list(codes: List<String>) = StocksReply.Http(200, """{"items":[${codes.joinToString(",") { "{\"code\":\"$it\",\"name\":\"$it Tbk\"}" }}]}""")

    private val transport = StocksTransport { _, endpoint, request ->
        sent += request
        to += endpoint
        when {
            request.path == "watchlist" && request.method == "GET" -> list(watch)
            request.path == "watchlist" && request.method == "PUT" ->
                if (refuseReorder) StocksReply.Http(400, """{"error":"bad_request"}""") else list(watch.reversed()).also { watch = watch.reversed() }
            request.path == "watchlist/GOTO" -> StocksReply.Http(409, """{"error":"limit_reached"}""")
            request.path == "quotes" -> StocksReply.Http(200, """{"at":"2026-11-05T10:00:00+07:00","market":{"state":"open","session":1,"closes_at":"2026-11-05T12:00:00+07:00"},"quotes":[],"errors":[]}""")
            request.path == "holdings" -> StocksReply.Http(200, """{"at":"2026-11-05T10:00:00+07:00","holdings":[],"total":{"cost":0}}""")
            request.path == "news" -> StocksReply.Http(200, """{"items":[],"sources":[]}""")
            else -> StocksReply.Http(404, """{"error":"not_found"}""")
        }
    }

    private val vm = StocksViewModel(pc, link, transport, scope = scope, pause = { awaitCancellation() }, searchPause = {})

    @After
    fun stop() = scope.cancel()

    private fun shown() {
        link.value = LinkState.Online(lan, 1)
        vm.homeVisible(true)
    }

    @Test
    fun nothingIsCalledUntilVisibleAndOnline() {
        vm.homeVisible(true)
        assertTrue(sent.isEmpty())
        link.value = LinkState.Offline(1_000, OfflineCause.Unreachable, "", lan, 1, null)
        assertTrue(sent.isEmpty())
        link.value = LinkState.Online(lan, 1)
        assertTrue(sent.map { it.path }.containsAll(listOf("watchlist", "news", "quotes", "holdings")))
        assertEquals(listOf("BBCA", "BBRI", "TLKM"), vm.uiState.value.watchCodes)
        assertTrue(vm.uiState.value.quotes!!.market.isOpen)
    }

    private val tailnet = ServerEndpoint("100.74.61.96", 8760, Network.Tailscale)

    /** Stocks need only aristotle-server (§20): with Hermes down or updated they load on the address that answered. */
    private fun loadsWhile(state: LinkState) {
        link.value = state
        vm.homeVisible(true)
        assertTrue(sent.map { it.path }.containsAll(listOf("watchlist", "quotes", "holdings", "news")))
        assertEquals(setOf(tailnet), to.toSet())
        assertTrue(vm.uiState.value.reachable)
        assertEquals(listOf("BBCA", "BBRI", "TLKM"), vm.uiState.value.watchCodes)
    }

    @Test
    fun stocksLoadWhileHermesIsDown() = loadsWhile(LinkState.HermesDown("close 4503", tailnet))

    @Test
    fun stocksLoadWhileHermesIsUpdated() = loadsWhile(LinkState.HermesUpdated("close 4505", tailnet))

    @Test
    fun noStocksCallWhileNothingAnswersOrTheUserMustAct() {
        val certificate = LinkState.Offline(1_000, OfflineCause.CertificateChanged, "certificate differs", lan, 1, null)
        vm.homeVisible(true)
        vm.holdingsVisible(true)
        vm.newsVisible(true)
        for (state in listOf(certificate, LinkState.Idle, LinkState.Revoked, LinkState.Stopped, LinkState.Connecting)) {
            link.value = state
            vm.refresh()
            vm.showFees()
            assertTrue("$state called ${sent.map { it.path }}", sent.isEmpty())
            assertEquals("$state", false, vm.uiState.value.reachable)
        }
    }

    @Test
    fun holdingsAndNewsLoadOnEveryShowAndWhenTheServerComesBack() {
        fun count(path: String) = sent.count { it.path == path && it.method == "GET" }
        link.value = LinkState.Offline(1_000, OfflineCause.Unreachable, "", lan, 1, null)
        vm.holdingsVisible(true)
        vm.newsVisible(true)
        assertEquals(0, count("holdings"))
        link.value = LinkState.Online(lan, 1)
        assertEquals(1, count("holdings"))
        assertEquals(1, count("news"))
        assertEquals("the holdings page's fee line", 1, count("settings"))
        link.value = LinkState.Offline(2_000, OfflineCause.ConnectionLost, "", lan, 1, null)
        link.value = LinkState.HermesDown("close 4503", lan)
        assertEquals("back while shown", 2, count("holdings"))
        assertEquals(2, count("news"))
        link.value = LinkState.Online(lan, 2)
        assertEquals("still reachable: no extra read", 2, count("holdings"))
        vm.holdingsVisible(false)
        vm.holdingsVisible(true)
        assertEquals("shown again", 3, count("holdings"))
        assertEquals("news not shown again", 2, count("news"))
    }

    @Test
    fun aRefusedReorderIsPutBack() {
        shown()
        refuseReorder = true
        vm.moveInWatchlist("TLKM", -1)
        assertEquals(listOf("BBCA", "BBRI", "TLKM"), vm.uiState.value.watchCodes)
        val message = vm.uiState.value.message!!
        assertEquals(StocksAction.Reorder, message.action)
        assertEquals(StocksError.Server(400, "bad_request"), message.error)
        val put = sent.last { it.path == "watchlist" && it.method == "PUT" }
        assertEquals("""{"codes":["BBCA","TLKM","BBRI"]}""", put.body)
    }

    @Test
    fun anAcceptedReorderTakesThePcsOrder() {
        shown()
        vm.moveInWatchlist("BBCA", 1)
        assertEquals(listOf("TLKM", "BBRI", "BBCA"), vm.uiState.value.watchCodes)
        assertNull(vm.uiState.value.message)
    }

    @Test
    fun aFullWatchlistSaysSo() {
        shown()
        vm.openWatchSearch()
        vm.addToWatchlist("GOTO")
        val message = vm.uiState.value.message!!
        assertEquals(StocksAction.AddWatch, message.action)
        assertEquals("GOTO", message.code)
        assertEquals(StocksError.LIMIT_REACHED, (message.error as StocksError.Server).code)
        assertNull(vm.uiState.value.search!!.adding)
    }

    @Test
    fun offlineActionsFailWithoutACall() {
        vm.openWatchSearch()
        vm.addToWatchlist("BBCA")
        assertTrue(sent.isEmpty())
        assertTrue(vm.uiState.value.message!!.error is StocksError.Unreachable)
    }

    @Test
    fun pickingAStockForAHoldingOpensTheForm() {
        shown()
        vm.addHolding()
        assertTrue(vm.uiState.value.search!!.forHolding)
        vm.pickForHolding(SearchResult("BBRI", "Bank Rakyat Indonesia (Persero) Tbk", true))
        val sheet = vm.uiState.value.holdingSheet!!
        assertEquals("BBRI", sheet.code)
        assertEquals(false, sheet.editing)
        assertNull(vm.uiState.value.search)
    }
}
