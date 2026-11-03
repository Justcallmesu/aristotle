package io.github.justcallmesu.aristotle.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** JSON settings for Hermes frames: unknown keys and nulls are normal there (protocol §2.1). */
val HermesJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

/** A JSON-RPC error; [data] is Hermes' `error.data` (`reason`, and fresh fences on a CAS refusal, protocol §4). */
data class RpcError(val code: Int, val message: String, val data: JsonObject? = null) {
    val reason: String? get() = (data?.get("reason") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/** One JSON-RPC 2.0 text frame from the relay, classified (docs/protocol.md §1.3, §2). */
sealed interface InboundFrame {
    data class Response(val id: Long, val result: JsonElement?, val error: RpcError?) : InboundFrame

    /**
     * A session event (protocol §2). [seq] is per session and monotonic within [replayEpoch]; [admissionId]
     * names the prompt a turn's frames belong to, [executionGeneration] the claim that runs it (the fence Steer and
     * Stop name, `tui_gateway/contracts/canonical_sessions.py:301-309`). Process-wide broadcasts carry none of them.
     */
    data class Event(
        val type: String,
        val sessionId: String,
        val payload: JsonObject?,
        val seq: Long? = null,
        val replayEpoch: String? = null,
        val admissionId: String? = null,
        val executionGeneration: Long? = null,
    ) : InboundFrame

    /**
     * A Hermes -> client request (`srq-…` id, protocol §6): `sudo`, `secret`, batch `clarify`, vault prompts and
     * window bridges. [params] carries the `session_id` the request belongs to.
     */
    data class ServerRequest(val id: String, val method: String, val params: JsonObject = JsonObject(emptyMap())) : InboundFrame {
        val sessionId: String get() = (params["session_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
    }
    data object Unknown : InboundFrame
}

object RpcFrames {
    const val GATEWAY_READY = "gateway.ready"
    const val SESSIONS_CHANGED = "sessions.changed"

    fun request(id: Long, method: String, params: JsonObject = JsonObject(emptyMap())): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }.toString()

    /**
     * The answer to a Hermes -> client request: `{"jsonrpc":"2.0","id":"srq-…","result":{…}}` (no `method`,
     * `gateway/session_server_requests.py:72-75`). Hermes sends no reply to it.
     */
    fun response(requestId: String, result: JsonObject): String =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", requestId)
            put("result", result)
        }.toString()

    fun parse(text: String): InboundFrame {
        val obj = runCatching { HermesJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return InboundFrame.Unknown
        val method = obj.stringOrNull("method")
        val id = obj["id"] as? JsonPrimitive
        return when {
            method == "event" -> event(obj)
            method != null && id != null && id.isString ->
                InboundFrame.ServerRequest(id.content, method, obj["params"] as? JsonObject ?: JsonObject(emptyMap()))
            // Our ids are JSON numbers; a string id "5" is not an answer to call 5.
            method == null && id != null && !id.isString && id.longOrNull != null -> response(id.longOrNull!!, obj)
            else -> InboundFrame.Unknown
        }
    }

    private fun event(obj: JsonObject): InboundFrame = (obj["params"] as? JsonObject)?.let(::eventOf) ?: InboundFrame.Unknown

    /** An event from its `params` object (also the shape of `session.events.since` replays); null without a type. */
    fun eventOf(params: JsonObject): InboundFrame.Event? {
        val type = params.stringOrNull("type") ?: return null
        return InboundFrame.Event(
            type = type,
            sessionId = params.stringOrNull("session_id").orEmpty(),
            payload = params["payload"] as? JsonObject,
            seq = (params["seq"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull,
            replayEpoch = (params["replay_epoch"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
            admissionId = (params["admission_id"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull,
            executionGeneration = (params["execution_generation"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull,
        )
    }

    private fun response(id: Long, obj: JsonObject): InboundFrame {
        val error = (obj["error"] as? JsonObject)?.let {
            RpcError(it["code"]?.jsonPrimitive?.intOrNull ?: 0, it.stringOrNull("message").orEmpty(), it["data"] as? JsonObject)
        }
        return InboundFrame.Response(id, obj["result"], error)
    }

    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}

/** One row of `session.list` (protocol §4). Missing or null fields fall back to the defaults. */
@Serializable
data class SessionSummary(
    @SerialName("session_id") val sessionId: String = "",
    val id: String = "",
    val title: String = "",
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("last_active") val lastActive: Double? = null,
    @SerialName("message_count") val messageCount: Int = 0,
    val running: Boolean = false,
    /** Stored rows: the physical (compression tip) id the row resumes (`gateway/session_listing.py:21-24`). */
    @SerialName("resolved_id") val resolvedId: String? = null,
) {
    /** Seconds since the epoch of the latest known activity; `session.list` only carries `started_at` today. */
    val lastActivitySeconds: Double? get() = lastActive ?: startedAt
    val key: String get() = sessionId.ifEmpty { id }

    /** Every id this row answers to; a live row and a stored row that share one are the same conversation. */
    val ids: Set<String> get() = setOfNotNull(sessionId.ifEmpty { null }, id.ifEmpty { null }, resolvedId?.ifEmpty { null })
}

@Serializable
data class SessionListResult(val sessions: List<SessionSummary> = emptyList())

@Serializable
data class RuntimeDescription(@SerialName("profile_id") val profileId: String = "")

object Profiles {
    /**
     * Profile name from a Hermes `profile_id`, which is the profile's home path (protocol §1.1, §3):
     * `…/.hermes` is `default`, `…/.hermes/profiles/pm` is `pm`.
     */
    fun nameOf(profileId: String): String? {
        val path = profileId.trimEnd('/')
        if (path.isEmpty()) return null
        val parent = path.substringBeforeLast('/', "")
        return if (parent.endsWith("/profiles")) path.substringAfterLast('/') else DEFAULT
    }

    const val DEFAULT = "default"
}
