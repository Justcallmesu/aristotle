package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.RpcFrames
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Prompt parsing and the answer frames, against the captured frames (`d-client1`, `e-client2`) and the Hermes
 * contracts (`tui_gateway/contracts/server_requests.py`, `gateway/session_controls.py:925-935`,
 * `gateway/session_clarify_lock.py`).
 */
class PromptsTest {
    private val session = CapturedFrames.SESSION

    private fun obj(text: String): JsonObject = HermesJson.parseToJsonElement(text) as JsonObject

    private fun request(id: String, method: String, params: String): InboundFrame.ServerRequest =
        RpcFrames.parse("""{"jsonrpc":"2.0","id":"$id","method":"$method","params":$params}""") as InboundFrame.ServerRequest

    private fun capturedPrompt(file: String, line: Int): Prompt =
        Prompts.fromEvent(CapturedFrames.frameAt(file, line).event.payload!!, at = 1_000)!!

    private fun capturedParams(file: String, line: Int): JsonObject =
        CapturedFrames.lines(file).first { it.line == line }.frame["params"] as JsonObject

    private fun single(replies: List<PromptReply>): PromptReply.Call = replies.single() as PromptReply.Call

    // ── approval ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theCapturedApprovalOffersOnlyWhatHermesSent() {
        val prompt = capturedPrompt("d-client1.jsonl", 71) as Prompt.Approval
        assertEquals("3c94f4763f5e4cf2826cf8acf22047fc", prompt.key)
        assertEquals(4L, prompt.generation)
        assertEquals("<write to AGENTS.md>", prompt.command)
        assertTrue(prompt.description.startsWith("Write to protected agent-instruction file(s)"))
        assertEquals(listOf(ApprovalChoice.Once, ApprovalChoice.Deny), prompt.choices)
        assertEquals(1_000L + 300_000L, prompt.deadlineMillis)
    }

    @Test
    fun theApprovalAnswerIsTheFrameTheSpikeSent() {
        val prompt = capturedPrompt("d-client1.jsonl", 71)
        val call = single(PromptReplies.of(session, prompt, PromptAnswer.Choose(ApprovalChoice.Deny)))
        assertEquals("approval.respond", call.method)
        assertEquals(capturedParams("d-client1.jsonl", 75), call.params)
        // `respond` refuses any other key set (session_controls.py:926).
        assertEquals(setOf("session_id", "execution_generation", "prompt_id", "choice"), call.params.keys)
    }

    @Test
    fun aChoiceHermesDidNotOfferIsNeverSent() {
        val prompt = capturedPrompt("d-client1.jsonl", 71)
        assertTrue(PromptReplies.of(session, prompt, PromptAnswer.Choose(ApprovalChoice.Always)).isEmpty())
        assertTrue(PromptReplies.of(session, prompt, PromptAnswer.Choose(ApprovalChoice.Session)).isEmpty())
        assertTrue(PromptReplies.of(session, prompt, PromptAnswer.Value("x")).isEmpty())
    }

    @Test
    fun allFourChoicesInHermesOrderAndUnknownOnesDropped() {
        val prompt = Prompts.fromEvent(
            obj("""{"kind":"approval","prompt_id":"p","execution_generation":2,"command":"rm -r x","choices":["once","deny","session","always","later"]}"""),
            at = null,
        ) as Prompt.Approval
        assertEquals(ApprovalChoice.entries, prompt.choices)
        assertNull(prompt.deadlineMillis)
        val call = single(PromptReplies.of("s", prompt, PromptAnswer.Choose(ApprovalChoice.Always)))
        assertEquals(obj("""{"session_id":"s","execution_generation":2,"prompt_id":"p","choice":"always"}"""), call.params)
    }

    @Test
    fun aPromptWithoutIdOrGenerationIsNotAPrompt() {
        assertNull(Prompts.fromEvent(obj("""{"kind":"approval","execution_generation":2}"""), at = null))
        assertNull(Prompts.fromEvent(obj("""{"kind":"approval","prompt_id":"p"}"""), at = null))
        assertNull(Prompts.fromEvent(obj("""{"kind":"sudo","prompt_id":"p","execution_generation":2}"""), at = null))
    }

    // ── clarify ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun theClarifyAnswerIsTheFrameTheSpikeSent() {
        val prompt = capturedPrompt("e-client2.jsonl", 15) as Prompt.Clarify
        assertFalse(prompt.batch)
        assertEquals(listOf("red (Recommended)", "blue"), prompt.questions.single().choices)
        val answers = mapOf(prompt.key to ClarifyAnswer(picked = listOf("blue")))
        val call = single(PromptReplies.of(session, prompt, PromptAnswer.Answers(answers)))
        assertEquals("clarify.respond", call.method)
        assertEquals(capturedParams("e-client2.jsonl", 20), call.params)
        assertEquals(setOf("session_id", "execution_generation", "prompt_id", "answer"), call.params.keys)
    }

    @Test
    fun clarifyAnswersAreEncodedAsHermesReadsThem() {
        val single = ClarifyQuestion("q", "Pick", listOf("a", "b"), multiSelect = false)
        val multi = single.copy(multiSelect = true)
        val open = ClarifyQuestion("q", "Why?", emptyList(), multiSelect = false)
        assertEquals("b", ClarifyAnswer(picked = listOf("b")).wire(single))
        assertEquals("my own", ClarifyAnswer(other = "  my own ").wire(single))
        assertEquals("""["a","b"]""", ClarifyAnswer(picked = listOf("a", "b")).wire(multi))
        assertEquals("""["a","extra \"x\""]""", ClarifyAnswer(picked = listOf("a"), other = "extra \"x\"").wire(multi))
        assertEquals("because", ClarifyAnswer(other = "because").wire(open))
        assertNull(ClarifyAnswer().wire(single))
        assertNull(ClarifyAnswer(other = "  ").wire(multi))
    }

    @Test
    fun anEmptyClarifyAnswerSendsNothing() {
        val prompt = capturedPrompt("e-client2.jsonl", 15)
        assertTrue(PromptReplies.of(session, prompt, PromptAnswer.Answers(emptyMap())).isEmpty())
    }

    @Test
    fun aBatchClarifyIsLockedQuestionByQuestion() {
        val prompt = Prompts.fromRequest(
            request(
                "srq-0123456789ab",
                "clarify",
                """{"session_id":"s","questions":[{"qid":"q0","question":"Which env?","choices":["staging","prod"],"multi_select":true},
                   {"qid":"q1","question":"Why?"},{"qid":"q2","question":"Done?","choices":["yes","no"]}],"answers":{"q2":"yes"}}""",
            ),
        ) as Prompt.Clarify
        assertTrue(prompt.batch)
        assertEquals(listOf("q0", "q1", "q2"), prompt.questions.map { it.qid })
        assertEquals(mapOf("q2" to "yes"), prompt.locked)
        val replies = PromptReplies.of("s", prompt, PromptAnswer.Answers(mapOf("q0" to ClarifyAnswer(picked = listOf("prod")))))
        // Already locked q2 is not sent again; q1 left empty is locked as skipped (null).
        assertEquals(
            listOf(
                PromptReply.Call("clarify.lock", obj("""{"request_id":"srq-0123456789ab","question_id":"q0","answer":"[\"prod\"]"}""")),
                PromptReply.Call("clarify.lock", JsonObject(mapOf("request_id" to JsonPrimitive("srq-0123456789ab"), "question_id" to JsonPrimitive("q1"), "answer" to JsonNull))),
            ),
            replies,
        )
        // ClarifyLockParams has no session_id (prompt_voice.py:265-269): unknown keys are refused.
        replies.forEach { assertEquals(setOf("request_id", "question_id", "answer"), (it as PromptReply.Call).params.keys) }
        assertTrue(PromptReplies.of("s", prompt, PromptAnswer.Answers(emptyMap())).isEmpty())
    }

    // ── sudo / secret (Q51) ───────────────────────────────────────────────────────────────────────

    @Test
    fun sudoIsAnsweredWithItsValueInOneResponseFrame() {
        val prompt = Prompts.fromRequest(request("srq-aaaaaaaaaaaa", "sudo", """{"session_id":"s","command":"sudo apt install x"}""")) as Prompt.Secret
        assertEquals(SecretKind.Sudo, prompt.kind)
        assertEquals("sudo apt install x", prompt.label)
        val reply = PromptReplies.of("s", prompt, PromptAnswer.Value("hunter2")).single() as PromptReply.Response
        assertEquals("srq-aaaaaaaaaaaa", reply.requestId)
        // ValueResult (server_requests.py:26-30) in a JSON-RPC response (session_server_requests.py:72-75, :89-96).
        assertEquals("""{"jsonrpc":"2.0","id":"srq-aaaaaaaaaaaa","result":{"value":"hunter2"}}""", RpcFrames.response(reply.requestId, reply.result))
    }

    @Test
    fun aSecretIsAnsweredTheSameWayAndShowsWhatItIsFor() {
        val prompt = Prompts.fromRequest(
            request("srq-bbbbbbbbbbbb", "secret", """{"session_id":"s","env_var":"TAVILY_API_KEY","prompt":"Paste your Tavily key","metadata":{"skill":"web"}}"""),
        ) as Prompt.Secret
        assertEquals(SecretKind.Secret, prompt.kind)
        assertEquals("TAVILY_API_KEY", prompt.label)
        assertEquals("Paste your Tavily key", prompt.prompt)
        val reply = PromptReplies.of("s", prompt, PromptAnswer.Value("tvly-123")).single() as PromptReply.Response
        assertEquals(obj("""{"value":"tvly-123"}"""), reply.result)
        assertNull(Prompts.fromRequest(request("srq-cccccccccccc", "secret", """{"session_id":"s","prompt":"no var"}""")))
    }

    @Test
    fun declineSendsTheEmptyValueAndAnEmptyAnswerSendsNothing() {
        val prompt = Prompts.fromRequest(request("srq-aaaaaaaaaaaa", "sudo", """{"session_id":"s","command":"sudo ls"}"""))!!
        val decline = PromptReplies.of("s", prompt, PromptAnswer.Decline).single() as PromptReply.Response
        assertEquals(obj("""{"value":""}"""), decline.result)
        assertTrue(PromptReplies.of("s", prompt, PromptAnswer.Value("")).isEmpty())
        assertTrue(PromptReplies.of("s", prompt, PromptAnswer.Choose(ApprovalChoice.Once)).isEmpty())
        assertTrue(PromptReplies.of("s", prompt, PromptAnswer.Answers(emptyMap())).isEmpty())
    }

    @Test
    fun noToStringShowsTheValue() {
        val prompt = Prompts.fromRequest(request("srq-aaaaaaaaaaaa", "sudo", """{"session_id":"s","command":"sudo ls"}"""))!!
        val answer = PromptAnswer.Value("hunter2")
        val reply = PromptReplies.of("s", prompt, answer).single()
        listOf(answer.toString(), reply.toString(), prompt.toString()).forEach { assertFalse(it, "hunter2" in it) }
    }

    // ── never answered from the phone ────────────────────────────────────────────────────────────

    @Test
    fun vaultPromptsAreForThePcAndProduceNoFrame() {
        val prompt = Prompts.fromRequest(request("srq-dddddddddddd", "vault.unlock_prompt", """{"session_id":"s","backend":"bitwarden","display_name":"Bitwarden"}"""))
        assertEquals(Prompt.OnPc("srq-dddddddddddd", "vault.unlock_prompt", "Bitwarden"), prompt)
        listOf(PromptAnswer.Value("pw"), PromptAnswer.Decline, PromptAnswer.Choose(ApprovalChoice.Once), PromptAnswer.Answers(emptyMap())).forEach {
            assertTrue(PromptReplies.of("s", prompt!!, it).isEmpty())
        }
    }

    @Test
    fun windowBridgesAreNotPrompts() {
        // Any answer from the phone would settle them for the Desktop too (session_server_requests.py:89-96).
        listOf("terminal.read", "window.read", "preview.read", "preview.act", "tour", "theme.pick", "setup_beat", "approval").forEach { method ->
            assertNull(method, Prompts.fromRequest(request("srq-eeeeeeeeeeee", method, """{"session_id":"s"}""")))
        }
        assertNull(Prompts.fromRequest(InboundFrame.ServerRequest("not-srq", "sudo", obj("""{"session_id":"s"}"""))))
    }

    @Test
    fun openRequestEntriesParseLikeFrames() {
        val entry = obj("""{"id":"srq-aaaaaaaaaaaa","method":"sudo","params":{"session_id":"s","command":"sudo ls"}}""")
        assertEquals(Prompt.Secret("srq-aaaaaaaaaaaa", SecretKind.Sudo, "sudo ls", null), Prompts.fromOpenRequest(entry))
    }
}
