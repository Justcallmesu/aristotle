package io.github.justcallmesu.aristotle.data.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The stored transcript of a `session.resume` result (`messages`) as chat items. The rows are Hermes'
 * stored conversation in OpenAI form (`hermes_state_messages.py` `_rows_to_conversation`, read 2026-11-03; no
 * captured frame carries rows, the spike resumed with `omit_messages`): `role`, `content` (text or a list of
 * parts), `reasoning`, `tool_calls[{id, function{name, arguments}}]`, tool rows with `tool_call_id`,
 * `tool_name`, and `row_id`. A tool row fills the card its assistant row opened. Pure.
 */
object HistoryMapper {
    fun items(messages: JsonArray?): List<ChatItem> {
        val items = mutableListOf<ChatItem>()
        val cards = HashMap<String, Int>()
        val toolKeys = HashSet<String>()
        messages.orEmpty().forEachIndexed { index, element ->
            val row = element.asObject() ?: return@forEachIndexed
            if (modelOnly(row)) return@forEachIndexed
            val key = "h-${row.number("row_id")?.toLong() ?: "i$index"}"
            when (row.text("role")) {
                "user" -> textOf(row["content"])?.let { items += ChatItem.User(key, it) }
                "assistant" -> assistant(row, key, items, cards, toolKeys)
                "tool" -> toolResult(row, key, items, cards)
            }
        }
        return items
    }

    private fun assistant(row: JsonObject, key: String, items: MutableList<ChatItem>, cards: MutableMap<String, Int>, toolKeys: MutableSet<String>) {
        val reasoning = row.text("reasoning") ?: row.text("reasoning_content")
        if (!reasoning.isNullOrBlank()) items += ChatItem.Reasoning("$key-r", reasoning)
        textOf(row["content"])?.let { items += ChatItem.Assistant(key, it) }
        (row["tool_calls"] as? JsonArray).orEmpty().forEach { element ->
            val call = element.asObject() ?: return@forEach
            val id = call.text("id")?.takeIf { it.isNotEmpty() } ?: return@forEach
            val function = call["function"].asObject()
            val card = ChatItem.Tool(
                key = ChatReducer.toolKey(id),
                toolId = id,
                name = function?.text("name") ?: "tool",
                summary = ToolText.summary(null, ToolText.argsOf(function?.get("arguments"))),
                status = ToolStatus.Done,
            )
            cards[id] = items.size
            // Keys must stay unique for the list; a provider may reuse a call id in another turn.
            items += if (toolKeys.add(card.key)) card else card.copy(key = "$key-${card.key}")
        }
    }

    private fun toolResult(row: JsonObject, key: String, items: MutableList<ChatItem>, cards: Map<String, Int>) {
        val id = row.text("tool_call_id").orEmpty()
        val content = row["content"]
        val status = if (ToolText.looksFailed(content)) ToolStatus.Failed else ToolStatus.Done
        val output = ToolText.output(content)
        val index = cards[id]
        val card = index?.let { items[it] as? ChatItem.Tool }
        if (index != null && card != null) {
            items[index] = card.copy(output = output, status = status)
        } else {
            val name = row.text("tool_name") ?: row.text("name") ?: "tool"
            // A result without its call (an older build's row): its own card, keyed by the row.
            items += ChatItem.Tool(key, id, name, "", status, output)
        }
    }

    /** A micro-compaction merge: the model reads it, nobody typed it (Hermes `session_controls.model_only`). */
    private fun modelOnly(row: JsonObject): Boolean = row["display_metadata"].asObject()?.flag("model_only") == true

    /** Text of a string content, or of the `text` parts of a list content; null when there is none. */
    private fun textOf(content: JsonElement?): String? {
        val text = when (content) {
            is JsonPrimitive -> if (content.isString) content.content else null
            is JsonArray -> content.mapNotNull { part -> part.asObject()?.takeIf { it.text("type") == "text" }?.text("text") }.joinToString("\n")
            else -> null
        }
        return text?.takeIf { it.isNotBlank() }
    }
}
