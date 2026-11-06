package io.github.justcallmesu.aristotle.ui.stocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.serverEndpoint
import io.github.justcallmesu.aristotle.data.stocks.StocksApi
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.data.stocks.StocksResult
import io.github.justcallmesu.aristotle.data.stocks.StocksTransport
import io.github.justcallmesu.aristotle.data.stocks.TrackFilters
import io.github.justcallmesu.aristotle.data.stocks.TrackOptions
import io.github.justcallmesu.aristotle.data.stocks.TrackRecordBody
import io.github.justcallmesu.aristotle.data.stocks.TrackRecords
import io.github.justcallmesu.aristotle.data.stocks.VerdictRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the track record page shows (board "states"): one of these at any time. */
sealed interface TrackPageView {
    data object Loading : TrackPageView

    /** P14: the PC is out of reach; no cached copy is drawn. */
    data object Offline : TrackPageView

    data class Failed(val error: StocksError) : TrackPageView

    /** Nothing scored and nothing waiting: "Nothing to score yet". */
    data object NoHistory : TrackPageView

    /** Nothing scored yet, but [pending] verdicts wait for the 17:00 run: "The record starts after the close". */
    data class OnlyPending(val pending: Int) : TrackPageView

    /**
     * Scores exist. [records] are the filtered cards; [shown] of [total] scored verdicts match the filters; [noMatch]: the
     * filters match nothing. [scoredThrough] is the latest scored session (`YYYY-MM-DD`), [pending] the unscored count.
     */
    data class Content(
        val records: List<VerdictRecord>,
        val shown: Int,
        val total: Int,
        val noMatch: Boolean,
        val pending: Int,
        val scoredThrough: String?,
        val options: TrackOptions,
        val filters: TrackFilters,
    ) : TrackPageView
}

data class TrackUiState(
    val link: LinkState = LinkState.Connecting,
    val body: TrackRecordBody? = null,
    val problem: StocksError? = null,
    val filters: TrackFilters = TrackFilters(),
    val sheet: Boolean = false,
) {
    val reachable: Boolean get() = link.serverEndpoint != null

    fun view(): TrackPageView {
        if (!reachable) return TrackPageView.Offline
        val body = body ?: return problem?.let(TrackPageView::Failed) ?: TrackPageView.Loading
        if (body.summary.isEmpty()) return if (body.pending > 0) TrackPageView.OnlyPending(body.pending) else TrackPageView.NoHistory
        val kept = TrackRecords.filter(body.summary, filters)
        val records = TrackRecords.byVerdict(kept)
        return TrackPageView.Content(
            records = records,
            shown = TrackRecords.scored(kept),
            total = TrackRecords.scored(body.summary),
            noMatch = records.isEmpty(),
            pending = body.pending,
            scoredThrough = body.scoredThrough,
            options = TrackRecords.options(body.summary),
            filters = filters,
        )
    }
}

/**
 * The AI track record page (TR2, protocol §20.5). Read each time the page shows while the PC answers (and again when it
 * comes back); nothing is cached (P14), so an unreachable PC shows the offline state. The filters (model, trigger,
 * confidence) are the phone's: the server sends every group, and the page sums the ones that match.
 */
class TrackRecordViewModel(
    private val pc: PcCredentials,
    private val link: StateFlow<LinkState>,
    private val transport: StocksTransport,
    scope: CoroutineScope? = null,
    private val retryLink: () -> Unit = {},
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val state = MutableStateFlow(TrackUiState(link = link.value))
    val uiState: StateFlow<TrackUiState> = state.asStateFlow()

    private val visible = MutableStateFlow(false)

    init {
        work.launch { link.collect { l -> state.update { it.copy(link = l) } } }
        work.launch { combine(visible, link) { on, l -> on && l.serverEndpoint != null }.distinctUntilChanged().collect { on -> if (on) load() } }
    }

    fun pageVisible(on: Boolean) {
        visible.value = on
    }

    fun setModel(model: String?) = state.update { it.copy(filters = it.filters.copy(model = model)) }

    fun setTrigger(trigger: String?) = state.update { it.copy(filters = it.filters.copy(trigger = trigger)) }

    fun setConfidence(confidence: String?) = state.update { it.copy(filters = it.filters.copy(confidence = confidence)) }

    fun clearFilters() = state.update { it.copy(filters = TrackFilters()) }

    fun showSheet(on: Boolean) = state.update { it.copy(sheet = on) }

    /** "Retry now": a failed read again, or the link's own retry while the PC is out of reach. */
    fun retry() {
        if (!state.value.reachable) return retryLink()
        work.launch { load() }
    }

    private suspend fun load() {
        val endpoint = link.value.serverEndpoint ?: return
        val api = StocksApi { request -> transport.send(pc, endpoint, request) }
        when (val result = api.trackRecord()) {
            is StocksResult.Ok -> state.update { it.copy(body = result.value, problem = null) }
            is StocksResult.Failed -> state.update { it.copy(problem = result.error) }
        }
    }
}
