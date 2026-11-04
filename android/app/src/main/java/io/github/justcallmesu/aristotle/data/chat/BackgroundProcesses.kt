package io.github.justcallmesu.aristotle.data.chat

import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.RpcException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One background process of the session (`terminal(background=true)`), a `process.list` row
 * (`tui_gateway/contracts/tools_commands.py:93-109`, built by `tools/process_registry.py::list_sessions`). [id] is the
 * row's `session_id`: in that row it names the PROCESS (`proc.id`, what `process.kill` takes as `process_id`), not the
 * chat. [command] is what Hermes lists, already cut to 200 characters there. [uptimeSeconds] is as of the answer.
 */
data class BackgroundProcess(
    val id: String,
    val command: String,
    val status: String,
    val uptimeSeconds: Long,
    val exitCode: Int? = null,
) {
    /** `list_sessions` says `running` or `exited`; anything else that is not `exited` is treated as still there. */
    val running: Boolean get() = status != EXITED

    private companion object {
        const val EXITED = "exited"
    }
}

/** What `process.kill` did (`ProcessKillStatus`, `tools_commands.py:129-133`), or why it did not reach Hermes. */
sealed interface ProcessKill {
    data object Stopped : ProcessKill
    data object AlreadyExited : ProcessKill

    /** Hermes has no such process for this session (`not_found`, or `4044` from `tui_gateway/methods_tools.py:292-293`). */
    data object NotFound : ProcessKill

    data class Failed(val reason: String) : ProcessKill

    /** The frame never left the phone: nothing reached Hermes. */
    data object NotSent : ProcessKill

    /** The frame was written but the link dropped before the reply: Hermes may have stopped it. Check the list. */
    data object Unknown : ProcessKill
}

/**
 * The calls behind the chat's "Background" rows (TB1, Q77), the Desktop's way (`apps/desktop/src/store/composer-status.ts`):
 * `process.list {session_id}` and `process.kill {session_id, process_id}` (`profile` is added by
 * [io.github.justcallmesu.aristotle.data.ProfileLink]). Both are scoped to the caller's session by Hermes. Pure.
 */
object ProcessFrames {
    /** The session is gone in Hermes (`4001 session not found`, `tui_gateway/session_registry.py:303-318`). */
    const val SESSION_GONE = 4001

    /** No such process for this session (`tui_gateway/methods_tools.py:292-293`). */
    const val NO_PROCESS = 4044

    fun list(sessionId: String) = ComposerCall("process.list", buildJsonObject { put("session_id", sessionId) })

    fun kill(sessionId: String, processId: String) = ComposerCall(
        "process.kill",
        buildJsonObject {
            put("session_id", sessionId)
            put("process_id", processId)
        },
    )

    /** The rows of a `process.list` answer; a row without an id is dropped, anything else is no rows. */
    fun processes(result: JsonElement?): List<BackgroundProcess> =
        (result.asObject()?.get("processes") as? JsonArray).orEmpty().mapNotNull { element ->
            val row = element.asObject() ?: return@mapNotNull null
            val id = row.text("session_id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BackgroundProcess(
                id = id,
                command = row.text("command").orEmpty(),
                status = row.text("status") ?: "running",
                uptimeSeconds = row.number("uptime_seconds")?.toLong()?.coerceAtLeast(0) ?: 0,
                exitCode = row.number("exit_code")?.toInt(),
            )
        }

    /**
     * The rows the stack shows: running processes only, so a stopped one leaves on the next poll. A process already on
     * screen keeps its place and new ones are added at the end (the Desktop's layout-stable sync, `composer-status.ts:333-338`).
     */
    fun reconcile(previous: List<BackgroundProcess>, fresh: List<BackgroundProcess>): List<BackgroundProcess> {
        val running = fresh.filter { it.running }.associateBy { it.id }
        val kept = previous.mapNotNull { running[it.id] }
        val keptIds = kept.mapTo(HashSet()) { it.id }
        return kept + running.values.filter { it.id !in keptIds }
    }

    /** A `process.kill` answer (`tools/process_registry.py::kill_process`). */
    fun killOutcome(result: JsonElement?): ProcessKill {
        val answer = result.asObject()
        return when (val status = answer?.text("status")) {
            "killed" -> ProcessKill.Stopped
            "already_exited" -> ProcessKill.AlreadyExited
            "not_found" -> ProcessKill.NotFound
            else -> ProcessKill.Failed(answer?.text("error")?.takeIf { it.isNotBlank() } ?: status ?: "")
        }
    }

    /** A `process.kill` that failed as a call. */
    fun killFailure(e: RpcException): ProcessKill {
        val error = e.error
        return when {
            !e.written -> ProcessKill.NotSent
            e.linkClosed -> ProcessKill.Unknown
            error?.code == NO_PROCESS || error?.code == SESSION_GONE -> ProcessKill.NotFound
            error != null -> ProcessKill.Failed(error.message.ifBlank { "error ${error.code}" })
            else -> ProcessKill.Failed(e.message.orEmpty())
        }
    }

    /** The poll stops for this session: Hermes no longer has it, or says it has nothing of ours (the Desktop's latch). */
    fun stopsPolling(e: RpcException): Boolean = e.error?.code == SESSION_GONE || e.error?.code == NO_PROCESS

    /**
     * A frame after which the list may have changed, so it is read at once: a `terminal` or `process` tool finished
     * (`gateway-event/tools.ts:192-197`), or Hermes' notification poller announced a process (`status.update` with
     * `kind: process`, `gateway-event/status.ts:149-152`).
     */
    fun refreshes(frame: InboundFrame.Event): Boolean {
        val payload = frame.payload ?: return false
        return when (frame.type) {
            "tool.complete" -> (payload.text("name") ?: payload.text("tool_name")).let { it == "terminal" || it == "process" }
            "status.update" -> payload.text("kind") == "process"
            else -> false
        }
    }
}
