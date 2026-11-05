package io.github.justcallmesu.aristotle.ui.stocks

import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.stocks.ScreenerField
import io.github.justcallmesu.aristotle.data.stocks.ScreenerFixtures
import io.github.justcallmesu.aristotle.data.stocks.ScreenerKind
import io.github.justcallmesu.aristotle.data.stocks.ScreenerMode
import io.github.justcallmesu.aristotle.data.stocks.SetupKey
import io.github.justcallmesu.aristotle.data.stocks.StocksReply
import io.github.justcallmesu.aristotle.data.stocks.StocksRequest
import io.github.justcallmesu.aristotle.data.stocks.StocksTransport
import java.time.OffsetDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TSC3: the screener ViewModel over a fake PC — reads on show only while reachable, Scan now's cooldown, Save's two PUTs. */
class ScreenerViewModelTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val pc = PcCredentials("pc1", "dev1", listOf(lan), "ff", DeviceToken("t"))
    private val link = MutableStateFlow<LinkState>(LinkState.Connecting)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sent = mutableListOf<StocksRequest>()
    private var now = 1_000_000L
    private var scanReply: StocksReply = StocksReply.Http(202, """{"mode":"intraday","scan":{"running":true}}""")
    private var defaultReply: StocksReply = StocksReply.Http(200, ScreenerFixtures.INTRADAY)
    private var eodReply: StocksReply = StocksReply.Http(200, ScreenerFixtures.EOD)
    private val waits = mutableListOf<Long>()

    private val transport = StocksTransport { _, _, request ->
        sent += request
        when {
            request.path == "screener" && request.query["mode"] == "eod" -> eodReply
            request.path == "screener" -> defaultReply
            request.path == "screener/scan" -> scanReply
            request.path == "screener/settings" && request.method == "GET" -> StocksReply.Http(200, ScreenerFixtures.SETTINGS_DEFAULTS)
            request.path == "screener/settings" -> StocksReply.Http(200, request.body!!)
            request.path == "jobs" -> StocksReply.Http(200, ScreenerFixtures.JOBS)
            request.path == "jobs/screener.scan" -> StocksReply.Http(200, """{"id":"screener.scan","on":true,"every_min":15,"editable":["on","every_min"]}""")
            request.path == "universe" -> StocksReply.Http(200, ScreenerFixtures.UNIVERSE)
            else -> StocksReply.Http(404, """{"error":"not_found"}""")
        }
    }

    private val vm = ScreenerViewModel(pc, link, transport, scope = scope, clock = { now }, pause = { waits += it; awaitCancellation() })

    @After
    fun stop() = scope.cancel()

    private fun online() {
        link.value = LinkState.Online(lan, 1)
    }

    @Test
    fun `nothing is read while hidden or while the PC does not answer`() {
        online()
        assertTrue(sent.isEmpty())
        link.value = LinkState.Connecting
        vm.homeVisible(true)
        assertTrue(sent.isEmpty())
        assertFalse(vm.uiState.value.reachable)
    }

    @Test
    fun `showing Stocks home reads the default view once, and the row labels come from it`() {
        online()
        vm.homeVisible(true)
        assertEquals(listOf(StocksRequest("GET", "screener")), sent)
        val s = vm.uiState.value
        assertEquals(ScreenerMode.Intraday, s.home?.screenerMode)
        assertTrue(s.labelsFor("ANTM").single().bearish)
        assertTrue(s.labelsFor("TLKM").isEmpty())
        // Hidden and shown again: read again (read on show).
        vm.homeVisible(false)
        vm.homeVisible(true)
        assertEquals(2, sent.size)
        // The last answer's scan ran 50 s before the server's "at", but the server's cooldown counts from its own last start, which
        // no answer carries (an eod screen run by bars.daily or a GET never starts it): the phone does not guess a wait.
        assertNull(s.cooldownUntil)
    }

    @Test
    fun `Last close on the page reads that mode and the page shows it`() {
        online()
        vm.pageVisible(true)
        vm.selectMode(ScreenerMode.Eod)
        assertEquals(StocksRequest("GET", "screener", mapOf("mode" to "eod")), sent.last())
        val s = vm.uiState.value
        assertEquals(ScreenerMode.Eod, s.shownMode)
        assertEquals("2026-11-05T16:00:00+07:00", s.pageBody?.dataAsOf)
        assertEquals(ScreenerMode.Intraday, s.home?.screenerMode) // home keeps the default view
    }

    @Test
    fun `Scan now asks the server at once, then waits 2 min after its own 202`() {
        online()
        vm.pageVisible(true)
        // The shown answer's last_run is 50 s old; only the server knows whether that started its cooldown, so it is asked.
        vm.scanNow()
        assertEquals(1, sent.count { it.path == "screener/scan" && it.method == "POST" })
        val s = vm.uiState.value
        assertTrue(s.following)
        assertTrue(s.scanBusy)
        assertEquals(now + 120_000, s.cooldownUntil)
        vm.scanNow()
        assertEquals(1, sent.count { it.path == "screener/scan" })
    }

    @Test
    fun `the stale notice and the menu share one Scan now rule`() {
        online()
        vm.pageVisible(true)
        assertTrue(vm.uiState.value.canScan(now))
        scanReply = StocksReply.Http(429, """{"error":"too_soon","retry_after_s":80}""", retryAfterSeconds = 80)
        vm.scanNow()
        val s = vm.uiState.value
        assertFalse(s.canScan(now))
        assertEquals(80, s.scanWaitSeconds(now))
        assertTrue(s.canScan(now + 80_000))
        assertNull(s.scanWaitSeconds(now + 80_000))
    }

    @Test
    fun `a mode not read yet shows its own loading state, never the other mode's list`() {
        online()
        vm.pageVisible(true)
        eodReply = StocksReply.Http(500, """{"error":"state_error"}""")
        vm.selectMode(ScreenerMode.Eod)
        val s = vm.uiState.value
        assertEquals(ScreenerMode.Eod, s.shownMode)
        assertNull(s.pageBody)
        assertEquals(ScreenerKind.Loading, s.pageState.kind)
        // Offline, a mode never read is "not loaded"; the mode read before is still shown when picked back.
        link.value = LinkState.Connecting
        assertEquals(ScreenerKind.NotLoaded, vm.uiState.value.pageState.kind)
        vm.selectMode(ScreenerMode.Intraday)
        assertEquals(ScreenerMode.Intraday, vm.uiState.value.pageBody?.screenerMode)
    }

    @Test
    fun `the 3 s follow ends after 10 failed reads in a row and the normal rule takes over`() {
        online()
        vm.pageVisible(true)
        vm.scanNow()
        assertTrue(vm.uiState.value.following)
        defaultReply = StocksReply.Http(500, """{"error":"state_error"}""")
        repeat(9) { vm.refresh() }
        assertTrue(vm.uiState.value.following)
        // A good answer that still says running resets the count.
        defaultReply = StocksReply.Http(200, ScreenerFixtures.INTRADAY.replace(""""running":false""", """"running":true"""))
        vm.refresh()
        assertTrue(vm.uiState.value.following)
        defaultReply = StocksReply.Http(500, """{"error":"state_error"}""")
        repeat(9) { vm.refresh() }
        assertTrue(vm.uiState.value.following)
        vm.refresh()
        assertFalse(vm.uiState.value.following)
    }

    @Test
    fun `after 16_00 on a trading day the 16_00 and 16_10 scans are still read every 60 s`() {
        // Thu 5 Nov 2026 16:05 WIB: the market says closed, but [13:40, 16:15) is still a scan window and a scan ran today.
        now = OffsetDateTime.parse("2026-11-05T16:05:30+07:00").toInstant().toEpochMilli()
        defaultReply = StocksReply.Http(200, ScreenerFixtures.INTRADAY.replace("2026-11-05T09:56:00+07:00", "2026-11-05T16:05:30+07:00")
            .replace(""""market":{"state":"open","session":1,"closes_at":"2026-11-05T12:00:00+07:00"}""", """"market":{"state":"closed","next_open":"2026-11-06T09:00:00+07:00"}"""))
        online()
        vm.homeVisible(true)
        assertEquals(60_000L, waits.last())
    }

    @Test
    fun `a too_soon refusal sets the wait from retry_after_s, without a message`() {
        online()
        now += 200_000
        scanReply = StocksReply.Http(429, """{"error":"too_soon","retry_after_s":30}""", retryAfterSeconds = 30)
        vm.scanNow()
        val s = vm.uiState.value
        assertEquals(now + 30_000, s.cooldownUntil)
        assertFalse(s.following)
        assertNull(s.message)
    }

    @Test
    fun `settings load with the scan job and the list, and Save sends only what changed`() {
        online()
        vm.settingsVisible(true)
        assertTrue(sent.any { it.path == "screener/settings" } && sent.any { it.path == "jobs" } && sent.any { it.path == "universe" })
        val draft = assertNotNullDraft()
        assertEquals("10", draft.text(ScreenerField.ScanEvery))
        assertFalse(draft.canSave)

        // Only the interval: one PUT to the job, none to the settings.
        vm.editField(ScreenerField.ScanEvery, "15")
        sent.clear()
        vm.save()
        assertEquals(listOf(StocksRequest("PUT", "jobs/screener.scan", body = """{"every_min":15}""")), sent)
        assertEquals(15, vm.uiState.value.scanJob?.everyMin)
        assertFalse(assertNotNullDraft().canSave)
        assertNull(vm.uiState.value.message?.error)

        // A setting: the whole object, every key, then the re-ranked list.
        vm.setSetupOn(SetupKey.RsiRebound, false)
        sent.clear()
        vm.save()
        val put = sent.first()
        assertEquals("PUT", put.method)
        assertEquals("screener/settings", put.path)
        assertTrue(put.body!!.contains(""""rsi_rebound":{"on":false,"level":30.0}"""))
        assertTrue(put.body!!.contains(""""min_value_bn":5.0"""))
        assertEquals("screener", sent.last().path)
        assertFalse(vm.uiState.value.settings!!.setups.rsiRebound.on)
    }

    @Test
    fun `an invalid value is never sent`() {
        online()
        vm.settingsVisible(true)
        vm.editField(ScreenerField.BreakoutLookback, "75")
        sent.clear()
        vm.save()
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `Edit list replaces the list on the PC, a wrong code is never sent`() {
        online()
        vm.settingsVisible(true)
        vm.openUniverse()
        val sheet = vm.uiState.value.universeSheet!!
        assertEquals("BBCA TLKM", sheet.idx80)
        vm.editUniverse(sheet.copy(lq45 = "BBCA.JK"))
        sent.clear()
        vm.saveUniverse()
        assertTrue(sent.isEmpty())
        vm.editUniverse(sheet.copy(lq45 = "asii"))
        vm.saveUniverse()
        assertEquals(StocksRequest("PUT", "universe", body = """{"lq45":["ASII"],"idx80":["BBCA","TLKM"],"valid_to":"2027-01-29"}"""), sent.first())
        assertNull(vm.uiState.value.universeSheet)
    }

    private fun assertNotNullDraft() = vm.uiState.value.draft.also { assertNotNull(it) }!!
}
