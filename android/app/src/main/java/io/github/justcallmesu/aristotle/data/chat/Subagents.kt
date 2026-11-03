package io.github.justcallmesu.aristotle.data.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where a delegated child stands. The wire statuses are `queued`, `running`, `completed`, `failed`, `error`, `timeout`,
 * `interrupted` (`tui_gateway/contracts/common.py:189-205`); `error`/`timeout` read as [Failed] and `cancelled` as
 * [Interrupted], as on the Desktop (`apps/desktop/src/store/subagents.ts:90-112`).
 */
enum class SubagentStatus {
    Queued, Running, Completed, Failed, Interrupted;

    val live: Boolean get() = this == Queued || this == Running
}

/** One relayed activity line of a child: a tool call, a batch of tool names, a thought, or its final summary. */
data class SubagentLine(val kind: Kind, val text: String, val error: Boolean = false) {
    enum class Kind { Progress, Summary, Thinking, Tool }
}

/**
 * One delegated child as the parent session's `subagent.*` events describe it (protocol §2.2). [lines] is the short
 * activity reel (the Desktop keeps the last 24, `subagents.ts:50`); [mirror] is the same events as chat items, the
 * projection Hermes' child-session mirror makes (`tui_gateway/agent_callbacks.py:38-95`), shown read-only when the
 * child is opened. [startedAtMillis] is the phone's wall clock when the child was first seen (or Hermes'
 * `started_at` from `subagent.list`), so the elapsed time ticks without a server clock.
 */
data class Subagent(
    val id: String,
    val goal: String,
    val status: SubagentStatus,
    val startedAtMillis: Long,
    val parentId: String? = null,
    val childSessionId: String? = null,
    val delegationId: String? = null,
    val model: String? = null,
    val taskIndex: Int = 0,
    val taskCount: Int = 1,
    val durationSeconds: Double? = null,
    val summary: String? = null,
    val currentTool: String? = null,
    val lines: List<SubagentLine> = emptyList(),
    val mirror: List<ChatItem> = emptyList(),
    val mirrorNext: Long = 0,
    val mirrorOpenTool: String? = null,
) {
    val lastLine: SubagentLine? get() = lines.lastOrNull()
}

/**
 * The session's children, oldest first. [retired]: children that already ended; a late start or a `subagent.list`
 * row never brings them back (`subagents.ts:56-75`). [version] moves on every change, so a `subagent.list` answer can
 * tell that live events overtook it.
 */
data class SubagentRoster(
    val items: List<Subagent> = emptyList(),
    val retired: Set<String> = emptySet(),
    val version: Long = 0,
) {
    fun byId(id: String): Subagent? = items.firstOrNull { it.id == id }
    val anyLive: Boolean get() = items.any { it.status.live }
}

/** A `subagent.list` answer taken while the roster was at [baseVersion]; [atMillis] is the phone's wall clock. */
data class SubagentSnapshot(val children: List<JsonObject>, val baseVersion: Long, val atMillis: Long)

/**
 * The Desktop's subagent store (`apps/desktop/src/store/subagents.ts`) as pure functions over [SubagentRoster]. Unknown
 * payload keys are ignored; a missing key keeps what the child had.
 */
object Subagents {
    /** The events that move a child (`apps/desktop/src/app/session/hooks/use-message-stream/utils.ts:150-157`). */
    val EVENT_TYPES = setOf(
        "subagent.spawn_requested", "subagent.start", "subagent.thinking", "subagent.tool", "subagent.progress", "subagent.complete",
    )

    private const val COMPLETE = "subagent.complete"
    private const val MAX_LINES = 24
    private const val MAX_MIRROR = 200
    const val PREVIEW_MAX = 220
    private const val TOOL_PREVIEW_MAX = 96
    private val WHITESPACE = Regex("\\s+")

    /**
     * One `subagent.*` event (`subagents.ts:378-398`). Only `spawn_requested` and `start` create a child; anything
     * about an unknown or ended one is dropped (Hermes keeps relaying until the child is reaped).
     */
    fun onEvent(roster: SubagentRoster, type: String, payload: JsonObject, at: Long): SubagentRoster {
        val id = idOf(payload)
        if (id in roster.retired) return roster
        val index = roster.items.indexOfFirst { it.id == id }
        val previous = roster.items.getOrNull(index)
        if (previous == null && type != "subagent.spawn_requested" && type != "subagent.start") return roster
        if (previous != null && !previous.status.live) return roster
        val next = mirror(project(payload, previous, type, at), type, payload)
        val items = if (index < 0) roster.items + next else roster.items.toMutableList().also { it[index] = next }
        return changed(roster, items)
    }

    /**
     * A new turn starts (`message.start`): the last turn's finished children leave the list, children still running
     * stay (a background delegation outlives its turn; `subagents.ts:332-359`, called at
     * `use-message-stream/gateway-event/message-stream.ts:167`).
     */
    fun pruneFinished(roster: SubagentRoster): SubagentRoster {
        val kept = roster.items.filter { it.status.live }
        return if (kept.size == roster.items.size) roster else changed(roster, kept)
    }

    /**
     * A `subagent.list` answer (`gateway/session_ancillary.py:263-266`): ended children it no longer lists stay, running
     * ones it does not list are gone; every listed child is added or refreshed unless it ended (`subagents.ts:250-289`).
     * Dropped when live events changed the roster while the call was out (`use-subagent-snapshot.ts:39-55`).
     */
    fun reconcile(roster: SubagentRoster, snapshot: SubagentSnapshot): SubagentRoster {
        if (snapshot.baseVersion != roster.version) return roster
        val listed = snapshot.children.mapNotNull { it.text("subagent_id")?.ifEmpty { null } }.toSet()
        val next = roster.items.filter { !it.status.live || it.id in listed }.toMutableList()
        for (child in snapshot.children) {
            val id = child.text("subagent_id")?.ifEmpty { null } ?: continue
            if (id in roster.retired) continue
            val index = next.indexOfFirst { it.id == id }
            val previous = next.getOrNull(index)
            if (previous != null && !previous.status.live) continue
            var projected = project(child, previous, type = "", at = snapshot.atMillis)
            val started = child.number("started_at")?.let { (it * 1000).toLong() }?.takeIf { it > 0 }
            projected = projected.copy(startedAtMillis = started ?: previous?.startedAtMillis ?: projected.startedAtMillis)
            // A roster names the last tool, not one running now: it seeds an empty reel only.
            val lastTool = child.text("last_tool")?.ifEmpty { null }
            if (projected.lines.isEmpty() && lastTool != null) projected = projected.copy(lines = listOf(SubagentLine(SubagentLine.Kind.Tool, formatTool(lastTool))))
            if (index < 0) next += projected else next[index] = projected
        }
        return if (next == roster.items) roster else changed(roster, next)
    }

    /** The Desktop's id: `subagent_id`, else parent, task index and goal (`subagents.ts:149-150`). */
    fun idOf(payload: JsonObject): String =
        payload.text("subagent_id")?.ifEmpty { null } ?: "${payload.text("parent_id")?.ifEmpty { null } ?: "root"}:${payload.int("task_index") ?: 0}:${payload.text("goal").orEmpty()}"

    /**
     * A wire status. A `subagent.complete` is terminal by definition, so an unknown or still-active status reads as
     * failed rather than spin forever (`subagents.ts:90-112`).
     */
    fun status(raw: String?, terminalEvent: Boolean): SubagentStatus = when (raw) {
        "completed" -> SubagentStatus.Completed
        "failed", "error", "timeout" -> SubagentStatus.Failed
        "interrupted", "cancelled", "canceled" -> SubagentStatus.Interrupted
        else -> when {
            terminalEvent -> SubagentStatus.Failed
            raw == "queued" -> SubagentStatus.Queued
            else -> SubagentStatus.Running
        }
    }

    /** Whitespace runs to one space, cut at [max] with an ellipsis (`subagents.ts:114-122`). */
    fun compact(text: String?, max: Int = PREVIEW_MAX): String {
        val line = text.orEmpty().replace(WHITESPACE, " ").trim()
        return if (line.length > max) line.take(max - 1) + "…" else line
    }

    /** `read_file` -> `Read File`, with a short argument preview: `Read File("src/a.kt")` (`subagents.ts:124-130`). */
    fun formatTool(name: String, preview: String? = null): String {
        val label = name.split('_').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }.ifEmpty { name }
        val snippet = compact(preview, TOOL_PREVIEW_MAX)
        return if (snippet.isEmpty()) label else "$label(\"$snippet\")"
    }

    // ── one child from one payload (`subagents.ts:171-242`) ──────────────────────────────────────

    private fun project(payload: JsonObject, previous: Subagent?, type: String, at: Long): Subagent {
        val status = status(payload.text("status"), terminalEvent = type == COMPLETE)
        val tool = payload.text("tool_name")?.ifEmpty { null }
        val lines = linesOf(payload, status, type).fold(previous?.lines.orEmpty(), ::appendLine)
        return Subagent(
            id = previous?.id ?: idOf(payload),
            goal = payload.text("goal")?.ifEmpty { null } ?: previous?.goal ?: "Subagent",
            status = status,
            startedAtMillis = previous?.startedAtMillis ?: at,
            parentId = payload.text("parent_id")?.ifEmpty { null } ?: previous?.parentId,
            childSessionId = payload.text("child_session_id")?.ifEmpty { null } ?: previous?.childSessionId,
            delegationId = payload.text("delegation_id")?.ifEmpty { null } ?: previous?.delegationId,
            model = payload.text("model")?.ifEmpty { null } ?: previous?.model,
            taskIndex = payload.int("task_index") ?: previous?.taskIndex ?: 0,
            taskCount = payload.int("task_count") ?: previous?.taskCount ?: 1,
            durationSeconds = payload.number("duration_seconds") ?: previous?.durationSeconds,
            summary = payload.text("summary")?.ifEmpty { null } ?: timeoutSummary(payload) ?: previous?.summary,
            currentTool = if (status.live) tool ?: previous?.currentTool else null,
            lines = lines,
            mirror = previous?.mirror.orEmpty(),
            mirrorNext = previous?.mirrorNext ?: 0,
            mirrorOpenTool = previous?.mirrorOpenTool,
        )
    }

    private fun linesOf(payload: JsonObject, status: SubagentStatus, type: String): List<SubagentLine> {
        val out = mutableListOf<SubagentLine>()
        val tool = payload.text("tool_name")?.ifEmpty { null }
        val preview = payload.text("tool_preview")?.ifEmpty { null } ?: payload.text("text")
        val text = compact(payload.text("text")?.ifEmpty { null } ?: preview)
        val error = truthy(payload["error"])
        (payload["output_tail"] as? JsonArray).orEmpty().forEach { element ->
            val entry = element.asObject() ?: return@forEach
            val entryTool = entry.text("tool")?.ifEmpty { null }
            val line = if (entryTool != null) formatTool(entryTool, entry.text("preview")) else compact(entry.text("preview"))
            if (line.isNotEmpty()) out += SubagentLine(if (entryTool != null) SubagentLine.Kind.Tool else SubagentLine.Kind.Progress, line, entry.flag("is_error") == true)
        }
        if (tool != null) out += SubagentLine(SubagentLine.Kind.Tool, formatTool(tool, preview), error)
        if (type == "subagent.progress" && text.isNotEmpty()) out += SubagentLine(SubagentLine.Kind.Progress, text, error)
        if (type == "subagent.thinking" && text.isNotEmpty()) out += SubagentLine(SubagentLine.Kind.Thinking, text)
        val summary = compact(payload.text("summary")?.ifEmpty { null } ?: payload.text("text")?.ifEmpty { null } ?: timeoutSummary(payload))
        if (!status.live && summary.isNotEmpty()) out += SubagentLine(SubagentLine.Kind.Summary, summary, status == SubagentStatus.Failed)
        return out
    }

    /** The same line twice in a row is kept once; the reel keeps its last [MAX_LINES]. */
    private fun appendLine(lines: List<SubagentLine>, line: SubagentLine): List<SubagentLine> =
        if (lines.lastOrNull() == line) lines else (lines + line).takeLast(MAX_LINES)

    /** A hard child timeout carries no summary, only `duration_seconds` (`subagents.ts:162-169`). */
    private fun timeoutSummary(payload: JsonObject): String? {
        if (payload.text("status") != "timeout") return null
        val seconds = payload.number("duration_seconds")
        return "Timed out after ${seconds?.let { formatSeconds(it) } ?: "?"}s"
    }

    private fun formatSeconds(value: Double): String = if (value == Math.floor(value)) value.toLong().toString() else value.toString()

    // ── the child-session mirror (`tui_gateway/agent_callbacks.py:38-95`) ─────────────────────────

    /**
     * The events as the child's own chat would show them: the goal (`start`) and thoughts (`thinking`) as text, each
     * tool call as a card that ends when the next one starts, and the summary when it ends. `progress` (batched tool
     * names) is not mirrored there either.
     */
    private fun mirror(child: Subagent, type: String, payload: JsonObject): Subagent {
        val text = payload.text("text").orEmpty()
        return when (type) {
            "subagent.start" -> if (text.isEmpty()) child else child.addMirror { ChatItem.Assistant(it, text) }
            "subagent.thinking" -> if (text.isEmpty()) child else child.appendThought(text)
            "subagent.tool" -> {
                val name = payload.text("tool_name")?.ifEmpty { null } ?: "tool"
                val summary = ToolText.summary(payload.text("tool_preview") ?: text, null)
                val closed = child.closeMirrorTool()
                val key = "${child.id}#m${closed.mirrorNext}"
                closed.addMirror { ChatItem.Tool(it, key, name, summary, ToolStatus.Running) }.copy(mirrorOpenTool = key)
            }
            COMPLETE -> {
                val closed = child.closeMirrorTool()
                val summary = payload.text("summary")?.ifEmpty { null } ?: text.ifEmpty { null }
                val withSummary = summary?.let { s -> closed.addMirror { ChatItem.Assistant(it, s) } } ?: closed
                when (child.status) {
                    SubagentStatus.Interrupted -> withSummary.addMirror { ChatItem.Notice(it, NoticeKind.Stopped, "") }
                    SubagentStatus.Failed -> withSummary.addMirror { ChatItem.Notice(it, NoticeKind.TurnFailed, "") }
                    else -> withSummary
                }
            }
            else -> child
        }
    }

    private fun Subagent.addMirror(make: (String) -> ChatItem): Subagent {
        val item = make("${id}#m$mirrorNext")
        return copy(mirror = (mirror + item).takeLast(MAX_MIRROR), mirrorNext = mirrorNext + 1)
    }

    /** Consecutive thoughts join one reasoning block, as `reasoning.delta`s do in a chat. */
    private fun Subagent.appendThought(text: String): Subagent {
        val last = mirror.lastOrNull()
        if (last is ChatItem.Reasoning) return copy(mirror = mirror.dropLast(1) + last.copy(text = last.text + text))
        return addMirror { ChatItem.Reasoning(it, text) }
    }

    private fun Subagent.closeMirrorTool(): Subagent {
        val open = mirrorOpenTool ?: return this
        val items = mirror.map { if (it is ChatItem.Tool && it.toolId == open) it.copy(status = ToolStatus.Done) else it }
        return copy(mirror = items, mirrorOpenTool = null)
    }

    private fun changed(roster: SubagentRoster, items: List<Subagent>): SubagentRoster {
        val ended = items.filter { !it.status.live }.map { it.id }
        return SubagentRoster(items, roster.retired + ended, roster.version + 1)
    }

    /** JavaScript's `!!value` for a JSON value: null, false, 0 and "" are false. */
    private fun truthy(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonPrimitive -> if (value.isString) value.content.isNotEmpty() else value.content != "false" && value.content.toDoubleOrNull() != 0.0
        else -> true
    }

    private fun JsonObject.int(key: String): Int? = number(key)?.toInt()
}
