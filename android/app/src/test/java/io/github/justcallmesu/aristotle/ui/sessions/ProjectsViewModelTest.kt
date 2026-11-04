package io.github.justcallmesu.aristotle.ui.sessions

import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.HistoryGrouping
import io.github.justcallmesu.aristotle.data.HistorySort
import io.github.justcallmesu.aristotle.data.HomeView
import io.github.justcallmesu.aristotle.data.HomeViewStore
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.MemoryStore
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.ProfileOption
import io.github.justcallmesu.aristotle.data.ProfileSelection
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.RpcError
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.SessionPins
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** TY3 over a fake link: the tree is read only when a view needs it, kept per profile, drilled into and refetched. */
class ProjectsViewModelTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val pc = PcCredentials("pc-1", "pc-1", listOf(lan), "F", DeviceToken("t"))
    private val now = ZonedDateTime.of(2026, 11, 4, 10, 0, 0, 0, ZoneId.of("Asia/Jakarta"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val selection = ProfileSelection(MemoryStore()).apply { follow(pc.pcId) }
    private val store = MemoryStore()
    private val views = HomeViewStore(store)

    @After
    fun stop() = scope.cancel()

    private class Call(val method: String, val params: JsonObject) {
        fun text(key: String): String? = (params[key] as? JsonPrimitive)?.content
        fun int(key: String): Int? = (params[key] as? JsonPrimitive)?.intOrNull
    }

    private class FakeLink(var answer: (Call) -> JsonElement) : RelayLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Connecting)
        override val events = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 16)
        val calls = CopyOnWriteArrayList<Call>()

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            val call = Call(method, params)
            calls += call
            return answer(call)
        }

        override fun respond(requestId: String, result: JsonObject) = false
        override fun retryNow() = Unit
    }

    private fun json(text: String): JsonElement = HermesJson.parseToJsonElement(text)

    private var cost = 0.0
    private var treeFails = false

    private fun hermes(call: Call): JsonElement = when (call.method) {
        "runtime.describe" -> json("""{"profile_id":"/h/.hermes"}""")
        "profiles.list" -> json("""{"profiles":[{"name":"default"},{"name":"pm"}]}""")
        "session.list" -> if (call.text("scope") == "live") json("""{"sessions":[{"session_id":"live-1","title":"Live","running":true}]}""")
        else json("""{"sessions":[{"session_id":"s1","title":"One","started_at":100},{"session_id":"s2","title":"Two","started_at":50}]}""")
        "projects.tree" -> if (treeFails) throw RpcException(RpcError(5000, "boom"), "projects.tree failed") else json(
            """
            {"projects":[
              {"id":"p1","label":"Aristotle","isAuto":false,"isNoProject":false,"sessionCount":5,"lastActive":200,"totalCostUsd":$cost,
               "previewSessions":[{"id":"s1","title":"One","last_active":200,"input_tokens":5},{"id":"s2","title":"Two","last_active":150,"input_tokens":90},
                 {"id":"live-1","title":"Live","last_active":210}]},
              {"id":"__no_project__","label":"Home","isNoProject":true,"sessionCount":1,"lastActive":10,"previewSessions":[{"id":"h1","title":"Loose","last_active":10}]}
            ],"active_id":null}
            """.trimIndent(),
        )
        "projects.project_sessions" -> json(
            """{"project":{"id":"p1","label":"Aristotle","repos":[{"id":"r","groups":[{"id":"g","sessions":[
              {"id":"s3","title":"Three","last_active":90},{"id":"s4","title":"Four","last_active":80},{"id":"s1","title":"One","last_active":200}]}]}]}}""",
        )
        else -> throw AssertionError("unexpected ${call.method}")
    }

    private fun viewModel(link: FakeLink) = SessionsViewModel(
        pc, link, selection = selection, pins = SessionPins(store), views = views, locale = { Locale.ENGLISH }, now = { now }, scope = scope,
    )

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(5) }
        } catch (e: Exception) {
            throw AssertionError(what, e)
        }
    }

    private fun online(): Pair<FakeLink, SessionsViewModel> {
        val link = FakeLink(::hermes)
        val vm = viewModel(link)
        link.state.value = LinkState.Online(lan, 1)
        waitFor("listed") { vm.uiState.value.ledger != null }
        return link to vm
    }

    private fun SessionsViewModel.ledger() = uiState.value.ledger.orEmpty()
    private fun SessionsViewModel.headers() = ledger().filterIsInstance<LedgerItem.ProjectHeader>().map { it.id }
    private fun SessionsViewModel.rowKeys() = ledger().filterIsInstance<LedgerItem.Row>().map { it.row.key }
    private fun FakeLink.count(method: String) = calls.count { it.method == method }

    @Test
    fun defaultViewIsDateAndReadsNoTree() {
        val (link, vm) = online()
        assertEquals(HomeView(), vm.uiState.value.view)
        assertEquals(LedgerItem.ViewBar(HistoryGrouping.Date, HistorySort.Updated), vm.ledger().first())
        assertEquals(0, link.count("projects.tree"))
    }

    @Test
    fun projectGroupingReadsTheTreeAndIsKeptPerProfile() {
        val (link, vm) = online()
        vm.setGrouping(HistoryGrouping.Project)
        waitFor("projects") { vm.headers().isNotEmpty() }
        val call = link.calls.single { it.method == "projects.tree" }
        assertEquals(3, call.int("preview_limit"))
        assertEquals(300, call.int("session_limit"))
        assertEquals(listOf("__no_project__", "p1"), vm.headers()) // Home leads
        assertFalse("the live card is not repeated", "live-1" in vm.rowKeys())
        assertEquals(HistoryGrouping.Project, views.of(pc.pcId, null).grouping)

        // Another profile has its own view; coming back restores Project and reads the tree for it.
        vm.selectProfile(ProfileOption("pm", "pm", own = false))
        waitFor("pm listed") { vm.uiState.value.profile == "pm" && vm.uiState.value.ledger != null }
        assertEquals(HistoryGrouping.Date, vm.uiState.value.view.grouping)
        assertEquals(1, link.count("projects.tree"))
        vm.selectProfile(ProfileOption("default", "default", own = true))
        waitFor("default again") { vm.uiState.value.profile == null && vm.headers().isNotEmpty() }
        assertEquals(HistoryGrouping.Project, vm.uiState.value.view.grouping)
        assertEquals(2, link.count("projects.tree"))
    }

    @Test
    fun keptViewIsReadAtStart() {
        views.set(pc.pcId, null, HomeView(grouping = HistoryGrouping.Project, collapsed = setOf("p1")))
        val (link, vm) = online()
        waitFor("projects") { vm.headers().isNotEmpty() }
        assertEquals(1, link.count("projects.tree"))
        assertTrue(vm.ledger().filterIsInstance<LedgerItem.ProjectHeader>().single { it.id == "p1" }.collapsed)
        vm.toggleProject("p1")
        assertFalse(vm.ledger().filterIsInstance<LedgerItem.ProjectHeader>().single { it.id == "p1" }.collapsed)
        assertEquals(emptySet<String>(), views.of(pc.pcId, null).collapsed)
    }

    @Test
    fun loadMoreDrillsIntoTheProject() {
        val (link, vm) = online()
        vm.setGrouping(HistoryGrouping.Project)
        waitFor("projects") { vm.headers().isNotEmpty() }
        assertTrue(LedgerItem.ProjectFooter("p1", ProjectMore.More) in vm.ledger())
        vm.moreInProject("p1")
        waitFor("hydrated") { "s4" in vm.rowKeys() }
        val drill = link.calls.single { it.method == "projects.project_sessions" }
        assertEquals("p1", drill.text("project_id"))
        assertEquals(listOf("s1", "s2", "s3", "s4"), vm.rowKeys().filter { it.startsWith("s") })
        assertFalse(vm.ledger().any { it is LedgerItem.ProjectFooter })
    }

    @Test
    fun tokensSortAsksForEveryRowAndRanks() {
        val (link, vm) = online()
        vm.setSort(HistorySort.Tokens)
        waitFor("ranked") { (vm.ledger().first() as LedgerItem.ViewBar).sort == HistorySort.Tokens }
        assertEquals(300, link.calls.single { it.method == "projects.tree" }.int("preview_limit"))
        assertEquals(listOf("s2", "s1"), vm.rowKeys()) // 90 tokens before 5, inside the date group
    }

    @Test
    fun costSortOnlyWhenSomethingCosts() {
        val (link, vm) = online()
        vm.openView()
        assertTrue(vm.uiState.value.viewSheet)
        waitFor("tree for the cost rule") { link.count("projects.tree") == 1 && vm.headers().isEmpty() && vm.uiState.value.ledger != null }
        assertFalse("every cost is 0: no cost sort", vm.uiState.value.costSort)
        vm.closeView()

        cost = 0.4
        val (_, paid) = online()
        paid.openView()
        waitFor("cost offered") { paid.uiState.value.costSort }
    }

    @Test
    fun projectsChangedReadsTheTreeAgain() {
        val (link, vm) = online()
        vm.setGrouping(HistoryGrouping.Project)
        waitFor("projects") { vm.headers().isNotEmpty() }
        link.events.tryEmit(InboundFrame.Event("projects.changed", "", JsonObject(emptyMap())))
        waitFor("refetched") { link.count("projects.tree") == 2 }
    }

    @Test
    fun aFailedTreeOffersRetry() {
        treeFails = true
        val (link, vm) = online()
        vm.setGrouping(HistoryGrouping.Project)
        waitFor("failed") { LedgerItem.ProjectsFailed in vm.ledger() }
        treeFails = false
        vm.retryProjects()
        waitFor("loaded") { vm.headers().isNotEmpty() }
        assertEquals(2, link.count("projects.tree"))
    }

    @Test
    fun movedProjectOrderIsKept() {
        val (_, vm) = online()
        vm.setGrouping(HistoryGrouping.Project)
        waitFor("projects") { vm.headers().isNotEmpty() }
        vm.moveProject("p1", -1) // Home never moves, so nothing changes
        assertEquals(emptyList<String>(), views.of(pc.pcId, null).projectOrder)
    }
}
