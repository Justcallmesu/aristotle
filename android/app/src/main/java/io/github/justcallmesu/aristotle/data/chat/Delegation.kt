package io.github.justcallmesu.aristotle.data.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * What a `delegate_task` call says about its children on its own: the goals it dispatched (task order) and, once it
 * answered, how each one finished. This is all a rebuilt transcript has (`apps/desktop/src/components/assistant-ui/tool/
 * delegate-model.ts:200-267`). [answered]: the tool result arrived; [dispatched]: it answered `status: "dispatched"`
 * (a background delegation whose children outlive the call).
 */
data class DelegateCall(
    val goals: List<String>,
    val results: List<DelegateResult> = emptyList(),
    val answered: Boolean = false,
    val dispatched: Boolean = false,
) {
    /** The same call once its result came (`tool.complete`, or the stored tool row). */
    fun withResult(result: JsonElement?): DelegateCall {
        val record = Delegation.objectOf(result) ?: JsonObject(emptyMap())
        val results = (record["results"] as? JsonArray).orEmpty().map { DelegateResult.of(Delegation.objectOf(it) ?: JsonObject(emptyMap())) }
        val dispatched = record.text("status") == "dispatched"
        val dispatchedGoals = if (dispatched) (record["goals"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content } else emptyList()
        return copy(goals = goals.ifEmpty { dispatchedGoals }, results = results, answered = true, dispatched = dispatched)
    }
}

/** One finished child in the call's result: its `status` (`ok`, `error`, `timeout`, …), `summary`, time and model. */
data class DelegateResult(val status: String, val summary: String?, val durationSeconds: Double?, val model: String?) {
    companion object {
        fun of(entry: JsonObject) = DelegateResult(
            status = entry.text("status").orEmpty(),
            summary = entry.text("summary")?.ifEmpty { null },
            durationSeconds = entry.number("duration_seconds"),
            model = entry.text("model")?.ifEmpty { null },
        )
    }
}

/**
 * How a child row reads. [Dispatched]: a background delegation's children, seen from a transcript where nothing streams
 * their progress; running, but nothing here watches it, so it must not spin (`delegate-model.ts:190-196`).
 */
enum class DelegateStatus {
    Queued, Running, Dispatched, Completed, Failed, Interrupted;

    val live: Boolean get() = this == Queued || this == Running
}

/**
 * One child under its `delegate_task` card: goal and model, where it stands, how long it ran, its last line.
 * [subagentId] is set when the live roster knows the child, so it can be opened and stopped; [startedAtMillis] lets a
 * running row tick its elapsed time.
 */
data class DelegateRow(
    val key: String,
    val goal: String,
    val status: DelegateStatus,
    val model: String? = null,
    val durationSeconds: Double? = null,
    val lastLine: String? = null,
    val lastLineError: Boolean = false,
    val subagentId: String? = null,
    val startedAtMillis: Long? = null,
)

/** The `delegate_task` card's rows: the call's own view joined with the live children. Pure. */
object Delegation {
    const val TOOL = "delegate_task"

    /** The call a `delegate_task` tool start (or stored call) describes; null for other tools or a call with no goal. */
    fun callOf(name: String, args: JsonObject?): DelegateCall? {
        if (name != TOOL) return null
        val record = args ?: JsonObject(emptyMap())
        // `action` = list / steer / stop: a control call on running children, not a fan-out (`tools/delegate_tool.py:848-860`).
        if (!record.text("action").isNullOrEmpty()) return null
        val tasks = (record["tasks"] as? JsonArray).orEmpty()
        val goals = if (tasks.isNotEmpty()) {
            tasks.mapIndexed { index, task -> objectOf(task)?.text("goal")?.takeIf { it.isNotBlank() } ?: "Task ${index + 1}" }
        } else {
            listOfNotNull(record.text("goal")?.takeIf { it.isNotBlank() })
        }
        return DelegateCall(goals)
    }

    /**
     * The rows a call describes by itself (`delegate-model.ts:247-267`): one per goal; before its answer they read
     * as running, after a background dispatch as dispatched; a result entry settles its row.
     */
    fun rowsOf(call: DelegateCall, toolId: String): List<DelegateRow> {
        val titles = call.goals.ifEmpty { call.results.map { "Delegated task" } }
        val idle = if (call.answered) DelegateStatus.Dispatched else DelegateStatus.Running
        return titles.mapIndexed { index, goal ->
            val entry = call.results.getOrNull(index)
            val summary = entry?.summary?.let { Subagents.compact(it) }?.ifEmpty { null }
            val status = entry?.let { settled(it.status) } ?: idle
            DelegateRow(
                key = "$toolId:$index",
                goal = goal,
                status = status,
                model = entry?.model,
                durationSeconds = entry?.durationSeconds,
                lastLine = summary,
                lastLineError = summary != null && status == DelegateStatus.Failed,
            )
        }
    }

    /**
     * Live children over the call's rows (`delegate-model.ts:281-322`). Live events carry no tool linkage, but they
     * carry the goal verbatim, so a child is claimed by goal; failing that, by task order only when both sides count
     * the same children (a second delegation in the turn must not claim the first one's). Live state wins.
     */
    fun merge(rows: List<DelegateRow>, live: List<Subagent>): List<DelegateRow> {
        if (live.isEmpty()) return rows
        val unclaimed = live.toMutableList()
        fun claim(predicate: (Subagent) -> Boolean): Subagent? = unclaimed.indexOfFirst(predicate).takeIf { it >= 0 }?.let { unclaimed.removeAt(it) }
        val byGoal = rows.map { row -> claim { normalize(it.goal) == normalize(row.goal) } }
        val sameShape = rows.size == live.size
        return rows.mapIndexed { index, row ->
            val matched = byGoal[index] ?: if (sameShape) claim { it.taskIndex == index } else null
            matched?.let { fromSubagent(it, row) } ?: row
        }
    }

    /** The rows of a card now: its call's own, then the live children over them. */
    fun rows(call: DelegateCall, toolId: String, roster: SubagentRoster): List<DelegateRow> = merge(rowsOf(call, toolId), roster.items)

    private fun fromSubagent(child: Subagent, row: DelegateRow): DelegateRow {
        val last = child.lastLine
        return DelegateRow(
            key = row.key,
            goal = child.goal.ifEmpty { row.goal },
            status = when (child.status) {
                SubagentStatus.Queued -> DelegateStatus.Queued
                SubagentStatus.Running -> DelegateStatus.Running
                SubagentStatus.Completed -> DelegateStatus.Completed
                SubagentStatus.Failed -> DelegateStatus.Failed
                SubagentStatus.Interrupted -> DelegateStatus.Interrupted
            },
            model = child.model,
            durationSeconds = child.durationSeconds,
            lastLine = last?.text,
            lastLineError = last?.error == true,
            subagentId = child.id,
            startedAtMillis = child.startedAtMillis,
        )
    }

    /** Anything but a success is a failure: unknown statuses must not hide a timed-out child (`delegate-model.ts:221-227`). */
    private fun settled(status: String): DelegateStatus =
        if (status.isEmpty() || status == "ok" || status == "completed") DelegateStatus.Completed else DelegateStatus.Failed

    private fun normalize(text: String): String = text.trim().lowercase()

    /** A JSON object, or a string holding one (tool arguments and results often arrive as strings). */
    internal fun objectOf(element: JsonElement?): JsonObject? = when (element) {
        is JsonObject -> element
        is JsonPrimitive -> if (element.isString) ToolText.argsOf(element) else null
        else -> null
    }
}
