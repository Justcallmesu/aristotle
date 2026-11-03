package io.github.justcallmesu.aristotle.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What the session list hears from one connection attempt, in order; [Ended] is always last. */
sealed interface SessionFeedEvent {
    /** The launch profile's name (`runtime.describe`), null when Hermes did not say. */
    data class Profile(val name: String?) : SessionFeedEvent
    data class Sessions(val sessions: List<SessionSummary>) : SessionFeedEvent
    data class Ended(val outcome: LinkOutcome) : SessionFeedEvent
}

/** The two calls the feed makes to the PC; [HttpPcLink] in the app, a fake in tests. */
interface PcLink {
    suspend fun me(): MeResult
    fun openRelay(): RelayChannel
}

/** A stored PC as an endpoint the app can dial, or null when its address or pin cannot be used. */
class PcTarget private constructor(val endpoint: ServerEndpoint, val pin: ByteArray?) {
    companion object {
        fun of(credentials: PcCredentials): PcTarget? {
            val endpoint = (credentials.baseUrls.firstOrNull()?.let(ServerAddress::parse) as? AddressCheck.Ok)?.endpoint
                ?: return null
            val pin = credentials.fingerprint?.let(Fingerprint::parse)
            if (endpoint.network == Network.Lan && pin == null) return null
            return PcTarget(endpoint, pin)
        }
    }
}

class HttpPcLink(target: PcTarget, httpClients: HttpClients, private val token: DeviceToken) : PcLink {
    private val baseUrl = target.endpoint.baseUrl
    private val client = httpClients.forEndpoint(target.endpoint, target.pin)

    override suspend fun me(): MeResult = ServerClient(client, baseUrl).me(token)

    override fun openRelay(): RelayChannel = RelaySocket(client, baseUrl, token)
}

data class FeedTimings(
    val readyTimeoutMillis: Long = 20_000,
    val refreshDebounceMillis: Long = 1_000,
    val rpcTimeoutMillis: Long = 15_000,
)

/**
 * Read-only session list over the relay: `GET /v1/me`, open `/v1/ws`, wait for `gateway.ready`, then
 * `runtime.describe` (launch profile) and `session.list`; refetch after `sessions.changed` once a list
 * was delivered. One [feed] collection is one connection attempt; no automatic reconnect (T09).
 */
class SessionRepository(private val link: PcLink, private val timings: FeedTimings = FeedTimings()) {

    fun feed(): Flow<SessionFeedEvent> = channelFlow {
        val refused = LinkOutcomes.ofMe(link.me())
        val outcome = refused ?: RelayLink(link.openRelay(), channel).run()
        send(SessionFeedEvent.Ended(outcome))
    }

    /** One relay socket's lifetime: frames in, RPC calls out, ends on close, failure or timeout. */
    private inner class RelayLink(private val socket: RelayChannel, private val out: SendChannel<SessionFeedEvent>) {
        private val rpc = RelayRpc(socket::send, timings.rpcTimeoutMillis)
        private val outcome = CompletableDeferred<LinkOutcome>()

        @Volatile
        private var listed = false
        private var ready = false
        private var refresh: Job? = null

        suspend fun run(): LinkOutcome = coroutineScope {
            val readyTimeout = launch {
                delay(timings.readyTimeoutMillis)
                end(ConnectionIssue.Generic("no gateway.ready within ${durationText(timings.readyTimeoutMillis)}"))
            }
            launch {
                socket.events().collect { event -> onEvent(event, readyTimeout) }
                end(LinkOutcome.Show(ConnectionIssue.Generic("relay closed")))
            }
            val result = outcome.await()
            rpc.failAll("relay closed")
            coroutineContext.cancelChildren()
            result
        }

        private fun CoroutineScope.onEvent(event: RelayEvent, readyTimeout: Job) {
            when (event) {
                RelayEvent.Opened -> Unit
                is RelayEvent.Text -> onFrame(RpcFrames.parse(event.text), readyTimeout)
                is RelayEvent.Closed -> end(LinkOutcomes.ofClose(event.code, event.reason))
                is RelayEvent.Failed -> end(LinkOutcomes.ofFailure(event.httpStatus, event.problem))
            }
        }

        private fun CoroutineScope.onFrame(frame: InboundFrame, readyTimeout: Job) {
            when (frame) {
                is InboundFrame.Response -> rpc.onResponse(frame)
                is InboundFrame.Event -> when (frame.type) {
                    RpcFrames.GATEWAY_READY -> if (!ready) {
                        ready = true
                        readyTimeout.cancel()
                        launch { loadAll() }
                    }
                    RpcFrames.SESSIONS_CHANGED -> scheduleRefresh()
                }
                // Hermes may ask this socket things (srq-…); a list-only screen leaves them to the Desktop.
                is InboundFrame.ServerRequest, InboundFrame.Unknown -> Unit
            }
        }

        private suspend fun loadAll() {
            val profile = runCatching {
                HermesJson.decodeFromJsonElement(RuntimeDescription.serializer(), rpc.call("runtime.describe"))
            }.getOrNull()?.profileId?.let(Profiles::nameOf)
            emit(SessionFeedEvent.Profile(profile))
            loadSessions()
        }

        private suspend fun loadSessions() {
            try {
                val result = rpc.call("session.list", buildJsonObject { put("limit", SESSION_LIMIT) })
                val sessions = HermesJson.decodeFromJsonElement(SessionListResult.serializer(), result).sessions
                listed = true
                emit(SessionFeedEvent.Sessions(sessions))
            } catch (e: RpcException) {
                end(ConnectionIssue.Generic(e.message.orEmpty()))
            } catch (_: IllegalArgumentException) {
                end(ConnectionIssue.Generic("session.list: unexpected result"))
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

        private suspend fun emit(event: SessionFeedEvent) {
            if (!outcome.isCompleted) out.send(event)
        }

        private fun end(issue: ConnectionIssue) = end(LinkOutcome.Show(issue))

        /** The first end wins; later ones (a timeout racing a close) are dropped. */
        private fun end(result: LinkOutcome) {
            outcome.complete(result)
        }
    }

    private companion object {
        const val SESSION_LIMIT = 50

        fun durationText(millis: Long): String = if (millis % 1000 == 0L) "${millis / 1000} s" else "$millis ms"
    }
}
