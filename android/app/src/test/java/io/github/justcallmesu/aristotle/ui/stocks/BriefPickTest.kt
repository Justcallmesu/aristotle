package io.github.justcallmesu.aristotle.ui.stocks

import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.stocks.BriefTest
import io.github.justcallmesu.aristotle.data.stocks.StocksReply
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
import org.junit.Test

/** TBA2c MAJOR-1: a picked day never outlives the visit - opening the page, or a later latest brief, shows the latest. */
class BriefPickTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val pc = PcCredentials("pc1", "dev1", listOf(lan), "ff", DeviceToken("t"))
    private val link = MutableStateFlow<LinkState>(LinkState.Online(lan, 1))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var latestDate = "2026-11-05"
    private var latestState = "pending"

    private val transport = StocksTransport { _, _, request ->
        when {
            request.path == "brief" && request.query["date"] == "2026-11-04" -> StocksReply.Http(200, BriefTest.BRIEF_JSON.replace("2026-11-05", "2026-11-04"))
            request.path == "brief" -> StocksReply.Http(200, """{"date":"$latestDate","state":"$latestState"}""")
            request.path == "briefs" -> StocksReply.Http(200, """{"briefs":[{"date":"2026-11-04","state":"done","late":false,"headline":"Old"}]}""")
            request.path == "jobs" -> StocksReply.Http(200, """{"jobs":[{"id":"brief","on":true,"at":"08:30","editable":["on","at"]}]}""")
            else -> StocksReply.Http(404, """{"error":"not_found"}""")
        }
    }

    private val vm = BriefViewModel(pc, link, transport, scope = scope, pause = { awaitCancellation() })

    @After
    fun stop() = scope.cancel()

    @Test
    fun `open after a pick shows the latest, and its chip is still there (the reviewer's re-entry probe)`() {
        vm.pageVisible(true)
        vm.pick("2026-11-04")
        assertEquals("2026-11-04", vm.uiState.value.page?.date)
        vm.pageVisible(false)
        vm.pageVisible(true)
        assertNull(vm.uiState.value.picked)
        assertEquals("2026-11-05", vm.uiState.value.page?.date)
        assertEquals(listOf("2026-11-05", "2026-11-04"), vm.uiState.value.dayChips().map { it.date })
        assertEquals("2026-11-05", vm.uiState.value.dayChips().single { it.selected }.date)
    }

    @Test
    fun `a repeat tap on the brief page (a notification while it is shown) resets the pick`() {
        vm.pageVisible(true)
        vm.pick("2026-11-04")
        vm.opened()
        assertNull(vm.uiState.value.picked)
        assertEquals("2026-11-05", vm.uiState.value.page?.date)
    }

    @Test
    fun `a new latest brief for a later date drops the pick`() {
        vm.homeVisible(true)
        vm.pageVisible(true)
        vm.pick("2026-11-04")
        // Overnight the 06 brief arrives; the card's next read brings it.
        latestDate = "2026-11-06"
        latestState = "done"
        vm.homeVisible(false)
        vm.homeVisible(true)
        assertNull(vm.uiState.value.picked)
        assertEquals("2026-11-06", vm.uiState.value.page?.date)
    }

    @Test
    fun `a read of the same latest keeps the pick`() {
        vm.homeVisible(true)
        vm.pageVisible(true)
        vm.pick("2026-11-04")
        vm.homeVisible(false)
        vm.homeVisible(true)
        assertEquals("2026-11-04", vm.uiState.value.picked)
    }
}
