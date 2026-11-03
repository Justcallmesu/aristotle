package io.github.justcallmesu.aristotle.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.ConnectionIssue
import io.github.justcallmesu.aristotle.data.HttpClients
import io.github.justcallmesu.aristotle.data.HttpPcLink
import io.github.justcallmesu.aristotle.data.LinkOutcome
import io.github.justcallmesu.aristotle.data.Network
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.PcTarget
import io.github.justcallmesu.aristotle.data.SessionFeedEvent
import io.github.justcallmesu.aristotle.data.SessionRepository
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

/** UI state for the session list; the connection itself lives in [SessionRepository]. */
class SessionsViewModel(
    private val credentials: PcCredentials,
    httpClients: HttpClients,
    private val onRevoked: () -> Unit,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : ViewModel() {
    private val target = PcTarget.of(credentials)
    private val repository = target?.let { SessionRepository(HttpPcLink(it, httpClients, credentials.token)) }

    private val state = MutableStateFlow(
        SessionsUiState(
            address = target?.endpoint?.hostForUrl ?: credentials.baseUrls.firstOrNull().orEmpty(),
            network = target?.endpoint?.network,
        ),
    )
    val uiState: StateFlow<SessionsUiState> = state.asStateFlow()

    private var connection: Job? = null

    init {
        connect()
    }

    fun retry() = connect()

    private fun connect() {
        connection?.cancel()
        val feed = repository
        if (feed == null) {
            show(ConnectionIssue.Generic("stored address unusable: ${credentials.baseUrls.firstOrNull().orEmpty()}"))
            return
        }
        state.update { it.copy(content = SessionsContent.Connecting) }
        connection = viewModelScope.launch { feed.feed().collect(::onFeed) }
    }

    private fun onFeed(event: SessionFeedEvent) {
        when (event) {
            is SessionFeedEvent.Profile -> state.update { it.copy(profileName = event.name) }
            is SessionFeedEvent.Sessions -> state.update {
                it.copy(content = SessionsContent.Loaded(sessionListItems(event.sessions, now(), locale())))
            }
            is SessionFeedEvent.Ended -> when (val outcome = event.outcome) {
                LinkOutcome.Revoked -> onRevoked()
                is LinkOutcome.Show -> show(outcome.issue)
            }
        }
    }

    private fun show(issue: ConnectionIssue) = state.update { it.copy(content = SessionsContent.Problem(issue)) }
}
