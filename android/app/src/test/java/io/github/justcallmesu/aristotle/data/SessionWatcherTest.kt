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
    private fun run(link: FakeLink, script: suspend (StateFlow<List<SessionAlert>>) -> Unit): List<SessionAlert> = runBlocking {
        val seen = MutableStateFlow<List<SessionAlert>>(emptyList())
        val job = launch { SessionWatcher(link, fast).alerts().collect { seen.value = seen.value + it } }
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
            """{"session_id":"a","running":true,"info":{},"prompts":[{"kind":"approval","prompt_id":"p1","command":"ls"}]}""",
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
    fun thePromptsOpenAtResumeAreReportedWithTheListTitle() {
        val snapshot = json(
            """{"session_id":"s1","running":true,"info":{},"prompts":[{"kind":"approval","prompt_id":"p1","command":"x"},{"kind":"sudo"},"junk"]}""",
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
    fun nothingHappensWhileTheLinkIsDown() {
        val link = FakeLink { call -> if (call.method == "session.list") list("s1" to true) else emptySnapshot }
        val alerts = run(link) {
            link.event("message.complete", "s1", """{"text":"x","outcome":"completed"}""")
            delay(100)
        }
        assertTrue(alerts.isEmpty())
        assertTrue(link.calls.isEmpty())
    }
}
