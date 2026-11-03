package io.github.justcallmesu.aristotle.data.chat

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One row of the chat transcript. [key] is stable for the row's life (LazyColumn key). */
sealed interface ChatItem {
    val key: String

    data class User(override val key: String, val text: String) : ChatItem

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

    /** A tool call card. [summary] is the one-line argument summary, [output] the text shown in the body. */
    data class Tool(
        override val key: String,
        val toolId: String,
        val name: String,
        val summary: String,
        val status: ToolStatus,
        val output: String = "",
        val durationSeconds: Double? = null,
        val progress: String? = null,
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
    /** Approval / clarify prompts still open (answered in T12). */
    val openPrompts: Set<String> = emptySet(),
    val replayEpoch: String? = null,
    val lastSeq: Long? = null,
    val turn: TurnCursor? = null,
    /** Admissions already shown as a user message (or already in the stored history). */
    val shownAdmissions: Set<String> = emptySet(),
    val nextKey: Long = 0,
) {
    val title: String? get() = infoText("title")
    val model: String? get() = infoText("model")
    val profileName: String? get() = infoText("profile_name")

    /** Some item is still receiving deltas. */
    val streaming: Boolean
        get() = items.lastOrNull().let { (it is ChatItem.Assistant && it.streaming) || (it is ChatItem.Reasoning && it.streaming) }

    private fun infoText(key: String): String? =
        (info[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.ifEmpty { null }
}

internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.number(key: String): Double? =
    (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toDoubleOrNull()

internal fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()

internal fun JsonElement?.asObject(): JsonObject? = this as? JsonObject
