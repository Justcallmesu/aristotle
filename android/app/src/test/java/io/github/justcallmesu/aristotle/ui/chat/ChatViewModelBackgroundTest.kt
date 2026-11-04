package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.MemoryStore
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.ResumedSessions
import io.github.justcallmesu.aristotle.data.RpcError
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import io.github.justcallmesu.aristotle.data.chat.ProcessKill
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

/**
 * TB1: the chat's `process.list` poll over a fake link (on screen, live and online only; stops on 4001/4044 and when
 * the chat is left) and the Stop dialog (only its armed red Stop sends `process.kill`).
 */
class ChatViewModelBackgroundTest {
    private val session = "s1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private class Call(val method: String, val params: JsonObject)

    private class FakeLink(private val snapshot: String) : RelayLink {
        override val state = MutableStateFlow<LinkState>(online(1))
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
    }

    private companion object {
        fun online(generation: Long) = LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), generation)
    }

    private fun json(text: String) = HermesJson.parseToJsonElement(text) as JsonObject

    private val plain = """{"session_id":"s1","messages":[],"running":false,"replay_epoch":"e","last_sequence":3,"prompts":[],"pending":[],"info":{}}"""

    private val oneProcess = """{"processes":[{"session_id":"proc_1","command":"npm run dev -- --port 5174","uptime_seconds":65,"status":"running"}]}"""

    private var now = 0L

    private fun viewModel(link: FakeLink, pollMs: Long = 60_000) =
        ChatViewModel(session, link, ResumedSessions(), ChatDrafts(MemoryStore()), background = Dispatchers.Unconfined, scope = scope, clock = { now }, wallClock = { 5_000 }, backgroundPollMs = pollMs)
            .also { now = 1_000 }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.nanoTime() + 3_000_000_000L
        while (!check()) {
            check(System.nanoTime() < until) { "timed out: $what" }
            Thread.sleep(5)
        }
    }

    private fun FakeLink.lists() = sent("process.list").size

    /** The count stays put for a while (several poll intervals). */
    private fun FakeLink.staysAt(count: Int) {
        Thread.sleep(250)
        assertEquals(count, lists())
    }

    // ── the poll ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun nothingIsAskedUntilTheChatIsOnScreenThenOneReadAtOnce() {
        val link = FakeLink(plain)
        link.answers["process.list"] = { HermesJson.parseToJsonElement(oneProcess) }
        val vm = viewModel(link)
        assertEquals(0, link.lists())
        vm.onStart()
        assertEquals(json("""{"session_id":"s1"}"""), link.sent("process.list").single().params)
        val process = vm.uiState.value.background.processes.single()
        assertEquals("proc_1", process.id)
        assertEquals(65, process.uptimeSeconds)
        assertEquals(5_000, vm.uiState.value.background.polledAt)
    }

    @Test
    fun itPollsAtItsPaceWhileVisibleAndStopsOnStop() {
        val link = FakeLink(plain)
        link.answers["process.list"] = { HermesJson.parseToJsonElement(oneProcess) }
        val vm = viewModel(link, pollMs = 30)
        vm.onStart()
        waitFor("a few reads") { link.lists() >= 3 }
        vm.onStop()
        val stopped = link.lists()
        link.staysAt(stopped)
        vm.onStart()
        waitFor("reads again on ON_START") { link.lists() > stopped }
    }

    @Test
    fun leavingTheScreenStopsThePoll() {
        val link = FakeLink(plain)
        link.answers["process.list"] = { HermesJson.parseToJsonElement(oneProcess) }
        val vm = viewModel(link, pollMs = 30)
        vm.onStart()
        waitFor("a read") { link.lists() >= 1 }
        vm.onScreenGone()
        link.staysAt(link.lists())
    }

    @Test
    fun leavingTheChatEndsThePollWithTheViewModel() {
        val link = FakeLink(plain)
        link.answers["process.list"] = { HermesJson.parseToJsonElement(oneProcess) }
        val vm = viewModel(link, pollMs = 30)
        vm.onStart()
        waitFor("a read") { link.lists() >= 1 }
        scope.cancel() // the ViewModel's scope ends when the chat's route is cleared
        link.staysAt(link.lists())
    }

    @Test
    fun noPollWhileTheLinkIsDownThenOnceTheChatIsSubscribedAgain() {
        val link = FakeLink(plain)
        link.answers["process.list"] = { HermesJson.parseToJsonElement(oneProcess) }
        val vm = viewModel(link, pollMs = 30)
        vm.onStart()
        waitFor("a read") { link.lists() >= 1 }
        link.state.value = LinkState.Connecting
        link.staysAt(link.lists())
        val before = link.lists()
        link.state.value = online(2)
        waitFor("reads on the new socket") { link.lists() > before }
        assertEquals(2, link.sent("session.resume").size)
    }

    @Test
    fun sessionGoneStopsThePollUntilANewSubscription() {
        val link = FakeLink(plain)
        var gone = true
        link.answers["process.list"] = {
            if (gone) throw RpcException(RpcError(4001, "session not found"), "process.list failed: 4001")
            HermesJson.parseToJsonElement(oneProcess)
        }
        val vm = viewModel(link, pollMs = 30)
        vm.onStart()
        assertEquals(1, link.lists())
        link.staysAt(1)
        // Neither an event nor coming back to the screen asks again on this subscription.
        link.frames.tryEmit(InboundFrame.Event("tool.complete", session, json("""{"tool_id":"t","name":"terminal"}""")))
        vm.onStop()
        vm.onStart()
        link.staysAt(1)
        assertTrue(vm.uiState.value.background.processes.isEmpty())
        gone = false
        link.state.value = LinkState.Connecting
        link.state.value = online(2)
        waitFor("reads after the resubscription") { link.lists() >= 2 }
        waitFor("rows") { vm.uiState.value.background.processes.isNotEmpty() }
    }

    @Test
    fun noSuchProcessAlsoStopsThePoll() {
        val link = FakeLink(plain)
        link.answers["process.list"] = { throw RpcException(RpcError(4044, "no such process"), "process.list failed: 4044") }
        val vm = viewModel(link, pollMs = 30)
        vm.onStart()
        link.staysAt(1)
    }

    @Test
    fun aLostAnswerKeepsPolling() {
        val link = FakeLink(plain)
        link.answers["process.list"] = { call ->
            if (link.lists() == 1) HermesJson.parseToJsonElement(oneProcess) else throw RpcException(null, "process.list: no answer in 15 s")
        }
        val vm = viewModel(link, pollMs = 30)
        vm.onStart()
        waitFor("still polling") { link.lists() >= 3 }
    }

    @Test
    fun aTerminalOrProcessToolOfThisSessionReadsTheListAtOnce() {
        val link = FakeLink(plain)
        val vm = viewModel(link)
        vm.onStart()
        assertEquals(1, link.lists())
        link.frames.tryEmit(InboundFrame.Event("tool.complete", session, json("""{"tool_id":"t","name":"terminal"}""")))
        waitFor("event read") { link.lists() == 2 }
        link.frames.tryEmit(InboundFrame.Event("status.update", session, json("""{"kind":"process","text":"x"}""")))
        waitFor("status read") { link.lists() == 3 }
        link.frames.tryEmit(InboundFrame.Event("tool.complete", "other", json("""{"tool_id":"t","name":"terminal"}""")))
        link.frames.tryEmit(InboundFrame.Event("tool.complete", session, json("""{"tool_id":"t","name":"read_file"}""")))
        link.staysAt(3)
    }

    // ── the Stop dialog ───────────────────────────────────────────────────────────────────────────

    private fun stoppable(killAnswer: (Call) -> JsonElement): Pair<FakeLink, ChatViewModel> {
        val link = FakeLink(plain)
        link.answers["process.list"] = { HermesJson.parseToJsonElement(oneProcess) }
        link.answers["process.kill"] = killAnswer
        val vm = viewModel(link)
        vm.onStart()
        return link to vm
    }

    @Test
    fun stopOnTheRowOnlyOpensTheDialog() {
        val (link, vm) = stoppable { HermesJson.parseToJsonElement("""{"status":"killed"}""") }
        vm.askStopProcess("proc_1")
        val confirm = vm.uiState.value.background.confirm
        assertEquals("npm run dev -- --port 5174", confirm?.process?.command)
        assertTrue(link.sent("process.kill").isEmpty())
    }

    @Test
    fun cancelBackOrOutsideSendNothing() {
        val (link, vm) = stoppable { HermesJson.parseToJsonElement("""{"status":"killed"}""") }
        vm.askStopProcess("proc_1")
        now += BackgroundLogic.ARM_MS
        vm.cancelStopProcess() // Cancel, back and a tap outside all call this
        assertNull(vm.uiState.value.background.confirm)
        vm.confirmStopProcess() // a late tap on the closed dialog
        assertTrue(link.sent("process.kill").isEmpty())
    }

    @Test
    fun onlyTheArmedRedStopSendsTheKillOnceThenTheListIsReadAgain() {
        val (link, vm) = stoppable { HermesJson.parseToJsonElement("""{"status":"killed","session_id":"proc_1"}""") }
        vm.askStopProcess("proc_1")
        now += BackgroundLogic.ARM_MS - 1
        vm.confirmStopProcess()
        assertTrue(link.sent("process.kill").isEmpty())
        now += 1
        link.answers["process.list"] = { HermesJson.parseToJsonElement("""{"processes":[{"session_id":"proc_1","command":"npm run dev","uptime_seconds":66,"status":"exited","exit_code":-15}]}""") }
        vm.confirmStopProcess()
        assertEquals(json("""{"session_id":"s1","process_id":"proc_1"}"""), link.sent("process.kill").single().params)
        assertEquals(ProcessKill.Stopped, vm.uiState.value.background.result)
        assertNull(vm.uiState.value.background.confirm)
        waitFor("the row leaves on the next read") { vm.uiState.value.background.processes.isEmpty() }
        vm.confirmStopProcess()
        assertEquals(1, link.sent("process.kill").size)
        vm.processResultShown()
        assertNull(vm.uiState.value.background.result)
    }

    @Test
    fun theResultSaysWhatHermesDid() {
        for ((answer, expected) in listOf(
            """{"status":"already_exited"}""" to ProcessKill.AlreadyExited,
            """{"status":"not_found","error":"No process with ID proc_1"}""" to ProcessKill.NotFound,
            """{"status":"error","error":"Kill incomplete"}""" to ProcessKill.Failed("Kill incomplete"),
        )) {
            val (_, vm) = stoppable { HermesJson.parseToJsonElement(answer) }
            vm.askStopProcess("proc_1")
            now += BackgroundLogic.ARM_MS
            vm.confirmStopProcess()
            assertEquals(expected, vm.uiState.value.background.result)
        }
        val (_, vm) = stoppable { throw RpcException(RpcError(4044, "no such process: proc_1"), "process.kill failed: 4044") }
        vm.askStopProcess("proc_1")
        now += BackgroundLogic.ARM_MS
        vm.confirmStopProcess()
        assertEquals(ProcessKill.NotFound, vm.uiState.value.background.result)
    }

    @Test
    fun anUnknownProcessOpensNoDialog() {
        val (link, vm) = stoppable { HermesJson.parseToJsonElement("""{"status":"killed"}""") }
        vm.askStopProcess("proc_9")
        assertNull(vm.uiState.value.background.confirm)
        now += BackgroundLogic.ARM_MS
        vm.confirmStopProcess()
        assertTrue(link.sent("process.kill").isEmpty())
    }

    // ── TB1b: the timer only while a process runs (Desktop parity) ─────────────────────────────

    @Test
    fun withNoRunningRowTheListIsReadOnceAndTheTimerStops() {
        val link = FakeLink(plain)
        val vm = viewModel(link, pollMs = 30)
        vm.onStart()
        waitFor("the opening read") { link.lists() == 1 }
        link.staysAt(1)
        // An event still reads it, and a process that appears starts the timer.
        link.answers["process.list"] = { HermesJson.parseToJsonElement(oneProcess) }
        link.frames.tryEmit(InboundFrame.Event("tool.complete", session, json("""{"tool_id":"t","name":"terminal"}""")))
        waitFor("event read") { link.lists() >= 2 }
        waitFor("timer polls while it runs") { link.lists() >= 4 }
        // It ends: one more read sees no row, and the timer stops again.
        link.answers["process.list"] = { HermesJson.parseToJsonElement("""{"processes":[]}""") }
        waitFor("rows gone") { vm.uiState.value.background.processes.isEmpty() }
        val settled = link.lists()
        link.staysAt(settled)
        // A reconnect reads once more.
        link.state.value = LinkState.Connecting
        link.state.value = online(2)
        waitFor("read on reconnect") { link.lists() > settled }
    }

    @Test
    fun aKillWhoseFrameWasSentButWhoseAnswerNeverCameIsUnknown() {
        val (_, vm) = stoppable { throw RpcException(null, "link closed", linkClosed = true) }
        vm.askStopProcess("proc_1")
        now += BackgroundLogic.ARM_MS
        vm.confirmStopProcess()
        assertEquals(ProcessKill.Unknown, vm.uiState.value.background.result)
        val (_, unsent) = stoppable { throw RpcException(null, "socket not open", linkClosed = true, written = false) }
        unsent.askStopProcess("proc_1")
        now += BackgroundLogic.ARM_MS
        unsent.confirmStopProcess()
        assertEquals(ProcessKill.NotSent, unsent.uiState.value.background.result)
    }

    @Test
    fun theDialogClosesWhenItsProcessLeavesTheList() {
        val link = FakeLink(plain)
        link.answers["process.list"] = { HermesJson.parseToJsonElement(oneProcess) }
        val vm = viewModel(link)
        vm.onStart()
        vm.askStopProcess("proc_1")
        assertTrue(vm.uiState.value.background.confirm != null)
        link.answers["process.list"] = { HermesJson.parseToJsonElement("""{"processes":[]}""") }
        link.frames.tryEmit(InboundFrame.Event("status.update", session, json("""{"kind":"process","text":"x"}""")))
        waitFor("dialog closed") { vm.uiState.value.background.confirm == null }
    }
}
