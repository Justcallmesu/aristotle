package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Subagents in the chat (T17) on handmade frames. No capture holds a delegation (the spike never delegated), so the
 * payloads follow Hermes' own producer: `tui_gateway/tool_progress.py:460-488` (`_subagent_payload`: `goal`,
 * `task_count`, `task_index` always, the identity fields when known), published on the parent's stream by
 * `gateway/local_viewer_events.py:129-136`; the `delegate_task` call and result follow
 * `apps/desktop/src/components/assistant-ui/tool/delegate-model.ts:200-267`.
 */
class SubagentsTest {
    private val session = "s1"
    private fun json(text: String) = HermesJson.parseToJsonElement(text) as JsonObject

    private var seq = 0L
    private fun frame(type: String, payload: String, at: Long = 1_000) =
        ChatFrame(InboundFrame.Event(type, session, json(payload), seq = ++seq, replayEpoch = "e"), at)

    private fun ChatState.reduce(vararg frames: ChatFrame): ChatState = frames.fold(this) { state, frame -> ChatReducer.reduce(state, frame) }

    private val args = """{"tasks":[{"goal":"Audit the relay"},{"goal":"Read the spec"}]}"""

    private fun start(id: String, goal: String, index: Int, extra: String = "") =
        frame("subagent.start", """{"goal":"$goal","task_count":2,"task_index":$index,"subagent_id":"$id","parent_id":null,"depth":0,
            "model":"claude-sonnet-5-5","toolsets":["file"],"child_session_id":"child-$id","delegation_id":"d1","tool_count":0,"text":"$goal"$extra}""")

    private fun tool(id: String, goal: String, index: Int, name: String, preview: String) =
        frame("subagent.tool", """{"goal":"$goal","task_count":2,"task_index":$index,"subagent_id":"$id","model":"claude-sonnet-5-5","tool_count":1,
            "tool_name":"$name","text":"$preview","tool_preview":"$preview"}""")

    private fun complete(id: String, goal: String, index: Int, status: String, summary: String, duration: Double) =
        frame("subagent.complete", """{"goal":"$goal","task_count":2,"task_index":$index,"subagent_id":"$id","model":"claude-sonnet-5-5","status":"$status",
            "summary":"$summary","duration_seconds":$duration,"text":"$summary","input_tokens":10,"output_tokens":5,"api_calls":2,
            "output_tail":[{"tool":"read_file","preview":"relay.go","is_error":false}]}""")

    private val result = """{"results":[
        {"task_index":0,"status":"completed","summary":"Relay is fine.","duration_seconds":12.0,"model":"claude-sonnet-5-5"},
        {"task_index":1,"status":"completed","summary":"Spec read.","duration_seconds":8.0,"model":"claude-sonnet-5-5"}]}"""

    private fun delegateStart() = frame("tool.start", """{"tool_id":"call_d","name":"delegate_task","args":$args}""")
    /** A JSON string literal of [text]: tool results and stored arguments often arrive as strings. */
    private fun quoted(text: String) = JsonPrimitive(text).toString()

    private fun delegateComplete() = frame("tool.complete", """{"tool_id":"call_d","name":"delegate_task","result":${quoted(result)},"duration_s":13.0}""")

    private fun ChatState.card(): ChatItem.Tool = items.filterIsInstance<ChatItem.Tool>().single { it.name == "delegate_task" }

    private fun live(): ChatState = ChatState(session).reduce(
        frame("message.start", "{}"),
        delegateStart(),
        start("sa-1", "Audit the relay", 0),
        start("sa-2", "Read the spec", 1),
        tool("sa-1", "Audit the relay", 0, "read_file", "server/internal/relay/relay.go"),
        frame("subagent.thinking", """{"goal":"Read the spec","task_count":2,"task_index":1,"subagent_id":"sa-2","text":"The spec has a decisions table."}"""),
        frame("subagent.progress", """{"goal":"Audit the relay","task_count":2,"task_index":0,"subagent_id":"sa-1","text":"🔀 read_file, search_files"}"""),
    )

    // ── reducer: rows under the tool call ────────────────────────────────────────────────────────

    @Test
    fun aDelegationsChildrenBecomeRowsUnderItsToolCall() {
        val card = live().card()
        assertEquals(DelegateCall(listOf("Audit the relay", "Read the spec")), card.delegate)
        val (first, second) = card.subagents
        assertEquals(DelegateRow("call_d:0", "Audit the relay", DelegateStatus.Running, "claude-sonnet-5-5", null, "🔀 read_file, search_files", false, "sa-1", 1_000), first)
        assertEquals(DelegateStatus.Running, second.status)
        assertEquals("The spec has a decisions table.", second.lastLine)
        assertEquals("sa-2", second.subagentId)
    }

    @Test
    fun aToolLineReadsAsTheDesktopsLabel() {
        val state = ChatState(session).reduce(delegateStart(), start("sa-1", "Audit the relay", 0), tool("sa-1", "Audit the relay", 0, "read_file", "a.kt"))
        assertEquals("Read File(\"a.kt\")", state.subagents.byId("sa-1")!!.lastLine!!.text)
        assertEquals("read_file", state.subagents.byId("sa-1")!!.currentTool)
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val plain = ChatState(session).reduce(delegateStart(), start("sa-1", "Audit the relay", 0))
        seq = 0
        val extra = ChatState(session).reduce(delegateStart(), start("sa-1", "Audit the relay", 0, extra = ""","future_field":{"x":[1,2]},"reasoning_tokens":7"""))
        assertEquals(plain.items, extra.items)
        assertEquals(plain.subagents, extra.subagents)
    }

    @Test
    fun completionSettlesTheRowWithModelTimeAndSummary() {
        val state = live().reduce(
            complete("sa-1", "Audit the relay", 0, "completed", "Relay is fine.", 12.0),
            complete("sa-2", "Read the spec", 1, "completed", "Spec read.", 8.0),
        )
        val rows = state.card().subagents
        assertEquals(listOf(DelegateStatus.Completed, DelegateStatus.Completed), rows.map { it.status })
        assertEquals(listOf("Relay is fine.", "Spec read."), rows.map { it.lastLine })
        assertEquals(listOf(12.0, 8.0), rows.map { it.durationSeconds })
        assertNull(state.subagents.byId("sa-1")!!.currentTool)
    }

    @Test
    fun statusesMapAsOnTheDesktop() {
        assertEquals(SubagentStatus.Failed, Subagents.status("timeout", terminalEvent = true))
        assertEquals(SubagentStatus.Failed, Subagents.status("error", terminalEvent = false))
        assertEquals(SubagentStatus.Interrupted, Subagents.status("cancelled", terminalEvent = true))
        assertEquals(SubagentStatus.Queued, Subagents.status("queued", terminalEvent = false))
        // A completion is terminal: an unknown or still-active status fails closed instead of spinning forever.
        assertEquals(SubagentStatus.Failed, Subagents.status("running", terminalEvent = true))
        assertEquals(SubagentStatus.Failed, Subagents.status(null, terminalEvent = true))
        assertEquals(SubagentStatus.Running, Subagents.status("whatever", terminalEvent = false))
    }

    @Test
    fun aTimeoutWithoutSummarySaysWhy() {
        val state = live().reduce(
            frame("subagent.complete", """{"goal":"Audit the relay","task_count":2,"task_index":0,"subagent_id":"sa-1","status":"timeout","duration_seconds":612.3}"""),
        )
        val row = state.card().subagents.first()
        assertEquals(DelegateStatus.Failed, row.status)
        assertEquals("Timed out after 612.3s", row.lastLine)
        assertTrue(row.lastLineError)
    }

    @Test
    fun eventsOfAnUnknownOrEndedChildAreDropped() {
        val unknown = live().reduce(tool("sa-9", "Other", 0, "terminal", "ls"))
        assertEquals(null, unknown.subagents.byId("sa-9"))
        val ended = live().reduce(complete("sa-1", "Audit the relay", 0, "completed", "Relay is fine.", 12.0))
        val roster = ended.subagents
        val late = ended.reduce(tool("sa-1", "Audit the relay", 0, "terminal", "ls"), start("sa-1", "Audit the relay", 0))
        assertSame(roster, late.subagents)
    }

    @Test
    fun aNewTurnDropsFinishedChildrenAndKeepsRunningOnes() {
        val state = live().reduce(complete("sa-1", "Audit the relay", 0, "completed", "Relay is fine.", 12.0), frame("message.start", "{}"))
        assertEquals(listOf("sa-2"), state.subagents.items.map { it.id })
        // The retired child never comes back, not even from a late start.
        assertEquals(listOf("sa-2"), state.reduce(start("sa-1", "Audit the relay", 0)).subagents.items.map { it.id })
    }

    @Test
    fun aDelegationThatWentToTheBackgroundIsDispatchedNotSpinning() {
        val dispatched = """{"status":"dispatched","delegation_id":"d1","goals":["Audit the relay","Read the spec"]}"""
        val state = ChatState(session).reduce(
            frame("tool.start", """{"tool_id":"call_b","name":"delegate_task","args":{"goal":"x","background":true}}"""),
            frame("tool.complete", """{"tool_id":"call_b","name":"delegate_task","result":$dispatched}"""),
        )
        assertEquals(listOf(DelegateStatus.Dispatched), state.card().subagents.map { it.status })
        val fresh = HistoryMapper.items(historyRows("call_b", """{"tasks":[{"goal":"Audit the relay"},{"goal":"Read the spec"}]}""", dispatched))
        assertEquals(listOf(DelegateStatus.Dispatched, DelegateStatus.Dispatched), (fresh[1] as ChatItem.Tool).subagents.map { it.status })
    }

    @Test
    fun aControlCallOfDelegateTaskIsAPlainToolCard() {
        val state = ChatState(session).reduce(frame("tool.start", """{"tool_id":"call_l","name":"delegate_task","args":{"action":"list"}}"""))
        assertNull(state.card().delegate)
        assertEquals(emptyList<DelegateRow>(), state.card().subagents)
    }

    @Test
    fun aChildMatchesItsRowByGoalThenByTaskOrder() {
        val rows = Delegation.rowsOf(DelegateCall(listOf("A", "B")), "t")
        val child = Subagent("x", "  b ", SubagentStatus.Running, 5, taskIndex = 0)
        assertEquals(listOf(null, "x"), Delegation.merge(rows, listOf(child)).map { it.subagentId })
        // Unequal counts: task order is not trusted (another delegation's child must not be claimed).
        val other = Subagent("y", "Z", SubagentStatus.Running, 5, taskIndex = 0)
        assertEquals(listOf(null, null), Delegation.merge(rows, listOf(other)).map { it.subagentId })
        val two = listOf(other, Subagent("w", "Q", SubagentStatus.Running, 5, taskIndex = 1))
        assertEquals(listOf("y", "w"), Delegation.merge(rows, two).map { it.subagentId })
    }

    // ── history parity ──────────────────────────────────────────────────────────────────────────

    private fun historyRows(callId: String, arguments: String, content: String): JsonArray =
        HermesJson.parseToJsonElement(
            """[{"role":"user","content":"Audit it.","row_id":1},
                {"role":"assistant","content":"","row_id":2,"tool_calls":[{"id":"$callId","type":"function","function":{"name":"delegate_task","arguments":${quoted(arguments)}}}]},
                {"role":"tool","tool_call_id":"$callId","tool_name":"delegate_task","content":${quoted(content)},"row_id":3}]""",
        ) as JsonArray

    @Test
    fun aRebuiltTranscriptShowsTheSameRowsTheLiveCardEndedWith() {
        val ended = live().reduce(
            complete("sa-1", "Audit the relay", 0, "completed", "Relay is fine.", 12.0),
            complete("sa-2", "Read the spec", 1, "completed", "Spec read.", 8.0),
            delegateComplete(),
            frame("message.complete", """{"text":"Both done.","status":"complete","outcome":"completed"}"""),
        )
        val history = HistoryMapper.items(historyRows("call_d", args, result))[1] as ChatItem.Tool
        // While the children are still listed, what a row shows matches the stored result's.
        fun DelegateRow.shown() = listOf(goal, status, model, durationSeconds, lastLine, lastLineError)
        assertEquals(history.subagents.map { it.shown() }, ended.card().subagents.map { it.shown() })
        // The next turn drops the finished children: the live card is then exactly the rebuilt one.
        val next = ended.reduce(frame("message.start", "{}"))
        assertEquals(history.subagents, next.card().subagents)
        assertEquals(history.delegate, next.card().delegate)
    }

    @Test
    fun aSnapshotRebuildKeepsTheKnownChildrenOnTheRebuiltCard() {
        val running = live()
        val snapshot = json(
            """{"session_id":"s1","running":true,"replay_epoch":"e","last_sequence":99,"pending":[],"prompts":[],"info":{},
               "messages":${historyRows("call_d", args, "").let { rows -> JsonArray(rows.take(2)) }}}""",
        )
        val rebuilt = ChatReducer.fromSnapshot(session, snapshot, running)
        assertEquals(running.card().subagents, rebuilt.card().subagents)
    }

    // ── subagent.list ───────────────────────────────────────────────────────────────────────────

    @Test
    fun aListAnswerAddsChildrenTheChatMissedAndDropsReapedOnes() {
        val opened = ChatState(session).reduce(delegateStart())
        val children = listOf(
            json("""{"subagent_id":"sa-1","parent_id":null,"depth":0,"goal":"Audit the relay","delegation_id":"d1","model":"m","started_at":1700000000.5,"status":"running","tool_count":3,"last_tool":"search_files","accepting_steer":true}"""),
        )
        val hydrated = ChatReducer.subagentSnapshot(opened, SubagentSnapshot(children, opened.subagents.version, atMillis = 5))
        val child = hydrated.subagents.byId("sa-1")!!
        assertEquals(1_700_000_000_500, child.startedAtMillis)
        assertEquals("Search Files", child.lastLine!!.text)
        assertEquals("sa-1", hydrated.card().subagents.first().subagentId)
        // Reaped: a running child the list no longer names is gone.
        val reaped = ChatReducer.subagentSnapshot(hydrated, SubagentSnapshot(emptyList(), hydrated.subagents.version, atMillis = 6))
        assertEquals(emptyList<Subagent>(), reaped.subagents.items)
    }

    @Test
    fun aListAnswerOvertakenByLiveEventsIsDropped() {
        val state = live()
        val stale = SubagentSnapshot(emptyList(), state.subagents.version - 1, atMillis = 5)
        assertSame(state, ChatReducer.subagentSnapshot(state, stale))
    }

    @Test
    fun aListAnswerNeverRevivesAnEndedChild() {
        val ended = live().reduce(complete("sa-1", "Audit the relay", 0, "completed", "Relay is fine.", 12.0))
        val again = listOf(json("""{"subagent_id":"sa-1","goal":"Audit the relay","status":"running"}"""), json("""{"subagent_id":"sa-2","goal":"Read the spec","status":"running"}"""))
        val merged = ChatReducer.subagentSnapshot(ended, SubagentSnapshot(again, ended.subagents.version, atMillis = 5))
        assertEquals(SubagentStatus.Completed, merged.subagents.byId("sa-1")!!.status)
    }

    // ── the child's own view (Hermes' child mirror) ──────────────────────────────────────────────

    @Test
    fun theChildsEventsReadAsItsOwnChat() {
        val state = ChatState(session).reduce(
            delegateStart(),
            start("sa-1", "Audit the relay", 0),
            frame("subagent.thinking", """{"goal":"Audit the relay","task_count":2,"task_index":0,"subagent_id":"sa-1","text":"Look at "}"""),
            frame("subagent.thinking", """{"goal":"Audit the relay","task_count":2,"task_index":0,"subagent_id":"sa-1","text":"the relay."}"""),
            tool("sa-1", "Audit the relay", 0, "read_file", "relay.go"),
            tool("sa-1", "Audit the relay", 0, "terminal", "go test ./..."),
            frame("subagent.complete", """{"goal":"Audit the relay","task_count":2,"task_index":0,"subagent_id":"sa-1","status":"interrupted","summary":"Stopped early."}"""),
        )
        val mirror = state.subagents.byId("sa-1")!!.mirror
        assertEquals(
            listOf(
                ChatItem.Assistant("sa-1#m0", "Audit the relay"),
                ChatItem.Reasoning("sa-1#m1", "Look at the relay."),
                ChatItem.Tool("sa-1#m2", "sa-1#m2", "read_file", "relay.go", ToolStatus.Done),
                ChatItem.Tool("sa-1#m3", "sa-1#m3", "terminal", "go test ./...", ToolStatus.Done),
                ChatItem.Assistant("sa-1#m4", "Stopped early."),
                ChatItem.Notice("sa-1#m5", NoticeKind.Stopped, ""),
            ),
            mirror,
        )
    }

    // ── frames ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun framesCarryOnlyTheKeysTheOwnerAccepts() {
        assertEquals(ComposerCall("subagent.list", json("""{"session_id":"s1"}""")), SubagentFrames.list("s1"))
        assertEquals(ComposerCall("subagent.tail", json("""{"session_id":"s1","subagent_id":"sa-1"}""")), SubagentFrames.tail("s1", "sa-1"))
        assertEquals(ComposerCall("subagent.interrupt", json("""{"session_id":"s1","subagent_id":"sa-1"}""")), SubagentFrames.interrupt("s1", "sa-1"))
    }

    @Test
    fun answersAreReadAsTheOwnerWritesThem() {
        assertTrue(SubagentFrames.stopped(json("""{"found":true,"subagent_id":"sa-1"}""")))
        assertEquals(false, SubagentFrames.stopped(json("""{"found":false,"subagent_id":"sa-1"}""")))
        assertEquals(SubagentTail(true, "12:00:01 tool     | -> read_file(x)\n", true), SubagentFrames.tailOf(json("""{"subagent_id":"sa-1","available":true,"text":"12:00:01 tool     | -> read_file(x)\n","truncated":true}""")))
        assertEquals(SubagentTail(false, "", false), SubagentFrames.tailOf(json("""{"subagent_id":"sa-1","available":false,"text":"","truncated":false}""")))
        assertEquals(listOf("sa-1"), SubagentFrames.children(json("""{"subagents":[{"subagent_id":"sa-1"},"junk"],"delegations":[]}""")).map { it.text("subagent_id") })
        assertEquals(emptyList<JsonObject>(), SubagentFrames.children(null))
    }
}
