package io.github.justcallmesu.aristotle.data.cron

import io.github.justcallmesu.aristotle.data.chat.ComposerCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * One Hermes cron job as `cron.manage list` sends it (`tools/cronjob_job_args.py:476-526`, `_format_job`). [promptPreview]
 * is Hermes' first 100 characters plus "..." (`:488`): the relay has no full prompt. [schedule] is Hermes' own display
 * string (`schedule_display`: `every 30m`, a cron expression, `once in 30m`, `once at …`; `cron/jobs.py:770-860`).
 * Times are ISO-8601 with an offset, as Hermes stores them.
 */
data class CronJob(
    val id: String,
    val name: String,
    val promptPreview: String = "",
    val schedule: String = "",
    val repeat: String? = null,
    val deliver: String? = null,
    val nextRunAt: String? = null,
    val lastRunAt: String? = null,
    val lastStatus: String? = null,
    val lastError: String? = null,
    val lastDeliveryError: String? = null,
    val lastFireError: String? = null,
    val enabled: Boolean = true,
    val state: String? = null,
    val pausedReason: String? = null,
    val model: String? = null,
    val workdir: String? = null,
    val skills: List<String> = emptyList(),
    val continuity: Boolean = false,
) {
    /** Scheduled to fire: enabled and not paused (the Desktop's switch, `hermes-bots/cron.tsx:500`). */
    val active: Boolean get() = enabled && state != PAUSED

    /**
     * Why the job is not doing what one expects: the last fire error, then the last delivery error, then the pause
     * reason (the Desktop's `routineDetailIssue`, `hermes-bots/cron.tsx:412-417`). Hermes' text, shown as it is.
     */
    val issue: String? get() = listOf(lastFireError, lastDeliveryError, pausedReason).firstOrNull { !it.isNullOrBlank() }?.trim()

    /** Hermes cut the prompt for the list (`_format_job` keeps 100 characters and adds "..."). */
    val promptCut: Boolean get() = promptPreview.length > PREVIEW_CHARS && promptPreview.endsWith("...")

    private companion object {
        const val PAUSED = "paused"
        const val PREVIEW_CHARS = 100
    }
}

/** A `cron.manage list` answer: the jobs, and Hermes' warning when no gateway is there to fire them (`gateway_running`). */
data class CronList(val jobs: List<CronJob>, val warning: String? = null)

/** What a `cron.manage` change answered: done (with the job when Hermes sent it), or refused with Hermes' reason. */
sealed interface CronAnswer {
    data class Done(val job: CronJob? = null) : CronAnswer
    data class Refused(val reason: String) : CronAnswer
}

/** A new job as the phone's form gives it; [repeat] null = until paused or deleted. */
data class CronDraft(val name: String, val prompt: String, val schedule: String, val repeat: Int? = null, val continuity: Boolean = false)

/**
 * The relay's cron calls: one method, `cron.manage {action, …}` (`tui_gateway/contracts/tools_commands.py:438-529`,
 * handler `tui_gateway/methods_tools.py:1305-1329`), reached over `/api/ws` through the legacy fallback
 * (`tui_gateway/ws.py:380-397`). Its actions are only `list`, `add`, `remove`, `pause`, `resume`: no edit, no run now,
 * no run history (those are REST only, protocol §13). `profile` is added by
 * [io.github.justcallmesu.aristotle.data.ProfileLink]. Keys are the ones the Desktop sends
 * (`apps/desktop/src/plugins/hermes-bots/cron.tsx:125-129`, `:519-527`, `:1013-1041`). Pure.
 */
object CronFrames {
    const val METHOD = "cron.manage"

    /** The event Hermes broadcasts when its `cron/jobs.json` moved (`tui_gateway/change_watcher.py:380`). */
    const val CHANGED = "cron.changed"

    /** Every job, paused ones too: the default leaves them out, which reads as deletion beside a switch (`methods_tools.py:1311-1313`). */
    fun list() = ComposerCall(METHOD, buildJsonObject {
        put("action", "list")
        put("include_disabled", true)
    })

    fun pause(jobId: String) = byId("pause", jobId)

    fun resume(jobId: String) = byId("resume", jobId)

    fun remove(jobId: String) = byId("remove", jobId)

    /** The switch: pause an active job, resume any other. */
    fun toggle(job: CronJob) = if (job.active) pause(job.id) else resume(job.id)

    /**
     * A new job (`methods_tools.py:1319-1326`). `repeat` and `continuity` only when set, as the Desktop
     * (`cron.tsx:1023-1032`); no `deliver`: Hermes' default (`local`, the run's own session), the Desktop's "Run
     * history only". Hermes parses and checks the schedule itself and refuses a bad one with its usage text.
     */
    fun add(draft: CronDraft) = ComposerCall(METHOD, buildJsonObject {
        put("action", "add")
        put("name", draft.name)
        put("schedule", draft.schedule)
        put("prompt", draft.prompt)
        draft.repeat?.let { put("repeat", it) }
        if (draft.continuity) put("continuity", true)
    })

    /** `cron.manage` names a job by `name`, which Hermes resolves as id or name (`methods_tools.py:1309`, `:1327-1328`). */
    private fun byId(action: String, jobId: String) = ComposerCall(METHOD, buildJsonObject {
        put("action", action)
        put("name", jobId)
    })

    /**
     * The jobs of a `list` answer (`{success, count, jobs, gateway_running?, warning?}`, `tools/cronjob_tools.py:757-765`).
     * A tool-level failure (`success: false`, `error`) throws [IllegalArgumentException] with Hermes' text.
     */
    fun jobs(result: JsonElement?): CronList {
        val answer = result as? JsonObject ?: throw IllegalArgumentException("cron.manage: unexpected result")
        refusal(answer)?.let { throw IllegalArgumentException(it) }
        val rows = (answer["jobs"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::job) }
        val warning = answer.text("warning")?.takeIf { answer.flag("gateway_running") == false && it.isNotBlank() }
        return CronList(rows, warning)
    }

    /** A change's answer: `success: false` with `error` is Hermes refusing (`tool_error`, `cronjob_tools.py:1091-1103`). */
    fun answer(result: JsonElement?): CronAnswer {
        val answer = result as? JsonObject ?: return CronAnswer.Refused("cron.manage: unexpected result")
        refusal(answer)?.let { return CronAnswer.Refused(it) }
        return CronAnswer.Done((answer["job"] as? JsonObject)?.let(::job))
    }

    /** One `_format_job` row; null without a `job_id`. */
    fun job(row: JsonObject): CronJob? {
        val id = row.text("job_id")?.takeIf { it.isNotBlank() } ?: return null
        return CronJob(
            id = id,
            name = row.text("name")?.takeIf { it.isNotBlank() } ?: id,
            promptPreview = row.text("prompt_preview").orEmpty(),
            schedule = row.text("schedule").orEmpty(),
            repeat = row.scalar("repeat"),
            deliver = row.text("deliver"),
            nextRunAt = row.text("next_run_at"),
            lastRunAt = row.text("last_run_at"),
            lastStatus = row.text("last_status"),
            lastError = row.text("last_error"),
            lastDeliveryError = row.text("last_delivery_error"),
            lastFireError = row.text("last_fire_error"),
            enabled = row.flag("enabled") != false,
            state = row.text("state"),
            pausedReason = row.text("paused_reason"),
            model = row.text("model"),
            workdir = row.text("workdir"),
            skills = (row["skills"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content },
            continuity = row.flag("continuity") == true,
        )
    }

    private fun refusal(answer: JsonObject): String? {
        val error = answer.text("error")
        return if (answer.flag("success") == false || !error.isNullOrBlank()) error?.ifBlank { null } ?: "refused" else null
    }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    /** A string or a number (`repeat` is `int | str`, `tools_commands.py:470`). */
    private fun JsonObject.scalar(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
}
