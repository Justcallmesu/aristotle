package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * One Hermes profile the relay socket can reach. [name] is what calls send as `profile` (protocol §3: the profile
 * name, resolved by Hermes' `profile_matches_home`); [own] is the relay's launch profile, which calls reach without
 * `profile`.
 */
data class ProfileOption(val name: String, val displayName: String, val own: Boolean)

/** The `profiles.list` roster (protocol §1, "Profile list") read for the phone's profile picker. Pure. */
object ProfileRoster {
    /**
     * The profiles a session call can reach on this host-scoped socket, in Hermes' order (its own first,
     * `gateway/session_group_controls.py:390-460`). Hermes also lists profiles it only discovered on disk (parked or
     * not served); a session call naming one is refused `profile_mismatch` (`gateway/session_controls.py:173-198`).
     * A served one is told apart by its session fields (`last_session`, `gateway/session_group_controls.py:316-370`):
     * only the own row and the siblings this socket routes to carry them (`:424-430`). Rows without a name, and a
     * name seen twice, are dropped.
     */
    fun of(result: JsonElement): List<ProfileOption> {
        val rows = ((result as? JsonObject)?.get("profiles") as? JsonArray).orEmpty()
        val seen = HashSet<String>()
        return rows.mapIndexedNotNull { index, element ->
            val row = element as? JsonObject ?: return@mapIndexedNotNull null
            val name = row.text("name")?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
            val own = index == 0
            if (!own && SESSION_FIELD !in row) return@mapIndexedNotNull null
            if (!seen.add(name)) return@mapIndexedNotNull null
            ProfileOption(name, row.text("display_name")?.takeIf { it.isNotBlank() } ?: name, own)
        }
    }

    /** What calls send for [option]: null for the launch profile (no `profile` key), else its name. */
    fun wire(option: ProfileOption): String? = if (option.own) null else option.name

    private const val SESSION_FIELD = "last_session"

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

/**
 * [base] with every call routed to [profile] (protocol §3: the Desktop way, a `profile` key in the params of a
 * host-scoped socket). Null: the launch profile, params untouched. A chat, its composer and its prompt answers all
 * go through one, so a session keeps the profile it was opened in (T14). Hermes strips `profile` for its own home
 * on the strict session verbs (`gateway/session_controls.py:96-100`, `:305-318`) and routes it to the sibling
 * otherwise; `clarify.lock` and `session.steer` accept it too (`tui_gateway/contracts/prompt_voice.py:267-271`,
 * `gateway/session_busy_controls.py:20-27`). Responses to `srq-` requests name no profile; Hermes settles them as
 * the sibling connection that attached the session (`gateway/session_controls.py:909-919`).
 */
class ProfileLink(private val base: RelayLink, val profile: String?) : RelayLink {
    override val state: StateFlow<LinkState> get() = base.state
    override val events: SharedFlow<InboundFrame> get() = base.events

    override suspend fun call(method: String, params: JsonObject): JsonElement = base.call(method, withProfile(params, profile))

    override fun respond(requestId: String, result: JsonObject): Boolean = base.respond(requestId, result)

    override fun retryNow() = base.retryNow()

    companion object {
        /** [params] plus `"profile": [profile]`; unchanged for null or when the key is already there. */
        fun withProfile(params: JsonObject, profile: String?): JsonObject =
            if (profile == null || "profile" in params) params else JsonObject(params + ("profile" to JsonPrimitive(profile)))
    }
}
