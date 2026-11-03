package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.HermesJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** How a tool call reads on its card: the one-line summary and the output text. Pure. */
object ToolText {
    const val SUMMARY_MAX = 200

    /** Argument keys that say what a call is about, most telling first. */
    private val SUMMARY_KEYS = listOf("command", "path", "file_path", "query", "url", "pattern", "name", "prompt", "text")

    private val pretty = Json { prettyPrint = true }

    /** Hermes' own one-liner (`context`, e.g. "sleep 20 + 1 command") when given, else the most telling argument. */
    fun summary(context: String?, args: JsonObject?): String {
        val line = context?.takeIf { it.isNotBlank() }
            ?: args?.let { SUMMARY_KEYS.firstNotNullOfOrNull(it::text) ?: firstText(it) ?: it.takeIf { a -> a.isNotEmpty() }?.toString() }
            ?: ""
        return oneLine(line)
    }

    /** `function.arguments` of a stored tool call: a JSON object as a string. */
    fun argsOf(arguments: JsonElement?): JsonObject? = when (arguments) {
        is JsonObject -> arguments
        is JsonPrimitive -> arguments.contentOrNull?.let(::parseObject)
        else -> null
    }

    /**
     * The text a tool card shows. Hermes tools answer JSON like `{"output": "…", "exit_code": 0, "error": null}`
     * (often as a string): the output, then the error and a non-zero exit code. Other JSON is pretty-printed;
     * plain text stays as it is. [diff] (`inline_diff`) follows the result.
     */
    fun output(result: JsonElement?, resultText: String? = null, diff: String? = null): String {
        val body = resultText?.takeIf { it.isNotBlank() } ?: format(result)
        return listOf(body, diff.orEmpty()).filter { it.isNotBlank() }.joinToString("\n")
    }

    /** A stored result that reports a failure (non-empty `error`, non-zero `exit_code`); history has no `is_error`. */
    fun looksFailed(result: JsonElement?): Boolean {
        val obj = asObject(result) ?: return false
        val error = obj["error"]
        val exit = (obj["exit_code"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toLongOrNull()
        return (error != null && error !is JsonNull && !(error is JsonPrimitive && error.contentOrNull.isNullOrBlank())) || (exit != null && exit != 0L)
    }

    private fun format(result: JsonElement?): String {
        val obj = asObject(result)
        if (obj != null) return formatObject(obj)
        return when (result) {
            null, JsonNull -> ""
            is JsonPrimitive -> if (result.isString) parseArray(result.content)?.let(::prettyJson) ?: result.content else result.content
            is JsonArray -> prettyJson(result)
            else -> result.toString()
        }
    }

    private fun formatObject(obj: JsonObject): String {
        val output = obj.text("output") ?: obj.text("content") ?: obj.text("result")
        if (output == null) return prettyJson(obj)
        val error = obj.text("error")?.takeIf { it.isNotBlank() }
        val exit = (obj["exit_code"] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toLongOrNull()?.takeIf { it != 0L }
        return listOfNotNull(output.takeIf { it.isNotEmpty() }, error, exit?.let { "exit_code: $it" }).joinToString("\n")
    }

    private fun asObject(result: JsonElement?): JsonObject? = when (result) {
        is JsonObject -> result
        is JsonPrimitive -> if (result.isString) parseObject(result.content) else null
        else -> null
    }

    private fun parseObject(text: String): JsonObject? =
        if (text.trimStart().startsWith("{")) runCatching { HermesJson.parseToJsonElement(text) as? JsonObject }.getOrNull() else null

    private fun parseArray(text: String): JsonArray? =
        if (text.trimStart().startsWith("[")) runCatching { HermesJson.parseToJsonElement(text) as? JsonArray }.getOrNull() else null

    private fun prettyJson(element: JsonElement): String = pretty.encodeToString(JsonElement.serializer(), element)

    private fun firstText(args: JsonObject): String? = args.values.firstNotNullOfOrNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf(String::isNotBlank) }

    private fun oneLine(text: String): String {
        val line = text.replace(WHITESPACE, " ").trim()
        return if (line.length <= SUMMARY_MAX) line else line.take(SUMMARY_MAX - 1) + "…"
    }

    private val WHITESPACE = Regex("\\s+")
}
