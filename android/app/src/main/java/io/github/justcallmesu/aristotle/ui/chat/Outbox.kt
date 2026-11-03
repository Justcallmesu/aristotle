package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.chat.Attachment
import io.github.justcallmesu.aristotle.data.chat.ChatState
import io.github.justcallmesu.aristotle.data.chat.Staged
import io.github.justcallmesu.aristotle.data.chat.Upload
import io.github.justcallmesu.aristotle.data.chat.UserAttachment
import io.github.justcallmesu.aristotle.data.chat.asObject
import io.github.justcallmesu.aristotle.data.chat.flag
import io.github.justcallmesu.aristotle.data.chat.text
import kotlinx.serialization.json.JsonElement

/** How the user sent a message: Send (idle), Queue (after the running turn) or Steer (into it). */
enum class OutgoingKind { Send, Queue, Steer }

/** Why a message did not go through. */
sealed interface SendFailure {
    /** No socket took it: Hermes never saw it. */
    data object NotSent : SendFailure

    /** It went out and the link dropped (or nothing answered) before Hermes' answer: it may have arrived. */
    data object NoAnswer : SendFailure

    /** Hermes refused it; [reason] is its error message, [code] its JSON-RPC code (`stale_generation`, `queue_full`, …). */
    data class Refused(val reason: String, val code: Int? = null) : SendFailure

    /** A steer no agent saw: the turn had ended (`rejected` + `delivered: false`, safe to send as a new message). */
    data object TurnEnded : SendFailure

    /** A steer the turn did not take; it may still have reached it, so it is never sent again by itself. */
    data object NotTaken : SendFailure

    /**
     * Attachment [name] did not upload, so the prompt was never sent (T16). [reason]: Hermes' words when it refused.
     */
    data class AttachFailed(val name: String, val cause: AttachCause, val reason: String? = null) : SendFailure
}

/**
 * Why an upload stopped: [NotReached] the PC was not connected or did not answer; [Refused] Hermes did not stage it;
 * [Gone] the prepared bytes are no longer on the phone; [TooLarge] it would not fit one relay frame.
 */
enum class AttachCause { NotReached, Refused, Gone, TooLarge }

sealed interface OutgoingStatus {
    data object Sending : OutgoingStatus

    /**
     * Hermes answered. [admissionId] names a submit's admission; [atSnapshot] is [ChatState.snapshots] then; [anchor]
     * is the chat item an accepted steer follows.
     */
    data class Sent(val admissionId: String?, val atSnapshot: Int, val anchor: String? = null) : OutgoingStatus

    /** [at] on the ViewModel's clock: Retry counts only once it has been on screen (tap-through guard). */
    data class Failed(val failure: SendFailure, val at: Long) : OutgoingStatus
}

/**
 * A message this phone sent, shown at once (optimistic echo) until Hermes' own record of it shows instead.
 * [id] is a submit's `submission_id` (kept for Retry, so a retry is the same admission) or a steer's local id.
 */
data class Outgoing(
    val id: String,
    val text: String,
    val kind: OutgoingKind,
    val status: OutgoingStatus,
    /** What Hermes is sent when it differs from the bubble [text]: a skill command's expanded message (T15). */
    val wire: String? = null,
    /** The images and files the message carries (T16); staged ones keep Hermes' answer for Retry. */
    val attachments: List<Attachment> = emptyList(),
) {
    /** The words, or the attachments' names when there are none (a queued row needs a label). */
    val label: String get() = text.ifEmpty { attachments.joinToString(", ") { it.name } }

    /**
     * Retry is offered when it cannot deliver twice: a submit always (same `submission_id`), a steer only when it
     * surely did not reach the turn (a steer has no idempotency key, `gateway/session_busy_controls.py:176`): Hermes'
     * refusal code 4001 only; a `-32603` crash or a contract violation can follow delivery.
     */
    val retryable: Boolean
        get() {
            val failure = (status as? OutgoingStatus.Failed)?.failure ?: return false
            return kind != OutgoingKind.Steer || failure == SendFailure.NotSent || (failure is SendFailure.Refused && failure.code == Outbox.REFUSED) || failure == SendFailure.TurnEnded
        }
}

/**
 * The outbox: what Hermes' answers do to an [Outgoing], and when Hermes' own record replaces the echo, so a message
 * never shows twice. Hermes has no user-message event; its record is the `pending` admission (`input_id` = our
 * `submission_id`, `admission_id`), which becomes the turn's user row at `message.start` (EXERCISED
 * `c-send.jsonl:9-13`). Pure.
 */
object Outbox {
    /** Hermes' own refusal (`RuntimeStoreError`), raised before the steer reaches the agent. */
    const val REFUSED = 4001

    /**
     * `prompt.submit` answered with an admission receipt (`tui_gateway/contracts/canonical.py:29-38`). `terminal`:
     * a Retry replayed an admission that already ran (the history holds it); `voice_stopped`: nothing was admitted.
     */
    fun submitted(item: Outgoing, result: JsonElement?, chat: ChatState): Outgoing? {
        val receipt = result.asObject()
        if (receipt?.flag("voice_stopped") == true || receipt?.text("status") == "terminal") return null
        return item.copy(status = OutgoingStatus.Sent(receipt?.text("admission_id"), chat.snapshots))
    }

    /**
     * `session.steer` answered (`gateway/session_busy_controls.py:198-217`, `tui_gateway/contracts/canonical_sessions.py:273-287`):
     * `queued` / `redirected` reached the turn and stay as a user row after what was on screen then (the Desktop
     * records an accepted steer as a user message too, `use-prompt-actions/index.ts:885-947`).
     */
    fun steered(item: Outgoing, result: JsonElement?, chat: ChatState, now: Long): Outgoing? {
        val answer = result.asObject()
        if (answer?.flag("voice_stopped") == true) return null
        return when (answer?.text("status")) {
            "queued", "redirected" -> item.copy(status = OutgoingStatus.Sent(null, chat.snapshots, anchor = chat.items.lastOrNull()?.key))
            "rejected" -> item.copy(status = OutgoingStatus.Failed(if (answer.flag("delivered") == false) SendFailure.TurnEnded else SendFailure.NotTaken, now))
            else -> item.copy(status = OutgoingStatus.Failed(SendFailure.NotTaken, now))
        }
    }

    fun failed(item: Outgoing, error: RpcException, now: Long): Outgoing {
        val failure = when {
            !error.written -> SendFailure.NotSent
            error.error != null -> SendFailure.Refused(error.error.message.ifBlank { "error ${error.error.code}" }, error.error.code)
            else -> SendFailure.NoAnswer
        }
        return item.copy(status = OutgoingStatus.Failed(failure, now))
    }

    /**
     * The key a dismissed submit's id is kept under: its words and, when it had any, its attachments (ids, which keep
     * their staged answers when they go back to the composer), so the same message sent again reuses the id.
     */
    fun keepKey(text: String, attachments: List<Attachment>): String =
        if (attachments.isEmpty()) text else text + "\u0000" + attachments.joinToString("\u0000") { it.id }

    /**
     * Hermes refused the prompt itself: a staged image may be gone from its staging folder (Hermes prunes it), so a
     * Retry stages the images again. Safe: Hermes knows a retried admission by the images' bytes, not their staging
     * names (`gateway/session_ingress_media.py:158-181`). A file's `@file:` text is kept: a new name would change the
     * prompt and Hermes would refuse the same `submission_id` (`admission_conflict`).
     */
    fun afterSubmitFailure(item: Outgoing): Outgoing {
        val refused = (item.status as? OutgoingStatus.Failed)?.failure is SendFailure.Refused
        return item.copy(
            attachments = item.attachments.map { a ->
                if (refused && a.staged is Staged.Image) a.copy(staged = null, upload = Upload.Waiting) else a
            },
        )
    }

    /** The images of [item] as a bubble shows them: name and the bytes' digest (for the thumbnail). */
    fun sentImages(item: Outgoing): List<UserAttachment> = item.attachments.mapNotNull { a ->
        a.prepared?.takeIf { it.image }?.let { UserAttachment(UserAttachment.Kind.Image, it.name, it.name, it.sha256) }
    }

    /** Drops every echo whose message Hermes now shows itself; a failed submit Hermes did get counts as sent. */
    fun reconcile(outbox: List<Outgoing>, chat: ChatState): List<Outgoing> {
        if (outbox.isEmpty()) return outbox
        val next = outbox.mapNotNull { if (it.kind == OutgoingKind.Steer) keepSteer(it, chat) else keepSubmit(it, chat) }
        return if (next == outbox) outbox else next
    }

    private fun keepSubmit(item: Outgoing, chat: ChatState): Outgoing? {
        val sent = item.status as? OutgoingStatus.Sent
        val admission = chat.inputs[item.id] ?: sent?.admissionId ?: return item
        val pending = admission in chat.pendingAdmissions
        val queuedNow = chat.queued.any { it.admissionId == admission }
        return when {
            // The turn's own user row shows it (`message.start` with this admission).
            admission in chat.shownAdmissions -> null
            // Hermes' queue row shows it: for Queue at once; for Send only behind another running turn (idle, the
            // queued state lasts until `message.start`, ~20 ms in the captures, and the bubble should not flicker).
            queuedNow && (item.kind == OutgoingKind.Queue || chat.running) -> null
            // A snapshot after the answer: the stored history holds whatever is no longer pending.
            sent != null && chat.snapshots > sent.atSnapshot && !pending -> null
            sent != null -> item
            // Hermes lists it by our submission id: only the answer was lost.
            else -> item.copy(status = OutgoingStatus.Sent(admission, chat.snapshots))
        }
    }

    /** An accepted steer stays after its anchor until the chat is rebuilt from a snapshot (the history is then the truth). */
    private fun keepSteer(item: Outgoing, chat: ChatState): Outgoing? {
        val sent = item.status as? OutgoingStatus.Sent ?: return item
        if (chat.snapshots > sent.atSnapshot) return null
        val anchor = sent.anchor ?: return item
        return if (chat.items.any { it.key == anchor }) item else null
    }
}
