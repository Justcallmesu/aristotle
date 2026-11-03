package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Stored transcript rows (Hermes `_rows_to_conversation` shape) to chat items, and the snapshot around them. */
class HistoryMapperTest {
    private fun rows(text: String) = HermesJson.parseToJsonElement(text) as JsonArray

    private val transcript = rows(
        """[
          {"role":"session_meta","content":"x","row_id":1},
          {"role":"user","content":"Run the tests.","row_id":2,"timestamp":1793674036.8},
          {"role":"assistant","content":"","reasoning":"Whole module, not just relay.","row_id":3,
           "tool_calls":[{"id":"call_1","type":"function","function":{"name":"terminal","arguments":"{\"command\":\"go test ./...\"}"}},
                         {"id":"call_2","type":"function","function":{"name":"read_file","arguments":"{\"path\":\"relay.go\"}"}}]},
          {"role":"tool","tool_call_id":"call_1","tool_name":"terminal","content":"{\"output\": \"FAIL relay\", \"exit_code\": 1, \"error\": null}","row_id":4},
          {"role":"tool","tool_call_id":"call_2","tool_name":"read_file","content":"package relay","row_id":5},
          {"role":"assistant","content":[{"type":"text","text":"One package fails."},{"type":"image_url","image_url":{"url":"x"}}],"row_id":6},
          {"role":"assistant","content":"merged","display_metadata":{"model_only":true},"row_id":7},
          {"role":"system","content":"You are Hermes","row_id":8}
        ]""",
    )

    @Test
    fun storedRowsBecomeTheSameItemsTheLiveStreamMakes() {
        assertEquals(
            listOf(
                ChatItem.User("h-2", "Run the tests."),
                ChatItem.Reasoning("h-3-r", "Whole module, not just relay."),
                ChatItem.Tool("tool-call_1", "call_1", "terminal", "go test ./...", ToolStatus.Failed, "FAIL relay\nexit_code: 1"),
                ChatItem.Tool("tool-call_2", "call_2", "read_file", "relay.go", ToolStatus.Done, "package relay"),
                ChatItem.Assistant("h-6", "One package fails."),
            ),
            HistoryMapper.items(transcript),
        )
    }

    @Test
    fun aReusedCallIdStillGetsAUniqueKey() {
        val items = HistoryMapper.items(
            rows(
                """[
                  {"role":"assistant","content":"","row_id":1,"tool_calls":[{"id":"c","function":{"name":"a","arguments":"{}"}}]},
                  {"role":"tool","tool_call_id":"c","content":"one","row_id":2},
                  {"role":"assistant","content":"","row_id":3,"tool_calls":[{"id":"c","function":{"name":"b","arguments":"{}"}}]},
                  {"role":"tool","tool_call_id":"c","content":"two","row_id":4}
                ]""",
            ),
        )
        assertEquals(listOf("tool-c", "h-3-tool-c"), items.map { it.key })
        assertEquals(listOf("one", "two"), items.map { (it as ChatItem.Tool).output })
    }

    @Test
    fun aResultWithoutItsCallGetsItsOwnCard() {
        val items = HistoryMapper.items(rows("""[{"role":"tool","tool_call_id":"lost","tool_name":"terminal","content":"ok","row_id":9}]"""))
        assertEquals(listOf(ChatItem.Tool("h-9", "lost", "terminal", "", ToolStatus.Done, "ok")), items)
    }

    @Test
    fun aSnapshotWithMessagesRebuildsTheTranscriptAndKeepsTheRunningPrompt() {
        val snapshot = HermesJson.parseToJsonElement(
            """{"session_id":"s","messages":[{"role":"user","content":"Count to ten.","row_id":10}],"running":true,
               "replay_epoch":"e","last_sequence":40,"revision":2,"execution_generation":3,
               "pending":[{"admission_id":"adm","status":"started","text":"Count to ten."}],
               "prompts":[{"kind":"approval","prompt_id":"p1","execution_generation":3}],
               "info":{"title":"Counting","model":"m","system_prompt":"secret","credential_hint":"sk-…"}}""",
        ) as JsonObject
        val stale = ChatState("s", items = listOf(ChatItem.Assistant("a-0", "old", streaming = true)))
        val state = ChatReducer.fromSnapshot("s", snapshot, stale)
        assertEquals(listOf(ChatItem.User("h-10", "Count to ten.")), state.items)
        assertTrue(state.running)
        assertEquals(setOf("p1"), state.openPrompts)
        assertEquals(40L, state.lastSeq)
        assertEquals("Counting", state.title)
        assertFalse("system_prompt" in state.info)
        assertFalse("credential_hint" in state.info)

        // The running turn's prompt is in the history: a shape-A update naming it does not add it twice.
        val update = ChatFrame(
            io.github.justcallmesu.aristotle.data.InboundFrame.Event(
                "session.info", "s", HermesJson.parseToJsonElement("""{"pending":[{"admission_id":"adm","status":"started","text":"Count to ten."}]}""") as JsonObject,
                seq = 41, replayEpoch = "e",
            ),
            0,
        )
        assertEquals(state.items, ChatReducer.reduce(state, update).items)
    }

    @Test
    fun aSnapshotWithoutMessagesKeepsTheCaughtUpItems() {
        val caughtUp = ChatState("s", items = listOf(ChatItem.Assistant("a-0", "par", streaming = true)), turn = TurnCursor("t", textKey = "a-0", applied = 3))
        val snapshot = HermesJson.parseToJsonElement("""{"session_id":"s","messages":[],"messages_omitted":true,"running":true,"last_sequence":9}""") as JsonObject
        val state = ChatReducer.fromSnapshot("s", snapshot, caughtUp)
        assertEquals(caughtUp.items, state.items)
        assertEquals("a-0", state.turn?.textKey)
    }
}
