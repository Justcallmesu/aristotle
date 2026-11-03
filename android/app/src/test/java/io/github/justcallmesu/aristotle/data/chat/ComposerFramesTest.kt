package io.github.justcallmesu.aristotle.data.chat

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The composer's frames against the frames the spike sent to the real Hermes (`c-send.jsonl`, `f-busy-*.jsonl`) and
 * the key sets Hermes accepts (`gateway/session_controls.py:817-824`, `gateway/session_busy_controls.py:176-178`,
 * `tui_gateway/contracts/canonical_sessions.py:265-270`, `tui_gateway/contracts/canonical.py:59-61`).
 */
class ComposerFramesTest {
    private val session = CapturedFrames.SESSION

    private fun sent(file: String, line: Int): Pair<String, JsonObject> {
        val frame = CapturedFrames.lines(file).first { it.line == line && it.dir == "out" }.frame
        return (frame["method"] as kotlinx.serialization.json.JsonPrimitive).content to frame["params"] as JsonObject
    }

    @Test
    fun sendIsTheCapturedPromptSubmit() {
        val (method, params) = sent("c-send.jsonl", 9)
        val call = ComposerFrames.submit(session, "Reply with the single word pong.", "e3fcb7f8355fcc9f2dfdd6871ff7e8ac")
        assertEquals(method, call.method)
        assertEquals(params, call.params)
        // `submission()` refuses unknown keys and a missing submission id (session_controls.py:817-824).
        assertEquals(setOf("session_id", "text", "submission_id"), call.params.keys)
    }

    @Test
    fun queueWhileBusyIsTheSamePromptSubmit() {
        // EXERCISED: a plain submit while a turn ran was queued and ran after it (f-busy-client2.jsonl:21-22, :42).
        val (method, params) = sent("f-busy-client2.jsonl", 21)
        val call = ComposerFrames.submit(session, "Reply with the single word queued.", "72833571b68623ca764e546f0d3e99d1")
        assertEquals(method to params, call.method to call.params)
    }

    @Test
    fun steerIsTheCapturedSessionSteer() {
        val (method, params) = sent("f-busy-client2.jsonl", 25)
        val call = ComposerFrames.steer(session, 6, "When you finish, also add the word steered on its own line.")
        assertEquals(method to params, call.method to call.params)
        // `correct()` accepts only these (+ `profile`) and an int generation (session_busy_controls.py:176-178).
        assertEquals(setOf("session_id", "execution_generation", "text"), call.params.keys)
    }

    @Test
    fun stopIsTheCapturedSessionInterrupt() {
        val (method, params) = sent("f-busy-client1.jsonl", 70)
        val call = ComposerFrames.interrupt(session, 8)
        assertEquals(method to params, call.method to call.params)
        assertEquals(setOf("session_id", "execution_generation"), call.params.keys)
    }

    @Test
    fun cancellingAQueuedRowIsPromptCancel() {
        val call = ComposerFrames.cancelQueued(session, "5060991b63f0419180b3fd120cc7c698")
        assertEquals("prompt.cancel", call.method)
        assertEquals(setOf("session_id", "admission_id"), call.params.keys)
    }
}
