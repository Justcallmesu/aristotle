package io.github.justcallmesu.aristotle.ui.chat

import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.chat.Attachment
import io.github.justcallmesu.aristotle.data.chat.AttachmentRefs
import io.github.justcallmesu.aristotle.data.chat.AttachmentStatus
import io.github.justcallmesu.aristotle.data.chat.ChatFeedState
import io.github.justcallmesu.aristotle.data.chat.ChatItem
import io.github.justcallmesu.aristotle.data.chat.UserAttachment

/**
 * What the composer offers (design.md §5 composer): [Offline] nothing can be sent (no link, chat not subscribed);
 * [Idle] Send; [Busy] Stop, Queue and Steer; [Covered] the prompt sheet is on screen over it.
 */
enum class ComposerMode { Offline, Idle, Busy, Covered }

/**
 * What the composer's controls mean right now. [blocked]: a prompt waits for this phone's answer, so Send and Steer
 * are off (a parked turn cannot take a steer; the Desktop queues instead, `apps/desktop/src/app/chat/composer/busy-submit-state.ts:37-42`).
 * [queued]: the rows above the field, whose ✕ moves when the list changes.
 */
data class ComposerKey(val mode: ComposerMode, val blocked: Boolean, val queued: List<String>)

/** [key] on screen since [at] (ViewModel clock); [armed] once [ComposerLogic.ARM_MS] passed (the buttons turn on). */
data class ComposerShown(val key: ComposerKey, val at: Long, val armed: Boolean = false)

/** A one-off line (snackbar) about a composer action. */
sealed interface ComposerMessage {
    data class StopFailed(val reason: String) : ComposerMessage

    /** Stop before the turn's generation is known: nothing was sent. */
    data object StopUnavailable : ComposerMessage

    data class CancelFailed(val reason: String) : ComposerMessage

    /** More picked than a message may carry ([limit], `gateway/session_ingress_media.py:123`); the rest were left out (T16). */
    data class TooManyAttachments(val limit: Int) : ComposerMessage

    /** No camera app took the capture intent. */
    data object CameraUnavailable : ComposerMessage
}

/** One row above the field: a queued admission ([admissionId] null while ours is still on its way). */
data class QueuedRow(val key: String, val text: String, val admissionId: String?)

/**
 * The composer (T13). [draft] is the field's text; [draftEpoch] changes only when the ViewModel itself changes the
 * draft (sent, put back), so the field never takes an older value back while the user types.
 */
data class ComposerState(
    val draft: String = "",
    val draftEpoch: Int = 0,
    val outbox: List<Outgoing> = emptyList(),
    val shown: ComposerShown? = null,
    val stopping: Boolean = false,
    val cancelling: Set<String> = emptySet(),
    val message: ComposerMessage? = null,
    /**
     * [Outbox.keepKey] (text and attachments) -> `submission_id` of a dismissed "No answer" submit: sending the same
     * again is the same admission (T13 NIT).
     */
    val keptIds: Map<String, String> = emptyMap(),
    /** The picked items above the field (T16), removable until sent. */
    val attachments: List<Attachment> = emptyList(),
    /**
     * Admission id -> the images this phone sent with it: Hermes' own row for a turn (its `pending` text) names no
     * image, so the bubble takes them from here until the stored history shows them (T16).
     */
    val sentImages: Map<String, List<UserAttachment>> = emptyMap(),
) {
    val mode: ComposerMode get() = shown?.key?.mode ?: ComposerMode.Offline
    val blocked: Boolean get() = shown?.key?.blocked == true
    val armed: Boolean get() = shown?.armed == true
}

/** How the composer follows the chat, and the tap-through guard of its controls. Pure. */
object ComposerLogic {
    /** The same window as the prompt sheet's (T12b): a control that just appeared or changed meaning ignores taps. */
    const val ARM_MS = PromptSheetLogic.ARM_MS

    fun key(link: LinkState, feed: ChatFeedState, sheet: PromptSheetState, outbox: List<Outgoing>): ComposerKey {
        val chat = feed.chat
        val mode = when {
            // Not subscribed on this socket (no link, not loaded, resume refused): Hermes would refuse the submit
            // (`gateway/session_controls.py:815-816`).
            link !is LinkState.Online || !feed.loaded || feed.problem != null -> ComposerMode.Offline
            sheet.visible(chat) -> ComposerMode.Covered
            chat.running -> ComposerMode.Busy
            else -> ComposerMode.Idle
        }
        return ComposerKey(mode, blocked = sheet.current(chat) != null, queued = queuedRows(feed, outbox).map { it.key })
    }

    /** Sets [ComposerState.shown] from [key]; the same key keeps its time. */
    fun track(composer: ComposerState, key: ComposerKey, now: Long): ComposerState =
        if (composer.shown?.key == key) composer else composer.copy(shown = ComposerShown(key, now))

    /** A tap meant for [mode] counts: the composer shows that mode and has, unchanged, for [ARM_MS]. */
    fun armed(composer: ComposerState, mode: ComposerMode, now: Long): Boolean {
        val shown = composer.shown ?: return false
        return shown.key.mode == mode && now - shown.at >= ARM_MS
    }

    /** Every picked item is prepared and none was refused, so a message can carry them. */
    fun attachmentsReady(composer: ComposerState): Boolean = composer.attachments.all { it.ready }

    /** There is something to send: words, or attachments that are all ready. */
    fun hasContent(composer: ComposerState): Boolean =
        attachmentsReady(composer) && (composer.draft.isNotBlank() || composer.attachments.isNotEmpty())

    /**
     * Q69: while busy, Queue, Steer and their hint show only once the field holds something (words, or a picked
     * attachment, which Queue can carry); Stop is always there.
     */
    fun showsBusyExtras(composer: ComposerState): Boolean = composer.draft.isNotBlank() || composer.attachments.isNotEmpty()

    /** A refused item is in the composer: Send stays off until it is removed. */
    fun hasRefused(composer: ComposerState): Boolean = composer.attachments.any { it.status is AttachmentStatus.Refused }

    /**
     * What a user row shows: its own attachments, plus, for the row of a turn this phone sent (`user-<admission>`),
     * the images it sent when the row names none ([ComposerState.sentImages]).
     */
    fun userAttachments(item: ChatItem.User, composer: ComposerState): List<UserAttachment> {
        if (item.attachments.any { it.kind == UserAttachment.Kind.Image }) return item.attachments
        val sent = composer.sentImages[item.key.removePrefix(USER_ROW)].takeIf { item.key.startsWith(USER_ROW) }.orEmpty()
        return item.attachments + sent
    }

    private const val USER_ROW = "user-"

    /** The composer is usable (idle or busy, not covered) and has been for [ARM_MS]: slash commands, a queued row's ✕. */
    fun armedOnline(composer: ComposerState, now: Long): Boolean =
        armed(composer, ComposerMode.Idle, now) || armed(composer, ComposerMode.Busy, now)

    /** A failed echo's Retry or dismiss counts once the failure has been on screen [ARM_MS]. */
    fun failureSeen(item: Outgoing, now: Long): Boolean {
        val failed = item.status as? OutgoingStatus.Failed ?: return false
        return now - failed.at >= ARM_MS
    }

    /**
     * The rows above the field: Hermes' queue (the Desktop shows the same rows), minus a Send of ours that the
     * transcript still shows as its bubble, then our Queue messages Hermes has not listed yet.
     */
    fun queuedRows(feed: ChatFeedState, outbox: List<Outgoing>): List<QueuedRow> {
        val chat = feed.chat
        val bubbles = outbox.filter { it.kind == OutgoingKind.Send }.mapTo(HashSet()) { it.id }
        // A queued prompt's text may start with `@file:` lines (T16): the row shows the words, or the files' names.
        val hermes = chat.queued.filter { it.inputId !in bubbles }.map { QueuedRow(it.admissionId, rowText(it.text), it.admissionId) }
        val listed = hermes.mapTo(HashSet()) { it.key }
        val ours = outbox.filter { it.kind == OutgoingKind.Queue && it.status !is OutgoingStatus.Failed }.mapNotNull { item ->
            val admission = (item.status as? OutgoingStatus.Sent)?.admissionId
            if (admission != null && admission in listed) null else QueuedRow(admission ?: "local-${item.id}", item.label, admission)
        }
        return hermes + ours
    }

    private fun rowText(text: String): String {
        val split = AttachmentRefs.split(text)
        return split.text.ifEmpty { split.attachments.joinToString(", ") { it.label } }
    }

    /** Echoes shown at the end of the transcript: everything not yet Hermes' own, except Queue rows and accepted steers. */
    fun tail(outbox: List<Outgoing>): List<Outgoing> = outbox.filter { item ->
        when (item.kind) {
            OutgoingKind.Send -> true
            OutgoingKind.Queue -> item.status is OutgoingStatus.Failed
            OutgoingKind.Steer -> item.status !is OutgoingStatus.Sent
        }
    }

    /** Accepted steers, each after the chat item it followed (null: before everything). */
    fun anchored(outbox: List<Outgoing>): Map<String?, List<Outgoing>> =
        outbox.filter { it.kind == OutgoingKind.Steer && it.status is OutgoingStatus.Sent }
            .groupBy { (it.status as OutgoingStatus.Sent).anchor }
}
