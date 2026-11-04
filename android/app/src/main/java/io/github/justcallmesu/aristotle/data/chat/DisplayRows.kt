package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Hermes' typed rows (`display_kind`), shown as the Desktop shows them (TC1). Sources, all read 2026-11-04:
 * - kinds: `apps/desktop/src/types/hermes.ts:720-731`; `hidden` is dropped (`lib/chat-messages/hydration.ts:162-171`, plus a
 *   pre-`hidden` heartbeat wake by its text, `:31-35`); the machine notices `model_switch`, `async_delegation_complete`,
 *   `process_complete`, `auto_continue`, `personality_switch`, `failed_turn` become system rows (`:178-190`, `:507-508`).
 * - title: `display_metadata.display_text` (`:237-241`; the metadata may be raw JSON text, `:192-195`), fixed words for the switch
 *   kinds, a fallback title for the two cards (`:309-336`). `steer` has no case in the Desktop: it stays a user row.
 * - live: `message.start` carries `display_kind` / `display_text` / `text` for an automation turn
 *   (`apps/shared/src/gateway-contract.generated.ts:5649-5655`, `gateway/session_automation.py:437-446`); `pending` entries
 *   (`gateway/session_contract.py:64-72`) and `session.info` do not.
 * Pure.
 */
object DisplayRows {
    const val HIDDEN = "hidden"
    const val PROCESS_COMPLETE = "process_complete"
    const val ASYNC_DELEGATION_COMPLETE = "async_delegation_complete"

    private val HEARTBEAT_WAKE = Regex("^\\[Background process \\S+ heartbeat #\\d+ ")

    /** What a typed row turns into. [Drop] shows nothing; [Plain] is an ordinary row (user, assistant, tool, steer). */
    sealed interface Shown {
        data object Drop : Shown
        data object Plain : Shown
        data class Item(val item: ChatItem) : Shown
    }

    fun isCard(kind: String?): Boolean = kind == PROCESS_COMPLETE || kind == ASYNC_DELEGATION_COMPLETE

    /** A stored row ([HistoryMapper]) or a live frame ([kind], [text], [displayText] as `message.start` carries them). */
    fun shown(key: String, role: String?, kind: String?, metadata: JsonElement?, text: String?): Shown {
        val meta = metadata.asMetadata()
        return when {
            kind == HIDDEN -> Shown.Drop
            role == "user" && kind == null && text != null && HEARTBEAT_WAKE.containsMatchIn(text.trim()) -> Shown.Drop
            isCard(kind) -> Shown.Item(card(key, kind!!, meta?.text("display_text"), meta?.number("task_count")?.toInt(), text.orEmpty()))
            kind == "model_switch" -> Shown.Item(ChatItem.Notice(key, NoticeKind.Info, "model changed"))
            kind == "auto_continue" -> Shown.Item(ChatItem.Notice(key, NoticeKind.Info, "resumed interrupted turn"))
            kind == "personality_switch" -> Shown.Item(ChatItem.Notice(key, NoticeKind.Info, "personality changed"))
            kind == "failed_turn" -> Shown.Item(ChatItem.Notice(key, NoticeKind.TurnFailed, meta?.text("error")?.takeIf { it.isNotBlank() } ?: text.orEmpty().trim()))
            else -> Shown.Plain
        }
    }

    /**
     * The compact card. Title: the producer's `display_text`, else the text's first line, else the Desktop's fallback words.
     * The line is cut for display only by the UI (one line, ellipsized); [ChatItem.Card.text] keeps everything.
     */
    fun card(key: String, kind: String, displayText: String?, taskCount: Int?, text: String): ChatItem.Card {
        val process = kind == PROCESS_COMPLETE
        val title = displayText?.takeIf { it.isNotBlank() }?.let(::oneLine)
            ?: text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?: when {
                process -> "background process finished"
                taskCount == null -> "background agent work finished"
                else -> "$taskCount background agent${if (taskCount == 1) "" else "s"} finished"
            }
        return ChatItem.Card(key, process, title, text.trim())
    }

    private fun oneLine(text: String): String = text.trim().lineSequence().first().trim()

    /** `display_metadata` as an object: a backend older than the Desktop serves it as JSON text (`hydration.ts:192-195`). */
    private fun JsonElement?.asMetadata(): JsonObject? = when (this) {
        is JsonObject -> this
        is JsonPrimitive -> if (isString) runCatching { HermesJson.parseToJsonElement(content) }.getOrNull() as? JsonObject else null
        else -> null
    }
}
