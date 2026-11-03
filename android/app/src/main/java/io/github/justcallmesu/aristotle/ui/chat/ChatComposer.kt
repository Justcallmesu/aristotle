package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.chat.AttachFrames
import io.github.justcallmesu.aristotle.data.chat.Attachment
import io.github.justcallmesu.aristotle.data.chat.AttachmentRules
import io.github.justcallmesu.aristotle.data.chat.AttachmentSource
import io.github.justcallmesu.aristotle.data.chat.AttachmentStatus
import io.github.justcallmesu.aristotle.data.chat.ChatDrafts
import io.github.justcallmesu.aristotle.data.chat.ComposerCall
import io.github.justcallmesu.aristotle.data.chat.ComposerFrames
import io.github.justcallmesu.aristotle.data.chat.PickKind
import io.github.justcallmesu.aristotle.data.chat.Preparation
import io.github.justcallmesu.aristotle.data.chat.SlashMenu
import io.github.justcallmesu.aristotle.data.chat.Staged
import io.github.justcallmesu.aristotle.data.chat.Upload
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
    /** Reads and prepares picked items (T16); null: attaching is off (tests that do not need it). */
    private val source: AttachmentSource? = null,
) {
    fun edit(text: String) {
        updateComposer { it.copy(draft = text) }
        drafts?.put(sessionId, text)
    }

    /** The ViewModel replaces the field's text (a picked slash command, T15); the field takes it with the cursor at the end. */
    fun replaceDraft(text: String) {
        updateComposer { it.copy(draft = text, draftEpoch = it.draftEpoch + 1) }
        drafts?.put(sessionId, text)
    }

    /**
     * A turn a slash command asked for (a skill's expanded [message], T15): submitted with the submission id the
     * caller made before the command ran ([id], reused when the same command goes again), as the Desktop does (`use-prompt-actions/slash.ts:502-517`); the bubble shows [display]. While a turn runs
     * Hermes queues it like any submit.
     */
    fun submitDirective(display: String, message: String, id: String) {
        val kind = if (state().feed.chat.running) OutgoingKind.Queue else OutgoingKind.Send
        val item = Outgoing(id, display, kind, OutgoingStatus.Sending, wire = message)
        updateComposer { it.copy(outbox = it.outbox + item, keptIds = it.keptIds - display) }
        deliverSubmit(item)
    }

    /** Idle: a new turn. */
    fun send() = submit(ComposerMode.Idle, OutgoingKind.Send)

    /** Busy: runs after the current turn (and after anything queued before it). Allowed while a prompt waits. */
    fun queue() = submit(ComposerMode.Busy, OutgoingKind.Queue)

    // ── attachments (T16) ─────────────────────────────────────────────────────────────────────────

    /**
     * Items picked with [kind]: each shows at once as a chip while it is prepared off the main thread. Past
     * [AttachmentRules.MAX_ATTACHMENTS] the rest are left out and the user is told.
     */
    fun attach(uris: List<String>, kind: PickKind) {
        val source = source ?: return
        val free = (AttachmentRules.MAX_ATTACHMENTS - state().composer.attachments.size).coerceAtLeast(0)
        if (uris.size > free) updateComposer { it.copy(message = ComposerMessage.TooManyAttachments(AttachmentRules.MAX_ATTACHMENTS)) }
        uris.take(free).forEach { uri ->
            val item = Attachment(newId(), "", kind)
            updateComposer { it.copy(attachments = it.attachments + item) }
            work.launch {
                val result = source.prepare(uri, kind)
                var kept = false
                updateComposer { c ->
                    kept = c.attachments.any { it.id == item.id }
                    c.copy(attachments = c.attachments.map { if (it.id == item.id) prepared(it, result) else it })
                }
                // Removed while it was being prepared: its bytes are not needed.
                if (!kept && result is Preparation.Ready) source.discard(result.prepared.handle)
            }
        }
    }

    /** A chip's ✕: the item leaves the composer and its prepared bytes go. */
    fun removeAttachment(id: String) {
        val item = state().composer.attachments.firstOrNull { it.id == id } ?: return
        updateComposer { c -> c.copy(attachments = c.attachments.filter { it.id != id }) }
        item.prepared?.let { source?.discard(it.handle) }
    }

    fun cameraTarget(): String? = source?.cameraTarget()

    fun cameraUnavailable() = updateComposer { it.copy(message = ComposerMessage.CameraUnavailable) }

    private fun prepared(item: Attachment, result: Preparation): Attachment = when (result) {
        is Preparation.Ready -> item.copy(name = result.prepared.name, status = AttachmentStatus.Ready, prepared = result.prepared)
        is Preparation.Refused -> item.copy(name = result.name, status = AttachmentStatus.Refused(result.problem))
    }

    /** Busy: joins the running turn. Not while a prompt waits: the turn is parked and could not take it. */
    fun steer() {
        val ui = state()
        val composer = ui.composer
        // A steer carries words only (`session.steer` params, protocol §5): attachments go with Send or Queue.
        if (!ComposerLogic.armed(composer, ComposerMode.Busy, clock()) || composer.blocked || SlashMenu.isCommand(composer.draft) || composer.attachments.isNotEmpty()) return
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
            replace(item.copy(status = OutgoingStatus.Sending, attachments = item.attachments.map { it.copy(upload = if (it.staged != null) Upload.Sent else Upload.Waiting) }))
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

    /**
     * Removes a failed echo; its words go back into an empty field and its attachments into an empty chip row (with
     * what Hermes already staged), so nothing picked or typed is lost.
     */
    fun dismiss(id: String) {
        val item = state().composer.outbox.firstOrNull { it.id == id } ?: return
        if (!ComposerLogic.failureSeen(item, clock())) return
        var restored: String? = null
        // A submit that may have reached Hermes keeps its id: the same message sent again is then the same admission.
        val keep = item.kind != OutgoingKind.Steer && (item.status as? OutgoingStatus.Failed)?.failure == SendFailure.NoAnswer
        updateComposer { c ->
            val kept = if (keep) c.keptIds + (Outbox.keepKey(item.text, item.attachments) to item.id) else c.keptIds
            val rest = c.copy(
                outbox = c.outbox.filter { it.id != id },
                keptIds = kept,
                attachments = c.attachments.ifEmpty { item.attachments.map { it.copy(upload = Upload.Waiting) } },
            )
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
        // A command runs through the ViewModel's slash path, never as a message (T15).
        if (SlashMenu.isCommand(composer.draft)) return
        if (!ComposerLogic.hasContent(composer)) return
        val text = composer.draft.trim()
        val attachments = composer.attachments.map { it.copy(upload = if (it.staged != null) Upload.Sent else Upload.Waiting) }
        val key = Outbox.keepKey(text, composer.attachments)
        val kept = composer.keptIds[key]
        val item = Outgoing(kept ?: newId(), text, kind, OutgoingStatus.Sending, attachments = attachments)
        takeDraft(item)
        if (kept != null) updateComposer { it.copy(keptIds = it.keptIds - key) }
        deliverSubmit(item)
    }

    /**
     * Stages the attachments not yet staged, one call each and in order (each is one frame, protocol §12), then submits
     * the prompt with Hermes' answers (T16). An upload that fails stops there: the prompt was not sent, and Retry
     * goes on from the first attachment not staged, with the same `submission_id`.
     */
    private fun deliverSubmit(item: Outgoing) {
        work.launch {
            val staged = stageAll(item.id) ?: return@launch
            val current = state().composer.outbox.firstOrNull { it.id == item.id } ?: return@launch
            val text = AttachFrames.promptText(current.wire ?: current.text, staged)
            val call = ComposerFrames.submit(sessionId, text, current.id, staged.filterIsInstance<Staged.Image>())
            val outcome = attempt(call)
            var admission: String? = null
            updateItem(item.id) { latest, ui ->
                when (outcome) {
                    is Attempt.Answered -> Outbox.submitted(latest, outcome.result, ui.feed.chat).also { admission = (it?.status as? OutgoingStatus.Sent)?.admissionId }
                    is Attempt.Failed -> Outbox.afterSubmitFailure(Outbox.failed(latest, outcome.error, clock()))
                }
            }
            val images = Outbox.sentImages(current)
            admission?.takeIf { images.isNotEmpty() }?.let { id -> updateComposer { it.copy(sentImages = it.sentImages + (id to images)) } }
        }
    }

    /** Hermes' answers for every attachment of [id] in order, or null when one did not stage (the echo then says why). */
    private suspend fun stageAll(id: String): List<Staged>? {
        val item = state().composer.outbox.firstOrNull { it.id == id } ?: return null
        val staged = mutableListOf<Staged>()
        for (attachment in item.attachments) {
            attachment.staged?.let {
                staged += it
                continue
            }
            staged += stage(id, attachment) ?: return null
        }
        return staged
    }

    private suspend fun stage(id: String, attachment: Attachment): Staged? {
        setUpload(id, attachment.id, Upload.Sending)
        val prepared = attachment.prepared
        val bytes = prepared?.let { source?.read(it.handle) } ?: return attachFailed(id, attachment, AttachCause.Gone)
        val call = AttachFrames.stage(sessionId, prepared, bytes)
        // Checked when it was prepared; a frame the relay would refuse (close 1009) is never written.
        if (AttachFrames.frameBytes(call) > AttachmentRules.MAX_FRAME_BYTES - PROFILE_RESERVE) return attachFailed(id, attachment, AttachCause.TooLarge)
        return when (val outcome = attempt(call)) {
            is Attempt.Answered -> AttachFrames.staged(outcome.result, prepared.image)?.also { staged ->
                updateAttachment(id, attachment.id) { it.copy(staged = staged, upload = Upload.Sent) }
            } ?: attachFailed(id, attachment, AttachCause.Refused, AttachFrames.refusal(outcome.result))
            is Attempt.Failed -> if (outcome.error.error != null) {
                attachFailed(id, attachment, AttachCause.Refused, reasonOf(outcome.error))
            } else {
                attachFailed(id, attachment, AttachCause.NotReached)
            }
        }
    }

    private fun attachFailed(id: String, attachment: Attachment, cause: AttachCause, reason: String? = null): Staged? {
        val failure = SendFailure.AttachFailed(attachment.name, cause, reason)
        updateItem(id) { current, _ ->
            current.copy(
                status = OutgoingStatus.Failed(failure, clock()),
                attachments = current.attachments.map { if (it.id == attachment.id) it.copy(upload = Upload.Failed) else it },
            )
        }
        return null
    }

    private fun setUpload(id: String, attachmentId: String, upload: Upload) = updateAttachment(id, attachmentId) { it.copy(upload = upload) }

    private fun updateAttachment(id: String, attachmentId: String, transform: (Attachment) -> Attachment) = updateItem(id) { current, _ ->
        current.copy(attachments = current.attachments.map { if (it.id == attachmentId) transform(it) else it })
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

    /** The words and attachments leave the composer for the outbox in one step: one tap can never send them twice. */
    private fun takeDraft(item: Outgoing) {
        updateComposer { it.copy(draft = "", draftEpoch = it.draftEpoch + 1, outbox = it.outbox + item, attachments = emptyList()) }
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

    private fun armedOnline(composer: ComposerState): Boolean = ComposerLogic.armedOnline(composer, clock())

    private fun reasonOf(error: RpcException): String = error.error?.message?.ifBlank { null } ?: error.message.orEmpty()

    private companion object {
        /** `session.interrupt` for a claim that already settled (`gateway/session_controls.py:880-886`). */
        const val STALE_GENERATION = "stale_generation"

        /** Room left in a frame for the `profile` key [io.github.justcallmesu.aristotle.data.ProfileLink] adds. */
        const val PROFILE_RESERVE = 512
    }
}
