package io.github.justcallmesu.aristotle.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.AddressCheck
import io.github.justcallmesu.aristotle.data.Fingerprint
import io.github.justcallmesu.aristotle.data.HermesJson
import io.github.justcallmesu.aristotle.data.HttpClients
import io.github.justcallmesu.aristotle.data.InboundFrame
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.Profiles
import io.github.justcallmesu.aristotle.data.RelayEvent
import io.github.justcallmesu.aristotle.data.RelayRpc
import io.github.justcallmesu.aristotle.data.RelaySocket
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.RpcFrames
import io.github.justcallmesu.aristotle.data.RuntimeDescription
import io.github.justcallmesu.aristotle.data.ServerAddress
import io.github.justcallmesu.aristotle.data.ServerClient
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.SessionListResult
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface SessionsContent {
    data object Connecting : SessionsContent
    data class Loaded(val items: List<SessionListItem>) : SessionsContent
    data class Problem(val issue: ConnectionIssue) : SessionsContent
}

data class SessionsUiState(
    val address: String,
    val network: Network?,
    val profileName: String? = null,
    val content: SessionsContent = SessionsContent.Connecting,
)

/**
 * Read-only session list over the relay: `GET /v1/me`, open `/v1/ws`, wait for `gateway.ready`,
 * then `runtime.describe` (launch profile) and `session.list`. No automatic reconnect (T09).
 */
class SessionsViewModel(
    private val credentials: PcCredentials,
    private val httpClients: HttpClients,
    private val onRevoked: () -> Unit,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : ViewModel() {
    private val endpoint: ServerEndpoint? =
        (credentials.baseUrls.firstOrNull()?.let(ServerAddress::parse) as? AddressCheck.Ok)?.endpoint
    private val pin: ByteArray? = credentials.fingerprint?.let(Fingerprint::parse)

    private val state = MutableStateFlow(
        SessionsUiState(address = endpoint?.hostForUrl ?: credentials.baseUrls.firstOrNull().orEmpty(), network = endpoint?.network),
    )
    val uiState: StateFlow<SessionsUiState> = state.asStateFlow()

    private var connection: Job? = null

    init {
        connect()
    }

    fun retry() = connect()

    private fun connect() {
        connection?.cancel()
        state.update { it.copy(content = SessionsContent.Connecting) }
        connection = viewModelScope.launch { runConnection() }
    }

    private suspend fun runConnection() {
        val target = endpoint
        if (target == null || (target.network == Network.Lan && pin == null)) {
            show(ConnectionIssue.Generic("stored address unusable: ${credentials.baseUrls.firstOrNull().orEmpty()}"))
            return
        }
        val client = httpClients.forEndpoint(target, pin)
        LinkOutcomes.ofMe(ServerClient(client, target.baseUrl).me(credentials.token))?.let {
            handle(it)
            return
        }
        RelayLink(RelaySocket(client, target.baseUrl, credentials.token)).run()
    }

    private fun handle(outcome: LinkOutcome) = when (outcome) {
        LinkOutcome.Revoked -> onRevoked()
        is LinkOutcome.Show -> show(outcome.issue)
    }

    private fun show(issue: ConnectionIssue) = state.update { it.copy(content = SessionsContent.Problem(issue)) }

    /** One relay socket's lifetime: frames in, RPC calls out, ends on close or failure. */
    private inner class RelayLink(private val socket: RelaySocket) {
        private val rpc = RelayRpc(socket::send)

        @Volatile
        private var ended = false
        private var refresh: Job? = null
        private var linkJob: Job? = null

        suspend fun run() = coroutineScope {
            linkJob = coroutineContext.job
            val readyTimeout = launch {
                delay(READY_TIMEOUT_MILLIS)
                fail(ConnectionIssue.Generic("no gateway.ready within ${READY_TIMEOUT_MILLIS / 1000} s"))
            }
            socket.events().collect { event -> onEvent(event, readyTimeout) }
            coroutineContext.cancelChildren()
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
                    RpcFrames.GATEWAY_READY -> {
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
            state.update { it.copy(profileName = profile) }
            loadSessions()
        }

        private suspend fun loadSessions() {
            try {
                val result = rpc.call("session.list", buildJsonObject { put("limit", SESSION_LIMIT) })
                val sessions = HermesJson.decodeFromJsonElement(SessionListResult.serializer(), result).sessions
                if (!ended) state.update { it.copy(content = SessionsContent.Loaded(sessionListItems(sessions, now(), locale()))) }
            } catch (e: RpcException) {
                if (!ended) fail(ConnectionIssue.Generic(e.message.orEmpty()))
            } catch (_: IllegalArgumentException) {
                if (!ended) fail(ConnectionIssue.Generic("session.list: unexpected result"))
            }
        }

        /** `sessions.changed` arrives in bursts; refetch once things settle, and only once a list is shown. */
        private fun CoroutineScope.scheduleRefresh() {
            if (state.value.content !is SessionsContent.Loaded) return
            refresh?.cancel()
            refresh = launch {
                delay(REFRESH_DEBOUNCE_MILLIS)
                loadSessions()
            }
        }

        private fun end(outcome: LinkOutcome) {
            ended = true
            rpc.failAll("relay closed")
            handle(outcome)
        }

        private fun fail(issue: ConnectionIssue) {
            ended = true
            show(issue)
            linkJob?.cancel()
        }
    }

    private companion object {
        const val SESSION_LIMIT = 50
        const val READY_TIMEOUT_MILLIS = 20_000L
        const val REFRESH_DEBOUNCE_MILLIS = 1_000L
    }
}
