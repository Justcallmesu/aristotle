package io.github.justcallmesu.aristotle.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The session-list calls (T14), each with only the keys Hermes accepts. [profile] null = the relay's launch profile
 * (no `profile` key, protocol §3); otherwise the profile's name. Pure, so every frame is checked against the source.
 */
object SessionFrames {
    /** `session.list` takes `limit` 1..200 and no offset (`gateway/session_controls.py:561-568`). */
    const val MAX_LIMIT = 200

    /** One page of stored history; the next page asks again with a limit one page larger (protocol §4 paging). */
    const val PAGE = 50

    const val LIVE = "live"
    const val STORED = "stored"

    /**
     * `session.list {scope, limit, profile?}` (`gateway/session_controls.py:561-607`): `live` = sessions with a runtime
     * now, newest started first; `stored` = resumable history, newest activity first (`gateway/session_listing.py:27-86`).
     */
    fun list(scope: String, limit: Int, profile: String?): JsonObject = buildJsonObject {
        put("scope", scope)
        put("limit", limit.coerceIn(1, MAX_LIMIT))
        profile?.let { put("profile", it) }
    }

    /**
     * New session, as the Desktop and the T01 spike send it (`session.create {request_id, source:"gui"}`,
     * `tui_gateway/contracts/canonical_sessions.py:21-46`; EXERCISED `docs/spikes/frames/c-create.jsonl`). No title:
     * a titled create returns the existing session of that title (`gateway/session_controls.py:467-478`).
     */
    fun create(requestId: String, profile: String?): JsonObject = buildJsonObject {
        put("request_id", requestId)
        put("source", "gui")
        profile?.let { put("profile", it) }
    }

    /**
     * Rename, as the Desktop's canonical client sends `session.title` (`apps/desktop/src/api/canonical-protocol.ts:440-442`,
     * `:328-347`): `session.mutate {session_id, request_id, expected_revision, operation:"rename", payload:{title}}`
     * (`tui_gateway/contracts/canonical.py:82-108`, `hermes_state_mutations.py:49-55`). No `expected_generation`:
     * a metadata edit is not fenced on the turn.
     */
    fun rename(sessionId: String, requestId: String, revision: Long, title: String, profile: String?): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        put("request_id", requestId)
        put("expected_revision", revision)
        put("operation", "rename")
        putJsonObject("payload") { put("title", title) }
        profile?.let { put("profile", it) }
    }

    /**
     * The profile roster (`profiles.list {include_sessions}`, `gateway/session_group_controls.py:45`, `:390-460`); the
     * Desktop asks the same (`apps/desktop/src/components/assistant-ui/thread/user-message.tsx:146`). With session
     * fields, so a served profile can be told from one only found on disk ([ProfileRoster.of]).
     */
    fun profiles(): JsonObject = buildJsonObject { put("include_sessions", true) }

    /**
     * A viewer leaving a session without closing it (`session.detach {session_id, subscription_id}`, no `end_reason`:
     * `gateway/session_controls.py:714-737`).
     */
    fun detach(sessionId: String, subscriptionId: String, profile: String?): JsonObject = buildJsonObject {
        put("session_id", sessionId)
        put("subscription_id", subscriptionId)
        profile?.let { put("profile", it) }
    }
}

/**
 * The phone's session list (Q48): live sessions on top, stored history below, each conversation once. Pure.
 */
object SessionLists {
    /**
     * [stored] without the rows [live] already shows. A stored row is the same conversation as a live one when they
     * share any id (`session_id`, `id`, `resolved_id`): a closed session resumed elsewhere moves from the stored feed to
     * the live one (`gateway/session_listing.py:89-108`), and a compressed lineage lists its root `id` with its tip as
     * `session_id`/`resolved_id` (`:21-24`). Within each list a key appears once (the first row wins).
     */
    fun merge(live: List<SessionSummary>, stored: List<SessionSummary>): Pair<List<SessionSummary>, List<SessionSummary>> {
        val liveRows = live.filter { it.key.isNotEmpty() }.distinctBy { it.key }
        val liveIds = liveRows.flatMapTo(HashSet()) { it.ids }
        val storedRows = stored.filter { it.key.isNotEmpty() && it.ids.none(liveIds::contains) }.distinctBy { it.key }
        return liveRows to storedRows
    }
}
