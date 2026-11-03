package io.github.justcallmesu.aristotle.ui.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.ServerEndpoint
import io.github.justcallmesu.aristotle.data.SessionFeedEvent
import io.github.justcallmesu.aristotle.data.SessionRepository
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SessionsUiState(
    val link: LinkState = LinkState.Connecting,
    /** Where the link is, or the address it is about while offline. */
    val address: ServerEndpoint?,
    val profileName: String? = null,
    /** The last list fetched, kept while offline; null until the first one arrives. */
    val items: List<SessionListItem>? = null,
    /** When [items] was fetched, as HH:mm. */
    val listedAt: String? = null,
    /** `session.list` failed although the link is up. */
    val listProblem: String? = null,
)

/** UI state for the session list. The connection is the process-wide [RelayLink]; this only lists. */
class SessionsViewModel(
    pc: PcCredentials,
    private val link: RelayLink,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : ViewModel() {
    private val repository = SessionRepository(link)
    private val state = MutableStateFlow(SessionsUiState(address = pc.lastGood ?: pc.addresses.firstOrNull()))
    val uiState: StateFlow<SessionsUiState> = state.asStateFlow()

    private var feed: Job? = null

    init {
        viewModelScope.launch { link.state.collect(::onLink) }
        startFeed()
    }

    /** Retry now / Check again: the link tries at once; a failed list is fetched again. */
    fun retry() {
        link.retryNow()
        if (state.value.listProblem != null) startFeed()
    }

    /** "HH:mm" in the phone's zone, for "PC offline since …". */
    fun timeText(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(now().zone).format(DateTimeFormatter.ofPattern(TIME, locale()))

    private fun startFeed() {
        feed?.cancel()
        state.update { it.copy(listProblem = null) }
        feed = viewModelScope.launch { repository.feed().collect(::onFeed) }
    }

    private fun onLink(link: LinkState) = state.update {
        val address = when (link) {
            is LinkState.Online -> link.endpoint
            is LinkState.Offline -> link.address
            else -> null
        }
        it.copy(link = link, address = address ?: it.address)
    }

    private fun onFeed(event: SessionFeedEvent) {
        when (event) {
            is SessionFeedEvent.Profile -> state.update { it.copy(profileName = event.name, listProblem = null) }
            is SessionFeedEvent.Sessions -> {
                val at = now()
                state.update {
                    it.copy(
                        items = sessionListItems(event.sessions, at, locale()),
                        listedAt = at.format(DateTimeFormatter.ofPattern(TIME, locale())),
                        listProblem = null,
                    )
                }
            }
            is SessionFeedEvent.ListFailed -> state.update { it.copy(listProblem = event.details) }
        }
    }

    private companion object {
        const val TIME = "HH:mm"
    }
}
