package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import io.github.justcallmesu.aristotle.data.chat.ComposerCall
import io.github.justcallmesu.aristotle.data.chat.ComposerFrames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * The composer's actions (T13) for one session, on the [ChatViewModel]'s state. Every action is a user tap: nothing
 * here sends by itself, so a reconnect never sends anything again (Retry is a tap too, and a submit's Retry reuses
 * its `submission_id`). Each tap is dropped unless its control has meant the same thing for [ComposerLogic.ARM_MS].
 */
internal class ChatComposer(
    private val sessionId: String,
    private val link: RelayLink,
    private val drafts: ChatDrafts?,
    private val clock: () -> Long,
    private val newId: () -> String,
    private val work: CoroutineScope,
    private val state: () -> ChatUiState,
    private val change: ((ChatUiState) -> ChatUiState) -> Unit,
) {
    fun edit(text: String) {
        updateComposer { it.copy(draft = text) }
        drafts?.put(sessionId, text)
    }

    /** Idle: a new turn. */
    fun send() = submit(ComposerMode.Idle, OutgoingKind.Send)

    /** Busy: runs after the current turn (and after anything queued before it). Allowed while a prompt waits. */
    fun queue() = submit(ComposerMode.Busy, OutgoingKind.Queue)

    /** Busy: joins the running turn. Not while a prompt waits: the turn is parked and could not take it. */
    fun steer() {
        val ui = state()
        val composer = ui.composer
        if (!ComposerLogic.armed(composer, ComposerMode.Busy, clock()) || composer.blocked) return
        val generation = ui.feed.chat.generation ?: return
        val text = composer.draft.trim().ifEmpty { return }
        val item = Outgoing(newId(), text, OutgoingKind.Steer, OutgoingStatus.Sending)
        takeDraft(item)
        deliverSteer(item, generation)
    }

    /** Busy: interrupts the running turn. No confirmation (as on the Desktop); only the armed control counts. */
    fun stop() {
        val ui = state()
        if (!ComposerLogic.armed(ui.composer, ComposerMode.Busy, clock()) || ui.composer.stopping) return
        val generation = ui.feed.chat.generation ?: run {
            updateComposer { it.copy(message = ComposerMessage.StopUnavailable) }
            return
        }
        updateComposer { it.copy(stopping = true) }
        val call = ComposerFrames.interrupt(sessionId, generation)
        work.launch {
            val message = when (val outcome = attempt(call)) {
                is Attempt.Answered -> null
                // The turn ended before the Stop arrived: nothing left to stop.
                is Attempt.Failed -> if (outcome.error.error?.message == STALE_GENERATION) null else ComposerMessage.StopFailed(reasonOf(outcome.error))
            }
            updateComposer { it.copy(stopping = false, message = message ?: it.message) }
        }
    }

    /** A queued row's ✕ (Hermes' queue, so the Desktop's row goes too). */
    fun cancelQueued(admissionId: String) {
        val composer = state().composer
        if (!armedOnline(composer) || admissionId in composer.cancelling) return
        updateComposer { it.copy(cancelling = it.cancelling + admissionId) }
        val call = ComposerFrames.cancelQueued(sessionId, admissionId)
        work.launch {
            val failed = attempt(call) as? Attempt.Failed
            updateComposer { it.copy(cancelling = it.cancelling - admissionId, message = failed?.let { f -> ComposerMessage.CancelFailed(reasonOf(f.error)) } ?: it.message) }
        }
    }

    /**
     * Retry of a failed echo. A submit goes out again with its own `submission_id`: if Hermes had it after all, it
     * answers with that admission and nothing runs twice. A steer that surely did not arrive is steered again while
     * the turn runs, queued while a prompt waits, or sent as a new message once the turn is over.
     */
    fun retry(id: String) {
        val ui = state()
        val composer = ui.composer
        val item = composer.outbox.firstOrNull { it.id == id } ?: return
        if (!item.retryable || !ComposerLogic.failureSeen(item, clock()) || !armedOnline(composer)) return
        if (item.kind != OutgoingKind.Steer) {
            replace(item.copy(status = OutgoingStatus.Sending))
            deliverSubmit(item)
            return
        }
        val generation = ui.feed.chat.generation
        when {
            composer.mode == ComposerMode.Busy && !composer.blocked && generation != null -> {
                replace(item.copy(status = OutgoingStatus.Sending))
                deliverSteer(item, generation)
            }
            else -> {
                val kind = if (composer.mode == ComposerMode.Busy) OutgoingKind.Queue else OutgoingKind.Send
                val resent = Outgoing(newId(), item.text, kind, OutgoingStatus.Sending)
                updateComposer { c -> c.copy(outbox = c.outbox.map { if (it.id == id) resent else it }) }
                deliverSubmit(resent)
            }
        }
    }

    /** Removes a failed echo; its words go back into an empty field so nothing typed is lost. */
    fun dismiss(id: String) {
        val item = state().composer.outbox.firstOrNull { it.id == id } ?: return
        if (!ComposerLogic.failureSeen(item, clock())) return
        var restored: String? = null
        // A submit that may have reached Hermes keeps its id: the same text sent again is then the same admission.
        val keep = item.kind != OutgoingKind.Steer && (item.status as? OutgoingStatus.Failed)?.failure == SendFailure.NoAnswer
        updateComposer { c ->
            val rest = c.copy(outbox = c.outbox.filter { it.id != id }, keptIds = if (keep) c.keptIds + (item.text to item.id) else c.keptIds)
            if (c.draft.isNotBlank()) rest else rest.copy(draft = item.text, draftEpoch = c.draftEpoch + 1).also { restored = item.text }
        }
        restored?.let { drafts?.put(sessionId, it) }
    }

    fun messageShown() = updateComposer { it.copy(message = null) }

    // ── delivery ──────────────────────────────────────────────────────────────────────────────────

    private fun submit(mode: ComposerMode, kind: OutgoingKind) {
        val composer = state().composer
        if (!ComposerLogic.armed(composer, mode, clock())) return
        if (kind == OutgoingKind.Send && composer.blocked) return
        val text = composer.draft.trim().ifEmpty { return }
        val kept = composer.keptIds[text]
        val item = Outgoing(kept ?: newId(), text, kind, OutgoingStatus.Sending)
        takeDraft(item)
        if (kept != null) updateComposer { it.copy(keptIds = it.keptIds - text) }
        deliverSubmit(item)
    }

    private fun deliverSubmit(item: Outgoing) {
        val call = ComposerFrames.submit(sessionId, item.text, item.id)
        work.launch {
            val outcome = attempt(call)
            updateItem(item.id) { current, ui ->
                when (outcome) {
                    is Attempt.Answered -> Outbox.submitted(current, outcome.result, ui.feed.chat)
                    is Attempt.Failed -> Outbox.failed(current, outcome.error, clock())
                }
            }
        }
    }

    private fun deliverSteer(item: Outgoing, generation: Long) {
        val call = ComposerFrames.steer(sessionId, generation, item.text)
        work.launch {
            val outcome = attempt(call)
            updateItem(item.id) { current, ui ->
                when (outcome) {
                    is Attempt.Answered -> Outbox.steered(current, outcome.result, ui.feed.chat, clock())
                    is Attempt.Failed -> Outbox.failed(current, outcome.error, clock())
                }
            }
        }
    }

    private sealed interface Attempt {
        data class Answered(val result: JsonElement) : Attempt
        data class Failed(val error: RpcException) : Attempt
    }

    private suspend fun attempt(call: ComposerCall): Attempt = try {
        Attempt.Answered(link.call(call.method, call.params))
    } catch (e: RpcException) {
        Attempt.Failed(e)
    }

    // ── state ─────────────────────────────────────────────────────────────────────────────────────

    /** The words leave the field for the outbox in one step: one tap can never send them twice. */
    private fun takeDraft(item: Outgoing) {
        updateComposer { it.copy(draft = "", draftEpoch = it.draftEpoch + 1, outbox = it.outbox + item) }
        drafts?.put(sessionId, "")
    }

    private fun replace(item: Outgoing) = updateComposer { c -> c.copy(outbox = c.outbox.map { if (it.id == item.id) item else it }) }

    /** [transform] the echo [id] if it is still shown (Hermes' own record may have replaced it meanwhile); null drops it. */
    private fun updateItem(id: String, transform: (Outgoing, ChatUiState) -> Outgoing?) = change { ui ->
        val current = ui.composer.outbox.firstOrNull { it.id == id } ?: return@change ui
        val next = transform(current, ui)
        val outbox = if (next == null) ui.composer.outbox.filter { it.id != id } else ui.composer.outbox.map { if (it.id == id) next else it }
        ui.copy(composer = ui.composer.copy(outbox = outbox))
    }

    private fun updateComposer(transform: (ComposerState) -> ComposerState) = change { it.copy(composer = transform(it.composer)) }

    /** The composer is usable (idle or busy, not covered) and has been for [ComposerLogic.ARM_MS]. */
    private fun armedOnline(composer: ComposerState): Boolean {
        val now = clock()
        return ComposerLogic.armed(composer, ComposerMode.Idle, now) || ComposerLogic.armed(composer, ComposerMode.Busy, now)
    }

    private fun reasonOf(error: RpcException): String = error.error?.message?.ifBlank { null } ?: error.message.orEmpty()

    private companion object {
        /** `session.interrupt` for a claim that already settled (`gateway/session_controls.py:880-886`). */
        const val STALE_GENERATION = "stale_generation"
    }
}
