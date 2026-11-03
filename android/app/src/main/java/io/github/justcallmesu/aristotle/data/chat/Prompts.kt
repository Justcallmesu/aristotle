package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.InboundFrame
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The approval choices Hermes may offer (`gateway/session_pending_controls.py:22-34`), in its order. */
enum class ApprovalChoice(val wire: String) {
    Once("once"),
    Deny("deny"),
    Session("session"),
    Always("always");

    companion object {
        fun of(wire: String?): ApprovalChoice? = entries.firstOrNull { it.wire == wire }
    }
}

/** One clarify question; [qid] is the event's `prompt_id` for a single clarify, `q0…` in a batch. */
data class ClarifyQuestion(val qid: String, val question: String, val choices: List<String>, val multiSelect: Boolean)

/** What a password-like request is for. Both are answered with `{value}` (`tui_gateway/contracts/server_requests.py:26-30`). */
enum class SecretKind { Sudo, Secret }

/**
 * Something Hermes waits for in a session (protocol §6). [key] identifies it in the session: the `prompt_id` of
 * an `approval.request` / `clarify.request` event, or the `srq-…` id of a Hermes -> client request.
 */
sealed interface Prompt {
    val key: String

    /** `approval.request` (`session_pending_controls.py:27-36`). [choices]: only what Hermes offers. */
    data class Approval(
        override val key: String,
        val generation: Long,
        val command: String,
        val description: String,
        val choices: List<ApprovalChoice>,
        val deadlineMillis: Long? = null,
    ) : Prompt

    /**
     * A clarify: one `clarify.request` event ([generation] set, answered with `clarify.respond`), or a batch `clarify`
     * server request ([generation] null, answered per question with `clarify.lock`). [locked]: batch answers Hermes
     * already holds (a reconnect replay, `session_server_requests.py:132-133`).
     */
    data class Clarify(
        override val key: String,
        val generation: Long?,
        val questions: List<ClarifyQuestion>,
        val locked: Map<String, String?> = emptyMap(),
        val deadlineMillis: Long? = null,
    ) : Prompt {
        val batch: Boolean get() = generation == null
    }

    /**
     * `sudo` (`{command}`) or `secret` (`{env_var, prompt, metadata?}`) server request
     * (`tui_gateway/contracts/server_requests.py:206-223`). [label] is the command or the variable name, [prompt]
     * the secret's own explanation. Never holds the value.
     */
    data class Secret(override val key: String, val kind: SecretKind, val label: String, val prompt: String?) : Prompt

    /** A vault prompt (master password, login, one-time code): the phone does not answer it, the PC does. */
    data class OnPc(override val key: String, val method: String, val detail: String?) : Prompt
}

/**
 * Prompts from Hermes' frames. Approval and clarify events and the resume snapshot's `prompts` are the same dict
 * (`session_pending_controls.py:27-64`); server requests and the snapshot's `open_requests` share `{id, method,
 * params}` (`session_server_requests.py:125-134`). Window bridges (`terminal.read`, `preview.*`, `tour`, …) are
 * not prompts: the phone never answers them, since any answer settles them for the Desktop too. Pure.
 */
object Prompts {
    private val VAULT = setOf("vault.unlock_prompt", "vault.save_login", "vault.code")

    /** An `approval.request` / `clarify.request` payload or a snapshot `prompts` entry; [at] dates `timeout_s`. */
    fun fromEvent(payload: JsonObject, at: Long?): Prompt? {
        val id = payload.text("prompt_id")?.takeIf { it.isNotEmpty() } ?: return null
        val generation = payload.number("execution_generation")?.toLong() ?: return null
        val deadline = payload.number("timeout_s")?.let { seconds -> at?.plus((seconds * 1000).toLong()) }
        return when (payload.text("kind")) {
            "approval" -> Prompt.Approval(
                key = id,
                generation = generation,
                command = payload.text("command").orEmpty(),
                description = payload.text("description").orEmpty(),
                choices = strings(payload["choices"]).mapNotNull(ApprovalChoice::of).distinct(),
                deadlineMillis = deadline,
            )
            "clarify" -> Prompt.Clarify(
                key = id,
                generation = generation,
                questions = listOf(ClarifyQuestion(id, payload.text("question").orEmpty(), strings(payload["choices"]), payload.flag("multi_select") == true)),
                deadlineMillis = deadline,
            )
            else -> null
        }
    }

    fun fromRequest(request: InboundFrame.ServerRequest): Prompt? = fromRequest(request.id, request.method, request.params)

    /** A server request frame or an `open_requests` entry (`{id, method, params}`). */
    fun fromOpenRequest(entry: JsonObject): Prompt? {
        val id = entry.text("id") ?: return null
        val method = entry.text("method") ?: return null
        return fromRequest(id, method, entry["params"].asObject() ?: JsonObject(emptyMap()))
    }

    private fun fromRequest(id: String, method: String, params: JsonObject): Prompt? {
        if (!id.startsWith("srq-")) return null
        return when (method) {
            "sudo" -> Prompt.Secret(id, SecretKind.Sudo, params.text("command").orEmpty(), null)
            "secret" -> params.text("env_var")?.let { Prompt.Secret(id, SecretKind.Secret, it, params.text("prompt")?.ifBlank { null }) }
            "clarify" -> batch(id, params)
            in VAULT -> Prompt.OnPc(id, method, params.text("display_name") ?: params.text("site"))
            else -> null
        }
    }

    /** `ClarifyRequestParams` (`tui_gateway/contracts/server_requests.py:36-47`). */
    private fun batch(id: String, params: JsonObject): Prompt.Clarify? {
        val questions = (params["questions"] as? JsonArray).orEmpty().mapNotNull { element ->
            val question = element.asObject() ?: return@mapNotNull null
            val qid = question.text("qid")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            ClarifyQuestion(qid, question.text("question").orEmpty(), strings(question["choices"]), question.flag("multi_select") == true)
        }
        if (questions.isEmpty()) return null
        val locked = params["answers"].asObject().orEmpty().mapValues { (_, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content }
        return Prompt.Clarify(id, generation = null, questions = questions, locked = locked)
    }

    private fun strings(element: Any?): List<String> =
        (element as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}

/** The user's answer to one clarify question: the picked choice labels and/or the "Other answer" text. */
data class ClarifyAnswer(val picked: List<String> = emptyList(), val other: String = "") {
    /**
     * The `answer` string Hermes reads (`tools/clarify_tool.py:37-47`, `tools/clarify_gateway.py:213-230`): single
     * select = the label or the typed text; multi select = a JSON array of labels (the typed text as one more entry).
     * Null when nothing was given.
     */
    fun wire(question: ClarifyQuestion): String? {
        val typed = other.trim()
        if (!question.multiSelect) return typed.ifEmpty { null } ?: picked.firstOrNull()
        val all = picked + listOfNotNull(typed.ifEmpty { null })
        return if (all.isEmpty()) null else JsonArray(all.map(::JsonPrimitive)).toString()
    }
}

/** What the user chose in a prompt sheet. */
sealed interface PromptAnswer {
    data class Choose(val choice: ApprovalChoice) : PromptAnswer

    /** By question id; questions left out are sent as skipped in a batch. */
    data class Answers(val answers: Map<String, ClarifyAnswer>) : PromptAnswer

    /** A sudo password or secret value. Not a data class: [toString] never shows it. */
    class Value(val value: String) : PromptAnswer {
        override fun toString(): String = "Value(<redacted>)"
    }

    /** The user declines a sudo/secret request (`''` = skipped, `server_requests.py:26-30`). */
    data object Decline : PromptAnswer
}

/** One frame to send for an answer. */
sealed interface PromptReply {
    /** A JSON-RPC call; its result tells whether this phone's answer counted. */
    data class Call(val method: String, val params: JsonObject) : PromptReply

    /** A response to a Hermes -> client request; no reply comes. [toString] never shows the result (it may hold a password). */
    class Response(val requestId: String, val result: JsonObject) : PromptReply {
        override fun toString(): String = "Response($requestId, <redacted>)"
    }
}

/**
 * The frames that answer a prompt, exactly as Hermes checks them (protocol §6). Empty when the answer does not fit
 * the prompt (a choice Hermes did not offer, nothing answered, a vault prompt). Pure.
 */
object PromptReplies {
    fun of(sessionId: String, prompt: Prompt, answer: PromptAnswer): List<PromptReply> = when (prompt) {
        is Prompt.Approval -> approval(sessionId, prompt, answer)
        is Prompt.Clarify -> (answer as? PromptAnswer.Answers)?.let { if (prompt.batch) locks(prompt, it) else respond(sessionId, prompt, it) }.orEmpty()
        is Prompt.Secret -> secret(prompt, answer)
        is Prompt.OnPc -> emptyList()
    }

    /** `approval.respond`: exactly these four keys (`gateway/session_controls.py:925-929`), a choice in `choices` (`session_pending_controls.py:95`). */
    private fun approval(sessionId: String, prompt: Prompt.Approval, answer: PromptAnswer): List<PromptReply> {
        val choice = (answer as? PromptAnswer.Choose)?.choice?.takeIf { it in prompt.choices } ?: return emptyList()
        return listOf(
            PromptReply.Call(
                "approval.respond",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("execution_generation", prompt.generation)
                    put("prompt_id", prompt.key)
                    put("choice", choice.wire)
                },
            ),
        )
    }

    /** `clarify.respond`: exactly these four keys (`gateway/session_controls.py:931-935`). */
    private fun respond(sessionId: String, prompt: Prompt.Clarify, answer: PromptAnswer.Answers): List<PromptReply> {
        val question = prompt.questions.firstOrNull() ?: return emptyList()
        val generation = prompt.generation ?: return emptyList()
        val text = answer.answers[question.qid]?.wire(question) ?: return emptyList()
        return listOf(
            PromptReply.Call(
                "clarify.respond",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("execution_generation", generation)
                    put("prompt_id", prompt.key)
                    put("answer", text)
                },
            ),
        )
    }

    /**
     * Batch: `clarify.lock {request_id, question_id, answer}` per question not yet locked, in order; the last lock
     * settles the request (`gateway/session_clarify_lock.py:15-38`, `tui_gateway/contracts/prompt_voice.py:265-269`;
     * the Desktop does the same, `apps/desktop/src/store/clarify.ts:325-333`). A question left out is locked as
     * skipped (`null`). Nothing when no question was answered.
     */
    private fun locks(prompt: Prompt.Clarify, answer: PromptAnswer.Answers): List<PromptReply> {
        val open = prompt.questions.filter { it.qid !in prompt.locked }
        val texts = open.map { it to answer.answers[it.qid]?.wire(it) }
        if (texts.none { it.second != null }) return emptyList()
        return texts.map { (question, text) ->
            PromptReply.Call(
                "clarify.lock",
                buildJsonObject {
                    put("request_id", prompt.key)
                    put("question_id", question.qid)
                    put("answer", text?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        }
    }

    /**
     * `{"jsonrpc":"2.0","id":"srq-…","result":{"value":"…"}}` (`ValueResult`, `tui_gateway/contracts/server_requests.py:26-30`;
     * read at `gateway/session_server_requests.py:89-96`). Decline = `value: ""`, which Hermes reads as skipped
     * (`gateway/session_sudo_prompt.py:22-26`, `gateway/session_secret_prompt.py:45-46`). An empty value is never sent as an answer.
     */
    private fun secret(prompt: Prompt.Secret, answer: PromptAnswer): List<PromptReply> {
        val value = when (answer) {
            is PromptAnswer.Value -> answer.value.takeIf { it.isNotEmpty() } ?: return emptyList()
            PromptAnswer.Decline -> ""
            else -> return emptyList()
        }
        return listOf(PromptReply.Response(prompt.key, buildJsonObject { put("value", value) }))
    }
}
