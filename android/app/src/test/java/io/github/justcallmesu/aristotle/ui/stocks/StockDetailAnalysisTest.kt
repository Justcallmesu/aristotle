package io.github.justcallmesu.aristotle.ui.stocks

import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.stocks.AnalysisPhase
import io.github.justcallmesu.aristotle.data.stocks.AnalysisProblem
import io.github.justcallmesu.aristotle.data.stocks.AnalysisStates
import io.github.justcallmesu.aristotle.data.stocks.StocksReply
import io.github.justcallmesu.aristotle.data.stocks.StocksRequest
import io.github.justcallmesu.aristotle.data.stocks.StocksTransport
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
 * The Analyze section's calls over a fake PC (TA2): `GET /analysis` once per showing, every 3 s only while a job runs and
 * the page is visible, never after done / failed; `POST /analyze` with the phone's `lang`; refusals as problems. The
 * poll's pause is a gate the test opens (a fake clock: no real waiting).
 */
class StockDetailAnalysisTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val pc = PcCredentials("pc1", "dev1", listOf(lan), "ff", DeviceToken("t"))
    private val link = MutableStateFlow<LinkState>(LinkState.Connecting)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val sent = CopyOnWriteArrayList<StocksRequest>()
    private val polls = CopyOnWriteArrayList<Long>()
    private val pollGate = Channel<Unit>(Channel.UNLIMITED)
    private val thursday = LocalDate.parse("2026-11-05")

    private fun fixture(name: String): String = javaClass.classLoader!!.getResource("stocks/$name")!!.readText()

    @Volatile
    private var analysis = fixture("analysis_idle.json")

    @Volatile
    private var post: StocksReply = StocksReply.Http(202, """{"job_id":"an_0123456789ab","state":"running","started_at":"2026-11-05T10:00:00+07:00"}""")

    private val transport = StocksTransport { _, _, request ->
        sent += request
        when (request.path) {
            "analysis/BBCA" -> StocksReply.Http(200, analysis)
            "analyze/BBCA" -> post
            else -> StocksReply.Http(404, """{"error":"not_found"}""")
        }
    }

    private val vm = StockDetailViewModel(
        "BBCA", pc, link, transport, scope = scope,
        // The quote refresher never ticks here.
        pause = { awaitCancellation() },
        retryPause = { awaitCancellation() },
        analysisPause = { polls += it; pollGate.receive() },
    )

    @After
    fun stop() = scope.cancel()

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(5) }
        } catch (e: Exception) {
            throw AssertionError("$what; sent ${sent.map { "${it.method} ${it.path}" }}, polls $polls", e)
        }
    }

    private fun settle() = runBlocking { delay(100) }

    private fun reads() = sent.count { it.path == "analysis/BBCA" }
    private fun posts() = sent.filter { it.path == "analyze/BBCA" }
    private fun view() = vm.uiState.value.analysisView(null, thursday)

    private fun show() {
        link.value = LinkState.Online(lan, 1)
        vm.visible(true)
    }

    @Test
    fun readOnceOnShowingAndNotWhileOffline() {
        vm.visible(true)
        settle()
        assertEquals(0, reads())
        assertEquals(AnalysisPhase.Unknown, view().phase)
        analysis = fixture("analysis_done.json")
        link.value = LinkState.HermesDown("close 4503", lan)
        waitFor("read while Hermes is down") { reads() == 1 && view().phase == AnalysisPhase.Done }
        settle()
        assertTrue("done: no polling", polls.isEmpty())
        vm.visible(false)
        settle()
        vm.visible(true)
        waitFor("read again on the next showing") { reads() == 2 }
    }

    @Test
    fun aRunningJobIsPolledEvery3sOnlyWhileVisible() {
        analysis = fixture("analysis_running.json")
        show()
        waitFor("running, then a 3 s wait") { view().phase == AnalysisPhase.Running && polls.toList() == listOf(AnalysisStates.POLL_MILLIS) }
        pollGate.trySend(Unit)
        waitFor("the second read") { reads() == 2 && polls.size == 2 }
        // Hidden: the wait is dropped and nothing more is read.
        vm.visible(false)
        settle()
        pollGate.trySend(Unit)
        settle()
        assertEquals(2, reads())
        pollGate.tryReceive() // the cancelled wait never took it
        // Shown again: read at once, still running, so it waits again.
        vm.visible(true)
        waitFor("read on return") { reads() == 3 && polls.size == 3 }
        // Finished on the PC: the next read stops the polling.
        analysis = fixture("analysis_done.json")
        pollGate.trySend(Unit)
        waitFor("done") { view().phase == AnalysisPhase.Done }
        settle()
        assertEquals(4, reads())
        assertEquals("no wait after done", 3, polls.size)
    }

    @Test
    fun analyzeSendsTheLanguageAndFollowsTheJob() {
        show()
        waitFor("idle") { view().phase == AnalysisPhase.Idle }
        vm.analyze("id")
        waitFor("running after the 202") { view().phase == AnalysisPhase.Running && polls.size == 1 }
        val request = posts().single()
        assertEquals("POST", request.method)
        assertEquals("""{"lang":"id"}""", request.body)
        assertEquals("2026-11-05T10:00:00+07:00", view().startedAt)
        // A second tap while it runs sends nothing.
        vm.analyze("id")
        settle()
        assertEquals(1, posts().size)
        analysis = fixture("analysis_held_failed.json")
        pollGate.trySend(Unit)
        waitFor("failed") { view().phase == AnalysisPhase.Failed }
        assertEquals(AnalysisProblem.InvalidVerdict, view().problem)
        settle()
        assertEquals("no wait after failed", 1, polls.size)
    }

    @Test
    fun aRefusedStartSaysWhyAndPollsNothing() {
        post = StocksReply.Http(503, """{"error":"analyzer_not_set_up"}""")
        show()
        waitFor("idle") { view().phase == AnalysisPhase.Idle }
        vm.analyze("en")
        waitFor("refused") { view().phase == AnalysisPhase.Failed }
        assertEquals(AnalysisProblem.NotSetUp, view().problem)
        assertEquals("""{"lang":"en"}""", posts().single().body)
        settle()
        assertTrue(polls.isEmpty())
        // Retry: the next start clears the problem.
        post = StocksReply.Http(429, """{"error":"busy"}""")
        vm.analyze("en")
        waitFor("busy") { view().problem == AnalysisProblem.Busy }
    }

    @Test
    fun alreadyRunningShowsThatJob() {
        post = StocksReply.Http(409, """{"error":"analysis_running"}""")
        show()
        waitFor("idle") { view().phase == AnalysisPhase.Idle }
        analysis = fixture("analysis_running.json")
        vm.analyze("en")
        waitFor("the other job, followed") { view().phase == AnalysisPhase.Running && polls.size == 1 }
        assertNull(view().problem)
    }

    @Test
    fun nothingStartsWhileOffline() {
        vm.visible(true)
        vm.analyze("en")
        settle()
        assertTrue(sent.isEmpty())
    }
}
