package io.github.justcallmesu.aristotle.ui.sessions

import io.github.justcallmesu.aristotle.data.DeviceToken
import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.MemoryStore
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session list's ViewModel over a fake link: profile switch, paging, pins, rename and new session (T14). */
class SessionsViewModelTest {
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)
    private val pc = PcCredentials("pc-1", "pc-1", listOf(lan), "F", DeviceToken("t"))
    private val now = ZonedDateTime.of(2026, 11, 4, 10, 0, 0, 0, ZoneId.of("Asia/Jakarta"))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val settings = MemoryStore()
    private val selection = ProfileSelection(settings).apply { follow(pc.pcId) }
    private val pins = SessionPins(MemoryStore())

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

    private fun rows(prefix: String, count: Int, running: Boolean = false): JsonElement = json(
        (0 until count).joinToString(",", """{"sessions":[""", "]}") { """{"session_id":"$prefix$it","title":"$prefix $it","running":$running}""" },
    )

    /** Live: one row per profile; stored: as many as asked (an endless history), titled by profile. */
    private fun hermes(call: Call): JsonElement {
        val profile = call.text("profile") ?: "default"
        return when (call.method) {
            "runtime.describe" -> json("""{"profile_id":"/h/.hermes"}""")
            "profiles.list" -> json("""{"profiles":[{"name":"default","last_session":null},{"name":"pm","last_session":null},{"name":"off"}]}""")
            "session.list" -> if (call.text("scope") == "live") rows("$profile-live-", 1, running = true) else rows("$profile-h", call.int("limit")!!)
            "session.create" -> json("""{"session_id":"new-1"}""")
            "session.mutate" -> json("""{"session_id":"x","operation":"rename","revision":1}""")
            else -> throw AssertionError("unexpected ${call.method}")
        }
    }

    private fun viewModel(link: FakeLink) =
        SessionsViewModel(pc, link, selection = selection, pins = pins, locale = { Locale.ENGLISH }, now = { now }, scope = scope)

    private fun waitFor(what: String, condition: () -> Boolean) = runBlocking {
        try {
            withTimeout(5_000) { while (!condition()) delay(5) }
        } catch (e: Exception) {
            throw AssertionError(what, e)
        }
    }

    private fun SessionsViewModel.keys(): List<String> = uiState.value.items.orEmpty().rows().map { it.key }

    @Test
    fun liveThenHistoryAndPagingPastFifty() {
        val link = FakeLink(::hermes)
        val vm = viewModel(link)
        link.state.value = LinkState.Online(lan, 1)
        waitFor("listed") { vm.uiState.value.items != null }
        assertEquals("default", vm.uiState.value.profileName)
        assertEquals(51, vm.keys().size)
        assertEquals("default-live-0", vm.keys().first())
        assertEquals(SessionListItem.Footer(HistoryEnd.More), vm.uiState.value.items!!.last())
        vm.loadMore()
        waitFor("second page") { vm.keys().size == 101 }
        assertEquals(listOf(50, 100), link.calls.filter { it.text("scope") == "stored" }.map { it.int("limit") })
        assertNull(vm.uiState.value.open)
        vm.open(vm.uiState.value.items!!.rows()[3])
        assertEquals(OpenRequest("default-h2", "default-h 2", null), vm.uiState.value.open)
    }

    // Q25: the picker lists the served profiles; switching re-lists, is remembered, and the watcher's selection follows.
    @Test
    fun switchingTheProfileRelistsAndIsRemembered() {
        val link = FakeLink(::hermes)
        val vm = viewModel(link)
        link.state.value = LinkState.Online(lan, 1)
        waitFor("listed") { vm.uiState.value.items != null }
        vm.openProfiles()
        waitFor("roster") { vm.uiState.value.picker?.options != null }
        val options = vm.uiState.value.picker!!.options!!
        assertEquals(listOf("default", "pm"), options.map { it.name })
        vm.selectProfile(options[1])
        assertNull(vm.uiState.value.picker)
        waitFor("pm listed") { vm.keys().firstOrNull() == "pm-live-0" }
        assertEquals("pm", vm.uiState.value.profileName)
        assertEquals("pm", selection.selected.value)
        assertEquals("pm", ProfileSelection(settings).apply { follow(pc.pcId) }.selected.value)
        assertTrue(link.calls.filter { it.method == "session.list" }.takeLast(2).all { it.text("profile") == "pm" })
        // A chat opened from here keeps the profile.
        vm.open(vm.uiState.value.items!!.rows().first())
        assertEquals("pm", vm.uiState.value.open?.profile)
        // Back to the launch profile: calls without `profile`.
        vm.opened()
        vm.openProfiles()
        waitFor("roster again") { vm.uiState.value.picker?.options != null }
        vm.selectProfile(options[0])
        waitFor("default listed") { vm.keys().firstOrNull() == "default-live-0" }
        assertNull(selection.selected.value)
        assertNull(link.calls.last { it.method == "session.list" }.text("profile"))
    }

    @Test
    fun theRememberedProfileIsListedFirst() {
        selection.select("pm")
        val link = FakeLink(::hermes)
        val vm = viewModel(link)
        link.state.value = LinkState.Online(lan, 1)
        waitFor("listed") { vm.uiState.value.items != null }
        assertEquals("pm-live-0", vm.keys().first())
        assertTrue(link.calls.filter { it.method == "session.list" }.all { it.text("profile") == "pm" })
    }

    @Test
    fun aPinMovesTheRowToPinnedOnThisPhoneOnly() {
        val link = FakeLink(::hermes)
        val vm = viewModel(link)
        link.state.value = LinkState.Online(lan, 1)
        waitFor("listed") { vm.uiState.value.items != null }
        val row = vm.uiState.value.items!!.rows().first { it.key == "default-h7" }
        vm.showActions(row)
        vm.togglePin(row)
        val items = vm.uiState.value.items!!
        assertEquals(SessionListItem.Header(ListSection.Pinned), items.first())
        assertEquals("default-h7", (items[1] as SessionListItem.Row).row.key)
        assertEquals(setOf("default-h7"), pins.of(pc.pcId, null))
        assertNull(vm.uiState.value.actionsFor)
        assertTrue(link.calls.none { it.method == "session.mutate" }) // never a server pin
    }

    @Test
    fun searchFiltersTheLoadedRows() {
        val link = FakeLink(::hermes)
        val vm = viewModel(link)
        link.state.value = LinkState.Online(lan, 1)
        waitFor("listed") { vm.uiState.value.items != null }
        vm.openSearch()
        vm.search("H 4")
        assertEquals(listOf("default-h4", "default-h40", "default-h41", "default-h42", "default-h43", "default-h44", "default-h45", "default-h46", "default-h47", "default-h48", "default-h49"), vm.keys())
        vm.closeSearch()
        assertEquals(51, vm.keys().size)
        assertTrue(link.calls.none { it.text("title") != null }) // no server search
    }

    // New session: the Desktop's create in the listed profile; its chat opens with the composer focused.
    @Test
    fun aNewSessionOpensItsChatWithTheComposerFocused() {
        selection.select("pm")
        val link = FakeLink(::hermes)
        val vm = viewModel(link)
        link.state.value = LinkState.Online(lan, 1)
        waitFor("listed") { vm.uiState.value.items != null }
        vm.newSession()
        waitFor("created") { vm.uiState.value.open != null }
        assertEquals(OpenRequest("new-1", null, "pm", focusComposer = true), vm.uiState.value.open)
        val create = link.calls.single { it.method == "session.create" }
        assertEquals("gui", create.text("source"))
        assertEquals("pm", create.text("profile"))
    }

    @Test
    fun renameSendsTheMutationAndAFailureIsShown() {
        val link = FakeLink(::hermes)
        val vm = viewModel(link)
        link.state.value = LinkState.Online(lan, 1)
        waitFor("listed") { vm.uiState.value.items != null }
        val row = vm.uiState.value.items!!.rows().first()
        vm.startRename(row)
        vm.rename("  Better title ")
        waitFor("renamed") { vm.uiState.value.renaming == null }
        val mutate = link.calls.single { it.method == "session.mutate" }
        assertEquals(json("""{"title":"Better title"}"""), mutate.params["payload"])
        assertEquals(row.key, mutate.text("session_id"))

        link.answer = { call ->
            if (call.method == "session.mutate") {
                throw RpcException(RpcError(4001, "invalid_params", json("""{"reason":"invalid_params"}""") as JsonObject), "session.mutate failed: 4001")
            }
            hermes(call)
        }
        vm.startRename(row)
        vm.rename("x")
        waitFor("failed") { vm.uiState.value.message != null }
        assertEquals(SessionsMessage.RenameFailed("invalid_params"), vm.uiState.value.message)
        assertEquals(row, vm.uiState.value.renaming) // the dialog stays for another try
    }
}
