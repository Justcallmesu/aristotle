package io.github.justcallmesu.aristotle.data

import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** What the session list hears; each new relay connection starts again with [Profile] and [Sessions]. */
sealed interface SessionFeedEvent {
    /** The launch profile's name (`runtime.describe`), null when Hermes did not say. */
    data class Profile(val name: String?) : SessionFeedEvent

    /**
     * One listing of [profile] (null = launch profile): [live] then [stored], each conversation once
     * ([SessionLists.merge]). [more]: history may go on past [storedLimit]; [capped]: it may, but `session.list` cannot
     * page further ([SessionFrames.MAX_LIMIT]).
     */
    data class Sessions(
        val profile: String?,
        val live: List<SessionSummary>,
        val stored: List<SessionSummary>,
        val storedLimit: Int = SessionFrames.PAGE,
        val more: Boolean = false,
        val capped: Boolean = false,
        /** The stored-history call failed (the live rows are still good); [stored] is empty then. Null = history loaded. */
        val historyProblem: String? = null,
    ) : SessionFeedEvent {
        /** Every row, live first. */
        val sessions: List<SessionSummary> get() = live + stored
    }

    /** Hermes answered `session.list` with an error or something unreadable; the link itself is fine. */
    data class ListFailed(val details: String, val profile: String? = null, val unserved: Boolean = false) : SessionFeedEvent
}

/**
 * [refreshDebounceMillis]: the background watcher's wait after `sessions.changed`. [liveRefetchMillis]: the session
 * list's, at most one live refetch per this long (Hermes sends `sessions.changed` on every message-count move).
 */
data class FeedTimings(val refreshDebounceMillis: Long = 1_000, val liveRefetchMillis: Long = 2_000)

/** What to list: [profile] (null = launch) with [storedLimit] rows of history; [nonce] forces a reload. */
private data class ListRequest(val profile: String?, val storedLimit: Int, val nonce: Int = 0)

/**
 * The session list over the process-wide link (T09, T14): on every new relay connection `runtime.describe`
 * (launch profile), then `session.list` live + stored for the profile on show; again after [show], [loadMore] and
 * [reload]. After `sessions.changed` (once a list was delivered, coalesced, at most every [FeedTimings.liveRefetchMillis])
 * only the live list is fetched again; the stored page is kept. A failing stored call leaves the live rows
 * ([SessionFeedEvent.Sessions.historyProblem]). Connecting, reconnecting and their pages are
 * the link's business ([LinkState]). Also the list's actions: new session, rename, the profile roster.
 */
class SessionRepository(private val link: RelayLink, private val timings: FeedTimings = FeedTimings()) {
    private val request = MutableStateFlow(ListRequest(null, SessionFrames.PAGE))

    /** Lists [profile] from the first page of history on; the next listing is for it. */
    fun show(profile: String?) = request.update { if (it.profile == profile) it else ListRequest(profile, SessionFrames.PAGE, it.nonce + 1) }

    /** One more page of history (protocol §4: the same call with a larger `limit`); false at Hermes' cap. */
    fun loadMore(): Boolean {
        val current = request.value
        if (current.storedLimit >= SessionFrames.MAX_LIMIT) return false
        request.value = current.copy(storedLimit = minOf(SessionFrames.MAX_LIMIT, current.storedLimit + SessionFrames.PAGE))
        return true
    }

    /** Lists again now, live and stored (Retry, pull to refresh, after a rename). */
    fun reload() = request.update { it.copy(nonce = it.nonce + 1) }

    /** Runs until cancelled. */
    fun feed(): Flow<SessionFeedEvent> = channelFlow {
        link.state
            .map { (it as? LinkState.Online)?.generation }
            .distinctUntilChanged()
            .collectLatest { generation -> if (generation != null) Connection(channel).run() }
    }

    /** The relay's profile roster for the picker ([ProfileRoster.of]); throws [RpcException]. */
    suspend fun profiles(): List<ProfileOption> = ProfileRoster.of(link.call("profiles.list", SessionFrames.profiles()))

    /**
     * [profile]'s project tree (`projects.tree`, protocol §18): Home first, explicit, auto and discovered projects with
     * counts and [previewLimit] newest rows each, over the [ProjectFrames.SESSION_LIMIT] newest sessions. Throws
     * [RpcException]; an answer of another shape throws IllegalArgumentException.
     */
    suspend fun projectTree(profile: String?, previewLimit: Int = ProjectFrames.PREVIEW): ProjectTree =
        HermesJson.decodeFromJsonElement(ProjectTree.serializer(), link.call("projects.tree", ProjectFrames.tree(profile, previewLimit)))

    /** One project of [profile] with every row in its lanes (`projects.project_sessions`), or null when it is gone. */
    suspend fun projectSessions(profile: String?, projectId: String): ProjectNode? =
        HermesJson.decodeFromJsonElement(ProjectSessionsResult.serializer(), link.call("projects.project_sessions", ProjectFrames.projectSessions(profile, projectId))).project

    /** Creates a session in [profile] and returns its id; throws [RpcException]. */
    suspend fun create(profile: String?, requestId: String = newId()): String {
        val result = link.call("session.create", SessionFrames.create(requestId, profile)) as? JsonObject
        return result?.text("session_id")?.takeIf { it.isNotEmpty() } ?: throw RpcException(null, "session.create: no session_id")
    }

    /**
     * Renames [sessionId] of [profile]. The phone holds no revision for a session it has not opened, so the first
     * try names revision 0; a `revision_conflict` refusal carries the session's current one in `error.data.revision`
     * and is retried once at it (`gateway/session_controls.py:845-849`). So a rename made on the PC meanwhile is overwritten:
     * last writer wins, unlike the Desktop. Throws [RpcException].
     */
    suspend fun rename(profile: String?, sessionId: String, title: String, newRequestId: () -> String = ::newId) {
        try {
            link.call("session.mutate", SessionFrames.rename(sessionId, newRequestId(), 0, title, profile))
        } catch (e: RpcException) {
            val fresh = e.error?.takeIf { it.reason == REVISION_CONFLICT }?.data?.get("revision")?.let { (it as? JsonPrimitive)?.longOrNull }
                ?: throw e
            link.call("session.mutate", SessionFrames.rename(sessionId, newRequestId(), fresh, title, profile))
        }
    }

    /** One relay connection's worth of listing; cancelled when the link moves to another socket or goes offline. */
    private inner class Connection(private val out: SendChannel<SessionFeedEvent>) {
        private var listed = false
        private var refresh: Job? = null
        private var fetched: Fetched? = null
        private val changes = MutableStateFlow(0)

        suspend fun run() = coroutineScope {
            launch(start = CoroutineStart.UNDISPATCHED) {
                link.events.collect { frame ->
                    if (frame is InboundFrame.Event && frame.type == RpcFrames.SESSIONS_CHANGED) scheduleRefresh()
                }
            }
            val profile = describeRuntime()?.profileId?.let(Profiles::nameOf)
            out.send(SessionFeedEvent.Profile(profile))
            combine(request, changes) { wanted, _ -> wanted }.collectLatest(::load)
        }

        /** `runtime.describe`, or null when it failed (the profile label is optional); cancellation passes. */
        private suspend fun describeRuntime(): RuntimeDescription? = try {
            HermesJson.decodeFromJsonElement(RuntimeDescription.serializer(), link.call("runtime.describe"))
        } catch (_: RpcException) {
            null
        } catch (_: IllegalArgumentException) {
            null // SerializationException: an answer of another shape
        }

        /** The stored page of the last full listing of [request]: [rows], or why it failed. */
        private inner class Fetched(val request: ListRequest, val rows: List<SessionSummary>, val problem: String?)

        /** A listing: live always; stored only when [wanted] is not what was fetched last (show, load more, reload). */
        private suspend fun load(wanted: ListRequest) {
            try {
                val live = list(SessionFrames.LIVE, SessionFrames.MAX_LIMIT, wanted.profile)
                val kept = fetched?.takeIf { it.request == wanted }
                val page = kept ?: storedPage(wanted).also { fetched = it }
                val (liveRows, storedRows) = SessionLists.merge(live, page.rows)
                val full = page.rows.size >= wanted.storedLimit
                listed = true
                out.send(
                    SessionFeedEvent.Sessions(
                        profile = wanted.profile,
                        live = liveRows,
                        stored = storedRows,
                        storedLimit = wanted.storedLimit,
                        more = full && wanted.storedLimit < SessionFrames.MAX_LIMIT,
                        capped = full && wanted.storedLimit >= SessionFrames.MAX_LIMIT,
                        historyProblem = page.problem,
                    ),
                )
            } catch (e: RpcException) {
                // A closed link is reported by the link state; only Hermes' own failure is the list's.
                if (!e.linkClosed) out.send(SessionFeedEvent.ListFailed(e.message.orEmpty(), wanted.profile, unserved = wanted.profile != null && e.error?.reason == PROFILE_MISMATCH))
            } catch (_: IllegalArgumentException) {
                out.send(SessionFeedEvent.ListFailed("session.list: unexpected result", wanted.profile))
            }
        }

        /** The history page; a Hermes failure is kept as a problem so the live rows still show. A closed link throws. */
        private suspend fun storedPage(wanted: ListRequest): Fetched = try {
            Fetched(wanted, list(SessionFrames.STORED, wanted.storedLimit, wanted.profile), null)
        } catch (e: RpcException) {
            if (e.linkClosed) throw e
            Fetched(wanted, emptyList(), e.message.orEmpty().ifEmpty { "session.list failed" })
        } catch (_: IllegalArgumentException) {
            Fetched(wanted, emptyList(), "session.list: unexpected result")
        }

        private suspend fun list(scope: String, limit: Int, profile: String?): List<SessionSummary> =
            HermesJson.decodeFromJsonElement(SessionListResult.serializer(), link.call("session.list", SessionFrames.list(scope, limit, profile))).sessions

        /**
         * `sessions.changed` arrives in bursts (every message-count move): one live refetch after the window, which
         * further events inside it join, so there is at most one per window. Only once a list was delivered.
         */
        private fun CoroutineScope.scheduleRefresh() {
            if (!listed || refresh?.isActive == true) return
            refresh = launch {
                delay(timings.liveRefetchMillis)
                changes.update { it + 1 }
            }
        }
    }

    private companion object {
        const val REVISION_CONFLICT = "revision_conflict"
        const val PROFILE_MISMATCH = "profile_mismatch"

        fun newId(): String = UUID.randomUUID().toString().replace("-", "")

        fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
