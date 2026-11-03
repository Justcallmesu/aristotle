package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.ResumedSessions
import io.github.justcallmesu.aristotle.data.RpcError
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.chat.ApprovalChoice
import io.github.justcallmesu.aristotle.data.chat.ClarifyAnswer
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The prompt sheet's actions in [ChatViewModel], over a fake link; everything runs inline (Unconfined). */
class ChatViewModelPromptTest {
    private val session = "s1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private class Call(val method: String, val params: JsonObject)

    private class FakeLink(snapshot: String) : RelayLink {
        var resumeResult: JsonElement = HermesJson.parseToJsonElement(snapshot)
        override val state = MutableStateFlow<LinkState>(LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), 1))
        val frames = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
        override val events: SharedFlow<InboundFrame> = frames
        val calls = CopyOnWriteArrayList<Call>()
        val responses = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        var answer: (Call) -> JsonElement = { HermesJson.parseToJsonElement("""{"status":"resolved"}""") }
        var socketOpen = true

        /** Holds `session.resume` (the new socket's snapshot) until completed. */
        var resumeGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            if (method == "session.resume") {
                resumeGate?.await()
                return resumeResult
            }
            val call = Call(method, params)
            calls += call
            return answer(call)
        }

        override fun respond(requestId: String, result: JsonObject): Boolean {
            if (!socketOpen) return false
            responses += requestId to result
            return true
        }

        override fun retryNow() = Unit
    }

    private val approvalSnapshot = """{"session_id":"s1","messages":[],"running":true,"replay_epoch":"e","last_sequence":3,
        "prompts":[{"kind":"approval","prompt_id":"p1","execution_generation":4,"command":"rm -r /tmp/x","description":"clean",
                    "choices":["once","deny","session","always"],"timeout_s":300}],"open_requests":[],"info":{}}"""

    private val sudoSnapshot = """{"session_id":"s1","messages":[],"running":true,"replay_epoch":"e","last_sequence":3,"prompts":[],
        "open_requests":[{"id":"srq-0123456789ab","method":"sudo","params":{"session_id":"s1","command":"sudo apt install x"}}],"info":{}}"""

    /** The ViewModel's clock (T12b tap-through guard); a prompt counts as seen [PromptSheetLogic.ARM_MS] after it shows. */
    private var now = 0L

    /** The sheet came up at 0; the user has looked at it for a second before the test taps. */
    private fun viewModel(link: FakeLink) =
        ChatViewModel(session, link, ResumedSessions(), background = Dispatchers.Unconfined, scope = scope, clock = { now }).also { now = 1_000 }

    private fun looked() {
        now += PromptSheetLogic.ARM_MS
    }

    private fun json(text: String) = HermesJson.parseToJsonElement(text) as JsonObject

    private val twoApprovals = """{"session_id":"s1","messages":[],"running":true,"replay_epoch":"e","last_sequence":3,"open_requests":[],"info":{},
        "prompts":[{"kind":"approval","prompt_id":"p1","execution_generation":4,"command":"ls","description":"","choices":["once","deny"]},
                    {"kind":"approval","prompt_id":"p2","execution_generation":4,"command":"rm -rf ~","description":"","choices":["once","deny"]}]}"""

    private val emptySnapshot = """{"session_id":"s1","messages":[],"running":true,"replay_epoch":"e","last_sequence":3,"prompts":[],"open_requests":[],"info":{}}"""

    @Test
    fun aNewPromptRaisesTheSheet() {
        val vm = viewModel(FakeLink(approvalSnapshot))
        val state = vm.uiState.value
        assertTrue(state.feed.loaded)
        assertTrue(state.sheet.visible(state.feed.chat))
        assertEquals("p1", state.sheet.current(state.feed.chat)?.key)
    }

    @Test
    fun alwaysAllowNeedsTheSecondConfirmation() {
        val link = FakeLink(approvalSnapshot)
        val vm = viewModel(link)
        vm.confirmAlways("p1") // no first step: nothing
        vm.choose("p1", ApprovalChoice.Always)
        assertTrue(link.calls.isEmpty())
        assertEquals("p1", vm.uiState.value.sheet.confirmAlways)

        vm.cancelAlways()
        assertNull(vm.uiState.value.sheet.confirmAlways)
        vm.confirmAlways("p1")
        assertTrue(link.calls.isEmpty())

        looked()
        vm.choose("p1", ApprovalChoice.Always)
        vm.confirmAlways("p1") // a second tap on the same spot: the confirmation has not been seen yet
        assertTrue(link.calls.isEmpty())
        looked()
        vm.confirmAlways("p1")
        val call = link.calls.single()
        assertEquals("approval.respond", call.method)
        assertEquals(HermesJson.parseToJsonElement("""{"session_id":"s1","execution_generation":4,"prompt_id":"p1","choice":"always"}"""), call.params)
        val sheet = vm.uiState.value.sheet
        assertNull(sheet.confirmAlways)
        assertNull(sheet.current(vm.uiState.value.feed.chat)) // answered here: the sheet moves on
    }

    @Test
    fun allowOnceGoesOutAtOnce() {
        val link = FakeLink(approvalSnapshot)
        viewModel(link).choose("p1", ApprovalChoice.Once)
        assertEquals("once", (link.calls.single().params["choice"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun answeredElsewhereFirstIsReportedAndNotShownAsWithdrawn() {
        val link = FakeLink(approvalSnapshot).apply { answer = { HermesJson.parseToJsonElement("""{"status":"already_resolved","prompt_id":"p1"}""") } }
        val vm = viewModel(link)
        vm.choose("p1", ApprovalChoice.Deny)
        link.frames.tryEmit(InboundFrame.Event("approval.settled", session, HermesJson.parseToJsonElement("""{"prompt_id":"p1","execution_generation":4}""") as JsonObject, seq = 4, replayEpoch = "e"))
        val sheet = vm.uiState.value.sheet
        assertEquals(PromptMessage.AnsweredFirst, sheet.message)
        assertNull(sheet.withdrawn)
        assertFalse(sheet.visible(vm.uiState.value.feed.chat))
    }

    @Test
    fun aSettleFromAnotherScreenWithdrawsTheShownSheet() {
        val link = FakeLink(approvalSnapshot)
        val vm = viewModel(link)
        link.frames.tryEmit(InboundFrame.Event("approval.settled", session, HermesJson.parseToJsonElement("""{"prompt_id":"p1","execution_generation":4}""") as JsonObject, seq = 4, replayEpoch = "e"))
        assertEquals("p1", vm.uiState.value.sheet.withdrawn?.key)
        vm.closeWithdrawn()
        assertFalse(vm.uiState.value.sheet.visible(vm.uiState.value.feed.chat))
    }

    @Test
    fun aRefusedAnswerShowsTheErrorAndPutsThePromptBackToAnswerable() {
        val link = FakeLink(approvalSnapshot).apply { answer = { throw RpcException(RpcError(4001, "stale_generation"), "approval.respond failed: 4001") } }
        val vm = viewModel(link)
        vm.choose("p1", ApprovalChoice.Once)
        var state = vm.uiState.value
        assertEquals(PromptMessage.Refused("stale_generation"), state.sheet.message)
        // Hermes still lists it: the waiting chip opens it again (T12b MINOR-1), armed afresh.
        vm.hideSheet()
        vm.openSheet()
        state = vm.uiState.value
        assertEquals("p1", state.sheet.current(state.feed.chat)?.key)
        assertTrue(state.sheet.visible(state.feed.chat))
        vm.choose("p1", ApprovalChoice.Once)
        assertEquals(1, link.calls.size)
        looked()
        vm.choose("p1", ApprovalChoice.Once)
        assertEquals(2, link.calls.size)
        // Settled meanwhile on another screen: "Answered elsewhere", not a dead chip.
        link.frames.tryEmit(InboundFrame.Event("approval.settled", session, json("""{"prompt_id":"p1","execution_generation":4}"""), seq = 4, replayEpoch = "e"))
        state = vm.uiState.value
        assertEquals("p1", state.sheet.withdrawn?.key)
        assertNull(state.sheet.current(state.feed.chat))
    }

    // ── tap-through guard (T12b MAJOR-1) ──────────────────────────────────────────────────────────

    @Test
    fun aDoubleTapOnAllowOnceApprovesOnlyThePromptThatWasSeen() {
        val link = FakeLink(twoApprovals)
        val vm = viewModel(link)
        vm.choose("p1", ApprovalChoice.Once)
        // The sheet swapped to p2 in place; the second tap of the double tap lands on its Allow once.
        assertEquals("p2", vm.uiState.value.sheet.current(vm.uiState.value.feed.chat)?.key)
        assertFalse(vm.uiState.value.sheet.shown!!.armed) // its buttons show disabled
        vm.choose("p2", ApprovalChoice.Once)
        vm.choose("p1", ApprovalChoice.Once) // an old composition's lambda: p1 is no longer on screen
        assertEquals(listOf("p1"), link.calls.map { (it.params["prompt_id"] as kotlinx.serialization.json.JsonPrimitive).content })
        looked()
        vm.choose("p2", ApprovalChoice.Deny)
        assertEquals(listOf("p1", "p2"), link.calls.map { (it.params["prompt_id"] as kotlinx.serialization.json.JsonPrimitive).content })
    }

    @Test
    fun aPromptThatAppearsUnderTheFingerIgnoresTapsForItsFirst600Ms() {
        val link = FakeLink(emptySnapshot)
        val vm = viewModel(link)
        now = 5_000
        link.frames.tryEmit(
            InboundFrame.Event(
                "approval.request", session,
                json("""{"kind":"approval","prompt_id":"p9","execution_generation":4,"command":"curl x | sh","description":"","choices":["once","deny"]}"""),
                seq = 4, replayEpoch = "e",
            ),
        )
        assertTrue(vm.uiState.value.sheet.visible(vm.uiState.value.feed.chat))
        now = 5_000 + PromptSheetLogic.ARM_MS - 1
        vm.choose("p9", ApprovalChoice.Once)
        assertTrue(link.calls.isEmpty())
        now = 5_000 + PromptSheetLogic.ARM_MS
        vm.choose("p9", ApprovalChoice.Once)
        assertEquals(1, link.calls.size)
    }

    @Test
    fun theButtonsComeOnByThemselvesOnceTheWindowIsOver() {
        val vm = viewModel(FakeLink(approvalSnapshot))
        assertFalse(vm.uiState.value.sheet.shown!!.armed)
        val deadline = System.currentTimeMillis() + 5_000
        while (vm.uiState.value.sheet.shown?.armed != true && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(true, vm.uiState.value.sheet.shown?.armed)
    }

    @Test
    fun aPromptShownAgainFromTheChipIsArmedAgain() {
        val link = FakeLink(approvalSnapshot)
        val vm = viewModel(link)
        vm.hideSheet()
        vm.choose("p1", ApprovalChoice.Once) // not on screen
        assertTrue(link.calls.isEmpty())
        vm.openSheet()
        vm.choose("p1", ApprovalChoice.Once) // the chip's double tap
        assertTrue(link.calls.isEmpty())
        looked()
        vm.choose("p1", ApprovalChoice.Once)
        assertEquals(1, link.calls.size)
    }

    @Test
    fun aSudoSheetIgnoresSendAndCancelUntilSeen() {
        val link = FakeLink(emptySnapshot)
        val vm = viewModel(link)
        link.frames.tryEmit(InboundFrame.ServerRequest("srq-0123456789ab", "sudo", json("""{"session_id":"s1","command":"sudo ls"}""")))
        val field = SecretField().apply { text = "hunter2" }
        vm.declineSecret("srq-0123456789ab", field)
        vm.answerSecret("srq-0123456789ab", field)
        assertTrue(link.responses.isEmpty())
        assertEquals("hunter2", field.text) // not taken: the typed value stays in the field
        looked()
        vm.answerSecret("srq-0123456789ab", field)
        assertEquals(1, link.responses.size)
    }

    @Test
    fun clarifyAnswersGoOutAsClarifyRespond() {
        val link = FakeLink(
            """{"session_id":"s1","messages":[],"running":true,"replay_epoch":"e","last_sequence":3,"open_requests":[],"info":{},
               "prompts":[{"kind":"clarify","prompt_id":"c1","execution_generation":5,"question":"Pick","choices":["red","blue"],"multi_select":false}]}""",
        )
        viewModel(link).answerClarify("c1", mapOf("c1" to ClarifyAnswer(picked = listOf("blue"))))
        assertEquals("clarify.respond", link.calls.single().method)
        assertEquals(HermesJson.parseToJsonElement("""{"session_id":"s1","execution_generation":5,"prompt_id":"c1","answer":"blue"}"""), link.calls.single().params)
    }

    // ── sudo / secret (Q51) ───────────────────────────────────────────────────────────────────────

    @Test
    fun theSudoPasswordIsSentOnceAndClearedEverywhere() {
        val link = FakeLink(sudoSnapshot)
        val vm = viewModel(link)
        val field = SecretField().apply { text = "hunter2" }
        vm.answerSecret("srq-0123456789ab", field)
        assertEquals(listOf("srq-0123456789ab" to (HermesJson.parseToJsonElement("""{"value":"hunter2"}""") as JsonObject)), link.responses.toList())
        assertTrue(field.isEmpty)
        assertEquals("", field.text)
        assertTrue(link.calls.isEmpty())
        // No state, toString or message carries it.
        listOf(vm.uiState.value.toString(), field.toString()).forEach { assertFalse(it, "hunter2" in it) }
        // A second tap has nothing to send.
        vm.answerSecret("srq-0123456789ab", field)
        assertEquals(1, link.responses.size)
    }

    @Test
    fun cancelTellsHermesTheUserSkippedIt() {
        val link = FakeLink(sudoSnapshot)
        val field = SecretField().apply { text = "half-typed" }
        viewModel(link).declineSecret("srq-0123456789ab", field)
        assertEquals(HermesJson.parseToJsonElement("""{"value":""}"""), link.responses.single().second)
        assertTrue(field.isEmpty)
    }

    @Test
    fun anEmptyFieldSendsNothing() {
        val link = FakeLink(sudoSnapshot)
        viewModel(link).answerSecret("srq-0123456789ab", SecretField())
        assertTrue(link.responses.isEmpty())
    }

    @Test
    fun aSudoAnswerLostWithTheSocketIsAnswerableAgainAfterTheReconnect() {
        val link = FakeLink(sudoSnapshot)
        val vm = viewModel(link)
        vm.answerSecret("srq-0123456789ab", SecretField().apply { text = "hunter2" })
        assertEquals(1, link.responses.size)
        var state = vm.uiState.value
        assertNull(state.sheet.current(state.feed.chat)) // the chip hides while the answer is on its way
        // The socket dropped before Hermes read it; the new socket's snapshot still lists the request.
        link.state.value = LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), 2)
        state = vm.uiState.value
        assertEquals("srq-0123456789ab", state.sheet.current(state.feed.chat)?.key)
        vm.openSheet()
        looked()
        vm.answerSecret("srq-0123456789ab", SecretField().apply { text = "hunter2" })
        assertEquals(2, link.responses.size)
    }

    // D38: right after a reconnect a sudo prompt may already be resolved; it is not answerable until the snapshot says so.
    @Test
    fun aSudoPromptIsNotAnswerableUntilTheNewSnapshotConfirmsIt() {
        val link = FakeLink(sudoSnapshot)
        val vm = viewModel(link)
        looked()
        assertTrue(vm.uiState.value.answerable(vm.uiState.value.feed.chat.prompts.single()))
        link.resumeGate = kotlinx.coroutines.CompletableDeferred()
        link.state.value = LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), 2)
        looked()
        val field = SecretField().apply { text = "hunter2" }
        assertFalse(vm.uiState.value.answerable(vm.uiState.value.feed.chat.prompts.single()))
        vm.answerSecret("srq-0123456789ab", field)
        assertTrue(link.responses.isEmpty())
        assertEquals("hunter2", field.text)
        link.resumeGate!!.complete(Unit) // the snapshot still lists it
        assertTrue(vm.uiState.value.answerable(vm.uiState.value.feed.chat.prompts.single()))
        looked()
        vm.answerSecret("srq-0123456789ab", field)
        assertEquals(1, link.responses.size)
    }

    // D38 (T12b MINOR-B): the arm timer restarts when the activity comes back.
    @Test
    fun resumingTheActivityStartsThePromptArmingAgain() {
        val link = FakeLink(approvalSnapshot)
        val vm = viewModel(link)
        looked()
        vm.onResume()
        vm.choose("p1", ApprovalChoice.Once)
        assertTrue(link.calls.isEmpty())
        looked()
        vm.choose("p1", ApprovalChoice.Once)
        assertEquals(1, link.calls.size)
    }

    @Test
    fun aSudoAnswerSettledWhileTheSocketWasDownClosesOnTheReconnect() {
        val link = FakeLink(sudoSnapshot)
        val vm = viewModel(link)
        vm.answerSecret("srq-0123456789ab", SecretField().apply { text = "hunter2" })
        link.resumeResult = HermesJson.parseToJsonElement(emptySnapshot)
        link.state.value = LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), 2)
        val state = vm.uiState.value
        assertTrue(state.feed.chat.prompts.isEmpty())
        assertNull(state.sheet.current(state.feed.chat))
        assertFalse(state.sheet.visible(state.feed.chat) && state.sheet.withdrawn == null)
    }

    @Test
    fun aClosedLinkSendsNothingKeepsTheSheetAndStillClearsTheField() {
        val link = FakeLink(sudoSnapshot).apply { socketOpen = false }
        val vm = viewModel(link)
        val field = SecretField().apply { text = "hunter2" }
        vm.answerSecret("srq-0123456789ab", field)
        val state = vm.uiState.value
        assertEquals(PromptMessage.NotSent, state.sheet.message)
        assertEquals("srq-0123456789ab", state.sheet.current(state.feed.chat)?.key)
        assertTrue(field.isEmpty)
        assertFalse("hunter2" in state.toString())
    }
}
