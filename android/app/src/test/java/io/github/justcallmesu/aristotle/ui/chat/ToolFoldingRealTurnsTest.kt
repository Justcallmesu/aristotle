package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.chat.ChatFrame
import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.ChatReducer
import io.github.justcallmesu.aristotle.data.chat.HistoryMapper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TU4b, updated for Q75: "N steps" folding on two real turns from the Hermes session store, in the `session.resume` row
 * shape the gateway sends (`local_history`; texts, arguments and outputs redacted, `src/test/resources/chat/`). One is a
 * stored turn (reasoning, text, four calls with reasoning between them, a delegation), the other a running turn: 10 stored
 * calls with reasoning between them, then a call that arrives live, in the order Hermes publishes a local viewer's tool round.
 */
class ToolFoldingRealTurnsTest {
    private fun messages(name: String): JsonArray {
        val text = javaClass.classLoader!!.getResource("chat/$name")!!.readText()
        return (HermesJson.parseToJsonElement(text) as JsonObject)["messages"] as JsonArray
    }

    private fun describe(row: TranscriptRow): String = when (row) {
        is TranscriptRow.Folded -> "fold(${row.steps.joinToString(",") { if (it is ChatItem.Tool) it.name else "thought" }})"
        is TranscriptRow.Single -> when (val item = row.item) {
            is ChatItem.Tool -> "tool(${item.name})"
            else -> item::class.simpleName!!.lowercase()
        }
    }

    @Test
    fun aStoredTurnFoldsItsReasoningAndCallsIntoStepsLines() {
        val rows = ToolFolding.rows(HistoryMapper.items(messages("stored-turn-with-calls.json")))
        assertEquals(
            listOf("user", "fold(thought)", "assistant", "fold(terminal,thought,write_file,patch,execute_code)", "tool(delegate_task)", "assistant", "card"),
            rows.map(::describe),
        )
        // The reasoning before the text is a run of one step; the text ends it.
        assertEquals(StepsSummary(steps = 1, running = false, tool = null, failed = 0), (rows[1] as TranscriptRow.Folded).summary(live = true))
        val fold = rows[3] as TranscriptRow.Folded
        // The reasoning between the first and second call folds with them.
        assertEquals(listOf("tool-toolu_01", "h-15004-r", "tool-toolu_02", "tool-toolu_03", "tool-toolu_04"), fold.items.map { it.key })
        assertEquals(StepsSummary(steps = 5, running = false, tool = "execute_code", failed = 0), fold.summary(live = true))
    }

    @Test
    fun aRunningTurnFoldsItsStoredAndLiveCallsIntoOneLine() {
        val snapshot = buildJsonObject {
            put("session_id", SESSION)
            put("running", true)
            put("last_sequence", 40)
            put("replay_epoch", "e1")
            put("pending", HermesJson.parseToJsonElement("""[{"admission_id":"adm","status":"started","text":"already plugged"}]"""))
            put("messages", messages("running-turn-with-calls.json"))
        }
        var state = ChatReducer.fromSnapshot(SESSION, snapshot)
        var seq = 41L
        fun event(type: String, payload: String) {
            val frame = InboundFrame.Event(type, SESSION, HermesJson.parseToJsonElement(payload) as JsonObject, seq = seq, replayEpoch = "e1", admissionId = "adm")
            state = ChatReducer.reduce(state, ChatFrame(frame, seq * 1000))
            seq++
        }
        val start = """"tool_id":"live1","name":"terminal","context":"bash shot.sh","tool_call_id":"live1","tool_name":"terminal","args":{"command":"bash shot.sh"}"""
        // One round as Hermes publishes it to a local viewer (gateway/local_viewer_events.py, run_turn_progress.py).
        event("thinking.delta", """{"text":"(o_o) pondering"}""")
        event("reasoning.delta", """{"text":"Take the screenshot next."}""")
        event("message.delta", """{"text":null}""")
        event("tool.generating", """{"name":"terminal"}""")
        event("reasoning.available", """{"text":"Take the screenshot next."}""")
        event("tool.start", "{$start}")

        val rows = ToolFolding.rows(state.items)
        assertEquals(listOf("assistant", "user", "fold"), rows.map { describe(it).substringBefore('(') })
        val fold = rows.last() as TranscriptRow.Folded
        assertEquals(11, fold.tools.size)
        assertEquals(6, fold.items.count { it is ChatItem.Reasoning })
        assertEquals("live1", fold.lastTool?.toolId)
        assertTrue(fold.running && fold.failed)
        // Live: "Running · 17 steps" naming the live call, with the stored failure; the spinner only while live.
        assertEquals(StepsSummary(steps = 17, running = true, tool = "terminal", failed = fold.failedCount), fold.summary(live = true))
        assertEquals(false, fold.summary(live = false).running)

        // The line keeps its key (LazyColumn key, open state) as the live call ends.
        val key = fold.key
        event("tool.complete", """{$start,"result":"{\"output\":\"ok\"}","is_error":false,"duration_s":0.4}""")
        val after = ToolFolding.rows(state.items).last() as TranscriptRow.Folded
        assertEquals(key, after.key)
        assertEquals(false, after.running)
    }

    private companion object {
        const val SESSION = "s"
    }
}
