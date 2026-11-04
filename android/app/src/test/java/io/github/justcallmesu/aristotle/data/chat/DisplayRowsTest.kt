package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TC1: Hermes' typed rows (`display_kind`) from stored history and from live frames, as the Desktop shows them. */
class DisplayRowsTest {
    private fun rows(text: String) = HermesJson.parseToJsonElement(text) as JsonArray
    private fun json(text: String) = HermesJson.parseToJsonElement(text) as JsonObject
    private fun event(type: String, payload: String, admission: String? = null) =
        ChatFrame(InboundFrame.Event(type, "s", json(payload), seq = null, replayEpoch = "e", admissionId = admission), 0)

    private val wall = "[ASYNC DELEGATION BATCH COMPLETE]\n" + "result line\n".repeat(200)

    @Test
    fun hiddenRowsAreDropped() {
        val items = HistoryMapper.items(rows("""[
          {"role":"user","content":"widget intent","display_kind":"hidden","row_id":1},
          {"role":"assistant","content":"ok","display_kind":"hidden","row_id":2},
          {"role":"user","content":"[Background process proc_1 heartbeat #3 output]","row_id":3},
          {"role":"user","content":"Hello","row_id":4}]"""))
        assertEquals(listOf<ChatItem>(ChatItem.User("h-4", "Hello")), items)
    }

    @Test
    fun aProcessCompletionIsACardTitledByDisplayText() {
        val items = HistoryMapper.items(rows("""[
          {"role":"user","content":"[SYSTEM: process done]\nexit 0\nlots","display_kind":"process_complete","row_id":1,
           "display_metadata":{"display_text":"Background Process Finished: sleep 20"}}]"""))
        assertEquals(listOf<ChatItem>(ChatItem.Card("h-1", true, "Background Process Finished: sleep 20", "[SYSTEM: process done]\nexit 0\nlots")), items)
    }

    @Test
    fun aDelegationCompletionIsACardAndMetadataMayBeJsonText() {
        val items = HistoryMapper.items(HermesJson.parseToJsonElement(
            """[{"role":"user","content":"${wall.replace("\n", "\\n")}","display_kind":"async_delegation_complete","row_id":1,
               "display_metadata":"{\"display_text\":\"Subagent Tasks Completed: two things (2 tasks)\"}"}]""",
        ) as JsonArray)
        val card = items.single() as ChatItem.Card
        assertEquals(false, card.process)
        assertEquals("Subagent Tasks Completed: two things (2 tasks)", card.title)
        assertEquals(wall.trim(), card.text)
    }

    @Test
    fun withoutDisplayTextTheTitleIsTheFirstLineThenTheDesktopWords() {
        val first = DisplayRows.card("k", "async_delegation_complete", null, null, "\n  first line  \nsecond")
        assertEquals("first line", first.title)
        assertEquals("2 background agents finished", DisplayRows.card("k", "async_delegation_complete", null, 2, "").title)
        assertEquals("1 background agent finished", DisplayRows.card("k", "async_delegation_complete", null, 1, "").title)
        assertEquals("background process finished", DisplayRows.card("k", "process_complete", "  ", null, "").title)
    }

    @Test
    fun noticeKindsReadAsSystemLinesAndSteerStaysAUserRow() {
        val items = HistoryMapper.items(rows("""[
          {"role":"system","content":"x","display_kind":"model_switch","row_id":1},
          {"role":"system","content":"x","display_kind":"auto_continue","row_id":2},
          {"role":"system","content":"x","display_kind":"personality_switch","row_id":3},
          {"role":"assistant","content":"boom","display_kind":"failed_turn","row_id":4,"display_metadata":{"error":"rate limited"}},
          {"role":"user","content":"go left","display_kind":"steer","row_id":5}]"""))
        assertEquals(
            listOf<ChatItem>(
                ChatItem.Notice("h-1", NoticeKind.Info, "model changed"),
                ChatItem.Notice("h-2", NoticeKind.Info, "resumed interrupted turn"),
                ChatItem.Notice("h-3", NoticeKind.Info, "personality changed"),
                ChatItem.Notice("h-4", NoticeKind.TurnFailed, "rate limited"),
                ChatItem.User("h-5", "go left", steer = true),
            ),
            items,
        )
    }

    @Test
    fun liveMessageStartWithAKindIsACardNotABubble() {
        val info = event("session.info", """{"pending":[{"admission_id":"a1","status":"started","text":"WALL"}]}""")
        val start = event("message.start", """{"display_kind":"process_complete","display_text":"Background Process Finished: ls","text":"WALL\nmore"}""", admission = "a1")
        // session.info first: the bubble it showed is replaced; message.start first: no bubble ever appears.
        for (frames in listOf(listOf(info, start), listOf(start, info))) {
            val state = frames.fold(ChatState("s")) { s, f -> ChatReducer.reduce(s, f) }
            assertEquals(listOf<ChatItem>(ChatItem.Card("card-a1", true, "Background Process Finished: ls", "WALL\nmore")), state.items)
            assertTrue("a1" in state.shownAdmissions)
        }
    }

    @Test
    fun liveHiddenAndPlainTurns() {
        val info = event("session.info", """{"pending":[{"admission_id":"a1","status":"started","text":"secret"}]}""")
        val hidden = listOf(info, event("message.start", """{"display_kind":"hidden"}""", admission = "a1")).fold(ChatState("s")) { s, f -> ChatReducer.reduce(s, f) }
        assertTrue(hidden.items.isEmpty())
        val plain = listOf(info, event("message.start", "{}", admission = "a1")).fold(ChatState("s")) { s, f -> ChatReducer.reduce(s, f) }
        assertEquals(listOf<ChatItem>(ChatItem.User("user-a1", "secret")), plain.items)
    }

    @Test
    fun theRunningPromptIsNotShownWhenAHiddenRowOrACardHoldsItAndCardsAreNotTheLastUser() {
        val pending = """"running":true,"pending":[{"admission_id":"a1","status":"started","text":"WALL"}]"""
        fun open(messages: String) = ChatReducer.fromSnapshot("s", json("""{"session_id":"s","messages":$messages,$pending}""")).items
        // Card holds the text: no duplicate bubble.
        assertEquals(1, open("""[{"role":"user","content":"WALL","display_kind":"process_complete","row_id":1}]""").size)
        // Hidden row holds it: nothing at all.
        assertTrue(open("""[{"role":"user","content":"WALL","display_kind":"hidden","row_id":1}]""").isEmpty())
        // Not in the history yet (D35): the prompt is added; a card before it is not "the last user row" it is compared with.
        val items = open("""[{"role":"user","content":"WALL","display_kind":"process_complete","row_id":1},{"role":"user","content":"typed","row_id":2}]""")
        assertEquals(listOf("h-1", "h-2", "user-a1"), items.map { it.key })
        // The text of an earlier typed prompt is not a machine text.
        assertEquals(setOf("WALL"), HistoryMapper.machineTexts(rows("""[{"role":"user","content":"old","row_id":1},{"role":"user","content":"WALL","display_kind":"hidden","row_id":2}]""")))
    }
}
