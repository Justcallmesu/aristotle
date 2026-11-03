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
import io.github.justcallmesu.aristotle.data.chat.ReasoningLevels
import io.github.justcallmesu.aristotle.data.chat.SlashBlock
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

/** The model and reasoning switch and the slash commands (T15) in [ChatViewModel] over a fake link. */
class ChatViewModelControlsTest {
    private val session = "s1"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private class Call(val method: String, val params: JsonObject)

    private class FakeLink(snapshot: String) : RelayLink {
        val resumeResult: JsonElement = HermesJson.parseToJsonElement(snapshot)
        override val state = MutableStateFlow<LinkState>(LinkState.Online(ServerEndpoint("192.168.1.61", 8760, Network.Lan), 1))
        val frames = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
        override val events: SharedFlow<InboundFrame> = frames
        val calls = CopyOnWriteArrayList<Call>()
        val answers = HashMap<String, suspend (Call) -> JsonElement>()

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            if (method == "session.resume") return resumeResult
            val call = Call(method, params)
            calls += call
            return answers[method]?.invoke(call) ?: HermesJson.parseToJsonElement("{}")
        }

        override fun respond(requestId: String, result: JsonObject): Boolean = true
        override fun retryNow() = Unit

        fun sent(method: String) = calls.filter { it.method == method }
    }

    private fun json(text: String) = HermesJson.parseToJsonElement(text) as JsonObject

    private fun snapshot(running: Boolean = false, revision: String = "\"revision\":7,") =
        """{"session_id":"s1","messages":[],"running":$running,"replay_epoch":"e","last_sequence":3,"prompts":[],"open_requests":[],
           "pending":[],$revision"execution_generation":5,
           "info":{"model":"gpt-5.1","provider":"openai-codex","reasoning_effort":"medium","profile_name":"pm"}}"""

    private val options = """{"model":"gpt-5.1","provider":"openai-codex","providers":[
        {"slug":"openai-codex","name":"OpenAI Codex","models":["gpt-5.1","gpt-5.1-mini"],
         "capabilities":{"gpt-5.1":{"fast":false,"reasoning":true},"gpt-5.1-mini":{"fast":false,"reasoning":false}}}]}"""

    private val catalog = """{"pairs":[["/status","Show session status"],["/new","Start a new session"],["/gif-search","Find a GIF"]],
        "canon":{},"commands":{"/status":{"desktop":null},"/new":{"desktop":null}},"skills":{"/gif-search":{}}}"""

    private var now = 0L
    private var ids = 0

    private fun viewModel(link: FakeLink) =
        ChatViewModel(session, link, ResumedSessions(), ChatDrafts(MemoryStore()), background = Dispatchers.Unconfined, scope = scope, clock = { now }, newId = { "id-${++ids}" })
            .also { now = 1_000 }

    private fun FakeLink.withOptions() = apply { answers["model.options"] = { HermesJson.parseToJsonElement(options) } }

    private fun refusal(reason: String, data: String) = RpcException(RpcError(4001, reason, json(data)), "session.mutate failed: 4001")

    private fun ChatViewModel.group() = uiState.value.model.catalog!!.groups.single()

    private fun Call.payload() = params["payload"] as JsonObject

    // ── model ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun aPickSendsTheFencedMutationWithTheSessionRevisionAndGeneration() {
        val link = FakeLink(snapshot()).withOptions()
        val vm = viewModel(link)
        vm.openModels()
        assertEquals(json("""{"session_id":"s1","explicit_only":true}"""), link.sent("model.options").single().params)
        assertTrue(vm.uiState.value.model.open)
        vm.pickModel(vm.group(), "gpt-5.1-mini")
        val mutate = link.sent("session.mutate").single().params
        assertEquals(
            json(
                """{"session_id":"s1","request_id":"id-1","expected_revision":7,"expected_generation":5,"operation":"model",
                   "payload":{"model":"gpt-5.1-mini","provider":"openai-codex"}}""",
            ),
            mutate,
        )
        val sheet = vm.uiState.value.model
        assertFalse(sheet.open)
        assertFalse(sheet.sending)
        assertNull(sheet.message)
    }

    @Test
    fun aConflictSaysChangedElsewhereRefreshesAndIsNeverRetried() {
        val link = FakeLink(snapshot()).withOptions()
        link.answers["session.mutate"] = { throw refusal("revision_conflict", """{"reason":"revision_conflict","revision":9,"execution_generation":6}""") }
        val vm = viewModel(link)
        vm.openModels()
        vm.pickModel(vm.group(), "gpt-5.1-mini")
        assertEquals(1, link.sent("session.mutate").size)
        assertEquals(ModelMessage.Changed, vm.uiState.value.model.message)
        // The session state is read again: the list (current model) once more.
        assertEquals(2, link.sent("model.options").size)
        // The user tries again: the fences the refusal named are presented, once, on this new tap.
        link.answers["session.mutate"] = { HermesJson.parseToJsonElement("""{"session_id":"s1","operation":"model","revision":10}""") }
        vm.modelMessageShown()
        vm.pickModel(vm.group(), "gpt-5.1-mini")
        val retried = link.sent("session.mutate")
        assertEquals(2, retried.size)
        assertEquals(json("""{"session_id":"s1","request_id":"id-2","expected_revision":9,"expected_generation":6,"operation":"model","payload":{"model":"gpt-5.1-mini","provider":"openai-codex"}}"""), retried[1].params)
        assertNull(vm.uiState.value.model.message)
    }

    @Test
    fun anotherViewersEditMovesTheRevisionForward() {
        val link = FakeLink(snapshot()).withOptions()
        val vm = viewModel(link)
        // A rename on the Desktop (gateway/session_mutations.py:330-334); then an older replayed receipt.
        link.frames.tryEmit(InboundFrame.Event("session.updated", session, json("""{"session_id":"s1","operation":"rename","revision":8}"""), seq = 4, replayEpoch = "e"))
        link.frames.tryEmit(InboundFrame.Event("session.updated", session, json("""{"session_id":"s1","operation":"rename","revision":6}"""), seq = 5, replayEpoch = "e"))
        assertEquals(8L, vm.uiState.value.feed.chat.revision)
        vm.pickReasoning("high")
        val mutate = link.sent("session.mutate").single()
        assertEquals(8L, (mutate.params["expected_revision"] as JsonPrimitive).content.toLong())
        assertEquals("reasoning", (mutate.params["operation"] as JsonPrimitive).content)
        assertEquals(json("""{"effort":"high"}"""), mutate.payload())
    }

    @Test
    fun aPickDuringATurnAppliesFromTheNextMessage() {
        val link = FakeLink(snapshot(running = true)).withOptions()
        link.answers["session.mutate"] = {
            HermesJson.parseToJsonElement("""{"session_id":"s1","operation":"model","status":"deferred","model":"gpt-5.1-mini","revision":7,"execution_generation":5}""")
        }
        val vm = viewModel(link)
        vm.openModels()
        vm.pickModel(vm.group(), "gpt-5.1-mini")
        assertEquals(ModelMessage.Deferred("gpt-5.1-mini"), vm.uiState.value.model.message)
    }

    @Test
    fun aGuardedTargetAsksAndOnlyTheUsersConfirmationSendsTheToken() {
        val link = FakeLink(snapshot()).withOptions()
        link.answers["session.mutate"] = {
            HermesJson.parseToJsonElement(
                """{"session_id":"s1","operation":"model","status":"confirmation_required","confirm":"tok","confirm_message":"Costs more","target_model":"gpt-5.1-mini"}""",
            )
        }
        val vm = viewModel(link)
        vm.openModels()
        vm.pickModel(vm.group(), "gpt-5.1-mini")
        assertEquals(ModelConfirm("gpt-5.1-mini", "openai-codex", "tok", "Costs more"), vm.uiState.value.model.confirm)
        assertEquals(1, link.sent("session.mutate").size)
        vm.cancelModelConfirm()
        assertNull(vm.uiState.value.model.confirm)
        assertEquals(1, link.sent("session.mutate").size)

        vm.pickModel(vm.group(), "gpt-5.1-mini")
        link.answers["session.mutate"] = { HermesJson.parseToJsonElement("""{"session_id":"s1","operation":"model","revision":8}""") }
        now += 700
        vm.confirmModel()
        val sent = link.sent("session.mutate")
        assertEquals(3, sent.size)
        assertEquals(json("""{"model":"gpt-5.1-mini","provider":"openai-codex","confirm":"tok"}"""), sent[2].payload())
        assertNull(vm.uiState.value.model.confirm)
    }

    @Test
    fun withoutAKnownRevisionNothingIsSent() {
        val link = FakeLink(snapshot(revision = "")).withOptions()
        val vm = viewModel(link)
        vm.pickReasoning("low")
        assertTrue(link.sent("session.mutate").isEmpty())
        assertEquals(ModelMessage.NotReady, vm.uiState.value.model.message)
    }

    @Test
    fun reasoningIsOfferedForTheCurrentModelOnlyWhenItHasIt() {
        val link = FakeLink(snapshot()).withOptions()
        val vm = viewModel(link)
        vm.openModels()
        val ui = vm.uiState.value
        val catalog = ui.model.catalog!!
        val chat = ui.feed.chat
        assertEquals(listOf("none") + ReasoningLevels.LEVELS, ReasoningLevels.choices(catalog.capabilities(chat.provider, chat.model)))
        assertTrue(ReasoningLevels.choices(catalog.capabilities(chat.provider, "gpt-5.1-mini")).isEmpty())
        assertEquals("medium", ReasoningLevels.current(chat.reasoningEffort))
    }

    // ── slash commands ────────────────────────────────────────────────────────────────────────────

    private fun FakeLink.withCatalog() = apply { answers["commands.catalog"] = { HermesJson.parseToJsonElement(catalog) } }

    @Test
    fun typingSlashLoadsHermesListOnceAndPickingInsertsTheCommand() {
        val link = FakeLink(snapshot()).withCatalog()
        val vm = viewModel(link)
        vm.editDraft("/")
        vm.editDraft("/st")
        assertEquals(json("""{"session_id":"s1"}"""), link.sent("commands.catalog").single().params)
        val status = vm.uiState.value.slash.catalog!!.resolve("/status")!!
        val epoch = vm.uiState.value.composer.draftEpoch
        vm.pickCommand(status)
        assertEquals("/status ", vm.uiState.value.composer.draft)
        assertEquals(epoch + 1, vm.uiState.value.composer.draftEpoch)
        // A blocked row inserts nothing.
        vm.pickCommand(vm.uiState.value.slash.catalog!!.resolve("/new")!!)
        assertEquals("/status ", vm.uiState.value.composer.draft)
    }

    @Test
    fun sendRunsTheCommandWithSlashExecAndShowsItsOutput() {
        val link = FakeLink(snapshot()).withCatalog()
        link.answers["slash.exec"] = { HermesJson.parseToJsonElement("""{"type":"exec","output":"All good"}""") }
        val vm = viewModel(link)
        vm.editDraft("/status")
        vm.send()
        assertEquals(json("""{"session_id":"s1","command":"status"}"""), link.sent("slash.exec").single().params)
        assertTrue(link.sent("prompt.submit").isEmpty())
        assertEquals("", vm.uiState.value.composer.draft)
        val run = vm.uiState.value.slash.runs.single()
        assertEquals("/status" to CommandStatus.Output("All good"), run.command to run.status)
        vm.dismissCommand(run.id)
        assertTrue(vm.uiState.value.slash.runs.isEmpty())
    }

    @Test
    fun aCommandThePhoneCannotRunIsNotSentAndSaysWhy() {
        val link = FakeLink(snapshot()).withCatalog()
        val vm = viewModel(link)
        vm.editDraft("/new")
        vm.send()
        assertTrue(link.sent("slash.exec").isEmpty())
        assertEquals(CommandStatus.Blocked(SlashBlock.PhoneControl), vm.uiState.value.slash.runs.single().status)
    }

    @Test
    fun aSkillCommandSubmitsHermesMessageWithItsOwnSubmissionId() {
        val link = FakeLink(snapshot()).withCatalog()
        link.answers["slash.exec"] = { HermesJson.parseToJsonElement("""{"type":"skill","name":"gif-search","message":"[skill body] cats","display":"/gif-search cats"}""") }
        val vm = viewModel(link)
        vm.editDraft("/gif-search cats")
        vm.send()
        assertEquals(json("""{"session_id":"s1","text":"[skill body] cats","submission_id":"id-2"}"""), link.sent("prompt.submit").single().params)
        // The bubble shows what the user typed, never the skill body; no output row is left.
        assertEquals("/gif-search cats", vm.uiState.value.composer.outbox.single().text)
        assertTrue(vm.uiState.value.slash.runs.isEmpty())
    }

    @Test
    fun aRefusedCommandShowsHermesReasonAndHint() {
        val link = FakeLink(snapshot()).withCatalog()
        link.answers["slash.exec"] = { throw RpcException(RpcError(4001, "unsupported_command", json("""{"reason":"unsupported_command","detail":"Run it in a terminal."}""")), "x") }
        val vm = viewModel(link)
        vm.editDraft("/snapshot")
        vm.send()
        assertEquals(CommandStatus.Failed("unsupported_command — Run it in a terminal."), vm.uiState.value.slash.runs.single().status)
    }

    @Test
    fun whileBusyACommandRunsAndIsNeverQueuedOrSteered() {
        val link = FakeLink(snapshot(running = true)).withCatalog()
        link.answers["slash.exec"] = { HermesJson.parseToJsonElement("""{"type":"exec","output":"ok"}""") }
        val vm = viewModel(link)
        vm.editDraft("/status")
        vm.queue()
        vm.steer()
        assertTrue(link.sent("prompt.submit").isEmpty())
        assertTrue(link.sent("session.steer").isEmpty())
        vm.send()
        assertEquals(1, link.sent("slash.exec").size)
    }

    @Test
    fun aCommandTappedBeforeTheComposerSettledIsDropped() {
        val link = FakeLink(snapshot()).withCatalog()
        val vm = viewModel(link)
        now = 100 // the composer appeared at 0: within the arming time
        vm.editDraft("/status")
        vm.send()
        assertTrue(link.sent("slash.exec").isEmpty())
        assertEquals("/status", vm.uiState.value.composer.draft)
    }

    // ── T15b ──────────────────────────────────────────────────────────────────────────────────────

    private val desktopCatalog = """{"pairs":[["/status","Show status"],["/footer","Footer"],["/busy","Busy input"]],
        "canon":{"/f":"/footer"},"commands":{"/status":{"desktop":null},"/footer":{"desktop":"no surface"},"/f":{"desktop":"no surface"},"/busy":{"desktop":"no surface"}},"skills":{}}"""

    @Test
    fun aTypedCommandTheDesktopRefusesIsNotSentAndKeepsTheDraft() {
        val link = FakeLink(snapshot()).apply { answers["commands.catalog"] = { HermesJson.parseToJsonElement(desktopCatalog) } }
        val vm = viewModel(link)
        vm.editDraft("/")
        for (typed in listOf("/footer", "/f on", "/FOOTER")) {
            vm.editDraft(typed)
            vm.send()
            assertEquals(typed, vm.uiState.value.composer.draft)
            assertEquals(CommandStatus.Blocked(SlashBlock.DesktopApp), vm.uiState.value.slash.runs.last().status)
        }
        assertTrue(link.sent("slash.exec").isEmpty())
    }

    @Test
    fun busyIsNeverSentEvenWhenTheCatalogDoesNotKnowIt() {
        val link = FakeLink(snapshot()).withCatalog()
        val vm = viewModel(link)
        vm.editDraft("/busy queue")
        vm.send()
        assertTrue(link.sent("slash.exec").isEmpty())
        assertEquals(CommandStatus.Blocked(SlashBlock.DesktopApp), vm.uiState.value.slash.runs.single().status)
        assertEquals("/busy queue", vm.uiState.value.composer.draft)
    }

    @Test
    fun aCommandSentBeforeTheCatalogLoadedWaitsForItAndIsThenChecked() {
        val gate = CompletableDeferred<Unit>()
        val link = FakeLink(snapshot()).apply {
            answers["commands.catalog"] = {
                gate.await()
                HermesJson.parseToJsonElement(desktopCatalog)
            }
            answers["slash.exec"] = { HermesJson.parseToJsonElement("""{"type":"exec","output":"ok"}""") }
        }
        val vm = viewModel(link)
        vm.editDraft("/footer")
        vm.send()
        vm.send() // a second tap while it waits does nothing
        assertTrue(link.sent("slash.exec").isEmpty())
        assertEquals(CommandStatus.Running, vm.uiState.value.slash.runs.single().status)
        gate.complete(Unit)
        assertTrue(link.sent("slash.exec").isEmpty())
        assertEquals(CommandStatus.Blocked(SlashBlock.DesktopApp), vm.uiState.value.slash.runs.single().status)
        assertEquals("/footer", vm.uiState.value.composer.draft)
        // A command the catalog allows goes out after the wait.
        vm.editDraft("/status")
        vm.send()
        assertEquals(1, link.sent("slash.exec").size)
    }

    @Test
    fun aCatalogThatCannotBeReadSendsNothingAndKeepsTheDraft() {
        val link = FakeLink(snapshot()).apply { answers["commands.catalog"] = { throw RpcException(RpcError(5000, "boom", null), "x") } }
        val vm = viewModel(link)
        vm.editDraft("/status")
        vm.send()
        assertTrue(link.sent("slash.exec").isEmpty())
        assertEquals("/status", vm.uiState.value.composer.draft)
        assertEquals(CommandStatus.Failed("boom", sent = false), vm.uiState.value.slash.runs.single().status)
    }

    @Test
    fun aSkillSubmissionIdExistsBeforeTheFirstCallAndIsReusedForTheRestoredText() {
        val link = FakeLink(snapshot()).withCatalog()
        var idsAtCall = -1
        link.answers["slash.exec"] = {
            idsAtCall = ids
            HermesJson.parseToJsonElement("""{"type":"skill","name":"gif-search","message":"[skill body] cats","display":"/gif-search cats"}""")
        }
        link.answers["prompt.submit"] = { throw RpcException(null, "relay closed", linkClosed = true) }
        val vm = viewModel(link)
        vm.editDraft("/gif-search cats")
        vm.send()
        // run row id-1 and the submission id-2 were made before slash.exec answered.
        assertEquals(2, idsAtCall)
        val echo = vm.uiState.value.composer.outbox.single()
        assertEquals("id-2", echo.id)
        now += 700
        vm.dismissFailed(echo.id)
        assertEquals("/gif-search cats", vm.uiState.value.composer.draft)
        vm.send()
        assertEquals(listOf("id-2", "id-2"), link.sent("prompt.submit").map { (it.params["submission_id"] as JsonPrimitive).content })
    }

    @Test
    fun aSkillRetryAfterAFailedSlashExecKeepsItsId() {
        val link = FakeLink(snapshot()).withCatalog()
        var fail = true
        link.answers["slash.exec"] = {
            if (fail) throw RpcException(null, "no answer", linkClosed = true, written = true)
            HermesJson.parseToJsonElement("""{"type":"skill","name":"gif-search","message":"[skill body] cats","display":"/gif-search cats"}""")
        }
        val vm = viewModel(link)
        vm.editDraft("/gif-search cats")
        vm.send()
        fail = false
        vm.editDraft("/gif-search cats")
        vm.send()
        assertEquals("id-2", (link.sent("prompt.submit").single().params["submission_id"] as JsonPrimitive).content)
    }

    @Test
    fun aConfirmationWithoutATokenIsNotAppliedAndSaysSo() {
        val link = FakeLink(snapshot()).withOptions()
        link.answers["session.mutate"] = { HermesJson.parseToJsonElement("""{"session_id":"s1","operation":"model","status":"confirmation_required","confirm_message":"Costs"}""") }
        val vm = viewModel(link)
        vm.openModels()
        vm.pickModel(vm.group(), "gpt-5.1-mini")
        assertNull(vm.uiState.value.model.confirm)
        assertEquals(ModelMessage.NoToken, vm.uiState.value.model.message)
    }

    private fun askedToConfirm(): Pair<ChatViewModel, FakeLink> {
        val link = FakeLink(snapshot()).withOptions()
        link.answers["session.mutate"] = {
            HermesJson.parseToJsonElement("""{"session_id":"s1","operation":"model","status":"confirmation_required","confirm":"tok","confirm_message":"Costs more"}""")
        }
        val vm = viewModel(link)
        vm.openModels()
        vm.pickModel(vm.group(), "gpt-5.1-mini")
        return vm to link
    }

    @Test
    fun theSwitchAnywayDialogClosesWhenTheModelChangesElsewhere() {
        val (vm, link) = askedToConfirm()
        assertTrue(vm.uiState.value.model.confirm != null)
        link.frames.tryEmit(InboundFrame.Event("session.info", session, json("""{"session_id":"s1","model":"claude-x","provider":"anthropic"}"""), seq = 4, replayEpoch = "e"))
        assertNull(vm.uiState.value.model.confirm)
        now += 700
        vm.confirmModel()
        assertEquals(1, link.sent("session.mutate").size)
    }

    @Test
    fun theSwitchAnywayDialogStaysWhenTheModelDidNotChange() {
        val (vm, link) = askedToConfirm()
        link.frames.tryEmit(InboundFrame.Event("session.info", session, json("""{"session_id":"s1","title":"renamed"}"""), seq = 4, replayEpoch = "e"))
        assertTrue(vm.uiState.value.model.confirm != null)
    }

    @Test
    fun switchAnywayIgnoresATapBeforeItsArmingTime() {
        val (vm, link) = askedToConfirm()
        now += 100
        vm.confirmModel()
        assertEquals(1, link.sent("session.mutate").size)
        assertTrue(vm.uiState.value.model.confirm != null)
        now += 600
        vm.confirmModel()
        assertEquals(2, link.sent("session.mutate").size)
    }
}
