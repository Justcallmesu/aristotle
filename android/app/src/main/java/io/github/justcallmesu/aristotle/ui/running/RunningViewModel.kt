package io.github.justcallmesu.aristotle.ui.running

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.RelayLink
import io.github.justcallmesu.aristotle.data.RpcException
import io.github.justcallmesu.aristotle.data.RunningPoller
import io.github.justcallmesu.aristotle.data.RunningSnapshot
import io.github.justcallmesu.aristotle.data.RunningTimings
import io.github.justcallmesu.aristotle.data.RunningWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RunningUiState(
    val link: LinkState = LinkState.Connecting,
    /** The last read; null until the first arrives. Kept while offline or after a failed read. */
    val snapshot: RunningSnapshot? = null,
    /** `profiles.list` failed (link up): Hermes' reason. */
    val problem: String? = null,
    val refreshing: Boolean = false,
) {
    val online: Boolean get() = link is LinkState.Online
}

/**
 * The Running page (TY1, Q82 D1): running work across every profile, read by [RunningPoller] only while the page is
 * visible ([setVisible], from the screen's resume/pause) and online.
 */
class RunningViewModel(
    private val link: RelayLink,
    timings: RunningTimings = RunningTimings(),
    scope: CoroutineScope? = null,
    private val work: RunningWork = RunningWork(link),
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope
    private val state = MutableStateFlow(RunningUiState())
    val uiState: StateFlow<RunningUiState> = state.asStateFlow()
    private val poller = RunningPoller(link, timings, this.scope, ::read)

    init {
        this.scope.launch { link.state.collect { l -> state.update { it.copy(link = l, refreshing = it.refreshing && l is LinkState.Online) } } }
    }

    fun setVisible(shown: Boolean) = poller.setVisible(shown)

    /** Pull to refresh; offline it is a Retry of the link. */
    fun refresh() {
        if (!state.value.online) return link.retryNow()
        state.update { it.copy(refreshing = true) }
        poller.kick()
    }

    /** Retry after `profiles.list` failed. */
    fun retry() {
        state.update { it.copy(problem = null) }
        if (state.value.online) poller.kick() else link.retryNow()
    }

    private suspend fun read() {
        try {
            val snapshot = work.fetch()
            state.update { it.copy(snapshot = snapshot, problem = null, refreshing = false) }
        } catch (e: RpcException) {
            // A closed link is the link state's to show; only Hermes' own refusal is the page's.
            state.update { it.copy(problem = if (e.linkClosed) it.problem else e.message.orEmpty().ifEmpty { "profiles.list failed" }, refreshing = false) }
        } catch (_: IllegalArgumentException) {
            state.update { it.copy(problem = "profiles.list: unexpected result", refreshing = false) }
        }
    }
}
