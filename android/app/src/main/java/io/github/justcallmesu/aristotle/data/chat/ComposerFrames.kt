package io.github.justcallmesu.aristotle.data.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One JSON-RPC call the composer makes: [method] and its exact [params]. */
data class ComposerCall(val method: String, val params: JsonObject)

/**
 * The calls behind Send, Queue, Steer, Stop and a queued row's cancel (protocol §5), each with only the keys Hermes
 * accepts. Never `config.set busy`: the phone picks the busy action per message and leaves the profile's
 * `busy_input_mode` alone (spec Q10). Pure.
 */
object ComposerFrames {
    /**
     * Send (idle) and Queue (busy) are the same call: the canonical owner admits every submit with intent `queue`, so
     * while a turn runs it waits in the session's FIFO (`gateway/session_controls.py:813-833`; EXERCISED
     * `f-busy-client2.jsonl:21-22`). [submissionId] is the idempotency key: the same principal, session and id with
     * the same text replays the first admission instead of adding one (`hermes_state_runtime.py:154-161`), so a Retry
     * reuses it and can never run the prompt twice.
     *
     * [images]: staged images, sent as `attachments: [{path, mime}]` exactly (`gateway/session_ingress_media.py:140-156`
     * admits only paths Hermes staged itself; the Desktop sends the same, `use-prompt-actions/submit.ts:1080-1088`). A
     * retry that stages the same bytes again is still the same admission: Hermes compares the committed bytes'
     * digests, not the staging names (`:158-181`).
     */
    fun submit(sessionId: String, text: String, submissionId: String, images: List<Staged.Image> = emptyList()) = ComposerCall(
        "prompt.submit",
        buildJsonObject {
            put("session_id", sessionId)
            put("text", text)
            put("submission_id", submissionId)
            if (images.isNotEmpty()) {
                put(
                    "attachments",
                    JsonArray(images.map { buildJsonObject { put("path", it.path); put("mime", it.mime) } }),
                )
            }
        },
    )

    /**
     * Steer: the text joins the running turn after its current tool (`gateway/session_busy_controls.py:175-217`;
     * params exactly `session_id`, `text`, `execution_generation` (+ `profile`), text not blank; EXERCISED
     * `f-busy-client2.jsonl:25-26`). It has no idempotency key.
     */
    fun steer(sessionId: String, generation: Long, text: String) = ComposerCall(
        "session.steer",
        buildJsonObject {
            put("session_id", sessionId)
            put("execution_generation", generation)
            put("text", text)
        },
    )

    /** Stop: interrupts the claim [generation] (`gateway/session_controls.py:903-907`; EXERCISED `f-busy-client1.jsonl:70-71`). */
    fun interrupt(sessionId: String, generation: Long) = ComposerCall(
        "session.interrupt",
        buildJsonObject {
            put("session_id", sessionId)
            put("execution_generation", generation)
        },
    )

    /**
     * A queued row's ✕: retires the admission, whoever queued it (`prompt.cancel`, `tui_gateway/contracts/canonical.py:59-65`,
     * `gateway/session_controls.py:872-895`; the Desktop's queue panel sends the same, `apps/desktop/src/app/chat/composer/discard-lost-prompt.ts:23-33`).
     * One that started meanwhile is stopped in the same call.
     */
    fun cancelQueued(sessionId: String, admissionId: String) = ComposerCall(
        "prompt.cancel",
        buildJsonObject {
            put("session_id", sessionId)
            put("admission_id", admissionId)
        },
    )
}
