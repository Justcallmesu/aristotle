package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.chat.CapturedFrames.SESSION
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The event reducer replayed on the frames the v0 spike captured from the real Hermes (docs/spikes/frames). */
class ChatReducerTest {
    private fun json(text: String) = HermesJson.parseToJsonElement(text) as JsonObject

    private fun event(type: String, payload: String, seq: Long? = null, session: String = SESSION, at: Long = 0, admission: String? = null) =
        ChatFrame(InboundFrame.Event(type, session, json(payload), seq = seq, replayEpoch = "e1", admissionId = admission), at)

    private fun ChatState.reduce(vararg frames: ChatFrame): ChatState = frames.fold(this) { state, frame -> ChatReducer.reduce(state, frame) }

    private val busyPrompt = "Use the terminal tool to run: sleep 20; echo done-sleeping. Then count from 1 to 10 in words, one per line."

    @Test
    fun aOneWordTurnFromTheDesktopShowsPromptAndReply() {
        val state = CapturedFrames.replay("c-client1.jsonl")
        assertEquals(
            listOf(
                ChatItem.User("user-91901b71612740519992f24eea842f61", "Reply with the single word pong."),
                ChatItem.Assistant("a-0", "pong", streaming = false),
            ),
            state.items,
        )
        assertFalse(state.running)
        assertNull(state.turn)
        assertEquals("aristotle-spike", state.title)
        assertEquals(12L, state.lastSeq)
    }

    @Test
    fun theBusyCaptureGivesThreeTurnsWithToolCardsAndTheStoppedOne() {
        val items = CapturedFrames.replay("f-busy-client1.jsonl").items
        assertEquals(
            listOf(
                ChatItem.User("user-7ba91e2c1dcd46f09aa36a2bc627ee0c", busyPrompt),
                ChatItem.Tool(
                    key = "tool-toolu_01JQBSNC2ALMBmJqehwyF7Ap",
                    toolId = "toolu_01JQBSNC2ALMBmJqehwyF7Ap",
                    name = "terminal",
                    summary = "sleep 20 + 1 command",
                    status = ToolStatus.Done,
                    output = "done-sleeping",
                    durationSeconds = 20.487507931000437,
                ),
                ChatItem.Assistant("a-0", "one\ntwo\nthree\nfour\nfive\nsix\nseven\neight\nnine\nten\nsteered"),
                ChatItem.User("user-5060991b63f0419180b3fd120cc7c698", "Reply with the single word queued."),
                ChatItem.Assistant("a-1", "queued"),
                ChatItem.User("user-be734d08a5944170ad2c3908b172ff16", "Use the terminal tool to run: sleep 30. Then say finished."),
                ChatItem.Tool(
                    key = "tool-toolu_01TkrUH16UdSeg27YgpaUXKB",
                    toolId = "toolu_01TkrUH16UdSeg27YgpaUXKB",
                    name = "terminal",
                    summary = "sleep 30",
                    status = ToolStatus.Failed,
                    output = "[Command interrupted]\nexit_code: 130",
                    durationSeconds = 6.757521550000092,
                ),
                ChatItem.Notice("n-2", NoticeKind.Stopped, ""),
            ),
            items,
        )
    }

    @Test
    fun bothViewersOfTheBusyCaptureSeeTheSameTranscript() {
        assertEquals(CapturedFrames.replay("f-busy-client1.jsonl").items, CapturedFrames.replay("f-busy-client2.jsonl").items)
    }

    @Test
    fun whileStreamingOnlyTheLastItemChangesAndCarriesTheCursor() {
        // f-busy-client1.jsonl:31-32 are the first two text chunks after the tool card.
        val before = CapturedFrames.replay("f-busy-client1.jsonl", through = 31)
        val after = ChatReducer.reduce(before, CapturedFrames.frameAt("f-busy-client1.jsonl", 32))
        assertEquals(ChatItem.Assistant("a-0", "one\ntwo\nthree\nfour\nfive\nsix\nseven\ne", streaming = true), after.items.last())
        assertTrue(after.streaming)
        assertTrue(after.running)
        assertEquals(before.items.size, after.items.size)
        before.items.dropLast(1).zip(after.items.dropLast(1)).forEach { (a, b) -> assertSame(a, b) }
    }

    @Test
    fun aRunningToolShowsAsRunningUntilItCompletes() {
        val state = CapturedFrames.replay("f-busy-client1.jsonl", through = 21)
        val tool = state.items.last() as ChatItem.Tool
        assertEquals(ToolStatus.Running, tool.status)
        assertEquals(1, state.step)
        assertTrue(state.running)
    }

    @Test
    fun approvalAndClarifyPromptsAreOpenUntilSettled() {
        val approval = "3c94f4763f5e4cf2826cf8acf22047fc"
        assertEquals(setOf(approval), CapturedFrames.replay("d-client1.jsonl", through = 71).openPrompts)
        assertEquals(emptySet<String>(), CapturedFrames.replay("d-client1.jsonl", through = 77).openPrompts)
        assertEquals(setOf("da894a8c36"), CapturedFrames.replay("e-client1.jsonl", through = 17).openPrompts)
        assertEquals(emptySet<String>(), CapturedFrames.replay("e-client1.jsonl").openPrompts)
    }

    @Test
    fun aDeltaStreamOfManySmallChunksBuildsOneBlock() {
        val text = (CapturedFrames.replay("d-client1.jsonl").items.last { it is ChatItem.Assistant } as ChatItem.Assistant).text
        assertTrue(text, text.startsWith("The write was blocked. `AGENTS.md` is a protected agent-instruction file"))
        assertTrue(text, text.endsWith("I'll run it again."))
    }

    @Test
    fun theFullInfoIsMergedAndNeverKeepsTheSystemPromptOrCredentialHint() {
        val info = CapturedFrames.replay("f-busy-client1.jsonl").info
        assertEquals("claude-sonnet-5-5", (info["model"] as JsonPrimitive).content)
        assertEquals("/home/justcallmesu", (info["cwd"] as JsonPrimitive).content)
        assertFalse("system_prompt" in info)
        assertFalse("credential_hint" in info)
    }

    @Test
    fun shapeAAfterShapeBBlanksNoField() {
        // c-client1.jsonl:26 is the full inventory (shape B), :29 the 7-key update (shape A) right after it.
        val afterB = CapturedFrames.replay("c-client1.jsonl", through = 26)
        val afterA = CapturedFrames.replay("c-client1.jsonl", through = 29)
        val shapeB = CapturedFrames.frameAt("c-client1.jsonl", 26).event.payload!!.keys - setOf("system_prompt", "credential_hint")
        shapeB.forEach { key -> assertEquals(key, afterB.info[key], afterA.info[key]) }
        assertEquals("aristotle-spike", afterA.title)
        assertEquals(JsonPrimitive(1), afterA.info["revision"])
        assertFalse(afterA.running)

        // And on a synthetic pair: a shape A carrying only a few keys keeps every earlier one.
        val start = ChatState(SESSION).reduce(event("session.info", """{"title":"T","model":"m","cwd":"/w","running":true}"""))
        val next = start.reduce(event("session.info", """{"running":false,"revision":3,"pending":[]}"""))
        assertEquals("T", next.title)
        assertEquals("m", next.model)
        assertEquals(JsonPrimitive("/w"), next.info["cwd"])
        assertFalse(next.running)
    }

    @Test
    fun thinkingDeltaIsHermesSpinnerNotAnItem() {
        val spinning = ChatState(SESSION).reduce(event("thinking.delta", """{"text":"(¬_¬) reasoning..."}"""))
        assertTrue(spinning.thinking)
        assertTrue(spinning.items.isEmpty())
        assertFalse(spinning.reduce(event("thinking.delta", """{"text":""}""")).thinking)
    }

    @Test
    fun reasoningStreamsCollapsedAndKnowsHowLongItTook() {
        val state = ChatState(SESSION).reduce(
            event("message.start", "{}", at = 1_000),
            event("reasoning.delta", """{"text":"Run the "}""", at = 1_000),
            event("reasoning.delta", """{"text":"whole module."}""", at = 2_000),
        )
        assertEquals(ChatItem.Reasoning("r-0", "Run the whole module.", streaming = true, startedAtMillis = 1_000), state.items.single())
        val answered = state.reduce(event("message.delta", """{"text":"Done.","turn_id":"t","segment":0,"offset":0}""", at = 4_200))
        assertEquals(ChatItem.Reasoning("r-0", "Run the whole module.", streaming = false, startedAtMillis = 1_000, durationMillis = 3_200), answered.items[0])
        assertEquals(ChatItem.Assistant("a-1", "Done.", streaming = true), answered.items[1])
    }

    @Test
    fun aReSentChunkIsNotAppliedTwice() {
        val first = event("message.delta", """{"text":"Hello wor","turn_id":"t","segment":0,"offset":0}""")
        val resent = event("message.delta", """{"text":"Hello world","turn_id":"t","segment":0,"offset":0}""")
        val state = ChatState(SESSION).reduce(event("message.start", "{}"), first, first, resent)
        assertEquals("Hello world", (state.items.single() as ChatItem.Assistant).text)
    }

    @Test
    fun anEventAtOrBeforeTheLastSeqIsIgnored() {
        val delta = event("message.delta", """{"text":"x"}""", seq = 5)
        val once = ChatState(SESSION).reduce(event("message.start", "{}", seq = 4), delta)
        assertSame(once, ChatReducer.reduce(once, delta))
        assertEquals(5L, once.lastSeq)
    }

    @Test
    fun unknownEventsAndOtherSessionsLeaveTheStateAlone() {
        val state = CapturedFrames.replay("c-client1.jsonl")
        // `subagent.*` is no longer unknown (T17, SubagentsTest); `cron.changed` still is to the chat.
        assertSame(state, ChatReducer.reduce(state, event("cron.changed", """{"id":"s"}""")))
        assertSame(state, ChatReducer.reduce(state, event("message.delta", """{"text":"x"}""", session = "other")))
        assertSame(state, ChatReducer.reduce(state, ChatFrame(InboundFrame.Event("message.delta", SESSION, null), 0)))
    }

    @Test
    fun malformedPayloadsDoNotCrash() {
        val state = ChatState(SESSION).reduce(
            event("message.start", """{"turn_id":5}"""),
            event("message.delta", """{"text":7,"segment":"x","offset":[]}"""),
            event("message.delta", """{"text":"ok","segment":"x","offset":"y"}"""),
            event("tool.start", """{"tool_id":null}"""),
            event("tool.complete", """{"tool_id":"t1","result":{"deep":[1,2]},"duration_s":"slow","is_error":"yes"}"""),
            event("session.info", """{"pending":"none","running":"maybe"}"""),
            event("agent.step", """{"iteration":"two"}"""),
            event("message.complete", """{"text":{"rich":true},"status":5}"""),
        )
        assertEquals("ok", (state.items.first() as ChatItem.Assistant).text)
        assertEquals(ToolStatus.Done, (state.items[1] as ChatItem.Tool).status)
        assertFalse(state.running)
    }

    @Test
    fun aFinalTextThatDidNotStreamIsAdded() {
        val state = ChatState(SESSION).reduce(event("message.start", "{}"), event("message.complete", """{"text":"All done.","status":"complete"}"""))
        assertEquals(listOf(ChatItem.Assistant("a-0", "All done.")), state.items)
    }

    @Test
    fun aStreamedFinalTextIsNotRepeatedButATransformedOneReplacesIt() {
        val streamed = ChatState(SESSION).reduce(
            event("message.start", "{}"),
            event("message.delta", """{"text":"draft","turn_id":"t","segment":0,"offset":0}"""),
        )
        val plain = streamed.reduce(event("message.complete", """{"text":"draft","status":"complete"}"""))
        assertEquals(listOf(ChatItem.Assistant("a-0", "draft")), plain.items)
        val transformed = streamed.reduce(event("message.complete", """{"text":"final","status":"complete","response_transformed":true}"""))
        assertEquals(listOf(ChatItem.Assistant("a-0", "final")), transformed.items)
    }

    @Test
    fun aFailedTurnAndASessionErrorBecomeNotices() {
        val state = ChatState(SESSION).reduce(
            event("message.start", "{}"),
            event("message.complete", """{"text":"provider quota exceeded","status":"error"}"""),
            event("error", """{"message":"model switch failed"}"""),
        )
        assertEquals(
            listOf(ChatItem.Notice("n-0", NoticeKind.TurnFailed, "provider quota exceeded"), ChatItem.Notice("n-1", NoticeKind.Error, "model switch failed")),
            state.items,
        )
    }

    @Test
    fun anInterimSealStartsTheNextSegmentAndAddsTextThatDidNotStream() {
        val state = ChatState(SESSION).reduce(
            event("message.start", "{}"),
            event("message.delta", """{"text":"Looking.","turn_id":"t","segment":0,"offset":0}"""),
            event("message.interim", """{"text":"Looking.","already_streamed":true,"turn_id":"t","segment":0}"""),
            event("message.interim", """{"text":"Checked the tests.","already_streamed":false,"turn_id":"t","segment":1}"""),
            event("message.delta", """{"text":"All green.","turn_id":"t","segment":2,"offset":0}"""),
        )
        assertEquals(
            listOf(ChatItem.Assistant("a-0", "Looking."), ChatItem.Assistant("a-1", "Checked the tests."), ChatItem.Assistant("a-2", "All green.", streaming = true)),
            state.items,
        )
    }

    @Test
    fun toolProgressShowsOnTheRunningCard() {
        val state = ChatState(SESSION).reduce(
            event("tool.start", """{"tool_id":"t1","name":"browser","args":{"url":"https://example.org"}}"""),
            event("tool.progress", """{"tool_id":"t1","name":"browser","progress":1,"total":4,"message":"loading page"}"""),
        )
        val tool = state.items.single() as ChatItem.Tool
        assertEquals("https://example.org", tool.summary)
        assertEquals("loading page", tool.progress)
    }

    @Test
    fun theSnapshotOfACapturedResumeSetsPositionAndInfo() {
        val snapshot = CapturedFrames.result("f-busy-client1.jsonl", 7)
        val state = ChatReducer.fromSnapshot(SESSION, snapshot)
        assertEquals(121L, state.lastSeq)
        assertEquals("509ec652dfb347cd859de859271faf2e", state.replayEpoch)
        assertEquals("aristotle-spike", state.title)
        assertFalse("system_prompt" in state.info)
        assertFalse("credential_hint" in state.info)
        assertEquals(JsonPrimitive(5), state.info["revision"])
        assertFalse(state.running)

        // The live turn that follows continues the snapshot: frames up to seq 121 are not applied again.
        val live = CapturedFrames.replay("f-busy-client1.jsonl", start = state)
        assertEquals(CapturedFrames.replay("f-busy-client1.jsonl").items, live.items)
    }

    // T11 review MAJOR-1: an omitted snapshot keeps the caught-up position; the frames between it and the
    // snapshot's last_sequence (sent before the resume reply) finish the turn in place, once.
    @Test
    fun anOmittedSnapshotKeepsTheCaughtUpPositionSoTheFramesInBetweenApply() {
        val caughtUp = ChatState(SESSION).reduce(
            event("message.start", "{}", seq = 1),
            event("message.delta", """{"text":"one","turn_id":"t","segment":0,"offset":0}""", seq = 2),
        )
        val snapshot = json(
            """{"session_id":"$SESSION","messages":[],"messages_omitted":true,"running":false,"replay_epoch":"e1","last_sequence":4,"prompts":[],"pending":[],"info":{}}""",
        )
        val resumed = ChatReducer.fromSnapshot(SESSION, snapshot, caughtUp)
        assertEquals(2L, resumed.lastSeq)
        assertEquals("e1", resumed.replayEpoch)
        assertTrue("the turn is still running until its own complete arrives", resumed.running)
        val done = resumed.reduce(
            event("message.delta", """{"text":" two","turn_id":"t","segment":0,"offset":3}""", seq = 3),
            event("message.delta", """{"text":" two","turn_id":"t","segment":0,"offset":3}""", seq = 3),
            event("message.complete", """{"text":"one two","status":"complete"}""", seq = 4),
        )
        assertEquals(listOf(ChatItem.Assistant("a-0", "one two")), done.items)
        assertFalse(done.running)
        assertEquals(4L, done.lastSeq)
    }

    // An omitted snapshot that is not ahead of the catch-up settles a turn the snapshot says is over.
    @Test
    fun anOmittedSnapshotAtTheCaughtUpPositionTakesItsRunningState() {
        val caughtUp = ChatState(SESSION).reduce(
            event("message.start", "{}", seq = 1),
            event("message.delta", """{"text":"one","turn_id":"t","segment":0,"offset":0}""", seq = 2),
        )
        val snapshot = json("""{"messages_omitted":true,"running":false,"replay_epoch":"e1","last_sequence":2,"prompts":[],"pending":[]}""")
        val resumed = ChatReducer.fromSnapshot(SESSION, snapshot, caughtUp)
        assertFalse(resumed.running)
        assertNull(resumed.turn)
        assertEquals(listOf(ChatItem.Assistant("a-0", "one")), resumed.items)
    }

    // T11 review MINOR-2: opened mid-turn, the snapshot has no partial text, so the first delta starts past what
    // was applied. The final text replaces that partial block.
    @Test
    fun aChatOpenedMidTurnGetsTheWholeFinalText() {
        val snapshot = json(
            """{"session_id":"$SESSION","messages":[{"role":"user","content":"Count to five.","row_id":1}],"running":true,
               "replay_epoch":"e1","last_sequence":5,"prompts":[],"pending":[],"info":{}}""",
        )
        val opened = ChatReducer.fromSnapshot(SESSION, snapshot)
        val streaming = opened.reduce(
            event("message.delta", """{"text":"four\nfive","turn_id":"t","segment":0,"offset":14}""", seq = 6),
        )
        assertEquals(ChatItem.Assistant("a-0", "four\nfive", streaming = true), streaming.items.last())
        val done = streaming.reduce(event("message.complete", """{"text":"one\ntwo\nthree\nfour\nfive","status":"complete"}""", seq = 7))
        assertEquals(listOf(ChatItem.User("h-1", "Count to five."), ChatItem.Assistant("a-0", "one\ntwo\nthree\nfour\nfive")), done.items)
    }

    // A turn followed from its start is left as it streamed (the final text is not swapped in).
    @Test
    fun aTurnFollowedFromItsStartKeepsItsStreamedText() {
        val done = ChatState(SESSION).reduce(
            event("message.start", "{}"),
            event("message.delta", """{"text":"Hello","turn_id":"t","segment":0,"offset":0}"""),
            event("message.delta", """{"text":" there","turn_id":"t","segment":0,"offset":5}"""),
            event("message.complete", """{"text":"Hello there (stripped)","status":"complete"}"""),
        )
        assertEquals(listOf(ChatItem.Assistant("a-0", "Hello there")), done.items)
    }
}
