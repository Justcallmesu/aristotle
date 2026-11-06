package io.github.justcallmesu.aristotle.ui.stocks

import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.MemoryStore
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.stocks.AlertsTest
import io.github.justcallmesu.aristotle.data.stocks.BriefCard
import io.github.justcallmesu.aristotle.data.stocks.BriefTest
import io.github.justcallmesu.aristotle.data.stocks.StockAlertStore
import io.github.justcallmesu.aristotle.data.stocks.StocksReply
import io.github.justcallmesu.aristotle.data.stocks.StocksRequest
import io.github.justcallmesu.aristotle.data.stocks.StocksTransport
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TBA2's ViewModels over a fake PC: the holding sheet's levels, Settings › Stocks › Alerts, the brief's card and page. */
class Tba2ViewModelsTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val pc = PcCredentials("pc1", "dev1", listOf(lan), "ff", DeviceToken("t"))
    private val link = MutableStateFlow<LinkState>(LinkState.Connecting)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = mutableListOf<StocksRequest>()
    private var briefReply: StocksReply = StocksReply.Http(200, BriefTest.BRIEF_JSON)
    private val pauses = mutableListOf<Long>()
    private var gate = CompletableDeferred<Unit>()

    private val transport = StocksTransport { _, _, request ->
        sent += request
        when {
            request.path == "holdings" -> StocksReply.Http(200, HOLDINGS)
            request.path == "holdings/BBCA" -> StocksReply.Http(200, HOLDINGS)
            request.path == "settings" -> StocksReply.Http(200, """{"buy_fee_pct":0.15,"sell_fee_pct":0.25}""")
            request.path == "jobs" -> StocksReply.Http(200, JOBS)
            request.path == "jobs/alerts" -> StocksReply.Http(200, """{"id":"alerts","on":false,"every_min":5,"editable":["on"]}""")
            request.path == "alerts" -> StocksReply.Http(200, AlertsTest.ALERTS_JSON)
            request.path == "brief" && request.method == "GET" -> briefReply
            request.path == "brief" -> StocksReply.Http(202, """{"date":"2026-11-05","state":"running"}""")
            request.path == "briefs" -> StocksReply.Http(200, """{"briefs":[{"date":"2026-11-05","state":"done","late":false,"headline":"Banks"}]}""")
            else -> StocksReply.Http(404, """{"error":"not_found"}""")
        }
    }

    @After
    fun stop() = scope.cancel()

    private fun online() {
        link.value = LinkState.Online(lan, 1)
    }

    @Test
    fun `editing a holding keeps its levels and Save sends both - an invalid stop blocks Save`() {
        val vm = StocksViewModel(pc, link, transport, scope = scope, pause = { awaitCancellation() }, searchPause = {})
        online()
        vm.holdingsVisible(true)
        vm.editHolding("BBCA")
        val sheet = vm.uiState.value.holdingSheet!!
        assertEquals("4600", sheet.stop)
        assertEquals("5100", sheet.target)
        vm.saveHolding()
        val put = sent.last { it.path == "holdings/BBCA" }
        assertEquals("""{"lots":10,"avg_price":4800.0,"stop":4600,"target":5100}""", put.body)
        // A stop at the average is refused here as the server would: nothing is sent.
        vm.editHolding("BBCA")
        vm.editStop("4800")
        val before = sent.size
        vm.saveHolding()
        assertEquals(before, sent.size)
        // Both cleared: the PUT leaves them out, which clears them on the PC.
        vm.editStop("")
        vm.editTarget("")
        vm.saveHolding()
        assertEquals("""{"lots":10,"avg_price":4800.0}""", sent.last().body)
    }

    @Test
    fun `Settings reads the alerts job and today's automatic runs, and the switch PUTs the job`() {
        val store = StockAlertStore(MemoryStore())
        val vm = StockAlertsViewModel(pc, link, transport, store, scope = scope)
        vm.settingsVisible(true)
        assertTrue("nothing while the PC does not answer", sent.isEmpty())
        online()
        assertEquals(listOf(StocksRequest("GET", "jobs")), sent)
        assertEquals(true, vm.uiState.value.alertsOn)
        assertEquals(2, vm.uiState.value.autoRuns?.used)
        assertEquals(5, vm.uiState.value.autoRuns?.cap)
        vm.setAlertsOn(false)
        assertEquals(StocksRequest("PUT", "jobs/alerts", body = """{"on":false}"""), sent.last())
        assertEquals(false, vm.uiState.value.alertsOn)
        assertFalse(vm.uiState.value.saving)
    }

    @Test
    fun `the alerts page reads the newest 50 without a cursor and marks them seen`() {
        val store = StockAlertStore(MemoryStore())
        val vm = StockAlertsViewModel(pc, link, transport, store, scope = scope)
        online()
        vm.pageVisible(true)
        assertEquals(StocksRequest("GET", "alerts"), sent.single())
        assertEquals(listOf(9L, 8L), vm.uiState.value.feed.items.map { it.seq })
        assertEquals(0, vm.uiState.value.feed.unseen)
        assertNull("the list read never moves the long-poll cursor", store.cursor("pc1"))
    }

    @Test
    fun `the home card reads the latest brief and the jobs - no brief yet is said as such`() {
        val vm = BriefViewModel(pc, link, transport, scope = scope)
        online()
        vm.homeVisible(true)
        assertEquals(listOf("brief", "jobs"), sent.map { it.path })
        val today = LocalDate.parse("2026-11-05")
        assertEquals(BriefCard.Ready("Banks lead into the open", late = false, running = false), vm.uiState.value.card(today, LocalTime.of(8, 0)))
        briefReply = StocksReply.Http(404, """{"error":"no_brief"}""")
        vm.homeVisible(false)
        vm.homeVisible(true)
        assertTrue(vm.uiState.value.latestMissing)
        assertEquals(BriefCard.NotTradingDay(null), vm.uiState.value.card(LocalDate.parse("2026-11-07"), LocalTime.of(8, 0)))
    }

    @Test
    fun `Run again posts the language, then the page follows the running brief until it is done`() {
        val vm = BriefViewModel(pc, link, transport, scope = scope, pause = { pauses += it; gate.await() })
        online()
        vm.pageVisible(true)
        assertTrue(sent.any { it.path == "briefs" })
        assertTrue(vm.uiState.value.canRerun(LocalDate.parse("2026-11-05")))
        briefReply = StocksReply.Http(200, BriefTest.BRIEF_JSON.replace("\"state\":\"done\"", "\"state\":\"running\""))
        vm.runAgain("id")
        assertTrue(sent.contains(StocksRequest("POST", "brief", body = """{"lang":"id"}""")))
        assertTrue(vm.uiState.value.page!!.running)
        assertFalse(vm.uiState.value.canRerun(LocalDate.parse("2026-11-05")))
        assertEquals(listOf(BriefViewModel.POLL_MILLIS), pauses)
        // The run finishes: the next read shows it done and the following stops.
        briefReply = StocksReply.Http(200, BriefTest.BRIEF_JSON)
        gate.complete(Unit)
        assertFalse(vm.uiState.value.page!!.running)
        assertEquals(1, pauses.size)
        // Leaving the page stops any following.
        vm.pageVisible(false)
    }

    private companion object {
        const val HOLDINGS = """{"at":"2026-11-05T10:00:00+07:00","holdings":[{"code":"BBCA","name":"Bank Central Asia","lots":10,"avg_price":4800,
            "price":4930,"cost":4807200,"value":4917675,"pl":110475,"pl_pct":2.3,"today":0,"stale":false,"stop":4600,"target":5100}],"total":{"cost":4807200}}"""
        const val JOBS = """{"jobs":[{"id":"alerts","on":true,"every_min":5,"editable":["on"]},{"id":"brief","on":true,"at":"08:30","editable":["on","at"]}],
            "auto_runs":{"date":"2026-11-05","used":2,"cap":5,"codes":["BBCA","TLKM"]}}"""
    }
}
