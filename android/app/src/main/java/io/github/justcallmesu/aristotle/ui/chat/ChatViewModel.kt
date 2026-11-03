package io.github.justcallmesu.aristotle.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.ResumedSessions
import io.github.justcallmesu.aristotle.data.chat.ChatFeed
import io.github.justcallmesu.aristotle.data.chat.ChatFeedState
import io.github.justcallmesu.aristotle.data.chat.ChatState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(val feed: ChatFeedState, val link: LinkState)

/**
 * The chat screen's state: one [ChatFeed] for the session, run on [background] (frames are parsed and reduced
 * off the main thread, protocol §12), and the link state for the top bar.
 */
class ChatViewModel(
    sessionId: String,
    private val link: RelayLink,
    resumed: ResumedSessions,
    private val background: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val feed = ChatFeed(link, resumed, sessionId)
    private val state = MutableStateFlow(ChatUiState(ChatFeedState(ChatState(sessionId)), link.state.value))
    val uiState: StateFlow<ChatUiState> = state.asStateFlow()

    private var job: Job? = null

    init {
        viewModelScope.launch { link.state.collect { current -> state.update { it.copy(link = current) } } }
        start()
    }

    /**
     * Retry after a failed load or a refused reattach: the link tries now, and the session is subscribed again.
     * A loaded transcript stays on screen and catches up from where it is.
     */
    fun retry() {
        link.retryNow()
        start()
    }

    private fun start() {
        job?.cancel()
        val from = state.value.feed
        job = viewModelScope.launch { feed.states(from).flowOn(background).collect { next -> state.update { it.copy(feed = next) } } }
    }
}
