package io.github.justcallmesu.aristotle.data.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One row of the chat transcript. [key] is stable for the row's life (LazyColumn key). */
sealed interface ChatItem {
    val key: String

    /**
     * [steer]: a steer Hermes stored as its own user row mid-turn (`display_kind` steer), not a turn's prompt.
     * [attachments]: the images and files the message names, taken out of [text] ([AttachmentRefs], T16).
     */
    data class User(
        override val key: String,
        val text: String,
        val steer: Boolean = false,
        val attachments: List<UserAttachment> = emptyList(),
    ) : ChatItem {
        companion object {
            /** A user row from Hermes' own text (stored row or `pending` admission), its attachments split off. */
            fun of(key: String, raw: String, steer: Boolean = false): User {
                val split = AttachmentRefs.split(raw)
                return User(key, split.text, steer, split.attachments)
            }
        }
    }

    /** Assistant text (Markdown). [streaming]: deltas still arrive for it; the cursor shows only then. */
    data class Assistant(override val key: String, val text: String, val streaming: Boolean = false) : ChatItem

    /** The model's reasoning. [durationMillis] is known only when it streamed live, never from history. */
    data class Reasoning(
        override val key: String,
        val text: String,
        val streaming: Boolean = false,
        val startedAtMillis: Long? = null,
        val durationMillis: Long? = null,
    ) : ChatItem

    /**
     * A tool call card. [summary] is the one-line argument summary, [output] the text shown in the body. A
     * `delegate_task` call also carries [delegate] (what the call itself says) and [subagents], its children as rows,
     * joined with the live ones (T17, [Delegation]).
     */
    data class Tool(
        override val key: String,
        val toolId: String,
        val name: String,
        val summary: String,
        val status: ToolStatus,
        val output: String = "",
        val durationSeconds: Double? = null,
        val progress: String? = null,
        val delegate: DelegateCall? = null,
        val subagents: List<DelegateRow> = emptyList(),
    ) : ChatItem

    /** A line about the session itself: a stopped turn, a failure, an informational notice. */
    data class Notice(override val key: String, val kind: NoticeKind, val text: String) : ChatItem
}

enum class ToolStatus { Running, Done, Failed }

/** [Stopped]: the turn was interrupted. [TurnFailed]: it ended in an error. [Error]: a session-level `error` event. */
enum class NoticeKind { Stopped, TurnFailed, Error, Info }

/**
 * Where the turn now streaming stands (protocol §2: `message.delta` carries `turn_id`, `segment` and `offset`,
 * the UTF-16 units of that segment already sent). [applied] lets a re-sent chunk be dropped; [textKey] /
 * [reasoningKey] name the items deltas append to while they are the last item.
 */
data class TurnCursor(
    val turnId: String? = null,
    val segment: Int = 0,
    val applied: Int = 0,
    val textKey: String? = null,
    val reasoningKey: String? = null,
    /** The turn's latest text block, open or closed (a transformed final text replaces it). */
    val lastTextKey: String? = null,
    /** Assistant text arrived in this turn (so `message.complete` adds none). */
    val hadText: Boolean = false,
    /** A delta arrived past [applied] (the chat opened mid-turn, or chunks were lost): its block misses its start. */
    val missedStart: Boolean = false,
    /** The text block that misses its start; `message.complete` replaces it with the whole final text. */
    val lateTextKey: String? = null,
)

/**
 * A prompt that is no longer open: `approval.settled` / `clarify.settled` ([reason] null), `request.cancel`
 * ([reason] `resolved`, `timeout`, `interrupted`, …), or gone from a new snapshot. [serial] tells two settles apart.
 */
data class SettledPrompt(val prompt: Prompt, val reason: String?, val serial: Long)

/** What the chat screen shows for one session. Built only by [ChatReducer] and [HistoryMapper]. */
data class ChatState(
    val sessionId: String,
    val items: List<ChatItem> = emptyList(),
    /** `session.info` merged key by key (protocol §2.1); never the system prompt or credential hint. */
    val info: JsonObject = JsonObject(emptyMap()),
    val running: Boolean = false,
    val step: Int? = null,
    /** Hermes' spinner is on (`thinking.delta` with text): the model works but nothing streams yet. */
    val thinking: Boolean = false,
    /** What Hermes waits for in this session, oldest first (protocol §6); the prompt sheet shows the first. */
    val prompts: List<Prompt> = emptyList(),
    /** The prompt that left [prompts] last, and why; the sheet tells "answered elsewhere" from it (T12). */
    val lastSettled: SettledPrompt? = null,
    val replayEpoch: String? = null,
    val lastSeq: Long? = null,
    val turn: TurnCursor? = null,
    /** Admissions already shown as a user message (or already in the stored history). */
    val shownAdmissions: Set<String> = emptySet(),
    /**
     * The execution generation of the latest claim (event envelopes and `session.info`; never moves back within a
     * snapshot). Steer and Stop name it (`gateway/session_busy_controls.py:176-191`, `session_controls.py:903-907`).
     */
    val generation: Long? = null,
    /** Every `pending` admission seen, by its `input_id` (the `submission_id` it was sent with): `admission_id`. */
    val inputs: Map<String, String> = emptyMap(),
    /** How many snapshots built this state: after one, the stored history is the truth for what came before. */
    val snapshots: Int = 0,
    /** The session's delegated children, from its `subagent.*` events and `subagent.list` (T17). */
    val subagents: SubagentRoster = SubagentRoster(),
    val nextKey: Long = 0,
) {
    val title: String? get() = infoText("title")

    /** Hermes' queue for this session (`session.info.pending`, status `queued`), oldest first: the Desktop shows the same. */
    val queued: List<QueuedPrompt>
        get() = (info["pending"] as? JsonArray).orEmpty().mapNotNull { element ->
            val entry = element.asObject()?.takeIf { it.text("status") == "queued" } ?: return@mapNotNull null
            val id = entry.text("admission_id") ?: return@mapNotNull null
            QueuedPrompt(id, entry.text("input_id"), entry.text("text").orEmpty())
        }

    /** Admission ids Hermes lists as pending now (queued, started or steering). */
    val pendingAdmissions: Set<String>
        get() = (info["pending"] as? JsonArray).orEmpty().mapNotNullTo(HashSet()) { it.asObject()?.text("admission_id") }
    val openPrompts: Set<String> get() = prompts.mapTo(LinkedHashSet()) { it.key }
    val model: String? get() = infoText("model")
    val provider: String? get() = infoText("provider")
    val profileName: String? get() = infoText("profile_name")

    /** The session's CAS revision (shape-A `session.info` and the snapshot, protocol §2.1): what `session.mutate` presents. */
    val revision: Long? get() = info.number("revision")?.toLong()

    /** `reasoning_effort` / `reasoning_effort_wire` (`gateway/session_local.py:427-444`); null when not set. */
    val reasoningEffort: String? get() = infoText("reasoning_effort")
    val reasoningWire: String? get() = infoText("reasoning_effort_wire")

    /** Some item is still receiving deltas. */
    val streaming: Boolean
        get() = items.lastOrNull().let { (it is ChatItem.Assistant && it.streaming) || (it is ChatItem.Reasoning && it.streaming) }

    private fun infoText(key: String): String? =
        (info[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.ifEmpty { null }
}

/** One queued admission: [inputId] is the `submission_id` its sender used (ours or the Desktop's). */
data class QueuedPrompt(val admissionId: String, val inputId: String?, val text: String)

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toDoubleOrNull()

internal fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
