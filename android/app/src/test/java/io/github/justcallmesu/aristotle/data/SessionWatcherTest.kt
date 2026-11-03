package io.github.justcallmesu.aristotle.data

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The background subscription rule and event -> alert mapping over a fake link (frames shaped as in docs/spikes/frames). */
class SessionWatcherTest {
    private val fast = FeedTimings(refreshDebounceMillis = 30)
    private val endpoint = ServerEndpoint("192.168.1.61", 8760, Network.Lan)

    private class Call(val method: String, val params: JsonObject)

    private class FakeLink(private val answer: (Call) -> JsonElement) : RelayLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Connecting)
        val frames = MutableSharedFlow<InboundFrame>(extraBufferCapacity = 64)
        override val events: SharedFlow<InboundFrame> = frames
        val calls = CopyOnWriteArrayList<Call>()

        override suspend fun call(method: String, params: JsonObject): JsonElement {
            val call = Call(method, params)
            calls += call
            return answer(call)
        }

        override fun retryNow() = Unit

        override fun respond(requestId: String, result: JsonObject): Boolean = false

        fun resumed(): List<String> = calls.filter { it.method == "session.resume" }.map { it.params["session_id"]!!.jsonPrimitive.content }

        fun event(type: String, sessionId: String, payload: String) =
            frames.tryEmit(InboundFrame.Event(type, sessionId, HermesJson.parseToJsonElement(payload) as JsonObject))
    }

    private fun json(text: String): JsonElement = HermesJson.parseToJsonElement(text)

    private fun list(vararg rows: Pair<String, Boolean>): JsonElement = json(
        rows.joinToString(",", """{"sessions":[""", "]}") { (id, running) -> """{"session_id":"$id","title":"Title $id","running":$running}""" },
    )

    private val emptySnapshot = json("""{"session_id":"x","running":true,"prompts":[],"info":{}}""")

    /** Collects alerts while [script] drives the link. */
    private fun run(
        link: FakeLink,
        resumed: ResumedSessions = ResumedSessions(),
        profile: StateFlow<String?> = MutableStateFlow(null),
        script: suspend (StateFlow<List<SessionAlert>>) -> Unit,
    ): List<SessionAlert> = runBlocking {
        val seen = MutableStateFlow<List<SessionAlert>>(emptyList())
        val job = launch { SessionWatcher(link, fast, resumed, profile).alerts().collect { seen.value = seen.value + it } }
        withTimeout(10_000) { script(seen) }
        job.cancel()
        seen.value
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        while (!condition()) delay(5)
    }

    @Test
    fun onlyRunningSessionsNotResumedYetAreSubscribed() {
        val sessions = listOf(
            SessionSummary(sessionId = "a", running = true),
            SessionSummary(sessionId = "b", running = false),
            SessionSummary(sessionId = "", id = "c", running = true),
            SessionSummary(sessionId = "a", running = true),
            SessionSummary(sessionId = "", id = "", running = true),
        )
        assertEquals(listOf("a", "c"), Subscriptions.toResume(sessions, emptySet()))
        assertEquals(listOf("c"), Subscriptions.toResume(sessions, setOf("a")))
    }

    @Test
    fun resumesRunningSessionsThenNewOnesAfterSessionsChanged() {
        var lists = 0
        val link = FakeLink { call ->
            when (call.method) {
                "session.list" -> if (lists++ == 0) list("a" to true, "b" to false) else list("a" to true, "b" to true)
                else -> emptySnapshot
            }
        }
        run(link) {
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { link.resumed() == listOf("a") }
            link.event(RpcFrames.SESSIONS_CHANGED, "", "{}")
            waitUntil { link.resumed() == listOf("a", "b") }
        }
        val resume = link.calls.first { it.method == "session.resume" }.params
        assertEquals(json("""{"session_id":"a","omit_messages":true}"""), resume)
    }

    @Test
    fun aReplayGapResumesTheWatchedSessionAgainOnTheSameSocket() {
        val reread = json(
            """{"session_id":"a","running":true,"info":{},"prompts":[{"kind":"approval","prompt_id":"p1","execution_generation":3,"command":"ls"}]}""",
        )
        var resumes = 0
        val link = FakeLink { call ->
            when (call.method) {
                "session.list" -> list("a" to true)
                else -> if (resumes++ == 0) emptySnapshot else reread
            }
        }
        val alerts = run(link) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { link.resumed() == listOf("a") }
            link.event("session.replay_gap", "other", "{}") // not watched: ignored
            link.event("session.replay_gap", "a", "{}")
            waitUntil { link.resumed() == listOf("a", "a") }
            seen.first { it.any { alert -> alert is SessionAlert.NeedsYou } }
            delay(100)
        }
        assertEquals(listOf("a", "a"), link.resumed())
        assertEquals(1, link.calls.count { it.method == "session.list" })
        assertTrue(alerts.contains(SessionAlert.NeedsYou("a", "Title a", "p1", PromptKind.Approval, null, command = true)))
    }

    @Test
    fun aNewRelayStartsTheSubscriptionsAgain() {
        val link = FakeLink { call -> if (call.method == "session.list") list("a" to true) else emptySnapshot }
        run(link) {
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { link.resumed() == listOf("a") }
            link.state.value = LinkState.Offline(0, OfflineCause.ConnectionLost, "close 1001", endpoint, 1, null)
            link.state.value = LinkState.Online(endpoint, 2)
            waitUntil { link.resumed() == listOf("a", "a") }
        }
    }

    @Test
    fun aRefusedResumeIsTriedAgainButATimedOutOneIsNot() {
        var refuse = true
        val link = FakeLink { call ->
            when {
                call.method == "session.list" -> list("refused" to true, "slow" to true)
                call.params["session_id"]!!.jsonPrimitive.content == "slow" -> throw RpcException(null, "session.resume: no answer in 15 s")
                refuse -> {
                    refuse = false
                    throw RpcException(RpcError(4004, "not found"), "session.resume failed: 4004")
                }
                else -> emptySnapshot
            }
        }
        run(link) {
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { link.resumed().size == 2 }
            link.event(RpcFrames.SESSIONS_CHANGED, "", "{}")
            waitUntil { link.resumed().size == 3 }
            delay(100)
        }
        assertEquals(listOf("refused", "slow", "refused"), link.resumed())
    }

    @Test
    fun turnsPromptsAndSettlesBecomeAlerts() {
        val link = FakeLink { call -> if (call.method == "session.list") list("s1" to true) else emptySnapshot }
        val alerts = run(link) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { link.resumed().isNotEmpty() }
            link.event("session.info", "s1", """{"title":"aristotle-spike"}""")
            link.event(
                "approval.request",
                "s1",
                """{"kind":"approval","prompt_id":"3c94","execution_generation":4,"command":"rm -rf /tmp/x","description":"…","choices":["once","deny"],"timeout_s":300}""",
            )
            link.event("approval.settled", "s1", """{"prompt_id":"3c94","execution_generation":4}""")
            link.event(
                "clarify.request",
                "s1",
                """{"kind":"clarify","prompt_id":"da89","execution_generation":5,"question":"Which do you pick?","choices":["red","blue"],"multi_select":false}""",
            )
            link.event("message.complete", "s1", """{"text":"one\ntwo","content":"one\ntwo","outcome":"completed","status":"complete"}""")
            link.event("message.complete", "s1", """{"text":"","outcome":"cancelled","status":"interrupted"}""")
            link.event("message.complete", "", """{"text":"broadcast","outcome":"completed"}""")
            link.event("message.delta", "s1", """{"text":"x"}""")
            seen.first { it.size >= 5 }
            delay(100)
        }
        assertEquals(
            listOf(
                SessionAlert.PromptsOpen("s1", emptySet()),
                SessionAlert.NeedsYou("s1", "aristotle-spike", "3c94", PromptKind.Approval, null, command = true),
                SessionAlert.Settled("s1", "3c94"),
                SessionAlert.NeedsYou("s1", "aristotle-spike", "da89", PromptKind.Clarify, "Which do you pick?"),
                SessionAlert.TurnFinished("s1", "aristotle-spike", "one\ntwo"),
            ),
            alerts,
        )
    }

    @Test
    fun aSubagentFinishingIsNoTurnEndAndNotifiesNothing() {
        // T17: the Desktop notifies only turnDone/turnError and prompts (`apps/desktop/src/store/native-notifications.ts:26-27`,
        // turnDone at `use-message-stream/index.ts:1163-1168`); a child's end is a `subagent.complete` on the parent stream.
        val link = FakeLink { call -> if (call.method == "session.list") list("s1" to true) else emptySnapshot }
        val alerts = run(link) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { link.resumed().isNotEmpty() }
            link.event("subagent.start", "s1", """{"goal":"Audit","task_count":1,"task_index":0,"subagent_id":"sa-1"}""")
            link.event("subagent.complete", "s1", """{"goal":"Audit","task_count":1,"task_index":0,"subagent_id":"sa-1","status":"completed","summary":"done"}""")
            link.event("subagent.complete", "s1", """{"goal":"Audit","task_count":1,"task_index":0,"subagent_id":"sa-2","status":"failed","summary":"boom"}""")
            link.event("message.complete", "s1", """{"text":"Both done.","outcome":"completed","status":"complete"}""")
            seen.first { it.size >= 2 }
            delay(100)
        }
        assertEquals(listOf(SessionAlert.PromptsOpen("s1", emptySet()), SessionAlert.TurnFinished("s1", "Title s1", "Both done.")), alerts)
    }

    @Test
    fun thePromptsOpenAtResumeAreReportedWithTheListTitle() {
        val snapshot = json(
            """{"session_id":"s1","running":true,"info":{},"prompts":[{"kind":"approval","prompt_id":"p1","execution_generation":3,"command":"x"},{"kind":"sudo"},"junk"]}""",
        )
        val link = FakeLink { call -> if (call.method == "session.list") list("s1" to true) else snapshot }
        val alerts = run(link) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            seen.first { it.size >= 2 }
            delay(50)
        }
        assertEquals(
            listOf(SessionAlert.NeedsYou("s1", "Title s1", "p1", PromptKind.Approval, null, command = true), SessionAlert.PromptsOpen("s1", setOf("p1"))),
            alerts,
        )
    }

    @Test
    fun serverRequestsNeedYouAndRequestCancelClearsThem() {
        // D32: sudo, secret, batch clarify and vault requests are notified; window bridges are not prompts.
        val snapshot = json(
            """{"session_id":"s1","running":true,"info":{},"prompts":[],
               "open_requests":[{"id":"srq-000000000001","method":"sudo","params":{"session_id":"s1","command":"sudo ls"}}]}""",
        )
        val link = FakeLink { call -> if (call.method == "session.list") list("s1" to true) else snapshot }
        fun request(id: String, method: String, params: String) =
            link.frames.tryEmit(RpcFrames.parse("""{"jsonrpc":"2.0","id":"$id","method":"$method","params":$params}"""))
        val alerts = run(link) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            seen.first { it.size >= 2 }
            request("srq-000000000002", "secret", """{"session_id":"s1","env_var":"KEY","prompt":"p"}""")
            request("srq-000000000003", "clarify", """{"session_id":"s1","questions":[{"qid":"q0","question":"Which?"}]}""")
            request("srq-000000000004", "vault.code", """{"session_id":"s1","site":"github.com"}""")
            request("srq-000000000005", "terminal.read", """{"session_id":"s1"}""")
            link.event("request.cancel", "s1", """{"id":"srq-000000000002","method":"secret","reason":"resolved"}""")
            seen.first { it.size >= 6 }
            delay(50)
        }
        assertEquals(
            listOf(
                SessionAlert.NeedsYou("s1", "Title s1", "srq-000000000001", PromptKind.Secret, null),
                SessionAlert.PromptsOpen("s1", setOf("srq-000000000001")),
                SessionAlert.NeedsYou("s1", "Title s1", "srq-000000000002", PromptKind.Secret, null),
                SessionAlert.NeedsYou("s1", "Title s1", "srq-000000000003", PromptKind.Clarify, "Which?"),
                SessionAlert.NeedsYou("s1", "Title s1", "srq-000000000004", PromptKind.OnPc, null),
                SessionAlert.Settled("s1", "srq-000000000002"),
            ),
            alerts,
        )
    }

    @Test
    fun nothingHappensWhileTheLinkIsDown() {
        val link = FakeLink { call -> if (call.method == "session.list") list("s1" to true) else emptySnapshot }
        val alerts = run(link) {
            link.event("message.complete", "s1", """{"text":"x","outcome":"completed"}""")
            delay(100)
        }
        assertTrue(alerts.isEmpty())
        assertTrue(link.calls.isEmpty())
    }

    @Test
    fun aSessionTheChatAlreadyResumedOnThisSocketIsNotResumedAgain() {
        val resumed = ResumedSessions()
        assertTrue(resumed.claim(1, "a")) // the chat screen, first on this socket
        val link = FakeLink { call -> if (call.method == "session.list") list("a" to true, "b" to true) else emptySnapshot }
        run(link, resumed) {
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { link.resumed() == listOf("b") }
            delay(100)
        }
        assertEquals(listOf("b"), link.resumed())
        assertEquals(setOf("a", "b"), resumed.snapshot(1))
    }

    @Test
    fun aReplayGapOfTheSessionOpenInTheChatIsLeftToTheChat() {
        val resumed = ResumedSessions()
        val link = FakeLink { call -> if (call.method == "session.list") list("a" to true) else emptySnapshot }
        run(link, resumed) {
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { link.resumed() == listOf("a") }
            resumed.openChat("a")
            link.event("session.replay_gap", "a", "{}")
            delay(150)
        }
        assertEquals(listOf("a"), link.resumed())
        assertEquals(setOf("a"), resumed.snapshot(1))
    }

    @Test
    fun aRefusedResumeIsReleasedForTheNextPass() {
        val resumed = ResumedSessions()
        val link = FakeLink { call ->
            if (call.method == "session.list") list("a" to true) else throw RpcException(RpcError(4001, "busy"), "session.resume failed: 4001")
        }
        run(link, resumed) {
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { link.resumed() == listOf("a") }
            delay(50)
        }
        assertEquals(emptySet<String>(), resumed.snapshot(1))
    }

    // T14b (T14 review MINOR-4): a profile the PC does not serve is handed back, so the selection can fall back.
    @Test
    fun anUnservedProfileIsReportedNotWatchedSilently() {
        val unserved = CopyOnWriteArrayList<String>()
        val refusal = RpcException(RpcError(4001, "profile_mismatch", json("""{"reason":"profile_mismatch"}""") as JsonObject), "session.list failed: 4001")
        val link = FakeLink { throw refusal }
        runBlocking {
            val job = launch { SessionWatcher(link, fast, ResumedSessions(), MutableStateFlow<String?>("gone"), onUnserved = { unserved += it }).alerts().collect { } }
            link.state.value = LinkState.Online(endpoint, 1)
            withTimeout(5_000) { waitUntil { unserved.isNotEmpty() } }
            job.cancel()
        }
        assertEquals(listOf("gone"), unserved.toList())
    }

    // T14 profile switch on the same socket: the old profile's watched sessions are detached (an open chat keeps
    // its own), only the new profile's sessions are listed, resumed and alert, and alerts carry their profile.
    @Test
    fun aProfileSwitchDetachesTheOldProfileAndWatchesTheNewOne() {
        val profile = MutableStateFlow<String?>(null)
        val resumed = ResumedSessions()
        val link = FakeLink { call ->
            val pm = call.params["profile"]?.jsonPrimitive?.content == "pm"
            when (call.method) {
                "session.list" -> if (pm) list("b" to true) else list("a" to true, "c" to true)
                "session.resume" -> {
                    val id = call.params["session_id"]!!.jsonPrimitive.content
                    json("""{"session_id":"$id","running":true,"prompts":[],"info":{},"subscription_id":"sub-$id"}""")
                }
                else -> json("""{"detached":true}""")
            }
        }
        val alerts = run(link, resumed, profile) { seen ->
            link.state.value = LinkState.Online(endpoint, 1)
            waitUntil { link.resumed() == listOf("a", "c") }
            resumed.openChat("c") // a chat of the launch profile is on screen
            link.event("message.complete", "a", """{"text":"done a","outcome":"completed"}""")
            seen.first { it.any { a -> a is SessionAlert.TurnFinished } }
            profile.value = "pm"
            waitUntil { link.resumed() == listOf("a", "c", "b") }
            link.event("message.complete", "a", """{"text":"old profile","outcome":"completed"}""")
            link.event("message.complete", "c", """{"text":"open chat, old profile","outcome":"completed"}""")
            link.event("message.complete", "b", """{"text":"done b","outcome":"completed"}""")
            seen.first { it.count { a -> a is SessionAlert.TurnFinished } == 2 }
            delay(100)
        }
        val detach = link.calls.single { it.method == "session.detach" }
        assertEquals(json("""{"session_id":"a","subscription_id":"sub-a"}"""), detach.params)
        val pmCalls = link.calls.filter { it.method == "session.list" || it.method == "session.resume" }.drop(3)
        assertTrue(pmCalls.all { it.params["profile"]?.jsonPrimitive?.content == "pm" })
        assertEquals(
            listOf(SessionAlert.TurnFinished("a", "Title a", "done a"), SessionAlert.TurnFinished("b", "Title b", "done b", profile = "pm")),
            alerts.filterIsInstance<SessionAlert.TurnFinished>(),
        )
        assertTrue(SessionAlert.Unwatched("a") in alerts)
        assertTrue(SessionAlert.Unwatched("c") !in alerts)
        assertEquals(setOf("c"), resumed.snapshot(1))
        assertEquals(setOf("b"), resumed.snapshot(1, "pm"))
    }
}
