package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.MemoryStore
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.OfflineCause
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.ResumedSessions
import io.github.justcallmesu.aristotle.data.RpcError
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import io.github.justcallmesu.aristotle.data.chat.ChatItem
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
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The composer (T13) in [ChatViewModel] over a fake link: Send, Steer, Queue, Stop, the echo, Retry, the guard. */
class ChatViewModelComposerTest {
    private val session = "s1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val lan = ServerEndpoint("192.168.1.61", 8760, Network.Lan)

    @After
    fun tearDown() = scope.cancel()

    private class Call(val method: String, val params: JsonObject)

    private class FakeLink(snapshot: String) : RelayLink {
        var resumeResult: JsonElement = HermesJson.parseToJsonElement(snapshot)
        override val state = MutableStateFlow<LinkState>(LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), 1))
        val frames = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
        override val events: SharedFlow<InboundFrame> = frames
        val calls = CopyOnWriteArrayList<Call>()
        var answer: suspend (Call) -> JsonElement = { HermesJson.parseToJsonElement("{}") }

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            if (method == "session.resume") return resumeResult
            // A catch-up after a reconnect: nothing new, so the feed resumes again.
            if (method == "session.events.since") return HermesJson.parseToJsonElement("""{"events":[],"truncated":true,"snapshot_required":true}""")
            val call = Call(method, params)
            calls += call
            return answer(call)
        }

        override fun respond(requestId: String, result: JsonObject): Boolean = true
        override fun retryNow() = Unit

        fun sent(method: String) = calls.filter { it.method == method }
    }

    private fun json(text: String) = HermesJson.parseToJsonElement(text) as JsonObject

    private val idle = """{"session_id":"s1","messages":[],"running":false,"replay_epoch":"e","last_sequence":3,"prompts":[],"open_requests":[],
        "pending":[],"execution_generation":5,"info":{}}"""

    private val busy = """{"session_id":"s1","messages":[{"role":"user","content":"Count","row_id":1}],"running":true,"replay_epoch":"e","last_sequence":3,
        "prompts":[],"open_requests":[],"execution_generation":6,"info":{},
        "pending":[{"admission_id":"a0","input_id":"i0","status":"started","text":"Count"}]}"""

    private fun receipt(admission: String, status: String = "queued") =
        HermesJson.parseToJsonElement("""{"admission_id":"$admission","ref":{"profile_id":"/h","session_id":"s1"},"sequence":1,"status":"$status","outcome":null,"authority_epoch":1,"execution_generation":null}""")

    private var now = 0L
    private var ids = 0
    private val drafts = ChatDrafts(MemoryStore())

    /** Opened at 0; the user has looked at the composer for a second before the test taps. */
    private fun viewModel(link: FakeLink) =
        ChatViewModel(session, link, ResumedSessions(), drafts, background = Dispatchers.Unconfined, scope = scope, clock = { now }, newId = { "id-${++ids}" })
            .also { now = 1_000 }

    private fun looked() {
        now += ComposerLogic.ARM_MS
    }

    private fun event(type: String, payload: String, seq: Long, admission: String? = null, generation: Long? = null) =
        InboundFrame.Event(type, session, json(payload), seq = seq, replayEpoch = "e", admissionId = admission, executionGeneration = generation)

    private fun ChatViewModel.composer() = uiState.value.composer

    private fun ChatViewModel.userTexts(): List<String> {
        val chat = uiState.value.feed.chat.items.filterIsInstance<ChatItem.User>().map { it.text }
        val echoes = ComposerLogic.tail(composer().outbox).map { it.text }
        return chat + echoes
    }

    private fun Call.text(key: String) = (params[key] as JsonPrimitive).content

    // ── Send ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun idleSendShowsTheMessageAtOnceAndHermesOwnRowReplacesIt() {
        val link = FakeLink(idle).apply { answer = { receipt("a1") } }
        val vm = viewModel(link)
        vm.editDraft("  hello  ")
        vm.send()
        assertEquals(json("""{"session_id":"s1","text":"hello","submission_id":"id-1"}"""), link.sent("prompt.submit").single().params)
        assertEquals("", vm.composer().draft)
        assertEquals("", drafts.get(session))
        assertEquals(listOf("hello"), vm.userTexts())
        assertEquals(OutgoingStatus.Sent("a1", 1), vm.composer().outbox.single().status)

        // Hermes lists it (shape A, still idle): the bubble stays as it is, no queued row flickers in.
        link.frames.tryEmit(event("session.info", """{"pending":[{"admission_id":"a1","input_id":"id-1","status":"queued","text":"hello"}],"running":false}""", 4))
        assertEquals(listOf("hello"), vm.userTexts())
        assertTrue(ComposerLogic.queuedRows(vm.uiState.value.feed, vm.composer().outbox).isEmpty())
        // The turn starts: Hermes' own user row, and the echo is gone. Shown once.
        link.frames.tryEmit(event("message.start", "{}", 5, admission = "a1", generation = 6))
        assertEquals(listOf("hello"), vm.userTexts())
        assertTrue(vm.composer().outbox.isEmpty())
    }

    @Test
    fun hermesListingTheMessageBeforeItsAnswerArrivesStillShowsItOnce() {
        val ack = CompletableDeferred<JsonElement>()
        val link = FakeLink(idle).apply { answer = { ack.await() } }
        val vm = viewModel(link)
        vm.editDraft("hello")
        vm.send()
        assertEquals(OutgoingStatus.Sending, vm.composer().outbox.single().status)
        link.frames.tryEmit(event("session.info", """{"pending":[{"admission_id":"a1","input_id":"id-1","status":"queued","text":"hello"}]}""", 4))
        link.frames.tryEmit(event("message.start", "{}", 5, admission = "a1"))
        ack.complete(receipt("a1", "started"))
        assertEquals(listOf("hello"), vm.userTexts())
        assertTrue(vm.composer().outbox.isEmpty())
    }

    @Test
    fun draftsArePerSessionAndSurviveANewViewModel() {
        val first = viewModel(FakeLink(idle))
        first.editDraft("half a thought")
        assertEquals("half a thought", drafts.get(session))
        val second = viewModel(FakeLink(idle))
        assertEquals("half a thought", second.composer().draft)
        assertEquals("", drafts.get("another"))
    }

    @Test
    fun offlineNothingIsSentAndTheTextStays() {
        val link = FakeLink(idle)
        val vm = viewModel(link)
        link.state.value = LinkState.Offline(0, OfflineCause.ConnectionLost, "", lan, 1, null)
        looked()
        vm.editDraft("later")
        vm.send()
        assertTrue(link.calls.isEmpty())
        assertEquals("later", vm.composer().draft)
        assertEquals(ComposerMode.Offline, vm.composer().mode)
    }

    // ── failure, Retry, no auto-resend ────────────────────────────────────────────────────────────

    @Test
    fun aSendWhoseAnswerWasLostIsRetriedWithTheSameSubmissionId() {
        val link = FakeLink(idle).apply { answer = { throw RpcException(null, "relay closed", linkClosed = true) } }
        val vm = viewModel(link)
        vm.editDraft("hello")
        vm.send()
        val failed = vm.composer().outbox.single()
        assertEquals(SendFailure.NoAnswer, (failed.status as OutgoingStatus.Failed).failure)
        assertTrue(failed.retryable)
        vm.retrySend("id-1") // the Retry that just appeared under the finger
        assertEquals(1, link.sent("prompt.submit").size)
        looked()
        link.answer = { receipt("a1") }
        vm.retrySend("id-1")
        val submits = link.sent("prompt.submit")
        assertEquals(2, submits.size)
        // The same idempotency key: Hermes replays the first admission if it got it (hermes_state_runtime.py:154-161).
        assertEquals(submits[0].params, submits[1].params)
        assertEquals(OutgoingStatus.Sent("a1", 1), vm.composer().outbox.single().status)
    }

    @Test
    fun aReconnectNeverSendsAgainAndHermesCopyReplacesTheFailedEcho() {
        val link = FakeLink(idle).apply { answer = { throw RpcException(null, "relay closed", linkClosed = true) } }
        val vm = viewModel(link)
        vm.editDraft("hello")
        vm.send()
        link.state.value = LinkState.Offline(0, OfflineCause.ConnectionLost, "", lan, 1, null)
        // Hermes had admitted it before the link dropped; the next socket's snapshot shows it running.
        link.resumeResult = HermesJson.parseToJsonElement(
            """{"session_id":"s1","messages":[],"running":true,"replay_epoch":"e","last_sequence":9,"prompts":[],"open_requests":[],"info":{},
               "execution_generation":6,"pending":[{"admission_id":"a1","input_id":"id-1","status":"started","text":"hello"}]}""",
        )
        link.state.value = LinkState.Online(lan, 2)
        assertEquals(1, link.sent("prompt.submit").size)
        assertEquals(listOf("hello"), vm.userTexts())
        assertTrue(vm.composer().outbox.isEmpty())
    }

    @Test
    fun aFailedEchoStaysFailedAcrossAReconnectUntilRetried() {
        val link = FakeLink(idle).apply { answer = { throw RpcException(null, "hello: socket not open", linkClosed = true, written = false) } }
        val vm = viewModel(link)
        vm.editDraft("hello")
        vm.send()
        link.state.value = LinkState.Offline(0, OfflineCause.ConnectionLost, "", lan, 1, null)
        link.state.value = LinkState.Online(lan, 2)
        now += 10_000
        assertEquals(1, link.sent("prompt.submit").size)
        assertEquals(SendFailure.NotSent, (vm.composer().outbox.single().status as OutgoingStatus.Failed).failure)
    }

    @Test
    fun dismissingAFailedEchoPutsTheWordsBack() {
        val link = FakeLink(idle).apply { answer = { throw RpcException(RpcError(4001, "queue_full"), "prompt.submit failed: 4001") } }
        val vm = viewModel(link)
        vm.editDraft("hello")
        vm.send()
        assertEquals(SendFailure.Refused("queue_full"), (vm.composer().outbox.single().status as OutgoingStatus.Failed).failure)
        looked()
        vm.dismissFailed("id-1")
        assertTrue(vm.composer().outbox.isEmpty())
        assertEquals("hello", vm.composer().draft)
        assertEquals("hello", drafts.get(session))
    }

    // ── busy: Steer, Queue, Stop ──────────────────────────────────────────────────────────────────

    @Test
    fun busySteerQueueAndStopSendTheirFrames() {
        val link = FakeLink(busy).apply {
            answer = { call ->
                when (call.method) {
                    "session.steer" -> HermesJson.parseToJsonElement("""{"status":"queued","text":"also this","execution_generation":6,"authority_epoch":1}""")
                    "prompt.submit" -> receipt("a2")
                    else -> HermesJson.parseToJsonElement("""{"execution_state":"running"}""")
                }
            }
        }
        val vm = viewModel(link)
        assertEquals(ComposerMode.Busy, vm.composer().mode)
        vm.send() // Send is not the busy control
        vm.editDraft("also this")
        vm.steer()
        assertEquals(json("""{"session_id":"s1","execution_generation":6,"text":"also this"}"""), link.sent("session.steer").single().params)
        // Accepted: it stays as the user's row after what was on screen.
        val steered = vm.composer().outbox.single()
        assertEquals(OutgoingStatus.Sent(null, 1, anchor = "h-1"), steered.status)
        assertEquals(listOf("h-1"), ComposerLogic.anchored(vm.composer().outbox).keys.toList())

        vm.editDraft("after that")
        vm.queue()
        assertEquals(json("""{"session_id":"s1","text":"after that","submission_id":"id-2"}"""), link.sent("prompt.submit").single().params)
        assertEquals(listOf(QueuedRow("a2", "after that", "a2")), ComposerLogic.queuedRows(vm.uiState.value.feed, vm.composer().outbox))

        // The queued row just appeared above the field: the composer re-arms before Stop counts.
        vm.stop()
        assertTrue(link.sent("session.interrupt").isEmpty())
        looked()
        vm.stop()
        assertEquals(json("""{"session_id":"s1","execution_generation":6}"""), link.sent("session.interrupt").single().params)
        assertFalse(vm.composer().stopping)
        assertTrue(link.sent("config.set").isEmpty())
    }

    @Test
    fun hermesQueueRowReplacesOurQueuedEchoAndCanBeCancelled() {
        val link = FakeLink(busy).apply { answer = { call -> if (call.method == "prompt.submit") receipt("a2") else receipt("a2", "terminal") } }
        val vm = viewModel(link)
        vm.editDraft("after that")
        vm.queue()
        link.frames.tryEmit(
            event("session.info", """{"pending":[{"admission_id":"a0","input_id":"i0","status":"started","text":"Count"},
                {"admission_id":"a2","input_id":"id-1","status":"queued","text":"after that"}],"running":true}""", 4),
        )
        assertTrue(vm.composer().outbox.isEmpty())
        assertEquals(listOf(QueuedRow("a2", "after that", "a2")), ComposerLogic.queuedRows(vm.uiState.value.feed, vm.composer().outbox))
        looked()
        vm.cancelQueued("a2")
        assertEquals(json("""{"session_id":"s1","admission_id":"a2"}"""), link.sent("prompt.cancel").single().params)
    }

    @Test
    fun aStopAfterTheTurnEndedIsQuietAndAnotherRefusalIsShown() {
        val link = FakeLink(busy).apply { answer = { throw RpcException(RpcError(4001, "stale_generation"), "session.interrupt failed: 4001") } }
        val vm = viewModel(link)
        vm.stop()
        assertNull(vm.composer().message)
        link.answer = { throw RpcException(RpcError(4001, "permission_denied"), "session.interrupt failed: 4001") }
        vm.stop()
        assertEquals(ComposerMessage.StopFailed("permission_denied"), vm.composer().message)
    }

    @Test
    fun aSteerTheEndedTurnNeverSawIsRetriedAsANewMessage() {
        val link = FakeLink(busy).apply {
            answer = { HermesJson.parseToJsonElement("""{"status":"rejected","text":"also this","execution_generation":6,"authority_epoch":1,"delivered":false}""") }
        }
        val vm = viewModel(link)
        vm.editDraft("also this")
        vm.steer()
        val failed = vm.composer().outbox.single()
        assertEquals(SendFailure.TurnEnded, (failed.status as OutgoingStatus.Failed).failure)
        link.frames.tryEmit(event("message.complete", """{"text":"done","status":"complete","outcome":"completed"}""", 4, generation = 6))
        looked()
        link.answer = { receipt("a3") }
        vm.retrySend(failed.id)
        assertEquals(json("""{"session_id":"s1","text":"also this","submission_id":"id-2"}"""), link.sent("prompt.submit").single().params)
    }

    @Test
    fun aSteerThatMayHaveArrivedIsNeverSentAgain() {
        val link = FakeLink(busy).apply { answer = { throw RpcException(null, "relay closed", linkClosed = true) } }
        val vm = viewModel(link)
        vm.editDraft("also this")
        vm.steer()
        val failed = vm.composer().outbox.single()
        assertFalse(failed.retryable)
        looked()
        vm.retrySend(failed.id)
        assertEquals(1, link.calls.size)
    }

    // ── tap-through guard ─────────────────────────────────────────────────────────────────────────

    @Test
    fun theIdleToBusySwapIgnoresTapsForItsFirst600Ms() {
        val link = FakeLink(idle).apply { answer = { receipt("a9") } }
        val vm = viewModel(link)
        vm.editDraft("more")
        now = 5_000
        // Another screen started a turn: Send turns into Stop / Queue / Steer under the finger.
        link.frames.tryEmit(event("agent.step", """{"iteration":1}""", 4, generation = 6))
        assertEquals(ComposerMode.Busy, vm.composer().mode)
        assertFalse(vm.composer().armed)
        vm.send()
        vm.steer()
        vm.queue()
        now = 5_000 + ComposerLogic.ARM_MS - 1
        vm.stop()
        vm.steer()
        assertTrue(link.calls.isEmpty())
        now = 5_000 + ComposerLogic.ARM_MS
        vm.stop()
        assertEquals(listOf("session.interrupt"), link.calls.map { it.method })
    }

    @Test
    fun aClosingPromptSheetArmsTheComposerAgain() {
        val link = FakeLink(busy)
        val vm = viewModel(link)
        link.frames.tryEmit(
            event("approval.request", """{"kind":"approval","prompt_id":"p1","execution_generation":6,"command":"ls","description":"","choices":["once","deny"]}""", 4),
        )
        assertEquals(ComposerMode.Covered, vm.composer().mode)
        now = 8_000
        link.frames.tryEmit(event("approval.settled", """{"prompt_id":"p1","execution_generation":6}""", 5))
        vm.closeWithdrawn() // "Answered elsewhere" closes; Stop is under the finger
        vm.stop()
        assertTrue(link.calls.isEmpty())
        now = 8_000 + ComposerLogic.ARM_MS
        vm.stop()
        assertEquals(1, link.sent("session.interrupt").size)
    }

    @Test
    fun aWaitingPromptTurnsSteerOffButQueueStillWorks() {
        val link = FakeLink(
            """{"session_id":"s1","messages":[],"running":true,"replay_epoch":"e","last_sequence":3,"open_requests":[],"info":{},"execution_generation":6,"pending":[],
               "prompts":[{"kind":"approval","prompt_id":"p1","execution_generation":6,"command":"ls","description":"","choices":["once","deny"]}]}""",
        ).apply { answer = { receipt("a5") } }
        val vm = viewModel(link)
        vm.hideSheet() // down to the chip; the prompt still waits
        looked()
        assertEquals(ComposerMode.Busy, vm.composer().mode)
        assertTrue(vm.composer().blocked)
        vm.editDraft("next")
        vm.steer()
        assertTrue(link.calls.isEmpty())
        vm.queue()
        assertEquals(listOf("prompt.submit"), link.calls.map { it.method })
    }
}
