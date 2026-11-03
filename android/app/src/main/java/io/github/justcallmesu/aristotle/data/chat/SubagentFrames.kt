package io.github.justcallmesu.aristotle.data.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The last 16 KiB of a child's live transcript (`subagent.tail`); [available] false while it has none (yet, or any more). */
data class SubagentTail(val available: Boolean, val text: String, val truncated: Boolean)

/**
 * The calls behind the subagent rows (T17), each with only the keys the owner accepts: `session_id` and
 * `subagent_id` (`profile` is added by [io.github.justcallmesu.aristotle.data.ProfileLink]); any other key is
 * `invalid_params` (`gateway/session_ancillary.py:41-44`, `:227-231`). Pure.
 */
object SubagentFrames {
    /** Live children of the session; the Desktop hydrates and polls it (`use-subagent-snapshot.ts:39-42`). */
    fun list(sessionId: String) = ComposerCall("subagent.list", buildJsonObject { put("session_id", sessionId) })

    /** The child's live transcript tail; the Desktop reads it every 2 s while a child is open (`subagent-transcript.tsx:30-60`). */
    fun tail(sessionId: String, subagentId: String) = ComposerCall("subagent.tail", child(sessionId, subagentId))

    /**
     * Stop one child: the Desktop's per-child Stop, with no confirmation (`subagent-controls.tsx:21-53`). The owner
     * hard-interrupts that child only (`gateway/session_ancillary.py:38-55`).
     */
    fun interrupt(sessionId: String, subagentId: String) = ComposerCall("subagent.interrupt", child(sessionId, subagentId))

    /** `subagent.list`'s `subagents` rows (`gateway/session_ancillary.py:263-266`); anything else is no rows. */
    fun children(result: JsonElement?): List<JsonObject> = (result.asObject()?.get("subagents") as? JsonArray).orEmpty().mapNotNull { it.asObject() }

    fun tailOf(result: JsonElement?): SubagentTail {
        val answer = result.asObject()
        val text = answer?.text("text").orEmpty()
        return SubagentTail(answer?.flag("available") == true, text.takeLast(TAIL_CHARS), answer?.flag("truncated") == true)
    }

    /** `found` false: the child had already finished, or is not this session's (`{found, subagent_id}`, `:53-55`). */
    fun stopped(result: JsonElement?): Boolean = result.asObject()?.flag("found") == true

    /** The Desktop keeps the same last 16 384 characters (`subagent-transcript.tsx:46`). */
    private const val TAIL_CHARS = 16_384

    private fun child(sessionId: String, subagentId: String) = buildJsonObject {
        put("session_id", sessionId)
        put("subagent_id", subagentId)
    }
}
