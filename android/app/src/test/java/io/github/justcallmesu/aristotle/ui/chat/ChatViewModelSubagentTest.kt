package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.MemoryStore
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.ResumedSessions
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.DelegateStatus
import io.github.justcallmesu.aristotle.data.chat.SubagentTail
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Subagents in [ChatViewModel] over a fake link (T17): no second subscription, the reads the Desktop makes, armed Stop. */
class ChatViewModelSubagentTest {
    private val session = "s1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private class Call(val method: String, val params: JsonObject)

    private class FakeLink(private val snapshot: String) : RelayLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), 1))
        val frames = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
        override val events: SharedFlow<InboundFrame> = frames
        val calls = CopyOnWriteArrayList<Call>()
        val answers = HashMap<String, (Call) -> JsonElement>()

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            val call = Call(method, params)
            calls += call
            if (method == "session.resume") return HermesJson.parseToJsonElement(snapshot)
            return answers[method]?.invoke(call) ?: HermesJson.parseToJsonElement("{}")
        }

        override fun respond(requestId: String, result: JsonObject): Boolean = true
        override fun retryNow() = Unit

        fun sent(method: String) = calls.filter { it.method == method }
        fun methods() = calls.map { it.method }
    }

    private fun json(text: String) = HermesJson.parseToJsonElement(text) as JsonObject

    private var seq = 3L
    private fun event(type: String, payload: String) = InboundFrame.Event(type, session, json(payload), seq = ++seq, replayEpoch = "e")

    private val plain = """{"session_id":"s1","messages":[],"running":false,"replay_epoch":"e","last_sequence":3,"prompts":[],"pending":[],"info":{}}"""

    /** Opened mid-delegation: the stored call has no result yet, so its children may run unseen. */
    private val midDelegation = """{"session_id":"s1","running":true,"replay_epoch":"e","last_sequence":3,"prompts":[],"pending":[],"info":{},
        "messages":[{"role":"user","content":"Audit it.","row_id":1},
                    {"role":"assistant","content":"","row_id":2,"tool_calls":[{"id":"call_d","type":"function","function":{"name":"delegate_task",
                     "arguments":"{\"tasks\":[{\"goal\":\"Audit the relay\"}]}"}}]}]}"""

    private val listed = """{"subagents":[{"subagent_id":"sa-1","goal":"Audit the relay","model":"m","started_at":1700000000,"status":"running","tool_count":2,"last_tool":"read_file"}],"delegations":[]}"""

    private var now = 0L

    private fun viewModel(link: FakeLink) =
        ChatViewModel(session, link, ResumedSessions(), ChatDrafts(MemoryStore()), background = Dispatchers.Unconfined, scope = scope, clock = { now }, wallClock = { 5_000 })
            .also { now = 1_000 }

    private fun ChatViewModel.chat() = uiState.value.feed.chat

    private fun FakeLink.startChild() {
        frames.tryEmit(event("tool.start", """{"tool_id":"call_d","name":"delegate_task","args":{"goal":"Audit the relay"}}"""))
        frames.tryEmit(event("subagent.start", """{"goal":"Audit the relay","task_count":1,"task_index":0,"subagent_id":"sa-1","model":"m","text":"Audit the relay"}"""))
    }

    @Test
    fun aPlainChatAsksNothingAboutSubagents() {
        val link = FakeLink(plain)
        viewModel(link)
        assertEquals(listOf("session.resume"), link.methods())
    }

    @Test
    fun aChatOpenedMidDelegationHydratesTheRosterOnceWithTheDesktopsCall() {
        val link = FakeLink(midDelegation)
        link.answers["subagent.list"] = { HermesJson.parseToJsonElement(listed) }
        val vm = viewModel(link)
        assertEquals(json("""{"session_id":"s1"}"""), link.sent("subagent.list").single().params)
        val row = vm.chat().items.filterIsInstance<ChatItem.Tool>().single().subagents.single()
        assertEquals("sa-1", row.subagentId)
        assertEquals(DelegateStatus.Running, row.status)
        // One resume, never a second subscription for the children.
        assertEquals(1, link.sent("session.resume").size)
    }

    @Test
    fun openingAChildReadsItsTailAndSubscribesNothing() {
        val link = FakeLink(plain)
        link.answers["subagent.tail"] = { HermesJson.parseToJsonElement("""{"subagent_id":"sa-1","available":true,"text":"12:00 tool | -> read_file(x)","truncated":false}""") }
        val vm = viewModel(link)
        link.startChild()
        vm.openSubagent("sa-1")
        assertEquals("sa-1", vm.uiState.value.subagent.open)
        assertEquals(json("""{"session_id":"s1","subagent_id":"sa-1"}"""), link.sent("subagent.tail").first().params)
        assertEquals(SubagentTail(true, "12:00 tool | -> read_file(x)", false), vm.uiState.value.subagent.tail)
        // The child's own session is never resumed: its live view is the parent's stream (Hermes' mirror only runs on the sidecar).
        assertEquals(1, link.sent("session.resume").size)
        assertTrue(link.methods().none { it == "session.create" || it == "session.detach" })
    }

    @Test
    fun anUnknownChildDoesNotOpen() {
        val vm = viewModel(FakeLink(plain))
        vm.openSubagent("nope")
        assertNull(vm.uiState.value.subagent.open)
    }

    @Test
    fun stopCountsOnlyOnceThePageHasBeenShownForTheArmingTime() {
        val link = FakeLink(plain)
        link.answers["subagent.interrupt"] = { HermesJson.parseToJsonElement("""{"found":true,"subagent_id":"sa-1"}""") }
        val vm = viewModel(link)
        link.startChild()
        vm.openSubagent("sa-1")
        now += SubagentLogic.ARM_MS - 1
        vm.stopSubagent("sa-1")
        assertTrue(link.sent("subagent.interrupt").isEmpty())
        now += 1
        vm.stopSubagent("sa-1")
        assertEquals(json("""{"session_id":"s1","subagent_id":"sa-1"}"""), link.sent("subagent.interrupt").single().params)
        assertEquals(SubagentMessage.StopRequested, vm.uiState.value.subagent.message)
    }

    @Test
    fun aStatusChangeUnderTheThumbRestartsTheArmingTime() {
        val link = FakeLink(plain)
        val vm = viewModel(link)
        link.startChild()
        vm.openSubagent("sa-1")
        now += SubagentLogic.ARM_MS
        link.frames.tryEmit(event("subagent.tool", """{"goal":"Audit the relay","task_count":1,"task_index":0,"subagent_id":"sa-1","status":"queued","tool_name":"x"}"""))
        vm.stopSubagent("sa-1")
        assertTrue(link.sent("subagent.interrupt").isEmpty())
    }

    @Test
    fun aRefusedStopSaysSoAndAFinishedChildHasNoStop() {
        val link = FakeLink(plain)
        link.answers["subagent.interrupt"] = { HermesJson.parseToJsonElement("""{"found":false,"subagent_id":"sa-1"}""") }
        val vm = viewModel(link)
        link.startChild()
        vm.openSubagent("sa-1")
        now += SubagentLogic.ARM_MS
        vm.stopSubagent("sa-1")
        assertEquals(SubagentMessage.Rejected, vm.uiState.value.subagent.message)
        link.frames.tryEmit(event("subagent.complete", """{"goal":"Audit the relay","task_count":1,"task_index":0,"subagent_id":"sa-1","status":"completed","summary":"ok"}"""))
        now += SubagentLogic.ARM_MS
        vm.stopSubagent("sa-1")
        assertEquals(1, link.sent("subagent.interrupt").size)
    }

    @Test
    fun thePageClosesWhenANewTurnDropsItsFinishedChild() {
        val link = FakeLink(plain)
        val vm = viewModel(link)
        link.startChild()
        vm.openSubagent("sa-1")
        link.frames.tryEmit(event("subagent.complete", """{"goal":"Audit the relay","task_count":1,"task_index":0,"subagent_id":"sa-1","status":"completed","summary":"ok"}"""))
        assertEquals("sa-1", vm.uiState.value.subagent.open)
        link.frames.tryEmit(event("message.start", "{}"))
        assertNull(vm.uiState.value.subagent.open)
    }
}
