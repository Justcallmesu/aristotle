package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the composer reads from the reducer: the turn's generation, Hermes' queue, its input ids, and D35. */
class ComposerReducerTest {
    private val session = CapturedFrames.SESSION

    private fun json(text: String) = HermesJson.parseToJsonElement(text) as JsonObject

    @Test
    fun theBusyCaptureGivesTheGenerationTheSteerUsedAndTheQueueTheDesktopShows() {
        // f-busy-client2.jsonl:25 steered generation 6; by :23 Hermes listed the queued prompt.
        val state = CapturedFrames.replay("f-busy-client2.jsonl", through = 23)
        assertEquals(6L, state.generation)
        assertEquals(listOf(QueuedPrompt("5060991b63f0419180b3fd120cc7c698", "72833571b68623ca764e546f0d3e99d1", "Reply with the single word queued.")), state.queued)
        assertEquals("5060991b63f0419180b3fd120cc7c698", state.inputs["72833571b68623ca764e546f0d3e99d1"])
        // The interrupt at f-busy-client1.jsonl:70 named generation 8.
        assertEquals(8L, CapturedFrames.replay("f-busy-client1.jsonl", through = 69).generation)
    }

    @Test
    fun anOlderFrameNeverMovesTheGenerationBack() {
        val state = CapturedFrames.replay("c-send.jsonl")
        assertEquals(2L, state.generation) // shape A at :11 still said 1, the turn ran as 2
        assertTrue(state.queued.isEmpty())
    }

    // D35: a chat opened before Hermes stored the running turn's prompt (agent/turn_context.py:1076-1090).
    @Test
    fun aChatOpenedBeforeThePromptWasStoredShowsItFromPending() {
        val snapshot = json(
            """{"session_id":"$session","running":true,"replay_epoch":"e","last_sequence":9,"prompts":[],"info":{},"execution_generation":3,
               "messages":[{"role":"user","content":"First","row_id":1},{"role":"assistant","content":"Done.","row_id":2}],
               "pending":[{"admission_id":"a2","input_id":"i2","status":"started","text":"Second","steering":false}]}""",
        )
        val state = ChatReducer.fromSnapshot(session, snapshot)
        assertEquals(listOf("First", "Done.", "Second"), state.items.map { (it as? ChatItem.User)?.text ?: (it as ChatItem.Assistant).text })
        assertEquals(ChatItem.User("user-a2", "Second"), state.items.last())
        assertTrue("a2" in state.shownAdmissions)
        assertEquals(3L, state.generation)
        // Its message.start (replayed or late) does not add it again.
        val started = ChatReducer.reduce(state, ChatFrame(io.github.justcallmesu.aristotle.data.InboundFrame.Event("message.start", session, json("{}"), seq = 10, replayEpoch = "e", admissionId = "a2"), 0))
        assertEquals(1, started.items.count { it is ChatItem.User && it.text == "Second" })
    }

    @Test
    fun aStoredPromptIsNotShownTwice() {
        val snapshot = json(
            """{"session_id":"$session","running":true,"replay_epoch":"e","last_sequence":9,"prompts":[],"info":{},
               "messages":[{"role":"user","content":"Second ","row_id":3},{"role":"assistant","content":"","tool_calls":[],"row_id":4}],
               "pending":[{"admission_id":"a2","input_id":"i2","status":"started","text":"Second"},
                          {"admission_id":"a3","input_id":"i3","status":"started","text":"steer me","steering":true}]}""",
        )
        val state = ChatReducer.fromSnapshot(session, snapshot)
        assertEquals(listOf(ChatItem.User("h-3", "Second ")), state.items)
    }
}
