package io.github.justcallmesu.aristotle.ui.stocks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.justcallmesu.aristotle.data.ConnectionProblem
import io.github.justcallmesu.aristotle.data.LinkState
import io.github.justcallmesu.aristotle.data.PcCredentials
import io.github.justcallmesu.aristotle.data.serverEndpoint
import io.github.justcallmesu.aristotle.data.stocks.AlertFeed
import io.github.justcallmesu.aristotle.data.stocks.AutoRuns
import io.github.justcallmesu.aristotle.data.stocks.JobsWithRuns
import io.github.justcallmesu.aristotle.data.stocks.StockAlertStore
import io.github.justcallmesu.aristotle.data.stocks.StocksApi
import io.github.justcallmesu.aristotle.data.stocks.StocksError
import io.github.justcallmesu.aristotle.data.stocks.StocksResult
import io.github.justcallmesu.aristotle.data.stocks.StocksTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The alerts page and Settings › Stocks › Alerts (TBA2). [feed] is the process-wide list the long-poll fills
 * ([StockAlertStore]); [jobs] the PC's jobs with today's automatic analyses (§20.2, §20.4).
 */
data class AlertsUiState(
    val link: LinkState = LinkState.Connecting,
    val feed: AlertFeed = AlertFeed(),
    val jobs: JobsWithRuns? = null,
    val listProblem: StocksError? = null,
    val jobsProblem: StocksError? = null,
    val saving: Boolean = false,
    val saveProblem: StocksError? = null,
) {
    val reachable: Boolean get() = link.serverEndpoint != null

    /** The `alerts` job's switch (null until read). */
    val alertsOn: Boolean? get() = jobs?.job(JobsWithRuns.ALERTS)?.on

    /** Today's automatic analyses: the jobs read, else what the last long-poll carried. */
    val autoRuns: AutoRuns? get() = jobs?.autoRuns ?: feed.autoRuns
}

class StockAlertsViewModel(
    private val pc: PcCredentials,
    private val link: StateFlow<LinkState>,
    private val transport: StocksTransport,
    private val store: StockAlertStore,
    scope: CoroutineScope? = null,
) : ViewModel() {
    private val work: CoroutineScope = scope ?: viewModelScope
    private val state = MutableStateFlow(AlertsUiState(link = link.value, feed = store.feed.value))
    val uiState: StateFlow<AlertsUiState> = state.asStateFlow()

    private val pageVisible = MutableStateFlow(false)
    private val settingsVisible = MutableStateFlow(false)

    init {
        work.launch { link.collect { l -> state.update { it.copy(link = l) } } }
        work.launch {
            store.feed.collect { feed ->
                state.update { it.copy(feed = feed) }
                // On the page, what arrives is seen at once.
                if (pageVisible.value && feed.unseen > 0) store.markSeen()
            }
        }
        work.launch { shownAndReachable(pageVisible).collect { on -> if (on) loadList() } }
        work.launch { shownAndReachable(settingsVisible).collect { on -> if (on) loadJobs() } }
    }

    private fun shownAndReachable(shown: StateFlow<Boolean>) = combine(shown, link) { on, l -> on && l.serverEndpoint != null }.distinctUntilChanged()

    /** The alerts page is on screen: everything listed counts as seen; the newest 50 are read from the PC. */
    fun pageVisible(visible: Boolean) {
        pageVisible.value = visible
        if (visible) store.markSeen()
    }

    fun settingsVisible(visible: Boolean) {
        settingsVisible.value = visible
    }

    /** The PC's `alerts` job on or off (§20.2: off = no alert and no automatic analysis). */
    fun setAlertsOn(on: Boolean) {
        if (state.value.saving) return
        state.update { it.copy(saving = true, saveProblem = null) }
        work.launch {
            when (val result = api()?.putJobOn(JobsWithRuns.ALERTS, on) ?: offline()) {
                is StocksResult.Ok -> state.update { s ->
                    val jobs = s.jobs?.let { j -> j.copy(jobs = j.jobs.map { if (it.id == result.value.id) result.value else it }) }
                    s.copy(saving = false, jobs = jobs)
                }
                is StocksResult.Failed -> state.update { it.copy(saving = false, saveProblem = result.error) }
            }
        }
    }

    fun saveProblemShown() = state.update { it.copy(saveProblem = null) }

    private suspend fun loadList() {
        when (val result = api()?.alerts(after = null) ?: return) {
            is StocksResult.Ok -> {
                store.list(result.value)
                store.markSeen()
                state.update { it.copy(listProblem = null) }
            }
            is StocksResult.Failed -> state.update { it.copy(listProblem = result.error) }
        }
    }

    private suspend fun loadJobs() {
        when (val result = api()?.jobsWithRuns() ?: return) {
            is StocksResult.Ok -> state.update { it.copy(jobs = result.value, jobsProblem = null) }
            is StocksResult.Failed -> state.update { it.copy(jobsProblem = result.error) }
        }
    }

    private fun api(): StocksApi? {
        val endpoint = link.value.serverEndpoint ?: return null
        return StocksApi { request -> transport.send(pc, endpoint, request) }
    }

    private fun offline(): StocksResult.Failed = StocksResult.Failed(StocksError.Unreachable(ConnectionProblem.Unreachable))
}
