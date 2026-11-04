package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.AuthPurpose
import io.github.justcallmesu.aristotle.data.FreshAuth
import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.ResumedSessions
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.chat.ApprovalChoice
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
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

/** TS2 (Q61): "Always allow" and sudo/secret values wait for the app lock's fresh check; a failed one sends nothing. */
class ChatViewModelLockTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private class FakeLink(snapshot: String) : RelayLink {
        private val resumeResult: JsonElement = HermesJson.parseToJsonElement(snapshot)
        override val state = MutableStateFlow<LinkState>(LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), 1))
        override val events: SharedFlow<InboundFrame> = MutableSharedFlow(extraBufferCapacity = 8)
        val calls = CopyOnWriteArrayList<Pair<String, JsonObject>>()
        val responses = CopyOnWriteArrayList<Pair<String, JsonObject>>()

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            if (method == "session.resume") return resumeResult
            calls += method to params
            return HermesJson.parseToJsonElement("""{"status":"resolved"}""")
        }

        override fun respond(requestId: String, result: JsonObject): Boolean {
            responses += requestId to result
            return true
        }

        override fun retryNow() = Unit
    }

    /** A fake check: answers with [answer], or waits for [gate] when set. Records what it was asked for. */
    private class FakeAuth(var answer: Boolean) : FreshAuth {
        val asked = mutableListOf<AuthPurpose>()
        var gate: CompletableDeferred<Boolean>? = null
        override suspend fun confirm(purpose: AuthPurpose): Boolean {
            asked += purpose
            return gate?.await() ?: answer
        }
    }

    private val approval = """{"session_id":"s1","messages":[],"running":true,"replay_epoch":"e","last_sequence":3,
        "prompts":[{"kind":"approval","prompt_id":"p1","execution_generation":4,"command":"rm -r /tmp/x","description":"",
                    "choices":["once","deny","session","always"],"timeout_s":300}],"open_requests":[],"info":{}}"""

    private val sudo = """{"session_id":"s1","messages":[],"running":true,"replay_epoch":"e","last_sequence":3,"prompts":[],
        "open_requests":[{"id":"srq-0123456789ab","method":"sudo","params":{"session_id":"s1","command":"sudo apt install x"}}],"info":{}}"""

    private var now = 0L

    private fun viewModel(link: FakeLink, auth: FreshAuth) =
        ChatViewModel("s1", link, ResumedSessions(), background = Dispatchers.Unconfined, scope = scope, clock = { now }, freshAuth = auth)
            .also { now = 1_000 }

    private fun ChatViewModel.toAlwaysConfirmation() {
        choose("p1", ApprovalChoice.Always)
        now += PromptSheetLogic.ARM_MS
    }

    @Test
    fun alwaysAllowAsksAndAFailedCheckSendsNothing() {
        val link = FakeLink(approval)
        val auth = FakeAuth(answer = false)
        val vm = viewModel(link, auth)
        vm.toAlwaysConfirmation()
        vm.confirmAlways("p1")
        assertEquals(listOf(AuthPurpose.AlwaysAllow), auth.asked)
        assertTrue(link.calls.isEmpty())
        // Still in the confirmation step: the user may try again or step back.
        assertEquals("p1", vm.uiState.value.sheet.confirmAlways)
        assertNull(vm.uiState.value.sheet.authorizing)

        auth.answer = true
        vm.confirmAlways("p1")
        assertEquals("approval.respond", link.calls.single().first)
        assertEquals("always", (link.calls.single().second["choice"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun theOtherApprovalChoicesNeedNoCheck() {
        val link = FakeLink(approval)
        val auth = FakeAuth(answer = false)
        viewModel(link, auth).choose("p1", ApprovalChoice.Once)
        assertTrue(auth.asked.isEmpty())
        assertEquals(1, link.calls.size)
    }

    @Test
    fun aSudoPasswordWaitsForTheCheckAndStaysInTheFieldWhenItFails() {
        val link = FakeLink(sudo)
        val auth = FakeAuth(answer = false)
        val vm = viewModel(link, auth)
        val field = SecretField().apply { text = "hunter2" }
        vm.answerSecret("srq-0123456789ab", field)
        assertEquals(listOf(AuthPurpose.SecretValue), auth.asked)
        assertTrue(link.responses.isEmpty())
        assertEquals("hunter2", field.text)

        auth.answer = true
        vm.answerSecret("srq-0123456789ab", field)
        assertEquals(1, link.responses.size)
        assertTrue(field.isEmpty)
    }

    @Test
    fun whileTheCheckIsUpTheButtonsAreOffAndASecondTapIsIgnored() {
        val link = FakeLink(sudo)
        val auth = FakeAuth(answer = true).apply { gate = CompletableDeferred() }
        val vm = viewModel(link, auth)
        val field = SecretField().apply { text = "hunter2" }
        vm.answerSecret("srq-0123456789ab", field)
        assertEquals("srq-0123456789ab", vm.uiState.value.sheet.authorizing)
        vm.answerSecret("srq-0123456789ab", field)
        assertEquals(1, auth.asked.size)
        auth.gate!!.complete(true)
        assertNull(vm.uiState.value.sheet.authorizing)
        assertEquals(1, link.responses.size)
    }

    @Test
    fun aPromptAnsweredElsewhereDuringTheCheckGetsNothing() {
        val link = FakeLink(sudo)
        val auth = FakeAuth(answer = true).apply { gate = CompletableDeferred() }
        val vm = viewModel(link, auth)
        val field = SecretField().apply { text = "hunter2" }
        vm.answerSecret("srq-0123456789ab", field)
        vm.hideSheet() // the sheet went down (e.g. the prompt moved on) while the check was up
        auth.gate!!.complete(true)
        assertTrue(link.responses.isEmpty())
    }

    @Test
    fun declineNeedsNoCheck() {
        val link = FakeLink(sudo)
        val auth = FakeAuth(answer = false)
        viewModel(link, auth).declineSecret("srq-0123456789ab", SecretField())
        assertTrue(auth.asked.isEmpty())
        assertEquals(1, link.responses.size)
    }
}
