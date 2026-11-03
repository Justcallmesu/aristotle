package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.RpcError
import io.github.justcallmesu.aristotle.data.RpcException
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The T15 frames against the key sets Hermes accepts: `model.options` (`gateway/session_config.py:213-217`),
 * `session.mutate` (`gateway/session_mutations.py:23`, `:148-154`; payloads `hermes_state_mutations.py:13-26`),
 * `commands.catalog` (`gateway/session_discovery.py:11-14`) and `slash.exec` (`gateway/session_commands.py:148-155`).
 */
class ControlFramesTest {
    private val session = "s1"
    private val fences = Fences(revision = 7, generation = 3)

    private fun json(text: String) = HermesJson.parseToJsonElement(text) as JsonObject

    @Test
    fun modelOptionsAsksTheChatPickerList() {
        val call = ControlFrames.modelOptions(session)
        assertEquals("model.options", call.method)
        // `explicit_only` must be a JSON bool, or the owner refuses invalid_params (session_config.py:215-217).
        assertEquals(json("""{"session_id":"s1","explicit_only":true}"""), call.params)
    }

    @Test
    fun aModelPickIsAFencedModelMutation() {
        val call = ControlFrames.model(session, "r1", fences, "gpt-5.1", "openai-codex")
        assertEquals("session.mutate", call.method)
        assertEquals(
            json(
                """{"session_id":"s1","request_id":"r1","expected_revision":7,"expected_generation":3,"operation":"model",
                   "payload":{"model":"gpt-5.1","provider":"openai-codex"}}""",
            ),
            call.params,
        )
        // The owner's closed field set plus the generation a policy edit must name.
        assertEquals(setOf("session_id", "request_id", "expected_revision", "operation", "payload", "expected_generation"), call.params.keys)
    }

    @Test
    fun aConfirmedPickCarriesTheTokenAndABlankProviderIsLeftOut() {
        val call = ControlFrames.model(session, "r2", fences, "big-model", " ", confirm = "tok")
        // Every payload value must be a non-blank string (hermes_state_mutations.py:13-14).
        assertEquals(json("""{"model":"big-model","confirm":"tok"}"""), call.params["payload"])
    }

    @Test
    fun aReasoningPickIsAFencedReasoningMutation() {
        val call = ControlFrames.reasoning(session, "r3", fences, "high")
        assertEquals(
            json("""{"session_id":"s1","request_id":"r3","expected_revision":7,"expected_generation":3,"operation":"reasoning","payload":{"effort":"high"}}"""),
            call.params,
        )
        assertEquals(json("""{"effort":"none"}"""), ControlFrames.reasoning(session, "r4", fences, ReasoningLevels.OFF).params["payload"])
    }

    @Test
    fun theCommandListAndARunNameOnlyTheSession() {
        assertEquals("commands.catalog" to json("""{"session_id":"s1"}"""), ControlFrames.commandsCatalog(session).let { it.method to it.params })
        val run = ControlFrames.slashExec(session, "  /skills pending ")
        assertEquals("slash.exec", run.method)
        // As the Desktop sends it: the command line without its slash (use-prompt-actions/slash.ts:521).
        assertEquals(json("""{"session_id":"s1","command":"skills pending"}"""), run.params)
    }

    // ── answers ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun answersAreReadAsAppliedDeferredOrConfirm() {
        assertEquals(PickAnswer.Applied, PickAnswers.of(json("""{"session_id":"s1","operation":"model","revision":8,"model":"m"}""")))
        // gateway/session_deferred_pick.py:59-61
        assertEquals(
            PickAnswer.Deferred("m2"),
            PickAnswers.of(json("""{"session_id":"s1","operation":"model","status":"deferred","model":"m2","revision":7,"execution_generation":3}""")),
        )
        assertEquals(PickAnswer.Deferred("high"), PickAnswers.of(json("""{"session_id":"s1","operation":"reasoning","status":"deferred","effort":"high"}""")))
        // gateway/session_mutation_model.py:375-378
        assertEquals(
            PickAnswer.Confirm("abc", "This model costs more.", "big"),
            PickAnswers.of(
                json("""{"session_id":"s1","operation":"model","status":"confirmation_required","confirm_required":true,"confirm":"abc",
                        "target_model":"big","target_provider":"p","confirm_message":"This model costs more.","warnings":[]}"""),
            ),
        )
    }

    @Test
    fun aFenceRefusalNamesTheCurrentFences() {
        // gateway/session_controls.py:22-35, :844-850: error.data carries reason, revision and execution_generation.
        val conflict = RpcException(RpcError(4001, "revision_conflict", json("""{"reason":"revision_conflict","revision":9,"execution_generation":4}""")), "x")
        assertEquals(PickRefusal.Conflict(9, 4), PickAnswers.refusal(conflict))
        val stale = RpcException(RpcError(4001, "stale_generation", json("""{"reason":"stale_generation"}""")), "x")
        assertEquals(PickRefusal.Conflict(null, null), PickAnswers.refusal(stale))
        val other = RpcException(RpcError(4001, "model_resolution_failed", json("""{"reason":"model_resolution_failed"}""")), "x")
        assertEquals(PickRefusal.Refused("model_resolution_failed"), PickAnswers.refusal(other))
        assertEquals(PickRefusal.NoAnswer, PickAnswers.refusal(RpcException(null, "closed", linkClosed = true)))
        assertFalse(PickAnswers.refusal(other) is PickRefusal.Conflict)
    }
}
