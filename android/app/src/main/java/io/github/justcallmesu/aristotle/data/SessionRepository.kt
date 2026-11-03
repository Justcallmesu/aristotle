package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the session list hears; each new relay connection starts again with [Profile] and [Sessions]. */
sealed interface SessionFeedEvent {
    /** The launch profile's name (`runtime.describe`), null when Hermes did not say. */
    data class Profile(val name: String?) : SessionFeedEvent
    data class Sessions(val sessions: List<SessionSummary>) : SessionFeedEvent

    /** Hermes answered `session.list` with an error or something unreadable; the link itself is fine. */
    data class ListFailed(val details: String) : SessionFeedEvent
}

data class FeedTimings(val refreshDebounceMillis: Long = 1_000)

/**
 * Read-only session list over the process-wide link (T09): on every new relay connection
 * `runtime.describe` (launch profile) and `session.list`; again after `sessions.changed` once a list was
 * delivered. Connecting, reconnecting and their pages are the link's business ([LinkState]).
 */
class SessionRepository(private val link: RelayLink, private val timings: FeedTimings = FeedTimings()) {

    /** Runs until cancelled. */
    fun feed(): Flow<SessionFeedEvent> = channelFlow {
        link.state
            .map { (it as? LinkState.Online)?.generation }
            .distinctUntilChanged()
            .collectLatest { generation -> if (generation != null) Connection(channel).run() }
    }

    /** One relay connection's worth of listing; cancelled when the link moves to another socket or goes offline. */
    private inner class Connection(private val out: SendChannel<SessionFeedEvent>) {
        private var listed = false
        private var refresh: Job? = null

        suspend fun run() = coroutineScope {
            launch(start = CoroutineStart.UNDISPATCHED) {
                link.events.collect { frame ->
                    if (frame is InboundFrame.Event && frame.type == RpcFrames.SESSIONS_CHANGED) scheduleRefresh()
                }
            }
            loadAll()
        }

        private suspend fun loadAll() {
            val profile = describeRuntime()?.profileId?.let(Profiles::nameOf)
            out.send(SessionFeedEvent.Profile(profile))
            loadSessions()
        }

        /** `runtime.describe`, or null when it failed (the profile label is optional); cancellation passes. */
        private suspend fun describeRuntime(): RuntimeDescription? = try {
            HermesJson.decodeFromJsonElement(RuntimeDescription.serializer(), link.call("runtime.describe"))
        } catch (_: RpcException) {
            null
        } catch (_: IllegalArgumentException) {
            null // SerializationException: an answer of another shape
        }

        private suspend fun loadSessions() {
            try {
                val result = link.call("session.list", buildJsonObject { put("limit", SESSION_LIMIT) })
                val sessions = HermesJson.decodeFromJsonElement(SessionListResult.serializer(), result).sessions
                listed = true
                out.send(SessionFeedEvent.Sessions(sessions))
            } catch (e: RpcException) {
                // A closed link is reported by the link state; only Hermes' own failure is the list's.
                if (!e.linkClosed) out.send(SessionFeedEvent.ListFailed(e.message.orEmpty()))
            } catch (_: IllegalArgumentException) {
                out.send(SessionFeedEvent.ListFailed("session.list: unexpected result"))
            }
        }

        /** `sessions.changed` arrives in bursts; refetch once things settle, and only once a list was delivered. */
        private fun CoroutineScope.scheduleRefresh() {
            if (!listed) return
            refresh?.cancel()
            refresh = launch {
                delay(timings.refreshDebounceMillis)
                loadSessions()
            }
        }
    }

    private companion object {
        const val SESSION_LIMIT = 50
    }
}
