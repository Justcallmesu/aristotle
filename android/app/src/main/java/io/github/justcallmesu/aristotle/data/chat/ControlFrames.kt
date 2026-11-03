package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.RpcException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The CAS fences a session policy edit presents: [revision] (shape-A `session.info` and the resume snapshot, protocol
 * §2.1) and [generation], the session's execution generation. A policy edit without `expected_generation` is refused
 * `invalid_params` (`gateway/session_mutations.py:149-154`).
 */
data class Fences(val revision: Long, val generation: Long)

/**
 * The calls behind the model and reasoning switch and the slash commands (T15), each with only the keys Hermes
 * accepts. The session's profile is added by [io.github.justcallmesu.aristotle.data.ProfileLink]. Pure.
 */
object ControlFrames {
    /**
     * The model list, as the Desktop's chat picker asks it (`apps/desktop/src/lib/model-options.ts:146-185`:
     * `model.options {session_id, explicit_only:true}`): only the providers this profile configured, with the session's
     * own model as the current one (`gateway/session_config.py:213-236`, keys `profile`, `session_id` and three bools).
     */
    fun modelOptions(sessionId: String) = ComposerCall(
        "model.options",
        buildJsonObject {
            put("session_id", sessionId)
            put("explicit_only", true)
        },
    )

    /**
     * A model pick. The Desktop's picker sends `config.set {key:"model", value:"<model> --provider <slug>"}`
     * (`apps/desktop/src/app/session/hooks/use-model-controls.ts:359-365`), which its canonical adapter sends as this
     * `session.mutate` with the session's revision and generation (`apps/desktop/src/api/canonical-protocol.ts:82-92`,
     * `:329-349`, `:471-474`). Payload keys `model`, `provider`, `confirm` (the owner's token for a guarded target),
     * all non-blank strings (`hermes_state_mutations.py:13-17`).
     */
    fun model(sessionId: String, requestId: String, fences: Fences, model: String, provider: String?, confirm: String? = null) =
        mutate(sessionId, requestId, fences, "model") {
            put("model", model)
            provider?.takeIf { it.isNotBlank() }?.let { put("provider", it) }
            confirm?.let { put("confirm", it) }
        }

    /**
     * A reasoning level. The Desktop sends `config.set {key:"reasoning", session_id, value}`
     * (`apps/desktop/src/lib/reasoning-step.ts:61-70`), which becomes `session.mutate reasoning {effort}`
     * (`apps/desktop/src/api/canonical-protocol.ts:222-234`, `:467-469`). `none` turns thinking off
     * (`hermes_constants.py:1076-1100`, `hermes_state_mutations.py:24-26`).
     */
    fun reasoning(sessionId: String, requestId: String, fences: Fences, effort: String) =
        mutate(sessionId, requestId, fences, "reasoning") { put("effort", effort) }

    private fun mutate(sessionId: String, requestId: String, fences: Fences, operation: String, payload: JsonObjectBuilder.() -> Unit) = ComposerCall(
        "session.mutate",
        buildJsonObject {
            put("session_id", sessionId)
            put("request_id", requestId)
            put("expected_revision", fences.revision)
            put("expected_generation", fences.generation)
            put("operation", operation)
            putJsonObject("payload", payload)
        },
    )

    /**
     * The slash command list, as the Desktop's composer warms it (`apps/desktop/src/app/chat/composer/hooks/use-slash-completions.ts:100-111`):
     * `commands.catalog {session_id}`, so project skills of the session's workspace are listed (`gateway/session_discovery.py:8-35`,
     * keys `profile`, `session_id` only).
     */
    fun commandsCatalog(sessionId: String) = ComposerCall("commands.catalog", buildJsonObject { put("session_id", sessionId) })

    /**
     * Run a typed command on the owner, as the Desktop does for every command it does not handle itself
     * (`apps/desktop/src/app/session/hooks/use-prompt-actions/slash.ts:520-522`: the command without its slash).
     * Keys `session_id`, `profile`, `command` only (`gateway/session_commands.py:148-155`).
     */
    fun slashExec(sessionId: String, command: String) = ComposerCall(
        "slash.exec",
        buildJsonObject {
            put("session_id", sessionId)
            put("command", command.trim().trimStart('/'))
        },
    )
}

/** What Hermes answered to a model or reasoning pick. */
sealed interface PickAnswer {
    /** Applied now; every viewer gets the new policy as `session.info` (`gateway/session_mutations.py:354-359`). */
    data object Applied : PickAnswer

    /**
     * A turn is running: the pick is recorded for the next turn (`gateway/session_mutations.py:133-142`,
     * `gateway/session_deferred_pick.py:59-61`); the Desktop says "<model> applies from your next message"
     * (`apps/desktop/src/app/session/hooks/use-model-controls.ts:367-384`, `apps/desktop/src/i18n/en_model_menu.ts:4`).
     */
    data class Deferred(val value: String?) : PickAnswer

    /**
     * A guarded target (cost, data policy, large context): nothing was written; resending the same pick with [token]
     * as `payload.confirm` applies it (`gateway/session_mutation_model.py:339-378`). The Desktop asks first
     * (`apps/desktop/src/app/session/hooks/use-model-controls.ts:389-448`).
     */
    data class Confirm(val token: String, val message: String, val model: String?) : PickAnswer

    /** Hermes asked to confirm but gave no token to confirm with: the pick is not applied and cannot be confirmed. */
    data object NoToken : PickAnswer
}

/** Why a pick did not apply. */
sealed interface PickRefusal {
    /**
     * `revision_conflict` / `stale_generation`: another viewer changed the session (or a turn moved on). Nothing was
     * written; the refusal names the current fences (`gateway/session_controls.py:844-850`, `:22-35`).
     */
    data class Conflict(val revision: Long?, val generation: Long?) : PickRefusal

    data class Refused(val reason: String) : PickRefusal

    /** The link dropped or nothing answered: it may or may not have applied. */
    data object NoAnswer : PickRefusal
}

object PickAnswers {
    fun of(result: JsonElement?): PickAnswer {
        val answer = result.asObject() ?: return PickAnswer.Applied
        return when (answer.text("status")) {
            "deferred" -> PickAnswer.Deferred(answer.text("model") ?: answer.text("effort"))
            "confirmation_required" -> answer.text("confirm")?.let {
                PickAnswer.Confirm(it, answer.text("confirm_message").orEmpty(), answer.text("target_model"))
            } ?: PickAnswer.NoToken
            else -> PickAnswer.Applied
        }
    }

    fun refusal(error: RpcException): PickRefusal {
        val rpc = error.error ?: return PickRefusal.NoAnswer
        val reason = rpc.reason ?: rpc.message
        if (reason == "revision_conflict" || reason == "stale_generation") {
            return PickRefusal.Conflict(rpc.data?.number("revision")?.toLong(), rpc.data?.number("execution_generation")?.toLong())
        }
        return PickRefusal.Refused(reason.ifBlank { "error ${rpc.code}" })
    }
}
