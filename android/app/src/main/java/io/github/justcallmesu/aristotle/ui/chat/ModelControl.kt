package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.chat.ChatState
import io.github.justcallmesu.aristotle.data.chat.ComposerCall
import io.github.justcallmesu.aristotle.data.chat.ControlFrames
import io.github.justcallmesu.aristotle.data.chat.Fences
import io.github.justcallmesu.aristotle.data.chat.ModelCatalog
import io.github.justcallmesu.aristotle.data.chat.ModelGroup
import io.github.justcallmesu.aristotle.data.chat.PickAnswer
import io.github.justcallmesu.aristotle.data.chat.PickAnswers
import io.github.justcallmesu.aristotle.data.chat.PickRefusal
import io.github.justcallmesu.aristotle.data.chat.asObject
import io.github.justcallmesu.aristotle.data.chat.number
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A one-off line (snackbar) about a model or reasoning pick. */
sealed interface ModelMessage {
    /** A turn runs: [value] applies from the next message (the Desktop's words). */
    data class Deferred(val value: String) : ModelMessage

    /** `revision_conflict` / `stale_generation`: changed elsewhere; the user tries again (never retried by itself). */
    data object Changed : ModelMessage

    data class Refused(val reason: String) : ModelMessage

    /** Sent, but no answer came: the top bar shows what Hermes has. */
    data object NoAnswer : ModelMessage

    /** The session's revision or generation is not known yet (still loading, or offline). */
    data object NotReady : ModelMessage

    /** Hermes asked to confirm without a token: nothing was switched. */
    data object NoToken : ModelMessage
}

/** A guarded model target Hermes asks the user to confirm (cost, data policy, large context). */
data class ModelConfirm(val model: String, val provider: String?, val token: String, val message: String)

/**
 * Fences a refusal named ([revision], [generation]), used while the chat still shows the values it had then
 * ([seenRevision], [seenGeneration]); once the chat moves on, its own values are newer.
 */
data class AdoptedFences(val revision: Long?, val generation: Long?, val seenRevision: Long?, val seenGeneration: Long?)

/** The model and reasoning sheet (T15) and the picks it sends. */
data class ModelSheetState(
    val open: Boolean = false,
    val catalog: ModelCatalog? = null,
    val loading: Boolean = false,
    val problem: String? = null,
    /** A pick is on its way: nothing else is sent until Hermes answers. */
    val sending: Boolean = false,
    val confirm: ModelConfirm? = null,
    /** The model and provider the chat showed when [confirm] was asked, and when: the dialog goes if they move, and arms after [ComposerLogic.ARM_MS]. */
    val confirmFrom: Pair<String?, String?>? = null,
    val confirmAt: Long = 0,
    val message: ModelMessage? = null,
    val adopted: AdoptedFences? = null,
)

/** Pure rules of the model switch. */
object ModelLogic {
    /** [sheet] without its confirmation once the chat's model or provider is not the one it was asked about (changed elsewhere). */
    fun dropStale(sheet: ModelSheetState, chat: ChatState): ModelSheetState =
        if (sheet.confirm != null && sheet.confirmFrom != (chat.model to chat.provider)) sheet.copy(confirm = null, confirmFrom = null) else sheet

    /** The confirmation's \"Switch anyway\" counts once it has been on screen for [ComposerLogic.ARM_MS]. */
    fun confirmArmed(sheet: ModelSheetState, now: Long): Boolean = sheet.confirm != null && now - sheet.confirmAt >= ComposerLogic.ARM_MS

    /**
     * The fences a pick presents: the chat's revision and generation, or what a refusal named while the chat has not
     * moved since. Null when either is unknown: the Desktop refuses to edit then too ("Session revision unavailable",
     * `apps/desktop/src/api/canonical-protocol.ts:336-341`).
     */
    fun fences(chat: ChatState, adopted: AdoptedFences?): Fences? {
        val current = adopted?.takeIf { it.seenRevision == chat.revision && it.seenGeneration == chat.generation }
        val revision = current?.revision ?: chat.revision ?: return null
        val generation = current?.generation ?: chat.generation ?: return null
        return Fences(revision, generation)
    }
}

/**
 * The model and reasoning picks of one session, on the [ChatViewModel]'s state. One pick at a time; a pick that a
 * fence refused is never sent again by itself (T15 brief; the Desktop retries a value pick once at the new fences,
 * `apps/desktop/src/api/canonical-protocol.ts:300-309`, the phone asks the user instead).
 */
internal class ChatModelControl(
    private val sessionId: String,
    private val link: RelayLink,
    private val newId: () -> String,
    private val clock: () -> Long,
    private val work: CoroutineScope,
    private val state: () -> ChatUiState,
    private val change: ((ChatUiState) -> ChatUiState) -> Unit,
) {
    /** The top bar was tapped: the sheet opens and reads the list again (the Desktop refetches on open, `model-picker.tsx:95-99`). */
    fun open() {
        update { it.copy(open = true) }
        load()
    }

    fun close() = update { it.copy(open = false) }

    fun reload() = load()

    fun messageShown() = update { it.copy(message = null) }

    fun cancelConfirm() = update { it.copy(confirm = null, confirmFrom = null) }

    /** A model row; a locked one does nothing. The sheet closes, as the Desktop's picker does (`model-picker.tsx:160-163`). */
    fun pickModel(group: ModelGroup, model: String) {
        if (model in group.unavailable || model !in group.models) return
        update { it.copy(open = false) }
        send(Pick.Model(model, group.slug, confirm = null))
    }

    fun pickReasoning(effort: String) {
        update { it.copy(open = false) }
        send(Pick.Reasoning(effort))
    }

    /** "Switch anyway" on a guarded target: the same pick with Hermes' token (`gateway/session_mutation_model.py:371-372`). */
    fun confirm() {
        val ui = state()
        val asked = ui.model.confirm ?: return
        if (!ModelLogic.confirmArmed(ui.model, clock())) return
        update { it.copy(confirm = null, confirmFrom = null) }
        send(Pick.Model(asked.model, asked.provider, asked.token))
    }

    private sealed interface Pick {
        val label: String

        fun call(sessionId: String, requestId: String, fences: Fences): ComposerCall

        data class Model(val model: String, val provider: String?, val confirm: String?) : Pick {
            override val label get() = model
            override fun call(sessionId: String, requestId: String, fences: Fences) = ControlFrames.model(sessionId, requestId, fences, model, provider, confirm)
        }

        data class Reasoning(val effort: String) : Pick {
            override val label get() = effort
            override fun call(sessionId: String, requestId: String, fences: Fences) = ControlFrames.reasoning(sessionId, requestId, fences, effort)
        }
    }

    private fun send(pick: Pick) {
        val ui = state()
        if (ui.model.sending) return
        val fences = ModelLogic.fences(ui.feed.chat, ui.model.adopted)
        if (ui.composer.mode == ComposerMode.Offline || fences == null) {
            update { it.copy(message = ModelMessage.NotReady) }
            return
        }
        val call = pick.call(sessionId, newId(), fences)
        update { it.copy(sending = true, message = null) }
        work.launch {
            val answer = try {
                Result.success(link.call(call.method, call.params))
            } catch (e: RpcException) {
                Result.failure(e)
            }
            var reload = false
            change { current ->
                val chat = current.feed.chat
                val base = current.model.copy(sending = false)
                val next = answer.fold(
                    onSuccess = { result -> answered(base, pick, PickAnswers.of(result), chat, result.asObject()?.number("revision")?.toLong()) },
                    onFailure = { error ->
                        when (val refusal = PickAnswers.refusal(error as RpcException)) {
                            is PickRefusal.Conflict -> {
                                reload = true
                                base.copy(
                                    adopted = AdoptedFences(refusal.revision, refusal.generation, chat.revision, chat.generation),
                                    message = ModelMessage.Changed,
                                )
                            }
                            is PickRefusal.Refused -> base.copy(message = ModelMessage.Refused(refusal.reason))
                            PickRefusal.NoAnswer -> base.copy(message = ModelMessage.NoAnswer)
                        }
                    },
                )
                current.copy(model = next)
            }
            // The refusal named the new fences; the list's current model is read again too (the session state refresh).
            if (reload) load()
        }
    }

    private fun answered(base: ModelSheetState, pick: Pick, answer: PickAnswer, chat: ChatState, revision: Long?): ModelSheetState = when (answer) {
        // The receipt's revision is the next fence until the chat's own `session.updated` / `session.info` arrives.
        PickAnswer.Applied -> base.copy(adopted = revision?.let { AdoptedFences(it, null, chat.revision, chat.generation) })
        is PickAnswer.Deferred -> base.copy(message = ModelMessage.Deferred(answer.value ?: pick.label))
        PickAnswer.NoToken -> base.copy(message = ModelMessage.NoToken)
        is PickAnswer.Confirm -> when (pick) {
            is Pick.Model -> base.copy(confirm = ModelConfirm(pick.model, pick.provider, answer.token, answer.message), confirmFrom = chat.model to chat.provider, confirmAt = clock())
            is Pick.Reasoning -> base
        }
    }

    private fun load() {
        if (state().model.loading) return
        update { it.copy(loading = true, problem = null) }
        val call = ControlFrames.modelOptions(sessionId)
        work.launch {
            val outcome = try {
                Result.success(ModelCatalog.of(link.call(call.method, call.params)))
            } catch (e: RpcException) {
                Result.failure(e)
            }
            update { sheet ->
                outcome.fold(
                    onSuccess = { sheet.copy(loading = false, catalog = it) },
                    onFailure = { sheet.copy(loading = false, problem = reasonOf(it as RpcException)) },
                )
            }
        }
    }

    private fun reasonOf(error: RpcException): String = error.error?.let { it.reason ?: it.message.ifBlank { null } } ?: error.message.orEmpty()

    private fun update(transform: (ModelSheetState) -> ModelSheetState) = change { it.copy(model = transform(it.model)) }
}
