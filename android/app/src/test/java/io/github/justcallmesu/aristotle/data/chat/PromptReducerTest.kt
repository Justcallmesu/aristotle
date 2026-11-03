package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.RpcFrames
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Prompts in the chat state: open, settled and withdrawn, for every prompt type (protocol §6). */
class PromptReducerTest {
    private val s = "s1"

    private fun obj(text: String): JsonObject = HermesJson.parseToJsonElement(text) as JsonObject

    private fun event(type: String, payload: String, session: String = s) = ChatFrame(InboundFrame.Event(type, session, obj(payload)), 1_000)

    private fun request(id: String, method: String, params: String) =
        RpcFrames.parse("""{"jsonrpc":"2.0","id":"$id","method":"$method","params":$params}""") as InboundFrame.ServerRequest

    private fun ChatState.keys() = prompts.map { it.key }

    @Test
    fun theCapturedApprovalOpensAndSettles() {
        val open = CapturedFrames.replay("d-client1.jsonl", through = 71)
        assertEquals(listOf("3c94f4763f5e4cf2826cf8acf22047fc"), open.keys())
        val settled = CapturedFrames.replay("d-client1.jsonl", through = 77)
        assertTrue(settled.prompts.isEmpty())
        assertEquals("3c94f4763f5e4cf2826cf8acf22047fc", settled.lastSettled?.prompt?.key)
        assertNull(settled.lastSettled?.reason)
    }

    @Test
    fun theCapturedClarifyOpensAndSettles() {
        val open = CapturedFrames.replay("e-client1.jsonl", through = 17)
        assertEquals(listOf("da894a8c36"), open.keys())
        assertTrue(open.prompts.single() is Prompt.Clarify)
        val settled = CapturedFrames.replay("e-client1.jsonl")
        assertTrue(settled.prompts.isEmpty())
        assertEquals("da894a8c36", settled.lastSettled?.prompt?.key)
    }

    @Test
    fun serverRequestsOpenAndRequestCancelWithdrawsThem() {
        var state = ChatState(s)
        state = ChatReducer.request(state, request("srq-000000000001", "sudo", """{"session_id":"s1","command":"sudo ls"}"""))
        state = ChatReducer.request(state, request("srq-000000000002", "secret", """{"session_id":"s1","env_var":"KEY","prompt":"p"}"""))
        state = ChatReducer.request(state, request("srq-000000000003", "clarify", """{"session_id":"s1","questions":[{"qid":"q0","question":"Q?"}]}"""))
        state = ChatReducer.request(state, request("srq-000000000004", "vault.code", """{"session_id":"s1","site":"github.com"}"""))
        assertEquals(listOf("srq-000000000001", "srq-000000000002", "srq-000000000003", "srq-000000000004"), state.keys())
        assertEquals(listOf(Prompt.Secret::class, Prompt.Secret::class, Prompt.Clarify::class, Prompt.OnPc::class), state.prompts.map { it::class })

        for ((id, reason) in listOf("srq-000000000001" to "resolved", "srq-000000000002" to "timeout", "srq-000000000003" to "interrupted", "srq-000000000004" to "session_closed")) {
            val serial = state.lastSettled?.serial
            state = ChatReducer.reduce(state, event("request.cancel", """{"id":"$id","method":"x","reason":"$reason"}"""))
            assertEquals(id, state.lastSettled?.prompt?.key)
            assertEquals(reason, state.lastSettled?.reason)
            assertTrue(state.lastSettled?.serial != serial)
        }
        assertTrue(state.prompts.isEmpty())
    }

    @Test
    fun aRepeatedRequestReplacesItsEntry() {
        val sudo = request("srq-000000000001", "sudo", """{"session_id":"s1","command":"sudo ls"}""")
        val once = ChatReducer.request(ChatState(s), sudo)
        assertEquals(1, ChatReducer.request(once, sudo).prompts.size)
    }

    @Test
    fun otherSessionsWindowBridgesAndUnknownSettlesChangeNothing() {
        val state = ChatReducer.request(ChatState(s), request("srq-000000000001", "sudo", """{"session_id":"s1","command":"sudo ls"}"""))
        assertSame(state, ChatReducer.request(state, request("srq-000000000009", "sudo", """{"session_id":"other","command":"x"}""")))
        assertSame(state, ChatReducer.request(state, request("srq-000000000009", "terminal.read", """{"session_id":"s1"}""")))
        assertEquals(state.prompts, ChatReducer.reduce(state, event("request.cancel", """{"id":"srq-unknown","method":"sudo","reason":"timeout"}""")).prompts)
        assertNull(ChatReducer.reduce(state, event("approval.settled", """{"prompt_id":"nope"}""")).lastSettled)
    }

    @Test
    fun aSnapshotListsPromptsAndOpenRequestsAndReportsTheOnesThatWentAway() {
        val before = ChatReducer.request(
            ChatReducer.reduce(ChatState(s), event("approval.request", """{"kind":"approval","prompt_id":"gone","execution_generation":3,"command":"ls","choices":["once","deny"]}""")),
            request("srq-000000000001", "sudo", """{"session_id":"s1","command":"sudo ls"}"""),
        )
        val snapshot = obj(
            """{"session_id":"s1","messages":[],"messages_omitted":true,"running":true,"replay_epoch":"e","last_sequence":5,
               "prompts":[{"kind":"clarify","prompt_id":"c1","execution_generation":3,"question":"Q?","choices":[],"multi_select":false}],
               "open_requests":[{"id":"srq-000000000001","method":"sudo","params":{"session_id":"s1","command":"sudo ls"}},
                                {"id":"srq-000000000002","method":"terminal.read","params":{"session_id":"s1"}}]}""",
        )
        val after = ChatReducer.fromSnapshot(s, snapshot, before)
        assertEquals(listOf("c1", "srq-000000000001"), after.keys())
        assertEquals("gone", after.lastSettled?.prompt?.key)
    }

    @Test
    fun eventsSinceOpenRequestsReplaceTheServerRequestsOnly() {
        val state = ChatReducer.request(
            ChatReducer.reduce(ChatState(s), event("approval.request", """{"kind":"approval","prompt_id":"a1","execution_generation":3,"choices":["once"]}""")),
            request("srq-000000000001", "sudo", """{"session_id":"s1","command":"sudo ls"}"""),
        )
        val next = ChatReducer.withOpenRequests(state, HermesJson.parseToJsonElement("""[{"id":"srq-000000000002","method":"secret","params":{"session_id":"s1","env_var":"K","prompt":"p"}}]""") as JsonArray)
        assertEquals(listOf("a1", "srq-000000000002"), next.keys())
        assertEquals("srq-000000000001", next.lastSettled?.prompt?.key)
        assertSame(state, ChatReducer.withOpenRequests(state, null))
    }
}
